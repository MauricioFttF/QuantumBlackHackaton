package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.ChatMemoryProperties;
import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.model.ConversationTurn;
import com.seuprojeto.backend.repository.ConversationTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The conversation each user is having with the assistant. One conversation per user, held in
 * Postgres for {@code app.chat-memory.ttl} and then deleted.
 *
 * <p>Two rules shape everything here:
 *
 * <ul>
 *   <li><b>No identity, no memory.</b> A caller without a user id gets an empty history and
 *       writes nothing. The alternative — falling back to the remote address, as the rate limiter
 *       does — would merge everyone behind one NAT into a single shared conversation.
 *   <li><b>The cutoff is enforced on read.</b> {@link #purgeExpired()} only reclaims space; it is
 *       never what makes the TTL correct. If the scheduler dies, answers stay correct and the
 *       table grows.
 * </ul>
 *
 * <p>History is context for resolving what the user is referring to. It is never a source of
 * facts — grounding still comes only from retrieved chunks (see {@link PromptAssembler}).
 */
@Service
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    private final ConversationTurnRepository repository;
    private final ChatMemoryProperties properties;
    private final Clock clock;

    public ConversationMemory(ConversationTurnRepository repository,
                              ChatMemoryProperties properties,
                              Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The user's recent turns, oldest first, at most {@code app.chat-memory.max-turns} of them.
     *
     * @param userId the current user, or {@code null} for an unidentified caller
     * @return an immutable, chronologically ordered history; empty when there is nothing to recall
     */
    public List<ConversationMessage> recall(String userId) {
        if (!enabledFor(userId)) {
            return List.of();
        }

        List<ConversationTurn> newestFirst = repository.findRecent(
                userId, cutoff(), Limit.of(properties.maxTurns()));

        List<ConversationMessage> chronological = new ArrayList<>(newestFirst.size());
        for (ConversationTurn turn : newestFirst) {
            chronological.add(ConversationMessage.of(turn));
        }
        Collections.reverse(chronological);

        log.debug("Recalled {} turn(s) of history", chronological.size());
        return List.copyOf(chronological);
    }

    /**
     * Appends one exchange. Both rows carry the same instant, so
     * {@link ConversationTurnRepository#findRecent} breaks the tie by id to keep the question
     * ahead of its answer.
     *
     * <p>Called only after an answer exists: a failed generation leaves the history untouched
     * rather than storing a question that was never answered.
     */
    @Transactional
    public void remember(String userId, String question, String answer) {
        if (!enabledFor(userId)) {
            return;
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Cannot remember a blank question");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("Cannot remember a blank answer");
        }

        Instant now = clock.instant();
        repository.saveAll(List.of(
                new ConversationTurn(userId, ChatRole.USER, question, now),
                new ConversationTurn(userId, ChatRole.ASSISTANT, answer, now)));
    }

    /**
     * Deletes every turn past the TTL, for every user. Scheduled by
     * {@code ChatMemoryConfig}; safe to run concurrently or not at all.
     *
     * @return how many rows were removed
     */
    @Transactional
    public int purgeExpired() {
        int deleted = repository.deleteOlderThan(cutoff());
        if (deleted > 0) {
            log.info("Purged {} expired conversation turn(s) older than {}", deleted, properties.ttl());
        }
        return deleted;
    }

    /** Turns written at or before this instant are gone, as far as any caller can tell. */
    private Instant cutoff() {
        return clock.instant().minus(properties.ttl());
    }

    private boolean enabledFor(String userId) {
        if (!properties.enabled()) {
            return false;
        }
        if (userId == null || userId.isBlank()) {
            log.debug("Anonymous request: no conversation to read or write");
            return false;
        }
        return true;
    }
}
