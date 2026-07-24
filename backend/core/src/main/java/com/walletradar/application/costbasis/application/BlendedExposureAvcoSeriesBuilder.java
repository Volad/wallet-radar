package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RC-E3 / B-ETH-00 — blended total-exposure move-basis AVCO series (ADR-061).
 *
 * <p>Read-model-only reconstruction of the ETH-origin basis that has been parked out of the liquid
 * spot-family pool into basis-conserving receipt corridors (LP / lending / GLV / Euler eWETH). It
 * re-includes that parked basis so the blended line does not spike when the liquid pool drains to
 * ≈0. This is <b>additive</b>: the ADR-045 Method-B spot-family series is never altered.</p>
 *
 * <p>The parked pool is reconstructed per grouped timeline event from the <b>family superset</b>
 * ledger points ({@code accountingFamilyIdentity == familyIdentity}, pre-spot-family filter), keyed
 * by {@code correlationId}:</p>
 * <ul>
 *   <li>{@code REALLOCATE_OUT} on a family point carrying a non-blank {@code correlationId} parks
 *       {@code (|quantityΔ| − |uncoveredQuantityΔ|, |costBasisΔ|, |netCostBasisΔ|)} into the pool.</li>
 *   <li>{@code REALLOCATE_IN} whose {@code correlationId} matches a still-open parked slice withdraws
 *       the restored amount, clamped ≥ 0. An unmatched {@code REALLOCATE_IN} (yield accrual) does not
 *       touch the pool — its quantity is already in the liquid pool.</li>
 *   <li>{@code REALLOCATE} <b>and</b> same-family {@code CARRY} participate (RM-1, 2026-07-24). A
 *       {@code CARRY_OUT} corridor (cross-wallet/cross-chain internal transfer, bridge-out,
 *       lending-loop collateral) parks its covered qty + basis keyed by {@code correlationId} (or, for
 *       a bare internal transfer, by {@code lifecycleChainId}) and closes on the matching
 *       {@code CARRY_IN}, keeping the blended denominator whole through the in-flight leg instead of
 *       collapsing to a false $0. {@code DISPOSE}/{@code ACQUIRE} (realized identity change, e.g. C2
 *       staked derivatives per ADR-054) never park. The C2 symbol guard is re-applied when consuming
 *       the superset so wstETH/weETH/cmETH still never enter the blended {@code FAMILY:ETH} pool
 *       (whether they arrive as REALLOCATE or CARRY). This relaxation is for the blended denominator
 *       ONLY — the AVCO/ledger lanes are untouched.</li>
 * </ul>
 *
 * <h2>B-ETH-05 — cross-asset LP-exit slice closure (ADR-061 amendment 2026-07-17)</h2>
 *
 * <p>On a <b>same-asset</b> LP exit (e.g. WETH→WETH) the returned principal lands on
 * {@code FAMILY:ETH} as a {@code REALLOCATE_IN}, so the parked slice closes through the standard
 * un-park path above. On a <b>cross-asset</b> LP exit (e.g. ETH→USDC) the return lands on a
 * different family (USDC) and the receipt burn is a {@code REALLOCATE_OUT} on the
 * {@code FAMILY:LP_RECEIPT} position — {@code FAMILY:ETH} never receives a matching
 * {@code REALLOCATE_IN}, so the parked slice would never close (over-park). Two additional,
 * zero-RPC read-model inputs close it, without disturbing the same-asset or lending-loop paths:</p>
 * <ul>
 *   <li><b>Per-event receipt-burn clamp (never up).</b> The parked-correlation
 *       {@code FAMILY:LP_RECEIPT} burn events ({@code REALLOCATE_OUT}) are merged into the ordered
 *       event stream by {@code (blockTimestamp, transactionIndex, replaySequence)}. At a burn with
 *       receipt {@code qtyBefore=Rb}, {@code qtyAfter=Ra},
 *       {@code remainingFraction = Rb>0 ? Ra/Rb : 0}, the slice quantity is clamped down to
 *       {@code min(slice.qty, parkedGrossCovered × remainingFraction)} (basis/netBasis scaled by the
 *       same factor to preserve AVCO). Same-asset exits already reduced the slice, so the {@code min}
 *       is a no-op there and never double-reduces. Full burn ({@code Ra==0}) closes the slice.</li>
 *   <li><b>Terminal exactness clamp.</b> At the terminal event each parked slice is set to the
 *       authoritative still-open {@code lp_receipt_basis_pools} ETH-origin holding
 *       ({@code qtyHeld − uncoveredQtyHeld}, {@code basisHeldUsd}, {@code netBasisHeldUsd}), or to
 *       zero when the corridor has no open ETH-origin pool (see B-ETH-06 generalization below).</li>
 * </ul>
 *
 * <h2>B-ETH-06 — generalized cross-family closure (ADR-061 amendment 2026-07-17)</h2>
 *
 * <p>The economic rule is: a parked ETH-origin slice must close when the ETH-origin exposure leaves
 * {@code FAMILY:ETH} — whether via (i) an LP-receipt burn (B-ETH-05), (ii) a cross-family settlement
 * (DEX order / bridge) sharing the same {@code correlationId}, or (iii) simply having no open
 * ETH-origin {@code lp_receipt_basis_pools} row at terminal. Two generalizations close the residual
 * non-LP over-park, still zero-RPC and read-model-only:</p>
 * <ul>
 *   <li><b>Generalized terminal clamp (all correlations).</b> The terminal exactness clamp is applied
 *       to <b>every</b> parked {@code REALLOCATE} correlation, not only LP-receipt-managed ones. The
 *       only legitimately-still-parked ETH-origin {@code REALLOCATE} exposure is an open LP pool, so a
 *       parked correlation with no open ETH-origin pool row (DEX-converted, bridge-out, evm deposit) is
 *       clamped to zero. Lending loops use {@code CARRY} and never enter this {@code REALLOCATE} pool,
 *       and are additionally protected by an explicit {@code lending-loop:} prefix guard so their
 *       basis-conserving residual is left untouched.</li>
 *   <li><b>Per-event cross-family settlement close.</b> For a parked correlation with no same-family
 *       {@code REALLOCATE_IN} return and no {@code FAMILY:LP_RECEIPT} burn, the earliest ledger point
 *       sharing that {@code correlationId} on a DIFFERENT accounting family
 *       ({@code accountingFamilyIdentity != familyIdentity}) with basis effect {@code ACQUIRE} /
 *       {@code REALLOCATE_IN} closes the slice at that event's ordering key (full close). This keeps the
 *       line smooth (closes at the conversion, not only at terminal). Mutual exclusion: correlations
 *       owned by the LP-receipt burn path or by a same-family return are skipped (no double-close);
 *       lending-loop correlations never enter the pool.</li>
 * </ul>
 *
 * <p>Blended per event reuses the SAME spot terms {@link AssetLedgerChartService} already computes:
 * {@code blendedMarketAvco = (spotMktBasis + Σ parkedMktBasis) / (spotCoveredQty + Σ parkedCoveredQty)},
 * {@code blendedNetAvco} analogously; {@code null} (UNAVAILABLE, ADR-031) when the denominator ≤ 0.</p>
 */
