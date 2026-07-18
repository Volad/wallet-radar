package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.application.port.AssetLedgerReadPort;
import com.walletradar.application.costbasis.breakeven.BreakEvenAttributionService;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AssetLedgerQueryService implements AssetLedgerReadPort {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal GAS_ONLY_BASIS_THRESHOLD = new BigDecimal("0.00000001");
    /**
     * B-ETH-04: LP-exit inbound legs that restore zero cost basis on a residual position whose
     * total cost basis after the event is below this USD threshold are treated as dust; their
     * spurious diluted AVCO is reported as undefined (null) in the read path.
     */
    private static final BigDecimal LP_EXIT_ZERO_BASIS_DUST_THRESHOLD_USD = new BigDecimal("1.00");
    /** RC-E3 / B-ETH-05: family identity for LP receipt positions (cross-asset LP-exit closure source). */
    private static final String LP_RECEIPT_FAMILY_IDENTITY = AccountingAssetFamilySupport.FAMILY_LP_RECEIPT;
    private static final String ETH_FAMILY_IDENTITY = "FAMILY:ETH";
    private final UserSessionRepository userSessionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final AssetLedgerChartService chartService;
    private final AssetLedgerReconciliationService reconciliationService;
    private final LpReceiptBasisPoolService lpReceiptBasisPoolService;
    private final BreakEvenCalculator breakEvenCalculator;
    private final BreakEvenAttributionService breakEvenAttributionService;

    @Override
    public Optional<SessionAssetLedgerView> findSessionFamilyLedger(String sessionId, String familyIdentity) {
        if (sessionId == null || sessionId.isBlank() || familyIdentity == null || familyIdentity.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim()).map(session -> toView(session, familyIdentity.trim()));
    }

    private SessionAssetLedgerView toView(UserSession session, String familyIdentity) {
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<AssetLedgerPoint> points = universeScope.accountingUniverseId() == null || universeScope.accountingUniverseId().isBlank()
                ? List.of()
                : assetLedgerPointRepository
                .findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                        universeScope.accountingUniverseId(),
                        familyIdentity
                );
        Map<String, NormalizedTransaction> normalizedById = findNormalizedTransactions(points);
        List<AssetLedgerPoint> timelinePoints = points.stream()
                .filter(point -> AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                        familyIdentity,
                        point.getAssetSymbol()
                ))
                .toList();
        // RC-E3 / B-ETH-05 (ADR-061): zero-RPC cross-asset LP-exit closure inputs. Both reuse existing
        // indexes: the FAMILY:LP_RECEIPT superset (asset_ledger_universe_family_order_idx) supplies the
        // receipt-burn stream, and the lp_receipt_basis_pools snapshot supplies the terminal exactness
        // clamp target. Filtering to the parked correlations happens inside the builder.
        List<AssetLedgerPoint> lpReceiptSupersetPoints = loadLpReceiptSupersetPoints(universeScope.accountingUniverseId());
        // B-ETH-06 (ADR-061): zero-RPC cross-family settlement points for parked correlations. Reuses
        // the existing asset_ledger_universe_tx_idx via the parked REALLOCATE_OUT transactions; filtering
        // to a DIFFERENT family + ACQUIRE/REALLOCATE_IN happens here so the builder receives only
        // settlement candidates.
        List<AssetLedgerPoint> crossFamilySettlementPoints =
                loadCrossFamilySettlementPoints(universeScope.accountingUniverseId(), familyIdentity, points);
        Map<String, BlendedExposureAvcoSeriesBuilder.EthOriginHolding> familyOriginHoldingByCorrelationId =
                loadFamilyOriginHoldingsByCorrelationId(universeScope.accountingUniverseId(), familyIdentity);
        // ADR-062 §3: load the attributed cluster children ONCE (reused for the scalar header offset,
        // the woven chart-series offset, and the family-member-symbol hint). Zero RPC; each child family
        // rides the existing asset_ledger_universe_family_order_idx.
        ChildAttributionData childAttribution =
                loadChildAttributionData(universeScope.accountingUniverseId(), familyIdentity);
        AssetLedgerChartService.ChartProjection chartProjection =
                chartService.buildTimelineProjection(
                        familyIdentity,
                        timelinePoints,
                        points,
                        lpReceiptSupersetPoints,
                        crossFamilySettlementPoints,
                        familyOriginHoldingByCorrelationId,
                        normalizedById,
                        childAttribution.pnlEvents()
                );
        CurrentStateView currentState = reconciliationService.currentStateView(
                session,
                familyIdentity,
                points,
                chartProjection.totalRealisedPnlUsd(),
                chartProjection.totalNetRealisedPnlUsd(),
                chartProjection.totalGasPaidUsd()
        );
        currentState = enrichWithBreakEven(
                currentState,
                familyIdentity,
                representativeSymbol(familyIdentity, points),
                familyMemberSymbols(points, childAttribution.childSymbols()),
                childAttribution.childInputs()
        );
        FullSessionCurrentView fullSessionCurrent = reconciliationService.fullSessionCurrentView(points, familyIdentity);
        return new SessionAssetLedgerView(
                session.getId(),
                familyIdentity,
                currentState,
                fullSessionCurrent,
                chartProjection.timeline(),
                chartProjection.overlays(),
                chartService.mapRawPoints(points)
        );
    }

    private Map<String, NormalizedTransaction> findNormalizedTransactions(List<AssetLedgerPoint> points) {
        List<String> normalizedIds = points.stream()
                .map(AssetLedgerPoint::getNormalizedTransactionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) return Map.of();
        Map<String, NormalizedTransaction> normalizedById = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : normalizedTransactionRepository.findAllById(normalizedIds)) {
            normalizedById.put(transaction.getId(), transaction);
        }
        return normalizedById;
    }

    /**
     * ADR-062 §3: enriches the move-basis header with break-even (effective-cost) fields. The SELF
     * family uses the reconciled current-state basis/coverage plus the chart-projected realized P&L.
     * Cross-family attribution is done correctly here: when the viewed family is a parent target
     * (e.g. {@code FAMILY:ETH}), each redirected child family's Market-lane realized P&L is loaded via
     * the existing {@code asset_ledger_universe_family_order_idx} and credited to the parent, so
     * exited children (e.g. cmETH → ETH) still lower the parent's break-even. Zero RPC / zero Mongo
     * mutation.
     */
    private CurrentStateView enrichWithBreakEven(
            CurrentStateView currentState,
            String familyIdentity,
            String representativeSymbol,
            List<String> familyMemberSymbols,
            List<BreakEvenCalculator.FamilyBreakEvenInput> childInputs
    ) {
        List<BreakEvenCalculator.FamilyBreakEvenInput> inputs = new ArrayList<>();
        inputs.add(new BreakEvenCalculator.FamilyBreakEvenInput(
                familyIdentity,
                representativeSymbol,
                zeroIfNull(currentState.totalCostBasisUsd()),
                zeroIfNull(currentState.coveredQuantity()),
                zeroIfNull(currentState.realisedPnlUsd()),
                zeroIfNull(currentState.netRealisedPnlUsd())
        ));
        inputs.addAll(childInputs);
        BreakEvenCalculator.BreakEvenResult result = breakEvenCalculator.compute(inputs).get(familyIdentity);
        if (result == null) {
            return currentState.withBreakEven(null, null, null, null, familyMemberSymbols);
        }
        return currentState.withBreakEven(
                result.breakEvenUsd(),
                result.lockedSurplusUsd(),
                result.incomeReceivedUsd(),
                result.attributionTargetFamily(),
                familyMemberSymbols
        );
    }

    /**
     * ADR-062 §3: loads the attributed cluster children once and derives the three read-model
     * artifacts that depend on them — the scalar break-even inputs (Market/Net realized P&L totals per
     * child family), the woven chart-series offset events (per-point Market-lane realized P&L keyed by
     * replay ordering), and the child asset symbols for the header member hint. Zero RPC / zero Mongo
     * mutation; each child family reuses the existing {@code asset_ledger_universe_family_order_idx}.
     */
    private ChildAttributionData loadChildAttributionData(String accountingUniverseId, String familyIdentity) {
        List<BreakEvenCalculator.FamilyBreakEvenInput> childInputs = new ArrayList<>();
        List<AssetLedgerChartService.AttributedRealizedPnlEvent> pnlEvents = new ArrayList<>();
        LinkedHashSet<String> childSymbols = new LinkedHashSet<>();
        for (String childFamily : breakEvenAttributionService.resolveChildFamilies(familyIdentity)) {
            BigDecimal marketPnl = BigDecimal.ZERO;
            BigDecimal netPnl = BigDecimal.ZERO;
            if (accountingUniverseId != null && !accountingUniverseId.isBlank()) {
                List<AssetLedgerPoint> childPoints = assetLedgerPointRepository
                        .findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                                accountingUniverseId,
                                childFamily
                        );
                for (AssetLedgerPoint point : childPoints) {
                    BigDecimal marketDelta = zeroIfNull(point.getRealisedPnlDeltaUsd());
                    marketPnl = marketPnl.add(marketDelta, MC);
                    netPnl = netPnl.add(zeroIfNull(point.getNetRealisedPnlDeltaUsd()), MC);
                    if (marketDelta.signum() != 0) {
                        pnlEvents.add(new AssetLedgerChartService.AttributedRealizedPnlEvent(
                                point.getBlockTimestamp(),
                                point.getTransactionIndex(),
                                point.getReplaySequence(),
                                marketDelta
                        ));
                    }
                    addSymbol(childSymbols, point.getAssetSymbol());
                }
            }
            childInputs.add(new BreakEvenCalculator.FamilyBreakEvenInput(
                    childFamily,
                    BreakEvenAttributionService.representativeSymbolFor(childFamily, null),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    marketPnl,
                    netPnl
            ));
        }
        return new ChildAttributionData(childInputs, pnlEvents, List.copyOf(childSymbols));
    }

    /**
     * ADR-062 §3: distinct member asset symbols actually present in the ledger for the viewed family
     * and its attributed children. The viewed family's own symbols come first (in ledger order),
     * followed by the children's; symbols are uppercased and blanks dropped.
     */
    private static List<String> familyMemberSymbols(List<AssetLedgerPoint> points, List<String> childSymbols) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (AssetLedgerPoint point : points) {
            addSymbol(symbols, point.getAssetSymbol());
        }
        for (String childSymbol : childSymbols) {
            addSymbol(symbols, childSymbol);
        }
        return List.copyOf(symbols);
    }

    private static void addSymbol(LinkedHashSet<String> symbols, String symbol) {
        if (symbol == null) {
            return;
        }
        String normalized = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.isBlank()) {
            symbols.add(normalized);
        }
    }

    private record ChildAttributionData(
            List<BreakEvenCalculator.FamilyBreakEvenInput> childInputs,
            List<AssetLedgerChartService.AttributedRealizedPnlEvent> pnlEvents,
            List<String> childSymbols
    ) {
    }

    private static String representativeSymbol(String familyIdentity, List<AssetLedgerPoint> points) {
        for (AssetLedgerPoint point : points) {
            if (point.getFamilyDisplaySymbol() != null && !point.getFamilyDisplaySymbol().isBlank()) {
                return point.getFamilyDisplaySymbol();
            }
        }
        return BreakEvenAttributionService.representativeSymbolFor(familyIdentity, null);
    }

    /**
     * RC-E3 / B-ETH-05: loads the {@code FAMILY:LP_RECEIPT} ledger-point superset for the universe
     * (receipt-burn source for the cross-asset LP-exit slice closure). Zero RPC; uses the existing
     * {@code asset_ledger_universe_family_order_idx}.
     */
    private List<AssetLedgerPoint> loadLpReceiptSupersetPoints(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return List.of();
        }
        return assetLedgerPointRepository
                .findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                        accountingUniverseId,
                        LP_RECEIPT_FAMILY_IDENTITY
                );
    }

    /**
     * B-ETH-06: loads cross-family settlement ledger points for the requested family's parked
     * correlations. A parked correlation whose ETH-origin value settled onto a DIFFERENT accounting
     * family (e.g. an ETH→wstETH DEX order) has an {@code ACQUIRE}/{@code REALLOCATE_IN} on that other
     * family sharing the correlation id. We scope the query by the parked slices'
     * {@code correlationId}s (NOT by the parked-out {@code normalizedTransactionId}) so settlements
     * that land in a SEPARATE transaction from the escrow/park leg are captured too. Zero RPC and no
     * new index: the single-universe query rides the {@code accountingUniverseId} leading prefix of the
     * existing compound indexes and filters {@code correlationId} during the indexed universe scan;
     * results are filtered in-memory to a different family + settlement basis effect. Bridge/EVM
     * out-only corridors (no settlement anywhere) are not returned here and are closed by the
     * generalized terminal clamp.
     */
    private List<AssetLedgerPoint> loadCrossFamilySettlementPoints(
            String accountingUniverseId,
            String familyIdentity,
            List<AssetLedgerPoint> familyPoints
    ) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank() || familyPoints.isEmpty()) {
            return List.of();
        }
        Set<String> parkedCorrelationIds = new LinkedHashSet<>();
        for (AssetLedgerPoint point : familyPoints) {
            if (point.getBasisEffect() != AssetLedgerPoint.BasisEffect.REALLOCATE_OUT) {
                continue;
            }
            String correlationId = point.getCorrelationId();
            if (correlationId == null || correlationId.isBlank()) {
                continue;
            }
            parkedCorrelationIds.add(correlationId);
        }
        if (parkedCorrelationIds.isEmpty()) {
            return List.of();
        }
        return assetLedgerPointRepository
                .findAllByAccountingUniverseIdAndCorrelationIdIn(accountingUniverseId, parkedCorrelationIds)
                .stream()
                .filter(point -> point.getAccountingFamilyIdentity() != null
                        && !point.getAccountingFamilyIdentity().equals(familyIdentity))
                .filter(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.ACQUIRE
                        || point.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .filter(point -> point.getCorrelationId() != null && !point.getCorrelationId().isBlank())
                .toList();
    }

    /**
     * RC-E3 / B-ETH-05: projects the still-open {@code lp_receipt_basis_pools} rows whose principal
     * asset resolves to the requested family (ETH-origin for {@code FAMILY:ETH}; C2 derivatives
     * excluded) into per-LP-correlation family-origin holdings used by the terminal exactness clamp.
     * Zero RPC; reuses {@link LpReceiptBasisPoolService#loadAllForUniverse(String)}.
     */
    private Map<String, BlendedExposureAvcoSeriesBuilder.EthOriginHolding> loadFamilyOriginHoldingsByCorrelationId(
            String accountingUniverseId,
            String familyIdentity
    ) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return Map.of();
        }
        Map<String, BigDecimal[]> aggregate = new LinkedHashMap<>();
        for (LpReceiptBasisPool pool : lpReceiptBasisPoolService.loadAllForUniverse(accountingUniverseId).values()) {
            if (!isFamilyOriginPool(pool, familyIdentity)) {
                continue;
            }
            String correlationId = pool.getLpCorrelationId();
            if (correlationId == null || correlationId.isBlank()) {
                continue;
            }
            BigDecimal covered = zeroIfNull(pool.getQtyHeld())
                    .subtract(zeroIfNull(pool.getUncoveredQtyHeld()), MC)
                    .max(BigDecimal.ZERO);
            BigDecimal marketBasis = zeroIfNull(pool.getBasisHeldUsd()).max(BigDecimal.ZERO);
            BigDecimal netBasis = (pool.getNetBasisHeldUsd() != null ? pool.getNetBasisHeldUsd() : pool.getBasisHeldUsd());
            netBasis = zeroIfNull(netBasis).max(BigDecimal.ZERO);
            BigDecimal[] slot = aggregate.computeIfAbsent(
                    correlationId,
                    ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}
            );
            slot[0] = slot[0].add(covered, MC);
            slot[1] = slot[1].add(marketBasis, MC);
            slot[2] = slot[2].add(netBasis, MC);
        }
        Map<String, BlendedExposureAvcoSeriesBuilder.EthOriginHolding> holdings = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal[]> entry : aggregate.entrySet()) {
            BigDecimal[] slot = entry.getValue();
            if (slot[0].signum() <= 0 && slot[1].signum() <= 0 && slot[2].signum() <= 0) {
                continue;
            }
            holdings.put(entry.getKey(), new BlendedExposureAvcoSeriesBuilder.EthOriginHolding(slot[0], slot[1], slot[2]));
        }
        return holdings;
    }

    private static boolean isFamilyOriginPool(LpReceiptBasisPool pool, String familyIdentity) {
        if (pool == null) {
            return false;
        }
        String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(
                pool.getAssetSymbol(), pool.getAssetContract());
        if (continuityIdentity == null || !continuityIdentity.equals(familyIdentity)) {
            return false;
        }
        // Mirror the builder's C2 guard: staked/value-accruing ETH derivatives (ADR-054) are their own
        // families and must never be attributed back to the blended FAMILY:ETH pool.
        return !(ETH_FAMILY_IDENTITY.equals(familyIdentity)
                && AccountingAssetClassificationSupport.isC2DistinctAsset(pool.getAssetSymbol()));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static BigDecimal gasOnlyAvcoAfter(AssetLedgerPoint point) {
        if (point == null) {
            return null;
        }
        // B-ETH-04: zero-cost-basis LP-exit inbound on a dust residual → AVCO undefined (ADR-031).
        if (isZeroBasisLpExitDustRestoration(point, LP_EXIT_ZERO_BASIS_DUST_THRESHOLD_USD)) {
            return null;
        }
        BigDecimal basisBacked = point.getBasisBackedQuantityAfter();
        if (basisBacked == null || basisBacked.compareTo(GAS_ONLY_BASIS_THRESHOLD) >= 0) {
            return point.getAvcoAfterUsd();
        }
        AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
        String normalizedType = point.getNormalizedType();
        boolean isGasOnlyEvent = basisEffect == AssetLedgerPoint.BasisEffect.GAS_ONLY
                || "SPONSORED_GAS_IN".equals(normalizedType)
                || "REWARD_CLAIM".equals(normalizedType);
        return isGasOnlyEvent ? null : point.getAvcoAfterUsd();
    }

    /**
     * B-ETH-04 dust guard (read-path presentation filter, ADR-031 undefined-representation).
     *
     * <p>Returns {@code true} for an LP-exit inbound restoration point that restored <b>zero</b>
     * cost basis (e.g. an unpriced {@code LP_FEE_INCOME} leg booked as a zero-cost acquisition)
     * while the position's total cost basis after the event is a sub-threshold dust residual. Such
     * a leg dilutes the covered-AVCO denominator without adding basis, producing a spurious
     * per-event AVCO (the audited symptom: seq 4875 on {@code FAMILY:ETH} reporting ≈$249 for a
     * sub-$1 dust residual). The read path reports that AVCO as {@code null} (UNAVAILABLE).</p>
     *
     * <p>Scope discipline: fires only when {@code costBasisDeltaUsd == 0} (LP exits that DO restore
     * basis are untouched) and only for dust residuals (non-dust fee-income legs keep their
     * genuine diluted AVCO), so the LP baseline stays byte-identical except for the zero-cbd dust
     * case. Zero replay impact — {@code asset_ledger_points} are not modified.</p>
     */
    static boolean isZeroBasisLpExitDustRestoration(AssetLedgerPoint point, BigDecimal dustThresholdUsd) {
        if (point == null) {
            return false;
        }
        String normalizedType = point.getNormalizedType();
        boolean isLpExit = "LP_EXIT".equals(normalizedType)
                || "LP_EXIT_PARTIAL".equals(normalizedType)
                || "LP_EXIT_FINAL".equals(normalizedType);
        if (!isLpExit) {
            return false;
        }
        AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
        boolean inboundRestore = basisEffect == AssetLedgerPoint.BasisEffect.ACQUIRE
                || basisEffect == AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
        if (!inboundRestore) {
            return false;
        }
        BigDecimal quantityDelta = point.getQuantityDelta();
        if (quantityDelta == null || quantityDelta.signum() <= 0) {
            return false;
        }
        BigDecimal costBasisDelta = point.getCostBasisDeltaUsd();
        if (costBasisDelta == null || costBasisDelta.signum() != 0) {
            return false;
        }
        BigDecimal totalCostBasisAfter = point.getTotalCostBasisAfterUsd();
        return totalCostBasisAfter == null
                || totalCostBasisAfter.abs().compareTo(dustThresholdUsd) < 0;
    }

    public record SessionAssetLedgerView(String sessionId, String familyIdentity, CurrentStateView current,
                                         FullSessionCurrentView fullSessionCurrent, List<TimelineEntryView> timeline,
                                         List<EventOverlayView> events, List<LedgerPointView> ledgerPoints) {}
    public record FullSessionCurrentView(BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal uncoveredQuantity,
                                         BigDecimal totalCostBasisUsd, BigDecimal avcoUsd,
                                         BigDecimal netTotalCostBasisUsd, BigDecimal netAvcoUsd) {}
    public record CurrentStateView(BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal uncoveredQuantity,
                                   BigDecimal totalCostBasisUsd, BigDecimal avcoUsd, BigDecimal netTotalCostBasisUsd,
                                   BigDecimal netAvcoUsd, BigDecimal realisedPnlUsd, BigDecimal netRealisedPnlUsd,
                                   BigDecimal gasPaidUsd, List<UncoveredBucketView> uncoveredBuckets,
                                   List<ShortfallSourceView> shortfallSources,
                                   // ADR-062 break-even (effective-cost) header fields (nullable, read-model only).
                                   BigDecimal breakEvenUsd, BigDecimal lockedSurplusUsd,
                                   BigDecimal incomeReceivedUsd, String attributionTargetFamily,
                                   // ADR-062 §3 header hint: real member symbols of the family + attributed children.
                                   List<String> familyMemberSymbols) {
        public CurrentStateView withBreakEven(BigDecimal breakEvenUsd, BigDecimal lockedSurplusUsd,
                                              BigDecimal incomeReceivedUsd, String attributionTargetFamily,
                                              List<String> familyMemberSymbols) {
            return new CurrentStateView(quantity, coveredQuantity, uncoveredQuantity, totalCostBasisUsd, avcoUsd,
                    netTotalCostBasisUsd, netAvcoUsd, realisedPnlUsd, netRealisedPnlUsd, gasPaidUsd, uncoveredBuckets,
                    shortfallSources, breakEvenUsd, lockedSurplusUsd, incomeReceivedUsd, attributionTargetFamily,
                    familyMemberSymbols);
        }
    }
    public record UncoveredBucketView(String walletAddress, String networkId, String assetSymbol, String assetContract,
                                      BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal uncoveredQuantity,
                                      String uncoveredReason, String latestTxHash, String latestNormalizedType,
                                      String latestBasisEffect, String latestProtocolName, boolean hasIncompleteHistory,
                                      boolean hasUnresolvedFlags, Integer unresolvedFlagCount) {}
    public record ShortfallSourceView(String walletAddress, String networkId, String txHash, Instant blockTimestamp,
                                      String normalizedType, String protocolName, BigDecimal quantityShortfall) {}
    public record TimelineEntryView(Instant blockTimestamp, String txHash, String eventGroupId,
                                    String normalizedTransactionId, String normalizedType, String protocolName,
                                    String lifecycleKind, String lifecycleStage, List<String> basisEffects,
                                    BigDecimal quantityDelta, BigDecimal costBasisDeltaUsd,
                                    BigDecimal realisedPnlDeltaUsd, BigDecimal gasDeltaUsd, BigDecimal quantityAfter,
                                    BigDecimal coveredQuantityAfter, BigDecimal uncoveredQuantityAfter,
                                    BigDecimal totalCostBasisAfterUsd, BigDecimal avcoBeforeUsd, BigDecimal avcoAfterUsd,
                                    BigDecimal netTotalCostBasisAfterUsd, BigDecimal netAvcoBeforeUsd,
                                    BigDecimal netAvcoAfterUsd, String avcoKind, String fromAddress, String toAddress,
                                    List<String> memberNormalizedTransactionIds,
                                    // RC-E3 / ADR-061 — additive blended total-exposure AVCO series (nullable).
                                    BigDecimal blendedAvcoBeforeUsd, BigDecimal blendedAvcoAfterUsd,
                                    BigDecimal blendedNetAvcoBeforeUsd, BigDecimal blendedNetAvcoAfterUsd,
                                    BigDecimal blendedCoveredQuantityAfter, BigDecimal liquidQuantityAfter,
                                    String blendedAvcoKind,
                                    // ADR-062 §3 — effective-cost (break-even) time series (nullable).
                                    BigDecimal effectiveCostAfterUsd) {}
    public record EventOverlayView(String eventGroupId, String normalizedTransactionId, String txHash,
                                   Instant blockTimestamp, String normalizedType, String protocolName,
                                   String lifecycleKind, List<String> walletAddresses, List<String> networkIds,
                                   List<EventFlowView> flows, String fromAddress, String toAddress,
                                   List<String> memberNormalizedTransactionIds) {}
    public record EventFlowView(String role, String assetContract, String assetSymbol, BigDecimal quantityDelta,
                                BigDecimal unitPriceUsd, BigDecimal valueUsd, String priceSource, Integer logIndex) {}
    public record LedgerPointView(String walletAddress, String networkId, String accountingAssetIdentity,
                                  String accountingFamilyIdentity, String familyDisplaySymbol, String assetSymbol,
                                  String assetContract, String normalizedTransactionId, String txHash,
                                  String correlationId, String lifecycleChainId, String matchedCounterparty,
                                  Instant blockTimestamp, Long replaySequence, String normalizedType,
                                  String lifecycleKind, String lifecycleStage, String basisEffect, String protocolName,
                                  BigDecimal quantityDelta, BigDecimal costBasisDeltaUsd, BigDecimal realisedPnlDeltaUsd,
                                  BigDecimal gasDeltaUsd, BigDecimal quantityBefore, BigDecimal quantityAfter,
                                  BigDecimal totalCostBasisBeforeUsd, BigDecimal totalCostBasisAfterUsd,
                                  BigDecimal avcoBeforeUsd, BigDecimal avcoAfterUsd,
                                  BigDecimal netTotalCostBasisBeforeUsd, BigDecimal netTotalCostBasisAfterUsd,
                                  BigDecimal netAvcoBeforeUsd, BigDecimal netAvcoAfterUsd,
                                  BigDecimal netCostBasisDeltaUsd, BigDecimal netRealisedPnlDeltaUsd,
                                  BigDecimal basisBackedQuantityAfter, BigDecimal uncoveredQuantityDelta,
                                  BigDecimal quantityShortfallAfter, BigDecimal uncoveredQuantityAfter,
                                  Boolean hasIncompleteHistoryAfter, Boolean hasUnresolvedFlagsAfter,
                                  Integer unresolvedFlagCountAfter) {}
}
