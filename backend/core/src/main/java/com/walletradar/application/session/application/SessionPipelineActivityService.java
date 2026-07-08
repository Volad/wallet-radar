package com.walletradar.application.session.application;

import com.walletradar.domain.session.UserSession;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-memory activity for long-running pipeline stages so the resume
 * watchdog does not restart work that is still actively progressing inside the
 * current application instance.
 */
@Service
public class SessionPipelineActivityService {

    private final Map<String, Map<UserSession.PipelineStage, Instant>> activeStagesBySession = new ConcurrentHashMap<>();

    public void markRunning(String sessionId, UserSession.PipelineStage stage) {
        if (sessionId == null || sessionId.isBlank() || stage == null) {
            return;
        }
        activeStagesBySession
                .computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .put(stage, Instant.now());
    }

    public void heartbeat(String sessionId, UserSession.PipelineStage stage) {
        markRunning(sessionId, stage);
    }

    public void markFinished(String sessionId, UserSession.PipelineStage stage) {
        if (sessionId == null || sessionId.isBlank() || stage == null) {
            return;
        }
        activeStagesBySession.computeIfPresent(sessionId, (ignored, stages) -> {
            stages.remove(stage);
            return stages.isEmpty() ? null : stages;
        });
    }

    public boolean hasFreshActivity(String sessionId, Duration staleAfter) {
        return latestFreshActivity(sessionId, staleAfter).isPresent();
    }

    public boolean hasFreshActivity(String sessionId, UserSession.PipelineStage stage, Duration staleAfter) {
        if (sessionId == null || sessionId.isBlank() || stage == null) {
            return false;
        }
        Map<UserSession.PipelineStage, Instant> stages = activeStagesBySession.get(sessionId);
        if (stages == null || stages.isEmpty()) {
            return false;
        }
        Instant heartbeatAt = stages.get(stage);
        if (heartbeatAt == null) {
            return false;
        }
        return heartbeatAt.isAfter(Instant.now().minus(staleAfter));
    }

    public Optional<ActivitySnapshot> latestFreshActivity(String sessionId, Duration staleAfter) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Map<UserSession.PipelineStage, Instant> stages = activeStagesBySession.get(sessionId);
        if (stages == null || stages.isEmpty()) {
            return Optional.empty();
        }
        Instant cutoff = Instant.now().minus(staleAfter);
        UserSession.PipelineStage freshestStage = null;
        Instant freshestAt = null;
        for (Map.Entry<UserSession.PipelineStage, Instant> entry : stages.entrySet()) {
            Instant heartbeatAt = entry.getValue();
            if (heartbeatAt == null || heartbeatAt.isBefore(cutoff)) {
                continue;
            }
            if (freshestAt == null || heartbeatAt.isAfter(freshestAt)) {
                freshestStage = entry.getKey();
                freshestAt = heartbeatAt;
            }
        }
        if (freshestStage == null || freshestAt == null) {
            return Optional.empty();
        }
        return Optional.of(new ActivitySnapshot(freshestStage, freshestAt));
    }

    public record ActivitySnapshot(UserSession.PipelineStage stage, Instant heartbeatAt) {
    }
}
