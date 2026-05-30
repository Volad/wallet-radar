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
        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void ignoresConfirmedExcludedTransactionsDuringReplay() {
        NormalizedTransaction active = tx("active", "0xactive", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        NormalizedTransaction excluded = tx("excluded", "0xexcluded", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.TRANSFER, "USDC", "1000", null, null));
        excluded.setExcludedFromAccounting(Boolean.TRUE);
        excluded.setAccountingExclusionReason("BYBIT_TRANSFER_SHADOW_ROW");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(active, excluded));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        assertThat(points)
                .extracting(AssetLedgerPoint::getNormalizedTransactionId)
                .containsExactly("active");
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void internalTransferCarriesBasisWithoutSyntheticBuyOrSell() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");

        NormalizedTransaction sourceTransfer = tx("2", "0xinternal", 1, NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("wallet-b");

        NormalizedTransaction destinationTransfer = tx("3", "0xinternal", 1, NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null));
        destinationTransfer.setWalletAddress("wallet-b");
        destinationTransfer.setContinuityCandidate(true);
        destinationTransfer.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, destinationTransfer));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.BASE, "ETH", null);
        AssetLedgerPoint destination = latestPoint(points, "wallet-b", NetworkId.BASE, "ETH", null);
        assertThat(source.getQuantityAfter()).isZero();
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("100");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void sponsoredGasInAddsZeroCostCoveredQuantity() {
        NormalizedTransaction topUp = tx("1", "0xgas-topup", 0, NormalizedTransactionType.SPONSORED_GAS_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.000004659018813092", null, null));
        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(topUp));

        service().replayConfirmed();

        AssetLedgerPoint point = latestPoint(capturedLedgerPoints(), "0xwallet", NetworkId.BASE, "ETH", null);
        assertThat(point.getQuantityAfter()).isEqualByComparingTo("0.000004659018813092");
        assertThat(point.getTotalCostBasisAfterUsd()).isZero();
        assertThat(point.getUncoveredQuantityAfter()).isZero();
        assertThat(point.getAvcoAfterUsd()).isZero();
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(point.getHasIncompleteHistoryAfter()).isFalse();
    }

    /**
     * ADR-020 — P0: When BRIDGE_IN arrives before its paired BRIDGE_OUT in replay order, the
     * authoritative carry must still be reserved for the downstream LENDING_DEPOSIT.
     * Before the fix, the LENDING_DEPOSIT captured $0 basis (depleted family pool).
     */
    @Test
    void bridgeInBeforeBridgeOutPreservesCarryForDownstreamLendingDeposit() {
        // Step 1: ETH acquired on UNICHAIN at $3000 — this is the bridge source network.
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "3000", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.UNICHAIN);

        // Step 2: LiFi bridge — BRIDGE_IN arrives first in replay (ETH arriving ON ZKSync).
        // Real bridge flows use NormalizedLegRole.TRANSFER (required by isLinkedBridgeContinuityTransfer).
        // matchedCounterparty = txHash of the paired BRIDGE_OUT (cross-reference pattern for LiFi).
        NormalizedTransaction bridgeIn = tx("2", "0xbridge-in", 1, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "1", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ZKSYNC);
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        // Step 3: BRIDGE_OUT arrives second (ETH leaving UNICHAIN — same LiFi bridge operation).
        NormalizedTransaction bridgeOut = tx("3", "0xbridge-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.UNICHAIN);
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        // Step 4: LENDING_DEPOSIT on ZKSync 35 seconds later — must receive the corridor-reserved carry
        NormalizedTransaction lendingDeposit = tx("4", "0xlending", 3, NormalizedTransactionType.LENDING_DEPOSIT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null),
                flow(NormalizedLegRole.BUY, "WETH", "1", null, null));
        lendingDeposit.setWalletAddress("wallet-a");
        lendingDeposit.setNetworkId(NetworkId.ZKSYNC);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, bridgeIn, bridgeOut, lendingDeposit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();

        // The WETH REALLOCATE_IN must carry the full $3000 basis, not $0
        AssetLedgerPoint wethIn = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol()))
                .filter(p -> AssetLedgerPoint.BasisEffect.REALLOCATE_IN.equals(p.getBasisEffect()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No WETH REALLOCATE_IN ledger point found"));
        assertThat(wethIn.getCostBasisDeltaUsd())
                .as("WETH basis must equal the carried ETH cost basis (~$3000)")
                .isGreaterThanOrEqualTo(new BigDecimal("2900"));
        assertThat(wethIn.getAvcoAfterUsd())
                .as("WETH AVCO must equal the carried ETH AVCO (~$3000)")
                .isGreaterThanOrEqualTo(new BigDecimal("2900"));
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void laterDisposalConsumesUncoveredTailBeforeCurrentCoveredInventory() {
        NormalizedTransaction coveredBuy = tx("1", "0xbuy-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.25", "100", PriceSource.BINANCE));

        NormalizedTransaction uncoveredInbound = tx("2", "0xinbound-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.75", null, PriceSource.UNKNOWN));

        NormalizedTransaction sell = tx("3", "0xsell", 2, NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.SELL, "ETH", "-1", "150", PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, uncoveredInbound, sell));

        service().replayConfirmed();

        AssetLedgerPoint point = latestPoint(capturedLedgerPoints(), "0xwallet", NetworkId.BASE, "ETH", null);
        assertThat(point.getQuantityAfter()).isEqualByComparingTo("1");
        assertThat(point.getBasisBackedQuantityAfter()).isEqualByComparingTo("1");
        assertThat(point.getUncoveredQuantityAfter()).isZero();
        assertThat(point.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(point.getAvcoAfterUsd()).isEqualByComparingTo("100");
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, inboundFirst, sourceLater, destinationSpend));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-b", NetworkId.BASE, "ETH", null);
        assertThat(destination.getQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isZero();
        assertThat(destination.getHasIncompleteHistoryAfter()).isFalse();
    }

    @Test
    void curveStyleMultiAssetLpEntryCarriesAllOutboundBasisToReceiptToken() {
        String lpContract = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
        String ghoContract = "0x40d16fc0246ad3160ccc09b8d0d3a2cd28ae6c2f";
        String usdcContract = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
        String usdtContract = "0x9702230a8bfeacae2f24a530c484fc24572abbf3";

        NormalizedTransaction ghoBuy = tx("1", "0xgho", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "GHO", ghoContract, "1140", "1", PriceSource.STABLECOIN));
        ghoBuy.setWalletAddress("wallet-a");
        ghoBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction usdcBuy = tx("2", "0xusdc", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", usdcContract, "500", "1", PriceSource.STABLECOIN));
        usdcBuy.setWalletAddress("wallet-a");
        usdcBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction usdtBuy = tx("3", "0xusdt", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDT", usdtContract, "500", "1", PriceSource.STABLECOIN));
        usdtBuy.setWalletAddress("wallet-a");
        usdtBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction lpEntry = tx("4", "0xlp-entry", 3, NormalizedTransactionType.LP_ENTRY,
                flowWithContract(NormalizedLegRole.TRANSFER, "GHO", ghoContract, "-1140", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", usdcContract, "-500", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", usdtContract, "-500", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "AAVE GHO/USDT/USDC", lpContract, "2140", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(ghoBuy, usdcBuy, usdtBuy, lpEntry));

        service().replayConfirmed();

        AssetLedgerPoint lpReceipt = latestPoint(
                capturedLedgerPoints(),
                "wallet-a",
                NetworkId.AVALANCHE,
                "AAVE GHO/USDT/USDC",
                lpContract
        );
        assertThat(lpReceipt.getQuantityAfter()).isEqualByComparingTo("2140");
        assertThat(lpReceipt.getTotalCostBasisAfterUsd()).isEqualByComparingTo("2140");
        assertThat(lpReceipt.getUncoveredQuantityAfter()).isZero();
        assertThat(lpReceipt.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void misclassifiedLpExitWithReceiptLegFirstStillCarriesBasisToLpToken() {
        String lpContract = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
        String ghoContract = "0x40d16fc0246ad3160ccc09b8d0d3a2cd28ae6c2f";
        String usdcContract = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
        String usdtContract = "0x9702230a8bfeacae2f24a530c484fc24572abbf3";

        NormalizedTransaction ghoBuy = tx("1", "0xgho", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "GHO", ghoContract, "1140", "1", PriceSource.STABLECOIN));
        ghoBuy.setWalletAddress("wallet-a");
        ghoBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction usdcBuy = tx("2", "0xusdc", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", usdcContract, "500", "1", PriceSource.STABLECOIN));
        usdcBuy.setWalletAddress("wallet-a");
        usdcBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction usdtBuy = tx("3", "0xusdt", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDT", usdtContract, "500", "1", PriceSource.STABLECOIN));
        usdtBuy.setWalletAddress("wallet-a");
        usdtBuy.setNetworkId(NetworkId.AVALANCHE);

        // Production Curve mint: LP_EXIT with positive LP leg listed before outbound underlyings.
        NormalizedTransaction lpMint = tx("4", "0xlp-mint", 3, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "AAVE GHO/USDT/USDC", lpContract, "2140", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "GHO", ghoContract, "-1140", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", usdcContract, "-500", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", usdtContract, "-500", null, null));
        lpMint.setWalletAddress("wallet-a");
        lpMint.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(ghoBuy, usdcBuy, usdtBuy, lpMint));

        service().replayConfirmed();

        AssetLedgerPoint lpReceipt = latestPoint(
                capturedLedgerPoints(),
                "wallet-a",
                NetworkId.AVALANCHE,
                "AAVE GHO/USDT/USDC",
                lpContract
        );
        assertThat(lpReceipt.getQuantityAfter()).isEqualByComparingTo("2140");
        assertThat(lpReceipt.getTotalCostBasisAfterUsd()).isEqualByComparingTo("2140");
        assertThat(lpReceipt.getUncoveredQuantityAfter()).isZero();
    }

    @Test
    void curveLpGaugeStakePreservesBasisOnWrapperToken() {
        String lpContract = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
        String gaugeContract = "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951";

        NormalizedTransaction lpBuy = tx("1", "0xlp-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "AAVE GHO/USDT/USDC", lpContract, "3000", "1", PriceSource.STABLECOIN));
        lpBuy.setWalletAddress("wallet-a");
        lpBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction gaugeStake = tx("2", "0xgauge", 1, NormalizedTransactionType.LP_POSITION_STAKE,
                flowWithContract(NormalizedLegRole.TRANSFER, "AAVE GHO/USDT/USDC", lpContract, "-3000", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "Aave GHO/USDT/USDC-gauge", gaugeContract, "3000", null, null));
        gaugeStake.setWalletAddress("wallet-a");
        gaugeStake.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(lpBuy, gaugeStake));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint gauge = points.stream()
                .filter(p -> gaugeContract.equalsIgnoreCase(p.getAssetContract()))
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(gauge.getQuantityAfter()).isEqualByComparingTo("3000");
        assertThat(gauge.getTotalCostBasisAfterUsd()).isEqualByComparingTo("3000");
        assertThat(gauge.getUncoveredQuantityAfter()).isZero();
    }

    @Test
    void correlatedLpExitCarriesCrossAssetPositionBasisIntoReturnedPrincipalOnly() {
        NormalizedTransaction ethBuy = tx("1", "0xeth-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        ethBuy.setWalletAddress("wallet-a");
        ethBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction usdcBuy = tx("2", "0xusdc-buy", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "200", "1", PriceSource.STABLECOIN));
        usdcBuy.setWalletAddress("wallet-a");
        usdcBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction lpEntry = tx("3", "0xlp-entry", 2, NormalizedTransactionType.LP_ENTRY,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "-200", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.ARBITRUM);
        lpEntry.setProtocolName("PancakeSwap");
        lpEntry.setCorrelationId("lp-position:arbitrum:pancakeswap:123");

        NormalizedTransaction lpExit = tx("4", "0xlp-exit", 3, NormalizedTransactionType.LP_EXIT,
                flow(NormalizedLegRole.TRANSFER, "CAKE", "10", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "1.5", null, null));
        lpExit.setWalletAddress("wallet-a");
        lpExit.setNetworkId(NetworkId.ARBITRUM);
        lpExit.setProtocolName("PancakeSwap");
        lpExit.setCorrelationId("lp-position:arbitrum:pancakeswap:123");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(ethBuy, usdcBuy, lpEntry, lpExit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint eth = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        AssetLedgerPoint cake = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "CAKE", null);

        assertThat(eth.getQuantityAfter()).isEqualByComparingTo("1.5");
        assertThat(eth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("300");
        assertThat(eth.getUncoveredQuantityAfter()).isZero();
        assertThat(eth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        assertThat(cake.getQuantityAfter()).isEqualByComparingTo("10");
        assertThat(cake.getTotalCostBasisAfterUsd()).isZero();
        assertThat(cake.getUncoveredQuantityAfter()).isEqualByComparingTo("10");
        assertThat(cake.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.UNKNOWN);
    }

    @Test
    void correlatedLpExitCarriesCoveredCrossAssetBasisIntoResidualStablecoinReturn() {
        NormalizedTransaction wethBuy = tx("1", "0xweth-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "WETH", "0xweth", "1", "100", PriceSource.BINANCE));
        wethBuy.setWalletAddress("wallet-a");
        wethBuy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction uncoveredUsdc = tx("2", "0xusdc-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "200", null, PriceSource.UNKNOWN));
        uncoveredUsdc.setWalletAddress("wallet-a");
        uncoveredUsdc.setNetworkId(NetworkId.BASE);

        NormalizedTransaction lpEntry = tx("3", "0xlp-entry", 2, NormalizedTransactionType.LP_ENTRY,
                flowWithContract(NormalizedLegRole.TRANSFER, "WETH", "0xweth", "-1", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "-200", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.BASE);
        lpEntry.setProtocolName("PancakeSwap");
        lpEntry.setCorrelationId("lp-position:base:pancakeswap:synthetic-1");

        NormalizedTransaction lpExit = tx("4", "0xlp-exit", 3, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "250", null, null));
        lpExit.setWalletAddress("wallet-a");
        lpExit.setNetworkId(NetworkId.BASE);
        lpExit.setProtocolName("PancakeSwap");
        lpExit.setCorrelationId("lp-position:base:pancakeswap:synthetic-1");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wethBuy, uncoveredUsdc, lpEntry, lpExit));

        service().replayConfirmed();

        AssetLedgerPoint usdc = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.BASE, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        assertThat(usdc.getQuantityAfter()).isEqualByComparingTo("250");
        // Cycle/19: the stablecoin $1 fallback now covers the 200 USDC that were previously
        // uncovered, so the full LP bucket (WETH $100 + USDC $200) flows through.
        assertThat(usdc.getTotalCostBasisAfterUsd()).isEqualByComparingTo("300");
        assertThat(usdc.getBasisBackedQuantityAfter()).isEqualByComparingTo("250");
        assertThat(usdc.getUncoveredQuantityAfter()).isEqualByComparingTo("0");
        assertThat(usdc.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void rewardOnlyLpExitSideflowDoesNotClearPositionBucketBeforeLaterPrincipalExit() {
        NormalizedTransaction wethBuy = tx("1", "0xweth-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "WETH", "0xweth", "1", "100", PriceSource.BINANCE));
        wethBuy.setWalletAddress("wallet-a");
        wethBuy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction uncoveredUsdc = tx("2", "0xusdc-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "200", null, PriceSource.UNKNOWN));
        uncoveredUsdc.setWalletAddress("wallet-a");
        uncoveredUsdc.setNetworkId(NetworkId.BASE);

        NormalizedTransaction lpEntry = tx("3", "0xlp-entry", 2, NormalizedTransactionType.LP_ENTRY,
                flowWithContract(NormalizedLegRole.TRANSFER, "WETH", "0xweth", "-1", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "-200", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.BASE);
        lpEntry.setProtocolName("PancakeSwap");
        lpEntry.setCorrelationId("lp-position:base:pancakeswap:synthetic-3");

        NormalizedTransaction rewardExit = tx("4", "0xlp-reward-exit", 3, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "CAKE", "0xcake", "10", null, null));
        rewardExit.setWalletAddress("wallet-a");
        rewardExit.setNetworkId(NetworkId.BASE);
        rewardExit.setProtocolName("PancakeSwap");
        rewardExit.setCorrelationId("lp-position:base:pancakeswap:synthetic-3");

        NormalizedTransaction principalExit = tx("5", "0xlp-principal-exit", 4, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "250", null, null));
        principalExit.setWalletAddress("wallet-a");
        principalExit.setNetworkId(NetworkId.BASE);
        principalExit.setProtocolName("PancakeSwap");
        principalExit.setCorrelationId("lp-position:base:pancakeswap:synthetic-3");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wethBuy, uncoveredUsdc, lpEntry, rewardExit, principalExit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint cake = latestPoint(points, "wallet-a", NetworkId.BASE, "CAKE", "0xcake");
        AssetLedgerPoint usdc = latestPoint(points, "wallet-a", NetworkId.BASE, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");

        assertThat(cake.getQuantityAfter()).isEqualByComparingTo("10");
        assertThat(cake.getTotalCostBasisAfterUsd()).isZero();
        assertThat(cake.getUncoveredQuantityAfter()).isEqualByComparingTo("10");
        assertThat(cake.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.UNKNOWN);

        assertThat(usdc.getQuantityAfter()).isEqualByComparingTo("250");
        // Cycle/19: stablecoin $1 fallback covers the 200 USDC → full LP bucket basis flows through.
        assertThat(usdc.getTotalCostBasisAfterUsd()).isEqualByComparingTo("300");
        assertThat(usdc.getBasisBackedQuantityAfter()).isEqualByComparingTo("250");
        assertThat(usdc.getUncoveredQuantityAfter()).isEqualByComparingTo("0");
        assertThat(usdc.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void pricedLpExitSideflowDoesNotConsumeOpenPrincipalBucket() {
        NormalizedTransaction xyzBuy = tx("1", "0xxyz-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "XYZ", "0xxyz", "100", "1", PriceSource.BINANCE));
        xyzBuy.setWalletAddress("wallet-a");
        xyzBuy.setNetworkId(NetworkId.BSC);

        NormalizedTransaction lpEntry = tx("2", "0xlp-entry", 1, NormalizedTransactionType.LP_ENTRY,
                flowWithContract(NormalizedLegRole.TRANSFER, "XYZ", "0xxyz", "-100", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.BSC);
        lpEntry.setProtocolName("PancakeSwap");
        lpEntry.setCorrelationId("lp-position:bsc:pancakeswap:reward-sideflow");

        NormalizedTransaction partialExit = tx("3", "0xlp-exit-partial", 2, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", "0x55d398326f99059ff775485246999027b3197955", "5", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "XYZ", "0xxyz", "40", null, null));
        partialExit.setWalletAddress("wallet-a");
        partialExit.setNetworkId(NetworkId.BSC);
        partialExit.setProtocolName("PancakeSwap");
        partialExit.setCorrelationId("lp-position:bsc:pancakeswap:reward-sideflow");

        NormalizedTransaction finalExit = tx("4", "0xlp-exit-final", 3, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "XYZ", "0xxyz", "60", null, null));
        finalExit.setWalletAddress("wallet-a");
        finalExit.setNetworkId(NetworkId.BSC);
        finalExit.setProtocolName("PancakeSwap");
        finalExit.setCorrelationId("lp-position:bsc:pancakeswap:reward-sideflow");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(xyzBuy, lpEntry, partialExit, finalExit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint usdt = latestPoint(points, "wallet-a", NetworkId.BSC, "USDT", "0x55d398326f99059ff775485246999027b3197955");
        AssetLedgerPoint xyz = latestPoint(points, "wallet-a", NetworkId.BSC, "XYZ", "0xxyz");

        assertThat(usdt.getQuantityAfter()).isEqualByComparingTo("5");
        assertThat(usdt.getTotalCostBasisAfterUsd()).isEqualByComparingTo("5");
        assertThat(usdt.getBasisBackedQuantityAfter()).isEqualByComparingTo("5");
        assertThat(usdt.getUncoveredQuantityAfter()).isZero();
        assertThat(usdt.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);

        assertThat(xyz.getQuantityAfter()).isEqualByComparingTo("100");
        assertThat(xyz.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(xyz.getBasisBackedQuantityAfter()).isEqualByComparingTo("100");
        assertThat(xyz.getUncoveredQuantityAfter()).isZero();
        assertThat(xyz.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        assertThat(points.stream()
                .filter(point -> "0xlp-exit-partial".equals(point.getTxHash()) || "0xlp-exit-final".equals(point.getTxHash()))
                .map(AssetLedgerPoint::getBasisEffect))
                .doesNotContain(AssetLedgerPoint.BasisEffect.UNKNOWN);
    }

    @Test
    void correlatedLpExitAllocatesResidualStablecoinBasketByReplayKnownValue() {
        NormalizedTransaction wethBuy = tx("1", "0xweth-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "WETH", "0xweth", "1", "100", PriceSource.BINANCE));
        wethBuy.setWalletAddress("wallet-a");
        wethBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction uncoveredUsdc = tx("2", "0xusdc-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "200", null, PriceSource.UNKNOWN));
        uncoveredUsdc.setWalletAddress("wallet-a");
        uncoveredUsdc.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction lpEntry = tx("3", "0xlp-entry", 2, NormalizedTransactionType.LP_ENTRY,
                flowWithContract(NormalizedLegRole.TRANSFER, "WETH", "0xweth", "-1", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "-200", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.ARBITRUM);
        lpEntry.setProtocolName("PancakeSwap");
        lpEntry.setCorrelationId("lp-position:arbitrum:pancakeswap:synthetic-2");

        NormalizedTransaction lpExit = tx("4", "0xlp-exit", 3, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "200", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "50", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "DAI", "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", "50", null, null));
        lpExit.setWalletAddress("wallet-a");
        lpExit.setNetworkId(NetworkId.ARBITRUM);
        lpExit.setProtocolName("PancakeSwap");
        lpExit.setCorrelationId("lp-position:arbitrum:pancakeswap:synthetic-2");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wethBuy, uncoveredUsdc, lpEntry, lpExit));

        service().replayConfirmed();

        AssetLedgerPoint usdt = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "USDT", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9");
        AssetLedgerPoint dai = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "DAI", "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1");

        // Cycle/15 round 2: multi-asset outbound LP_ENTRY now routes to per-asset
        // LpReceiptBasisPool entries; cross-asset basis carries to the same-asset return leg
        // (USDC here), and sideflow stablecoin returns (USDT/DAI) are priced at $1 each via the
        // sideflow ACQUIRE path. Financial outcome is identical to the prior async-bucket
        // distribution ($50 basis each), only the BasisEffect label changes.
        assertThat(usdt.getQuantityAfter()).isEqualByComparingTo("50");
        assertThat(usdt.getTotalCostBasisAfterUsd()).isEqualByComparingTo("50");
        assertThat(usdt.getBasisBackedQuantityAfter()).isEqualByComparingTo("50");
        assertThat(usdt.getUncoveredQuantityAfter()).isZero();
        assertThat(usdt.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);

        assertThat(dai.getQuantityAfter()).isEqualByComparingTo("50");
        assertThat(dai.getTotalCostBasisAfterUsd()).isEqualByComparingTo("50");
        assertThat(dai.getBasisBackedQuantityAfter()).isEqualByComparingTo("50");
        assertThat(dai.getUncoveredQuantityAfter()).isZero();
        assertThat(dai.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
    }

    @Test
    void gmxLpEntrySettlementStillAllocatesPrincipalWhenExecutionFeeReserveIsUncovered() {
        NormalizedTransaction usdcBuy = tx("1", "0xusdc-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "149.713585", "1", PriceSource.STABLECOIN));
        usdcBuy.setWalletAddress("wallet-a");
        usdcBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction ethTopUp = tx("2", "0xeth-topup", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.0001850891676072", null, PriceSource.UNKNOWN));
        ethTopUp.setWalletAddress("wallet-a");
        ethTopUp.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction request = tx("3", "0xff684e", 2, NormalizedTransactionType.LP_ENTRY_REQUEST,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "-149.713585", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.0001653292167072", null, null),
                flow(NormalizedLegRole.FEE, "ETH", "-0.0000197599509", "1907.2", PriceSource.BYBIT));
        request.setWalletAddress("wallet-a");
        request.setNetworkId(NetworkId.ARBITRUM);
        request.setProtocolName("GMX");
        request.setCorrelationId("gmx:lp-entry:1");

        NormalizedTransaction settlement = tx("4", "0x1aa343", 3, NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.0000528338469792", null, null),
                flowWithContract(
                        NormalizedLegRole.TRANSFER,
                        "GM: ETH/USD [WETH-USDC]",
                        "0xgm",
                        "97.960355697851727936",
                        null,
                        null
                ));
        settlement.setWalletAddress("wallet-a");
        settlement.setNetworkId(NetworkId.ARBITRUM);
        settlement.setProtocolName("GMX");
        settlement.setCorrelationId("gmx:lp-entry:1");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(usdcBuy, ethTopUp, request, settlement));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint gm = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "GM: ETH/USD [WETH-USDC]", "0xgm");
        AssetLedgerPoint eth = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);

        assertThat(gm.getQuantityAfter()).isEqualByComparingTo("97.960355697851727936");
        assertThat(gm.getTotalCostBasisAfterUsd()).isEqualByComparingTo("149.713585");
        assertThat(gm.getBasisBackedQuantityAfter()).isEqualByComparingTo("97.960355697851727936");
        assertThat(gm.getUncoveredQuantityAfter()).isZero();
        assertThat(gm.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        assertThat(eth.getQuantityAfter()).isEqualByComparingTo("0.0000528338469792");
        assertThat(eth.getTotalCostBasisAfterUsd()).isZero();
        assertThat(eth.getUncoveredQuantityAfter()).isEqualByComparingTo("0.0000528338469792");
        assertThat(eth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void sameWalletBridgeOutAfterLocalAcquisitionUsesPooledPositionInsteadOfReservedInboundSlice() {
        NormalizedTransaction sourceCoveredBuy = tx("1", "0xsource-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.1", "100", PriceSource.BINANCE));
        sourceCoveredBuy.setWalletAddress("wallet-a");
        sourceCoveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceUncoveredBuy = tx("2", "0xsource-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.6", null, null));
        sourceUncoveredBuy.setWalletAddress("wallet-a");
        sourceUncoveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction firstBridgeOut = tx("3", "0xbridge-one-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.7", null, null));
        firstBridgeOut.setWalletAddress("wallet-a");
        firstBridgeOut.setNetworkId(NetworkId.ARBITRUM);
        firstBridgeOut.setCorrelationId("bridge:one");
        firstBridgeOut.setContinuityCandidate(true);
        firstBridgeOut.setMatchedCounterparty("0xbridge-one-in");

        NormalizedTransaction firstBridgeIn = tx("4", "0xbridge-one-in", 3, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.7", null, null));
        firstBridgeIn.setWalletAddress("wallet-a");
        firstBridgeIn.setNetworkId(NetworkId.UNICHAIN);
        firstBridgeIn.setCorrelationId("bridge:one");
        firstBridgeIn.setContinuityCandidate(true);
        firstBridgeIn.setMatchedCounterparty("0xbridge-one-out");

        NormalizedTransaction localCoveredAcquisition = tx("5", "0xlocal-covered", 4, NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.BUY, "ETH", "0.3", "1000", PriceSource.BINANCE));
        localCoveredAcquisition.setWalletAddress("wallet-a");
        localCoveredAcquisition.setNetworkId(NetworkId.UNICHAIN);

        NormalizedTransaction secondBridgeOut = tx("6", "0xbridge-two-out", 5, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.68", null, null));
        secondBridgeOut.setWalletAddress("wallet-a");
        secondBridgeOut.setNetworkId(NetworkId.UNICHAIN);
        secondBridgeOut.setCorrelationId("bridge:two");
        secondBridgeOut.setContinuityCandidate(true);
        secondBridgeOut.setMatchedCounterparty("0xbridge-two-in");

        NormalizedTransaction secondBridgeIn = tx("7", "0xbridge-two-in", 6, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.68", null, null));
        secondBridgeIn.setWalletAddress("wallet-a");
        secondBridgeIn.setNetworkId(NetworkId.ARBITRUM);
        secondBridgeIn.setCorrelationId("bridge:two");
        secondBridgeIn.setContinuityCandidate(true);
        secondBridgeIn.setMatchedCounterparty("0xbridge-two-out");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(
                sourceCoveredBuy,
                sourceUncoveredBuy,
                firstBridgeOut,
                firstBridgeIn,
                localCoveredAcquisition,
                secondBridgeOut,
                secondBridgeIn
        ));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.UNICHAIN, "ETH", null);

        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("0.68");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("310");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.40");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0.28");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);

        assertThat(source.getQuantityAfter()).isEqualByComparingTo("0.32");
        assertThat(source.getTotalCostBasisAfterUsd()).isZero();
        assertThat(source.getUncoveredQuantityAfter()).isEqualByComparingTo("0.32");
        assertThat(source.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_OUT);
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredStableBuy, uncoveredStableBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        AssetLedgerPoint destination = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.KATANA, "ETH", null);
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1");
        // Cycle/19: stablecoin $1 fallback covers the 100 "uncovered" USDC, so the full 200 USDC
        // basis ($200) flows through the bridge settlement into the ETH destination.
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("1");
        assertThat(destination.getUncoveredQuantityAfter()).isEqualByComparingTo("0");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("200");
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void assetChangingBridgeSettlementIgnoresSecondaryBuyLegOnDestination() {
        NormalizedTransaction principalBuy = tx("1", "0xusde-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDe", "0xusde", "100", "1", PriceSource.STABLECOIN));
        principalBuy.setWalletAddress("wallet-a");
        principalBuy.setNetworkId(NetworkId.MANTLE);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDe", "0xusde", "-100", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.MANTLE);
        bridgeOut.setCorrelationId("bridge:lifi:route-settlement");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flowWithContract(NormalizedLegRole.TRANSFER, "USD₮0", "0xusdt0", "99.9", null, null),
                flow(NormalizedLegRole.BUY, "ETH", "0.01", "30", PriceSource.BINANCE));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:lifi:route-settlement");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(principalBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint stableDestination = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "USD₮0", "0xusdt0");
        AssetLedgerPoint bonusEth = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        assertThat(stableDestination.getQuantityAfter()).isEqualByComparingTo("99.9");
        assertThat(stableDestination.getBasisBackedQuantityAfter()).isEqualByComparingTo("99.9");
        assertThat(stableDestination.getUncoveredQuantityAfter()).isZero();
        assertThat(stableDestination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(stableDestination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        assertThat(bonusEth.getQuantityAfter()).isEqualByComparingTo("0.01");
        assertThat(bonusEth.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.01");
        assertThat(bonusEth.getUncoveredQuantityAfter()).isZero();
        assertThat(bonusEth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("0.30");
        assertThat(bonusEth.getAvcoAfterUsd()).isEqualByComparingTo("30");
        assertThat(bonusEth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void continuityMirrorDuplicateIsSkippedByReplayDedup() {
        // Cycle/7 S5: even if a Bybit stream mirror somehow survives the upstream
        // BybitStreamAuthorityCollapser and reaches replay as a second basis-relevant doc with the
        // same (corrId, wallet, family, sign) signature, the dispatcher's continuity-flow dedup
        // guard must skip it. The destination quantity must reflect a single 1.0 ETH carry, NOT
        // a doubled-up 2.0 ETH inventory inflation.
        NormalizedTransaction coveredBuy = tx("1", "0xbuy-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", "100", PriceSource.BINANCE));
        coveredBuy.setWalletAddress("wallet-a");
        coveredBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction sourceTransfer = tx("2", "0xcarry", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1.0", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setNetworkId(NetworkId.ARBITRUM);
        sourceTransfer.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcarry");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("BYBIT:1:FUND");

        NormalizedTransaction bybitInboundCanonical = tx("3", "0xcarry", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", null, null));
        bybitInboundCanonical.setSource(NormalizedTransactionSource.BYBIT);
        bybitInboundCanonical.setWalletAddress("BYBIT:1:FUND");
        bybitInboundCanonical.setNetworkId(NetworkId.ARBITRUM);
        bybitInboundCanonical.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcarry");
        bybitInboundCanonical.setContinuityCandidate(true);
        bybitInboundCanonical.setMatchedCounterparty("wallet-a");

        // Mirror with the same (corrId, wallet, family, sign) signature — must be skipped.
        NormalizedTransaction bybitInboundMirror = tx("4", "0xcarry", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", null, null));
        bybitInboundMirror.setSource(NormalizedTransactionSource.BYBIT);
        bybitInboundMirror.setWalletAddress("BYBIT:1:FUND");
        bybitInboundMirror.setNetworkId(NetworkId.ARBITRUM);
        bybitInboundMirror.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcarry");
        bybitInboundMirror.setContinuityCandidate(true);
        bybitInboundMirror.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, sourceTransfer, bybitInboundCanonical, bybitInboundMirror));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // BYBIT-CORRIDOR inbound to :FUND now keeps the full sub-account address (P-B fix).
        AssetLedgerPoint destination = latestPoint(points, "BYBIT:1:FUND", null, "ETH", null);
        // Without dedup, the mirror would push quantity to 2.0. With dedup, it stays at 1.0.
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1.0");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void bybitEarnPrincipalRedeemRestoresCoveredBasisOnFundInbound() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "AGLD", "500", "5", PriceSource.BINANCE));
        buy.setSource(NormalizedTransactionSource.BYBIT);
        buy.setWalletAddress("BYBIT:33625378");
        buy.getFlows().getFirst().setAccountRef("BYBIT:33625378");

        NormalizedTransaction earnOut = tx("2", "0xearn-out", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "AGLD", "-50.22", null, null));
        earnOut.setSource(NormalizedTransactionSource.BYBIT);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.getFlows().getFirst().setAccountRef("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        earnOut.setMatchedCounterparty("BYBIT:33625378:FUND");

        NormalizedTransaction fundIn = tx("3", "0xearn-in", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "AGLD", "50.22", null, null));
        fundIn.setSource(NormalizedTransactionSource.BYBIT);
        fundIn.setWalletAddress("BYBIT:33625378:FUND");
        fundIn.getFlows().getFirst().setAccountRef("BYBIT:33625378:FUND");
        fundIn.setContinuityCandidate(true);
        fundIn.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        fundIn.setMatchedCounterparty("BYBIT:33625378:EARN");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, earnOut, fundIn));

        service().replayConfirmed();

        AssetLedgerPoint fund = capturedLedgerPoints().stream()
                .filter(point -> "BYBIT:33625378:FUND".equals(point.getWalletAddress()))
                .filter(point -> "AGLD".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getQuantityDelta() != null && point.getQuantityDelta().signum() > 0)
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(fund.getBasisEffect()).isIn(
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );
        assertThat(fund.getTotalCostBasisAfterUsd()).isGreaterThan(new BigDecimal("200"));
        assertThat(fund.getUncoveredQuantityAfter()).isZero();
        assertThat(fund.getAvcoAfterUsd()).isNotNull();
    }

    @Test
    void bybitEarnPrincipalRedeemRestoresUmbrellaBasisOnFundInbound() {
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", "3000", PriceSource.BINANCE));
        buy.setSource(NormalizedTransactionSource.BYBIT);
        buy.setWalletAddress("BYBIT:33625378");

        NormalizedTransaction earnOut = tx("2", "0xearn-out", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.151", null, null));
        earnOut.setSource(NormalizedTransactionSource.BYBIT);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:ae372912");
        earnOut.setMatchedCounterparty("BYBIT:33625378");

        NormalizedTransaction fundIn = tx("3", "0xearn-in", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.151", null, null));
        fundIn.setSource(NormalizedTransactionSource.BYBIT);
        fundIn.setWalletAddress("BYBIT:33625378");
        fundIn.setContinuityCandidate(true);
        fundIn.setCorrelationId("bybit-earn-principal-v1:ae372912");
        fundIn.setMatchedCounterparty("BYBIT:33625378:EARN");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, earnOut, fundIn));

        service().replayConfirmed();

        AssetLedgerPoint fund = capturedLedgerPoints().stream()
                .filter(point -> "BYBIT:33625378".equals(point.getWalletAddress()))
                .filter(point -> "ETH".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getQuantityDelta() != null && point.getQuantityDelta().signum() > 0)
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(fund.getBasisEffect()).isIn(
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );
        assertThat(fund.getTotalCostBasisAfterUsd()).isGreaterThan(new BigDecimal("400"));
        assertThat(fund.getUncoveredQuantityAfter()).isLessThan(new BigDecimal("0.02"));
        assertThat(fund.getAvcoAfterUsd()).isNotNull();
    }

    @Test
    void canonicalLpReceiptSymbolPreventsDuplicateReceiptPoolsOnEntryAndExit() {
        NormalizedTransaction wethBuy = tx("1", "0xweth-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "WETH", "0xweth", "1", "1000", PriceSource.BINANCE));
        wethBuy.setWalletAddress("wallet-a");
        wethBuy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction lpEntry = tx("2", "0xlp-entry", 1, NormalizedTransactionType.LP_ENTRY,
                flowWithContract(NormalizedLegRole.TRANSFER, "WETH", "0xweth", "-1", null, null));
        lpEntry.setWalletAddress("wallet-a");
        lpEntry.setNetworkId(NetworkId.BASE);
        lpEntry.setProtocolName("PancakeSwap");
        lpEntry.setCorrelationId("lp-position:base:pancakeswap:477096");
        java.util.List<NormalizedTransaction.Flow> entryFlows = new java.util.ArrayList<>(lpEntry.getFlows());
        entryFlows.add(flow(NormalizedLegRole.TRANSFER, "LP-RECEIPT:base:pancakeswap:477096", "1", null, null));
        lpEntry.setFlows(entryFlows);

        NormalizedTransaction lpExit = tx("3", "0xlp-exit", 2, NormalizedTransactionType.LP_EXIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "WETH", "0xweth", "1", null, null));
        lpExit.setWalletAddress("wallet-a");
        lpExit.setNetworkId(NetworkId.BASE);
        lpExit.setProtocolName("PancakeSwap");
        lpExit.setCorrelationId("lp-position:base:pancakeswap:477096");
        java.util.List<NormalizedTransaction.Flow> exitFlows = new java.util.ArrayList<>(lpExit.getFlows());
        exitFlows.add(flow(NormalizedLegRole.TRANSFER, "LP-RECEIPT:base:pancakeswap:477096", "-1", null, null));
        lpExit.setFlows(exitFlows);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wethBuy, lpEntry, lpExit));

        service().replayConfirmed();

        String canonical = "LP-RECEIPT:BASE:PANCAKESWAP:477096";
        List<AssetLedgerPoint> receiptPoints = capturedLedgerPoints().stream()
                .filter(point -> canonical.equalsIgnoreCase(point.getAssetSymbol()))
                .toList();
        assertThat(receiptPoints).isNotEmpty();
        AssetLedgerPoint lastReceipt = receiptPoints.stream()
                .max(java.util.Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(lastReceipt.getQuantityAfter()).isEqualByComparingTo("0");
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
    void liquidStakingCarriesAvailableCoveredPrincipalWithoutRatioCuttingDerivative() {
        NormalizedTransaction coveredBuy = tx("1", "0xavax-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "AVAX", "0.4", "10", PriceSource.BINANCE));
        coveredBuy.setWalletAddress("wallet-a");
        coveredBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction uncoveredBuy = tx("2", "0xavax-uncovered", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "AVAX", "0.1", null, PriceSource.UNKNOWN));
        uncoveredBuy.setWalletAddress("wallet-a");
        uncoveredBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction stake = tx(
                "3",
                "0xavax-stake",
                2,
                NormalizedTransactionType.STAKING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "AVAX", "-0.5", null, null),
                flow(NormalizedLegRole.TRANSFER, "sAVAX", "0.4", null, null)
        );
        stake.setWalletAddress("wallet-a");
        stake.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, uncoveredBuy, stake));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint derivative = latestPoint(points, "wallet-a", NetworkId.AVALANCHE, "sAVAX", null);
        assertThat(derivative.getQuantityAfter()).isEqualByComparingTo("0.4");
        assertThat(derivative.getBasisBackedQuantityAfter()).isEqualByComparingTo("0.4");
        assertThat(derivative.getUncoveredQuantityAfter()).isZero();
        assertThat(derivative.getTotalCostBasisAfterUsd()).isEqualByComparingTo("4");
        assertThat(derivative.getAvcoAfterUsd()).isEqualByComparingTo("10");
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

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
        com.walletradar.costbasis.application.replay.support.ReplayAssetSupport assetSupport =
                new com.walletradar.costbasis.application.replay.support.ReplayAssetSupport();
        com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine genericFlowReplayEngine =
                new com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine();
        com.walletradar.costbasis.application.replay.support.ReplayFlowSupport replayFlowSupport =
                new com.walletradar.costbasis.application.replay.support.ReplayFlowSupport(genericFlowReplayEngine);
        com.walletradar.costbasis.application.replay.support.ContinuityCarryService continuityCarryService =
                new com.walletradar.costbasis.application.replay.support.ContinuityCarryService(
                        genericFlowReplayEngine,
                        replayFlowSupport
                );
        com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory keyFactory =
                new com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory(assetSupport);
        com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier transferClassifier =
                new com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier(keyFactory);
        com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher pendingTransferMatcher =
                new com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher();
        com.walletradar.costbasis.application.replay.support.ReplaySettlementAllocator settlementAllocator =
                new com.walletradar.costbasis.application.replay.support.ReplaySettlementAllocator(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.pricing.persistence.HistoricalPriceCacheService historicalPriceCacheService =
                org.mockito.Mockito.mock(com.walletradar.pricing.persistence.HistoricalPriceCacheService.class);
        com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator priceExternalSourceOrchestrator =
                org.mockito.Mockito.mock(com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator.class);
        org.mockito.Mockito.lenient().when(priceExternalSourceOrchestrator.prioritizedSources(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(PriceSource.BINANCE));
        org.mockito.Mockito.lenient().when(historicalPriceCacheService.findQuote(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(PriceSource.BINANCE)))
                .thenAnswer(invocation -> {
                    com.walletradar.pricing.domain.PriceRequest request = invocation.getArgument(0);
                    if (request != null && "ETH".equalsIgnoreCase(request.assetSymbol())) {
                        return java.util.Optional.of(new com.walletradar.pricing.domain.PriceQuote(
                                new BigDecimal("3000"),
                                PriceSource.BINANCE,
                                Instant.parse("2026-03-25T10:00:00Z"),
                                "ETH",
                                "test"
                        ));
                    }
                    return java.util.Optional.empty();
                });
        com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority replayMarketAuthority =
                new com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority(
                        historicalPriceCacheService,
                        priceExternalSourceOrchestrator
                );
        com.walletradar.costbasis.application.replay.handler.TransferReplayHandler transferReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.TransferReplayHandler(
                        replayFlowSupport,
                        continuityCarryService,
                        keyFactory,
                        transferClassifier,
                        pendingTransferMatcher,
                        replayMarketAuthority
                );
        com.walletradar.costbasis.application.replay.handler.LiquidStakingReplayHandler liquidStakingReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.LiquidStakingReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator
                );
        com.walletradar.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler familyReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        continuityCarryService,
                        keyFactory
                );
        com.walletradar.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator
                );
        com.walletradar.costbasis.application.replay.handler.GmxLpEntryReplayHandler gmxLpEntryReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.GmxLpEntryReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator
                );
        com.walletradar.costbasis.domain.LpReceiptBasisPoolRepository lpReceiptBasisPoolRepository =
                org.mockito.Mockito.mock(com.walletradar.costbasis.domain.LpReceiptBasisPoolRepository.class);
        org.mockito.Mockito.when(lpReceiptBasisPoolRepository.findByUniverseId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());
        LpReceiptBasisPoolService lpReceiptBasisPoolService = new LpReceiptBasisPoolService(lpReceiptBasisPoolRepository);
        com.walletradar.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator,
                        lpReceiptBasisPoolService,
                        keyFactory
                );
        com.walletradar.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.costbasis.application.replay.handler.EulerLoopReplayHandler eulerLoopReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.EulerLoopReplayHandler(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.costbasis.domain.AssetFamilyResolver assetFamilyResolver =
                new com.walletradar.costbasis.domain.AssetFamilyResolver();
        com.walletradar.session.application.AccountingUniverseService accountingUniverseService =
                org.mockito.Mockito.mock(com.walletradar.session.application.AccountingUniverseService.class);
        com.walletradar.costbasis.domain.CounterpartyBasisPoolRepository counterpartyBasisPoolRepository =
                org.mockito.Mockito.mock(com.walletradar.costbasis.domain.CounterpartyBasisPoolRepository.class);
        org.mockito.Mockito.when(counterpartyBasisPoolRepository.findByUniverseId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());
        CounterpartyBasisPoolService counterpartyBasisPoolService = new CounterpartyBasisPoolService(
                counterpartyBasisPoolRepository,
                assetFamilyResolver,
                accountingUniverseService
        );
        com.walletradar.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook =
                new com.walletradar.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook(
                counterpartyBasisPoolService,
                transferClassifier
        );
        com.walletradar.costbasis.domain.BorrowLiabilityRepository borrowLiabilityRepository =
                org.mockito.Mockito.mock(com.walletradar.costbasis.domain.BorrowLiabilityRepository.class);
        org.mockito.Mockito.when(borrowLiabilityRepository.findByUniverseId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());
        BorrowLiabilityTracker borrowLiabilityTracker = new BorrowLiabilityTracker(borrowLiabilityRepository);
        com.walletradar.costbasis.application.replay.handler.BorrowReplayHandler borrowReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.BorrowReplayHandler(
                        borrowLiabilityTracker,
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.costbasis.application.replay.handler.RepayReplayHandler repayReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.RepayReplayHandler(
                        borrowLiabilityTracker,
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.costbasis.application.replay.handler.LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.LpReceiptEntryReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        lpReceiptBasisPoolService
                );
        com.walletradar.costbasis.application.replay.handler.BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler =
                new com.walletradar.costbasis.application.replay.handler.BybitVenueInternalReplayHandler(
                        transferClassifier,
                        transferReplayHandler
                );
        com.walletradar.costbasis.application.replay.dispatch.ReplayDispatcher replayDispatcher =
                new com.walletradar.costbasis.application.replay.dispatch.ReplayDispatcher(
                        new com.walletradar.costbasis.application.replay.planning.ReplayTransactionRouter(),
                        assetSupport,
                        replayFlowSupport,
                        transferClassifier,
                        keyFactory,
                        transferReplayHandler,
                        bybitVenueInternalReplayHandler,
                        liquidStakingReplayHandler,
                        familyReplayHandler,
                        genericAsyncLifecycleReplayHandler,
                        gmxLpEntryReplayHandler,
                        lpReceiptEntryReplayHandler,
                        positionScopedLpExitReplayHandler,
                        asyncSpotOrderReplayHandler,
                        eulerLoopReplayHandler,
                        counterpartyBasisPoolReplayHook,
                        borrowReplayHandler,
                        repayReplayHandler
                );
        com.walletradar.costbasis.domain.AccountingShortfallAuditRepository shortfallAuditRepository =
                org.mockito.Mockito.mock(com.walletradar.costbasis.domain.AccountingShortfallAuditRepository.class);
        AccountingShortfallAuditService accountingShortfallAuditService =
                new AccountingShortfallAuditService(shortfallAuditRepository);
        return new AvcoReplayService(
                new com.walletradar.costbasis.application.replay.query.ConfirmedReplayQueryService(normalizedTransactionRepository),
                normalizedTransactionRepository,
                assetLedgerPointRepository,
                new com.walletradar.costbasis.application.replay.planning.PassThroughCorridorPlanner(),
                assetSupport,
                replayFlowSupport,
                replayDispatcher,
                counterpartyBasisPoolService,
                lpReceiptBasisPoolService,
                borrowLiabilityTracker,
                accountingShortfallAuditService,
                accountingUniverseService
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
