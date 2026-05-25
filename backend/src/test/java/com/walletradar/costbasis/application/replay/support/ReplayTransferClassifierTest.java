package com.walletradar.costbasis.application.replay.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayTransferClassifierTest {

    private final ReplayAssetSupport assetSupport = new ReplayAssetSupport();
    private final ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
    private final ReplayTransferClassifier classifier = new ReplayTransferClassifier(keyFactory);

    @Test
    void bundleInternalTransferIsDetectedForMultiLegDrain() {
        NormalizedTransaction tx = bundleTransfer("bybit-it-bundle-v1:a|b|c", "27.90");

        assertThat(classifier.isBybitMultiLegBundleTransfer(tx)).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(0))).isFalse();
        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isFalse();
    }

    @Test
    void roundtripInternalTransferUsesStandardPendingQueue() {
        NormalizedTransaction tx = bundleTransfer("bybit-it-roundtrip-v1:a|b", "-68.665");

        assertThat(classifier.isBybitMultiLegBundleTransfer(tx)).isFalse();
    }

    @Test
    void lpPositionStakeOutboundAndInboundAreBucketEligible() {
        NormalizedTransaction tx = wrapperTx(NormalizedTransactionType.LP_POSITION_STAKE,
                "AAVE GHO/USDT/USDC", "0xfcec3c8d86329defb548202fe1b86ff2188603a8", "-100",
                "gauge", "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951", "100");

        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(1))).isTrue();
    }

    @Test
    void vaultDepositOutboundAndInboundAreBucketEligible() {
        NormalizedTransaction tx = wrapperTx(NormalizedTransactionType.VAULT_DEPOSIT,
                "AAVE GHO/USDT/USDC", "0xfcec3c8d86329defb548202fe1b86ff2188603a8", "-50",
                "auraVault", "0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc", "50");

        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(1))).isTrue();
    }

    @Test
    void lendingDepositWrapperStakeBucketsBothLegs() {
        // Cycle/15 R5 F1: AVAX Curve LP → gauge stake observed on prod as type=LENDING_DEPOSIT
        // (LP outbound + gauge inbound + AVAX FEE). Pre-fix the inbound gauge leg fell through
        // to the generic pending-transfer path and lost its basis carry.
        NormalizedTransaction tx = wrapperTx(NormalizedTransactionType.LENDING_DEPOSIT,
                "Aave GHO/USDT/USDC", "0xfcec3c8d86329defb548202fe1b86ff2188603a8", "-2144.92",
                "Aave GHO/USDT/USDC-gauge", "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951", "2144.92");

        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(1))).isTrue();
    }

    @Test
    void lendingWithdrawWrapperUnstakeBucketsBothLegs() {
        // Mirror case: gauge → LP unstake. Pre-fix the outbound gauge leg was not recognised
        // as bucket-outbound, breaking basis carry on unstake.
        NormalizedTransaction tx = wrapperTx(NormalizedTransactionType.LENDING_WITHDRAW,
                "Aave GHO/USDT/USDC-gauge", "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951", "-2144.92",
                "Aave GHO/USDT/USDC", "0xfcec3c8d86329defb548202fe1b86ff2188603a8", "2144.92");

        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(1))).isTrue();
    }

    @Test
    void stakingDepositWrapperBucketsBothLegs() {
        NormalizedTransaction tx = wrapperTx(NormalizedTransactionType.STAKING_DEPOSIT,
                "BAL", "0xba100000625a3754423978a60c9317c58a424e3d", "-10",
                "veBAL-Receipt", "0xc128a9954e6c874ea3d62ce62b468ba073093f25", "10");

        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(1))).isTrue();
    }

    @Test
    void protocolCustodyDepositWrapperBucketsBothLegs() {
        NormalizedTransaction tx = wrapperTx(NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT,
                "CRV", "0xd533a949740bb3306d119cc777fa900ba034cd52", "-25",
                "veCRV", "0x5f3b5dfeb7b28cdbd7faba78963ee202a494e2a2", "25");

        assertThat(classifier.isBucketOutbound(tx, tx.getFlows().get(0))).isTrue();
        assertThat(classifier.isBucketInbound(tx, tx.getFlows().get(1))).isTrue();
    }

    private static NormalizedTransaction wrapperTx(
            NormalizedTransactionType type,
            String outSymbol,
            String outContract,
            String outQty,
            String inSymbol,
            String inContract,
            String inQty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(type);
        tx.setWalletAddress("0xwallet");
        NormalizedTransaction.Flow outbound = new NormalizedTransaction.Flow();
        outbound.setRole(NormalizedLegRole.TRANSFER);
        outbound.setAssetSymbol(outSymbol);
        outbound.setAssetContract(outContract);
        outbound.setQuantityDelta(new BigDecimal(outQty));
        NormalizedTransaction.Flow inbound = new NormalizedTransaction.Flow();
        inbound.setRole(NormalizedLegRole.TRANSFER);
        inbound.setAssetSymbol(inSymbol);
        inbound.setAssetContract(inContract);
        inbound.setQuantityDelta(new BigDecimal(inQty));
        tx.setFlows(List.of(outbound, inbound));
        return tx;
    }

    private static NormalizedTransaction bundleTransfer(String correlationId, String qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setWalletAddress("BYBIT:42:EARN");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("LDO");
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(List.of(flow));
        return tx;
    }
}
