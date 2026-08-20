package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AgendaProperties;
import com.seuprojeto.backend.dto.AgendaRecommendRequest;
import com.seuprojeto.backend.dto.AgendaRecommendResponse;
import com.seuprojeto.backend.dto.ItinerarySlot;
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
 * Builds a conflict-free itinerary from what a user says (or has shown) they care about.
 *
 * <p>Reuses the retrieval path the chat endpoint uses — same embedding model, same
 * {@code findNearestByType} query the listing questions use — so there is exactly one
 * implementation of cosine search in this codebase. What differs is what happens afterwards: a
 * wider candidate pool, because conflict resolution throws candidates away, and no generation call
 * at all. This endpoint spends embedding quota only.
 *
 * <p>An empty interest vector is never used as a fallback. If neither the request nor the account
 * says anything about interests, the request is refused.
 */
@Service
public class AgendaRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(AgendaRecommendationService.class);

    /**
     * The {@code type} discriminator ingestion writes for agenda rows (see
     * {@code IngestionService.toDrafts}). A schema value, not configuration: changing it would
     * require re-ingesting the corpus, because it is part of every chunk's content hash.
     */
    static final String AGENDA_TYPE = "agenda";

    private final EmbeddingService embeddingService;
    private final KnowledgeChunkRepository repository;
    private final InterestProfilePort interestProfile;
    private final RetrievalLogger retrievalLogger;
    private final AgendaProperties properties;

    public AgendaRecommendationService(EmbeddingService embeddingService,
                                       KnowledgeChunkRepository repository,
                                       InterestProfilePort interestProfile,
                                       RetrievalLogger retrievalLogger,
                                       AgendaProperties properties) {
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.interestProfile = interestProfile;
        this.retrievalLogger = retrievalLogger;
        this.properties = properties;
    }

    /**
     * @param userId the authenticated account, or null for a caller with no identity
     * @throws IllegalArgumentException when there is nothing to base a recommendation on — mapped
     *         to a 400. Proceeding with an empty interest text would embed nothing and return
     *         whatever happens to sit nearest the origin, dressed up as a recommendation
     */
    public AgendaRecommendResponse recommend(String userId, AgendaRecommendRequest request) {
        if (request.date() != null && !request.date().equals(properties.eventDate())) {
            // The agenda has time slots but no dates, so the only honest answer for another day is
            // "there is nothing on that day", with the reason attached.
            log.info("Recommendation requested for {}, but the event is on {}",
                    request.date(), properties.eventDate());
            return AgendaRecommendResponse.empty(0,
                    "O evento acontece em %s; não há programação em %s."
                            .formatted(properties.eventDate(), request.date()));
        }

        String interestText = resolveInterests(userId, request.interests());
        int maxSessions = request.maxSessions() == null
                ? properties.defaultMaxSessions()
                : request.maxSessions();

        float[] interestVector = embeddingService.embed(interestText);

        List<ChunkMatch> candidates = repository.findNearestByType(
                AGENDA_TYPE, interestVector, Limit.of(properties.recommendTopK()));
        List<ChunkMatch> relevant = candidates.stream()
                .filter(match -> match.getDistance() <= properties.recommendMaxDistance())
                .toList();

        if (relevant.isEmpty()) {
            log.info("No agenda session within distance {} of the stated interests",
                    properties.recommendMaxDistance());
            return AgendaRecommendResponse.empty(0,
                    "Nenhuma sessão da programação combina com esses interesses.");
        }

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(
                relevant, maxSessions, properties.openEndedSlotDuration());

        List<ChunkMatch> accepted = itinerary.sessions().stream()
                .map(ItineraryPlanner.PlannedSession::match)
                .toList();
        List<ItinerarySlot> slots = itinerary.sessions().stream()
                .map(session -> ItinerarySlot.from(session.match(),
                        session.slot().startsAt(), session.slot().endsAt()))
                .toList();

        // Analytics records what was recommended, i.e. what the user actually saw — the itinerary
        // is this endpoint's equivalent of the `sources` array. Candidates dropped for clashing are
        // not logged: they were never shown, and counting them would inflate interest in sessions
        // nobody was offered.
        retrievalLogger.record(RetrievalEndpoint.AGENDA_RECOMMEND, accepted);

        log.info("Recommended {} of {} relevant agenda session(s) ({} dropped for conflicts, "
                        + "{} unschedulable)", accepted.size(), relevant.size(),
                itinerary.conflictCount(), itinerary.unparsedCount());

        return AgendaRecommendResponse.of(slots, relevant.size(), explain(itinerary, maxSessions));
    }

    /**
     * Explicit text first, then the stored profile, embedded as one string.
     *
     * <p>Concatenated rather than embedded separately and averaged: averaging two vectors produces a
     * point that may match neither source, and silently so. Putting the explicit text first keeps
     * it dominant when the two disagree.
     */
    private String resolveInterests(String userId, String explicit) {
        String stated = explicit == null ? "" : explicit.trim();
        Optional<String> stored = userId == null
                ? Optional.empty()
                : interestProfile.interestSummaryFor(userId);

        if (stated.isEmpty() && stored.isEmpty()) {
            throw new IllegalArgumentException(
                    "Descreva seus interesses no campo 'interests': esta conta ainda não tem "
                            + "histórico recente para inferir preferências.");
        }
        if (stated.isEmpty()) {
            log.info("Recommending from the stored profile alone");
            return stored.get();
        }
        if (stored.isEmpty()) {
            return stated;
        }
        log.info("Recommending from stated interests plus the stored profile");
        return stated + " " + stored.get();
    }

    /** A sentence only when the result needs one; null when the itinerary speaks for itself. */
    private static String explain(ItineraryPlanner.Itinerary itinerary, int maxSessions) {
        boolean short_ = itinerary.sessions().size() < maxSessions;
        if (!short_) {
            return null;
        }
        if (itinerary.conflictCount() > 0 || itinerary.unparsedCount() > 0) {
            return ("Retornamos %d de %d sessões pedidas: %d conflitavam com horários já escolhidos"
                    + " e %d não têm horário reconhecível.")
                    .formatted(itinerary.sessions().size(), maxSessions,
                            itinerary.conflictCount(), itinerary.unparsedCount());
        }
        return "Retornamos %d de %d sessões pedidas: não há mais sessões relevantes na programação."
                .formatted(itinerary.sessions().size(), maxSessions);
    }
}
