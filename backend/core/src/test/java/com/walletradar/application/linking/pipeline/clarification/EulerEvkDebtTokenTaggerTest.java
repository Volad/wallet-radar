package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BLOCKER-9 / ADR-057: Unit tests for {@link EulerEvkDebtTokenTagger}.
 */
class EulerEvkDebtTokenTaggerTest {

    private static final String DEBT_CONTRACT_A = "0x2eb15b5e4e5749bdd46a8cca48c500f69bd0df5d";
    private static final String DEBT_CONTRACT_B = "0x1d45674ec811f8a33c97616790bc5a81d4c9afac";

    // -------------------------------------------------------------------------
    // hasDebtTokenPositiveInflow
    // -------------------------------------------------------------------------

    @Test
    void detectsPositiveInflowFromDebtContractA() {
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_A, new BigDecimal("1.0"));
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(tx)).isTrue();
    }

    @Test
    void detectsPositiveInflowFromDebtContractB() {
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_B, new BigDecimal("1000"));
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(tx)).isTrue();
    }

    @Test
    void ignoresUppercaseContractAddress() {
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_A.toUpperCase(), new BigDecimal("5"));
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(tx)).isTrue();
    }

    @Test
    void tagsNegativeFlowFromDebtContract() {
        // Negative flows (debt burns in LENDING_DEPOSIT) must also be excluded so the engine
        // does not try to drain inventory that was never credited (the credit was in an excluded
        // LENDING_LOOP_REBALANCE transaction).
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_A, new BigDecimal("-1.0"));
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenFlow(tx)).isTrue();
    }

    @Test
    void ignoresZeroFlowFromDebtContract() {
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_A, BigDecimal.ZERO);
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(tx)).isFalse();
    }

    @Test
    void ignoresUnrelatedContract() {
        NormalizedTransaction tx = avalancheTxWithFlow("0xdeadbeef", new BigDecimal("1.0"));
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(tx)).isFalse();
    }

    @Test
    void handlesNullFlows() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.AVALANCHE);
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(tx)).isFalse();
    }

    @Test
    void handlesNullTx() {
        assertThat(EulerEvkDebtTokenTagger.hasDebtTokenPositiveInflow(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // tagIfEulerEvkDebtToken
    // -------------------------------------------------------------------------

    @Test
    void tagsAvalancheTxWithDebtTokenPositiveInflow() {
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_A, new BigDecimal("1.0"));
        Instant now = Instant.now();
        boolean changed = tagger().tagIfEulerEvkDebtToken(tx, now);

        assertThat(changed).isTrue();
        assertThat(tx.getExcludedFromAccounting()).isTrue();
        assertThat(tx.getAccountingExclusionReason()).isEqualTo(EulerEvkDebtTokenTagger.REASON);
        assertThat(tx.getMissingDataReasons()).contains(EulerEvkDebtTokenTagger.REASON);
        assertThat(tx.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void idempotentOnAlreadyTaggedTx() {
        NormalizedTransaction tx = avalancheTxWithFlow(DEBT_CONTRACT_A, new BigDecimal("1.0"));
        Instant now = Instant.now();
        tagger().tagIfEulerEvkDebtToken(tx, now);
        boolean secondCall = tagger().tagIfEulerEvkDebtToken(tx, now);

        assertThat(secondCall).isFalse();
        assertThat(tx.getMissingDataReasons()).containsOnlyOnce(EulerEvkDebtTokenTagger.REASON);
    }

    @Test
    void doesNotTagNonAvalancheTx() {
        NormalizedTransaction tx = txWithFlow(NetworkId.ETHEREUM, DEBT_CONTRACT_A, new BigDecimal("1.0"));
        boolean changed = tagger().tagIfEulerEvkDebtToken(tx, Instant.now());
        assertThat(changed).isFalse();
        assertThat(tx.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void doesNotTagTxWithoutDebtTokenFlow() {
        NormalizedTransaction tx = avalancheTxWithFlow("0xdeadbeef", new BigDecimal("1.0"));
        boolean changed = tagger().tagIfEulerEvkDebtToken(tx, Instant.now());
        assertThat(changed).isFalse();
        assertThat(tx.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static EulerEvkDebtTokenTagger tagger() {
        // Null repo/mongo: unit tests only exercise pure logic methods
        return new EulerEvkDebtTokenTagger(null, null);
    }

    private static NormalizedTransaction avalancheTxWithFlow(String contract, BigDecimal qty) {
        return txWithFlow(NetworkId.AVALANCHE, contract, qty);
    }

    private static NormalizedTransaction txWithFlow(NetworkId networkId, String contract, BigDecimal qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(networkId);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetContract(contract);
        flow.setQuantityDelta(qty);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
