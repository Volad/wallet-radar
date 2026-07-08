package com.walletradar.lending.application;

import com.walletradar.api.dto.RefreshStatusResponse;
import com.walletradar.platform.common.refresh.RefreshStatus;
import com.walletradar.platform.common.refresh.RefreshTrigger;
import com.walletradar.lending.persistence.LendingGroupRefreshState;
import com.walletradar.lending.persistence.LendingGroupRefreshStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LendingGroupRefreshStateService {

    private static final EnumSet<RefreshStatus> ACTIVE_STATUSES = EnumSet.of(
            RefreshStatus.QUEUED,
            RefreshStatus.UPDATING
    );

    private final LendingGroupRefreshStateRepository repository;

    public void markQueued(String sessionId, String groupId, RefreshTrigger trigger) {
        Instant now = Instant.now();
        LendingGroupRefreshState state = repository.findById(groupId).orElseGet(LendingGroupRefreshState::new);
        state.setGroupId(groupId);
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

    public void markUpdating(String groupId) {
        repository.findById(groupId).ifPresent(state -> {
            Instant now = Instant.now();
            state.setStatus(RefreshStatus.UPDATING);
            if (state.getStartedAt() == null) {
                state.setStartedAt(now);
            }
            state.setUpdatedAt(now);
            repository.save(state);
        });
    }

    public void markSynced(String groupId) {
        Instant now = Instant.now();
        repository.findById(groupId).ifPresent(state -> {
            state.setStatus(RefreshStatus.SYNCED);
            state.setCompletedAt(now);
            state.setLastSyncedAt(now);
            state.setError(null);
            state.setUpdatedAt(now);
            repository.save(state);
        });
    }

    public void markFailed(String groupId, String error) {
        Instant now = Instant.now();
        repository.findById(groupId).ifPresent(state -> {
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
                .map(LendingGroupRefreshState::getLastSyncedAt);
    }

    private RefreshStatusResponse.Item toItem(LendingGroupRefreshState state) {
        return new RefreshStatusResponse.Item(
                state.getGroupId(),
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
