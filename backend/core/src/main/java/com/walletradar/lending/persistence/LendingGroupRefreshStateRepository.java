package com.walletradar.lending.persistence;

import com.walletradar.platform.common.refresh.RefreshStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LendingGroupRefreshStateRepository extends MongoRepository<LendingGroupRefreshState, String> {

    List<LendingGroupRefreshState> findBySessionId(String sessionId);

    List<LendingGroupRefreshState> findBySessionIdAndStatusIn(String sessionId, List<RefreshStatus> statuses);

    Optional<LendingGroupRefreshState> findTopBySessionIdOrderByLastSyncedAtDesc(String sessionId);
}
