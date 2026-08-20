package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.repository.ChunkMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PromptAssemblerTest {

    @Test
    void systemInstruction_forbidsOutsideKnowledgeAndDemandsAnAdmissionOfIgnorance() {
        String instruction = PromptAssembler.systemInstruction();

        // These two rules are the whole defence against hallucination.
        assertThat(instruction)
                .contains("SOMENTE com base no CONTEXTO")
                .contains("Não use conhecimento próprio")
                .contains("não encontrou essa informação")
                .contains("Nunca invente")
                .contains("português do Brasil");
    }

    @Test
    void userPrompt_includesEveryRetrievedChunkAndTheQuestion() {
        String prompt = PromptAssembler.userPrompt("Quem fala sobre IA?", List.of(), List.of(
                match(1L, "palestrante", "Salim Ismail", "Autor de Exponential Organizations.", 0.12),
                match(2L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.30)));

        assertThat(prompt)
                .contains("[1] (palestrante) Salim Ismail — Autor de Exponential Organizations.")
                .contains("[2] (agenda) 09h10 às 10h00 — Tecnologias Exponenciais")
                .contains("PERGUNTA: Quem fala sobre IA?");
    }

    @Test
    void userPrompt_isByteIdenticalForTheSameInput() {
        List<ChunkMatch> matches = List.of(match(1L, "evento", "Geral", "Evento sobre IA", 0.2));

        assertThat(PromptAssembler.userPrompt("Qual o tema?", List.of(), matches))
                .isEqualTo(PromptAssembler.userPrompt("Qual o tema?", List.of(), matches));
    }

    @Test
    void userPrompt_nullTitleRef_omitsTheSeparator() {
        String prompt = PromptAssembler.userPrompt("Qual o tema?", List.of(), List.of(
                match(1L, "agenda", null, "Coffee Break", 0.2)));

        assertThat(prompt).contains("[1] (agenda) Coffee Break").doesNotContain("— Coffee Break");
    }

    @Test
    void userPrompt_noMatches_throwsBecauseTheCallerMustHandleThat() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PromptAssembler.userPrompt("Qual o tema?", List.of(), List.of()))
                .withMessageContaining("no context");
    }

    @Test
    void userPrompt_blankQuestion_throws() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PromptAssembler.userPrompt("  ", List.of(),
                        List.of(match(1L, "evento", "Geral", "Evento sobre IA", 0.2))));
    }

    @Test
    void systemInstruction_forbidsTreatingHistoryAsASourceOfFacts() {
        // Without this rule the model happily "remembers" a fact it made up two turns ago and
        // repeats it as if the corpus had said it.
        assertThat(PromptAssembler.systemInstruction())
                .contains("HISTÓRICO")
                .contains("NÃO é fonte de fatos");
    }

    @Test
    void userPrompt_emptyHistory_omitsTheHistorySectionEntirely() {
        String prompt = PromptAssembler.userPrompt("Qual o tema?", List.of(),
                List.of(match(1L, "evento", "Geral", "Evento sobre IA", 0.2)));

        assertThat(prompt).startsWith("CONTEXTO:").doesNotContain("HISTÓRICO");
    }

    @Test
    void userPrompt_withHistory_labelsEachTurnAndKeepsTheQuestionLast() {
        String prompt = PromptAssembler.userPrompt("E ele fala a que horas?",
                List.of(new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?"),
                        new ConversationMessage(ChatRole.ASSISTANT, "Fundador da Singularity.")),
                List.of(match(1L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.2)));

        assertThat(prompt)
                .containsSubsequence(
                        "HISTÓRICO",
                        "Usuário: Quem é Salim Ismail?",
                        "Assistente: Fundador da Singularity.",
                        "CONTEXTO:",
                        "PERGUNTA: E ele fala a que horas?")
                .contains("não é fonte de fatos");
    }

    static ChunkMatch match(Long id, String type, String titleRef, String content, double distance) {
        return new ChunkMatch() {
            public Long getId() { return id; }
            public String getType() { return type; }
            public String getTitleRef() { return titleRef; }
            public String getContent() { return content; }
            public double getDistance() { return distance; }
        };
    }
}
