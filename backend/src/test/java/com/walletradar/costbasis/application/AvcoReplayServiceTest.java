package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvcoReplayServiceTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AssetLedgerPointRepository assetLedgerPointRepository;

    @Test
    void deterministicReplayOrderingUsesIdAsFinalTieBreaker() {
        NormalizedTransaction sell = tx("b", "0xsell", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", "20", PriceSource.BINANCE));
        NormalizedTransaction buy = tx("a", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "10", PriceSource.BINANCE));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sell, buy));

        service().replayConfirmed();

        ArgumentCaptor<List<NormalizedTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(txCaptor.capture());
        NormalizedTransaction replayedSell = txCaptor.getValue().stream()
                .filter(tx -> "b".equals(tx.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(replayedSell.getFlows().getFirst().getAvcoAtTimeOfSale()).isEqualByComparingTo("10");
        assertThat(replayedSell.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("10");
    }

    @Test
    void transferCarryOverMovesBasisWithoutDoubleCounting() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");

        NormalizedTransaction sourceTransfer = tx("2", "0xtransfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("wallet-b");

        NormalizedTransaction destTransfer = tx("3", "0xtransfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        destTransfer.setWalletAddress("wallet-b");
        destTransfer.setContinuityCandidate(true);
        destTransfer.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, destTransfer));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.BASE, "ETH", null);
        AssetLedgerPoint destination = latestPoint(points, "wallet-b", NetworkId.BASE, "ETH", null);
        assertThat(source.getQuantityAfter()).isZero();
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void bybitTransitCorridorKeepsPreExistingVenueInventoryOutOfTransitCarry() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");
        sourceBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceTransfer = tx("2", "0xbybit-out", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("bybit:arb:1");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1");

        NormalizedTransaction bybitInventory = tx("3", "bybit-buy", 2, NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.BUY, "ETH", "0.1", "4000", PriceSource.BINANCE));
        bybitInventory.setSource(NormalizedTransactionSource.BYBIT);
        bybitInventory.setWalletAddress("BYBIT:1");
        bybitInventory.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction bybitInbound = tx("4", "0xbybit-in", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        bybitInbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setNetworkId(NetworkId.ARBITRUM);
        bybitInbound.setCorrelationId("bybit:arb:1");
        bybitInbound.setContinuityCandidate(true);
        bybitInbound.setMatchedCounterparty("wallet-a");

        NormalizedTransaction bybitOutbound = tx("5", "0xbybit-mantle", 4, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        bybitOutbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitOutbound.setWalletAddress("BYBIT:1");
        bybitOutbound.setNetworkId(NetworkId.MANTLE);
        bybitOutbound.setCorrelationId("bybit:mantle:1");
        bybitOutbound.setContinuityCandidate(true);
        bybitOutbound.setMatchedCounterparty("wallet-a");

        NormalizedTransaction destinationInbound = tx("6", "0xmantle-in", 5, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        destinationInbound.setWalletAddress("wallet-a");
        destinationInbound.setNetworkId(NetworkId.MANTLE);
        destinationInbound.setCorrelationId("bybit:mantle:1");
        destinationInbound.setContinuityCandidate(true);
        destinationInbound.setMatchedCounterparty("BYBIT:1");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, bybitInventory, bybitInbound, bybitOutbound, destinationInbound));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.MANTLE, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("100");

        AssetLedgerPoint bybit = points.stream()
                .filter(point -> "BYBIT:1".equals(point.getWalletAddress()))
                .filter(point -> point.getNetworkId() == null)
                .filter(point -> "ETH".equals(point.getAssetSymbol()))
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(bybit.getQuantityAfter()).isEqualByComparingTo("0.1");
        assertThat(bybit.getTotalCostBasisAfterUsd()).isEqualByComparingTo("400");
        assertThat(bybit.getAvcoAfterUsd()).isEqualByComparingTo("4000");
    }

    @Test
    void correlatedCarryIntoBybitUsesCanonicalSymbolIdentityForArb() {
        NormalizedTransaction sourceBuy = tx("1", "0xarb-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "ARB", "0xarb", "10", "0.5", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");
        sourceBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceTransfer = tx("2", "0xarb-transfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "ARB", "0xarb", "-10", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("bybit:arb:1");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1");

        NormalizedTransaction bybitInbound = tx("3", "0xarb-transfer", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.TRANSFER, "ARB", "10", null, null));
        bybitInbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setCorrelationId("bybit:arb:1");
        bybitInbound.setContinuityCandidate(true);
        bybitInbound.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, bybitInbound));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = points.stream()
                .filter(point -> "BYBIT:1".equals(point.getWalletAddress()))
                .filter(point -> "ARB".equals(point.getAssetSymbol()))
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("10");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("5");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("0.5");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
    }

    @Test
    void correlatedCarryIntoBybitUsesCanonicalSymbolAliasForUsdt0() {
        NormalizedTransaction sourceBuy = tx("1", "0xusdt-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USD₮0", "0xusdt0", "27", "1", PriceSource.STABLECOIN));
        sourceBuy.setWalletAddress("wallet-a");
        sourceBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceTransfer = tx("2", "0xusdt-transfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USD₮0", "0xusdt0", "-27", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("bybit:usdt:1");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1");

        NormalizedTransaction bybitInbound = tx("3", "0xusdt-transfer", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.TRANSFER, "USDT", "27", null, null));
        bybitInbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setCorrelationId("bybit:usdt:1");
        bybitInbound.setContinuityCandidate(true);
        bybitInbound.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, bybitInbound));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = points.stream()
                .filter(point -> "BYBIT:1".equals(point.getWalletAddress()))
                .filter(point -> "USDT".equals(point.getAssetSymbol()))
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("27");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("27");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("1");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
    }

    @Test
    void correlatedCarryIntoBybitUsesCanonicalSymbolAliasForRoundedUsdt0Ingress() {
        NormalizedTransaction sourceBuy = tx("1", "0xusdt-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USD₮0", "0xusdt0", "939.264861", "1", PriceSource.STABLECOIN));
        sourceBuy.setWalletAddress("wallet-a");
        sourceBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceTransfer = tx("2", "0xusdt-transfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USD₮0", "0xusdt0", "-939.264861", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("bybit:usdt:rounded");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1");

        NormalizedTransaction bybitInbound = tx("3", "0xusdt-transfer", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.TRANSFER, "USDT", "939.2648", null, null));
        bybitInbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setCorrelationId("bybit:usdt:rounded");
        bybitInbound.setContinuityCandidate(true);
        bybitInbound.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, bybitInbound));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = points.stream()
                .filter(point -> "BYBIT:1".equals(point.getWalletAddress()))
                .filter(point -> "USDT".equals(point.getAssetSymbol()))
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("939.2648");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("939.2648");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("1");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
    }

    @Test
    void continuityTransferKeepsUncoveredTailOnDestinationInsteadOfPoisoningFutureCoverage() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");

        NormalizedTransaction sourceTransfer = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1.5", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setCorrelationId("bridge:1");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("wallet-b");

        NormalizedTransaction destinationTransfer = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1.5", null, null));
        destinationTransfer.setWalletAddress("wallet-b");
        destinationTransfer.setNetworkId(NetworkId.ARBITRUM);
        destinationTransfer.setCorrelationId("bridge:1");
        destinationTransfer.setContinuityCandidate(true);
        destinationTransfer.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, destinationTransfer));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.BASE, "ETH", null);
        AssetLedgerPoint destination = latestPoint(points, "wallet-b", NetworkId.ARBITRUM, "ETH", null);
        assertThat(source.getQuantityAfter()).isZero();
        assertThat(source.getQuantityShortfallAfter()).isEqualByComparingTo("0.5");
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1.5");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.5");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void laterCoveredAcquisitionIsNotZeroedOutByHistoricalLifetimeShortfall() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));

        NormalizedTransaction oversizedFee = tx("2", "0xfee", 1, NormalizedTransactionType.ADMIN_CONFIG,
                flow(NormalizedLegRole.FEE, "ETH", "-1.5", "100", PriceSource.BINANCE));

        NormalizedTransaction laterBuy = tx("3", "0xbuy-later", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.2", "200", PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, oversizedFee, laterBuy));

        service().replayConfirmed();

        AssetLedgerPoint point = latestPoint(capturedLedgerPoints(), "0xwallet", NetworkId.BASE, "ETH", null);
        assertThat(point.getQuantityAfter()).isEqualByComparingTo("0.2");
        assertThat(point.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.2");
        assertThat(point.getUncoveredQuantityAfter()).isZero();
        assertThat(point.getQuantityShortfallAfter()).isEqualByComparingTo("0.5");
        assertThat(point.getTotalCostBasisAfterUsd()).isEqualByComparingTo("40");
        assertThat(point.getAvcoAfterUsd()).isEqualByComparingTo("200");
    }

    @Test
    void replayPersistsAssetLedgerPointsWithFamilyAndBeforeAfterState() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setCorrelationId("bridge:1");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, bridgeOut));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        assertThat(points).hasSize(2);
        assertThat(points).anySatisfy(point -> {
            if ("1".equals(point.getNormalizedTransactionId())) {
                assertThat(point.getAccountingFamilyIdentity()).isEqualTo("FAMILY:ETH");
                assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
                assertThat(point.getQuantityBefore()).isZero();
                assertThat(point.getQuantityAfter()).isEqualByComparingTo("1");
                assertThat(point.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
            }
        }).anySatisfy(point -> {
            if ("2".equals(point.getNormalizedTransactionId())) {
                assertThat(point.getLifecycleKind()).isEqualTo(AssetLedgerPoint.LifecycleKind.BRIDGE);
                assertThat(point.getLifecycleStage()).isEqualTo(AssetLedgerPoint.LifecycleStage.SOURCE);
                assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_OUT);
                assertThat(point.getQuantityAfter()).isZero();
                assertThat(point.getTotalCostBasisAfterUsd()).isZero();
            }
        });
    }

    @Test
    void inboundFirstTransferIsSpendableBeforeReplayEndsAndDoesNotResurrectQuantity() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");

        NormalizedTransaction inboundFirst = tx("2a", "0xmove", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        inboundFirst.setWalletAddress("wallet-b");
        inboundFirst.setContinuityCandidate(true);
        inboundFirst.setMatchedCounterparty("wallet-a");

        NormalizedTransaction sourceLater = tx("2b", "0xmove", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        sourceLater.setWalletAddress("wallet-a");
        sourceLater.setContinuityCandidate(true);
        sourceLater.setMatchedCounterparty("wallet-b");

        NormalizedTransaction destinationSpend = tx("3", "0xlp-entry", 2, NormalizedTransactionType.LP_ENTRY,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        destinationSpend.setWalletAddress("wallet-b");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, inboundFirst, sourceLater, destinationSpend));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-b", NetworkId.BASE, "ETH", null);
        assertThat(destination.getQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isZero();
        assertThat(destination.getHasIncompleteHistoryAfter()).isFalse();
    }

    @Test
    void bridgeDestinationCarryInPreservesBasisAcrossNetworks() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.BASE);
        bridgeOut.setCorrelationId("bridge:1");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("wallet-a");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:1");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
    }

    @Test
    void bridgeDestinationLowerThanSourceCarriesFullCostIntoSmallerDestinationQuantity() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.BASE);
        bridgeOut.setCorrelationId("bridge:drift-lower");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("wallet-a");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.99", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:drift-lower");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("0.99");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.99");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("101.0101010101010101010101010101010");
    }

    @Test
    void bridgeDestinationHigherThanSourceLeavesOnlyExcessAsUncovered() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.BASE);
        bridgeOut.setCorrelationId("bridge:drift-higher");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("wallet-a");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1.002", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:drift-higher");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1.002");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("1.0");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.002");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void inboundFirstBridgeWithLowerDestinationLaterAttachesFullCostWithoutDuplicateQuantity() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction bridgeIn = tx("2", "0xbridge-in", 1, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.99", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:late-lower");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("wallet-a");

        NormalizedTransaction bridgeOut = tx("3", "0xbridge-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.BASE);
        bridgeOut.setCorrelationId("bridge:late-lower");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, bridgeIn, bridgeOut));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("0.99");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.99");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getHasIncompleteHistoryAfter()).isFalse();
    }

    @Test
    void bridgeInboundCorridorFeedsLendingDepositBeforeSpotPooling() {
        NormalizedTransaction localSpot = tx("1", "0xlocal", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.1", "4000", PriceSource.BINANCE));
        localSpot.setWalletAddress("wallet-a");
        localSpot.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceBuy = tx("2", "0xsource-buy", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");
        sourceBuy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction bridgeOut = tx("3", "0xbridge-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.BASE);
        bridgeOut.setCorrelationId("bridge:1");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx("4", "0xbridge-in", 3, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:1");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        NormalizedTransaction lendingDeposit = tx("5", "0xdeposit", 4, NormalizedTransactionType.LENDING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null),
                flow(NormalizedLegRole.TRANSFER, "aArbWETH", "1", null, null));
        lendingDeposit.setWalletAddress("wallet-a");
        lendingDeposit.setNetworkId(NetworkId.ARBITRUM);

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(localSpot, sourceBuy, bridgeOut, bridgeIn, lendingDeposit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint spot = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        AssetLedgerPoint custody = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "aArbWETH", null);
        assertThat(spot.getQuantityAfter()).isEqualByComparingTo("0.1");
        assertThat(spot.getTotalCostBasisAfterUsd()).isEqualByComparingTo("400");
        assertThat(spot.getAvcoAfterUsd()).isEqualByComparingTo("4000");
        assertThat(custody.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(custody.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(custody.getAvcoAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void linkedAssetChangingBridgeSettlementMovesSourceCostIntoDestinationAcquisition() {
        NormalizedTransaction stableBuy = tx("1", "0xusdc-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "2050.040045", "1", PriceSource.STABLECOIN));
        stableBuy.setWalletAddress("wallet-a");
        stableBuy.setNetworkId(NetworkId.UNICHAIN);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "-2050.040045", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.UNICHAIN);
        bridgeOut.setCorrelationId("bridge:lifi:stable-to-eth");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.452894410848733888", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:stable-to-eth");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(stableBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.UNICHAIN, "USDC", "0xusdc");
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.KATANA, "ETH", null);
        assertThat(source.getQuantityAfter()).isZero();
        assertThat(source.getTotalCostBasisAfterUsd()).isZero();
        assertThat(source.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("0.452894410848733888");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.452894410848733888");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("2050.040045");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("4526.529795671756618439305606448699");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void linkedAssetChangingBridgeSettlementStillCarriesWhenSourceHasRouteFundingFeeLeg() {
        NormalizedTransaction sourceBuy = tx("1", "0xusdt-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDT", "0xusdt", "21.81403", "1", PriceSource.STABLECOIN));
        sourceBuy.setWalletAddress("wallet-a");
        sourceBuy.setNetworkId(NetworkId.MANTLE);

        NormalizedTransaction bridgeOut = tx("2", "0xf8cbea", 1, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", "0xusdt", "-21.81403", null, null),
                flow(NormalizedLegRole.FEE, "MNT", "-0.084340262615309958", null, null),
                flow(NormalizedLegRole.FEE, "MNT", "-0.03345797133", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.MANTLE);
        bridgeOut.setCorrelationId("bridge:lifi:usdt-to-usdc");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0x7483c0");

        NormalizedTransaction bridgeIn = tx("3", "0x7483c0", 2, NormalizedTransactionType.BRIDGE_IN,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "21.818316", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:lifi:usdt-to-usdc");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xf8cbea");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "USDC", "0xusdc");
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("21.818316");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("21.818316");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("21.81403");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void linkedAssetChangingBridgeSettlementPreservesPartialCoveredShareFromSourceLot() {
        NormalizedTransaction coveredStableBuy = tx("1", "0xusdc-buy-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "100", "1", PriceSource.STABLECOIN));
        coveredStableBuy.setWalletAddress("wallet-a");
        coveredStableBuy.setNetworkId(NetworkId.UNICHAIN);

        NormalizedTransaction uncoveredStableBuy = tx("2", "0xusdc-buy-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "100", null, PriceSource.UNKNOWN));
        uncoveredStableBuy.setWalletAddress("wallet-a");
        uncoveredStableBuy.setNetworkId(NetworkId.UNICHAIN);

        NormalizedTransaction bridgeOut = tx("3", "0xbridge-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "-200", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.UNICHAIN);
        bridgeOut.setCorrelationId("bridge:lifi:partial-source");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx("4", "0xbridge-in", 3, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:partial-source");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredStableBuy, uncoveredStableBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.KATANA, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.5");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.5");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("200");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void inboundFirstAssetChangingBridgeSettlementAttachesLaterWithoutDuplicatingQuantity() {
        NormalizedTransaction stableBuy = tx("1", "0xusdc-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "100", "1", PriceSource.STABLECOIN));
        stableBuy.setWalletAddress("wallet-a");
        stableBuy.setNetworkId(NetworkId.UNICHAIN);

        NormalizedTransaction bridgeIn = tx("2", "0xbridge-in", 1, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:late-route");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        NormalizedTransaction bridgeOut = tx("3", "0xbridge-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "-100", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.UNICHAIN);
        bridgeOut.setCorrelationId("bridge:lifi:late-route");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(stableBuy, bridgeIn, bridgeOut));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.KATANA, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getHasIncompleteHistoryAfter()).isFalse();
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void simpleFamilyEquivalentDepositCarriesBasisWhenReceiptFlowPrecedesPrincipalOut() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.798", "2454.911612404057700588861163058142", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction deposit = tx("2", "0xdeposit", 1, NormalizedTransactionType.LENDING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "aArbWETH", "0.798355982952963328", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.798", null, null));
        deposit.setWalletAddress("wallet-a");
        deposit.setNetworkId(NetworkId.ARBITRUM);
        deposit.setProtocolName("Aave");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, deposit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint receipt = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "aArbWETH", null);
        AssetLedgerPoint principal = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        assertThat(principal.getQuantityAfter()).isZero();
        assertThat(receipt.getQuantityAfter()).isEqualByComparingTo("0.798355982952963328");
        assertThat(receipt.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1959.019466698438045069911208120397");
        assertThat(receipt.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.798");
        assertThat(receipt.getUncoveredQuantityAfter()).isEqualByComparingTo("0.000355982952963328");
        assertThat(receipt.getAvcoAfterUsd()).isEqualByComparingTo("2454.911612404057700588861163058142");
    }

    @Test
    void simpleFamilyEquivalentWithdrawKeepsFullSourceCostAndLeavesOnlyExcessAsUncovered() {
        NormalizedTransaction receiptBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "aArbWETH", "3.045871023828205936", "3026.273507192132827989353697725674", PriceSource.BINANCE));
        receiptBuy.setWalletAddress("wallet-a");
        receiptBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction withdraw = tx("2", "0xwithdraw", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "aArbWETH", "-3.045871023828205936", null, null),
                flow(NormalizedLegRole.TRANSFER, "WETH", "3.048250993852645231", null, null));
        withdraw.setWalletAddress("wallet-a");
        withdraw.setNetworkId(NetworkId.ARBITRUM);
        withdraw.setProtocolName("Aave");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(receiptBuy, withdraw));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "WETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("3.048250993852645231");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("9217.638785735477156955878881041781");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("3.045871023828205936");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.002379970024439295");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("3026.273507192132827989353697725674");
    }

    @Test
    void wrapCarriesBasisWhenWrappedFlowPrecedesNativeOut() {
        NormalizedTransaction buy = tx("1", "0xbuy-eth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction wrap = tx("2", "0xwrap", 1, NormalizedTransactionType.WRAP,
                flow(NormalizedLegRole.TRANSFER, "WETH", "1", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        wrap.setWalletAddress("wallet-a");
        wrap.setNetworkId(NetworkId.BASE);

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, wrap));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint nativeEth = latestPoint(points, "wallet-a", NetworkId.BASE, "ETH", null);
        AssetLedgerPoint weth = latestPoint(points, "wallet-a", NetworkId.BASE, "WETH", null);
        assertThat(nativeEth.getQuantityAfter()).isZero();
        assertThat(nativeEth.getTotalCostBasisAfterUsd()).isZero();
        assertThat(nativeEth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(weth.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(weth.getBasisBackedQuantityAfter()).isEqualByComparingTo("1");
        assertThat(weth.getUncoveredQuantityAfter()).isZero();
        assertThat(weth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(weth.getAvcoAfterUsd()).isEqualByComparingTo("100");
        assertThat(weth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void unwrapCarriesBasisWhenNativeFlowPrecedesWrappedOut() {
        NormalizedTransaction buy = tx("1", "0xbuy-weth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "WETH", "1", "100", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction unwrap = tx("2", "0xunwrap", 1, NormalizedTransactionType.UNWRAP,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null),
                flow(NormalizedLegRole.TRANSFER, "WETH", "-1", null, null));
        unwrap.setWalletAddress("wallet-a");
        unwrap.setNetworkId(NetworkId.BASE);

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, unwrap));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint weth = latestPoint(points, "wallet-a", NetworkId.BASE, "WETH", null);
        AssetLedgerPoint nativeEth = latestPoint(points, "wallet-a", NetworkId.BASE, "ETH", null);
        assertThat(weth.getQuantityAfter()).isZero();
        assertThat(weth.getTotalCostBasisAfterUsd()).isZero();
        assertThat(weth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(nativeEth.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(nativeEth.getBasisBackedQuantityAfter()).isEqualByComparingTo("1");
        assertThat(nativeEth.getUncoveredQuantityAfter()).isZero();
        assertThat(nativeEth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(nativeEth.getAvcoAfterUsd()).isEqualByComparingTo("100");
        assertThat(nativeEth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void correlatedCarryIntoBybitSurvivesProviderRoundedInboundQuantity() {
        NormalizedTransaction coveredBuy = tx("1", "0xbuy-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.7", "100", PriceSource.BINANCE));
        coveredBuy.setWalletAddress("wallet-a");
        coveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction uncoveredBuy = tx("2", "0xbuy-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.300000004", null, PriceSource.UNKNOWN));
        uncoveredBuy.setWalletAddress("wallet-a");
        uncoveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceTransfer = tx("3", "0xcarry", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1.000000004", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("BYBIT:ARBITRUM:0xcarry");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1");

        NormalizedTransaction bybitInbound = tx("4", "0xcarry", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", null, null));
        bybitInbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setNetworkId(NetworkId.ARBITRUM);
        bybitInbound.setCorrelationId("BYBIT:ARBITRUM:0xcarry");
        bybitInbound.setContinuityCandidate(true);
        bybitInbound.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, uncoveredBuy, sourceTransfer, bybitInbound));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = latestPoint(points, "BYBIT:1", null, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1.0");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("70.0");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.700000000");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.300000000");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
    }

    @Test
    void inboundFirstCorrelatedCarryIntoBybitAttachesLaterWithRoundedQuantity() {
        NormalizedTransaction coveredBuy = tx("1", "0xbuy-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.7", "100", PriceSource.BINANCE));
        coveredBuy.setWalletAddress("wallet-a");
        coveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction uncoveredBuy = tx("2", "0xbuy-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.300000004", null, PriceSource.UNKNOWN));
        uncoveredBuy.setWalletAddress("wallet-a");
        uncoveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction bybitInbound = tx("3", "0xcarry", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", null, null));
        bybitInbound.setSource(NormalizedTransactionSource.BYBIT);
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setNetworkId(NetworkId.ARBITRUM);
        bybitInbound.setCorrelationId("BYBIT:ARBITRUM:0xcarry");
        bybitInbound.setContinuityCandidate(true);
        bybitInbound.setMatchedCounterparty("wallet-a");

        NormalizedTransaction sourceTransfer = tx("4", "0xcarry", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1.000000004", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("BYBIT:ARBITRUM:0xcarry");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, uncoveredBuy, bybitInbound, sourceTransfer));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = latestPoint(points, "BYBIT:1", null, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1.0");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("70.0");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.700000000");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.300000000");
        assertThat(destination.getHasIncompleteHistoryAfter()).isTrue();
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
    }

    @Test
    void liquidStakingConversionCarriesFullBasisIntoDerivativeWithoutRealizedPnl() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "1000", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");

        NormalizedTransaction stake = tx(
                "2",
                "0xstake",
                1,
                NormalizedTransactionType.STAKING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null),
                flow(NormalizedLegRole.TRANSFER, "METH", "0.95", null, null)
        );
        stake.setWalletAddress("wallet-a");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, stake));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint eth = latestPoint(points, "wallet-a", NetworkId.BASE, "ETH", null);
        AssetLedgerPoint meth = latestPoint(points, "wallet-a", NetworkId.BASE, "METH", null);
        assertThat(eth.getQuantityAfter()).isZero();
        assertThat(eth.getTotalCostBasisAfterUsd()).isZero();
        assertThat(eth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(meth.getQuantityAfter()).isEqualByComparingTo("0.95");
        assertThat(meth.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.95");
        assertThat(meth.getUncoveredQuantityAfter()).isZero();
        assertThat(meth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1000");
        assertThat(meth.getAvcoAfterUsd()).isEqualByComparingTo("1052.631578947368421052631578947368");
        assertThat(meth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        ArgumentCaptor<List<NormalizedTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(txCaptor.capture());
        NormalizedTransaction replayedStake = txCaptor.getValue().stream()
                .filter(tx -> "2".equals(tx.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(replayedStake.getFlows())
                .allSatisfy(flow -> {
                    assertThat(flow.getAvcoAtTimeOfSale()).isNull();
                    assertThat(flow.getRealisedPnlUsd()).isNull();
                });
    }

    @Test
    void familyEquivalentVaultWithdrawCarriesBasisFromYvvbethIntoVbeth() {
        NormalizedTransaction buy = tx("1", "0xbuy-yvvbeth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "YVVBETH", "0.435428171459772105", "2809.177530660769159772942625393432", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.KATANA);

        NormalizedTransaction unwrap = tx(
                "2",
                "0xvault-withdraw",
                1,
                NormalizedTransactionType.VAULT_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "YVVBETH", "-0.435428171459772105", null, null),
                flow(NormalizedLegRole.TRANSFER, "VBETH", "0.438269104813635143", null, null)
        );
        unwrap.setWalletAddress("wallet-a");
        unwrap.setNetworkId(NetworkId.KATANA);

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, unwrap));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint yvvbeth = latestPoint(points, "wallet-a", NetworkId.KATANA, "YVVBETH", null);
        AssetLedgerPoint vbeth = latestPoint(points, "wallet-a", NetworkId.KATANA, "VBETH", null);
        assertThat(yvvbeth.getQuantityAfter()).isZero();
        assertThat(yvvbeth.getTotalCostBasisAfterUsd()).isZero();
        assertThat(vbeth.getQuantityAfter()).isEqualByComparingTo("0.438269104813635143");
        assertThat(vbeth.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.435428171459772105");
        assertThat(vbeth.getUncoveredQuantityAfter()).isEqualByComparingTo("0.002840933353863038");
        assertThat(vbeth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1223.195035481496603283743060170887");
        assertThat(vbeth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void unknownPricePropagatesIncompleteHistory() {
        NormalizedTransaction unknownBuy = tx("1", "0xunknown", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "TOKEN", "10", null, PriceSource.UNKNOWN));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(unknownBuy));

        service().replayConfirmed();

        AssetLedgerPoint point = latestPoint(capturedLedgerPoints(), "0xwallet", NetworkId.BASE, "TOKEN", null);
        assertThat(point.getQuantityAfter()).isEqualByComparingTo("10");
        assertThat(point.getHasIncompleteHistoryAfter()).isTrue();
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
    }

    private List<AssetLedgerPoint> capturedLedgerPoints() {
        ArgumentCaptor<List<AssetLedgerPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetLedgerPointRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private AssetLedgerPoint latestPoint(
            List<AssetLedgerPoint> points,
            String walletAddress,
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
        return latestPointsByBucket(points).values().stream()
                .filter(point -> walletAddress.equals(point.getWalletAddress()))
                .filter(point -> networkId == point.getNetworkId())
                .filter(point -> assetSymbol.equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> assetContract == null
                        || assetContract.equals(point.getAssetContract()))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, AssetLedgerPoint> latestPointsByBucket(List<AssetLedgerPoint> points) {
        Map<String, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points.stream()
                .sorted(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .toList()) {
            latest.put(point.getWalletAddress()
                    + "|"
                    + point.getNetworkId()
                    + "|"
                    + point.getAccountingAssetIdentity(), point);
        }
        return latest;
    }

    private AvcoReplayService service() {
        return new AvcoReplayService(
                new ConfirmedReplayQueryService(normalizedTransactionRepository),
                normalizedTransactionRepository,
                assetLedgerPointRepository
        );
    }

    private NormalizedTransaction tx(
            String id,
            String txHash,
            int txIndex,
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash(txHash);
        transaction.setWalletAddress("0xwallet");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        transaction.setTransactionIndex(txIndex);
        transaction.setFlows(List.of(flows));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String quantityDelta,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(null);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        return flow;
    }

    private NormalizedTransaction.Flow flowWithContract(
            NormalizedLegRole role,
            String symbol,
            String contract,
            String quantityDelta,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = flow(role, symbol, quantityDelta, unitPriceUsd, priceSource);
        flow.setAssetContract(contract);
        return flow;
    }
}
