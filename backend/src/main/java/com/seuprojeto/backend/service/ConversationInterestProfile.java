package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Builds an interest profile out of what the account recently asked about.
 *
 * <p>A stopgap with a known shape, not a pretend profile store. Two consequences worth stating
 * plainly: it only ever knows the last {@code app.chat-memory.max-turns} questions, and it forgets
 * everything after {@code app.chat-memory.ttl} — so a user who has not chatted in the last hour has
 * <em>no</em> profile, and the recommender must say so rather than recommending from nothing.
 *
 * <p>Only the user's own turns are used. Feeding the assistant's answers back in would profile the
 * model's phrasing as much as the user's interests.
 */
@Service
public class ConversationInterestProfile implements InterestProfilePort {

    private static final Logger log = LoggerFactory.getLogger(ConversationInterestProfile.class);

    private final ConversationMemory conversationMemory;

    public ConversationInterestProfile(ConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    @Override
    public Optional<String> interestSummaryFor(String userId) {
        List<ConversationMessage> history = conversationMemory.recall(userId);

        String questions = history.stream()
                .filter(message -> message.role() == ChatRole.USER)
                .map(ConversationMessage::text)
                .reduce((first, second) -> first + " " + second)
                .orElse("");

        if (questions.isBlank()) {
            log.debug("No stored interests: this account has no recent conversation");
            return Optional.empty();
        }
        return Optional.of(questions);
    }
}