@Service
class BlendedExposureAvcoSeriesBuilder {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String AVCO_KIND_PRIMARY_FLOW = "PRIMARY_FLOW";
    private static final String AVCO_KIND_UNAVAILABLE = "UNAVAILABLE";
    private static final BigDecimal PARKED_POOL_EPSILON = new BigDecimal("0.00000001");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final String ETH_FAMILY_IDENTITY = "FAMILY:ETH";

    /**
     * Opens a per-projection blended session over the family superset points, with no cross-family
     * settlement closure inputs (backward-compatible entry point for pure {@code REALLOCATE} corridors).
     */
    BlendedSeriesSession newSession(String familyIdentity, List<AssetLedgerPoint> familySupersetPoints) {
        return newSession(familyIdentity, familySupersetPoints, List.of(), List.of(), Map.of());
    }

    /**
     * Opens a per-projection blended session with the B-ETH-05 LP-receipt closure inputs only (no
     * B-ETH-06 cross-family settlement stream). Retained for focused unit tests.
     */
    BlendedSeriesSession newSession(
            String familyIdentity,
            List<AssetLedgerPoint> familySupersetPoints,
            List<AssetLedgerPoint> lpReceiptSupersetPoints,
            Map<String, EthOriginHolding> familyOriginHoldingByCorrelationId
    ) {
        return newSession(familyIdentity, familySupersetPoints, lpReceiptSupersetPoints, List.of(),
                familyOriginHoldingByCorrelationId);
    }

