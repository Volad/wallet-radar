package com.walletradar.costbasis.engine;

import com.walletradar.domain.AssetPosition;
import com.walletradar.domain.AssetPositionRepository;
import com.walletradar.domain.CostBasisOverride;
import com.walletradar.domain.CostBasisOverrideRepository;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvcoEngineTest {

    private static final String WALLET = "0xwallet";
    private static final String ASSET_CONTRACT = "0x0000000000000000000000000000000000000000";
    private static final String ASSET_SYMBOL = "ETH";

    @Mock
    EconomicEventRepository economicEventRepository;
    @Mock
    AssetPositionRepository assetPositionRepository;
    @Mock
    CostBasisOverrideRepository costBasisOverrideRepository;

    @InjectMocks
    AvcoEngine avcoEngine;

    @BeforeEach
    void setUp() {
        when(assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("no events removes position if present")
    void noEvents_removesPosition() {
        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of());
        when(assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract(WALLET, "ETHEREUM", ASSET_CONTRACT))
                .thenReturn(Optional.of(new AssetPosition()));

        avcoEngine.recalculate(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        verify(assetPositionRepository).delete(any(AssetPosition.class));
        verify(assetPositionRepository, never()).save(any());
    }

    @Test
    @DisplayName("BUY then SELL: position has quantity, perWalletAvco, totalRealisedPnlUsd, avcoAtTimeOfSale on SELL event")
    void buyThenSell_persistsPositionAndUpdatesSellEvent() {
        EconomicEvent buy = event("e1", "0xhash1", Instant.parse("2025-01-01T10:00:00Z"),
                EconomicEventType.SWAP_BUY, new BigDecimal("2"), new BigDecimal("1000"), false);
        EconomicEvent sell = event("e2", "0xhash2", Instant.parse("2025-01-02T10:00:00Z"),
                EconomicEventType.SWAP_SELL, new BigDecimal("-1"), new BigDecimal("1200"), false);

        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of(buy, sell));
        when(costBasisOverrideRepository.findByEconomicEventIdInAndIsActiveTrue(any())).thenReturn(List.of());

        avcoEngine.recalculate(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        AssetPosition pos = positionCaptor.getValue();
        assertThat(pos.getQuantity()).isEqualByComparingTo("1");
        assertThat(pos.getPerWalletAvco()).isEqualByComparingTo("1000");
        assertThat(pos.getTotalRealisedPnlUsd()).isEqualByComparingTo("200"); // (1200-1000)*1
        assertThat(pos.isHasIncompleteHistory()).isFalse();

        assertThat(sell.getAvcoAtTimeOfSale()).isEqualByComparingTo("1000");
        assertThat(sell.getRealisedPnlUsd()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("first event SELL sets hasIncompleteHistory")
    void firstEventSell_hasIncompleteHistory() {
        EconomicEvent sell = event("e1", "0xhash1", Instant.parse("2025-01-01T10:00:00Z"),
                EconomicEventType.SWAP_SELL, new BigDecimal("-1"), new BigDecimal("1200"), false);

        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of(sell));
        when(costBasisOverrideRepository.findByEconomicEventIdInAndIsActiveTrue(any())).thenReturn(List.of());

        avcoEngine.recalculate(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().isHasIncompleteHistory()).isTrue();
    }

    @Test
    @DisplayName("active override applied to on-chain event price")
    void overrideAppliedToOnChainEvent() {
        EconomicEvent buy = event("e1", "0xhash1", Instant.parse("2025-01-01T10:00:00Z"),
                EconomicEventType.SWAP_BUY, new BigDecimal("1"), new BigDecimal("1000"), false);
        EconomicEvent sell = event("e2", "0xhash2", Instant.parse("2025-01-02T10:00:00Z"),
                EconomicEventType.SWAP_SELL, new BigDecimal("-1"), new BigDecimal("1200"), false);

        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of(buy, sell));
        CostBasisOverride override = new CostBasisOverride();
        override.setEconomicEventId("e1");
        override.setPriceUsd(new BigDecimal("900"));
        override.setActive(true);
        when(costBasisOverrideRepository.findByEconomicEventIdInAndIsActiveTrue(any()))
                .thenReturn(List.of(override));

        avcoEngine.recalculate(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        AssetPosition pos = positionCaptor.getValue();
        assertThat(pos.getPerWalletAvco()).isEqualByComparingTo("900");
        assertThat(sell.getRealisedPnlUsd()).isEqualByComparingTo("300"); // (1200-900)*1
    }

    @Test
    @DisplayName("MANUAL_COMPENSATING with priceUsd participates in AVCO")
    void manualCompensatingUsesOwnPrice() {
        EconomicEvent buy = event("e1", "0xhash1", Instant.parse("2025-01-01T10:00:00Z"),
                EconomicEventType.SWAP_BUY, new BigDecimal("1"), new BigDecimal("1000"), false);
        EconomicEvent manual = new EconomicEvent();
        manual.setId("e2");
        manual.setTxHash(null);
        manual.setNetworkId(NetworkId.ETHEREUM);
        manual.setWalletAddress(WALLET);
        manual.setBlockTimestamp(Instant.parse("2025-01-02T10:00:00Z"));
        manual.setEventType(EconomicEventType.MANUAL_COMPENSATING);
        manual.setAssetSymbol(ASSET_SYMBOL);
        manual.setAssetContract(ASSET_CONTRACT);
        manual.setQuantityDelta(new BigDecimal("1"));
        manual.setPriceUsd(new BigDecimal("1100"));
        manual.setGasCostUsd(BigDecimal.ZERO);
        manual.setGasIncludedInBasis(false);

        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of(buy, manual));
        when(costBasisOverrideRepository.findByEconomicEventIdInAndIsActiveTrue(List.of("e1"))).thenReturn(List.of());

        avcoEngine.recalculate(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        AssetPosition pos = positionCaptor.getValue();
        assertThat(pos.getQuantity()).isEqualByComparingTo("2");
        assertThat(pos.getPerWalletAvco()).isEqualByComparingTo("1050"); // (1000*1 + 1100*1)/2
    }

    private static EconomicEvent event(String id, String txHash, Instant ts, EconomicEventType type,
                                       BigDecimal qtyDelta, BigDecimal priceUsd, boolean gasInBasis) {
        EconomicEvent e = new EconomicEvent();
        e.setId(id);
        e.setTxHash(txHash);
        e.setNetworkId(NetworkId.ETHEREUM);
        e.setWalletAddress(WALLET);
        e.setBlockTimestamp(ts);
        e.setEventType(type);
        e.setAssetSymbol(ASSET_SYMBOL);
        e.setAssetContract(ASSET_CONTRACT);
        e.setQuantityDelta(qtyDelta);
        e.setPriceUsd(priceUsd);
        e.setGasCostUsd(BigDecimal.ZERO);
        e.setGasIncludedInBasis(gasInBasis);
        return e;
    }
}
