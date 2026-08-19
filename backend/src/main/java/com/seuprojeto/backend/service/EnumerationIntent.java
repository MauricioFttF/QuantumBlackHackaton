package com.seuprojeto.backend.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects "list every X" questions and maps them to a chunk type.
 *
 * <p>Vector similarity is the wrong tool for enumeration: it ranks by topical closeness, so a
 * question <em>about articles</em> happily returns agenda items and never guarantees it saw
 * every article. When the question is clearly a listing, retrieval switches to a metadata
 * filter instead, which is exhaustive by construction.
 *
 * <p>Pure and deterministic — no Spring, no I/O.
 */
public final class EnumerationIntent {

    /**
     * Type keywords, checked in order. Order matters: "palestrantes" contains "palestra", so the
     * speaker pattern must be tested before the agenda one.
     */
    private static final Map<Pattern, String> TYPE_PATTERNS = new LinkedHashMap<>();

    /** Phrases that make a question a listing rather than a lookup. */
    private static final Pattern LISTING_CUE = Pattern.compile(
            "\\b(quais|quantos|quantas|liste|listar|lista|todos|todas|"
                    + "quem sao|quem e que|quem participa|me diga os|me diga as)\\b");

    /** Words that are enumerative on their own — asking for "a programação" means all of it. */
    private static final Pattern INHERENTLY_ENUMERATIVE = Pattern.compile(
            "\\b(programacao|cronograma|agenda)\\b");

    static {
        TYPE_PATTERNS.put(Pattern.compile("\\bpalestrantes?\\b|\\bspeakers?\\b|\\bconvidados?\\b"), "palestrante");
        TYPE_PATTERNS.put(Pattern.compile("\\bartigos?\\b|\\bpapers?\\b|\\bpublicacoes\\b|\\bestudos?\\b"), "artigo");
        TYPE_PATTERNS.put(Pattern.compile("\\bmaterias?\\b|\\bnoticias?\\b|\\breportagens?\\b|\\bpodcasts?\\b"), "materia");
        TYPE_PATTERNS.put(Pattern.compile(
                "\\bprogramacao\\b|\\bcronograma\\b|\\bagenda\\b|\\bpalestras?\\b|\\bhorarios?\\b|\\bsessoes\\b"),
                "agenda");
    }

    private EnumerationIntent() {
    }

    /**
     * @return the chunk type to enumerate, or empty when the question is a normal lookup
     */
    public static Optional<String> detect(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(question);

        String type = null;
        for (Map.Entry<Pattern, String> entry : TYPE_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(normalized).find()) {
                type = entry.getValue();
                break;
            }
        }
        if (type == null) {
            return Optional.empty();
        }

        boolean listing = LISTING_CUE.matcher(normalized).find()
                || INHERENTLY_ENUMERATIVE.matcher(normalized).find();
        return listing ? Optional.of(type) : Optional.empty();
    }

    /** Lowercase and strip accents, so "programação" and "programacao" behave identically. */
    private static String normalize(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
