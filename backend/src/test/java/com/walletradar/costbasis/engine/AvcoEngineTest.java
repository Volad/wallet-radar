package com.walletradar.costbasis.engine;

import com.walletradar.domain.accounting.AssetPosition;
import com.walletradar.domain.accounting.AssetPositionRepository;
import com.walletradar.domain.accounting.CostBasisOverride;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvcoEngineTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AssetPositionRepository assetPositionRepository;
    @Mock
    private CostBasisOverrideRepository costBasisOverrideRepository;

    private AvcoEngine avcoEngine;

    @BeforeEach
    void setUp() {
        avcoEngine = new AvcoEngine(normalizedTransactionRepository, assetPositionRepository, costBasisOverrideRepository);
    }

    @Test
    @DisplayName("replayFromBeginning computes AVCO and realised PnL from confirmed swap legs")
    void replayFromBeginning_computesFromConfirmedLegs() {
        NormalizedTransaction buyTx = tx(
                "tx-1",
                Instant.parse("2025-01-01T10:00:00Z"),
                NormalizedTransactionType.SWAP,
                leg(NormalizedLegRole.BUY, "ETH", "0xeth", new BigDecimal("2"), new BigDecimal("100"), 1)
        );
        NormalizedTransaction sellTx = tx(
                "tx-2",
                Instant.parse("2025-01-02T10:00:00Z"),
                NormalizedTransactionType.SWAP,
                leg(NormalizedLegRole.SELL, "ETH", "0xeth", new BigDecimal("-1"), new BigDecimal("150"), 1)
        );

        when(normalizedTransactionRepository.findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
                "0xwallet", NetworkId.ARBITRUM, NormalizedTransactionStatus.CONFIRMED))
                .thenReturn(List.of(buyTx, sellTx));
        when(costBasisOverrideRepository.findByNormalizedLegIdInAndActiveTrue(anyList())).thenReturn(List.of());
        when(assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract("0xwallet", "ARBITRUM", "0xeth"))
                .thenReturn(Optional.empty());

        avcoEngine.replayFromBeginning("0xwallet", NetworkId.ARBITRUM, "0xeth");

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());

        AssetPosition saved = positionCaptor.getValue();
        assertThat(saved.getQuantity()).isEqualByComparingTo("1");
        assertThat(saved.getPerWalletAvco()).isEqualByComparingTo("100");
        assertThat(saved.getTotalRealisedPnlUsd()).isEqualByComparingTo("50.000000000000000000");

        assertThat(sellTx.getFlows().get(0).getAvcoAtTimeOfSale()).isEqualByComparingTo("100");
        assertThat(sellTx.getFlows().get(0).getRealisedPnlUsd()).isEqualByComparingTo("50.000000000000000000");
    }

    @Test
    @DisplayName("replayFromBeginning applies active override by normalizedLegId")
    void replayFromBeginning_appliesOverride() {
        NormalizedTransaction buyTx = tx(
                "tx-10",
                Instant.parse("2025-01-01T10:00:00Z"),
                NormalizedTransactionType.SWAP,
                leg(NormalizedLegRole.BUY, "ETH", "0xeth", new BigDecimal("1"), new BigDecimal("100"), 1)
        );
        NormalizedTransaction sellTx = tx(
                "tx-20",
                Instant.parse("2025-01-02T10:00:00Z"),
                NormalizedTransactionType.SWAP,
                leg(NormalizedLegRole.SELL, "ETH", "0xeth", new BigDecimal("-1"), new BigDecimal("150"), 1)
        );

        CostBasisOverride override = new CostBasisOverride();
        override.setNormalizedLegId("tx-10:0");
        override.setPriceUsd(new BigDecimal("120"));
        override.setActive(true);

        when(normalizedTransactionRepository.findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
                "0xwallet", NetworkId.ARBITRUM, NormalizedTransactionStatus.CONFIRMED))
                .thenReturn(List.of(buyTx, sellTx));
        when(costBasisOverrideRepository.findByNormalizedLegIdInAndActiveTrue(anyList())).thenReturn(List.of(override));
        when(assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract("0xwallet", "ARBITRUM", "0xeth"))
                .thenReturn(Optional.of(new AssetPosition()));

        avcoEngine.replayFromBeginning("0xwallet", NetworkId.ARBITRUM, "0xeth");

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getTotalRealisedPnlUsd()).isEqualByComparingTo("30.000000000000000000");
    }

    @Test
    @DisplayName("recalculateForWallet does nothing when no confirmed normalized transactions")
    void recalculateForWallet_noData_noop() {
        when(normalizedTransactionRepository.findByWalletAddressAndStatusOrderByBlockTimestampAsc(
                "0xwallet", NormalizedTransactionStatus.CONFIRMED))
                .thenReturn(List.of());

        avcoEngine.recalculateForWallet("0xwallet");

        verify(assetPositionRepository, never()).save(any());
        verify(normalizedTransactionRepository, never()).saveAll(anyList());
        verify(costBasisOverrideRepository, never()).findByNormalizedLegIdInAndActiveTrue(anyList());
    }

    private static NormalizedTransaction tx(String id, Instant ts, NormalizedTransactionType type, NormalizedTransaction.Flow... legs) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash("0x" + id);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(ts);
        tx.setType(type);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setFlows(List.of(legs));
        return tx;
    }

    private static NormalizedTransaction.Flow leg(
            NormalizedLegRole role,
            String symbol,
            String contract,
            BigDecimal qty,
            BigDecimal unitPrice,
            Integer logIndex
    ) {
        NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
        leg.setRole(role);
        leg.setAssetSymbol(symbol);
        leg.setAssetContract(contract);
        leg.setQuantityDelta(qty);
        leg.setUnitPriceUsd(unitPrice);
        leg.setLogIndex(logIndex);
        return leg;
    }
}
