package com.walletradar.platform.telemetry;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineTelemetrySnapshotServiceTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void snapshotAggregatesOperationalCountersAcrossCollections() {
        when(mongoOperations.count(any(org.springframework.data.mongodb.core.query.Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(10L, 2L, 1L, 3L, 4L, 6L, 2L);
        when(mongoOperations.count(any(org.springframework.data.mongodb.core.query.Query.class), eq(ExternalLedgerRaw.class)))
                .thenReturn(5L);
        when(mongoOperations.count(any(org.springframework.data.mongodb.core.query.Query.class), eq(BybitExtractedEvent.class)))
                .thenReturn(7L);

        PipelineTelemetrySnapshot snapshot = new PipelineTelemetrySnapshotService(mongoOperations).snapshot();

        assertThat(snapshot.onChainNormalizedCount()).isEqualTo(10L);
        assertThat(snapshot.bybitNormalizedCount()).isEqualTo(2L);
        assertThat(snapshot.pendingStatCount()).isEqualTo(1L);
        assertThat(snapshot.unmatchedBybitBridgeCount()).isEqualTo(12L);
        assertThat(snapshot.orphanUtaLegCount()).isEqualTo(3L);
        assertThat(snapshot.unresolvedPriceCount()).isEqualTo(4L);
        assertThat(snapshot.needsReviewCount()).isEqualTo(6L);
        assertThat(snapshot.excludedNeedsReviewCount()).isEqualTo(2L);
    }
}
