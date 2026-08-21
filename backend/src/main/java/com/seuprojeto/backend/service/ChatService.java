package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.ChatMemoryProperties;
import com.seuprojeto.backend.config.RetrievalProperties;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.dto.SourceRef;
import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.model.RetrievalEndpoint;
import com.seuprojeto.backend.repository.ChunkMatch;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * RAG question answering: embed the question, retrieve the nearest chunks, and answer strictly
 * from them.
 *
 * <p>When nothing clears {@code rag.max-distance} the model is never called: the endpoint says
 * so directly. That is cheaper, deterministic, and removes any opportunity to hallucinate an
 * answer the corpus cannot support.
 *
 * <p>Questions that ask to list a whole category ("quais artigos existem?") take a different
 * path — see {@link EnumerationIntent}. Similarity ranking cannot promise it saw every article,
 * so those questions retrieve by type instead and skip the distance filter: the user asked for
 * all of them, not for the closest few.
 *
 * <p>The conversation is remembered server-side per user ({@link ConversationMemory}) and used in
 * two places: it expands the text that gets embedded, so a follow-up still retrieves the right
 * chunks, and it goes into the prompt so the model can tell what "ele" refers to. It never
 * becomes evidence — the answer is still grounded only in retrieved chunks.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String NO_CONTEXT_ANSWER =
            "Não encontrei essa informação no material do evento.";

    private final EmbeddingService embeddingService;
    private final GenerationService generationService;
    private final KnowledgeChunkRepository repository;
    private final RetrievalProperties retrievalProperties;
    private final ConversationMemory conversationMemory;
    private final ChatMemoryProperties chatMemoryProperties;
    private final RetrievalLogger retrievalLogger;

    public ChatService(EmbeddingService embeddingService,
                       GenerationService generationService,
                       KnowledgeChunkRepository repository,
                       RetrievalProperties retrievalProperties,
                       ConversationMemory conversationMemory,
                       ChatMemoryProperties chatMemoryProperties,
                       RetrievalLogger retrievalLogger) {
        this.embeddingService = embeddingService;
        this.generationService = generationService;
        this.repository = repository;
        this.retrievalProperties = retrievalProperties;
        this.conversationMemory = conversationMemory;
        this.chatMemoryProperties = chatMemoryProperties;
        this.retrievalLogger = retrievalLogger;
    }

    /**
     * @param userId   whose conversation this is, or {@code null} for an unidentified caller —
     *                 who gets a correct answer with no memory, in either direction
     * @param question the question, in Portuguese
     */
    public ChatResponse answer(String userId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("A pergunta não pode ser vazia");
        }

        List<ConversationMessage> history = conversationMemory.recall(userId);

        String retrievalText = RetrievalQuery.expand(
                question, history, chatMemoryProperties.retrievalContextTurns());
        float[] queryVector = embeddingService.embed(retrievalText);
        // Intent is read from the raw question, never from the expanded text: an earlier
        // "quais artigos existem?" would otherwise turn every later question into a listing.
        List<ChunkMatch> relevant = retrieve(question, queryVector);

        if (relevant.isEmpty()) {
            log.info("No chunk within distance {} for question of {} chars; answering without the model",
                    retrievalProperties.maxDistance(), question.length());
            // Remembered like any other turn: the user saw this reply, so the next question is
            // asked in the light of it, and the model is not left to assume it answered.
            conversationMemory.remember(userId, question, NO_CONTEXT_ANSWER);
            return new ChatResponse(NO_CONTEXT_ANSWER, List.of());
        }

        String answer = generationService.generate(
                PromptAssembler.systemInstruction(),
                PromptAssembler.userPrompt(question, history, relevant));

        log.info("Answered a {}-char question from {} chunks and {} remembered turn(s) (closest distance {})",
                question.length(), relevant.size(), history.size(), relevant.getFirst().getDistance());

        // Only after a usable answer exists. A failed generation throws above and leaves the
        // history untouched rather than storing a question that was never answered.
        conversationMemory.remember(userId, question, answer);

        // Best-effort analytics, off the request thread and unable to fail this call. Logged here
        // rather than in the controller so it records exactly the chunks that reached the model —
        // which is also exactly what the response reports as sources.
        retrievalLogger.record(RetrievalEndpoint.CHAT, relevant);

        return new ChatResponse(answer, relevant.stream().map(SourceRef::from).toList());
    }

    private List<ChunkMatch> retrieve(String question, float[] queryVector) {
        Optional<String> enumerationType = EnumerationIntent.detect(question);

        if (enumerationType.isPresent()) {
            int cap = retrievalProperties.maxEnumeration();
            // Fetch one past the cap so a truncated listing is detectable. Presenting a capped
            // list as the whole category is the exact failure this code path exists to prevent.
            List<ChunkMatch> everyChunkOfType = repository.findNearestByTypes(
                    List.of(enumerationType.get()), queryVector, Limit.of(cap + 1));
            if (everyChunkOfType.size() > cap) {
                log.warn("Enumeration of type {} exceeds rag.max-enumeration ({}); the answer will be "
                        + "incomplete. Raise the cap or add pagination.", enumerationType.get(), cap);
                everyChunkOfType = everyChunkOfType.subList(0, cap);
            }
            if (!everyChunkOfType.isEmpty()) {
                log.info("Enumeration question detected (type={}); returning all {} chunks of that type",
                        enumerationType.get(), everyChunkOfType.size());
                return everyChunkOfType;
            }
            // Nothing of that type stored — fall through rather than wrongly claiming ignorance.
            log.info("Enumeration type {} matched no chunks; falling back to similarity search",
                    enumerationType.get());
        }

        return repository.findNearest(queryVector, Limit.of(retrievalProperties.topK()))
                .stream()
                .filter(match -> match.getDistance() <= retrievalProperties.maxDistance())
                .toList();
    }
}
