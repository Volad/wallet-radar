package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.session.SessionBridgeStatus;
import com.walletradar.domain.transaction.session.SessionTransaction;
import com.walletradar.domain.transaction.session.SessionTransactionSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionBridgeLifecycleResolverTest {

    private final SessionBridgeLifecycleResolver resolver = new SessionBridgeLifecycleResolver();

    @Test
    @DisplayName("matches unique bridge legs across networks")
    void matchesUniqueBridgeLegsAcrossNetworks() {
        SessionTransaction bridgeOut = bridgeTx(
                "out-1",
                "0xout",
                NetworkId.ETHEREUM,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                Instant.parse("2026-03-05T10:00:00Z"),
                new BigDecimal("-100"),
                new BigDecimal("-100"));
        SessionTransaction bridgeIn = bridgeTx(
                "in-1",
                "0xin",
                NetworkId.ARBITRUM,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_INBOUND,
                Instant.parse("2026-03-05T10:12:00Z"),
                new BigDecimal("99.60"),
                new BigDecimal("99.60"));

        resolver.apply(List.of(bridgeOut, bridgeIn));

        assertThat(bridgeOut.getBridgeStatus()).isEqualTo(SessionBridgeStatus.MATCHED);
        assertThat(bridgeIn.getBridgeStatus()).isEqualTo(SessionBridgeStatus.MATCHED);
        assertThat(bridgeOut.getBridgePairKey()).isEqualTo("out-1::in-1");
        assertThat(bridgeIn.getBridgePairKey()).isEqualTo("out-1::in-1");
    }

    @Test
    @DisplayName("matches opposite leg even when destination timestamp is earlier")
    void matchesOutOfOrderBridgeLegs() {
        SessionTransaction bridgeOut = bridgeTx(
                "out-2",
                "0xout2",
                NetworkId.BASE,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                Instant.parse("2026-03-05T10:05:00Z"),
                new BigDecimal("-3"),
                new BigDecimal("-6"));
        SessionTransaction bridgeIn = bridgeTx(
                "in-2",
                "0xin2",
                NetworkId.OPTIMISM,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_INBOUND,
                Instant.parse("2026-03-05T10:01:00Z"),
                new BigDecimal("3"),
                new BigDecimal("6"));

        resolver.apply(List.of(bridgeOut, bridgeIn));

        assertThat(bridgeOut.getBridgeStatus()).isEqualTo(SessionBridgeStatus.MATCHED);
        assertThat(bridgeIn.getBridgeStatus()).isEqualTo(SessionBridgeStatus.MATCHED);
    }

    @Test
    @DisplayName("keeps unmatched transfer rows as bridge in or bridge out")
    void keepsUnmatchedTransferRowsAsBridgePending() {
        SessionTransaction bridgeOut = bridgeTx(
                "out-3",
                "0xout3",
                NetworkId.ETHEREUM,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                Instant.parse("2026-03-05T10:00:00Z"),
                new BigDecimal("-1"),
                new BigDecimal("-1"));
        SessionTransaction bridgeIn = bridgeTx(
                "in-3",
                "0xin3",
                NetworkId.ARBITRUM,
                "0xwallet-b",
                NormalizedTransactionType.EXTERNAL_INBOUND,
                Instant.parse("2026-03-08T12:00:00Z"),
                new BigDecimal("2"),
                new BigDecimal("2"));

        resolver.apply(List.of(bridgeOut, bridgeIn));

        assertThat(bridgeOut.getBridgeStatus()).isEqualTo(SessionBridgeStatus.BRIDGE_OUT);
        assertThat(bridgeOut.getBridgePairKey()).isNull();
        assertThat(bridgeIn.getBridgeStatus()).isEqualTo(SessionBridgeStatus.BRIDGE_IN);
        assertThat(bridgeIn.getBridgePairKey()).isNull();
    }

    @Test
    @DisplayName("marks ambiguous candidate graph as review and stays deterministic on re-run")
    void marksAmbiguousCandidateGraphAsReview() {
        SessionTransaction bridgeOut = bridgeTx(
                "out-4",
                "0xout4",
                NetworkId.ETHEREUM,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                Instant.parse("2026-03-05T10:00:00Z"),
                new BigDecimal("-50"),
                new BigDecimal("-50"));
        SessionTransaction bridgeInFirst = bridgeTx(
                "in-4a",
                "0xin4a",
                NetworkId.ARBITRUM,
                "0xwallet-a",
                NormalizedTransactionType.EXTERNAL_INBOUND,
                Instant.parse("2026-03-05T10:03:00Z"),
                new BigDecimal("50"),
                new BigDecimal("50"));
        SessionTransaction bridgeInSecond = bridgeTx(
                "in-4b",
                "0xin4b",
                NetworkId.BASE,
                "0xwallet-b",
                NormalizedTransactionType.EXTERNAL_INBOUND,
                Instant.parse("2026-03-05T10:04:00Z"),
                new BigDecimal("50"),
                new BigDecimal("50"));

        List<SessionTransaction> rows = List.of(bridgeOut, bridgeInFirst, bridgeInSecond);
        resolver.apply(rows);
        resolver.apply(rows);

        assertThat(bridgeOut.getBridgeStatus()).isEqualTo(SessionBridgeStatus.REVIEW);
        assertThat(bridgeInFirst.getBridgeStatus()).isEqualTo(SessionBridgeStatus.REVIEW);
        assertThat(bridgeInSecond.getBridgeStatus()).isEqualTo(SessionBridgeStatus.REVIEW);
        assertThat(bridgeOut.getBridgePairKey()).isNull();
        assertThat(bridgeInFirst.getBridgePairKey()).isNull();
        assertThat(bridgeInSecond.getBridgePairKey()).isNull();
    }

    private static SessionTransaction bridgeTx(
            String sourceId,
            String txHash,
            NetworkId networkId,
            String walletAddress,
            NormalizedTransactionType type,
            Instant blockTimestamp,
            BigDecimal quantityDelta,
            BigDecimal valueUsd
    ) {
        SessionTransaction row = new SessionTransaction();
        row.setId("session-1:CHAIN:" + sourceId);
        row.setSessionId("session-1");
        row.setSourceType(SessionTransactionSourceType.CHAIN);
        row.setSourceId(sourceId);
        row.setTxHash(txHash);
        row.setNetworkId(networkId);
        row.setWalletAddress(walletAddress);
        row.setBlockTimestamp(blockTimestamp);
        row.setType(type);
        row.setSortKey(blockTimestamp.toString() + ":" + txHash);

        SessionTransaction.Flow primaryFlow = new SessionTransaction.Flow();
        primaryFlow.setRole(NormalizedLegRole.TRANSFER);
        primaryFlow.setAssetSymbol("USDC");
        primaryFlow.setAssetContract("0xasset");
        primaryFlow.setQuantityDelta(quantityDelta);
        primaryFlow.setValueUsd(valueUsd);

        SessionTransaction.Flow feeFlow = new SessionTransaction.Flow();
        feeFlow.setRole(NormalizedLegRole.FEE);
        feeFlow.setAssetSymbol("ETH");
        feeFlow.setQuantityDelta(new BigDecimal("-0.0001"));

        row.setFlows(List.of(primaryFlow, feeFlow));
        return row;
    }
}
