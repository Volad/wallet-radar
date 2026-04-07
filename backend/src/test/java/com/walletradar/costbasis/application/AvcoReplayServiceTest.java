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
                .filter(point -> assetSymbol.equals(point.getAssetSymbol()))
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
}
