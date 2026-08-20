package com.seuprojeto.backend.service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The time a session occupies, read from an agenda chunk.
 *
 * <p>Where that time lives depends on the corpus. Ingestion currently puts it in the chunk's text
 * ({@code "Agenda do evento — Horário: 09:10 às 10:00 — <título>"}) and uses the session title as
 * the {@code titleRef}; an earlier corpus used the slot itself as the {@code titleRef}
 * ({@code "09h10 às 10h00"}). {@link #parseFromChunk} handles both, and both separators
 * ({@code 09h10} and {@code 09:10}), so a corpus change does not silently produce an itinerary
 * where nothing can be scheduled.
 *
 * <p>The end is <b>exclusive</b>. The corpus has slots that touch — {@code 08h15 às 09h00}
 * followed by {@code 09h00 às 09h10} — and treating the boundary as a clash would make two
 * perfectly compatible sessions look like a conflict.
 *
 * <p>Some entries carry only a start ({@code "12h15"}). Those get a configured default duration
 * rather than being treated as a zero-length instant: a zero-length slot can never overlap
 * anything, so the planner would happily schedule a talk on top of it.
 *
 * <p>Pure and deterministic: no Spring, no I/O, no clock.
 */
public record AgendaSlot(LocalTime start, LocalTime end) {

    /** A whole string that is nothing but a time or a time range. */
    private static final Pattern WHOLE_SLOT = Pattern.compile(
            "^\\s*(\\d{1,2})[h:](\\d{2})?\\s*(?:(?:às|as|a|-|–|até)\\s*(\\d{1,2})[h:](\\d{2})?)?\\s*$");

    /**
     * The first time (or range) appearing inside a longer text. Minutes are required here, unlike
     * {@link #WHOLE_SLOT}: in free text, insisting on {@code 09:10} rather than accepting {@code 9h}
     * is what keeps "Rewired 2.0" and similar from being read as a clock.
     */
    private static final Pattern SLOT_IN_TEXT = Pattern.compile(
            "(\\d{1,2})[h:](\\d{2})\\s*(?:(?:às|as|-|–|até)\\s*(\\d{1,2})[h:](\\d{2}))?");

    public AgendaSlot {
        if (start == null || end == null) {
            throw new IllegalArgumentException("An agenda slot needs a start and an end");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "An agenda slot must end after it starts, got %s to %s".formatted(start, end));
        }
    }

    /**
     * @param text               the chunk's {@code titleRef}
     * @param openEndedDuration  how long a session with no stated end is assumed to run
     * @return the slot, or empty when the text is not a time range this parser understands —
     *         including a range that ends before it starts (a slot crossing midnight is not
     *         supported). The caller must decide what to do with an unschedulable session; it must
     *         not be treated as conflict-free
     */
    public static Optional<AgendaSlot> parse(String text, Duration openEndedDuration) {
        if (text == null || openEndedDuration == null
                || openEndedDuration.isZero() || openEndedDuration.isNegative()) {
            return Optional.empty();
        }

        Matcher matcher = WHOLE_SLOT.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return build(matcher, openEndedDuration);
    }

    /**
     * Reads a session's slot from a chunk, wherever the corpus happens to keep it: the
     * {@code titleRef} if that is the slot itself, otherwise the first time found in the text.
     *
     * @return empty when neither carries a time — the caller must treat that session as
     *         unschedulable rather than conflict-free
     */
    public static Optional<AgendaSlot> parseFromChunk(String titleRef, String content,
                                                      Duration openEndedDuration) {
        Optional<AgendaSlot> fromTitle = parse(titleRef, openEndedDuration);
        if (fromTitle.isPresent()) {
            return fromTitle;
        }
        if (content == null || openEndedDuration == null
                || openEndedDuration.isZero() || openEndedDuration.isNegative()) {
            return Optional.empty();
        }

        Matcher matcher = SLOT_IN_TEXT.matcher(content.toLowerCase(Locale.ROOT));
        return matcher.find() ? build(matcher, openEndedDuration) : Optional.empty();
    }

    private static Optional<AgendaSlot> build(Matcher matcher, Duration openEndedDuration) {
        try {
            LocalTime start = time(matcher.group(1), matcher.group(2));
            LocalTime end = matcher.group(3) == null
                    ? start.plus(openEndedDuration)
                    : time(matcher.group(3), matcher.group(4));
            return Optional.of(new AgendaSlot(start, end));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            // An hour of 99, or an end before the start. Unparseable rather than fatal: one odd
            // agenda row must not break a whole itinerary, and the caller reports how many it
            // could not schedule.
            return Optional.empty();
        }
    }

    /** {@code HH:mm}, for showing the itinerary as a schedule. */
    public String startsAt() {
        return String.format("%02d:%02d", start.getHour(), start.getMinute());
    }

    public String endsAt() {
        return String.format("%02d:%02d", end.getHour(), end.getMinute());
    }

    /** True when the two sessions cannot both be attended. */
    public boolean overlaps(AgendaSlot other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    private static LocalTime time(String hour, String minute) {
        return LocalTime.of(Integer.parseInt(hour), minute == null ? 0 : Integer.parseInt(minute));
    }
}
