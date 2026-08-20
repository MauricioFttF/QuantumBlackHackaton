package com.seuprojeto.backend.service;

import java.util.Optional;

/**
 * Where a user's stored interests come from.
 *
 * <p>This port exists because the account subsystem has no interest profile yet: {@code app_user}
 * holds credentials only, and the only per-user signal in the database is recent chat history,
 * which expires after {@code app.chat-memory.ttl}. {@link ConversationInterestProfile} derives a
 * profile from that; when a real profile table arrives, it replaces that one class and the
 * recommender does not change.
 *
 * <p>Single abstract method on purpose — a test supplies a profile with a lambda instead of a mock.
 */
@FunctionalInterface
public interface InterestProfilePort {

    /**
     * @param userId the account, never null here — the caller decides what an anonymous request
     *               means
     * @return a free-text description of what this user has shown interest in, or empty when
     *         nothing is known. Empty must not be turned into an empty interest vector: the caller
     *         has to refuse the request instead
     */
    Optional<String> interestSummaryFor(String userId);
}
