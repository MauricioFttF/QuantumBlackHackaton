package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the text that gets embedded for retrieval.
 *
 * <p>A follow-up carries almost no searchable content of its own: "e ele fala a que horas?"
 * embeds to something close to noise and retrieves whatever happens to be nearest. Prepending the
 * user's recent questions puts the subject back into the query, so the vector search looks for
 * what the conversation is actually about.
 *
 * <p>Only <em>user</em> turns are used. Answers are model output — feeding them back in would let
 * one weak answer steer every retrieval after it.
 *
 * <p>Pure and deterministic: no Spring, no I/O, no clock.
 */
public final class RetrievalQuery {

    private RetrievalQuery() {
    }

    /**
     * @param question     the current question; always the last thing in the result
     * @param history      conversation so far, oldest first; may be empty
     * @param contextTurns how many earlier user turns to include. 0 returns {@code question}
     *                     unchanged, which is what keeps single-turn retrieval identical to how
     *                     it behaved before memory existed
     * @return the text to embed
     */
    public static String expand(String question, List<ConversationMessage> history, int contextTurns) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be null or blank");
        }
        if (contextTurns <= 0 || history == null || history.isEmpty()) {
            return question;
        }

        List<String> earlierQuestions = new ArrayList<>();
        // Walk backwards so "the last N" is measured from the most recent turn, then restore
        // chronological order: the query reads as the conversation did.
        for (int i = history.size() - 1; i >= 0 && earlierQuestions.size() < contextTurns; i--) {
            ConversationMessage message = history.get(i);
            if (message.role() == ChatRole.USER && !message.text().equals(question)) {
                earlierQuestions.add(message.text());
            }
        }
        if (earlierQuestions.isEmpty()) {
            return question;
        }

        StringBuilder expanded = new StringBuilder();
        for (int i = earlierQuestions.size() - 1; i >= 0; i--) {
            expanded.append(earlierQuestions.get(i)).append(' ');
        }
        return expanded.append(question).toString();
    }
}
