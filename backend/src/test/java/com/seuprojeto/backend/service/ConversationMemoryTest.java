package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.ChatMemoryProperties;
import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.model.ConversationTurn;
import com.seuprojeto.backend.repository.ConversationTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final String USER = "user-42";

    private ConversationTurnRepository repository;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationTurnRepository.class);
        memory = new ConversationMemory(repository, properties(true, Duration.ofHours(1), 6),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recall_returnsTurnsOldestFirst_becauseTheRepositoryHandsThemBackNewestFirst() {
        when(repository.findRecent(anyString(), any(), any())).thenReturn(List.of(
                turn(ChatRole.ASSISTANT, "Fundador da Singularity University."),
                turn(ChatRole.USER, "Quem é Salim Ismail?")));

        List<ConversationMessage> history = memory.recall(USER);

        assertThat(history).containsExactly(
                new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?"),
                new ConversationMessage(ChatRole.ASSISTANT, "Fundador da Singularity University."));
    }

    @Test
    void recall_asksOnlyForTurnsInsideTheTtl_soExpiryHoldsEvenIfThePurgeNeverRuns() {
        ArgumentCaptor<Instant> after = ArgumentCaptor.forClass(Instant.class);

        memory.recall(USER);

        verify(repository).findRecent(eq(USER), after.capture(), any());
        assertThat(after.getValue()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    void recall_capsAtMaxTurns() {
        memory.recall(USER);

        verify(repository).findRecent(anyString(), any(), eq(Limit.of(6)));
    }

    @Test
    void recall_anonymousCaller_returnsEmptyAndNeverTouchesTheDatabase() {
        assertThat(memory.recall(null)).isEmpty();
        assertThat(memory.recall("  ")).isEmpty();

        assertThat(mockingDetails(repository).getInvocations()).isEmpty();
    }

    @Test
    void recall_memoryDisabled_returnsEmpty() {
        memory = new ConversationMemory(repository, properties(false, Duration.ofHours(1), 6),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(memory.recall(USER)).isEmpty();
        verify(repository, never()).findRecent(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void remember_writesQuestionThenAnswerWithOneSharedInstant() {
        ArgumentCaptor<List<ConversationTurn>> saved = ArgumentCaptor.forClass(List.class);

        memory.remember(USER, "Quem é Salim Ismail?", "Fundador da Singularity University.");

        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(
                        ConversationTurn::getUserId, ConversationTurn::getRole,
                        ConversationTurn::getContent, ConversationTurn::getCreatedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(USER, ChatRole.USER,
                                "Quem é Salim Ismail?", NOW),
                        org.assertj.core.groups.Tuple.tuple(USER, ChatRole.ASSISTANT,
                                "Fundador da Singularity University.", NOW));
    }

    @Test
    void remember_anonymousCaller_writesNothing() {
        memory.remember(null, "Quem é Salim Ismail?", "Fundador da Singularity University.");

        verify(repository, never()).saveAll(any());
    }

    @Test
    void remember_blankAnswer_throwsRatherThanStoringAnEmptyTurn() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> memory.remember(USER, "Quem é Salim?", "   "));

        verify(repository, never()).saveAll(any());
    }

    @Test
    void purgeExpired_deletesEverythingOlderThanTheTtl() {
        when(repository.deleteOlderThan(any())).thenReturn(4);

        int deleted = memory.purgeExpired();

        assertThat(deleted).isEqualTo(4);
        verify(repository).deleteOlderThan(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    void constructor_invalidConfiguration_isRejectedAtStartup() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> properties(true, Duration.ZERO, 6))
                .withMessageContaining("app.chat-memory.ttl");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> properties(true, Duration.ofHours(1), 0))
                .withMessageContaining("max-turns");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ChatMemoryProperties(
                        true, Duration.ofHours(1), 6, -1, Duration.ofMinutes(15)))
                .withMessageContaining("retrieval-context-turns");

        // A purge slower than the window leaves the table growing past what it should hold.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ChatMemoryProperties(
                        true, Duration.ofHours(1), 6, 2, Duration.ofHours(6)))
                .withMessageContaining("cleanup-interval");
    }

    private static ChatMemoryProperties properties(boolean enabled, Duration ttl, int maxTurns) {
        return new ChatMemoryProperties(enabled, ttl, maxTurns, 2, Duration.ofMinutes(15));
    }

    private static ConversationTurn turn(ChatRole role, String content) {
        return new ConversationTurn(USER, role, content, NOW);
    }
}
