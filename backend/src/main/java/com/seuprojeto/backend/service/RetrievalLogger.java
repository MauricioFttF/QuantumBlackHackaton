package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AnalyticsConfig;
import com.seuprojeto.backend.dto.SourceRef;
import com.seuprojeto.backend.model.ChunkRetrievalLog;
import com.seuprojeto.backend.model.RetrievalEndpoint;
import com.seuprojeto.backend.repository.ChunkMatch;
import com.seuprojeto.backend.repository.ChunkRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Records which chunks were actually used as context, for the organizer dashboard.
 *
 * <p><b>This class is the one place in the codebase where failures are deliberately swallowed.</b>
 * Everywhere else, silent degradation is forbidden — a failed embedding throws rather than
 * returning a zero vector, a truncated answer becomes a 502. Analytics is different: it is not part
 * of the user-facing contract, and a full disk or a dead connection must not turn a working answer
 * into an error the user cannot act on. So the insert is best-effort, off the request thread, and a
 * failure produces a server-side warning and nothing else. Read that as a scoped exception with a
 * reason, not as inconsistency: the visible symptom is undercounted dashboard rows, which is
 * recoverable, whereas the alternative is losing an answer the user already paid quota for.
 *
 * <p>Nothing here records the question. One opaque {@code sessionRef} per call is what makes "42
 * retrievals across 19 requests" countable without knowing who asked or what they typed.
 */
@Service
public class RetrievalLogger {

    private static final Logger log = LoggerFactory.getLogger(RetrievalLogger.class);

    private final ChunkRetrievalLogRepository repository;
    private final Clock clock;

    public RetrievalLogger(ChunkRetrievalLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records one row per retrieved chunk.
     *
     * <p>Runs on the analytics executor, so the caller does not wait for the insert. Note that
     * {@code @Async} only applies when called from another bean — a direct call inside this class,
     * or from a test, executes inline, which is what makes the failure path testable.
     *
     * @param matches the chunks that survived retrieval — exactly what the response reports as
     *                {@code sources}. An empty list writes nothing: a refused answer retrieved no
     *                context, and inventing a row would misreport interest
     */
    @Async(AnalyticsConfig.EXECUTOR)
    public void record(RetrievalEndpoint endpoint, List<ChunkMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }

        try {
            String sessionRef = UUID.randomUUID().toString();
            Instant now = clock.instant();

            repository.saveAll(matches.stream()
                    .map(match -> new ChunkRetrievalLog(
                            match.getId(),
                            endpoint,
                            SourceRef.similarityOf(match.getDistance()),
                            sessionRef,
                            now))
                    .toList());
        } catch (Exception e) {
            // Intentional catch-all — see the class javadoc. The parent request has already
            // succeeded; the only thing left to lose is a dashboard row.
            log.warn("Could not record retrieval analytics for {} ({} chunk(s)); "
                    + "the request itself was unaffected", endpoint.storedName(), matches.size(), e);
        }
    }
}
