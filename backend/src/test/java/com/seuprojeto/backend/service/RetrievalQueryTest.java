package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RetrievalQueryTest {

    private static final ConversationMessage ASKED_ABOUT_SALIM =
            new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?");
    private static final ConversationMessage ANSWERED_ABOUT_SALIM =
            new ConversationMessage(ChatRole.ASSISTANT, "Fundador da Singularity University.");

    @Test
    void expand_emptyHistory_returnsTheQuestionUnchanged() {
        assertThat(RetrievalQuery.expand("Quem é Salim Ismail?", List.of(), 2))
                .isEqualTo("Quem é Salim Ismail?");
    }

    @Test
    void expand_zeroContextTurns_returnsTheQuestionUnchanged() {
        // The switch that makes retrieval behave exactly as it did before memory existed.
        assertThat(RetrievalQuery.expand("E ele fala a que horas?",
                List.of(ASKED_ABOUT_SALIM, ANSWERED_ABOUT_SALIM), 0))
                .isEqualTo("E ele fala a que horas?");
    }

    @Test
    void expand_followUp_prependsTheEarlierQuestionSoTheSubjectIsSearchable() {
        String expanded = RetrievalQuery.expand("E ele fala a que horas?",
                List.of(ASKED_ABOUT_SALIM, ANSWERED_ABOUT_SALIM), 2);

        assertThat(expanded).isEqualTo("Quem é Salim Ismail? E ele fala a que horas?");
    }

    @Test
    void expand_ignoresAssistantTurns_soOneWeakAnswerCannotSteerLaterRetrieval() {
        String expanded = RetrievalQuery.expand("E a que horas?", List.of(ANSWERED_ABOUT_SALIM), 2);

        assertThat(expanded).isEqualTo("E a que horas?");
    }

    @Test
    void expand_moreHistoryThanContextTurns_keepsOnlyTheMostRecentQuestions() {
        List<ConversationMessage> history = List.of(
                new ConversationMessage(ChatRole.USER, "primeira"),
                new ConversationMessage(ChatRole.USER, "segunda"),
                new ConversationMessage(ChatRole.USER, "terceira"));

        String expanded = RetrievalQuery.expand("atual", history, 2);

        assertThat(expanded).isEqualTo("segunda terceira atual");
    }

    @Test
    void expand_repeatedQuestion_isNotDuplicated() {
        String expanded = RetrievalQuery.expand("Quem é Salim Ismail?", List.of(ASKED_ABOUT_SALIM), 2);

        assertThat(expanded).isEqualTo("Quem é Salim Ismail?");
    }

    @Test
    void expand_blankQuestion_throws() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RetrievalQuery.expand("  ", List.of(), 2));
    }
}
