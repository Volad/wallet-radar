package com.walletradar.costbasis.engine;

import com.walletradar.domain.AssetPosition;
import com.walletradar.domain.AssetPositionRepository;
import com.walletradar.domain.CostBasisOverride;
import com.walletradar.domain.CostBasisOverrideRepository;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvcoEngineTest {

    private static final String WALLET = "0xwallet";
    private static final String ASSET_CONTRACT = "0x0000000000000000000000000000000000000000";
    private static final String ASSET_SYMBOL = "ETH";

    @Mock
    EconomicEventRepository economicEventRepository;
    @Mock
    NormalizedTransactionRepository normalizedTransactionRepository;
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
        when(normalizedTransactionRepository.findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
                any(), any(), any())).thenReturn(List.of());
        when(normalizedTransactionRepository.findByWalletAddressAndStatusOrderByBlockTimestampAsc(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("confirmed normalized legs are used as primary AVCO source")
    void confirmedNormalizedLegs_primarySource() {
        NormalizedTransaction txBuy = normalizedTx("tx1", Instant.parse("2025-01-01T10:00:00Z"),
                new BigDecimal("2"), new BigDecimal("1000"));
        NormalizedTransaction txSell = normalizedTx("tx2", Instant.parse("2025-01-02T10:00:00Z"),
                new BigDecimal("-1"), new BigDecimal("1200"));

        when(normalizedTransactionRepository.findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, NormalizedTransactionStatus.CONFIRMED)).thenReturn(List.of(txBuy, txSell));

        avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        AssetPosition pos = positionCaptor.getValue();
        assertThat(pos.getQuantity()).isEqualByComparingTo("1");
        assertThat(pos.getPerWalletAvco()).isEqualByComparingTo("1000");
        assertThat(pos.getTotalRealisedPnlUsd()).isEqualByComparingTo("200");

        NormalizedTransaction.Leg sellLeg = txSell.getLegs().get(0);
        assertThat(sellLeg.getAvcoAtTimeOfSale()).isEqualByComparingTo("1000");
        assertThat(sellLeg.getRealisedPnlUsd()).isEqualByComparingTo("200");
        verify(normalizedTransactionRepository).saveAll(any());
        verify(economicEventRepository, never())
                .findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(any(), any(), any());
    }

    @Test
    @DisplayName("no events removes position if present")
    void noEvents_removesPosition() {
        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of());
        when(assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract(WALLET, "ETHEREUM", ASSET_CONTRACT))
                .thenReturn(Optional.of(new AssetPosition()));

        avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

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
        when(costBasisOverrideRepository.findByEconomicEventIdInAndActiveTrue(any())).thenReturn(List.of());

        avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

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
        when(costBasisOverrideRepository.findByEconomicEventIdInAndActiveTrue(any())).thenReturn(List.of());

        avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

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
        when(costBasisOverrideRepository.findByEconomicEventIdInAndActiveTrue(any()))
                .thenReturn(List.of(override));

        avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

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
        when(costBasisOverrideRepository.findByEconomicEventIdInAndActiveTrue(List.of("e1"))).thenReturn(List.of());

        avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT);

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        AssetPosition pos = positionCaptor.getValue();
        assertThat(pos.getQuantity()).isEqualByComparingTo("2");
        assertThat(pos.getPerWalletAvco()).isEqualByComparingTo("1050"); // (1000*1 + 1100*1)/2
    }

    @Test
    @DisplayName("incomplete history: sell then equal buy does not divide by zero")
    void incompleteHistory_sellThenEqualBuy_noDivideByZero() {
        EconomicEvent sell = event("e1", "0xhash1", Instant.parse("2025-01-01T10:00:00Z"),
                EconomicEventType.SWAP_SELL, new BigDecimal("-1"), new BigDecimal("1200"), false);
        EconomicEvent buy = event("e2", "0xhash2", Instant.parse("2025-01-02T10:00:00Z"),
                EconomicEventType.SWAP_BUY, new BigDecimal("1"), new BigDecimal("1000"), false);

        when(economicEventRepository.findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT)).thenReturn(List.of(sell, buy));
        when(costBasisOverrideRepository.findByEconomicEventIdInAndActiveTrue(any())).thenReturn(List.of());

        assertThatCode(() -> avcoEngine.replayFromBeginning(WALLET, NetworkId.ETHEREUM, ASSET_CONTRACT))
                .doesNotThrowAnyException();

        ArgumentCaptor<AssetPosition> positionCaptor = ArgumentCaptor.forClass(AssetPosition.class);
        verify(assetPositionRepository).save(positionCaptor.capture());
        AssetPosition pos = positionCaptor.getValue();
        assertThat(pos.getQuantity()).isEqualByComparingTo("0");
        assertThat(pos.isHasIncompleteHistory()).isTrue();
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

    private static NormalizedTransaction normalizedTx(String txHash, Instant ts, BigDecimal qty, BigDecimal priceUsd) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(txHash);
        tx.setTxHash(txHash);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setWalletAddress(WALLET);
        tx.setBlockTimestamp(ts);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);

        NormalizedTransaction.Leg leg = new NormalizedTransaction.Leg();
        leg.setRole(qty.signum() >= 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL);
        leg.setAssetContract(ASSET_CONTRACT);
        leg.setAssetSymbol(ASSET_SYMBOL);
        leg.setQuantityDelta(qty);
        leg.setUnitPriceUsd(priceUsd);
        tx.setLegs(List.of(leg));
        return tx;
    }
}
