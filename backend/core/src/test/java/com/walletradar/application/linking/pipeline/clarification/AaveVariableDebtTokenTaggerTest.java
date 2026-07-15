package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-3: Unit tests for {@link AaveVariableDebtTokenTagger}.
 */
class AaveVariableDebtTokenTaggerTest {

    private static final String VARIABLE_DEBT_USDT = "0xfb00ac187a8eb5afae4eace434f493eb62672df7";
    private static final String VARIABLE_DEBT_EURC = "0x5d557b07776d12967914379c71a1310e917c7555";
    private static final String UNRELATED_CONTRACT  = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    // -------------------------------------------------------------------------
    // hasVariableDebtTokenFlow
    // -------------------------------------------------------------------------

    @Test
    void detectsPositiveInflowFromVariableDebtUSDT() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_USDT, new BigDecimal("1.0"));
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isTrue();
    }

    @Test
    void detectsPositiveInflowFromVariableDebtEURC() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_EURC, new BigDecimal("500"));
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isTrue();
    }

    @Test
    void detectsNegativeOutboundFromVariableDebtUSDT() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_USDT, new BigDecimal("-1.0"));
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isTrue();
    }

    @Test
    void detectsNegativeOutboundFromVariableDebtEURC() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_EURC, new BigDecimal("-200"));
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isTrue();
    }

    @Test
    void ignoresUppercaseContractAddress() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_USDT.toUpperCase(), new BigDecimal("10"));
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isTrue();
    }

    @Test
    void ignoresZeroFlowFromDebtContract() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_USDT, BigDecimal.ZERO);
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isFalse();
    }

    @Test
    void ignoresUnrelatedContract() {
        NormalizedTransaction tx = avalancheTxWithFlow(UNRELATED_CONTRACT, new BigDecimal("1.0"));
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isFalse();
    }

    @Test
    void handlesNullFlows() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.AVALANCHE);
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(tx)).isFalse();
    }

    @Test
    void handlesNullTx() {
        assertThat(AaveVariableDebtTokenTagger.hasVariableDebtTokenFlow(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // tagIfAaveVariableDebtToken
    // -------------------------------------------------------------------------

    @Test
    void tagsAvalancheTxWithDebtTokenPositiveInflow() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_USDT, new BigDecimal("1.0"));
        Instant now = Instant.now();
        boolean changed = tagger().tagIfAaveVariableDebtToken(tx, now);

        assertThat(changed).isTrue();
        assertThat(tx.getExcludedFromAccounting()).isTrue();
        assertThat(tx.getAccountingExclusionReason()).isEqualTo(AaveVariableDebtTokenTagger.REASON);
        assertThat(tx.getMissingDataReasons()).contains(AaveVariableDebtTokenTagger.REASON);
        assertThat(tx.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void tagsAvalancheTxWithDebtTokenNegativeOutbound() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_EURC, new BigDecimal("-50"));
        Instant now = Instant.now();
        boolean changed = tagger().tagIfAaveVariableDebtToken(tx, now);

        assertThat(changed).isTrue();
        assertThat(tx.getExcludedFromAccounting()).isTrue();
        assertThat(tx.getAccountingExclusionReason()).isEqualTo(AaveVariableDebtTokenTagger.REASON);
        assertThat(tx.getMissingDataReasons()).contains(AaveVariableDebtTokenTagger.REASON);
    }

    @Test
    void idempotentOnAlreadyTaggedTx() {
        NormalizedTransaction tx = avalancheTxWithFlow(VARIABLE_DEBT_USDT, new BigDecimal("1.0"));
        Instant now = Instant.now();
        tagger().tagIfAaveVariableDebtToken(tx, now);
        boolean secondCall = tagger().tagIfAaveVariableDebtToken(tx, now);

        assertThat(secondCall).isFalse();
        assertThat(tx.getMissingDataReasons()).containsOnlyOnce(AaveVariableDebtTokenTagger.REASON);
    }

    @Test
    void doesNotTagNonAvalancheTx() {
        NormalizedTransaction tx = txWithFlow(NetworkId.ETHEREUM, VARIABLE_DEBT_USDT, new BigDecimal("1.0"));
        boolean changed = tagger().tagIfAaveVariableDebtToken(tx, Instant.now());
        assertThat(changed).isFalse();
        assertThat(tx.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void doesNotTagArbitrumTxEvenIfContractMatches() {
        NormalizedTransaction tx = txWithFlow(NetworkId.ARBITRUM, VARIABLE_DEBT_USDT, new BigDecimal("1.0"));
        boolean changed = tagger().tagIfAaveVariableDebtToken(tx, Instant.now());
        assertThat(changed).isFalse();
        assertThat(tx.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void doesNotTagTxWithoutDebtTokenFlow() {
        NormalizedTransaction tx = avalancheTxWithFlow(UNRELATED_CONTRACT, new BigDecimal("1.0"));
        boolean changed = tagger().tagIfAaveVariableDebtToken(tx, Instant.now());
        assertThat(changed).isFalse();
        assertThat(tx.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static AaveVariableDebtTokenTagger tagger() {
        return new AaveVariableDebtTokenTagger(null, null);
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
