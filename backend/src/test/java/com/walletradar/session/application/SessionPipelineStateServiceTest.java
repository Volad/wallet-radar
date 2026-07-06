package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPipelineStateServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;

    @Test
    void preservesRunningParallelClassificationStageWhenPeerStageCompletes() {
        UserSession session = sessionWithState(
                UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                UserSession.PipelineStatus.RUNNING,
                "On-chain normalization running"
        );
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionPipelineStateService service = new SessionPipelineStateService(userSessionRepository);

        service.markStageComplete("session-1", UserSession.PipelineStage.BYBIT_NORMALIZATION, "Bybit complete");

        verify(userSessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(session.getPipelineState().getStage()).isEqualTo(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
        assertThat(session.getPipelineState().getStatus()).isEqualTo(UserSession.PipelineStatus.RUNNING);
    }

    @Test
    void preservesHigherPriorityParallelStageWhenPeerStageStartsRunning() {
        UserSession session = sessionWithState(
                UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                UserSession.PipelineStatus.RUNNING,
                "On-chain normalization running"
        );
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionPipelineStateService service = new SessionPipelineStateService(userSessionRepository);

        service.markStageRunning("session-1", UserSession.PipelineStage.BYBIT_NORMALIZATION, "Bybit running");

        verify(userSessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(session.getPipelineState().getStage()).isEqualTo(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
        assertThat(session.getPipelineState().getStatus()).isEqualTo(UserSession.PipelineStatus.RUNNING);
    }

    @Test
    void promotesHigherPriorityParallelStageWhenItStartsRunning() {
        UserSession session = sessionWithState(
                UserSession.PipelineStage.BYBIT_NORMALIZATION,
                UserSession.PipelineStatus.RUNNING,
                "Bybit running"
        );
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionPipelineStateService service = new SessionPipelineStateService(userSessionRepository);

        service.markStageRunning("session-1", UserSession.PipelineStage.ON_CHAIN_NORMALIZATION, "On-chain running");

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPipelineState().getStage()).isEqualTo(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
        assertThat(captor.getValue().getPipelineState().getStatus()).isEqualTo(UserSession.PipelineStatus.RUNNING);
    }

    @Test
    void updatesStateWhenRunningStageTransitionsIntoNextNonParallelPhase() {
        UserSession session = sessionWithState(
                UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                UserSession.PipelineStatus.RUNNING,
                "Clarification running"
        );
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionPipelineStateService service = new SessionPipelineStateService(userSessionRepository);

        service.markStageComplete("session-1", UserSession.PipelineStage.LINKING, "Linking complete");

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPipelineState().getStage()).isEqualTo(UserSession.PipelineStage.LINKING);
        assertThat(captor.getValue().getPipelineState().getStatus()).isEqualTo(UserSession.PipelineStatus.COMPLETE);
    }

    private UserSession sessionWithState(
            UserSession.PipelineStage stage,
            UserSession.PipelineStatus status,
            String message
    ) {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setCreatedAt(Instant.parse("2026-04-10T19:00:00Z"));
        session.setUpdatedAt(Instant.parse("2026-04-10T19:00:00Z"));
        UserSession.PipelineState pipelineState = new UserSession.PipelineState();
        pipelineState.setStage(stage);
        pipelineState.setStatus(status);
        pipelineState.setMessage(message);
        pipelineState.setUpdatedAt(Instant.parse("2026-04-10T19:00:00Z"));
        session.setPipelineState(pipelineState);
        return session;
    }
}