    /**
     * Opens a per-projection blended session with the full B-ETH-05 + B-ETH-06 closure inputs.
     *
     * @param familyIdentity                     the requested family page (e.g. {@code FAMILY:ETH}).
     * @param familySupersetPoints               the family-scoped ledger points (pre-spot-family filter).
     * @param lpReceiptSupersetPoints            the {@code FAMILY:LP_RECEIPT} ledger points for the
     *                                           universe (receipt-burn source; filtered in-memory to
     *                                           parked correlations).
     * @param crossFamilySettlementPoints        B-ETH-06: {@code ACQUIRE}/{@code REALLOCATE_IN} ledger
     *                                           points on a DIFFERENT accounting family sharing a parked
     *                                           correlation id (cross-family conversion settlements, e.g.
     *                                           ETH→wstETH DEX orders). Filtered in-memory to parked
     *                                           correlations with no LP-receipt burn and no same-family
     *                                           return.
     * @param familyOriginHoldingByCorrelationId still-open {@code lp_receipt_basis_pools} family-origin
     *                                           holdings grouped by LP correlation id (terminal clamp).
     */
    BlendedSeriesSession newSession(
            String familyIdentity,
            List<AssetLedgerPoint> familySupersetPoints,
            List<AssetLedgerPoint> lpReceiptSupersetPoints,
            List<AssetLedgerPoint> crossFamilySettlementPoints,
            Map<String, EthOriginHolding> familyOriginHoldingByCorrelationId
    ) {
        return new BlendedSeriesSession(
                familyIdentity,
                familySupersetPoints,
                lpReceiptSupersetPoints,
                crossFamilySettlementPoints,
                familyOriginHoldingByCorrelationId
        );
    }

    /**
     * Blended per-event result. All values are {@code null} when the blended series is UNAVAILABLE
     * (total ETH-origin covered quantity ≤ 0).
     */
    record BlendedPoint(
            BigDecimal marketAvco,
            BigDecimal netAvco,
            BigDecimal coveredQuantity,
            String avcoKind
    ) {
    }

    /**
     * Still-open family-origin holding parked in an LP receipt corridor, projected from
     * {@code lp_receipt_basis_pools} (B-ETH-05 terminal exactness clamp source).
     */
    record EthOriginHolding(
            BigDecimal coveredQuantity,
            BigDecimal marketBasisUsd,
            BigDecimal netBasisUsd
    ) {
    }

    /**
     * Replay-ordering key {@code (blockTimestamp, transactionIndex, replaySequence)} used to merge
     * {@code FAMILY:LP_RECEIPT} burn events into the {@code FAMILY:ETH} timeline stream.
     */
    record OrderingKey(Instant blockTimestamp, Integer transactionIndex, Long replaySequence) {

        static final Comparator<OrderingKey> COMPARATOR = Comparator
                .comparing(OrderingKey::blockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OrderingKey::transactionIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OrderingKey::replaySequence, Comparator.nullsLast(Comparator.naturalOrder()));

        static OrderingKey of(AssetLedgerPoint point) {
            return new OrderingKey(
                    point.getBlockTimestamp(),
                    point.getTransactionIndex(),
                    point.getReplaySequence()
            );
        }
    }

    /**
     * Stateful reconstruction of the ETH-origin parked pool across a single timeline projection.
     * Not thread-safe; a fresh instance is created per projection.
     */
    static final class BlendedSeriesSession {

        private final Map<String, List<AssetLedgerPoint>> reallocateByTxId;
        private final Map<String, ParkedSlice> pool = new LinkedHashMap<>();
        /** Cumulative covered quantity ever parked per correlation (never reduced by un-park). */
        private final Map<String, BigDecimal> parkedGrossCoveredByCorr = new LinkedHashMap<>();
        /**
         * Ordered slice-close stream: {@code FAMILY:LP_RECEIPT} receipt-burn clamps (B-ETH-05) and
         * cross-family settlement full-closes (B-ETH-06, encoded as {@code remainingFraction == 0}).
         */
        private final List<ReceiptBurn> receiptBurns;
        /** Advancing cursor into {@link #receiptBurns} as events are flushed in order. */
        private int receiptBurnCursor = 0;
        /** Still-open family-origin holdings by LP correlation id (terminal exactness source). */
        private final Map<String, EthOriginHolding> familyOriginHoldingByCorrelationId;

