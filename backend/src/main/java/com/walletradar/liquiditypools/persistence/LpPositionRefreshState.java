package com.walletradar.liquiditypools.persistence;

import com.walletradar.common.refresh.RefreshStatus;
import com.walletradar.common.refresh.RefreshTrigger;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "lp_position_refresh_state")
@CompoundIndexes({
        @CompoundIndex(name = "lp_refresh_session_status_idx", def = "{'sessionId': 1, 'status': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LpPositionRefreshState {

    @Id
    @EqualsAndHashCode.Include
    private String correlationId;

    private String sessionId;
    private RefreshStatus status;
    private RefreshTrigger trigger;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant lastSyncedAt;
    private String error;
    private Instant updatedAt;
}
