package com.walletradar.application.lending.persistence;

import com.walletradar.platform.common.refresh.RefreshStatus;
import com.walletradar.platform.common.refresh.RefreshTrigger;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "lending_group_refresh_state")
@CompoundIndexes({
        @CompoundIndex(name = "lending_refresh_session_status_idx", def = "{'sessionId': 1, 'status': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LendingGroupRefreshState {

    @Id
    @EqualsAndHashCode.Include
    private String groupId;

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
