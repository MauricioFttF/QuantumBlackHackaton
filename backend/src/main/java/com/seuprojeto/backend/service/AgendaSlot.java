package com.seuprojeto.backend.service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The time a session occupies, parsed from an agenda chunk's {@code titleRef} (for example
 * {@code "09h10 às 10h00"}).
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

    /** {@code 9h00}, {@code 09h00}, optionally {@code às}/{@code as}/{@code -} and a second time. */
    private static final Pattern SLOT = Pattern.compile(
            "^\\s*(\\d{1,2})h(\\d{2})?\\s*(?:(?:às|as|a|-|–|até)\\s*(\\d{1,2})h(\\d{2})?)?\\s*$");

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

        Matcher matcher = SLOT.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }

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

    /** True when the two sessions cannot both be attended. */
    public boolean overlaps(AgendaSlot other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    private static LocalTime time(String hour, String minute) {
        return LocalTime.of(Integer.parseInt(hour), minute == null ? 0 : Integer.parseInt(minute));
    }
}
