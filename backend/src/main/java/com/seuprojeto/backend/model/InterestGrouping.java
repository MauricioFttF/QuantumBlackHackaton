package com.seuprojeto.backend.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** How the organizer dashboard aggregates retrievals. */
public enum InterestGrouping {

    /** By chunk type — the shape of the demand (agenda vs speakers vs articles). */
    TYPE("type"),

    /** By individual item — the useful view: which talk or speaker people keep asking about. */
    TITLE_REF("titleRef");

    private final String wireName;

    InterestGrouping(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * @throws IllegalArgumentException on anything else, listing what is accepted. A silent fallback
     *         to a default grouping would answer a different question than the one asked
     */
    public static InterestGrouping fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Informe 'groupBy': " + accepted());
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(grouping -> grouping.wireName.toLowerCase(Locale.ROOT).equals(normalised))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "'groupBy' inválido: '%s'. Valores aceitos: %s".formatted(value, accepted())));
    }

    private static String accepted() {
        return Arrays.stream(values()).map(InterestGrouping::wireName).collect(Collectors.joining(" | "));
    }
}
