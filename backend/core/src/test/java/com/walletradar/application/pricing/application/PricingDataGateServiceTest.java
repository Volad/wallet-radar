package com.walletradar.application.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingDataGateServiceTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void snapshotMarksAvcoReadyWhenNoBlockingStatusesRemain() {
        when(mongoOperations.count(
                any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)
        )).thenReturn(0L, 0L, 0L, 0L, 3L, 12L);

        PricingDataGateSnapshot snapshot = new PricingDataGateService(mongoOperations).snapshot();

        assertThat(snapshot.avcoReady()).isTrue();
        assertThat(snapshot.excludedNeedsReviewCount()).isEqualTo(3L);
        assertThat(snapshot.unresolvedPriceCount()).isEqualTo(12L);
    }

    @Test
    void snapshotBlocksAvcoWhenPendingPriceStillExists() {
        when(mongoOperations.count(
                any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)
        )).thenReturn(5L, 0L, 0L, 0L, 0L, 0L);

        PricingDataGateSnapshot snapshot = new PricingDataGateService(mongoOperations).snapshot();

        assertThat(snapshot.avcoReady()).isFalse();
        assertThat(snapshot.pendingPriceCount()).isEqualTo(5L);
    }
}
