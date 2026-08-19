package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.RetrievalProperties;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.error.EmbeddingException;
import com.seuprojeto.backend.repository.ChunkMatch;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

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

    private EmbeddingService embeddingService;
    private GenerationService generationService;
    private KnowledgeChunkRepository repository;
    private ChatService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        generationService = mock(GenerationService.class);
        repository = mock(KnowledgeChunkRepository.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[768]);
        service = new ChatService(embeddingService, generationService, repository,
                new RetrievalProperties(5, 0.8, 30));
    }

    @Test
    void answer_relevantChunksFound_returnsGroundedAnswerWithSources() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "Autor de Exponential Organizations.", 0.166),
                match(3L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.30)));
        when(generationService.generate(anyString(), anyString()))
                .thenReturn("Salim Ismail fala às 09h10.");

        ChatResponse response = service.answer("Quem fala sobre tecnologias exponenciais?");

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

        service.answer("Quem é Salim?");

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

        ChatResponse response = service.answer("Qual é a capital da Mongólia?");

        assertThat(response.answer()).isEqualTo(ChatService.NO_CONTEXT_ANSWER);
        assertThat(response.sources()).isEmpty();
        // The point of the short-circuit: no tokens spent, no chance to hallucinate.
        verify(generationService, never()).generate(anyString(), anyString());
    }

    @Test
    void answer_emptyCorpus_admitsIgnoranceWithoutCallingTheModel() {
        when(repository.findNearest(any(), any())).thenReturn(List.of());

        assertThat(service.answer("Quem fala sobre IA?").answer()).isEqualTo(ChatService.NO_CONTEXT_ANSWER);
        verify(generationService, never()).generate(anyString(), anyString());
    }

    @Test
    void answer_respectsConfiguredTopK() {
        when(repository.findNearest(any(), any())).thenReturn(List.of());
        service = new ChatService(embeddingService, generationService, repository,
                new RetrievalProperties(3, 0.8, 30));

        service.answer("Quem fala sobre IA?");

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

        ChatResponse response = service.answer("Quais artigos estão disponíveis?");

        assertThat(response.sources()).hasSize(3);
        verify(repository, never()).findNearest(any(), any());
    }

    @Test
    void answer_enumerationExceedingTheCap_isTruncatedToTheCapNotSilentlyOverflowed() {
        // Repository is asked for cap+1 so truncation is detectable; the answer uses cap items.
        service = new ChatService(embeddingService, generationService, repository,
                new RetrievalProperties(2, 0.8, 2));
        when(repository.findNearestByType(anyString(), any(), any())).thenReturn(List.of(
                match(1L, "artigo", "A", "a", 0.1),
                match(2L, "artigo", "B", "b", 0.2),
                match(3L, "artigo", "C", "c", 0.3)));
        when(generationService.generate(anyString(), anyString())).thenReturn("dois artigos");

        ChatResponse response = service.answer("Quais artigos existem?");

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

        ChatResponse response = service.answer("Quais matérias existem?");

        assertThat(response.sources()).hasSize(1);
        verify(repository).findNearest(any(), any());
    }

    @Test
    void answer_lookupQuestion_usesSimilaritySearchNotTypeFilter() {
        when(repository.findNearest(any(), any())).thenReturn(List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166)));
        when(generationService.generate(anyString(), anyString())).thenReturn("Resposta.");

        service.answer("Quem é Salim Ismail?");

        verify(repository, never()).findNearestByType(anyString(), any(), any());
    }

    @Test
    void answer_embeddingFails_propagatesInsteadOfDegrading() {
        when(embeddingService.embed(anyString())).thenThrow(new EmbeddingException("Gemini is down"));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.answer("Quem fala sobre IA?"));

        verify(generationService, never()).generate(anyString(), anyString());
    }

    @Test
    void answer_blankQuestion_throwsIllegalArgumentException() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.answer("   "));

        verify(embeddingService, never()).embed(anyString());
    }
}
