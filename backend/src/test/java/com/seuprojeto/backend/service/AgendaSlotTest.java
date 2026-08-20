package com.seuprojeto.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AgendaSlotTest {

    private static final Duration OPEN_ENDED = Duration.ofMinutes(45);

    @Test
    void parse_range_readsBothEnds() {
        assertThat(AgendaSlot.parse("09h10 às 10h00", OPEN_ENDED))
                .contains(new AgendaSlot(LocalTime.of(9, 10), LocalTime.of(10, 0)));
    }

    @Test
    void parse_withoutAccent_isAccepted() {
        assertThat(AgendaSlot.parse("09h10 as 10h00", OPEN_ENDED))
                .contains(new AgendaSlot(LocalTime.of(9, 10), LocalTime.of(10, 0)));
    }

    @Test
    void parse_startOnly_getsTheConfiguredDuration() {
        // The corpus's "12h15" (Networking Lunch). A zero-length slot could never clash with
        // anything, so the planner would schedule a talk on top of lunch.
        assertThat(AgendaSlot.parse("12h15", OPEN_ENDED))
                .contains(new AgendaSlot(LocalTime.of(12, 15), LocalTime.of(13, 0)));
    }

    @Test
    void parse_hourWithoutMinutes_isAccepted() {
        assertThat(AgendaSlot.parse("9h às 10h", OPEN_ENDED))
                .contains(new AgendaSlot(LocalTime.of(9, 0), LocalTime.of(10, 0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Salim Ismail",
            "Sessões Temáticas",
            "",
            "99h99 às 10h00",
            "10h00 às 09h00",   // ends before it starts
            "23h30 às 00h30",   // crosses midnight: not supported
    })
    void parse_notATimeRangeWeUnderstand_isEmptySoTheCallerCanReportIt(String text) {
        assertThat(AgendaSlot.parse(text, OPEN_ENDED)).isEmpty();
    }

    @Test
    void parse_null_isEmpty() {
        assertThat(AgendaSlot.parse(null, OPEN_ENDED)).isEmpty();
    }

    @Test
    void overlaps_touchingSlots_doNotConflict_becauseTheEndIsExclusive() {
        // The corpus has exactly this pair: 08h15-09h00 followed by 09h00-09h10.
        AgendaSlot earlier = AgendaSlot.parse("08h15 às 09h00", OPEN_ENDED).orElseThrow();
        AgendaSlot later = AgendaSlot.parse("09h00 às 09h10", OPEN_ENDED).orElseThrow();

        assertThat(earlier.overlaps(later)).isFalse();
        assertThat(later.overlaps(earlier)).isFalse();
    }

    @Test
    void overlaps_partialOverlap_conflicts() {
        AgendaSlot first = new AgendaSlot(LocalTime.of(9, 0), LocalTime.of(10, 0));
        AgendaSlot second = new AgendaSlot(LocalTime.of(9, 30), LocalTime.of(10, 30));

        assertThat(first.overlaps(second)).isTrue();
        assertThat(second.overlaps(first)).isTrue();
    }

    @Test
    void overlaps_containedSlot_conflicts() {
        AgendaSlot outer = new AgendaSlot(LocalTime.of(9, 0), LocalTime.of(12, 0));
        AgendaSlot inner = new AgendaSlot(LocalTime.of(10, 0), LocalTime.of(10, 30));

        assertThat(outer.overlaps(inner)).isTrue();
        assertThat(inner.overlaps(outer)).isTrue();
    }
}
