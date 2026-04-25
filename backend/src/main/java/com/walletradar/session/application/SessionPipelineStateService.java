package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Persists one authoritative live-pipeline state per user session.
 */
@Service
@RequiredArgsConstructor
public class SessionPipelineStateService {

    private static final Set<UserSession.PipelineStage> PARALLEL_CLASSIFICATION_STAGES = EnumSet.of(
            UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
            UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
            UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
            UserSession.PipelineStage.BYBIT_NORMALIZATION
    );

    private final UserSessionRepository userSessionRepository;
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public void markStageRunning(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.RUNNING, message);
    }

    public void markStageComplete(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.COMPLETE, message);
    }

    public void markStageBlocked(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.BLOCKED, message);
    }

    public void markStageFailed(String sessionId, UserSession.PipelineStage stage, String message) {
        update(sessionId, stage, UserSession.PipelineStatus.FAILED, message);
    }

    private void update(String sessionId, UserSession.PipelineStage stage, UserSession.PipelineStatus status, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        synchronized (lockFor(sessionId)) {
            userSessionRepository.findById(sessionId).ifPresent(session -> {
                UserSession.PipelineState state = session.getPipelineState();
                if (state == null) {
                    state = new UserSession.PipelineState();
                    session.setPipelineState(state);
                }
                if (shouldPreserveCurrentRunningState(state, stage, status)) {
                    return;
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

    private boolean shouldPreserveCurrentRunningState(
            UserSession.PipelineState currentState,
            UserSession.PipelineStage newStage,
            UserSession.PipelineStatus newStatus
    ) {
        if (currentState == null
                || currentState.getStatus() != UserSession.PipelineStatus.RUNNING
                || currentState.getStage() == null
                || !PARALLEL_CLASSIFICATION_STAGES.contains(currentState.getStage())
                || !PARALLEL_CLASSIFICATION_STAGES.contains(newStage)
                || currentState.getStage() == newStage) {
            return false;
        }
        if (newStatus == UserSession.PipelineStatus.RUNNING) {
            return stagePriority(currentState.getStage()) >= stagePriority(newStage);
        }
        return newStatus == UserSession.PipelineStatus.COMPLETE;
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private int stagePriority(UserSession.PipelineStage stage) {
        if (stage == null) {
            return Integer.MIN_VALUE;
        }
        return switch (stage) {
            case ON_CHAIN_RECLASSIFICATION -> 4;
            case ON_CHAIN_CLARIFICATION -> 3;
            case ON_CHAIN_NORMALIZATION -> 2;
            case BYBIT_NORMALIZATION -> 1;
            default -> 0;
        };
    }
}
