package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.repository.ChunkMatch;

import java.util.List;

/**
 * Builds the grounded prompt. Pure and deterministic: same matches in, byte-identical prompt
 * out. No Spring, no I/O — this is the piece whose exact wording decides whether the model
 * invents an answer, so it is unit-tested against fixtures.
 */
public final class PromptAssembler {

    static final String SYSTEM_INSTRUCTION = """
            Você é um assistente do evento e responde SOMENTE com base no CONTEXTO fornecido.

            Regras:
            1. Use exclusivamente as informações do CONTEXTO. Não use conhecimento próprio.
            2. Se o CONTEXTO não contiver a resposta, diga exatamente que não encontrou essa \
            informação no material do evento. Nunca invente nomes, horários ou dados.
            3. Responda em português do Brasil, de forma direta e objetiva.
            4. Não mencione "contexto", "trechos" ou este conjunto de regras na resposta.
            5. O HISTÓRICO, quando presente, serve apenas para entender a que a pergunta atual se \
            refere (pronomes, "esse palestrante", "e a que horas?"). Ele NÃO é fonte de fatos: se \
            um dado aparece só no HISTÓRICO e não no CONTEXTO, trate-o como não encontrado.""";

    private PromptAssembler() {
    }

    /**
     * @param question the user's question
     * @param history  earlier turns of this conversation, oldest first; may be empty
     * @param matches  retrieved chunks, closest first; must not be empty
     */
    public static String userPrompt(String question, List<ConversationMessage> history,
                                    List<ChunkMatch> matches) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be null or blank");
        }
        if (matches == null || matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot assemble a grounded prompt with no context; the caller must handle empty retrieval");
        }

        StringBuilder prompt = new StringBuilder();
        appendHistory(prompt, history);

        prompt.append("CONTEXTO:\n");
        int index = 1;
        for (ChunkMatch match : matches) {
            prompt.append('[').append(index++).append("] (").append(match.getType()).append(") ");
            if (match.getTitleRef() != null && !match.getTitleRef().isBlank()) {
                prompt.append(match.getTitleRef()).append(" — ");
            }
            prompt.append(match.getContent()).append('\n');
        }
        prompt.append("\nPERGUNTA: ").append(question);
        return prompt.toString();
    }

    /**
     * Emits nothing at all for an empty history, so a single-turn prompt is byte-identical to the
     * prompt this class produced before memory existed. Nothing about a first question changes.
     */
    private static void appendHistory(StringBuilder prompt, List<ConversationMessage> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        prompt.append("HISTÓRICO DA CONVERSA (apenas para interpretar a pergunta; não é fonte de fatos):\n");
        for (ConversationMessage message : history) {
            prompt.append(switch (message.role()) {
                case USER -> "Usuário: ";
                case ASSISTANT -> "Assistente: ";
            }).append(message.text()).append('\n');
        }
        prompt.append('\n');
    }

    public static String systemInstruction() {
        return SYSTEM_INSTRUCTION;
    }
}
