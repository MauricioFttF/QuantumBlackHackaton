package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.ChatMemoryProperties;
import com.seuprojeto.backend.config.RetrievalProperties;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.error.EmbeddingException;
import com.seuprojeto.backend.error.GenerationException;
import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.model.RetrievalEndpoint;
import com.seuprojeto.backend.repository.ChunkMatch;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import java.time.Duration;
import java.util.List;

import static com.seuprojeto.backend.service.PromptAssemblerTest.match;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final String USER = "user-42";
    private static final ChatMemoryProperties MEMORY_CONFIG =
            new ChatMemoryProperties(true, Duration.ofHours(1), 6, 2, Duration.ofMinutes(15));

    private EmbeddingService embeddingService;
    private GenerationService generationService;
    private KnowledgeChunkRepository repository;
    private ConversationMemory conversationMemory;
    private RetrievalLogger retrievalLogger;
    private ChatService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        generationService = mock(GenerationService.class);
        repository = mock(KnowledgeChunkRepository.class);
        conversationMemory = mock(ConversationMemory.class);
        retrievalLogger = mock(RetrievalLogger.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[768]);
        when(conversationMemory.recall(any())).thenReturn(List.of());
        service = newService(new RetrievalProperties(5, 0.8, 30), MEMORY_CONFIG);
    }

    private ChatService newService(RetrievalProperties retrieval, ChatMemoryProperties memory) {
        return new ChatService(embeddingService, generationService, repository, retrieval,
                conversationMemory, memory, retrievalLogger);
    }

    @Test
    void answer_relevantChunksFound_returnsGroundedAnswerWithSources() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "Autor de Exponential Organizations.", 0.166),
                match(3L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.30)));
        when(generationService.generate(anyString(), anyString()))
                .thenReturn("Salim Ismail fala às 09h10.");

        ChatResponse response = service.answer(USER, "Quem fala sobre tecnologias exponenciais?");

        assertThat(response.answer()).isEqualTo("Salim Ismail fala às 09h10.");
        assertThat(response.sources()).hasSize(2);
        assertThat(response.sources().getFirst().id()).isEqualTo(7L);
        assertThat(response.sources().getFirst().titleRef()).isEqualTo("Salim Ismail");
        // similarity = 1 - distance, rounded to 3 decimals
        assertThat(response.sources().getFirst().score()).isEqualTo(0.834);
    }

    @Test
    void answer_passesRetrievedContextToTheModel() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "Autor de Exponential Organizations.", 0.166)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Resposta.");

        service.answer(USER, "Quem é Salim?");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(generationService).generate(systemPrompt.capture(), userPrompt.capture());

        assertThat(systemPrompt.getValue()).contains("SOMENTE com base no CONTEXTO");
        assertThat(userPrompt.getValue())
                .contains("Autor de Exponential Organizations.")
                .contains("PERGUNTA: Quem é Salim?");
    }

    @Test
    void answer_everythingBeyondMaxDistance_admitsIgnoranceWithoutCallingTheModel() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(1L, "evento", "Geral", "Evento sobre IA", 0.95)));

        ChatResponse response = service.answer(USER, "Qual é a capital da Mongólia?");

        assertThat(response.answer()).isEqualTo(ChatService.NO_CONTEXT_ANSWER);
        assertThat(response.sources()).isEmpty();
        // The point of the short-circuit: no tokens spent, no chance to hallucinate.
        verify(generationService, never()).generate(anyString(), anyString());
    }

    @Test
    void answer_emptyCorpus_admitsIgnoranceWithoutCallingTheModel() {
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        assertThat(service.answer(USER, "Quem fala sobre IA?").answer()).isEqualTo(ChatService.NO_CONTEXT_ANSWER);
        verify(generationService, never()).generate(anyString(), anyString());
    }

    @Test
    void answer_respectsConfiguredTopK() {
        when(repository.findNearest(any(), any())).thenReturn(List.of());
        service = newService(new RetrievalProperties(3, 0.8, 30), MEMORY_CONFIG);

        service.answer(USER, "Quem fala sobre IA?");

        verify(repository).findNearest(any(), org.mockito.ArgumentMatchers.eq(Limit.of(3)));
    }

    @Test
    void answer_enumerationQuestion_returnsEveryChunkOfThatTypeIgnoringDistanceCutoff() {
        // "quais artigos" -> all articles, even ones further away than rag.max-distance,
        // because the user asked for the whole category rather than the closest few.
        when(repository.findNearestByType(org.mockito.ArgumentMatchers.eq("artigo"), any(), any()))
                .thenReturn(List.of(
                        match(1L, "artigo", "Artigo A", "conteudo A", 0.42),
                        match(2L, "artigo", "Artigo B", "conteudo B", 0.91),
                        match(3L, "artigo", "Artigo C", "conteudo C", 0.95)));
        when(generationService.generate(anyString(), anyString())).thenReturn("São três artigos.");

        ChatResponse response = service.answer(USER, "Quais artigos estão disponíveis?");

        assertThat(response.sources()).hasSize(3);
        verify(repository, never()).findNearest(any(), any());
    }

    @Test
    void answer_enumerationExceedingTheCap_isTruncatedToTheCapNotSilentlyOverflowed() {
        // Repository is asked for cap+1 so truncation is detectable; the answer uses cap items.
        service = newService(new RetrievalProperties(2, 0.8, 2), MEMORY_CONFIG);
        when(repository.findNearestByType(anyString(), any(), any())).thenReturn(List.of(
                match(1L, "artigo", "A", "a", 0.1),
                match(2L, "artigo", "B", "b", 0.2),
                match(3L, "artigo", "C", "c", 0.3)));
        when(generationService.generate(anyString(), anyString())).thenReturn("dois artigos");

        ChatResponse response = service.answer(USER, "Quais artigos existem?");

        assertThat(response.sources()).hasSize(2);
        verify(repository).findNearestByType(anyString(), any(),
                org.mockito.ArgumentMatchers.eq(Limit.of(3)));
    }

    @Test
    void answer_enumerationTypeHasNoChunks_fallsBackToSimilaritySearch() {
        when(repository.findNearestByType(anyString(), any(), any())).thenReturn(List.of());
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(9L, "evento", "Informações Gerais", "Evento sobre IA", 0.2)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Resposta.");

        ChatResponse response = service.answer(USER, "Quais matérias existem?");

        assertThat(response.sources()).hasSize(1);
        verify(repository).findNearest(any(), any());
    }

    @Test
    void answer_lookupQuestion_usesSimilaritySearchNotTypeFilter() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Resposta.");

        service.answer(USER, "Quem é Salim Ismail?");

        verify(repository, never()).findNearestByType(anyString(), any(), any());
    }

    @Test
    void answer_embeddingFails_propagatesInsteadOfDegrading() {
        when(embeddingService.embed(anyString())).thenThrow(new EmbeddingException("Gemini is down"));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.answer(USER, "Quem fala sobre IA?"));

        verify(generationService, never()).generate(anyString(), anyString());
    }

    @Test
    void answer_followUpQuestion_embedsTheEarlierQuestionTooSoRetrievalHasASubject() {
        when(conversationMemory.recall(USER)).thenReturn(List.of(
                new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?"),
                new ConversationMessage(ChatRole.ASSISTANT, "Fundador da Singularity University.")));
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(3L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.2)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Às 09h10.");

        service.answer(USER, "E ele fala a que horas?");

        ArgumentCaptor<String> embedded = ArgumentCaptor.forClass(String.class);
        verify(embeddingService).embed(embedded.capture());
        assertThat(embedded.getValue()).isEqualTo("Quem é Salim Ismail? E ele fala a que horas?");
    }

    @Test
    void answer_withoutHistory_embedsTheQuestionExactlyAsAsked() {
        // Regression guard: a first question must behave as it did before memory existed.
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        service.answer(USER, "Quem é Salim Ismail?");

        verify(embeddingService).embed("Quem é Salim Ismail?");
    }

    @Test
    void answer_historyPresent_reachesTheModelAsHistoryNotAsContext() {
        when(conversationMemory.recall(USER)).thenReturn(List.of(
                new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?")));
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(3L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.2)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Às 09h10.");

        service.answer(USER, "E a que horas?");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(generationService).generate(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .containsSubsequence("HISTÓRICO", "Usuário: Quem é Salim Ismail?", "CONTEXTO:");
    }

    @Test
    void answer_enumerationIntent_isReadFromTheRawQuestionNotTheExpandedText() {
        // An earlier "quais artigos existem?" must not turn every later question into a listing.
        when(conversationMemory.recall(USER)).thenReturn(List.of(
                new ConversationMessage(ChatRole.USER, "Quais artigos existem?")));
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.2)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Resposta.");

        service.answer(USER, "Quem é Salim Ismail?");

        verify(repository, never()).findNearestByType(anyString(), any(), any());
    }

    @Test
    void answer_successfulAnswer_isRemembered() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Fundador da Singularity.");

        service.answer(USER, "Quem é Salim Ismail?");

        verify(conversationMemory).remember(USER, "Quem é Salim Ismail?", "Fundador da Singularity.");
    }

    @Test
    void answer_refusalForLackOfContext_isRememberedLikeAnyOtherTurn() {
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        service.answer(USER, "Qual é a capital da Mongólia?");

        verify(conversationMemory).remember(USER, "Qual é a capital da Mongólia?",
                ChatService.NO_CONTEXT_ANSWER);
    }

    @Test
    void answer_generationFails_remembersNothing() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166)));
        when(generationService.generate(anyString(), anyString()))
                .thenThrow(new GenerationException("Gemini is down"));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.answer(USER, "Quem é Salim Ismail?"));

        // Storing a question whose answer never arrived would put a hole in the next prompt.
        verify(conversationMemory, never()).remember(anyString(), anyString(), anyString());
    }

    @Test
    void answer_retrievalContextTurnsDisabled_leavesTheQueryAlone() {
        service = newService(new RetrievalProperties(5, 0.8, 30),
                new ChatMemoryProperties(true, Duration.ofHours(1), 6, 0, Duration.ofMinutes(15)));
        when(conversationMemory.recall(USER)).thenReturn(List.of(
                new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?")));
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        service.answer(USER, "E a que horas?");

        verify(embeddingService).embed("E a que horas?");
    }

    @Test
    void answer_selfContainedQuestionMidConversation_isNotBlurredByHistory() {
        // Expanding a question that already has its own subject moved retrieval onto the previous
        // topic and produced a refusal against the real corpus. Guard against the regression.
        when(conversationMemory.recall(USER)).thenReturn(List.of(
                new ConversationMessage(ChatRole.USER, "Quem fala sobre tecnologias exponenciais?")));
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        service.answer(USER, "Onde e quando acontece o evento?");

        verify(embeddingService).embed("Onde e quando acontece o evento?");
    }

    @Test
    void answer_successfulAnswer_recordsWhatWasSentAsContext() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Resposta.");

        service.answer(USER, "Quem é Salim Ismail?");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.seuprojeto.backend.repository.ChunkMatch>> logged =
                ArgumentCaptor.forClass(List.class);
        verify(retrievalLogger).record(
                org.mockito.ArgumentMatchers.eq(RetrievalEndpoint.CHAT), logged.capture());
        assertThat(logged.getValue()).extracting(
                        com.seuprojeto.backend.repository.ChunkMatch::getId)
                .containsExactly(7L);
    }

    @Test
    void answer_noContext_recordsNothing_becauseNothingWasRetrieved() {
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        service.answer(USER, "Qual é a capital da Mongólia?");

        verify(retrievalLogger, never()).record(any(), any());
    }

    @Test
    void answer_generationFails_recordsNothing() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166)));
        when(generationService.generate(anyString(), anyString()))
                .thenThrow(new GenerationException("Gemini is down"));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.answer(USER, "Quem é Salim Ismail?"));

        verify(retrievalLogger, never()).record(any(), any());
    }

    @Test
    void answer_blankQuestion_throwsIllegalArgumentException() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.answer(USER, "   "));

        verify(embeddingService, never()).embed(anyString());
        verify(conversationMemory, never()).recall(anyString());
    }
}
