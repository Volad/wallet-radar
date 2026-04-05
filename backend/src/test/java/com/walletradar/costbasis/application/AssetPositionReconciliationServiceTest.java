package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetPosition;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
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
class AssetPositionReconciliationServiceTest {

    @Mock
    private AssetPositionRepository assetPositionRepository;
    @Mock
    private OnChainBalanceRepository onChainBalanceRepository;

    @Test
    void nativeAliasBalancesCollapseIntoOneMatchedReconciliationRow() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.ZKSYNC)
                .setAssetSymbol("ETH")
                .setAssetContract("NATIVE:ZKSYNC")
                .setQuantity(new BigDecimal("1.5"));

        OnChainBalance symbolBalance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.ZKSYNC)
                .setAssetSymbol("ETH")
                .setQuantity(new BigDecimal("0.5"))
                .setCapturedAt(Instant.parse("2026-04-03T10:00:00Z"));

        OnChainBalance aliasBalance = new OnChainBalance()
                .setId("bal-2")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.ZKSYNC)
                .setAssetSymbol("ETH")
                .setAssetContract("0x000000000000000000000000000000000000800a")
                .setQuantity(new BigDecimal("1.0"))
                .setCapturedAt(Instant.parse("2026-04-03T10:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(symbolBalance, aliasBalance));

        AssetPositionReconciliationService service = new AssetPositionReconciliationService(
                assetPositionRepository,
                onChainBalanceRepository
        );

        service.reconcile(Instant.parse("2026-04-03T10:05:00Z"));

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition saved = captor.getValue().getFirst();
        assertThat(saved.getOnChainQuantity()).isEqualByComparingTo("1.5");
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCH);
        assertThat(saved.getOnChainCapturedAt()).isEqualTo(Instant.parse("2026-04-03T10:00:00Z"));
    }

    @Test
    void quantityMismatchMarksPositionAsMismatch() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.MANTLE)
                .setAssetSymbol("WETH")
                .setAssetContract("0xdEAddEaDdeadDEadDEADDEAddEADDEAddead1111")
                .setQuantity(new BigDecimal("3.06"));

        OnChainBalance balance = new OnChainBalance()
                .setId("bal-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.MANTLE)
                .setAssetSymbol("WETH")
                .setAssetContract("0xdEAddEaDdeadDEadDEADDEAddEADDEAddead1111")
                .setQuantity(new BigDecimal("3.00"))
                .setCapturedAt(Instant.parse("2026-04-03T10:00:00Z"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of(balance));

        AssetPositionReconciliationService service = new AssetPositionReconciliationService(
                assetPositionRepository,
                onChainBalanceRepository
        );

        service.reconcile(Instant.parse("2026-04-03T10:05:00Z"));

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue().getFirst().getReconciliationStatus()).isEqualTo(ReconciliationStatus.MISMATCH);
    }

    @Test
    void missingBalanceEvidenceRemainsNotApplicable() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("0xwallet")
                .setNetworkId(NetworkId.BASE)
                .setAssetSymbol("ETH")
                .setAssetContract("NATIVE:BASE")
                .setQuantity(new BigDecimal("0.1"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of());

        AssetPositionReconciliationService service = new AssetPositionReconciliationService(
                assetPositionRepository,
                onChainBalanceRepository
        );

        service.reconcile(Instant.parse("2026-04-03T10:05:00Z"));

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition saved = captor.getValue().getFirst();
        assertThat(saved.getOnChainQuantity()).isNull();
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NOT_APPLICABLE);
    }

    @Test
    void bybitPositionsStayNotApplicableForOnChainReconciliation() {
        AssetPosition position = new AssetPosition()
                .setId("pos-1")
                .setWalletAddress("BYBIT:33625378")
                .setAssetSymbol("ETH")
                .setAssetContract("SYMBOL:ETH")
                .setQuantity(new BigDecimal("1"));

        when(assetPositionRepository.findAll()).thenReturn(List.of(position));
        when(onChainBalanceRepository.findAll()).thenReturn(List.of());

        AssetPositionReconciliationService service = new AssetPositionReconciliationService(
                assetPositionRepository,
                onChainBalanceRepository
        );

        service.reconcile(Instant.parse("2026-04-03T10:05:00Z"));

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition saved = captor.getValue().getFirst();
        assertThat(saved.getOnChainQuantity()).isNull();
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NOT_APPLICABLE);
    }
}
