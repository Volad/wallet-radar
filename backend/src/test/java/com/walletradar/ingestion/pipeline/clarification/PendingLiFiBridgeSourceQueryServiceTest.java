package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingLiFiBridgeSourceQueryServiceTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void loadNextBatchRestrictsToOutstandingBridgeOutRows() {
        when(mongoOperations.find(org.mockito.ArgumentMatchers.any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        PendingLiFiBridgeSourceQueryService service = new PendingLiFiBridgeSourceQueryService(mongoOperations);

        service.loadNextBatch(25);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        String queryString = queryCaptor.getValue().getQueryObject().toString();
        assertThat(queryString).contains("source=ON_CHAIN");
        assertThat(queryString).contains("type=BRIDGE_OUT");
        assertThat(queryString).contains("correlationId");
        assertThat(queryString).contains("matchedCounterparty");
    }
}
