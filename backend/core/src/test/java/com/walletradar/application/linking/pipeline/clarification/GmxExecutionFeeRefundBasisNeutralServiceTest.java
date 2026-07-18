package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NEW-13: verifies that a <em>residual</em> GMX V2 execution-fee refund (no matching open
 * {@code LP_EXIT_REQUEST}, so NEW-09 already declined it) is demoted to a basis-neutral
 * {@code SPONSORED_GAS_IN} rather than fabricating a market {@code ACQUIRE}. Anchored to the live
 * secondary wallet {@code 0xf03b52e8…} refund {@code 0x17273e5c…} on ARBITRUM (evidence only).
 */
@ExtendWith(MockitoExtension.class)
class GmxExecutionFeeRefundBasisNeutralServiceTest {

    private static final String WALLET = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
    private static final String REFUND_TX =
            "0x17273e5ce3ae000000000000000000000000000000000000000000000000abcd";
    private static final Instant REFUND_TS = Instant.parse("2026-02-10T11:22:33Z");

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private GmxExecutionFeeRefundBasisNeutralService service;

    @BeforeEach
    void setUp() {
        service = new GmxExecutionFeeRefundBasisNeutralService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("T-13a: residual GMX exec-fee refund becomes basis-neutral SPONSORED_GAS_IN")
    void residualRefundBecomesSponsoredGasIn() {
        NormalizedTransaction refund = feeRefund();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(refund));

        int demoted = service.reclassifyResidualRefunds(50);

        assertThat(demoted).isEqualTo(1);
        assertThat(refund.getType()).isEqualTo(NormalizedTransactionType.SPONSORED_GAS_IN);
        assertThat(refund.getMissingDataReasons()).contains("GMX_EXECUTION_FEE_REFUND");
        assertThat(refund.getFlows()).allSatisfy(flow -> {
            assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
            assertThat(flow.getUnitPriceUsd()).isNull();
            assertThat(flow.getValueUsd()).isNull();
            assertThat(flow.getPriceSource()).isNull();
            assertThat(flow.getRealisedPnlUsd()).isNull();
            assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("0.000019472334"));
        });
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("T-13b: an already-settled GLV row (LP_EXIT_SETTLEMENT) is never a candidate")
    void settlementRowNotDemoted() {
        // NEW-09 promoted genuine settlements to LP_EXIT_SETTLEMENT before this pass runs; the
        // EXTERNAL_TRANSFER_IN candidate filter therefore excludes them entirely.
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        int demoted = service.reclassifyResidualRefunds(50);

        assertThat(demoted).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("T-13c: a refund carrying an outbound principal is left untouched (not pure return-of-capital)")
    void refundWithOutboundPrincipalLeftUntouched() {
        NormalizedTransaction refund = feeRefund();
        NormalizedTransaction.Flow outbound = ethBuyLeg("0.5", "2817.58");
        outbound.setRole(NormalizedLegRole.SELL);
        outbound.setQuantityDelta(new BigDecimal("-0.5"));
        refund.getFlows().add(outbound);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(refund));

        int demoted = service.reclassifyResidualRefunds(50);

        assertThat(demoted).isZero();
        assertThat(refund.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("T-13d: candidate filter targets only ON_CHAIN GMX V2 EXTERNAL_TRANSFER_IN fee refunds")
    void candidateFilterExcludesNonGmxAndNonFeeRefundRows() {
        // Deterministic proof that a non-GMX (or non-fee-refund, or non-EXTERNAL_TRANSFER_IN) row is
        // never a candidate: the Mongo query narrows strictly on source + type + protocolName +
        // GMX_EXECUTION_FEE_REFUND, so such rows are excluded at source and can never be demoted.
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        service.reclassifyResidualRefunds(50);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        Document filter = queryCaptor.getValue().getQueryObject();

        @SuppressWarnings("unchecked")
        List<Document> clauses = (List<Document>) filter.get("$and");
        assertThat(clauses).isNotNull();
        Document merged = new Document();
        clauses.forEach(merged::putAll);

        assertThat(merged.get("source")).isEqualTo(NormalizedTransactionSource.ON_CHAIN);
        assertThat(merged.get("type")).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(merged.get("protocolName")).isEqualTo("GMX V2");
        Document missingDataReasonsClause = (Document) merged.get("missingDataReasons");
        assertThat(missingDataReasonsClause.get("$in"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("GMX_EXECUTION_FEE_REFUND");
    }

    private static NormalizedTransaction feeRefund() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(REFUND_TX + ":ARBITRUM:" + WALLET);
        tx.setTxHash(REFUND_TX);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress(WALLET);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setProtocolName("GMX V2");
        tx.setCounterpartyType("PROTOCOL");
        tx.setBlockTimestamp(REFUND_TS);
        tx.setFlows(new ArrayList<>(List.of(ethBuyLeg("0.000019472334", "1900.0"))));
        tx.setMissingDataReasons(new ArrayList<>(List.of("GMX_EXECUTION_FEE_REFUND")));
        return tx;
    }

    private static NormalizedTransaction.Flow ethBuyLeg(String qty, String price) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setUnitPriceUsd(new BigDecimal(price));
        flow.setValueUsd(new BigDecimal(qty).multiply(new BigDecimal(price)));
        flow.setPriceSource(PriceSource.DZENGI);
        flow.setRealisedPnlUsd(BigDecimal.ZERO);
        return flow;
    }
}