        private BlendedSeriesSession(
                String familyIdentity,
                List<AssetLedgerPoint> familySupersetPoints,
                List<AssetLedgerPoint> lpReceiptSupersetPoints,
                List<AssetLedgerPoint> crossFamilySettlementPoints,
                Map<String, EthOriginHolding> familyOriginHoldingByCorrelationId
        ) {
            this.reallocateByTxId = groupReallocatePointsByTransaction(familyIdentity, familySupersetPoints);
            Set<String> parkedCandidateCorrelationIds = collectReallocateCorrelationIds(
                    familyIdentity, familySupersetPoints, AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
            Set<String> familyReturnCorrelationIds = collectReallocateCorrelationIds(
                    familyIdentity, familySupersetPoints, AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
            List<ReceiptBurn> closes = new ArrayList<>(
                    buildReceiptBurns(lpReceiptSupersetPoints, parkedCandidateCorrelationIds));
            Set<String> receiptBurnCorrelationIds = new LinkedHashSet<>();
            for (ReceiptBurn burn : closes) {
                receiptBurnCorrelationIds.add(burn.correlationId());
            }
            // B-ETH-06: a parked corr whose ETH-origin value settled onto a DIFFERENT family (DEX order /
            // bridge) with no same-family return and no LP-receipt burn closes at the settlement event.
            closes.addAll(buildCrossFamilyCloses(
                    familyIdentity,
                    crossFamilySettlementPoints,
                    parkedCandidateCorrelationIds,
                    receiptBurnCorrelationIds,
                    familyReturnCorrelationIds
            ));
            closes.sort(Comparator.comparing(ReceiptBurn::orderingKey, OrderingKey.COMPARATOR));
            this.receiptBurns = closes;
            this.familyOriginHoldingByCorrelationId = familyOriginHoldingByCorrelationId == null
                    ? Map.of()
                    : familyOriginHoldingByCorrelationId;
        }

        /**
         * Applies (in replay order) the park/unpark reallocations of every ledger point belonging to
         * the given event's member transactions to the running parked pool. Each transaction is
         * processed at most once per timeline (transactions map to exactly one grouped event).
         */
        void applyEvent(List<String> memberNormalizedTransactionIds) {
            if (memberNormalizedTransactionIds == null || memberNormalizedTransactionIds.isEmpty()) {
                return;
            }
            for (String txId : memberNormalizedTransactionIds) {
                List<AssetLedgerPoint> points = reallocateByTxId.remove(txId);
                if (points == null) {
                    continue;
                }
                for (AssetLedgerPoint point : points) {
                    applyReallocation(point);
                }
            }
        }

        /**
         * B-ETH-05: flushes every merged {@code FAMILY:LP_RECEIPT} burn clamp whose replay-ordering
         * key is at or before {@code eventKey}. Called once per grouped timeline event (after that
         * event's {@link #applyEvent(List)} park/unpark) so the parked pool reflects every receipt
         * burn that has occurred by the event being plotted. A {@code null} key flushes nothing
         * (used by pure-{@code REALLOCATE} callers without a receipt-burn stream).
         */
        void flushReceiptBurnsUpTo(OrderingKey eventKey) {
            if (eventKey == null) {
                return;
            }
            while (receiptBurnCursor < receiptBurns.size()) {
                ReceiptBurn burn = receiptBurns.get(receiptBurnCursor);
                if (OrderingKey.COMPARATOR.compare(burn.orderingKey(), eventKey) > 0) {
                    break;
                }
                applyReceiptBurnClamp(burn);
                receiptBurnCursor++;
            }
        }

        /**
         * B-ETH-06 generalized terminal exactness clamp: at the final timeline event, sets <b>every</b>
         * parked {@code REALLOCATE} correlation slice to the authoritative still-open
         * {@code lp_receipt_basis_pools} family-origin holding, or to zero when the correlation has no
         * open family-origin pool row. This is correct because the only legitimately-still-parked
         * ETH-origin {@code REALLOCATE} exposure is an open LP pool: any parked correlation whose
         * ETH-origin value has settled to another family (DEX order / bridge) or has otherwise no open
         * pool row is closed to zero. Lending loops use {@code CARRY} (never enter this
         * {@code REALLOCATE} pool) and are additionally protected by an explicit
         * {@code lending-loop:} prefix guard, so the lending lane's basis-conserving residual is left
         * untouched.
         */
        void applyTerminalClamp() {
            // Flush any pending slice-close events that landed after the last plotted event.
            while (receiptBurnCursor < receiptBurns.size()) {
                applyReceiptBurnClamp(receiptBurns.get(receiptBurnCursor));
                receiptBurnCursor++;
            }
            for (String parkKey : parkedGrossCoveredByCorr.keySet()) {
                // B-ETH-02 / B-ETH-06 item 3: a lending-loop corridor keeps its basis-conserving residual
                // at terminal. Under RM-1 a lending-loop CARRY corridor now parks (collateral folded while
                // open) and closes on its LENDING_LOOP_CLOSE/_DECREASE CARRY_IN via unpark; if still open
                // at terminal this guard preserves the genuinely-locked collateral rather than force-zeroing
                // it, so the lending lane stays untouched. A same-asset CARRY corridor that already returned
                // is closed by unpark; a CARRY corridor with NO matching return (bridge leak / dropped
                // transfer) has no open family-origin pool row and is clamped to zero below (B-ETH-06).
                if (isLendingLoopCorrelation(parkKey)) {
                    continue;
                }
                EthOriginHolding holding = familyOriginHoldingByCorrelationId.get(parkKey);
                if (holding == null) {
                    pool.remove(parkKey);
                    continue;
                }
                ParkedSlice slice = pool.computeIfAbsent(parkKey, ignored -> new ParkedSlice());
                slice.coveredQuantity = zeroIfNull(holding.coveredQuantity()).max(BigDecimal.ZERO);
                slice.marketBasisUsd = zeroIfNull(holding.marketBasisUsd()).max(BigDecimal.ZERO);
                slice.netBasisUsd = zeroIfNull(holding.netBasisUsd()).max(BigDecimal.ZERO);
                if (slice.isClosed()) {
                    pool.remove(parkKey);
                }
            }
        }

        /**
         * Blends the current parked pool with the spot terms already computed by the spot Method-B
         * lane, keeping the spot output byte-identical (the spot terms are passed in verbatim).
         */
        BlendedPoint blend(BigDecimal spotCoveredQty, BigDecimal spotMarketBasis, BigDecimal spotNetBasis) {
            BigDecimal parkedCovered = BigDecimal.ZERO;
            BigDecimal parkedMarket = BigDecimal.ZERO;
            BigDecimal parkedNet = BigDecimal.ZERO;
            for (ParkedSlice slice : pool.values()) {
                parkedCovered = parkedCovered.add(slice.coveredQuantity, MC);
                parkedMarket = parkedMarket.add(slice.marketBasisUsd, MC);
                parkedNet = parkedNet.add(slice.netBasisUsd, MC);
            }
            BigDecimal blendedCovered = zeroIfNull(spotCoveredQty).add(parkedCovered, MC);
            if (blendedCovered.signum() <= 0) {
                return new BlendedPoint(null, null, blendedCovered.max(BigDecimal.ZERO), AVCO_KIND_UNAVAILABLE);
            }
            BigDecimal blendedMarketBasis = zeroIfNull(spotMarketBasis).add(parkedMarket, MC);
            BigDecimal blendedNetBasis = zeroIfNull(spotNetBasis).add(parkedNet, MC);
            return new BlendedPoint(
                    blendedMarketBasis.divide(blendedCovered, MC),
                    blendedNetBasis.divide(blendedCovered, MC),
                    blendedCovered,
                    AVCO_KIND_PRIMARY_FLOW
            );
        }

        private void applyReallocation(AssetLedgerPoint point) {
            String parkKey = parkKey(point);
            if (parkKey == null) {
                return;
            }
            AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
            // RM-1 (2026-07-24): same-family CARRY_OUT/CARRY_IN corridors are folded into the blended
            // denominator alongside REALLOCATE (park on OUT, close on the matching IN) so the series
            // holds through an in-flight cross-wallet/cross-chain transfer, bridge-out, or lending-loop
            // collateral leg instead of collapsing to a false $0. AVCO/ledger lanes are untouched.
            if (basisEffect == AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                    || basisEffect == AssetLedgerPoint.BasisEffect.CARRY_OUT) {
                park(parkKey, point);
            } else if (basisEffect == AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                    || basisEffect == AssetLedgerPoint.BasisEffect.CARRY_IN) {
                unpark(parkKey, point);
            }
        }

        private void park(String parkKey, AssetLedgerPoint point) {
            BigDecimal coveredQuantity = coveredReallocationQuantity(point);
            BigDecimal marketBasis = absOrZero(point.getCostBasisDeltaUsd());
            BigDecimal netBasis = absOrZero(netCostBasisDelta(point));
            if (coveredQuantity.signum() <= 0 && marketBasis.signum() <= 0 && netBasis.signum() <= 0) {
                return;
            }
            ParkedSlice slice = pool.computeIfAbsent(parkKey, ignored -> new ParkedSlice());
            slice.coveredQuantity = slice.coveredQuantity.add(coveredQuantity, MC);
            slice.marketBasisUsd = slice.marketBasisUsd.add(marketBasis, MC);
            slice.netBasisUsd = slice.netBasisUsd.add(netBasis, MC);
            parkedGrossCoveredByCorr.merge(parkKey, coveredQuantity, (existing, added) -> existing.add(added, MC));
        }

        private void unpark(String parkKey, AssetLedgerPoint point) {
            ParkedSlice slice = pool.get(parkKey);
            // Unmatched REALLOCATE_IN/CARRY_IN (e.g. pure yield accrual, orphan return) does not touch the pool.
            if (slice == null || slice.isClosed()) {
                return;
            }
            BigDecimal coveredQuantity = coveredReallocationQuantity(point);
            BigDecimal marketBasis = absOrZero(point.getCostBasisDeltaUsd());
            BigDecimal netBasis = absOrZero(netCostBasisDelta(point));
            slice.coveredQuantity = slice.coveredQuantity.subtract(coveredQuantity, MC).max(BigDecimal.ZERO);
            slice.marketBasisUsd = slice.marketBasisUsd.subtract(marketBasis, MC).max(BigDecimal.ZERO);
            slice.netBasisUsd = slice.netBasisUsd.subtract(netBasis, MC).max(BigDecimal.ZERO);
            if (slice.isClosed()) {
                pool.remove(parkKey);
            }
        }

        /**
         * B-ETH-05 close-boundary clamp (never up). Clamps the parked slice quantity down to
         * {@code parkedGrossCovered × remainingFraction} (scaling basis/netBasis by the resulting
         * ratio so AVCO is preserved). Same-asset exits already reduced the slice via
         * {@code FAMILY:ETH REALLOCATE_IN}, so the {@code min} makes this a no-op there.
         */
        private void applyReceiptBurnClamp(ReceiptBurn burn) {
            String correlationId = burn.correlationId();
            ParkedSlice slice = pool.get(correlationId);
            if (slice == null || slice.isClosed()) {
                return;
            }
            BigDecimal grossCovered = parkedGrossCoveredByCorr.getOrDefault(correlationId, BigDecimal.ZERO);
            BigDecimal target = grossCovered.multiply(burn.remainingFraction(), MC).max(BigDecimal.ZERO);
            if (slice.coveredQuantity.compareTo(target) <= 0) {
                return;
            }
            if (target.signum() <= 0) {
                pool.remove(correlationId);
                return;
            }
            BigDecimal factor = target.divide(slice.coveredQuantity, MC);
            slice.coveredQuantity = target;
            slice.marketBasisUsd = slice.marketBasisUsd.multiply(factor, MC);
            slice.netBasisUsd = slice.netBasisUsd.multiply(factor, MC);
            if (slice.isClosed()) {
                pool.remove(correlationId);
            }
        }

        private static Map<String, List<AssetLedgerPoint>> groupReallocatePointsByTransaction(
                String familyIdentity,
                List<AssetLedgerPoint> familySupersetPoints
        ) {
            Map<String, List<AssetLedgerPoint>> grouped = new LinkedHashMap<>();
            if (familySupersetPoints == null) {
                return grouped;
            }
            familySupersetPoints.stream()
                    .filter(point -> isBlendedReallocation(familyIdentity, point))
                    .sorted(Comparator.comparing(
                            AssetLedgerPoint::getReplaySequence,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ))
                    .forEach(point -> grouped
                            .computeIfAbsent(point.getNormalizedTransactionId(), ignored -> new ArrayList<>())
                            .add(point));
            return grouped;
        }

        private static Set<String> collectReallocateCorrelationIds(
                String familyIdentity,
                List<AssetLedgerPoint> familySupersetPoints,
                AssetLedgerPoint.BasisEffect basisEffect
        ) {
            Set<String> correlationIds = new LinkedHashSet<>();
            if (familySupersetPoints == null) {
                return correlationIds;
            }
            for (AssetLedgerPoint point : familySupersetPoints) {
                if (!isBlendedReallocation(familyIdentity, point)) {
                    continue;
                }
                if (point.getBasisEffect() == basisEffect) {
                    correlationIds.add(point.getCorrelationId());
                }
            }
            return correlationIds;
        }

        /**
         * B-ETH-06: builds full-close events (encoded as {@code remainingFraction == 0} clamps) for
         * parked correlations whose ETH-origin value settled onto a DIFFERENT accounting family (DEX
         * order / bridge). Each eligible correlation closes at its EARLIEST cross-family settlement
         * ordering key. Mutual exclusion: correlations already owned by the LP-receipt burn path or by a
         * same-family {@code REALLOCATE_IN} return are skipped (the LP / same-asset lanes own them),
         * preventing any double-close.
         */
        private static List<ReceiptBurn> buildCrossFamilyCloses(
                String familyIdentity,
                List<AssetLedgerPoint> crossFamilySettlementPoints,
                Set<String> parkedCandidateCorrelationIds,
                Set<String> receiptBurnCorrelationIds,
                Set<String> familyReturnCorrelationIds
        ) {
            if (crossFamilySettlementPoints == null || parkedCandidateCorrelationIds.isEmpty()) {
                return List.of();
            }
            Map<String, OrderingKey> earliestByCorrelation = new LinkedHashMap<>();
            for (AssetLedgerPoint point : crossFamilySettlementPoints) {
                if (!isCrossFamilySettlement(familyIdentity, point)) {
                    continue;
                }
                String correlationId = point.getCorrelationId();
                if (correlationId == null || correlationId.isBlank()
                        || !parkedCandidateCorrelationIds.contains(correlationId)
                        || receiptBurnCorrelationIds.contains(correlationId)
                        || familyReturnCorrelationIds.contains(correlationId)
                        || isLendingLoopCorrelation(correlationId)) {
                    continue;
                }
                OrderingKey key = OrderingKey.of(point);
                earliestByCorrelation.merge(correlationId, key,
                        (existing, candidate) -> OrderingKey.COMPARATOR.compare(existing, candidate) <= 0
                                ? existing : candidate);
            }
            List<ReceiptBurn> closes = new ArrayList<>();
            for (Map.Entry<String, OrderingKey> entry : earliestByCorrelation.entrySet()) {
                closes.add(new ReceiptBurn(entry.getKey(), BigDecimal.ZERO, entry.getValue()));
            }
            return closes;
        }

        /**
         * A cross-family settlement lands the ETH-origin value on a DIFFERENT accounting family via an
         * {@code ACQUIRE} or {@code REALLOCATE_IN} basis effect (e.g. {@code FAMILY:WSTETH ACQUIRE} for an
         * ETH→wstETH DEX order sharing the parked correlation id).
         */
        private static boolean isLendingLoopCorrelation(String correlationId) {
            return correlationId != null && correlationId.startsWith(CorrelationContract.LENDING_LOOP_PREFIX);
        }

        private static boolean isCrossFamilySettlement(String familyIdentity, AssetLedgerPoint point) {
            if (point == null) {
                return false;
            }
            String family = point.getAccountingFamilyIdentity();
            if (family == null || family.equals(familyIdentity)) {
                return false;
            }
            AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
            return basisEffect == AssetLedgerPoint.BasisEffect.ACQUIRE
                    || basisEffect == AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
        }

        /**
         * Builds the ordered receipt-burn clamp stream from the {@code FAMILY:LP_RECEIPT} superset,
         * keeping only {@code REALLOCATE_OUT} burns whose correlation id was actually parked out of
         * the requested family. The {@code isLpReceiptSymbol} fallback tolerates a receipt row whose
         * family stamping differs but whose symbol is unambiguously an LP receipt.
         */
        private static List<ReceiptBurn> buildReceiptBurns(
                List<AssetLedgerPoint> lpReceiptSupersetPoints,
                Set<String> parkedCandidateCorrelationIds
        ) {
            List<ReceiptBurn> burns = new ArrayList<>();
            if (lpReceiptSupersetPoints == null || parkedCandidateCorrelationIds.isEmpty()) {
                return burns;
            }
            for (AssetLedgerPoint point : lpReceiptSupersetPoints) {
                if (point.getBasisEffect() != AssetLedgerPoint.BasisEffect.REALLOCATE_OUT) {
                    continue;
                }
                String correlationId = point.getCorrelationId();
                if (correlationId == null || correlationId.isBlank()
                        || !parkedCandidateCorrelationIds.contains(correlationId)) {
                    continue;
                }
                BigDecimal remainingFraction = remainingReceiptFraction(point);
                burns.add(new ReceiptBurn(correlationId, remainingFraction, OrderingKey.of(point)));
            }
            burns.sort(Comparator.comparing(ReceiptBurn::orderingKey, OrderingKey.COMPARATOR));
            return burns;
        }

        private static BigDecimal remainingReceiptFraction(AssetLedgerPoint point) {
            BigDecimal before = absOrZero(point.getQuantityBefore());
            BigDecimal after = absOrZero(point.getQuantityAfter());
            if (before.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal fraction = after.divide(before, MC);
            if (fraction.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return fraction.min(ONE);
        }

        private static boolean isBlendedReallocation(String familyIdentity, AssetLedgerPoint point) {
            if (point == null || point.getNormalizedTransactionId() == null) {
                return false;
            }
            AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
            // RM-1: REALLOCATE (LP / receipt corridors) AND same-family CARRY (internal transfer,
            // bridge-out, lending-loop collateral) both fold into the blended denominator. CARRY was
            // previously excluded, which is why an in-flight CARRY corridor floored the series to $0.
            if (basisEffect != AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                    && basisEffect != AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                    && basisEffect != AssetLedgerPoint.BasisEffect.CARRY_OUT
                    && basisEffect != AssetLedgerPoint.BasisEffect.CARRY_IN) {
                return false;
            }
            // A CARRY corridor may carry no correlationId (bare internal transfer); fall back to the
            // transfer/lifecycle chain id so both legs still share one park key.
            if (parkKey(point) == null) {
                return false;
            }
            // Re-apply ONLY the C2 guard when consuming the superset (never the LP-receipt-symbol drop):
            // C2 staked/value-accruing derivatives (wstETH/weETH/cmETH, own families per ADR-054) must
            // never enter the blended FAMILY:ETH pool. Non-ETH families are unaffected (no C2 concept).
            return !(ETH_FAMILY_IDENTITY.equals(familyIdentity)
                    && AccountingAssetClassificationSupport.isC2DistinctAsset(point.getAssetSymbol()));
        }

        /**
         * RM-1: the pool key for a park/unpark leg — the {@code correlationId} when present (LP
         * corridors, lending-loop {@code lending-loop:{openTxHash}}, bridge {@code bridge:*}), else the
         * {@code lifecycleChainId} for a bare internal transfer that shares no correlation. Both legs of
         * one corridor resolve to the same key so the OUT parks and the matching IN closes it. Returns
         * {@code null} when neither identifier is present (never parks — matches the legacy blank-corr
         * no-op).
         */
        private static String parkKey(AssetLedgerPoint point) {
            String correlationId = point.getCorrelationId();
            if (correlationId != null && !correlationId.isBlank()) {
                return correlationId;
            }
            String lifecycleChainId = point.getLifecycleChainId();
            if (lifecycleChainId != null && !lifecycleChainId.isBlank()) {
                return lifecycleChainId;
            }
            return null;
        }

        private static BigDecimal coveredReallocationQuantity(AssetLedgerPoint point) {
            BigDecimal quantity = absOrZero(point.getQuantityDelta());
            BigDecimal uncovered = absOrZero(point.getUncoveredQuantityDelta());
            return quantity.subtract(uncovered, MC).max(BigDecimal.ZERO);
        }

        private static BigDecimal netCostBasisDelta(AssetLedgerPoint point) {
            return point.getNetCostBasisDeltaUsd() != null
                    ? point.getNetCostBasisDeltaUsd()
                    : point.getCostBasisDeltaUsd();
        }
    }

    /**
     * A merged parked-correlation slice-close event. Two producers share this shape:
     * <ul>
     *   <li>B-ETH-05 {@code FAMILY:LP_RECEIPT} burn: {@code remainingFraction} is the surviving receipt
     *       fraction (partial burns scale the slice down proportionally).</li>
     *   <li>B-ETH-06 cross-family settlement: encoded as {@code remainingFraction == 0} (full close at
     *       the settlement event).</li>
     * </ul>
     */
    private record ReceiptBurn(String correlationId, BigDecimal remainingFraction, OrderingKey orderingKey) {
    }

    private static final class ParkedSlice {
        private BigDecimal coveredQuantity = BigDecimal.ZERO;
        private BigDecimal marketBasisUsd = BigDecimal.ZERO;
        private BigDecimal netBasisUsd = BigDecimal.ZERO;

        private boolean isClosed() {
            return coveredQuantity.compareTo(PARKED_POOL_EPSILON) < 0
                    && marketBasisUsd.compareTo(PARKED_POOL_EPSILON) < 0
                    && netBasisUsd.compareTo(PARKED_POOL_EPSILON) < 0;
        }
    }

    private static BigDecimal absOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
