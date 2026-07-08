package com.walletradar.liquiditypools.persistence;

import com.walletradar.platform.common.refresh.RefreshStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LpPositionRefreshStateRepository extends MongoRepository<LpPositionRefreshState, String> {

    List<LpPositionRefreshState> findBySessionId(String sessionId);

    List<LpPositionRefreshState> findBySessionIdAndStatusIn(String sessionId, List<RefreshStatus> statuses);

    Optional<LpPositionRefreshState> findTopBySessionIdOrderByLastSyncedAtDesc(String sessionId);

    List<LpPositionRefreshState> findByStatusAndStartedAtBefore(RefreshStatus status, Instant startedAtBefore);
}
