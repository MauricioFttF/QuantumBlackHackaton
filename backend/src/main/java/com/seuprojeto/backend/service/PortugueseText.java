package com.seuprojeto.backend.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Text normalisation shared by the heuristics that read Portuguese questions.
 *
 * <p>Extracted so {@link EnumerationIntent} and {@link RetrievalQuery} cannot drift apart: both
 * match patterns written without accents, and a question normalised one way in one place and another
 * way in the other would make "programação" work while "e ele" quietly stopped.
 */
final class PortugueseText {

    private PortugueseText() {
    }

    /** Lowercase and strip accents, so "programação" and "programacao" behave identically. */
    static String normalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
