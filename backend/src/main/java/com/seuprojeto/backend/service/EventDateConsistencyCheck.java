package com.seuprojeto.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.config.AgendaProperties;
import com.seuprojeto.backend.config.IngestionProperties;
import com.seuprojeto.backend.dto.EventDataDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Refuses to run with {@code agenda.event-date} pointing at a different day than the corpus.
 *
 * <p>The two hold the same fact — the corpus has {@code evento.data_iso} and the property exists
 * because agenda rows carry times but no dates, so the {@code date} filter has nothing else to check
 * against. When they disagree, nothing crashes: {@code POST /api/agenda/recommend} simply answers
 * "there is no programme that day" for the day the event is actually on. A wrong answer delivered
 * confidently is exactly what this codebase refuses to ship, so a mismatch fails the boot with both
 * values named.
 *
 * <p>A corpus that cannot be read, or one without {@code data_iso}, is <em>not</em> a mismatch — it
 * is unknown, and {@code StartupIngestion} already reports an unreadable corpus. Silence here means
 * "nothing to compare", and it is logged as such.
 *
 * <p>Constructs its own {@link ObjectMapper} for the reason given in {@code IngestionService}: there
 * is no Jackson 2 mapper bean in this application.
 */
@Component
@Order(0)
public class EventDateConsistencyCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventDateConsistencyCheck.class);

    private final IngestionProperties ingestionProperties;
    private final AgendaProperties agendaProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventDateConsistencyCheck(IngestionProperties ingestionProperties,
                                     AgendaProperties agendaProperties) {
        this.ingestionProperties = ingestionProperties;
        this.agendaProperties = agendaProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<LocalDate> corpusDate = corpusEventDate();
        if (corpusDate.isEmpty()) {
            return;
        }
        if (!corpusDate.get().equals(agendaProperties.eventDate())) {
            throw new IllegalStateException(
                    "agenda.event-date is %s but the corpus (%s) says the event is on %s. The date "
                            .formatted(agendaProperties.eventDate(), ingestionProperties.path(),
                                    corpusDate.get())
                            + "filter on /api/agenda/recommend would report no programme on the real "
                            + "event day. Fix the property or the corpus so they agree.");
        }
        log.info("Event date {} agrees with the corpus", agendaProperties.eventDate());
    }

    /** Empty when there is nothing to compare — an unreadable corpus, or one without the field. */
    private Optional<LocalDate> corpusEventDate() {
        String path = ingestionProperties.path();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        try {
            EventDataDTO data = objectMapper.readValue(
                    IngestionService.resolveWithinWorkingDirectory(path).toFile(), EventDataDTO.class);
            String iso = data.getEvento() == null ? null : data.getEvento().getData_iso();
            if (iso == null || iso.isBlank()) {
                log.info("Corpus has no evento.data_iso; agenda.event-date cannot be cross-checked");
                return Optional.empty();
            }
            return Optional.of(LocalDate.parse(iso.trim()));
        } catch (DateTimeParseException e) {
            log.warn("Corpus evento.data_iso is not an ISO date; agenda.event-date not cross-checked", e);
            return Optional.empty();
        } catch (Exception e) {
            // Unreadable corpus is StartupIngestion's story to tell, not this check's.
            log.info("Corpus {} could not be read for the event-date check: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
