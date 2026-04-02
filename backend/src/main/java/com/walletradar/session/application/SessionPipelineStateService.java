package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persists one authoritative live-pipeline state per user session.
 */
@Service
@RequiredArgsConstructor
public class SessionPipelineStateService {

    private final UserSessionRepository userSessionRepository;

    public void markStageRunning(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.RUNNING, message);
    }

    public void markStageComplete(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.COMPLETE, message);
    }

    public void markStageFailed(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.FAILED, message);
    }

    private void update(String sessionId, UserSession.PipelineStage stage, UserSession.PipelineStatus status, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        userSessionRepository.findById(sessionId).ifPresent(session -> {
            UserSession.PipelineState state = session.getPipelineState();
            if (state == null) {
                state = new UserSession.PipelineState();
                session.setPipelineState(state);
            }
            state.setStage(stage);
            state.setStatus(status);
            state.setMessage(message);
            state.setUpdatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
            userSessionRepository.save(session);
        });
    }
}
