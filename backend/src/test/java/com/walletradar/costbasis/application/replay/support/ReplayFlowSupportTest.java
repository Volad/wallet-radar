package com.walletradar.costbasis.application.replay.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayFlowSupportTest {

    @Test
    void copyTransactionRetainsCounterpartyAddress() {
        ReplayFlowSupport support = new ReplayFlowSupport(new GenericFlowReplayEngine());

        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("wallet-a");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.SWAP);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(Instant.parse("2026-04-22T10:00:00Z"));
        transaction.setCorrelationId("corr-1");
        transaction.setMatchedCounterparty("wallet-b");
        transaction.setCounterpartyAddress("0x1111111111111111111111111111111111111111");
        transaction.setFlows(List.of(flow()));

        NormalizedTransaction copy = support.copyTransaction(transaction);

        assertThat(copy.getCounterpartyAddress()).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(copy.getMatchedCounterparty()).isEqualTo("wallet-b");
        assertThat(copy.getFlows()).hasSize(1);
        assertThat(copy.getFlows().getFirst()).isNotSameAs(transaction.getFlows().getFirst());
    }

    private NormalizedTransaction.Flow flow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setAssetContract("0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        flow.setQuantityDelta(new BigDecimal("1"));
        return flow;
    }
}
