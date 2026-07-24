package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.application.costbasis.support.BridgeSettlementMetadataSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeAll;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvcoReplayServiceTest {

    @BeforeAll
    static void bindNetworkNativeAssets() {
        // Deterministically bind the NetworkNativeAssets / NetworkStablecoinContracts static bridges
        // from the authoritative network-descriptors.yml (same convention as
        // AccountingAssetIdentitySupportTest). In production NetworkRegistry binds these at startup,
        // so a native asset with no ERC-20 contract (e.g. GMX's native-ETH execution-fee reserve)
        // resolves to its NATIVE:<network> identity. Without this bind the static bridge returns
        // empty defaults, a bare "ETH" leg collapses to SYMBOL:ETH, and the GMX execution-fee-reserve
        // detection (which keys on the NATIVE: identity) silently fails — routing the request through
        // the generic async lifecycle path instead, where the uncovered reserve residual lands in the
        // principal bucket and blocks share-settlement principal allocation. Binding here removes that
        // flaky cross-test static-state dependency and mirrors the production wiring.
        com.walletradar.testsupport.NetworkTestFixtures.registry();
    }

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AssetLedgerPointRepository assetLedgerPointRepository;

    // RC-12 / ADR-030: stateful accumulator stores so refresh runs re-load prior persisted output
    // (exercises the empty-seed idempotency invariant — a reintroduced seed would double here).
    private final Map<String, Map<String, com.walletradar.application.costbasis.domain.BorrowLiability>> borrowStore =
            new LinkedHashMap<>();
    private final Map<String, Map<String, com.walletradar.application.costbasis.domain.CounterpartyBasisPool>> counterpartyStore =
            new LinkedHashMap<>();
    private final Map<String, Map<String, com.walletradar.application.costbasis.domain.LpReceiptBasisPool>> lpReceiptStore =
            new LinkedHashMap<>();
    private com.walletradar.application.costbasis.domain.BorrowLiabilityRepository borrowLiabilityRepositoryRef;

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
    void sponsoredGasInProducesGasOnlyBasisEffectWithZeroCostBasisDelta() {
        NormalizedTransaction topUp = tx("1", "0xgas-topup", 0, NormalizedTransactionType.SPONSORED_GAS_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.000004659018813092", null, null));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(topUp));

        service().replayConfirmed();

        AssetLedgerPoint point = latestPoint(capturedLedgerPoints(), "0xwallet", NetworkId.BASE, "ETH", null);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.GAS_ONLY);
        // Tiny rebate qty is added with zero cost — cost basis and AVCO remain $0
        assertThat(point.getTotalCostBasisAfterUsd()).isZero();
        assertThat(point.getAvcoAfterUsd()).isZero();
    }

    /**
     * B-USDC-1 — P1: BORROW must use market price, not position AVCO.
     * When the USDC position has AVCO=$1532 (from LP rebalancing), borrowing 800 USDC
     * at $1/USDC must record cbD=$800, not $1,225,570 (= 800 × $1532).
     */
    @Test
    void borrowUsesMarketPriceNotPositionAvcoForAcquisitionCost() {
        // Seed the USDC position with inflated AVCO ($1532) via LP-style REALLOCATE_IN
        NormalizedTransaction lpIn = tx("1", "0xlp", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "2", "1532", PriceSource.BINANCE));

        // BORROW 800 USDC at market $1 — position AVCO is $1532, but borrow must be at market
        NormalizedTransaction borrow = tx("2", "0xborrow", 1, NormalizedTransactionType.BORROW,
                flow(NormalizedLegRole.BUY, "USDC", "800", "1", PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(lpIn, borrow));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint borrowPoint = points.stream()
                .filter(p -> "2".equals(p.getNormalizedTransactionId()) && "USDC".equals(p.getAssetSymbol()))
                .findFirst()
                .orElseThrow();
        // cbD must be 800 × $1 = $800, NOT 800 × $1532 = $1,225,600
        assertThat(borrowPoint.getCostBasisDeltaUsd()).isEqualByComparingTo("800");
        assertThat(borrowPoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
        // Position AVCO after borrow: (2×1532 + 800×1) / (2+800) = (3064+800)/802 ≈ $4.82
        assertThat(borrowPoint.getAvcoAfterUsd()).isLessThan(new BigDecimal("10"));
    }

    /**
     * RC-12 / ADR-030 — replay accumulator idempotency. With the empty-seed fix, the persisted
     * {@code borrow_liabilities} book must be bit-identical across a full rebuild and N incremental
     * refreshes (the stateful repo re-loads prior output, so a reintroduced double-seed would
     * surface as a doubled qtyBorrowed on refresh×1). Covers BA edge E2 (a fully-repaid loan stays
     * {@code qtyOpen == 0} across refresh×N).
     */
    @Test
    void replayAccumulatorBorrowBookIsIdempotentAcrossRefreshN() {
        // loan-open: borrow 100 MNT, never repaid → qtyBorrowed=100, qtyOpen=100
        NormalizedTransaction borrowOpen = tx("1", "0xb-open", 0, NormalizedTransactionType.BORROW,
                flow(NormalizedLegRole.BUY, "MNT", "100", "1", PriceSource.BINANCE));
        borrowOpen.setCorrelationId("loan-open");

        // loan-partial: borrow 100 ETH, repay 40 ETH → qtyBorrowed=100, qtyOpen=60
        NormalizedTransaction borrowPartial = tx("2", "0xb-partial", 1, NormalizedTransactionType.BORROW,
                flow(NormalizedLegRole.BUY, "ETH", "100", "1", PriceSource.BINANCE));
        borrowPartial.setCorrelationId("loan-partial");
        NormalizedTransaction repayPartial = tx("3", "0xr-partial", 2, NormalizedTransactionType.REPAY,
                flow(NormalizedLegRole.SELL, "ETH", "-40", "1", PriceSource.BINANCE));
        repayPartial.setCorrelationId("loan-partial");

        // loan-closed (edge E2): borrow 50 USDC, repay 50 USDC → qtyOpen=0 stays 0 across refresh×N
        NormalizedTransaction borrowClosed = tx("4", "0xb-closed", 3, NormalizedTransactionType.BORROW,
                flow(NormalizedLegRole.BUY, "USDC", "50", "1", PriceSource.BINANCE));
        borrowClosed.setCorrelationId("loan-closed");
        NormalizedTransaction repayClosed = tx("5", "0xr-closed", 4, NormalizedTransactionType.REPAY,
                flow(NormalizedLegRole.SELL, "USDC", "-50", "1", PriceSource.BINANCE));
        repayClosed.setCorrelationId("loan-closed");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(borrowOpen, borrowPartial, repayPartial, borrowClosed, repayClosed));

        AvcoReplayService service = service();

        Map<String, String> rebuild = runAndSnapshotBorrowBook(service);
        Map<String, String> refresh1 = runAndSnapshotBorrowBook(service);
        Map<String, String> refresh2 = runAndSnapshotBorrowBook(service);

        // Hard idempotency check: books(rebuild) == books(rebuild→refresh) == books(rebuild→refresh×2).
        assertThat(refresh1).as("refresh×1 must equal rebuild (no double-seed)").isEqualTo(rebuild);
        assertThat(refresh2).as("refresh×2 must equal rebuild").isEqualTo(rebuild);

        // Per-loan: qtyBorrowed == on-chain borrowed; qtyOpen == borrowed − repaid.
        assertThat(rebuild.get("GLOBAL:loan-open")).isEqualTo("100|100|OPEN");
        assertThat(rebuild.get("GLOBAL:loan-partial")).isEqualTo("100|60|PARTIAL");
        // Edge E2: fully-repaid loan stays qtyOpen 0 (no negative, no re-double) across refresh×N.
        assertThat(rebuild.get("GLOBAL:loan-closed")).isEqualTo("50|0|CLOSED");
    }

    private Map<String, String> runAndSnapshotBorrowBook(AvcoReplayService service) {
        service.replayConfirmed();
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (com.walletradar.application.costbasis.domain.BorrowLiability liability
                : borrowLiabilityRepositoryRef.findByUniverseId("GLOBAL")) {
            snapshot.put(
                    liability.getCompositeId(),
                    plain(liability.getQtyBorrowed()) + "|" + plain(liability.getQtyOpen()) + "|" + liability.getStatus()
            );
        }
        return snapshot;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
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

    /**
     * C-1 / WS-B — a {@code bybit-collapsed-v1:} FUND→UTA pair (both legs {@code continuityCandidate}
     * + same corr) must carry the FUND-side basis into the UTA credit via the shared corr-family
     * queue. The seq816 defect orphaned the FUND CARRY_OUT because the UTA credit was excluded;
     * once both legs survive, the inherit-once machinery carries the basis with no synthetic credit.
     * Asserts quantity-conservation (carried-in qty == carried-out qty) and basis continuity
     * (UTA avco == FUND avco, not spot/$0).
     */
    @Test
    void bybitCollapsedFundToUtaPairCarriesBasisWithoutOrphan() {
        // FUND acquires 0.148 ETH @ $1000 (basis $148) — mirrors the seq816 ≈$391.85 carry shape.
        NormalizedTransaction fundBuy = tx("1", "bybit-fund-buy", 0, NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.BUY, "ETH", "0.148", "1000", PriceSource.BINANCE));
        fundBuy.setSource(NormalizedTransactionSource.BYBIT);
        fundBuy.setWalletAddress("BYBIT:1:FUND");
        fundBuy.setNetworkId(null);

        NormalizedTransaction fundOutbound = tx("2", "bybit-fund-out", 1, NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.148", null, null));
        fundOutbound.setSource(NormalizedTransactionSource.BYBIT);
        fundOutbound.setWalletAddress("BYBIT:1:FUND");
        fundOutbound.setNetworkId(null);
        fundOutbound.setCorrelationId("bybit-collapsed-v1:seq816");
        fundOutbound.setContinuityCandidate(true);
        fundOutbound.setMatchedCounterparty("BYBIT:1:UTA");

        NormalizedTransaction utaInbound = tx("3", "bybit-uta-in", 2, NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.148", null, null));
        utaInbound.setSource(NormalizedTransactionSource.BYBIT);
        utaInbound.setWalletAddress("BYBIT:1:UTA");
        utaInbound.setNetworkId(null);
        utaInbound.setCorrelationId("bybit-collapsed-v1:seq816");
        utaInbound.setContinuityCandidate(true);
        utaInbound.setMatchedCounterparty("BYBIT:1:FUND");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(fundBuy, fundOutbound, utaInbound));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // Collapsed UTA↔FUND legs both resolve to the umbrella root position BYBIT:1
        // (AccountingAssetIdentitySupport strips :FUND/:UTA for the drain side). The FUND CARRY_OUT
        // disposes 0.148 ETH to the corr-family queue; the UTA CARRY_IN must inherit that carry, so
        // the umbrella returns to 0.148 ETH @ avco $1000 — NOT diluted toward $0 by a spot fallback.
        AssetLedgerPoint umbrellaFinal = points.stream()
                .filter(point -> "BYBIT:1".equals(point.getWalletAddress()))
                .filter(point -> "ETH".equals(point.getAssetSymbol()))
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        // Quantity conservation: carried-in qty == carried-out qty (no inflation, no double credit).
        assertThat(umbrellaFinal.getQuantityAfter()).isEqualByComparingTo("0.148");
        // Basis continuity: CARRY_IN inherited the FUND CARRY_OUT basis, not spot/$0.
        assertThat(umbrellaFinal.getTotalCostBasisAfterUsd()).isEqualByComparingTo("148");
        assertThat(umbrellaFinal.getAvcoAfterUsd()).isEqualByComparingTo("1000");
        assertThat(umbrellaFinal.getUncoveredQuantityAfter()).isZero();

        // Both a CARRY_OUT (release) and a covered CARRY_IN/REALLOCATE_IN (inherit) must exist —
        // proving the carry rode the queue rather than orphaning the released basis.
        assertThat(points.stream()
                .anyMatch(point -> "ETH".equals(point.getAssetSymbol())
                        && point.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_OUT))
                .as("FUND outbound must release a CARRY_OUT")
                .isTrue();
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
                // No contract on USDT: NetworkStablecoinContracts is unbound in unit tests.
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", null, "5", null, null),
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
        AssetLedgerPoint usdt = latestPoint(points, "wallet-a", NetworkId.BSC, "USDT", null);
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
                // No contract on USDT/DAI: NetworkStablecoinContracts is unbound in unit tests
                // (returns empty set), so the symbol-based stablecoin fallback must be used.
                flowWithContract(NormalizedLegRole.TRANSFER, "USDT", null, "50", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "DAI", null, "50", null, null));
        lpExit.setWalletAddress("wallet-a");
        lpExit.setNetworkId(NetworkId.ARBITRUM);
        lpExit.setProtocolName("PancakeSwap");
        lpExit.setCorrelationId("lp-position:arbitrum:pancakeswap:synthetic-2");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wethBuy, uncoveredUsdc, lpEntry, lpExit));

        service().replayConfirmed();

        AssetLedgerPoint usdt = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "USDT", null);
        AssetLedgerPoint dai = latestPoint(capturedLedgerPoints(), "wallet-a", NetworkId.ARBITRUM, "DAI", null);

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
    void gmxGlvEntrySettlementMintsReceiptCarryingContributedNetBasisNotTax() {
        // M2 (ADR-040 net-carry conservation): a GMX GLV mint must mint the GLV receipt carrying the
        // SUM of the contributed assets' Net (reward-discounted) basis — NOT re-seed net = tax.
        //
        // Regression anchor (evidence only, never a runtime key): correlation
        // gmx-lp:arbitrum:glv-weth-usdc, where reward-discounted ETH was contributed at net ≪ tax
        // yet the GLV receipt was minted at net = tax = $47.23, fabricating +$21.51 of net basis.
        //
        // Synthetic reproduction: a reward-discounted principal token (ZRO: avco $2 / netAvco $1) is
        // contributed alongside a native-ETH execution-fee reserve (net = tax), then a GLV receipt is
        // minted. Pre-fix the receipt netTotalCostBasis == tax ($22); post-fix it equals Σ contributed
        // net ($12), preserving the reward discount with net ≤ tax and zero net fabrication.

        // 10 ZRO bought at $2 (tax = net = $20).
        NormalizedTransaction zroBuy = tx("1", "0xzro-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "ZRO", "0xzro", "10", "2", PriceSource.BINANCE));
        zroBuy.setWalletAddress("wallet-a");
        zroBuy.setNetworkId(NetworkId.ARBITRUM);

        // 10 ZRO claimed as a reward at $2 FMV (tax $20, net $0). Blended position: 20 ZRO,
        // tax $40 (avco $2), net $20 (netAvco $1) — a genuine reward discount.
        NormalizedTransaction zroReward = tx("2", "0xzro-reward", 1, NormalizedTransactionType.REWARD_CLAIM,
                flowWithContract(NormalizedLegRole.BUY, "ZRO", "0xzro", "10", "2", PriceSource.BINANCE));
        zroReward.setWalletAddress("wallet-a");
        zroReward.setNetworkId(NetworkId.ARBITRUM);

        // Native ETH for the execution-fee reserve (tax = net = $2; no reward discount).
        NormalizedTransaction ethBuy = tx("3", "0xeth-buy", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.001", "2000", PriceSource.BINANCE));
        ethBuy.setWalletAddress("wallet-a");
        ethBuy.setNetworkId(NetworkId.ARBITRUM);

        // GLV deposit request: contribute 10 ZRO principal (reward-discounted) + 0.001 native ETH as
        // the execution-fee reserve, correlated to the async GLV lifecycle bucket.
        NormalizedTransaction request = tx("4", "0xglv-request", 3, NormalizedTransactionType.LP_ENTRY_REQUEST,
                flowWithContract(NormalizedLegRole.TRANSFER, "ZRO", "0xzro", "-10", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.001", null, null));
        request.setWalletAddress("wallet-a");
        request.setNetworkId(NetworkId.ARBITRUM);
        request.setProtocolName("GMX");
        request.setCorrelationId("gmx-lp:arbitrum:glv-weth-usdc");

        // GLV deposit settlement: keeper mints the GLV receipt token to the wallet.
        NormalizedTransaction settlement = tx("5", "0xglv-settlement", 4, NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                flowWithContract(
                        NormalizedLegRole.TRANSFER,
                        "GLV [WETH-USDC]",
                        "0xglv",
                        "40",
                        null,
                        null
                ));
        settlement.setWalletAddress("wallet-a");
        settlement.setNetworkId(NetworkId.ARBITRUM);
        settlement.setProtocolName("GMX");
        settlement.setCorrelationId("gmx-lp:arbitrum:glv-weth-usdc");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(zroBuy, zroReward, ethBuy, request, settlement));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint glv = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "GLV [WETH-USDC]", "0xglv");

        // Tax lane conserved: Σ contributed tax = ZRO $20 + ETH-reserve $2 = $22.
        assertThat(glv.getQuantityAfter()).isEqualByComparingTo("40");
        assertThat(glv.getTotalCostBasisAfterUsd()).isEqualByComparingTo("22");
        assertThat(glv.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        // Net lane conserved (the fix): Σ contributed net = ZRO $10 + ETH-reserve $2 = $12,
        // NOT re-seeded to the $22 tax basis. This eliminates the +$10 net fabrication.
        assertThat(glv.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("12");
        // ADR-040 global gate: net ≤ tax on the minted receipt.
        assertThat(glv.getNetTotalCostBasisAfterUsd())
                .isLessThanOrEqualTo(glv.getTotalCostBasisAfterUsd());
    }

    @Test
    void asyncLpExitSettlementCarriesReceiptNetBasisBackToCrossAssetReturns() {
        // M2 follow-up (ADR-040 net-carry conservation, EXIT side): an async GLV/GM redeem that
        // returns CROSS-asset legs (GLV -> ETH + USDC) must carry the receipt's Σ net
        // (reward-discounted) basis back onto the returned assets, split by the same value weights as
        // the tax lane — NEVER re-seed net = tax. This is the exit-side peer of the GLV/GM mint fix.
        //
        // Pre-fix: the returned ETH+USDC were restored via the tax-only path (net = tax), fabricating
        // net basis on redeem (mirror of the mint-side +$21.51). Post-fix: Σ returned net == receipt
        // net (conserved), each leg has net ≤ tax, and the USD-stablecoin (USDC) leg is NOT re-inflated
        // back to peg on the net lane.

        // --- Build a reward-discounted GLV receipt end-to-end (same shape as the mint test). ---
        NormalizedTransaction zroBuy = tx("1", "0xzro-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "ZRO", "0xzro", "10", "2", PriceSource.BINANCE));
        zroBuy.setWalletAddress("wallet-a");
        zroBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction zroReward = tx("2", "0xzro-reward", 1, NormalizedTransactionType.REWARD_CLAIM,
                flowWithContract(NormalizedLegRole.BUY, "ZRO", "0xzro", "10", "2", PriceSource.BINANCE));
        zroReward.setWalletAddress("wallet-a");
        zroReward.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction ethBuy = tx("3", "0xeth-buy", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.001", "2000", PriceSource.BINANCE));
        ethBuy.setWalletAddress("wallet-a");
        ethBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction entryRequest = tx("4", "0xglv-request", 3, NormalizedTransactionType.LP_ENTRY_REQUEST,
                flowWithContract(NormalizedLegRole.TRANSFER, "ZRO", "0xzro", "-10", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.001", null, null));
        entryRequest.setWalletAddress("wallet-a");
        entryRequest.setNetworkId(NetworkId.ARBITRUM);
        entryRequest.setProtocolName("GMX");
        entryRequest.setCorrelationId("gmx-lp:arbitrum:glv-weth-usdc");

        NormalizedTransaction entrySettlement = tx("5", "0xglv-settlement", 4, NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                flowWithContract(NormalizedLegRole.TRANSFER, "GLV [WETH-USDC]", "0xglv", "40", null, null));
        entrySettlement.setWalletAddress("wallet-a");
        entrySettlement.setNetworkId(NetworkId.ARBITRUM);
        entrySettlement.setProtocolName("GMX");
        entrySettlement.setCorrelationId("gmx-lp:arbitrum:glv-weth-usdc");
        // Receipt now carries tax $22 / net $12 (reward discount preserved by the mint fix).

        // --- Redeem: send the GLV receipt, receive ETH + USDC (cross-asset). ---
        NormalizedTransaction exitRequest = tx("6", "0xglv-exit-request", 5, NormalizedTransactionType.LP_EXIT_REQUEST,
                flowWithContract(NormalizedLegRole.TRANSFER, "GLV [WETH-USDC]", "0xglv", "-40", null, null));
        exitRequest.setWalletAddress("wallet-a");
        exitRequest.setNetworkId(NetworkId.ARBITRUM);
        exitRequest.setProtocolName("GMX");
        exitRequest.setCorrelationId("gmx-lp:arbitrum:glv-weth-usdc-exit");

        // Keeper returns 0.0055 ETH (value $11) + 11 USDC (value $11). Cross-asset known-value split
        // is 50/50: tax $11 each; net $6 each (Σ net = $12 = receipt net).
        NormalizedTransaction exitSettlement = tx("7", "0xglv-exit-settlement", 6, NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.0055", "2000", PriceSource.BINANCE),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "11", "1", PriceSource.BINANCE));
        exitSettlement.setWalletAddress("wallet-a");
        exitSettlement.setNetworkId(NetworkId.ARBITRUM);
        exitSettlement.setProtocolName("GMX");
        exitSettlement.setCorrelationId("gmx-lp:arbitrum:glv-weth-usdc-exit");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(zroBuy, zroReward, ethBuy, entryRequest, entrySettlement, exitRequest, exitSettlement));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint eth = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        AssetLedgerPoint usdc = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "USDC", "0xusdc");

        // Tax lane conserved on redeem: $22 receipt tax split 50/50 by value.
        assertThat(eth.getQuantityAfter()).isEqualByComparingTo("0.0055");
        assertThat(eth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("11");
        assertThat(eth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        assertThat(usdc.getQuantityAfter()).isEqualByComparingTo("11");
        assertThat(usdc.getTotalCostBasisAfterUsd()).isEqualByComparingTo("11");
        assertThat(usdc.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        // Net lane carried back (the fix): each leg gets its net share ($6), NOT re-seeded to tax ($11).
        assertThat(eth.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("6");
        assertThat(usdc.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("6");

        // ADR-040 global gate: net ≤ tax per returned leg.
        assertThat(eth.getNetTotalCostBasisAfterUsd()).isLessThanOrEqualTo(eth.getTotalCostBasisAfterUsd());
        assertThat(usdc.getNetTotalCostBasisAfterUsd()).isLessThanOrEqualTo(usdc.getTotalCostBasisAfterUsd());

        // USD-stablecoin net NOT floored back to peg (D3 discipline): $6 net on 11 USDC is $0.545/unit,
        // materially below $1 — a peg-floored net lane would have re-fabricated it up to $11.
        assertThat(usdc.getNetTotalCostBasisAfterUsd()).isLessThan(usdc.getTotalCostBasisAfterUsd());

        // Conservation: Σ returned net == receipt net ($12), no fabrication / destruction on redeem.
        assertThat(eth.getNetTotalCostBasisAfterUsd().add(usdc.getNetTotalCostBasisAfterUsd()))
                .isEqualByComparingTo("12");
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
    void crossNetworkBridgeInIsNotDedupedAgainstOutboundDustBuyOnSourceNetwork() {
        NormalizedTransaction priorBuy = tx("0", "0xprior", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.081481", "165", PriceSource.BINANCE));
        priorBuy.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        priorBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction bridgeOut = tx(
                "1",
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7",
                1,
                NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.BUY, "ETH", "0.000000288551112619", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.080966355549794125", null, null)
        );
        bridgeOut.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        bridgeOut.setNetworkId(NetworkId.ARBITRUM);
        bridgeOut.setCorrelationId("bridge:lifi:0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa");
        bridgeOut.setBlockTimestamp(Instant.parse("2026-06-05T08:32:11Z"));

        NormalizedTransaction bridgeIn = tx(
                "2",
                "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa",
                2,
                NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.080966355549794125", null, null)
        );
        bridgeIn.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        bridgeIn.setNetworkId(NetworkId.BASE);
        bridgeIn.setCorrelationId("bridge:lifi:0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7");
        bridgeIn.setBlockTimestamp(Instant.parse("2026-06-05T08:37:35Z"));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(priorBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> destinationPoints = capturedLedgerPoints().stream()
                .filter(point -> "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa".equals(point.getTxHash()))
                .toList();
        assertThat(destinationPoints).isNotEmpty();
        AssetLedgerPoint carryIn = destinationPoints.stream()
                .filter(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_IN)
                .findFirst()
                .orElseThrow();
        assertThat(carryIn.getQuantityDelta()).isEqualByComparingTo("0.080966355549794125");
        assertThat(carryIn.getNetworkId()).isEqualTo(NetworkId.BASE);
    }

    /**
     * Regression: BRIDGE_OUT with an incidental dust BUY flow (e.g. LiFi refund dust) must NOT
     * register the BUY as a pending bridge inbound.  Before the fix, the dust BUY stole the
     * carry-queue slot and triggered {@code attachLateBridgeCarryToPendingInbound} on the source
     * ARBITRUM position, injecting ~$162 phantom basis with zero qty change (AVCO→$318 k).
     * The destination BASE CARRY_IN would then fall back to provisional market price instead of
     * propagating the authoritative ARBITRUM basis.
     *
     * <p>After the fix ({@code shouldTreatAsContinuityTransfer} returns false for BUY/SELL on
     * BRIDGE_OUT/BRIDGE_IN), the dust BUY is processed as a plain ACQUIRE and the TRANSFER flow's
     * carry queue is exclusively consumed by the BASE BRIDGE_IN.
     */
    @Test
    void bridgeOutDustBuyDoesNotCreatePhantomCarryInOnSourceNetwork() {
        NormalizedTransaction priorBuy = tx("0", "0xprior", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.081481", "2010", PriceSource.BINANCE));
        priorBuy.setWalletAddress("0xwallet");
        priorBuy.setNetworkId(NetworkId.ARBITRUM);

        // BRIDGE_OUT has a tiny dust BUY (flow[0]) alongside the main TRANSFER (flow[1]).
        NormalizedTransaction bridgeOut = tx(
                "1",
                "0xbridge-out",
                1,
                NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.BUY, "ETH", "0.000000288", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.080966", null, null)
        );
        bridgeOut.setWalletAddress("0xwallet");
        bridgeOut.setNetworkId(NetworkId.ARBITRUM);
        bridgeOut.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx(
                "2",
                "0xbridge-in",
                2,
                NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.080966", null, null)
        );
        bridgeIn.setWalletAddress("0xwallet");
        bridgeIn.setNetworkId(NetworkId.BASE);
        bridgeIn.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(priorBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> all = capturedLedgerPoints();

        // 1. No phantom CARRY_IN with qty=0 on ARBITRUM from the bridge OUT transaction
        List<AssetLedgerPoint> bridgeOutPoints = all.stream()
                .filter(p -> "0xbridge-out".equals(p.getTxHash()))
                .toList();
        bridgeOutPoints.forEach(p ->
                assertThat(p.getQuantityDelta())
                        .as("BRIDGE_OUT must not emit a qty=0 CARRY_IN (phantom basis injection) on ARBITRUM")
                        .isNotEqualByComparingTo("0")
        );

        // 2. ARBITRUM AVCO after bridge must not spike: should stay near $2010, not $318 k
        AssetLedgerPoint arbAfterBridge = bridgeOutPoints.stream()
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_OUT
                        && NetworkId.ARBITRUM.equals(p.getNetworkId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected CARRY_OUT on ARBITRUM"));
        assertThat(arbAfterBridge.getAvcoAfterUsd())
                .as("ARBITRUM AVCO after CARRY_OUT must not be inflated by phantom basis")
                .isLessThan(new java.math.BigDecimal("5000"));

        // 3. BASE BRIDGE_IN receives the authoritative carry (not provisional market price)
        AssetLedgerPoint baseCarryIn = all.stream()
                .filter(p -> "0xbridge-in".equals(p.getTxHash())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_IN
                        && NetworkId.BASE.equals(p.getNetworkId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected CARRY_IN on BASE"));
        assertThat(baseCarryIn.getQuantityDelta()).isEqualByComparingTo("0.080966");
        // The carried basis comes from ARBITRUM AVCO ($2010) × qty, not provisional market price
        assertThat(baseCarryIn.getTotalCostBasisAfterUsd())
                .as("BASE cost basis after CARRY_IN must reflect the carried ARBITRUM basis")
                .isGreaterThan(new java.math.BigDecimal("100"));
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
    void assetConvertingBridgeRealizesPnlOnNonPegSourceAndAcquiresDestinationAtMarket() {
        // B-ETH-01: WBTC (non-peg) → ETH cross-family bridge. Source WBTC is DISPOSED against its
        // own AVCO with proceeds = destination fair market value ($42,000). Destination ETH is
        // ACQUIRED at that carried USD (tax lane), with the net lane inheriting the source net
        // capped at market.
        NormalizedTransaction wbtcBuy = tx("1", "0xwbtc-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "WBTC", "0xwbtc", "1", "40000", PriceSource.BINANCE));
        wbtcBuy.setWalletAddress("wallet-a");
        wbtcBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "WBTC", "0xwbtc", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.ARBITRUM);
        bridgeOut.setCorrelationId("bridge:lifi:wbtc-to-eth");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeOut, true, new BigDecimal("42000"));

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "14", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:wbtc-to-eth");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeIn, true, new BigDecimal("42000"));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wbtcBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "WBTC", "0xwbtc");
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.KATANA, "ETH", null);

        // Source WBTC fully disposed; realized P&L = proceeds ($42,000) − source AVCO ($40,000) = $2,000.
        assertThat(source.getQuantityAfter()).isZero();
        assertThat(source.getTotalCostBasisAfterUsd()).isZero();
        assertThat(source.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(source.getRealisedPnlDeltaUsd()).isEqualByComparingTo("2000");
        // Net lane carried to destination capped at market ($40,000 ≤ $42,000) → no net P&L realized.
        assertThat(source.getNetRealisedPnlDeltaUsd()).isEqualByComparingTo("0");

        // Destination ETH acquired at the carried destination market value; net inherits source net.
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("14");
        assertThat(destination.getBasisBackedQuantityAfter()).isEqualByComparingTo("14");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("42000");
        assertThat(destination.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("40000");
        assertThat(destination.getAvcoAfterUsd()).isEqualByComparingTo("3000");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
    }

    @Test
    void reverseAssetConvertingBridgeRealizesPnlOnEthSourceAndAcquiresStableAtMarket() {
        // B-ETH-01 reverse: ETH (non-peg) → USDC cross-family bridge. Source ETH DISPOSED at
        // destination FMV ($3,300); destination USDC acquired at that carried USD.
        NormalizedTransaction ethBuy = tx("1", "0xeth-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "3000", PriceSource.BINANCE));
        ethBuy.setWalletAddress("wallet-a");
        ethBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.ARBITRUM);
        bridgeOut.setCorrelationId("bridge:lifi:eth-to-usdc");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeOut, true, new BigDecimal("3300"));

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "3300", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:eth-to-usdc");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeIn, true, new BigDecimal("3300"));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(ethBuy, bridgeOut, bridgeIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "ETH", null);
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.KATANA, "USDC", "0xusdc");

        assertThat(source.getQuantityAfter()).isZero();
        assertThat(source.getTotalCostBasisAfterUsd()).isZero();
        assertThat(source.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(source.getRealisedPnlDeltaUsd()).isEqualByComparingTo("300");
        assertThat(source.getNetRealisedPnlDeltaUsd()).isEqualByComparingTo("0");

        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("3300");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("3300");
        assertThat(destination.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("3000");
        assertThat(destination.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
    }

    @Test
    void inboundFirstAssetConvertingBridgeRealizesWithoutDuplicatingDestinationQuantity() {
        // B-ETH-01 inbound-first ordering: the ETH BRIDGE_IN materializes provisionally before the
        // WBTC BRIDGE_OUT attaches its realize carry. Quantity must not double; the destination
        // basis must settle to the carried destination FMV.
        NormalizedTransaction wbtcBuy = tx("1", "0xwbtc-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "WBTC", "0xwbtc", "1", "40000", PriceSource.BINANCE));
        wbtcBuy.setWalletAddress("wallet-a");
        wbtcBuy.setNetworkId(NetworkId.ARBITRUM);

        NormalizedTransaction bridgeIn = tx("2", "0xbridge-in", 1, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "14", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:wbtc-to-eth-late");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeIn, true, new BigDecimal("42000"));

        NormalizedTransaction bridgeOut = tx("3", "0xbridge-out", 2, NormalizedTransactionType.BRIDGE_OUT,
                flowWithContract(NormalizedLegRole.TRANSFER, "WBTC", "0xwbtc", "-1", null, null));
        bridgeOut.setWalletAddress("wallet-a");
        bridgeOut.setNetworkId(NetworkId.ARBITRUM);
        bridgeOut.setCorrelationId("bridge:lifi:wbtc-to-eth-late");
        bridgeOut.setContinuityCandidate(false);
        bridgeOut.setMatchedCounterparty("0xbridge-in");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeOut, true, new BigDecimal("42000"));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(wbtcBuy, bridgeIn, bridgeOut));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint source = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "WBTC", "0xwbtc");
        AssetLedgerPoint destination = latestPoint(points, "wallet-a", NetworkId.KATANA, "ETH", null);

        assertThat(source.getQuantityAfter()).isZero();
        assertThat(source.getRealisedPnlDeltaUsd()).isEqualByComparingTo("2000");

        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("14");
        assertThat(destination.getUncoveredQuantityAfter()).isZero();
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("42000");
    }

    @Test
    void pegNeutralCrossAssetBridgeStaysByteIdenticalEvenWhenStampedAssetConverting() {
        // Byte-identical regression: a peg-neutral source (USDC→ETH) stamped ASSET_CONVERTING with
        // realizeOnConvert=false must keep the existing REALLOCATE settlement behavior — the source
        // USD basis moves into the destination acquisition, no P&L realized. Mirrors the numbers of
        // linkedAssetChangingBridgeSettlementMovesSourceCostIntoDestinationAcquisition exactly.
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
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeOut, false, null);

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.452894410848733888", null, null));
        bridgeIn.setWalletAddress("wallet-a");
        bridgeIn.setNetworkId(NetworkId.KATANA);
        bridgeIn.setCorrelationId("bridge:lifi:stable-to-eth");
        bridgeIn.setContinuityCandidate(false);
        bridgeIn.setMatchedCounterparty("0xbridge-out");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(bridgeIn, false, null);

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
        // BLOCKER-4: BYBIT-CORRIDOR inbound to :FUND now collapses onto the UID umbrella so the
        // deposit is reachable by umbrella-level spot activity (P-B inbound reversed).
        AssetLedgerPoint destination = latestPoint(points, "BYBIT:1", null, "ETH", null);
        // Without dedup, the mirror would push quantity to 2.0. With dedup, it stays at 1.0.
        assertThat(destination.getQuantityAfter()).isEqualByComparingTo("1.0");
        assertThat(destination.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
    }

    @Test
    void corridorCarryOutUsesProportionalBasisNotPerWalletAvcoWhenPositionHasUncoveredQty() {
        // B-3: When a position has uncoveredQuantity > 0, perWalletAvco = totalBasis / coveredQty
        // inflates the carry basis. The fix computes proportional basis = totalBasis × (movedQty / totalQty).
        //
        // Setup: wallet-a has 0.65 ETH covered ($65 basis) + 0.35 ETH uncovered = 1.0 ETH total.
        //   perWalletAvco = $65 / 0.65 = $100
        //   Old (wrong): carry basis = 1.0 × $100 = $100
        //   New (correct): carry basis = $65 × (1.0 / 1.0) = $65
        NormalizedTransaction coveredBuy = tx("1", "0xcov-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.65", "100", PriceSource.BINANCE));
        coveredBuy.setWalletAddress("wallet-a");
        coveredBuy.setNetworkId(NetworkId.MANTLE);

        NormalizedTransaction uncoveredBuy = tx("2", "0xuncov-buy", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.35", null, null));
        uncoveredBuy.setWalletAddress("wallet-a");
        uncoveredBuy.setNetworkId(NetworkId.MANTLE);

        NormalizedTransaction carryOut = tx("3", "0xcorridor", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1.0", null, null));
        carryOut.setWalletAddress("wallet-a");
        carryOut.setNetworkId(NetworkId.MANTLE);
        carryOut.setCorrelationId("BYBIT-CORRIDOR:MANTLE:0xcorridor");
        carryOut.setContinuityCandidate(true);
        carryOut.setMatchedCounterparty("BYBIT:1:FUND");

        NormalizedTransaction carryIn = tx("4", "0xcorridor", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.0", null, null));
        carryIn.setSource(NormalizedTransactionSource.BYBIT);
        carryIn.setWalletAddress("BYBIT:1:FUND");
        carryIn.setNetworkId(NetworkId.MANTLE);
        carryIn.setCorrelationId("BYBIT-CORRIDOR:MANTLE:0xcorridor");
        carryIn.setContinuityCandidate(true);
        carryIn.setMatchedCounterparty("wallet-a");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, uncoveredBuy, carryOut, carryIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // B-3 fix: carry basis = totalBasis × (movedQty / totalQty) = $65 × 1.0 = $65.
        // Without fix: perWalletAvco × movedQty = ($65/0.65) × 1.0 = $100 (inflated by $35).
        // BLOCKER-4: the corridor CARRY_IN destination now collapses onto the UID umbrella.
        AssetLedgerPoint bybitFund = latestPoint(points, "BYBIT:1", null, "ETH", null);
        assertThat(bybitFund.getTotalCostBasisAfterUsd()).isEqualByComparingTo("65");
        assertThat(bybitFund.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
    }

    @Test
    void bybitExecutionSpotCmethSellDisposesFromUmbrellaInventory() {
        // RC-1: a Bybit spot CMETH acquisition consolidates on the UID umbrella (FUND/UTA collapse),
        // clearing the ETH-family :FUND phantom, so the spot sell disposes from the umbrella lot.
        NormalizedTransaction fundAcquire = tx("1", "0xcmeth-acquire", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "CMETH", "0.14379048", "2873", PriceSource.BINANCE));
        fundAcquire.setSource(NormalizedTransactionSource.BYBIT);
        fundAcquire.setWalletAddress("BYBIT:33625378:FUND");
        fundAcquire.getFlows().getFirst().setAccountRef("BYBIT:33625378:FUND");

        NormalizedTransaction sell = tx(
                "BYBIT-33625378:EXECUTION_SPOT:2200000000707964104:CMETHUSDT",
                "2200000000707964104",
                1,
                NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.SELL, "CMETH", "-0.0038", "2873", PriceSource.BINANCE),
                flow(NormalizedLegRole.BUY, "USDT", "10.9174", "1", PriceSource.BINANCE)
        );
        sell.setSource(NormalizedTransactionSource.BYBIT);
        sell.setWalletAddress("BYBIT:33625378:UTA");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(fundAcquire, sell));

        service().replayConfirmed();

        AssetLedgerPoint dispose = capturedLedgerPoints().stream()
                .filter(point -> "CMETH".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.DISPOSE)
                .findFirst()
                .orElseThrow();
        assertThat(dispose.getWalletAddress()).isEqualTo("BYBIT:33625378");
        assertThat(dispose.getQuantityDelta()).isEqualByComparingTo("-0.0038");
        assertThat(dispose.getCostBasisDeltaUsd())
                .isLessThan(BigDecimal.ZERO)
                .isCloseTo(new BigDecimal("-10.9174"), org.assertj.core.data.Offset.offset(new BigDecimal("0.15")));
    }

    @Test
    void corridorDepositLotAndUmbrellaLotConvertAcrossUnifiedPoolWithZeroShortfall() {
        // BLOCKER-4 anchor: the 2025-04-17 CMETH convert oversold by 0.09992031 because a 0.1 CMETH
        // corridor DEPOSIT was stranded on :FUND while the umbrella held only 0.56939617. After the
        // fix the inbound corridor deposit collapses onto the UID umbrella, so the unified pool
        // (0.56939617 + 0.1 = 0.66939617) fully covers the 0.66931648 SWAP sell with zero shortfall.
        NormalizedTransaction umbrellaBuy = tx("1", "0xcmeth-umbrella", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "CMETH", "0.56939617", "2873", PriceSource.BINANCE));
        umbrellaBuy.setSource(NormalizedTransactionSource.BYBIT);
        umbrellaBuy.setWalletAddress("BYBIT:33625378");
        umbrellaBuy.getFlows().getFirst().setAccountRef("BYBIT:33625378");

        // Corridor deposit: an on-chain wallet sends 0.1 CMETH into Bybit :FUND (a covered lot).
        NormalizedTransaction onChainBuy = tx("2", "0xcmeth-onchain-buy", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "CMETH", "0.1", "2873", PriceSource.BINANCE));
        onChainBuy.setWalletAddress("user-cmeth-wallet");
        onChainBuy.setNetworkId(NetworkId.MANTLE);

        NormalizedTransaction corridorOnChainOut = tx("3", "0xcmeth-corridor", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "CMETH", "-0.1", null, null));
        corridorOnChainOut.setWalletAddress("user-cmeth-wallet");
        corridorOnChainOut.setNetworkId(NetworkId.MANTLE);
        corridorOnChainOut.setCorrelationId("BYBIT-CORRIDOR:MANTLE:0xcmeth-corridor");
        corridorOnChainOut.setContinuityCandidate(true);
        corridorOnChainOut.setMatchedCounterparty("BYBIT:33625378:FUND");

        NormalizedTransaction corridorFundIn = tx("4", "0xcmeth-corridor", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "CMETH", "0.1", null, null));
        corridorFundIn.setSource(NormalizedTransactionSource.BYBIT);
        corridorFundIn.setWalletAddress("BYBIT:33625378:FUND");
        corridorFundIn.setCorrelationId("BYBIT-CORRIDOR:MANTLE:0xcmeth-corridor");
        corridorFundIn.setContinuityCandidate(true);
        corridorFundIn.setMatchedCounterparty("user-cmeth-wallet");

        // The convert (EXECUTION_SPOT SWAP) on :UTA sells across the unified umbrella pool.
        NormalizedTransaction sell = tx(
                "BYBIT-33625378:EXECUTION_SPOT:2200000000707964104:CMETHUSDT",
                "2200000000707964104",
                4,
                NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.SELL, "CMETH", "-0.66931648", "2873", PriceSource.BINANCE),
                flow(NormalizedLegRole.BUY, "USDT", "1922.94", "1", PriceSource.BINANCE)
        );
        sell.setSource(NormalizedTransactionSource.BYBIT);
        sell.setWalletAddress("BYBIT:33625378:UTA");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(umbrellaBuy, onChainBuy, corridorOnChainOut, corridorFundIn, sell));

        service().replayConfirmed();

        AssetLedgerPoint dispose = capturedLedgerPoints().stream()
                .filter(point -> "CMETH".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.DISPOSE)
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(dispose.getWalletAddress()).isEqualTo("BYBIT:33625378");
        assertThat(dispose.getQuantityDelta()).isEqualByComparingTo("-0.66931648");
        // The whole point of the fix: the disposal no longer oversells the umbrella inventory.
        assertThat(dispose.getQuantityShortfallAfter())
                .satisfiesAnyOf(
                        v -> assertThat(v).isNull(),
                        v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(dispose.getQuantityAfter()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void bybitEarnPrincipalRedeemRestoresCoveredBasisOnFundInbound() {
        // RC-1: the redeem FUND-side credit (reported on :FUND) consolidates onto the UID umbrella,
        // where the Flexible-Savings principal actually lives, restoring covered basis there.
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
                .filter(point -> "BYBIT:33625378".equals(point.getWalletAddress()))
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
    void c1ToC2StakingDepositCarriesBasisWithZeroRealizedPnl() {
        // ADR-083: ETH → mETH is an intra-cluster (CLUSTER:ETH_STAKING) conversion — it carries basis
        // (REALLOCATE_OUT/IN, PnL=0), superseding the former realize-at-market behavior. mETH inherits
        // the disposed ETH basis ($1000), NOT a fresh market basis.
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "1000", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");

        NormalizedTransaction stake = tx(
                "2",
                "0xstake",
                1,
                NormalizedTransactionType.STAKING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1", "3000", PriceSource.BINANCE),
                flow(NormalizedLegRole.TRANSFER, "METH", "0.95", "3100", PriceSource.BINANCE)
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
        assertThat(eth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(meth.getQuantityAfter()).isEqualByComparingTo("0.95");
        assertThat(meth.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1000");
        assertThat(meth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        ArgumentCaptor<List<NormalizedTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(txCaptor.capture());
        NormalizedTransaction replayedStake = txCaptor.getValue().stream()
                .filter(tx -> "2".equals(tx.getId()))
                .findFirst()
                .orElseThrow();
        // PnL=0 → carried legs carry no realized PnL.
        assertThat(replayedStake.getFlows().getFirst().getRealisedPnlUsd()).isNull();
    }

    @Test
    void avaxToSavaxStakingDepositCarriesSourceBasis() {
        // ADR-083: AVAX → sAVAX is intra-cluster (CLUSTER:AVAX_STAKING) — sAVAX inherits the disposed
        // AVAX basis ($5 = 0.5 @ $10), NOT a fresh market basis.
        NormalizedTransaction coveredBuy = tx("1", "0xavax-covered", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "AVAX", "0.5", "10", PriceSource.BINANCE));
        coveredBuy.setWalletAddress("wallet-a");
        coveredBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction stake = tx(
                "3",
                "0xavax-stake",
                2,
                NormalizedTransactionType.STAKING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "AVAX", "-0.5", "12", PriceSource.BINANCE),
                flow(NormalizedLegRole.TRANSFER, "sAVAX", "0.4", "15", PriceSource.BINANCE)
        );
        stake.setWalletAddress("wallet-a");
        stake.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(coveredBuy, stake));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint derivative = latestPoint(points, "wallet-a", NetworkId.AVALANCHE, "sAVAX", null);
        assertThat(derivative.getQuantityAfter()).isEqualByComparingTo("0.4");
        assertThat(derivative.getTotalCostBasisAfterUsd()).isEqualByComparingTo("5");
        assertThat(derivative.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void c2ToC1VaultWithdrawCarriesBasisWithZeroRealizedPnl() {
        // ADR-083: yvVBETH (FAMILY:YVVBETH) → vBETH (FAMILY:ETH) are both in CLUSTER:ETH_STAKING, so
        // the vault withdraw carries basis (REALLOCATE_OUT/IN, PnL=0) instead of realizing.
        NormalizedTransaction buy = tx("1", "0xbuy-yvvbeth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "YVVBETH", "0.435428171459772105", "2809.177530660769159772942625393432", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.KATANA);

        NormalizedTransaction unwrap = tx(
                "2",
                "0xvault-withdraw",
                1,
                NormalizedTransactionType.VAULT_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "YVVBETH", "-0.435428171459772105", "2800", PriceSource.BINANCE),
                flow(NormalizedLegRole.TRANSFER, "VBETH", "0.438269104813635143", "2790", PriceSource.BINANCE)
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
        assertThat(yvvbeth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(vbeth.getQuantityAfter()).isEqualByComparingTo("0.438269104813635143");
        assertThat(vbeth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        // Basis conservation: the full disposed yvVBETH basis carries onto vBETH (PnL=0).
        assertThat(vbeth.getTotalCostBasisAfterUsd())
                .isCloseTo(yvvbeth.getTotalCostBasisBeforeUsd(), within(new BigDecimal("0.0001")));
    }

    @Test
    void c2ToC1VaultWithdrawCarriesBasisIgnoringStaleAndLiveInboundPrices() {
        // ADR-083 supersedes the ADR-054 realize-from-inbound-FMV addendum for intra-cluster
        // conversions: yvVBETH → vBETH now CARRIES basis regardless of the outbound stale price or the
        // inbound live FMV. Realized PnL = 0; the disposed yvVBETH basis carries onto vBETH.
        NormalizedTransaction buy = tx("1", "0xbuy-yvvbeth-fmv", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "YVVBETH", "0.435428171459772105",
                        "2808.74153341957057890670143788192629663021964911744193352366", PriceSource.BINANCE));
        buy.setWalletAddress("wallet-a");
        buy.setNetworkId(NetworkId.KATANA);

        NormalizedTransaction unwrap = tx(
                "2",
                "0xvault-withdraw-fmv",
                1,
                NormalizedTransactionType.VAULT_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "YVVBETH", "-0.435428171459772105", "2798", PriceSource.BINANCE),
                flow(NormalizedLegRole.TRANSFER, "VBETH", "0.438269104813635143",
                        "2449.07000792679781024096266236578583862108865626575312583188", PriceSource.BINANCE)
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

        BigDecimal tolerance = new BigDecimal("0.0001");

        // Carried, not realized: PnL = 0 both lanes.
        assertThat(yvvbeth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(yvvbeth.getQuantityAfter()).isZero();
        assertThat(yvvbeth.getRealisedPnlDeltaUsd()).isCloseTo(BigDecimal.ZERO, within(tolerance));
        assertThat(yvvbeth.getNetRealisedPnlDeltaUsd()).isCloseTo(BigDecimal.ZERO, within(tolerance));

        // vBETH inherits the disposed yvVBETH basis (conservation), independent of inbound FMV.
        assertThat(vbeth.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        assertThat(vbeth.getQuantityAfter()).isEqualByComparingTo("0.438269104813635143");
        assertThat(vbeth.getTotalCostBasisAfterUsd())
                .isCloseTo(yvvbeth.getTotalCostBasisBeforeUsd(), within(tolerance));
    }

    // ── B-VAULT-WITHDRAW Bug A: wrapper bucket denomination mismatch ──────────────────────────

    @Test
    void vaultWithdrawWrapperBucketRestoresFullBasis() {
        // MEV Capital VAULT_DEPOSIT: 1628 USDC deposited, 1,598,068,583 mevUSDC shares minted.
        // VAULT_WITHDRAW: 1,598,068,583 mevUSDC burned, 1628 USDC returned.
        // Bug A: proportional slice → cbD ≈ $0.001; fix → full drain → cbD = $1628.
        NormalizedTransaction usdcBuy = tx("1", "0xbuy-usdc", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "1628", "1", PriceSource.BINANCE));
        usdcBuy.setWalletAddress("wallet-v");
        usdcBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction vaultDeposit = tx("2", "0xvault-deposit", 1, NormalizedTransactionType.VAULT_DEPOSIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", null, "-1628", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "mevUSDC", "0xmevusdc", "1598068583", null, null));
        vaultDeposit.setWalletAddress("wallet-v");
        vaultDeposit.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction vaultWithdraw = tx("3", "0xvault-withdraw", 2, NormalizedTransactionType.VAULT_WITHDRAW,
                flowWithContract(NormalizedLegRole.TRANSFER, "mevUSDC", "0xmevusdc", "-1598068583", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", null, "1628", null, null));
        vaultWithdraw.setWalletAddress("wallet-v");
        vaultWithdraw.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(usdcBuy, vaultDeposit, vaultWithdraw));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint usdc = latestPoint(points, "wallet-v", NetworkId.AVALANCHE, "USDC", null);
        assertThat(usdc.getQuantityAfter()).isEqualByComparingTo("1628");
        assertThat(usdc.getTotalCostBasisAfterUsd()).isGreaterThanOrEqualTo(new BigDecimal("1600"));
        assertThat(usdc.getUncoveredQuantityAfter()).isZero();
        assertThat(usdc.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    @Test
    void vaultWithdrawPartialReturnUsesFullBucketProportionally() {
        // Partial VAULT_WITHDRAW: deposit 1628 USDC (1,598,068,583 shares), withdraw 50%
        // (799,034,291 shares → 814 USDC). The full-bucket drain restores only the
        // proportional half (~$814) because the outbound step already removed half the
        // mevUSDC position basis before populating the bucket.
        NormalizedTransaction usdcBuy = tx("1", "0xbuy-usdc-partial", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "1628", "1", PriceSource.BINANCE));
        usdcBuy.setWalletAddress("wallet-p");
        usdcBuy.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction vaultDeposit = tx("2", "0xvault-deposit-p", 1, NormalizedTransactionType.VAULT_DEPOSIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", null, "-1628", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "mevUSDC", "0xmevusdc", "1598068583", null, null));
        vaultDeposit.setWalletAddress("wallet-p");
        vaultDeposit.setNetworkId(NetworkId.AVALANCHE);

        NormalizedTransaction vaultWithdrawPartial = tx("3", "0xvault-withdraw-partial", 2, NormalizedTransactionType.VAULT_WITHDRAW,
                flowWithContract(NormalizedLegRole.TRANSFER, "mevUSDC", "0xmevusdc", "-799034291", null, null),
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", null, "814", null, null));
        vaultWithdrawPartial.setWalletAddress("wallet-p");
        vaultWithdrawPartial.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(usdcBuy, vaultDeposit, vaultWithdrawPartial));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint usdc = latestPoint(points, "wallet-p", NetworkId.AVALANCHE, "USDC", null);
        assertThat(usdc.getQuantityAfter()).isEqualByComparingTo("814");
        // Proportional half of the original $1628 deposit basis (~$814), not $0 and not $1628
        assertThat(usdc.getTotalCostBasisAfterUsd()).isBetween(new BigDecimal("700"), new BigDecimal("900"));
        assertThat(usdc.getUncoveredQuantityAfter()).isZero();
    }

    @Test
    void lpExitNonWrapperBucketUnchanged() {
        // LP_EXIT where both the burned receipt (AARBUSDC) and the returned asset (USDC) are
        // FAMILY:USDC. wrapperCompositeBucketIdentity returns null (no non-FAMILY receipt), so
        // isFamilyEquivalentCustodyTransfer handles the carry, and isBucketInbound/usesWrapperCompositeBucket
        // are never reached. Verifies the fix does not affect same-family LP_EXIT paths.
        NormalizedTransaction usdcBuy = tx("1", "0xbuy-usdc-fam", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "1000", "1", PriceSource.BINANCE));
        usdcBuy.setWalletAddress("wallet-fam");
        usdcBuy.setNetworkId(NetworkId.AVALANCHE);

        // LP_ENTRY: USDC → AARBUSDC (both FAMILY:USDC → isFamilyEquivalentCustodyTransfer fires)
        NormalizedTransaction lpEntry = tx("2", "0xlp-entry-fam", 1, NormalizedTransactionType.LP_ENTRY,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-1000", null, null),
                flow(NormalizedLegRole.TRANSFER, "AARBUSDC", "1000", null, null));
        lpEntry.setWalletAddress("wallet-fam");
        lpEntry.setNetworkId(NetworkId.AVALANCHE);

        // LP_EXIT: AARBUSDC → USDC (both FAMILY:USDC → isFamilyEquivalentCustodyTransfer fires)
        // usesWrapperCompositeBucket is never called because isFamilyEquivalentCustodyTransfer
        // fires first (same family); wrapperCompositeBucketIdentity is null anyway (FAMILY receipt)
        NormalizedTransaction lpExit = tx("3", "0xlp-exit-fam", 2, NormalizedTransactionType.LP_EXIT,
                flow(NormalizedLegRole.TRANSFER, "AARBUSDC", "-1000", null, null),
                flow(NormalizedLegRole.TRANSFER, "USDC", "1000", null, null));
        lpExit.setWalletAddress("wallet-fam");
        lpExit.setNetworkId(NetworkId.AVALANCHE);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(usdcBuy, lpEntry, lpExit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        AssetLedgerPoint usdc = latestPoint(points, "wallet-fam", NetworkId.AVALANCHE, "USDC", null);
        // Full basis should be preserved: $1000 deposited → $1000 returned
        assertThat(usdc.getQuantityAfter()).isEqualByComparingTo("1000");
        assertThat(usdc.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1000");
        assertThat(usdc.getUncoveredQuantityAfter()).isZero();
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

    // ── B-BYBIT-CORRIDOR-2: FUND zero-inventory corridor carry tests ──────────────────────────

    @Test
    void corridorFundOutboundUsesUmbrellaAvcoWhenFundIsEmpty() {
        // Sub-pattern A: FUND has zero inventory at corridor outbound time because Bybit's API
        // does not expose the UTA→FUND internal transfer. The fix makes resolveCarrySourcePosition
        // fall back to the umbrella BYBIT:1 position so the proportional-carry override fires.
        NormalizedTransaction umbrellaAcquire = tx("1", "0xumbrella-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", "1", PriceSource.BINANCE));
        umbrellaAcquire.setSource(NormalizedTransactionSource.BYBIT);
        umbrellaAcquire.setWalletAddress("BYBIT:1");

        // FUND has no prior position → resolveCarrySourcePosition falls back to BYBIT:1 umbrella
        NormalizedTransaction fundCorridorOut = tx("2", "0xcorr-a", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "USDC", "-100", null, null));
        fundCorridorOut.setSource(NormalizedTransactionSource.BYBIT);
        fundCorridorOut.setWalletAddress("BYBIT:1:FUND");
        fundCorridorOut.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-a");
        fundCorridorOut.setContinuityCandidate(true);
        fundCorridorOut.setMatchedCounterparty("user-on-chain");

        NormalizedTransaction onChainIn = tx("3", "0xcorr-a", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", null, null));
        onChainIn.setWalletAddress("user-on-chain");
        onChainIn.setNetworkId(NetworkId.ARBITRUM);
        onChainIn.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-a");
        onChainIn.setContinuityCandidate(true);
        onChainIn.setMatchedCounterparty("BYBIT:1:FUND");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(umbrellaAcquire, fundCorridorOut, onChainIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // With fix: FUND is empty → carrySource = BYBIT:1 umbrella (cbD=$100) → corridorCarryBasis=$100
        AssetLedgerPoint carryIn = latestPoint(points, "user-on-chain", NetworkId.ARBITRUM, "USDC", null);
        assertThat(carryIn.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
        assertThat(carryIn.getTotalCostBasisAfterUsd()).isGreaterThan(new BigDecimal("90"));
    }

    @Test
    void corridorFundOutboundUsesFundAvcoWhenFundHasBasis() {
        // Sub-pattern A guard: when FUND has real inventory (qty>0), resolveCarrySourcePosition
        // must NOT fall back to umbrella — it should use FUND's own basis.
        // Establish :FUND inventory via the on-chain-fund corridor corr, which legitimately keeps the
        // :FUND sub-account key (RC-1 only consolidates bybit-earn-principal-v1: FUND onto the umbrella).
        NormalizedTransaction fundAcquire = tx("1", "0xfund-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", "1", PriceSource.BINANCE));
        fundAcquire.setSource(NormalizedTransactionSource.BYBIT);
        fundAcquire.setWalletAddress("BYBIT:2:FUND");
        fundAcquire.setContinuityCandidate(true);
        fundAcquire.setCorrelationId("bybit-earn-onchain-fund-v1:fund-setup");

        NormalizedTransaction fundCorridorOut = tx("2", "0xcorr-b", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "USDC", "-100", null, null));
        fundCorridorOut.setSource(NormalizedTransactionSource.BYBIT);
        fundCorridorOut.setWalletAddress("BYBIT:2:FUND");
        fundCorridorOut.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-b");
        fundCorridorOut.setContinuityCandidate(true);
        fundCorridorOut.setMatchedCounterparty("user-on-chain-2");

        NormalizedTransaction onChainIn = tx("3", "0xcorr-b", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", null, null));
        onChainIn.setWalletAddress("user-on-chain-2");
        onChainIn.setNetworkId(NetworkId.ARBITRUM);
        onChainIn.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-b");
        onChainIn.setContinuityCandidate(true);
        onChainIn.setMatchedCounterparty("BYBIT:2:FUND");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(fundAcquire, fundCorridorOut, onChainIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // FUND has qty=100 → hasFundCarryInventory=true → carrySource = FUND → cbD from FUND ($100)
        AssetLedgerPoint carryIn = latestPoint(points, "user-on-chain-2", NetworkId.ARBITRUM, "USDC", null);
        assertThat(carryIn.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
        assertThat(carryIn.getTotalCostBasisAfterUsd()).isGreaterThan(new BigDecimal("90"));
    }

    @Test
    void corridorInboundCommingsWithUmbrellaThenOutboundDrainsProportionalBasis() {
        // BLOCKER-4: an inbound corridor deposit no longer establishes a :FUND-only zero-basis pool
        // — it collapses onto the UID umbrella and commingles with umbrella spot inventory. A later
        // corridor outbound from :FUND therefore drains the unified umbrella pool via the carry
        // waterfall, carrying proportional basis instead of the old FUND-only cbD=0.
        //
        // Setup: a zero-basis corridor inbound (qty=1 ETH, cbD=0) commingles onto BYBIT:3 with a
        // $3705 umbrella buy (qty=1) → umbrella holds qty=2, totalBasis=$3705. The outbound corridor
        // then moves 1 ETH: proportional basis = $3705 × (1/2) = $1852.50 lands on the on-chain leg.
        NormalizedTransaction noBasisBuy = tx("1", "0xno-basis-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        noBasisBuy.setWalletAddress("no-basis-wallet");
        noBasisBuy.setNetworkId(NetworkId.ARBITRUM);
        // ETH with no price → qty=1, uncovered=1, cbD=0

        NormalizedTransaction fundEstabOut = tx("2", "0xfund-estab", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        fundEstabOut.setWalletAddress("no-basis-wallet");
        fundEstabOut.setNetworkId(NetworkId.ARBITRUM);
        fundEstabOut.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xfund-estab");
        fundEstabOut.setContinuityCandidate(true);
        fundEstabOut.setMatchedCounterparty("BYBIT:3:FUND");

        NormalizedTransaction fundEstabIn = tx("3", "0xfund-estab", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        fundEstabIn.setSource(NormalizedTransactionSource.BYBIT);
        fundEstabIn.setWalletAddress("BYBIT:3:FUND");
        fundEstabIn.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xfund-estab");
        fundEstabIn.setContinuityCandidate(true);
        fundEstabIn.setMatchedCounterparty("no-basis-wallet");
        // FUND now has qty=1, cbD=0 (carry from zero-basis source)

        // Umbrella has valid basis — but sub-pattern B must NOT fall back to it
        NormalizedTransaction umbrellaAcquire = tx("4", "0xumbrella-buy2", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "3705", PriceSource.BINANCE));
        umbrellaAcquire.setSource(NormalizedTransactionSource.BYBIT);
        umbrellaAcquire.setWalletAddress("BYBIT:3");

        NormalizedTransaction fundCorridorOut = tx("5", "0xcorr-subB", 4, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        fundCorridorOut.setSource(NormalizedTransactionSource.BYBIT);
        fundCorridorOut.setWalletAddress("BYBIT:3:FUND");
        fundCorridorOut.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-subB");
        fundCorridorOut.setContinuityCandidate(true);
        fundCorridorOut.setMatchedCounterparty("user-on-chain-3");

        NormalizedTransaction onChainIn = tx("6", "0xcorr-subB", 5, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        onChainIn.setWalletAddress("user-on-chain-3");
        onChainIn.setNetworkId(NetworkId.ARBITRUM);
        onChainIn.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-subB");
        onChainIn.setContinuityCandidate(true);
        onChainIn.setMatchedCounterparty("BYBIT:3:FUND");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(noBasisBuy, fundEstabOut, fundEstabIn, umbrellaAcquire, fundCorridorOut, onChainIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // BLOCKER-4: the inbound corridor deposit commingled onto BYBIT:3 (umbrella qty=2,
        // totalBasis=$3705). The outbound corridor drains 1 ETH via the umbrella waterfall carrying
        // proportional basis = $3705 × (1/2) = $1852.50 (not the old FUND-only cbD=0).
        AssetLedgerPoint carryIn = latestPoint(points, "user-on-chain-3", NetworkId.ARBITRUM, "ETH", null);
        assertThat(carryIn.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
        assertThat(carryIn.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1852.5");
    }

    @Test
    void corridorOutboundDrainsFundSliceAndUmbrellaRemainder_emittingBothPoints() {
        // ETH umbrella-phantom regression anchor: the principal a corridor withdrawal spends is
        // split between the :FUND slice (earn-onchain-fund basis) and a larger umbrella lot (an
        // ETH buy that never drained when ETH left the venue). The drain plan must drain BOTH and
        // emit a ledger point for EACH, so the persisted trail conserves: umbrella qtyAfter→0 and
        // :FUND qtyAfter→0, with Σ|CARRY_OUT qtyDelta| == outbound qty and the combined basis
        // carried onto the on-chain CARRY_IN.
        NormalizedTransaction fundAcquire = tx("1", "0xfund-eth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.6929", "3000", PriceSource.BINANCE));
        fundAcquire.setSource(NormalizedTransactionSource.BYBIT);
        fundAcquire.setWalletAddress("BYBIT:9:FUND");
        fundAcquire.setContinuityCandidate(true);
        fundAcquire.setCorrelationId("bybit-earn-onchain-fund-v1:eth-fund-setup");

        // Umbrella ETH lot that the corridor outbound must also drain (the 1.149 phantom).
        NormalizedTransaction umbrellaAcquire = tx("2", "0xumbrella-eth", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.149", "3818", PriceSource.BINANCE));
        umbrellaAcquire.setSource(NormalizedTransactionSource.BYBIT);
        umbrellaAcquire.setWalletAddress("BYBIT:9");

        // Off-venue corridor withdrawal of the full 1.8419 ETH from :FUND. :FUND only covers
        // 0.6929 → the 1.149 remainder waterfalls onto the BYBIT:9 umbrella sibling.
        NormalizedTransaction fundCorridorOut = tx("3", "0xcorr-eth", 2, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1.8419", null, null));
        fundCorridorOut.setSource(NormalizedTransactionSource.BYBIT);
        fundCorridorOut.setWalletAddress("BYBIT:9:FUND");
        fundCorridorOut.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-eth");
        fundCorridorOut.setContinuityCandidate(true);
        fundCorridorOut.setMatchedCounterparty("user-on-chain-eth");

        NormalizedTransaction onChainIn = tx("4", "0xcorr-eth", 3, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1.8419", null, null));
        onChainIn.setWalletAddress("user-on-chain-eth");
        onChainIn.setNetworkId(NetworkId.ARBITRUM);
        onChainIn.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xcorr-eth");
        onChainIn.setContinuityCandidate(true);
        onChainIn.setMatchedCounterparty("BYBIT:9:FUND");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(fundAcquire, umbrellaAcquire, fundCorridorOut, onChainIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();

        // Both Bybit sub-pools drained to zero (network is null for BYBIT-source positions).
        AssetLedgerPoint fundPoint = latestPoint(points, "BYBIT:9:FUND", null, "ETH", null);
        assertThat(fundPoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        assertThat(fundPoint.getQuantityAfter()).isEqualByComparingTo("0");

        AssetLedgerPoint umbrellaPoint = latestPoint(points, "BYBIT:9", null, "ETH", null);
        assertThat(umbrellaPoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        assertThat(umbrellaPoint.getQuantityAfter()).isEqualByComparingTo("0");

        // Σ|CARRY_OUT qtyDelta| across the two emitted drain points == outbound quantity.
        BigDecimal drainedTotal = points.stream()
                .filter(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_OUT)
                .filter(point -> "ETH".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getWalletAddress() != null
                        && point.getWalletAddress().startsWith("BYBIT:9"))
                .map(point -> point.getQuantityDelta().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(drainedTotal).isEqualByComparingTo("1.8419");

        // Combined basis (≈0.6929×3000 + 1.149×3818) carried onto the on-chain inbound.
        AssetLedgerPoint carryIn = latestPoint(points, "user-on-chain-eth", NetworkId.ARBITRUM, "ETH", null);
        assertThat(carryIn.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
        assertThat(carryIn.getTotalCostBasisAfterUsd()).isGreaterThan(new BigDecimal("6000"));
    }

    @Test
    void earnPrincipalPathUnaffectedByFundChange() {
        // Regression guard: the :EARN fallback path in resolveCarrySourcePosition must continue
        // to fall back to the umbrella when EARN position has no basis, regardless of the new
        // :FUND corridor path.
        NormalizedTransaction umbrellaAcquire = tx("1", "0xumbrella-buy3", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "AGLD", "500", "5", PriceSource.BINANCE));
        umbrellaAcquire.setSource(NormalizedTransactionSource.BYBIT);
        umbrellaAcquire.setWalletAddress("BYBIT:9999");

        // EARN outbound — EARN position is empty so should fall back to umbrella
        NormalizedTransaction earnOut = tx("2", "0xearn-out-rg", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "AGLD", "-50", null, null));
        earnOut.setSource(NormalizedTransactionSource.BYBIT);
        earnOut.setWalletAddress("BYBIT:9999:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:rg-regression");
        earnOut.setMatchedCounterparty("BYBIT:9999");

        NormalizedTransaction fundIn = tx("3", "0xearn-in-rg", 1, NormalizedTransactionType.LENDING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "AGLD", "50", null, null));
        fundIn.setSource(NormalizedTransactionSource.BYBIT);
        fundIn.setWalletAddress("BYBIT:9999");
        fundIn.setContinuityCandidate(true);
        fundIn.setCorrelationId("bybit-earn-principal-v1:rg-regression");
        fundIn.setMatchedCounterparty("BYBIT:9999:EARN");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(umbrellaAcquire, earnOut, fundIn));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();
        // EARN path: EARN empty → umbrella BYBIT:9999 has AGLD cbD=$2500 → carry lands back on
        // BYBIT:9999 with basis > 0 (regression guard: FUND corridor change must not break this)
        AssetLedgerPoint umbrellaIn = capturedLedgerPoints().stream()
                .filter(p -> "BYBIT:9999".equals(p.getWalletAddress()))
                .filter(p -> "AGLD".equalsIgnoreCase(p.getAssetSymbol()))
                .filter(p -> p.getQuantityDelta() != null && p.getQuantityDelta().signum() > 0)
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(umbrellaIn.getBasisEffect()).isIn(
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );
        assertThat(umbrellaIn.getTotalCostBasisAfterUsd()).isGreaterThan(new BigDecimal("200"));
    }

    // ── end B-BYBIT-CORRIDOR-2 tests ──────────────────────────────────────────────────────────

    // ── B-ONDO-CARRY-1 tests ───────────────────────────────────────────────────────────────────

    @Test
    void ondoEarnFundSideUsesCorrFamilyForCollapsedV1CorrId() {
        // T-02: bybit-collapsed-v1: corrIds are excluded from the earn-carry FIFO so both
        // legs of a FUND↔EARN collapsed pair route to corr-family: and share the same queue.
        var assetSupport = new com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport();
        var keyFactory = new com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory(assetSupport);

        NormalizedTransaction fundTx = new NormalizedTransaction();
        fundTx.setSource(NormalizedTransactionSource.BYBIT);
        fundTx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        fundTx.setWalletAddress("BYBIT:33625378:FUND");
        fundTx.setMatchedCounterparty("BYBIT:33625378:EARN");
        fundTx.setCorrelationId("bybit-collapsed-v1:ONDO-TEST-1");
        fundTx.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ONDO");
        flow.setQuantityDelta(new BigDecimal("-100"));
        fundTx.setFlows(List.of(flow));

        var key = keyFactory.transferKey(fundTx, flow);

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:bybit-collapsed-v1:ONDO-TEST-1:");
        assertThat(key.value()).doesNotContain("bybit-earn-carry");
    }

    @Test
    void ondoEarnBundleFallsBackToFifoWhenPrimaryQueueEmpty() {
        // FUND→EARN transfer uses a non-collapsed corrId so it routes to the earn-carry FIFO.
        // The bundle inbound first tries its own corr-family queue (empty) then falls back to
        // the earn-carry FIFO, picking up the basis posted by fundOut.
        NormalizedTransaction buy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ONDO", "100", "1.30", PriceSource.BINANCE));
        buy.setSource(NormalizedTransactionSource.BYBIT);
        buy.setWalletAddress("BYBIT:33625378:FUND");

        NormalizedTransaction fundOut = tx("2", "0xfund-earn-out", 1, NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ONDO", "-100", null, null));
        fundOut.setSource(NormalizedTransactionSource.BYBIT);
        fundOut.setWalletAddress("BYBIT:33625378:FUND");
        fundOut.setMatchedCounterparty("BYBIT:33625378:EARN");
        // Use a non-collapsed corrId so fundOut routes to the earn-carry FIFO (not corr-family)
        fundOut.setCorrelationId("bybit-econ-v1:ONDO-TEST-2");
        fundOut.setContinuityCandidate(false);

        NormalizedTransaction bundleIn = tx("3", "0xbundle-in", 2, NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ONDO", "100", null, null));
        bundleIn.setSource(NormalizedTransactionSource.BYBIT);
        bundleIn.setWalletAddress("BYBIT:33625378:EARN");
        bundleIn.setCorrelationId("bybit-it-bundle-v1:ONDO-BUNDLE-TEST-2");
        bundleIn.setContinuityCandidate(true);

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, fundOut, bundleIn));

        service().replayConfirmed();

        AssetLedgerPoint earnPoint = capturedLedgerPoints().stream()
                .filter(point -> "BYBIT:33625378:EARN".equals(point.getWalletAddress()))
                .filter(point -> "ONDO".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getQuantityDelta() != null && point.getQuantityDelta().signum() > 0)
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(earnPoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
        assertThat(earnPoint.getCostBasisDeltaUsd()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void collapsedFundToEarnDrainsFundSubAccountWhenUmbrellaEmpty() {
        // R-1/R-3: real Bybit shape. ONDO is acquired on UTA (root/umbrella position), swept
        // UTA→FUND (collapsed pair credits the :FUND sub-account), then FUND→EARN (collapsed).
        // The FUND→EARN outbound leg strips :FUND to the empty umbrella for its position key,
        // but the inventory lives on :FUND. Before the fix it drained the empty umbrella and the
        // EARN inbound carried ~$0 (collapsing AVCO → fabricated gains; FUND USDT $0.84,
        // cmETH/mETH $0). The carry-source fallback now drains :FUND so EARN inherits the real
        // ~$2/unit basis (realised = 0).
        NormalizedTransaction utaBuy = bybitBundleBuyTx("uta-buy", "0xa", 0,
                "BYBIT:33625378:UTA", "ONDO", "100", "2");

        // UTA→FUND collapsed pair: outbound drains the umbrella, inbound credits :FUND.
        NormalizedTransaction utaFundOut = bybitBundleOutTx("uta-fund-out", "0xb", 1,
                "BYBIT:33625378:UTA", "BYBIT:33625378:FUND", "ONDO", "-100",
                "bybit-collapsed-v1:UTAFUND-1");
        NormalizedTransaction utaFundIn = bybitBundleOutTx("uta-fund-in", "0xb", 2,
                "BYBIT:33625378:FUND", "BYBIT:33625378:UTA", "ONDO", "100",
                "bybit-collapsed-v1:UTAFUND-1");

        // FUND→EARN collapsed pair: outbound resolves to the now-empty umbrella; the fix
        // redirects the drain to the funded :FUND sub-account.
        NormalizedTransaction fundEarnOut = bybitBundleOutTx("fund-earn-out", "0xc", 3,
                "BYBIT:33625378:FUND", "BYBIT:33625378:EARN", "ONDO", "-100",
                "bybit-collapsed-v1:FUNDEARN-1");
        NormalizedTransaction fundEarnIn = bybitBundleOutTx("fund-earn-in", "0xc", 4,
                "BYBIT:33625378:EARN", "BYBIT:33625378:FUND", "ONDO", "100",
                "bybit-collapsed-v1:FUNDEARN-1");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(utaBuy, utaFundOut, utaFundIn, fundEarnOut, fundEarnIn));

        service().replayConfirmed();

        AssetLedgerPoint earnPoint = capturedLedgerPoints().stream()
                .filter(point -> "BYBIT:33625378:EARN".equals(point.getWalletAddress()))
                .filter(point -> "ONDO".equalsIgnoreCase(point.getAssetSymbol()))
                .filter(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_IN)
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();

        assertThat(earnPoint.getCostBasisDeltaUsd())
                .as("EARN inbound carries the funded :FUND basis (~$2/unit), not ~$0")
                .isGreaterThan(new BigDecimal("150"));
        assertThat(earnPoint.getTotalCostBasisAfterUsd())
                .as("EARN total cost basis ≈ $200 (100 ONDO @ $2)")
                .isBetween(new BigDecimal("199"), new BigDecimal("201"));
    }

    @Test
    void crossUidCorrFamilyKeyUnaffectedByEarnReorder() {
        var assetSupport = new com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport();
        var keyFactory = new com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory(assetSupport);

        NormalizedTransaction crossUidTx = new NormalizedTransaction();
        crossUidTx.setSource(NormalizedTransactionSource.BYBIT);
        crossUidTx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        crossUidTx.setWalletAddress("BYBIT:33625378:FUND");
        crossUidTx.setMatchedCounterparty("BYBIT:409666:FUND");
        crossUidTx.setCorrelationId("bybit-cross-uid-v1:CROSSTEST");
        crossUidTx.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("-1.0"));
        crossUidTx.setFlows(List.of(flow));

        var key = keyFactory.transferKey(crossUidTx, flow);

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:");
        assertThat(key.value()).doesNotContain("bybit-earn-carry");
    }

    // ── end B-ONDO-CARRY-1 tests ───────────────────────────────────────────────────────────────

    // ── B-EARN-BUNDLE tests ────────────────────────────────────────────────────────────────────

    /**
     * AC-12: EARN inbound arrives before FUND/UTA outbounds. FUND is the partial leg.
     * Verifies that both CARRY_IN events are emitted and total EARN cbD reflects both carries.
     */
    @Test
    void earnBundleFundLegPartialAttachesAndUtaLegConsumes() {
        // FUND buys 0.016 ONDO at $1 → FUND position: qty=0.016, basis=$0.016
        NormalizedTransaction fundBuy = bybitBundleBuyTx("buy-fund", "0xfund-buy", 0,
                "BYBIT:33625378:FUND", "ONDO", "0.016", "1");

        // UTA buys 8.325 ONDO at $1 → UTA position: qty=8.325, basis=$8.325
        NormalizedTransaction utaBuy = bybitBundleBuyTx("buy-uta", "0xuta-buy", 1,
                "BYBIT:33625378:UTA", "ONDO", "8.325", "1");

        // EARN inbound (arrives first, provisional basis via unit price $1)
        NormalizedTransaction earnIn = bybitBundleTx("earn-in", "0xbundle", 2,
                "BYBIT:33625378:EARN", "ONDO", "8.341", "1",
                "bybit-it-bundle-v1:EARN-FAN-IN-1");

        // FUND outbound (small, partial leg)
        NormalizedTransaction fundOut = bybitBundleOutTx("fund-out", "0xbundle", 3,
                "BYBIT:33625378:FUND", "BYBIT:33625378:EARN", "ONDO", "-0.016",
                "bybit-it-bundle-v1:EARN-FAN-IN-1");

        // UTA outbound (large, final consumer)
        NormalizedTransaction utaOut = bybitBundleOutTx("uta-out", "0xbundle", 4,
                "BYBIT:33625378:UTA", "BYBIT:33625378:EARN", "ONDO", "-8.325",
                "bybit-it-bundle-v1:EARN-FAN-IN-1");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(fundBuy, utaBuy, earnIn, fundOut, utaOut));

        service().replayConfirmed();

        List<AssetLedgerPoint> allPoints = capturedLedgerPoints();
        List<AssetLedgerPoint> earnCarryIns = allPoints.stream()
                .filter(p -> "BYBIT:33625378:EARN".equals(p.getWalletAddress()))
                .filter(p -> "ONDO".equalsIgnoreCase(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_IN)
                .toList();

        // Three CARRY_IN events total:
        // #1 – EARN inbound materialisation (provisional), #2 – FUND partial attach, #3 – UTA final attach.
        // In the broken state (before fix) only 2 events exist (UTA carry is orphaned).
        assertThat(earnCarryIns).as("three CARRY_IN ledger points on EARN (inbound + FUND + UTA legs)").hasSize(3);

        // Total EARN cbD after full replay = FUND carry + UTA carry = $0.016 + $8.325 = $8.341
        AssetLedgerPoint finalEarnPoint = earnCarryIns.stream()
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(finalEarnPoint.getTotalCostBasisAfterUsd())
                .as("EARN total cost basis covers both FUND and UTA carries")
                .isGreaterThan(new BigDecimal("8"));
        assertThat(finalEarnPoint.getTotalCostBasisAfterUsd())
                .as("EARN total cost basis not double-counted")
                .isLessThan(new BigDecimal("9"));

    }

    /**
     * AC-12 (reverse order): UTA outbound arrives before FUND outbound.
     * UTA is now the partial leg; FUND is the final consumer. Both CARRY_IN must still be emitted.
     */
    @Test
    void earnBundleUtaFirstFundSecondBothAttach() {
        NormalizedTransaction fundBuy = bybitBundleBuyTx("buy-fund", "0xfund-buy", 0,
                "BYBIT:33625378:FUND", "ONDO", "0.016", "1");
        NormalizedTransaction utaBuy = bybitBundleBuyTx("buy-uta", "0xuta-buy", 1,
                "BYBIT:33625378:UTA", "ONDO", "8.325", "1");

        NormalizedTransaction earnIn = bybitBundleTx("earn-in", "0xbundle", 2,
                "BYBIT:33625378:EARN", "ONDO", "8.341", "1",
                "bybit-it-bundle-v1:EARN-FAN-IN-2");

        // UTA arrives before FUND (reversed order vs test 1)
        NormalizedTransaction utaOut = bybitBundleOutTx("uta-out", "0xbundle", 3,
                "BYBIT:33625378:UTA", "BYBIT:33625378:EARN", "ONDO", "-8.325",
                "bybit-it-bundle-v1:EARN-FAN-IN-2");

        NormalizedTransaction fundOut = bybitBundleOutTx("fund-out", "0xbundle", 4,
                "BYBIT:33625378:FUND", "BYBIT:33625378:EARN", "ONDO", "-0.016",
                "bybit-it-bundle-v1:EARN-FAN-IN-2");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(fundBuy, utaBuy, earnIn, utaOut, fundOut));

        service().replayConfirmed();

        List<AssetLedgerPoint> allPoints = capturedLedgerPoints();
        List<AssetLedgerPoint> earnCarryIns = allPoints.stream()
                .filter(p -> "BYBIT:33625378:EARN".equals(p.getWalletAddress()))
                .filter(p -> "ONDO".equalsIgnoreCase(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_IN)
                .toList();

        // Three CARRY_IN events: EARN inbound + UTA partial attach + FUND final attach.
        assertThat(earnCarryIns).as("three CARRY_IN ledger points on EARN (reversed leg order)").hasSize(3);

        AssetLedgerPoint finalEarnPoint = earnCarryIns.stream()
                .max(Comparator.comparing(AssetLedgerPoint::getReplaySequence))
                .orElseThrow();
        assertThat(finalEarnPoint.getTotalCostBasisAfterUsd())
                .as("EARN total cost basis reflects both carries regardless of leg order")
                .isGreaterThan(new BigDecimal("8"));
        assertThat(finalEarnPoint.getTotalCostBasisAfterUsd())
                .as("EARN total cost basis not double-counted")
                .isLessThan(new BigDecimal("9"));
    }

    /**
     * AC-6 regression guard: {@code bybit-cross-uid-v1:} transfers must NOT be routed to
     * {@code applyBybitMultiLegBundleTransfer} and must NOT be affected by the partial-leg logic.
     * Cross-UID transfers use the {@code corr-family:} pending key but go through the standard
     * outbound→carry→inbound path, not the bundle fan-in path.
     */
    @Test
    void earnBundleNoPriorCrossUidCarryRegression() {
        var assetSupport = new com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport();
        var keyFactory = new com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory(assetSupport);

        // Cross-UID transfer: different UIDs → not a bybit-it-bundle-v1: → isBybitMultiLegBundleTransfer = false
        NormalizedTransaction crossUidOut = new NormalizedTransaction();
        crossUidOut.setSource(NormalizedTransactionSource.BYBIT);
        crossUidOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        crossUidOut.setWalletAddress("BYBIT:33625378:FUND");
        crossUidOut.setMatchedCounterparty("BYBIT:409666:FUND");
        crossUidOut.setCorrelationId("bybit-cross-uid-v1:CROSS-REGRESSION-TEST");
        crossUidOut.setContinuityCandidate(true);
        NormalizedTransaction.Flow crossFlow = new NormalizedTransaction.Flow();
        crossFlow.setRole(NormalizedLegRole.TRANSFER);
        crossFlow.setAssetSymbol("ONDO");
        crossFlow.setQuantityDelta(new BigDecimal("-5.0"));
        crossUidOut.setFlows(List.of(crossFlow));

        // Verify: cross-uid uses corr-family: key, confirming it is NOT routed to bundle handler
        var key = keyFactory.transferKey(crossUidOut, crossFlow);
        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:bybit-cross-uid-v1:");
        assertThat(key.value()).doesNotContain("bybit-earn-carry");

        // Verify: isBybitMultiLegBundleTransfer = false for cross-uid prefix
        var classifier = new com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier(keyFactory);
        assertThat(classifier.isBybitMultiLegBundleTransfer(crossUidOut)).isFalse();
    }

    // Helper: create a Bybit INTERNAL_TRANSFER for a bundle leg (no matchedCounterparty — EARN inbound)
    private NormalizedTransaction bybitBundleTx(
            String id, String txHash, int txIndex,
            String walletAddress, String assetSymbol, String qty, String unitPrice,
            String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(txHash);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setWalletAddress(walletAddress);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z").plusSeconds(txIndex));
        tx.setTransactionIndex(txIndex);
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(NormalizedLegRole.TRANSFER);
        f.setAssetSymbol(assetSymbol);
        f.setQuantityDelta(new BigDecimal(qty));
        if (unitPrice != null) {
            f.setUnitPriceUsd(new BigDecimal(unitPrice));
        }
        tx.setFlows(List.of(f));
        return tx;
    }

    // Helper: create a Bybit INTERNAL_TRANSFER for a bundle outbound leg (with matchedCounterparty)
    private NormalizedTransaction bybitBundleOutTx(
            String id, String txHash, int txIndex,
            String walletAddress, String matchedCounterparty,
            String assetSymbol, String qty, String correlationId
    ) {
        NormalizedTransaction tx = bybitBundleTx(id, txHash, txIndex,
                walletAddress, assetSymbol, qty, null, correlationId);
        tx.setMatchedCounterparty(matchedCounterparty);
        return tx;
    }

    // Helper: create a simple Bybit BUY transaction to populate a wallet position
    private NormalizedTransaction bybitBundleBuyTx(
            String id, String txHash, int txIndex,
            String walletAddress, String assetSymbol, String qty, String unitPrice
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(txHash);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setWalletAddress(walletAddress);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z").plusSeconds(txIndex));
        tx.setTransactionIndex(txIndex);
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(NormalizedLegRole.BUY);
        f.setAssetSymbol(assetSymbol);
        f.setQuantityDelta(new BigDecimal(qty));
        f.setUnitPriceUsd(new BigDecimal(unitPrice));
        f.setPriceSource(PriceSource.BINANCE);
        tx.setFlows(List.of(f));
        return tx;
    }

    // ── end B-EARN-BUNDLE tests ────────────────────────────────────────────────────────────────

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
        com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport assetSupport =
                new com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport();
        com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine genericFlowReplayEngine =
                new com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine(null);
        com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport replayFlowSupport =
                new com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport(genericFlowReplayEngine);
        com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService continuityCarryService =
                new com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService(
                        genericFlowReplayEngine,
                        replayFlowSupport
                );
        com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory keyFactory =
                new com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory(assetSupport);
        com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier transferClassifier =
                new com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier(keyFactory);
        com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher pendingTransferMatcher =
                new com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher();
        com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator settlementAllocator =
                new com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.application.pricing.persistence.HistoricalPriceCacheService historicalPriceCacheService =
                org.mockito.Mockito.mock(com.walletradar.application.pricing.persistence.HistoricalPriceCacheService.class);
        com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator priceExternalSourceOrchestrator =
                org.mockito.Mockito.mock(com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator.class);
        org.mockito.Mockito.lenient().when(priceExternalSourceOrchestrator.prioritizedSources(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(PriceSource.BINANCE));
        org.mockito.Mockito.lenient().when(historicalPriceCacheService.findQuote(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(PriceSource.BINANCE)))
                .thenAnswer(invocation -> {
                    com.walletradar.application.pricing.domain.PriceRequest request = invocation.getArgument(0);
                    if (request != null && "ETH".equalsIgnoreCase(request.assetSymbol())) {
                        return java.util.Optional.of(new com.walletradar.application.pricing.domain.PriceQuote(
                                new BigDecimal("3000"),
                                PriceSource.BINANCE,
                                Instant.parse("2026-03-25T10:00:00Z"),
                                "ETH",
                                "test"
                        ));
                    }
                    return java.util.Optional.empty();
                });
        com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority replayMarketAuthority =
                new com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority(
                        historicalPriceCacheService,
                        priceExternalSourceOrchestrator
                );
        com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler transferReplayHandler =
                com.walletradar.testsupport.TransferReplayHandlerFixtures.handler(
                        replayFlowSupport,
                        continuityCarryService,
                        keyFactory,
                        transferClassifier,
                        pendingTransferMatcher,
                        replayMarketAuthority
                );
        com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler liquidStakingReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator
                );
        com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler familyReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        continuityCarryService,
                        keyFactory
                );
        com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator
                );
        com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler gmxLpEntryReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.application.costbasis.domain.LpReceiptBasisPoolRepository lpReceiptBasisPoolRepository =
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.domain.LpReceiptBasisPoolRepository.class);
        org.mockito.Mockito.lenient().when(lpReceiptBasisPoolRepository.findByUniverseId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new java.util.ArrayList<>(
                        lpReceiptStore.getOrDefault(invocation.getArgument(0), new LinkedHashMap<>()).values()));
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            lpReceiptStore.remove(invocation.getArgument(0));
            return null;
        }).when(lpReceiptBasisPoolRepository).deleteByUniverseId(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.lenient().when(lpReceiptBasisPoolRepository.saveAll(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Iterable<com.walletradar.application.costbasis.domain.LpReceiptBasisPool> pools = invocation.getArgument(0);
                    for (com.walletradar.application.costbasis.domain.LpReceiptBasisPool pool : pools) {
                        lpReceiptStore
                                .computeIfAbsent(pool.getUniverseId(), ignored -> new LinkedHashMap<>())
                                .put(pool.getId(), pool);
                    }
                    return java.util.Collections.emptyList();
                });
        LpReceiptBasisPoolService lpReceiptBasisPoolService = new LpReceiptBasisPoolService(lpReceiptBasisPoolRepository);
        com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        settlementAllocator,
                        lpReceiptBasisPoolService,
                        keyFactory
                );
        com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler eulerLoopReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler(
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.application.costbasis.domain.AssetFamilyResolver assetFamilyResolver =
                new com.walletradar.application.costbasis.domain.AssetFamilyResolver();
        com.walletradar.application.session.application.AccountingUniverseService accountingUniverseService =
                org.mockito.Mockito.mock(com.walletradar.application.session.application.AccountingUniverseService.class);
        com.walletradar.application.costbasis.domain.CounterpartyBasisPoolRepository counterpartyBasisPoolRepository =
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.domain.CounterpartyBasisPoolRepository.class);
        org.mockito.Mockito.lenient().when(counterpartyBasisPoolRepository.findByUniverseId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new java.util.ArrayList<>(
                        counterpartyStore.getOrDefault(invocation.getArgument(0), new LinkedHashMap<>()).values()));
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            counterpartyStore.remove(invocation.getArgument(0));
            return null;
        }).when(counterpartyBasisPoolRepository).deleteByUniverseId(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.lenient().when(counterpartyBasisPoolRepository.saveAll(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Iterable<com.walletradar.application.costbasis.domain.CounterpartyBasisPool> pools = invocation.getArgument(0);
                    for (com.walletradar.application.costbasis.domain.CounterpartyBasisPool pool : pools) {
                        counterpartyStore
                                .computeIfAbsent(pool.getUniverseId(), ignored -> new LinkedHashMap<>())
                                .put(pool.getId(), pool);
                    }
                    return java.util.Collections.emptyList();
                });
        CounterpartyBasisPoolService counterpartyBasisPoolService = new CounterpartyBasisPoolService(
                counterpartyBasisPoolRepository,
                assetFamilyResolver,
                accountingUniverseService
        );
        com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook =
                new com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook(
                counterpartyBasisPoolService,
                transferClassifier
        );
        com.walletradar.application.costbasis.domain.BorrowLiabilityRepository borrowLiabilityRepository =
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.domain.BorrowLiabilityRepository.class);
        org.mockito.Mockito.lenient().when(borrowLiabilityRepository.findByUniverseId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new java.util.ArrayList<>(
                        borrowStore.getOrDefault(invocation.getArgument(0), new LinkedHashMap<>()).values()));
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            borrowStore.remove(invocation.getArgument(0));
            return null;
        }).when(borrowLiabilityRepository).deleteByUniverseId(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.lenient().when(borrowLiabilityRepository.saveAll(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Iterable<com.walletradar.application.costbasis.domain.BorrowLiability> liabilities = invocation.getArgument(0);
                    for (com.walletradar.application.costbasis.domain.BorrowLiability liability : liabilities) {
                        borrowStore
                                .computeIfAbsent(liability.getUniverseId(), ignored -> new LinkedHashMap<>())
                                .put(liability.getCompositeId(), liability);
                    }
                    return java.util.Collections.emptyList();
                });
        this.borrowLiabilityRepositoryRef = borrowLiabilityRepository;
        BorrowLiabilityTracker borrowLiabilityTracker = new BorrowLiabilityTracker(borrowLiabilityRepository);
        com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler borrowReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler(
                        borrowLiabilityTracker,
                        assetSupport,
                        replayFlowSupport,
                        null
                );
        com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler repayReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler(
                        borrowLiabilityTracker,
                        assetSupport,
                        replayFlowSupport
                );
        com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler(
                        assetSupport,
                        replayFlowSupport,
                        lpReceiptBasisPoolService
                );
        com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler =
                new com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler(
                        transferClassifier,
                        transferReplayHandler
                );
        com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook leverageBorrowReplayHook =
                new com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook(
                        new com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector(),
                        borrowLiabilityTracker
                );
        com.walletradar.application.costbasis.application.replay.dispatch.ReplayRouteHandlerRegistry replayRouteHandlerRegistry =
                com.walletradar.application.costbasis.application.replay.dispatch.ReplayRouteHandlerRegistryFactory.create(
                        eulerLoopReplayHandler,
                        gmxLpEntryReplayHandler,
                        lpReceiptEntryReplayHandler,
                        genericAsyncLifecycleReplayHandler,
                        positionScopedLpExitReplayHandler,
                        liquidStakingReplayHandler,
                        familyReplayHandler
                );
        com.walletradar.application.costbasis.application.replay.dispatch.ReplayDispatcher replayDispatcher =
                new com.walletradar.application.costbasis.application.replay.dispatch.ReplayDispatcher(
                        new com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter(),
                        assetSupport,
                        replayFlowSupport,
                        transferClassifier,
                        keyFactory,
                        replayRouteHandlerRegistry,
                        org.mockito.Mockito.mock(com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy.class),
                        transferReplayHandler,
                        bybitVenueInternalReplayHandler,
                        liquidStakingReplayHandler,
                        familyReplayHandler,
                        genericAsyncLifecycleReplayHandler,
                        gmxLpEntryReplayHandler,
                        lpReceiptEntryReplayHandler,
                        positionScopedLpExitReplayHandler,
                        asyncSpotOrderReplayHandler,
                        counterpartyBasisPoolReplayHook,
                        leverageBorrowReplayHook,
                        borrowReplayHandler,
                        repayReplayHandler,
                        replayMarketAuthority
                );
        com.walletradar.application.costbasis.domain.AccountingShortfallAuditRepository shortfallAuditRepository =
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.domain.AccountingShortfallAuditRepository.class);
        AccountingShortfallAuditService accountingShortfallAuditService =
                new AccountingShortfallAuditService(shortfallAuditRepository);
        return new AvcoReplayService(
                new com.walletradar.application.costbasis.application.replay.query.ConfirmedReplayQueryService(normalizedTransactionRepository),
                normalizedTransactionRepository,
                assetLedgerPointRepository,
                new com.walletradar.application.costbasis.application.replay.planning.PassThroughCorridorPlanner(),
                assetSupport,
                replayFlowSupport,
                replayDispatcher,
                counterpartyBasisPoolService,
                lpReceiptBasisPoolService,
                borrowLiabilityTracker,
                accountingShortfallAuditService,
                accountingUniverseService,
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.application.replay.support.CorridorBasisConservationGuard.class),
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.application.replay.support.BybitEarnSubPoolConservationGuard.class),
                org.mockito.Mockito.mock(com.walletradar.application.costbasis.application.replay.support.NativePoolReconciliationGate.class),
                new com.walletradar.application.costbasis.application.replay.support.ReplayAccumulatorDriftCanary(),
                new com.walletradar.application.costbasis.application.replay.support.LendingLoopBasisConservationGuard()
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

    /**
     * Silo Finance LENDING_DEPOSIT double-count regression.
     *
     * <p>The on-chain tx emits two soUSDC inbound flows:
     * <ul>
     *   <li>TRANSFER in raw on-chain units (very large integer, e.g. 199835669)</li>
     *   <li>BUY in human-readable units (199.95) — an alternative encoding of the same receipt</li>
     * </ul>
     *
     * <p>Before the fix, the TRANSFER was paired by FamilyEquivalentCustodyReplayHandler (REALLOCATE_IN),
     * and the BUY then fell through to replayGenericFlowsSkipping and was recorded as a second
     * REALLOCATE_IN, doubling the cost basis and inflating AVCO to ~$2/USDC. On every subsequent
     * REPAY this generated a phantom realized loss of ~$933 for the user.
     *
     * <p>After the fix, the suppressed BUY index is added to selectedByIndex so generic replay
     * skips it — only one REALLOCATE_IN is recorded and the basis is exactly $200.
     */
    @Test
    void siloStyleLendingDepositDoesNotDoubleCountBuyAndTransferFlowsForSameReceiptToken() {
        // 200 USDC acquired at $1.00 each
        NormalizedTransaction usdcBuy = tx("1", "0xbuy-usdc", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flowWithContract(NormalizedLegRole.BUY, "USDC", "0xusdc", "200", "1", PriceSource.BINANCE));
        usdcBuy.setWalletAddress("wallet-a");
        usdcBuy.setNetworkId(NetworkId.ARBITRUM);

        // Silo deposit: USDC out, two soUSDC inbound legs with SAME contract.
        // The TRANSFER is at human-readable scale (200 USDC-equivalent).
        // The BUY is the raw on-chain ERC-20 transfer in 6-decimal units (200_000_000 = 200e6).
        // Before the fix the BUY fell through to generic replay and created a second REALLOCATE_IN,
        // doubling the cost basis to ~$400 and inflating AVCO to ~$2/USDC.
        NormalizedTransaction siloDeposit = tx("2", "0xsilo-deposit", 1, NormalizedTransactionType.LENDING_DEPOSIT,
                flowWithContract(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "-200", null, null),
                // human-readable inbound: this is the principal flow
                flowWithContract(NormalizedLegRole.TRANSFER, "soUSDC", "0xsousdc", "200", null, null),
                // raw on-chain ERC-20 amount (200e6 units at 6 decimals) — must be suppressed
                flowWithContract(NormalizedLegRole.BUY, "soUSDC", "0xsousdc", "200000000", "0.000001", PriceSource.BINANCE));
        siloDeposit.setWalletAddress("wallet-a");
        siloDeposit.setNetworkId(NetworkId.ARBITRUM);
        siloDeposit.setProtocolName("Silo Finance");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(usdcBuy, siloDeposit));

        service().replayConfirmed();

        List<AssetLedgerPoint> points = capturedLedgerPoints();

        AssetLedgerPoint usdcPoint = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "USDC", "0xusdc");
        assertThat(usdcPoint.getQuantityAfter()).isZero();
        assertThat(usdcPoint.getTotalCostBasisAfterUsd()).isZero();
        assertThat(usdcPoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);

        // There must be exactly ONE REALLOCATE_IN for soUSDC — not two.
        long soUsdcReallocateInCount = points.stream()
                .filter(p -> "wallet-a".equals(p.getWalletAddress())
                        && "soUSDC".equalsIgnoreCase(p.getAssetSymbol())
                        && AssetLedgerPoint.BasisEffect.REALLOCATE_IN.equals(p.getBasisEffect()))
                .count();
        assertThat(soUsdcReallocateInCount)
                .as("soUSDC must receive exactly one REALLOCATE_IN (no double-count from suppressed BUY)")
                .isEqualTo(1);

        AssetLedgerPoint soUsdcPoint = latestPoint(points, "wallet-a", NetworkId.ARBITRUM, "soUSDC", "0xsousdc");
        // Basis must equal the original USDC cost, not double that amount
        assertThat(soUsdcPoint.getTotalCostBasisAfterUsd())
                .as("soUSDC basis must equal USDC cost ($200), not be doubled to $400")
                .isEqualByComparingTo("200");
        // Quantity is the human-readable TRANSFER amount (200), not TRANSFER + BUY (200000200)
        assertThat(soUsdcPoint.getQuantityAfter())
                .as("soUSDC quantity must be the human-readable TRANSFER amount only, not inflated by raw BUY")
                .isEqualByComparingTo("200");
    }

    // ── B-ETH-02: lending-loop open↔close basis carry ─────────────────────────

    private static final BigDecimal LOOP_AVCO = new BigDecimal("4338.2956");
    private static final BigDecimal LOOP_OPEN_QTY = new BigDecimal("0.919170497571836978");
    private static final BigDecimal LOOP_DECREASE_QTY = new BigDecimal("0.419170497141233196");
    private static final BigDecimal LOOP_CLOSE_MARKET_PRICE = new BigDecimal("3027.90");
    private static final org.assertj.core.data.Offset<BigDecimal> CENT =
            org.assertj.core.data.Offset.offset(new BigDecimal("0.01"));

    private NormalizedTransaction lendingLoopLeg(
            String id,
            String txHash,
            int txIndex,
            NormalizedTransactionType type,
            String correlationId,
            NetworkId networkId,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = tx(id, txHash, txIndex, type, flows);
        transaction.setWalletAddress("loop-wallet");
        transaction.setNetworkId(networkId);
        transaction.setProtocolName("Euler");
        transaction.setCorrelationId(correlationId);
        return transaction;
    }

    @Test
    void lendingLoopPartialDecreaseRestoresCarriedBasisNotMarket() {
        NormalizedTransaction buy = tx("buy", "0xbuy-eth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", LOOP_OPEN_QTY.toPlainString(), LOOP_AVCO.toPlainString(), PriceSource.BINANCE));
        buy.setWalletAddress("loop-wallet");
        NormalizedTransaction open = lendingLoopLeg("open", "0xopen", 1,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "lending-loop:0xopen", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-" + LOOP_OPEN_QTY.toPlainString(), null, null));
        NormalizedTransaction decrease = lendingLoopLeg("decrease", "0xdecrease", 2,
                NormalizedTransactionType.LENDING_LOOP_DECREASE, "lending-loop:0xopen", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", LOOP_DECREASE_QTY.toPlainString(),
                        LOOP_CLOSE_MARKET_PRICE.toPlainString(), PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, open, decrease));

        service().replayConfirmed();
        List<AssetLedgerPoint> points = capturedLedgerPoints();

        AssetLedgerPoint decreasePoint = latestPoint(points, "loop-wallet", NetworkId.BASE, "ETH", null);
        BigDecimal expectedCarriedBasis = LOOP_DECREASE_QTY.multiply(LOOP_AVCO);
        BigDecimal marketBasis = LOOP_DECREASE_QTY.multiply(LOOP_CLOSE_MARKET_PRICE);

        assertThat(decreasePoint.getQuantityAfter()).isEqualByComparingTo(LOOP_DECREASE_QTY);
        assertThat(decreasePoint.getAvcoAfterUsd()).isCloseTo(LOOP_AVCO, CENT);
        assertThat(decreasePoint.getTotalCostBasisAfterUsd())
                .as("returned collateral must restore carried AVCO ($1,818.49), not market ($1,269.21)")
                .isCloseTo(expectedCarriedBasis, CENT);
        assertThat(decreasePoint.getTotalCostBasisAfterUsd()).isNotCloseTo(marketBasis, CENT);
        assertThat(decreasePoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
    }

    @Test
    void lendingLoopOpenDecreaseCloseDrainsBucketToZeroConservingBasis() {
        BigDecimal closeQty = LOOP_OPEN_QTY.subtract(LOOP_DECREASE_QTY);
        NormalizedTransaction buy = tx("buy", "0xbuy-eth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", LOOP_OPEN_QTY.toPlainString(), LOOP_AVCO.toPlainString(), PriceSource.BINANCE));
        buy.setWalletAddress("loop-wallet");
        NormalizedTransaction open = lendingLoopLeg("open", "0xopen", 1,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "lending-loop:0xopen", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-" + LOOP_OPEN_QTY.toPlainString(), null, null));
        NormalizedTransaction decrease = lendingLoopLeg("decrease", "0xdecrease", 2,
                NormalizedTransactionType.LENDING_LOOP_DECREASE, "lending-loop:0xopen", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", LOOP_DECREASE_QTY.toPlainString(),
                        LOOP_CLOSE_MARKET_PRICE.toPlainString(), PriceSource.BINANCE));
        NormalizedTransaction close = lendingLoopLeg("close", "0xclose", 3,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "lending-loop:0xopen", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", closeQty.toPlainString(),
                        LOOP_CLOSE_MARKET_PRICE.toPlainString(), PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, open, decrease, close));

        service().replayConfirmed();
        List<AssetLedgerPoint> points = capturedLedgerPoints();

        // Final ETH position: full quantity restored, total basis == parked basis (conservation).
        AssetLedgerPoint finalPoint = latestPoint(points, "loop-wallet", NetworkId.BASE, "ETH", null);
        BigDecimal parkedBasis = LOOP_OPEN_QTY.multiply(LOOP_AVCO);
        assertThat(finalPoint.getQuantityAfter()).isCloseTo(LOOP_OPEN_QTY, CENT);
        assertThat(finalPoint.getTotalCostBasisAfterUsd())
                .as("bucket must drain to zero: Σ restored == Σ parked")
                .isCloseTo(parkedBasis, CENT);
        assertThat(finalPoint.getAvcoAfterUsd()).isCloseTo(LOOP_AVCO, CENT);

        // The final CLOSE leg restores the residual 0.5 ETH ≈ $2,169.15 (audit residual).
        BigDecimal closePointBasisDelta = points.stream()
                .filter(p -> "0xclose".equals(p.getTxHash()))
                .map(p -> p.getTotalCostBasisAfterUsd().subtract(p.getTotalCostBasisBeforeUsd()))
                .findFirst()
                .orElseThrow();
        assertThat(closePointBasisDelta).isCloseTo(closeQty.multiply(LOOP_AVCO), CENT);
    }

    @Test
    void lendingLoopLinkedCloseWithEmptyBucketFallsBackToSpotNeverZero() {
        // Linked close (lending-loop: correlation) but NO OPEN parked into the bucket: the restore
        // finds an empty bucket, marks the qty uncovered, and the inbound-shortfall spot fallback
        // books market value — never $0.
        NormalizedTransaction close = lendingLoopLeg("close", "0xclose", 0,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "lending-loop:0xmissing-open", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", LOOP_DECREASE_QTY.toPlainString(),
                        LOOP_CLOSE_MARKET_PRICE.toPlainString(), PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(close));

        service().replayConfirmed();
        List<AssetLedgerPoint> points = capturedLedgerPoints();

        AssetLedgerPoint closePoint = latestPoint(points, "loop-wallet", NetworkId.BASE, "ETH", null);
        BigDecimal spotBasis = LOOP_DECREASE_QTY.multiply(LOOP_CLOSE_MARKET_PRICE);
        assertThat(closePoint.getTotalCostBasisAfterUsd())
                .as("empty-bucket linked close must book spot (never $0)")
                .isCloseTo(spotBasis, CENT);
        assertThat(closePoint.getTotalCostBasisAfterUsd()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void unpairedLendingLoopCloseKeepsTodaysMarketPricing() {
        // No lending-loop correlation: the classifier does not route this through the bucket, so it
        // keeps today's behaviour — market-priced acquisition of the returned collateral.
        NormalizedTransaction close = lendingLoopLeg("close", "0xclose", 0,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, null, NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", LOOP_DECREASE_QTY.toPlainString(),
                        LOOP_CLOSE_MARKET_PRICE.toPlainString(), PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(close));

        service().replayConfirmed();
        List<AssetLedgerPoint> points = capturedLedgerPoints();

        AssetLedgerPoint closePoint = latestPoint(points, "loop-wallet", NetworkId.BASE, "ETH", null);
        BigDecimal marketBasis = LOOP_DECREASE_QTY.multiply(LOOP_CLOSE_MARKET_PRICE);
        assertThat(closePoint.getTotalCostBasisAfterUsd())
                .as("unpaired loop keeps market pricing")
                .isCloseTo(marketBasis, CENT);
    }

    @Test
    void lendingLoopCloseOnDifferentNetworkThanOpenStillRestoresCarriedBasis() {
        // Loop opens on BASE and closes on UNICHAIN: the network-agnostic bucket restores the carried
        // basis on the UNICHAIN position, proving basis carry across networks.
        NormalizedTransaction buy = tx("buy", "0xbuy-eth", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", LOOP_OPEN_QTY.toPlainString(), LOOP_AVCO.toPlainString(), PriceSource.BINANCE));
        buy.setWalletAddress("loop-wallet");
        NormalizedTransaction open = lendingLoopLeg("open", "0xopen", 1,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "lending-loop:0xopen", NetworkId.BASE,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-" + LOOP_OPEN_QTY.toPlainString(), null, null));
        NormalizedTransaction close = lendingLoopLeg("close", "0xclose", 2,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "lending-loop:0xopen", NetworkId.UNICHAIN,
                flow(NormalizedLegRole.TRANSFER, "ETH", LOOP_OPEN_QTY.toPlainString(),
                        LOOP_CLOSE_MARKET_PRICE.toPlainString(), PriceSource.BINANCE));

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(buy, open, close));

        service().replayConfirmed();
        List<AssetLedgerPoint> points = capturedLedgerPoints();

        AssetLedgerPoint closePoint = latestPoint(points, "loop-wallet", NetworkId.UNICHAIN, "ETH", null);
        BigDecimal parkedBasis = LOOP_OPEN_QTY.multiply(LOOP_AVCO);
        assertThat(closePoint.getTotalCostBasisAfterUsd())
                .as("cross-network close restores carried basis, not market")
                .isCloseTo(parkedBasis, CENT);
        assertThat(closePoint.getAvcoAfterUsd()).isCloseTo(LOOP_AVCO, CENT);
        assertThat(closePoint.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
    }
}
