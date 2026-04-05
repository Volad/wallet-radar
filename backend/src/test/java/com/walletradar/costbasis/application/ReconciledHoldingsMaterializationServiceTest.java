package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetPosition;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.costbasis.domain.ReconciledHolding;
import com.walletradar.costbasis.domain.ReconciledHoldingRepository;
import com.walletradar.costbasis.domain.ReconciliationStatus;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ReconciledHoldingsMaterializationServiceTest {

    @Mock
    private AssetPositionRepository assetPositionRepository;
    @Mock
    private OnChainBalanceRepository onChainBalanceRepository;
    @Mock
    private ReconciledHoldingRepository reconciledHoldingRepository;

    @Test
    void materializeCopiesLiveQuantityAndBasisForMatchedRow() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.MANTLE)
                .setAssetSymbol("AMANWETH")
                .setAssetContract("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c")
                .setQuantity(new BigDecimal("3.06"))
                .setPerWalletAvco(new BigDecimal("1977.88"))
                .setTotalCostBasisUsd(new BigDecimal("6052.31"))
                .setReconciliationStatus(ReconciliationStatus.MATCH);

        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.MANTLE)
                .setAssetSymbol("AMANWETH")
                .setAssetContract("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c")
                .setQuantity(new BigDecimal("3.06"))
                .setCapturedAt(Instant.parse("2026-04-03T12:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        ReconciledHoldingsMaterializationService service = new ReconciledHoldingsMaterializationService(
                assetPositionRepository,
                onChainBalanceRepository,
                reconciledHoldingRepository
        );

        service.materialize(Instant.parse("2026-04-03T12:01:00Z"));

        verify(reconciledHoldingRepository).deleteAll();
        ArgumentCaptor<List<ReconciledHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciledHoldingRepository).saveAll(captor.capture());
        ReconciledHolding saved = captor.getValue().getFirst();
        assertThat(saved.getCurrentQuantity()).isEqualByComparingTo("3.06");
        assertThat(saved.getCurrentHolding()).isTrue();
        assertThat(saved.getDerivedQuantity()).isEqualByComparingTo("3.06");
        assertThat(saved.getBasisBackedDerivedQuantity()).isEqualByComparingTo("3.06");
        assertThat(saved.getCurrentCoveredQuantity()).isEqualByComparingTo("3.06");
        assertThat(saved.getCurrentUncoveredQuantity()).isZero();
        assertThat(saved.getCurrentCostBasisProvable()).isTrue();
        assertThat(saved.getPerWalletAvco()).isEqualByComparingTo("1977.88");
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCH);
    }

    @Test
    void materializePersistsZeroLiveQuantityAsNonCurrentHolding() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.ZKSYNC)
                .setAssetSymbol("WETH")
                .setAssetContract("0x5aea5775959fbc2557cc8789bc1bf90a239d9a91")
                .setQuantity(new BigDecimal("0.25"))
                .setReconciliationStatus(ReconciliationStatus.MISMATCH);

        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.ZKSYNC)
                .setAssetSymbol("WETH")
                .setAssetContract("0x5aea5775959fbc2557cc8789bc1bf90a239d9a91")
                .setQuantity(BigDecimal.ZERO)
                .setCapturedAt(Instant.parse("2026-04-03T12:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        ReconciledHoldingsMaterializationService service = new ReconciledHoldingsMaterializationService(
                assetPositionRepository,
                onChainBalanceRepository,
                reconciledHoldingRepository
        );

        service.materialize(Instant.parse("2026-04-03T12:01:00Z"));

        ArgumentCaptor<List<ReconciledHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciledHoldingRepository).saveAll(captor.capture());
        ReconciledHolding saved = captor.getValue().getFirst();
        assertThat(saved.getCurrentQuantity()).isEqualByComparingTo("0");
        assertThat(saved.getCurrentHolding()).isFalse();
        assertThat(saved.getCurrentCoveredQuantity()).isZero();
        assertThat(saved.getCurrentUncoveredQuantity()).isZero();
        assertThat(saved.getCurrentCostBasisProvable()).isTrue();
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MISMATCH);
    }

    @Test
    void materializeKeepsLiveBalanceWhenReplayPositionIsMissing() {
        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.KATANA)
                .setAssetSymbol("ETH")
                .setAssetContract("NATIVE:KATANA")
                .setQuantity(new BigDecimal("0.002"))
                .setCapturedAt(Instant.parse("2026-04-03T12:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of());
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        ReconciledHoldingsMaterializationService service = new ReconciledHoldingsMaterializationService(
                assetPositionRepository,
                onChainBalanceRepository,
                reconciledHoldingRepository
        );

        service.materialize(Instant.parse("2026-04-03T12:01:00Z"));

        ArgumentCaptor<List<ReconciledHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciledHoldingRepository).saveAll(captor.capture());
        ReconciledHolding saved = captor.getValue().getFirst();
        assertThat(saved.getCurrentHolding()).isTrue();
        assertThat(saved.getDerivedQuantity()).isNull();
        assertThat(saved.getBasisBackedDerivedQuantity()).isZero();
        assertThat(saved.getCurrentCoveredQuantity()).isZero();
        assertThat(saved.getCurrentUncoveredQuantity()).isEqualByComparingTo("0.002");
        assertThat(saved.getCurrentCostBasisProvable()).isFalse();
        assertThat(saved.getPerWalletAvco()).isNull();
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NOT_APPLICABLE);
    }

    @Test
    void materializeSurfacesUncoveredCurrentQuantityWhenReplayHasShortfall() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.BASE)
                .setAssetSymbol("ETH")
                .setAssetContract("NATIVE:BASE")
                .setQuantity(BigDecimal.ZERO)
                .setQuantityShortfall(new BigDecimal("1.032898112606630091"))
                .setTotalCostBasisUsd(BigDecimal.ZERO)
                .setHasIncompleteHistory(true)
                .setHasUnresolvedFlags(true)
                .setUnresolvedFlagCount(12)
                .setReconciliationStatus(ReconciliationStatus.MISMATCH);

        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.BASE)
                .setAssetSymbol("ETH")
                .setAssetContract("NATIVE:BASE")
                .setQuantity(new BigDecimal("0.001092538442123013"))
                .setCapturedAt(Instant.parse("2026-04-04T18:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        ReconciledHoldingsMaterializationService service = new ReconciledHoldingsMaterializationService(
                assetPositionRepository,
                onChainBalanceRepository,
                reconciledHoldingRepository
        );

        service.materialize(Instant.parse("2026-04-04T18:01:00Z"));

        ArgumentCaptor<List<ReconciledHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciledHoldingRepository).saveAll(captor.capture());
        ReconciledHolding saved = captor.getValue().getFirst();
        assertThat(saved.getBasisBackedDerivedQuantity()).isZero();
        assertThat(saved.getCurrentCoveredQuantity()).isZero();
        assertThat(saved.getCurrentUncoveredQuantity()).isEqualByComparingTo("0.001092538442123013");
        assertThat(saved.getCurrentCostBasisProvable()).isFalse();
        assertThat(saved.getQuantityShortfall()).isEqualByComparingTo("1.032898112606630091");
    }

    @Test
    void materializeLimitsBasisCoveredCurrentQuantityToDerivedReplayPrincipal() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.MANTLE)
                .setAssetSymbol("AMANWETH")
                .setAssetContract("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c")
                .setQuantity(new BigDecimal("3.059999999999999999"))
                .setQuantityShortfall(BigDecimal.ZERO)
                .setTotalCostBasisUsd(new BigDecimal("5818.83"))
                .setReconciliationStatus(ReconciliationStatus.MISMATCH);

        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.MANTLE)
                .setAssetSymbol("AMANWETH")
                .setAssetContract("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c")
                .setQuantity(new BigDecimal("3.065663787278869490"))
                .setCapturedAt(Instant.parse("2026-04-04T18:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        ReconciledHoldingsMaterializationService service = new ReconciledHoldingsMaterializationService(
                assetPositionRepository,
                onChainBalanceRepository,
                reconciledHoldingRepository
        );

        service.materialize(Instant.parse("2026-04-04T18:01:00Z"));

        ArgumentCaptor<List<ReconciledHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciledHoldingRepository).saveAll(captor.capture());
        ReconciledHolding saved = captor.getValue().getFirst();
        assertThat(saved.getBasisBackedDerivedQuantity()).isEqualByComparingTo("3.059999999999999999");
        assertThat(saved.getCurrentCoveredQuantity()).isEqualByComparingTo("3.059999999999999999");
        assertThat(saved.getCurrentUncoveredQuantity()).isEqualByComparingTo("0.005663787278869491");
        assertThat(saved.getCurrentCostBasisProvable()).isFalse();
    }

    @Test
    void materializeSkipsBybitRows() {
        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("BYBIT:33625378")
                .setNetworkId(NetworkId.ETHEREUM)
                .setAssetSymbol("ETH")
                .setAssetContract("NATIVE:ETHEREUM")
                .setQuantity(new BigDecimal("1"));

        when(assetPositionRepository.findAll()).thenReturn(List.of());
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        ReconciledHoldingsMaterializationService service = new ReconciledHoldingsMaterializationService(
                assetPositionRepository,
                onChainBalanceRepository,
                reconciledHoldingRepository
        );

        int materialized = service.materialize(Instant.parse("2026-04-03T12:01:00Z"));

        assertThat(materialized).isZero();
        verify(reconciledHoldingRepository).deleteAll();
    }
}
