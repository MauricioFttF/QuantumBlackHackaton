package com.seuprojeto.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EnumerationIntentTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Quais artigos estão disponíveis?                         | artigo",
            "Liste todos os artigos                                   | artigo",
            "Quantos estudos vocês têm?                               | artigo",
            "Quem são os palestrantes?                                | palestrante",
            "Quais convidados vão participar?                         | palestrante",
            "Liste todas as matérias                                  | materia",
            "Quais notícias existem sobre o Itaú?                     | materia",
            "Como está a programação da manhã?                        | agenda",
            "Qual é o cronograma do evento?                           | agenda",
            "Me diga os horários de todas as palestras                | agenda",
    })
    void detect_listingQuestion_returnsType(String question, String expectedType) {
        assertThat(EnumerationIntent.detect(question)).contains(expectedType);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Quem é Salim Ismail?",
            "Onde vai ser o evento?",
            "Qual o tema da palestra do Salim Ismail?",   // "palestra" without a listing cue
            "O que diz o artigo sobre o estado da IA?",   // one specific article
            "Qual é a capital da Mongólia?",
    })
    void detect_lookupQuestion_returnsEmpty(String question) {
        assertThat(EnumerationIntent.detect(question)).isEmpty();
    }

    @Test
    void detect_palestrantesBeatsPalestras_becauseTheWordContainsIt() {
        // "palestrantes" contains "palestra"; ordering must not classify this as agenda.
        assertThat(EnumerationIntent.detect("Quais palestrantes vão falar?")).contains("palestrante");
    }

    @Test
    void detect_worksWithoutAccents() {
        assertThat(EnumerationIntent.detect("Qual e a programacao?")).contains("agenda");
        assertThat(EnumerationIntent.detect("Quais materias existem?")).contains("materia");
    }

    @Test
    void detect_nullOrBlank_returnsEmpty() {
        assertThat(EnumerationIntent.detect(null)).isEmpty();
        assertThat(EnumerationIntent.detect("   ")).isEmpty();
    }
}
