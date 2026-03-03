package com.walletradar.costbasis.engine;

import com.walletradar.domain.accounting.CostBasisOverride;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossWalletAvcoAggregatorServiceTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private CostBasisOverrideRepository costBasisOverrideRepository;

    @Test
    @DisplayName("compute aggregates AVCO across wallets from confirmed normalized legs")
    void compute_aggregatesConfirmedLegs() {
        CrossWalletAvcoAggregatorService service = new CrossWalletAvcoAggregatorService(
                normalizedTransactionRepository,
                costBasisOverrideRepository
        );

        NormalizedTransaction buyA = tx("tx-a", "0xA", Instant.parse("2025-01-01T10:00:00Z"), new BigDecimal("1"), new BigDecimal("100"));
        NormalizedTransaction buyB = tx("tx-b", "0xB", Instant.parse("2025-01-02T10:00:00Z"), new BigDecimal("1"), new BigDecimal("200"));

        when(normalizedTransactionRepository.findByWalletAddressAndStatusOrderByBlockTimestampAsc(
                "0xA", NormalizedTransactionStatus.CONFIRMED)).thenReturn(List.of(buyA));
        when(normalizedTransactionRepository.findByWalletAddressAndStatusOrderByBlockTimestampAsc(
                "0xB", NormalizedTransactionStatus.CONFIRMED)).thenReturn(List.of(buyB));
        when(costBasisOverrideRepository.findByNormalizedLegIdInAndActiveTrue(anyList())).thenReturn(List.of());

        CrossWalletAvcoResult result = service.compute(List.of("0xB", "0xA"), "ETH");

        assertThat(result.getCrossWalletAvco()).isEqualByComparingTo("150.000000000000000000");
        assertThat(result.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("compute applies override by normalizedLegId")
    void compute_appliesOverride() {
        CrossWalletAvcoAggregatorService service = new CrossWalletAvcoAggregatorService(
                normalizedTransactionRepository,
                costBasisOverrideRepository
        );

        NormalizedTransaction buyA = tx("tx-a", "0xA", Instant.parse("2025-01-01T10:00:00Z"), new BigDecimal("1"), new BigDecimal("100"));

        CostBasisOverride override = new CostBasisOverride();
        override.setNormalizedLegId("tx-a:0");
        override.setPriceUsd(new BigDecimal("130"));
        override.setActive(true);

        when(normalizedTransactionRepository.findByWalletAddressAndStatusOrderByBlockTimestampAsc(
                "0xA", NormalizedTransactionStatus.CONFIRMED)).thenReturn(List.of(buyA));
        when(costBasisOverrideRepository.findByNormalizedLegIdInAndActiveTrue(anyList())).thenReturn(List.of(override));

        CrossWalletAvcoResult result = service.compute(List.of("0xA"), "ETH");

        assertThat(result.getCrossWalletAvco()).isEqualByComparingTo("130");
        assertThat(result.getQuantity()).isEqualByComparingTo("1");
    }

    private static NormalizedTransaction tx(String id, String wallet, Instant ts, BigDecimal qty, BigDecimal price) {
        NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
        leg.setRole(NormalizedLegRole.BUY);
        leg.setAssetSymbol("ETH");
        leg.setAssetContract("0xeth");
        leg.setQuantityDelta(qty);
        leg.setUnitPriceUsd(price);
        leg.setLogIndex(1);

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash("0x" + id);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress(wallet);
        tx.setBlockTimestamp(ts);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setFlows(List.of(leg));
        return tx;
    }
}
