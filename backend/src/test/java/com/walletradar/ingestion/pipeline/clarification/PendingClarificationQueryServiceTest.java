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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingClarificationQueryServiceTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void includesAllPendingClarificationRowsRegardlessOfMissingReason() {
        PendingClarificationQueryService service = new PendingClarificationQueryService(mongoOperations);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        service.loadNextBatch(10, 2, 120);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        String queryText = String.valueOf(queryCaptor.getValue().getQueryObject());

        assertThat(queryText).doesNotContain("missingDataReasons");
        assertThat(queryText).doesNotContain("$nin");
    }
}
