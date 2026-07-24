package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainBalanceRefreshQueryServiceTest {

    private static final String WALLET = "0xa0dd1234567890abcdef1234567890abcdef1234";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private TrackedWalletRepository trackedWalletRepository;

    private OnChainBalanceRefreshQueryService service() {
        return new OnChainBalanceRefreshQueryService(mongoOperations, trackedWalletRepository);
    }

    @SuppressWarnings("unchecked")
    private void stubFlowCandidates(List<Document> flowRows) {
        AggregationResults<Document> results = new AggregationResults<>(flowRows, new Document());
        when(mongoOperations.aggregate(any(Aggregation.class), eq(NormalizedTransaction.class), eq(Document.class)))
                .thenReturn(results);
    }

    private LpReceiptBasisPool receiptPool(String symbol, String contract) {
        LpReceiptBasisPool pool = new LpReceiptBasisPool();
        pool.setWalletAddress(WALLET);
        pool.setNetworkId(NetworkId.MANTLE);
        pool.setAssetSymbol(symbol);
        pool.setAssetContract(contract);
        pool.setQtyHeld(BigDecimal.ZERO);
        return pool;
    }

    @Test
    void emitsCandidateForBurnedLpReceiptThatFlowUniverseDrops() {
        // The flow universe drops a fully-burned receipt (nets to zero). C3 adds it back from
        // lp_receipt_basis_pools so the background refresh writes an explicit authoritative-zero row.
        stubFlowCandidates(List.of());
        when(mongoOperations.find(any(Query.class), eq(LpReceiptBasisPool.class)))
                .thenReturn(List.of(receiptPool("PENDLE-LPT", "0xmarket")));

        List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> candidates =
                service().loadCandidates(List.of(WALLET));

        assertThat(candidates).hasSize(1);
        OnChainBalanceRefreshQueryService.BalanceRefreshCandidate candidate = candidates.getFirst();
        assertThat(candidate.walletAddress()).isEqualTo(WALLET);
        assertThat(candidate.networkId()).isEqualTo(NetworkId.MANTLE);
        assertThat(candidate.assetSymbol()).isEqualTo("PENDLE-LPT");
        assertThat(candidate.assetContract()).isEqualTo("0xmarket");
    }

    @Test
    void doesNotDuplicateWhenReceiptAlsoPresentInFlowUniverse() {
        // A still-open receipt appears in BOTH the flow universe and the basis pools — merge dedupes it.
        Document flowRow = new Document("walletAddress", WALLET)
                .append("networkId", NetworkId.MANTLE.name())
                .append("assetSymbol", "PENDLE-LPT")
                .append("assetContract", "0xmarket");
        stubFlowCandidates(List.of(flowRow));
        when(mongoOperations.find(any(Query.class), eq(LpReceiptBasisPool.class)))
                .thenReturn(List.of(receiptPool("PENDLE-LPT", "0xmarket")));

        List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> candidates =
                service().loadCandidates(List.of(WALLET));

        assertThat(candidates).hasSize(1);
    }

    @Test
    void skipsBasisPoolsWithNeitherSymbolNorContract() {
        stubFlowCandidates(List.of());
        when(mongoOperations.find(any(Query.class), eq(LpReceiptBasisPool.class)))
                .thenReturn(List.of(receiptPool(null, null)));

        assertThat(service().loadCandidates(List.of(WALLET))).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoTrackedWallets() {
        assertThat(service().loadCandidates(List.of())).isEmpty();
    }
}
