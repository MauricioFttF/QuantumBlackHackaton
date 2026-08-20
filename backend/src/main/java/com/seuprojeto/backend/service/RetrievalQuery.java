package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
 * <p><b>And only questions that need it get expanded.</b> Measured the hard way: with expansion
 * applied unconditionally, asking "Onde e quando acontece o evento?" right after a question about
 * exponential technologies retrieved the earlier topic (similarity 0.803 on the wrong chunk) and the
 * event's own chunk never reached the model, which answered that it did not know. A self-contained
 * question already carries its subject; adding an old one only blurs it. So expansion now waits for
 * an anaphoric signal — a pronoun, a leading "e", a bare "e a que horas?" — which is exactly the
 * shape that has nothing to search for on its own.
 *
 * <p>Pure and deterministic: no Spring, no I/O, no clock.
 */
public final class RetrievalQuery {

    /**
     * Marks a question as depending on the conversation: pronouns and demonstratives standing in for
     * a subject, or an opening "e ..." that continues the previous question. Like
     * {@link EnumerationIntent}, a heuristic — one that errs towards <em>not</em> expanding, because
     * a missed expansion costs one weak follow-up while a wrong one corrupts a good question.
     */
    private static final Pattern NEEDS_CONTEXT = Pattern.compile(
            "^e\\s|\\b(ele|ela|eles|elas|dele|dela|deles|delas|lhe|nele|nela"
                    + "|isso|isto|aquilo|esse|essa|este|esta|aquele|aquela|desse|dessa|deste|desta"
                    + "|mesmo|mesma|tambem|ai|la|ali)\\b");

    private RetrievalQuery() {
    }

    /** Whether a question can be searched on its own. Package-private so it can be tested directly. */
    static boolean needsConversationContext(String question) {
        return NEEDS_CONTEXT.matcher(PortugueseText.normalize(question.trim())).find();
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
        if (!needsConversationContext(question)) {
            // Self-contained question: it has its own subject, and prepending an older one would
            // pull retrieval towards the wrong chunks.
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
