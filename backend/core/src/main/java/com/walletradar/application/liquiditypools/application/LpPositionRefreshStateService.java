package com.walletradar.application.liquiditypools.application;

import com.walletradar.api.dto.RefreshStatusResponse;
import com.walletradar.platform.common.refresh.RefreshStatus;
import com.walletradar.platform.common.refresh.RefreshTrigger;
import com.walletradar.application.liquiditypools.persistence.LpPositionRefreshState;
import com.walletradar.application.liquiditypools.persistence.LpPositionRefreshStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LpPositionRefreshStateService {

    private static final EnumSet<RefreshStatus> ACTIVE_STATUSES = EnumSet.of(
            RefreshStatus.QUEUED,
            RefreshStatus.UPDATING
    );

    private final LpPositionRefreshStateRepository repository;

    public void markQueued(String sessionId, String correlationId, RefreshTrigger trigger) {
        Instant now = Instant.now();
        LpPositionRefreshState state = repository.findById(correlationId).orElseGet(LpPositionRefreshState::new);
        state.setCorrelationId(correlationId);
        state.setSessionId(sessionId);
        state.setStatus(RefreshStatus.QUEUED);
        state.setTrigger(trigger);
        state.setRequestedAt(now);
        state.setStartedAt(null);
        state.setCompletedAt(null);
        state.setError(null);
        state.setUpdatedAt(now);
        repository.save(state);
    }

    public void markUpdating(String correlationId) {
        repository.findById(correlationId).ifPresent(state -> {
            Instant now = Instant.now();
            state.setStatus(RefreshStatus.UPDATING);
            if (state.getStartedAt() == null) {
                state.setStartedAt(now);
            }
            state.setUpdatedAt(now);
            repository.save(state);
        });
    }

    public void markSynced(String correlationId) {
        Instant now = Instant.now();
        repository.findById(correlationId).ifPresent(state -> {
            state.setStatus(RefreshStatus.SYNCED);
            state.setCompletedAt(now);
            state.setLastSyncedAt(now);
            state.setError(null);
            state.setUpdatedAt(now);
            repository.save(state);
        });
    }

    public void markFailed(String correlationId, String error) {
        Instant now = Instant.now();
        repository.findById(correlationId).ifPresent(state -> {
            state.setStatus(RefreshStatus.FAILED);
            state.setCompletedAt(now);
            state.setError(truncateError(error));
            state.setUpdatedAt(now);
            repository.save(state);
        });
    }

    public RefreshStatusResponse getStatus(String sessionId) {
        List<RefreshStatusResponse.Item> items = repository.findBySessionId(sessionId).stream()
                .map(this::toItem)
                .toList();
        boolean anyActive = items.stream()
                .anyMatch(item -> ACTIVE_STATUSES.contains(RefreshStatus.valueOf(item.status())));
        return new RefreshStatusResponse(sessionId, items, anyActive);
    }

    public boolean anyActive(String sessionId) {
        return !repository.findBySessionIdAndStatusIn(sessionId, List.copyOf(ACTIVE_STATUSES)).isEmpty();
    }

    public Optional<Instant> lastSessionRefreshAt(String sessionId) {
        return repository.findTopBySessionIdOrderByLastSyncedAtDesc(sessionId)
                .map(LpPositionRefreshState::getLastSyncedAt);
    }

    private RefreshStatusResponse.Item toItem(LpPositionRefreshState state) {
        return new RefreshStatusResponse.Item(
                state.getCorrelationId(),
                state.getStatus().name(),
                state.getTrigger() != null ? state.getTrigger().name() : null,
                state.getRequestedAt(),
                state.getStartedAt(),
                state.getCompletedAt(),
                state.getLastSyncedAt(),
                state.getError()
        );
    }

    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
