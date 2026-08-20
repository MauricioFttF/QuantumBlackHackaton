package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AnalyticsProperties;
import com.seuprojeto.backend.dto.InterestSummaryEntry;
import com.seuprojeto.backend.dto.InterestSummaryResponse;
import com.seuprojeto.backend.model.InterestGrouping;
import com.seuprojeto.backend.repository.ChunkRetrievalLogRepository;
import com.seuprojeto.backend.repository.InterestSummaryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Reads the retrieval log into the aggregate organizers care about.
 *
 * <p>Aggregate only: the query never selects a question, a user or a session id — it counts rows and
 * averages scores. That is a property of the table (there is nothing else in it) rather than a
 * restraint this class is exercising.
 */
@Service
public class InterestAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(InterestAnalyticsService.class);

    private final ChunkRetrievalLogRepository repository;
    private final AnalyticsProperties properties;
    private final Clock clock;

    public InterestAnalyticsService(ChunkRetrievalLogRepository repository,
                                    AnalyticsProperties properties,
                                    Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param from     start of the window, or null for {@code now - analytics.default-window}
     * @param to       end of the window (exclusive), or null for now
     * @param grouping how to aggregate
     * @throws IllegalArgumentException if the window is empty or inverted — mapped to a 400 rather
     *         than silently swapped, because a client asking for {@code from > to} has a bug and
     *         answering it with data hides that
     */
    public InterestSummaryResponse summarise(Instant from, Instant to, InterestGrouping grouping) {
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(properties.defaultWindow()) : from;

        if (!start.isBefore(end)) {
            throw new IllegalArgumentException(
                    "'from' (%s) deve ser anterior a 'to' (%s)".formatted(start, end));
        }

        List<InterestSummaryRow> rows = switch (grouping) {
            case TYPE -> repository.summariseByType(start, end);
            case TITLE_REF -> repository.summariseByTitleRef(start, end);
        };

        boolean truncated = rows.size() > properties.maxResults();
        if (truncated) {
            log.info("Interest summary truncated to {} of {} group(s)", properties.maxResults(), rows.size());
            rows = rows.subList(0, properties.maxResults());
        }

        return new InterestSummaryResponse(start, end, grouping.wireName(),
                rows.stream().map(InterestSummaryEntry::from).toList(), truncated);
    }
}
