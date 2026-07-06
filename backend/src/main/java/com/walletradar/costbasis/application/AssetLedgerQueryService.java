package com.walletradar.costbasis.application;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.support.AssetLedgerSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.integration.bybit.BybitLiveBalanceService;
import com.walletradar.ingestion.wallet.query.BybitUmbrellaSupport;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Session-scoped read service for immutable asset-ledger history.
 */
@Service
@RequiredArgsConstructor
public class AssetLedgerQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;

    /** ADR-045 chart-series AVCO kinds. FAMILY_ROLLUP is never emitted on the plotted line. */
    private static final String AVCO_KIND_PRIMARY_FLOW = "PRIMARY_FLOW";
    private static final String AVCO_KIND_UNAVAILABLE = "UNAVAILABLE";

    private final UserSessionRepository userSessionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final MongoOperations mongoOperations;
    private final BybitLiveBalanceService bybitLiveBalanceService;

    public Optional<SessionAssetLedgerView> findSessionFamilyLedger(String sessionId, String familyIdentity) {
        if (sessionId == null || sessionId.isBlank() || familyIdentity == null || familyIdentity.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> toView(session, familyIdentity.trim()));
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

        List<LedgerPointView> rawPoints = points.stream()
                .map(this::toRawPoint)
                .toList();

        List<AssetLedgerPoint> timelinePoints = points.stream()
                .filter(point -> AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                        familyIdentity,
                        point.getAssetSymbol()
                ))
                .toList();
        List<EventAccumulator> groupedEvents = groupPoints(timelinePoints, normalizedById);
        List<DisplayEventAccumulator> displayEvents = collapseDisplayEvents(groupedEvents);
        AggregatedState state = new AggregatedState();
        // ADR-045 Method B: covered-weighted per-bucket-snapshot family AVCO series.
        // Keyed by the same BucketKey used for the current-state rollups; each bucket keeps its
        // latest stored avcoAfter / netAvcoAfter and covered qty. The chart series is
        // Σ coveredᵢ·avcoᵢ / Σ coveredᵢ over the live bucket map after each grouped event.
        Map<BucketKey, BucketAvcoState> liveAvcoBuckets = new LinkedHashMap<>();
        BigDecimal previousAvcoAfterUsd = null;
        BigDecimal previousNetAvcoAfterUsd = null;
        List<TimelineEntryView> timeline = new ArrayList<>();
        List<EventOverlayView> overlays = new ArrayList<>();
        for (DisplayEventAccumulator accumulator : displayEvents) {
            state.apply(accumulator);
            applyMethodBBuckets(familyIdentity, accumulator.memberPoints(), liveAvcoBuckets);
            CoveredWeightedAvco series = coveredWeightedFamilyAvco(liveAvcoBuckets);
            BigDecimal avcoAfterUsd = series.taxAvco();
            BigDecimal netAvcoAfterUsd = series.netAvco();
            String avcoKind = avcoAfterUsd == null ? AVCO_KIND_UNAVAILABLE : AVCO_KIND_PRIMARY_FLOW;
            timeline.add(new TimelineEntryView(
                    accumulator.blockTimestamp,
                    accumulator.txHash,
                    accumulator.eventGroupId,
                    accumulator.normalizedTransactionId,
                    accumulator.normalizedType,
                    accumulator.protocolName,
                    accumulator.lifecycleKind,
                    accumulator.lifecycleStage,
                    List.copyOf(accumulator.basisEffects),
                    accumulator.quantityDelta,
                    accumulator.costBasisDeltaUsd,
                    accumulator.realisedPnlDeltaUsd,
                    accumulator.gasDeltaUsd,
                    state.quantity,
                    state.coveredQuantity(),
                    state.uncoveredQuantity,
                    state.totalCostBasisUsd,
                    previousAvcoAfterUsd,
                    avcoAfterUsd,
                    state.netTotalCostBasisUsd,
                    previousNetAvcoAfterUsd,
                    netAvcoAfterUsd,
                    avcoKind,
                    accumulator.fromAddress,
                    accumulator.toAddress,
                    List.copyOf(accumulator.memberNormalizedTransactionIds)
            ));
            previousAvcoAfterUsd = avcoAfterUsd;
            previousNetAvcoAfterUsd = netAvcoAfterUsd;
            overlays.add(new EventOverlayView(
                    accumulator.eventGroupId,
                    accumulator.normalizedTransactionId,
                    accumulator.txHash,
                    accumulator.blockTimestamp,
                    accumulator.normalizedType,
                    accumulator.protocolName,
                    accumulator.lifecycleKind,
                    List.copyOf(accumulator.walletAddresses),
                    List.copyOf(accumulator.networkIds),
                    List.copyOf(accumulator.flows),
                    accumulator.fromAddress,
                    accumulator.toAddress,
                    List.copyOf(accumulator.memberNormalizedTransactionIds)
            ));
        }

        CurrentStateView currentState = currentStateView(
                session,
                familyIdentity,
                points,
                state.totalRealisedPnlUsd,
                state.totalNetRealisedPnlUsd,
                state.totalGasPaidUsd
        );

        FullSessionCurrentView fullSessionCurrent = fullSessionCurrentView(
                latestLedgerPointByBucket(points),
                familyIdentity
        );

        return new SessionAssetLedgerView(
                session.getId(),
                familyIdentity,
                currentState,
                fullSessionCurrent,
                timeline,
                overlays,
                rawPoints
        );
    }

    /**
     * Ledger-based full-session current state: iterates the latest replay point per bucket
     * (on-chain + Bybit venues) and sums qty / covered qty / total cost basis for the requested
     * family. Unlike {@link #currentStateView} this does not require live on-chain balance
     * snapshots or a live Bybit balance call — it relies entirely on the stored replay state.
     * <p>
     * Use this to verify the honest full-session AVCO (A2 acceptance criterion) that includes
     * on-chain wallets and all Bybit venue sub-wallets.
     */
    private FullSessionCurrentView fullSessionCurrentView(
            Map<BucketKey, AssetLedgerPoint> latestPointByBucket,
            String familyIdentity
    ) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal coveredQuantity = BigDecimal.ZERO;
        BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        for (AssetLedgerPoint point : latestPointByBucket.values()) {
            String pointFamily = resolvedFamilyIdentity(
                    point,
                    point.getNetworkId(),
                    point.getAssetSymbol(),
                    point.getAssetContract()
            );
            if (!Objects.equals(pointFamily, familyIdentity)) {
                continue;
            }
            BigDecimal qty = zeroIfNull(point.getQuantityAfter());
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal covered = zeroIfNull(point.getBasisBackedQuantityAfter()).min(qty);
            quantity = quantity.add(qty, MC);
            coveredQuantity = coveredQuantity.add(covered, MC);
            if (point.getAvcoAfterUsd() != null && covered.signum() > 0) {
                totalCostBasisUsd = totalCostBasisUsd.add(
                        point.getAvcoAfterUsd().multiply(covered, MC), MC
                );
            }
            if (point.getNetAvcoAfterUsd() != null && covered.signum() > 0) {
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                        point.getNetAvcoAfterUsd().multiply(covered, MC), MC
                );
            } else if (point.getAvcoAfterUsd() != null && covered.signum() > 0) {
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                        point.getAvcoAfterUsd().multiply(covered, MC), MC
                );
            }
        }
        BigDecimal uncoveredQuantity = quantity.subtract(coveredQuantity, MC).max(BigDecimal.ZERO);
        BigDecimal avcoUsd = coveredQuantity.signum() <= 0
                ? null
                : totalCostBasisUsd.divide(coveredQuantity, MC);
        BigDecimal netAvcoUsd = coveredQuantity.signum() <= 0
                ? null
                : netTotalCostBasisUsd.divide(coveredQuantity, MC);
        return new FullSessionCurrentView(
                quantity, coveredQuantity, uncoveredQuantity,
                totalCostBasisUsd, avcoUsd, netTotalCostBasisUsd, netAvcoUsd
        );
    }

    private CurrentStateView currentStateView(
            UserSession session,
            String familyIdentity,
            List<AssetLedgerPoint> universePoints,
            BigDecimal realisedPnlUsd,
            BigDecimal netRealisedPnlUsd,
            BigDecimal gasPaidUsd
    ) {
        AllowedScope allowedScope = AllowedScope.from(session.getWallets());
        List<OnChainBalance> scopedBalances = loadOnChainBalances(session.getId(), allowedScope.walletAddresses()).stream()
                .filter(balance -> allowedScope.includes(balance.getWalletAddress(), balance.getNetworkId()))
                .toList();
        Map<BucketKey, OnChainBalance> latestBalances = latestBalanceByBucket(scopedBalances);
        Map<BucketKey, AssetLedgerPoint> latestPointByBucket = latestLedgerPointByBucket(universePoints);

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal coveredQuantity = BigDecimal.ZERO;
        BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        List<UncoveredBucketView> uncoveredBuckets = new ArrayList<>();
        for (OnChainBalance balance : latestBalances.values()) {
            if (!matchesFamily(balance, familyIdentity, latestPointByBucket)) {
                continue;
            }
            BigDecimal currentQuantity = zeroIfNull(balance.getQuantity());
            if (currentQuantity.signum() <= 0) {
                continue;
            }
            quantity = quantity.add(currentQuantity, MC);

            BucketKey bucketKey = bucketKey(balance);
            AssetLedgerPoint latestPoint = latestPointByBucket.get(bucketKey);
            BigDecimal exactCoveredQuantity = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(currentQuantity);
            coveredQuantity = coveredQuantity.add(exactCoveredQuantity, MC);
            if (latestPoint != null && latestPoint.getAvcoAfterUsd() != null && exactCoveredQuantity.signum() > 0) {
                totalCostBasisUsd = totalCostBasisUsd.add(
                        latestPoint.getAvcoAfterUsd().multiply(exactCoveredQuantity, MC),
                        MC
                );
            }
            if (latestPoint != null && exactCoveredQuantity.signum() > 0) {
                BigDecimal netAvcoPoint = latestPoint.getNetAvcoAfterUsd() != null
                        ? latestPoint.getNetAvcoAfterUsd()
                        : latestPoint.getAvcoAfterUsd();
                if (netAvcoPoint != null) {
                    netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                            netAvcoPoint.multiply(exactCoveredQuantity, MC),
                            MC
                    );
                }
            }
            BigDecimal exactUncoveredQuantity = currentQuantity.subtract(exactCoveredQuantity, MC).max(BigDecimal.ZERO);
            if (exactUncoveredQuantity.signum() > 0) {
                uncoveredBuckets.add(new UncoveredBucketView(
                        balance.getWalletAddress(),
                        balance.getNetworkId() == null ? null : balance.getNetworkId().name(),
                        balance.getAssetSymbol(),
                        balance.getAssetContract(),
                        currentQuantity,
                        exactCoveredQuantity,
                        exactUncoveredQuantity,
                        uncoveredReason(latestPoint, currentQuantity, exactCoveredQuantity),
                        latestPoint == null ? null : latestPoint.getTxHash(),
                        latestPoint == null ? null : latestPoint.getNormalizedType(),
                        latestPoint == null || latestPoint.getBasisEffect() == null ? null : latestPoint.getBasisEffect().name(),
                        latestPoint == null ? null : latestPoint.getProtocolName(),
                        latestPoint != null && Boolean.TRUE.equals(latestPoint.getHasIncompleteHistoryAfter()),
                        latestPoint != null && Boolean.TRUE.equals(latestPoint.getHasUnresolvedFlagsAfter()),
                        unresolvedFlagCountAfter(latestPoint)
                ));
            }
        }

        LinkedHashSet<String> enabledBybitVenueRefs = new LinkedHashSet<>(BybitUmbrellaSupport.enabledBybitAccountRefs(session));
        if (!enabledBybitVenueRefs.isEmpty()) {
            Map<String, BybitFamilyUmbrellaAccumulator> umbrellas = new LinkedHashMap<>();
            for (Map.Entry<BucketKey, AssetLedgerPoint> ledgerEntry : latestPointByBucket.entrySet()) {
                BucketKey bucketKey = ledgerEntry.getKey();
                AssetLedgerPoint latestPoint = ledgerEntry.getValue();
                if (!BybitUmbrellaSupport.bybitLedgerMatchesEnabledVenue(bucketKey.walletAddress(), enabledBybitVenueRefs)) {
                    continue;
                }
                String pointFamilyIdentity = resolvedFamilyIdentity(
                        latestPoint,
                        bucketKey.networkId(),
                        latestPoint.getAssetSymbol(),
                        latestPoint.getAssetContract()
                );
                if (!Objects.equals(pointFamilyIdentity, familyIdentity)) {
                    continue;
                }
                BigDecimal currentQuantity = BybitUmbrellaSupport.bybitRawQuantityAfter(latestPoint);
                if (currentQuantity.signum() <= 0) {
                    continue;
                }
                String aggregatedWallet = BybitUmbrellaSupport.ledgerWalletKeyForAggregation(
                        bucketKey.walletAddress(),
                        enabledBybitVenueRefs
                );
                umbrellas.computeIfAbsent(aggregatedWallet, ignored -> new BybitFamilyUmbrellaAccumulator())
                        .addVenue(latestPoint.getWalletAddress(), currentQuantity, latestPoint);
            }

            Map<String, Map<String, BigDecimal>> liveByAccountRef = loadLiveBybitBalances(session);
            for (Map.Entry<String, BybitFamilyUmbrellaAccumulator> umbrellaEntry : umbrellas.entrySet()) {
                String umbrellaWallet = umbrellaEntry.getKey();
                BybitFamilyUmbrellaAccumulator umbrella = umbrellaEntry.getValue();
                Map<String, BigDecimal> live = liveByAccountRef.get(umbrellaWallet);
                BigDecimal liveQty = live == null
                        ? BigDecimal.ZERO
                        : BybitUmbrellaSupport.liveQuantityForCandidates(
                        live,
                        umbrella.priceLookupCandidates(),
                        umbrella.fallbackSymbol()
                );
                BybitUmbrellaSupport.ScaledUmbrellaTotals scaled = BybitUmbrellaSupport.scaleUmbrellaToLive(
                        umbrella.quantity(),
                        umbrella.coveredQuantity(),
                        umbrella.totalCostBasisUsd(),
                        liveQty
                );
                if (scaled.dropped() || scaled.quantity().signum() <= 0) {
                    continue;
                }
                BigDecimal quantityScale = umbrella.quantity().signum() <= 0
                        ? BigDecimal.ONE
                        : scaled.quantity().divide(umbrella.quantity(), MC);
                BigDecimal ledgerScale = scaled.ledgerScale() == null ? BigDecimal.ONE : scaled.ledgerScale();
                quantity = quantity.add(scaled.quantity(), MC);
                coveredQuantity = coveredQuantity.add(scaled.coveredQuantity(), MC);
                totalCostBasisUsd = totalCostBasisUsd.add(scaled.totalCostBasisUsd(), MC);
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                        umbrella.netTotalCostBasisUsd().multiply(ledgerScale, MC),
                        MC
                );
                for (VenuePositionSlice venue : umbrella.venueSlices()) {
                    BigDecimal venueQty = venue.quantity().multiply(quantityScale.min(BigDecimal.ONE), MC);
                    BigDecimal venueCovered = venue.coveredQuantity().multiply(ledgerScale, MC);
                    BigDecimal venueUncovered = venueQty.subtract(venueCovered, MC).max(BigDecimal.ZERO);
                    if (venueUncovered.signum() <= 0) {
                        continue;
                    }
                    uncoveredBuckets.add(new UncoveredBucketView(
                            venue.walletAddress(),
                            null,
                            venue.assetSymbol(),
                            venue.assetContract(),
                            venueQty,
                            venueCovered,
                            venueUncovered,
                            uncoveredReason(venue.latestPoint(), venueQty, venueCovered),
                            venue.latestPoint() == null ? null : venue.latestPoint().getTxHash(),
                            venue.latestPoint() == null ? null : venue.latestPoint().getNormalizedType(),
                            venue.latestPoint() == null || venue.latestPoint().getBasisEffect() == null
                                    ? null
                                    : venue.latestPoint().getBasisEffect().name(),
                            venue.latestPoint() == null ? null : venue.latestPoint().getProtocolName(),
                            venue.latestPoint() != null && Boolean.TRUE.equals(venue.latestPoint().getHasIncompleteHistoryAfter()),
                            venue.latestPoint() != null && Boolean.TRUE.equals(venue.latestPoint().getHasUnresolvedFlagsAfter()),
                            unresolvedFlagCountAfter(venue.latestPoint())
                    ));
                }
            }
        }

        BigDecimal uncoveredQuantity = quantity.subtract(coveredQuantity, MC).max(BigDecimal.ZERO);
        BigDecimal avcoUsd = coveredQuantity.signum() <= 0
                ? null
                : totalCostBasisUsd.divide(coveredQuantity, MC);
        BigDecimal netAvcoUsd = coveredQuantity.signum() <= 0
                ? null
                : netTotalCostBasisUsd.divide(coveredQuantity, MC);
        uncoveredBuckets.sort((left, right) -> right.uncoveredQuantity().compareTo(left.uncoveredQuantity()));
        List<ShortfallSourceView> shortfallSources = familyShortfallSources(universePoints);
        return new CurrentStateView(
                quantity,
                coveredQuantity,
                uncoveredQuantity,
                totalCostBasisUsd,
                avcoUsd,
                netTotalCostBasisUsd,
                netAvcoUsd,
                realisedPnlUsd,
                netRealisedPnlUsd,
                gasPaidUsd,
                List.copyOf(uncoveredBuckets),
                shortfallSources
        );
    }

    private List<ShortfallSourceView> familyShortfallSources(List<AssetLedgerPoint> universePoints) {
        Map<ShortfallSourceKey, ShortfallSourceAccumulator> grouped = new LinkedHashMap<>();
        for (AssetLedgerPoint point : universePoints) {
            BigDecimal quantityShortfallDelta = zeroIfNull(point.getQuantityShortfallDelta());
            if (quantityShortfallDelta.signum() <= 0) {
                continue;
            }
            ShortfallSourceKey key = new ShortfallSourceKey(
                    point.getWalletAddress(),
                    point.getNetworkId() == null ? null : point.getNetworkId().name(),
                    point.getTxHash(),
                    point.getNormalizedType(),
                    point.getProtocolName()
            );
            grouped.computeIfAbsent(key, ignored -> new ShortfallSourceAccumulator(point))
                    .add(quantityShortfallDelta);
        }
        return grouped.values().stream()
                .sorted((left, right) -> {
                    int byShortfall = right.quantityShortfall().compareTo(left.quantityShortfall());
                    if (byShortfall != 0) {
                        return byShortfall;
                    }
                    if (left.blockTimestamp() == null && right.blockTimestamp() == null) {
                        return 0;
                    }
                    if (left.blockTimestamp() == null) {
                        return 1;
                    }
                    if (right.blockTimestamp() == null) {
                        return -1;
                    }
                    return left.blockTimestamp().compareTo(right.blockTimestamp());
                })
                .limit(20)
                .map(ShortfallSourceAccumulator::toView)
                .toList();
    }

    private LedgerPointView toRawPoint(AssetLedgerPoint point) {
        return new LedgerPointView(
                point.getWalletAddress(),
                point.getNetworkId() == null ? null : point.getNetworkId().name(),
                point.getAccountingAssetIdentity(),
                point.getAccountingFamilyIdentity(),
                point.getFamilyDisplaySymbol(),
                point.getAssetSymbol(),
                point.getAssetContract(),
                point.getNormalizedTransactionId(),
                point.getTxHash(),
                point.getCorrelationId(),
                point.getLifecycleChainId(),
                point.getMatchedCounterparty(),
                point.getBlockTimestamp(),
                point.getReplaySequence(),
                point.getNormalizedType(),
                point.getLifecycleKind() == null ? null : point.getLifecycleKind().name(),
                point.getLifecycleStage() == null ? null : point.getLifecycleStage().name(),
                point.getBasisEffect() == null ? null : point.getBasisEffect().name(),
                point.getProtocolName(),
                point.getQuantityDelta(),
                point.getCostBasisDeltaUsd(),
                point.getRealisedPnlDeltaUsd(),
                point.getGasDeltaUsd(),
                point.getQuantityBefore(),
                point.getQuantityAfter(),
                point.getTotalCostBasisBeforeUsd(),
                point.getTotalCostBasisAfterUsd(),
                point.getAvcoBeforeUsd(),
                gasOnlyAvcoAfter(point),
                point.getNetTotalCostBasisBeforeUsd(),
                point.getNetTotalCostBasisAfterUsd(),
                point.getNetAvcoBeforeUsd(),
                point.getNetAvcoAfterUsd(),
                point.getNetCostBasisDeltaUsd(),
                point.getNetRealisedPnlDeltaUsd(),
                point.getBasisBackedQuantityAfter(),
                point.getUncoveredQuantityDelta(),
                point.getQuantityShortfallAfter(),
                point.getUncoveredQuantityAfter(),
                point.getHasIncompleteHistoryAfter(),
                point.getHasUnresolvedFlagsAfter(),
                point.getUnresolvedFlagCountAfter()
        );
    }

    /**
     * WS-4: represents {@code avcoAfterUsd} as {@code null} (undefined) instead of 0 when the
     * basis-backed quantity is approximately zero AND the event is a gas-only or reward-claim type.
     *
     * <p>Gate conditions (all must hold):</p>
     * <ul>
     *   <li>{@code basisBackedQuantityAfter < 1e-8} — position has no meaningful basis backing</li>
     *   <li>{@code normalizedType = SPONSORED_GAS_IN} or {@code = REWARD_CLAIM} or
     *       {@code basisEffect = GAS_ONLY} — event is known to be basis-neutral</li>
     * </ul>
     *
     * <p>Must NOT fire for WRAP/UNWRAP events which have {@code basisBackedQuantityAfter > 0}
     * by design (they carry and reallocate real basis). The {@code basisBackedQuantityAfter < 1e-8}
     * threshold is the exclusive gate; WRAP/UNWRAP always satisfy the inverse condition.</p>
     */
    static BigDecimal gasOnlyAvcoAfter(AssetLedgerPoint point) {
        if (point == null) {
            return null;
        }
        BigDecimal basisBacked = point.getBasisBackedQuantityAfter();
        if (basisBacked == null || basisBacked.compareTo(GAS_ONLY_BASIS_THRESHOLD) >= 0) {
            // Has meaningful basis-backed quantity — report the stored AVCO as-is
            return point.getAvcoAfterUsd();
        }
        // basisBackedQuantityAfter ≈ 0 — check event type / basis effect
        AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
        String normalizedType = point.getNormalizedType();
        boolean isGasOnlyEvent = basisEffect == AssetLedgerPoint.BasisEffect.GAS_ONLY
                || "SPONSORED_GAS_IN".equals(normalizedType)
                || "REWARD_CLAIM".equals(normalizedType);
        return isGasOnlyEvent ? null : point.getAvcoAfterUsd();
    }

    private static final BigDecimal GAS_ONLY_BASIS_THRESHOLD = new BigDecimal("0.00000001");

    private Map<String, NormalizedTransaction> findNormalizedTransactions(List<AssetLedgerPoint> points) {
        List<String> normalizedIds = points.stream()
                .map(AssetLedgerPoint::getNormalizedTransactionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, NormalizedTransaction> normalizedById = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : normalizedTransactionRepository.findAllById(normalizedIds)) {
            normalizedById.put(transaction.getId(), transaction);
        }
        return normalizedById;
    }

    private List<OnChainBalance> loadOnChainBalances(String sessionId, List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("sessionId").is(sessionId),
                        Criteria.where("walletAddress").in(walletAddresses)
                ))
                .with(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("assetContract"),
                        Sort.Order.asc("capturedAt")
                ));
        return mongoOperations.find(query, OnChainBalance.class);
    }

    private Map<String, Map<String, BigDecimal>> loadLiveBybitBalances(UserSession session) {
        Map<String, Map<String, BigDecimal>> liveByAccountRef = new LinkedHashMap<>();
        if (session.getIntegrations() == null) {
            return liveByAccountRef;
        }
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null
                    || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                    || integration.getAccountRef() == null
                    || integration.getIntegrationId() == null
                    || !integration.getAccountRef().toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
                continue;
            }
            Optional<BybitLiveBalanceService.LiveSnapshotView> snapshotView =
                    bybitLiveBalanceService.getSnapshotView(integration.getIntegrationId());
            if (snapshotView.isEmpty()) {
                continue;
            }
            BybitLiveBalanceService.LiveSnapshotView view = snapshotView.get();
            if (view.availability() == BybitLiveBalanceService.LiveSnapshotAvailability.UNKNOWN) {
                continue;
            }
            liveByAccountRef.put(
                    BybitUmbrellaSupport.normalizeAddress(integration.getAccountRef()),
                    view.umbrella()
            );
        }
        return liveByAccountRef;
    }

    private Map<BucketKey, OnChainBalance> latestBalanceByBucket(List<OnChainBalance> balances) {
        Map<BucketKey, OnChainBalance> latest = new LinkedHashMap<>();
        for (OnChainBalance balance : balances) {
            latest.put(bucketKey(balance), balance);
        }
        return latest;
    }

    /**
     * ADR-045 Method B: fold the (spot-family-filtered) member points of a single grouped event
     * into the live per-bucket AVCO state. Within one event only the <em>last</em> point per bucket
     * (by {@code replaySequence}) is retained, mirroring how the current-state rollups read the
     * latest stored point per bucket. Unchanged buckets keep their prior snapshot.
     */
    private static void applyMethodBBuckets(
            String familyIdentity,
            List<AssetLedgerPoint> memberPoints,
            Map<BucketKey, BucketAvcoState> liveAvcoBuckets
    ) {
        if (memberPoints == null || memberPoints.isEmpty()) {
            return;
        }
        Map<BucketKey, AssetLedgerPoint> lastPointByBucket = new LinkedHashMap<>();
        memberPoints.stream()
                .filter(point -> AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                        familyIdentity,
                        point.getAssetSymbol()
                ))
                .sorted(Comparator.comparing(
                        AssetLedgerPoint::getReplaySequence,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .forEach(point -> lastPointByBucket.put(methodBBucketKey(point), point));
        for (Map.Entry<BucketKey, AssetLedgerPoint> entry : lastPointByBucket.entrySet()) {
            AssetLedgerPoint point = entry.getValue();
            BigDecimal quantityAfter = zeroIfNull(point.getQuantityAfter());
            BigDecimal covered = zeroIfNull(point.getBasisBackedQuantityAfter())
                    .min(quantityAfter)
                    .max(BigDecimal.ZERO);
            liveAvcoBuckets.put(
                    entry.getKey(),
                    new BucketAvcoState(point.getAvcoAfterUsd(), point.getNetAvcoAfterUsd(), covered)
            );
        }
    }

    /**
     * ADR-045 Method B: covered-weighted family AVCO over the live bucket snapshots.
     * {@code avco = Σ coveredᵢ·avcoᵢ / Σ coveredᵢ}. ADR-031: when {@code Σ coveredᵢ ≤ 0} both lanes
     * are {@code null} (the line breaks — never plotted as $0). A bucket missing its net lane falls
     * back to its tax AVCO (mirrors {@code netAvcoOnPoint}).
     */
    private static CoveredWeightedAvco coveredWeightedFamilyAvco(Map<BucketKey, BucketAvcoState> liveAvcoBuckets) {
        BigDecimal coveredTotal = BigDecimal.ZERO;
        BigDecimal taxBasisTotal = BigDecimal.ZERO;
        BigDecimal netBasisTotal = BigDecimal.ZERO;
        for (BucketAvcoState bucket : liveAvcoBuckets.values()) {
            BigDecimal covered = bucket.coveredQuantity();
            if (covered == null || covered.signum() <= 0) {
                continue;
            }
            coveredTotal = coveredTotal.add(covered, MC);
            if (bucket.avcoAfterUsd() != null) {
                taxBasisTotal = taxBasisTotal.add(bucket.avcoAfterUsd().multiply(covered, MC), MC);
            }
            BigDecimal netAvco = bucket.netAvcoAfterUsd() != null
                    ? bucket.netAvcoAfterUsd()
                    : bucket.avcoAfterUsd();
            if (netAvco != null) {
                netBasisTotal = netBasisTotal.add(netAvco.multiply(covered, MC), MC);
            }
        }
        if (coveredTotal.signum() <= 0) {
            return new CoveredWeightedAvco(null, null);
        }
        return new CoveredWeightedAvco(
                taxBasisTotal.divide(coveredTotal, MC),
                netBasisTotal.divide(coveredTotal, MC)
        );
    }

    private static BucketKey methodBBucketKey(AssetLedgerPoint point) {
        return new BucketKey(
                normalizeAddress(point.getWalletAddress()),
                point.getNetworkId(),
                point.getAccountingAssetIdentity()
        );
    }

    private Map<BucketKey, AssetLedgerPoint> latestLedgerPointByBucket(List<AssetLedgerPoint> points) {
        Map<BucketKey, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            latest.put(new BucketKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingAssetIdentity()
            ), point);
        }
        return latest;
    }

    private boolean matchesFamily(
            OnChainBalance balance,
            String familyIdentity,
            Map<BucketKey, AssetLedgerPoint> latestPointByBucket
    ) {
        AssetLedgerPoint latestPoint = latestPointByBucket.get(bucketKey(balance));
        String resolvedFamilyIdentity = resolvedFamilyIdentity(
                latestPoint,
                balance.getNetworkId(),
                balance.getAssetSymbol(),
                balance.getAssetContract()
        );
        return Objects.equals(resolvedFamilyIdentity, familyIdentity);
    }

    private BucketKey bucketKey(OnChainBalance balance) {
        return new BucketKey(
                normalizeAddress(balance.getWalletAddress()),
                balance.getNetworkId(),
                AccountingAssetIdentitySupport.positionAssetIdentity(
                        balance.getNetworkId(),
                        balance.getAssetSymbol(),
                        balance.getAssetContract()
                )
        );
    }

    private List<EventAccumulator> groupPoints(
            List<AssetLedgerPoint> points,
            Map<String, NormalizedTransaction> normalizedById
    ) {
        Map<String, EventAccumulator> grouped = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            String key = point.getNormalizedTransactionId();
            EventAccumulator accumulator = grouped.computeIfAbsent(
                    key,
                    ignored -> new EventAccumulator(point, normalizedById.get(key))
            );
            accumulator.add(point);
        }
        return List.copyOf(grouped.values());
    }

    private List<DisplayEventAccumulator> collapseDisplayEvents(List<EventAccumulator> rawEvents) {
        if (rawEvents.isEmpty()) {
            return List.of();
        }
        List<DisplayEventAccumulator> collapsed = new ArrayList<>();
        boolean[] consumed = new boolean[rawEvents.size()];
        for (int index = 0; index < rawEvents.size(); index++) {
            if (consumed[index]) {
                continue;
            }
            EventAccumulator current = rawEvents.get(index);
            int pairIndex = findInternalTransferPairIndex(index, rawEvents, consumed);
            if (pairIndex >= 0) {
                consumed[index] = true;
                consumed[pairIndex] = true;
                collapsed.add(DisplayEventAccumulator.internalTransfer(rawEvents.get(index), rawEvents.get(pairIndex)));
                continue;
            }
            consumed[index] = true;
            collapsed.add(DisplayEventAccumulator.single(current));
        }
        return List.copyOf(collapsed);
    }

    private int findInternalTransferPairIndex(int seedIndex, List<EventAccumulator> rawEvents, boolean[] consumed) {
        EventAccumulator seed = rawEvents.get(seedIndex);
        if (!isExternalTransferOut(seed)) {
            return -1;
        }
        for (int candidateIndex = seedIndex + 1; candidateIndex < rawEvents.size(); candidateIndex++) {
            if (consumed[candidateIndex]) {
                continue;
            }
            EventAccumulator candidate = rawEvents.get(candidateIndex);
            if (isInternalTransferPair(seed, candidate)) {
                return candidateIndex;
            }
        }
        return -1;
    }

    private boolean isInternalTransferPair(EventAccumulator outbound, EventAccumulator inbound) {
        if (!isExternalTransferOut(outbound) || !isExternalTransferIn(inbound)) {
            return false;
        }
        if (blank(outbound.txHash) || !Objects.equals(outbound.txHash, inbound.txHash)) {
            return false;
        }
        if (blank(outbound.primaryWalletAddress) || blank(inbound.primaryWalletAddress)) {
            return false;
        }
        if (!counterpartyCompatible(outbound, inbound)) {
            return false;
        }
        return networkCompatible(outbound, inbound);
    }

    private static boolean counterpartyCompatible(EventAccumulator outbound, EventAccumulator inbound) {
        boolean reciprocal = !blank(outbound.matchedCounterparty)
                && !blank(inbound.matchedCounterparty)
                && Objects.equals(normalizeAddress(outbound.primaryWalletAddress), normalizeAddress(inbound.matchedCounterparty))
                && Objects.equals(normalizeAddress(inbound.primaryWalletAddress), normalizeAddress(outbound.matchedCounterparty));
        if (reciprocal) {
            return true;
        }
        return !blank(outbound.matchedCounterparty)
                && Objects.equals(normalizeAddress(outbound.matchedCounterparty), normalizeAddress(inbound.primaryWalletAddress));
    }

    private static boolean networkCompatible(EventAccumulator left, EventAccumulator right) {
        if (left.networkIds.isEmpty() || right.networkIds.isEmpty()) {
            return true;
        }
        for (String networkId : left.networkIds) {
            if (right.networkIds.contains(networkId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExternalTransferOut(EventAccumulator event) {
        return "EXTERNAL_TRANSFER_OUT".equals(event.normalizedType);
    }

    private static boolean isExternalTransferIn(EventAccumulator event) {
        return "EXTERNAL_TRANSFER_IN".equals(event.normalizedType);
    }

    public record SessionAssetLedgerView(
            String sessionId,
            String familyIdentity,
            CurrentStateView current,
            FullSessionCurrentView fullSessionCurrent,
            List<TimelineEntryView> timeline,
            List<EventOverlayView> events,
            List<LedgerPointView> ledgerPoints
    ) {
    }

    /**
     * Ledger-based full-session current state: sum of all latest replay points per bucket
     * (on-chain + Bybit venues) for the requested family, without relying on live balance oracles.
     * Satisfies acceptance criterion A2.
     */
    public record FullSessionCurrentView(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal avcoUsd,
            BigDecimal netTotalCostBasisUsd,
            BigDecimal netAvcoUsd
    ) {
    }

    public record CurrentStateView(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal avcoUsd,
            BigDecimal netTotalCostBasisUsd,
            BigDecimal netAvcoUsd,
            BigDecimal realisedPnlUsd,
            BigDecimal netRealisedPnlUsd,
            BigDecimal gasPaidUsd,
            List<UncoveredBucketView> uncoveredBuckets,
            List<ShortfallSourceView> shortfallSources
    ) {
    }

    public record UncoveredBucketView(
            String walletAddress,
            String networkId,
            String assetSymbol,
            String assetContract,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            String uncoveredReason,
            String latestTxHash,
            String latestNormalizedType,
            String latestBasisEffect,
            String latestProtocolName,
            boolean hasIncompleteHistory,
            boolean hasUnresolvedFlags,
            Integer unresolvedFlagCount
    ) {
    }

    public record ShortfallSourceView(
            String walletAddress,
            String networkId,
            String txHash,
            Instant blockTimestamp,
            String normalizedType,
            String protocolName,
            BigDecimal quantityShortfall
    ) {
    }

    public record TimelineEntryView(
            Instant blockTimestamp,
            String txHash,
            String eventGroupId,
            String normalizedTransactionId,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            String lifecycleStage,
            List<String> basisEffects,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal realisedPnlDeltaUsd,
            BigDecimal gasDeltaUsd,
            BigDecimal quantityAfter,
            BigDecimal coveredQuantityAfter,
            BigDecimal uncoveredQuantityAfter,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoBeforeUsd,
            BigDecimal avcoAfterUsd,
            BigDecimal netTotalCostBasisAfterUsd,
            BigDecimal netAvcoBeforeUsd,
            BigDecimal netAvcoAfterUsd,
            String avcoKind,
            String fromAddress,
            String toAddress,
            List<String> memberNormalizedTransactionIds
    ) {
    }

    public record EventOverlayView(
            String eventGroupId,
            String normalizedTransactionId,
            String txHash,
            Instant blockTimestamp,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            List<String> walletAddresses,
            List<String> networkIds,
            List<EventFlowView> flows,
            String fromAddress,
            String toAddress,
            List<String> memberNormalizedTransactionIds
    ) {
    }

    public record EventFlowView(
            String role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            BigDecimal valueUsd,
            String priceSource,
            Integer logIndex
    ) {
    }

    public record LedgerPointView(
            String walletAddress,
            String networkId,
            String accountingAssetIdentity,
            String accountingFamilyIdentity,
            String familyDisplaySymbol,
            String assetSymbol,
            String assetContract,
            String normalizedTransactionId,
            String txHash,
            String correlationId,
            String lifecycleChainId,
            String matchedCounterparty,
            Instant blockTimestamp,
            Long replaySequence,
            String normalizedType,
            String lifecycleKind,
            String lifecycleStage,
            String basisEffect,
            String protocolName,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal realisedPnlDeltaUsd,
            BigDecimal gasDeltaUsd,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal totalCostBasisBeforeUsd,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoBeforeUsd,
            BigDecimal avcoAfterUsd,
            BigDecimal netTotalCostBasisBeforeUsd,
            BigDecimal netTotalCostBasisAfterUsd,
            BigDecimal netAvcoBeforeUsd,
            BigDecimal netAvcoAfterUsd,
            BigDecimal netCostBasisDeltaUsd,
            BigDecimal netRealisedPnlDeltaUsd,
            BigDecimal basisBackedQuantityAfter,
            BigDecimal uncoveredQuantityDelta,
            BigDecimal quantityShortfallAfter,
            BigDecimal uncoveredQuantityAfter,
            Boolean hasIncompleteHistoryAfter,
            Boolean hasUnresolvedFlagsAfter,
            Integer unresolvedFlagCountAfter
    ) {
    }

    private record BucketKey(
            String walletAddress,
            NetworkId networkId,
            String accountingAssetIdentity
    ) {
    }

    /** ADR-045 Method B live per-bucket AVCO snapshot (latest stored point for the bucket). */
    private record BucketAvcoState(
            BigDecimal avcoAfterUsd,
            BigDecimal netAvcoAfterUsd,
            BigDecimal coveredQuantity
    ) {
    }

    /** ADR-045 Method B covered-weighted family AVCO (Tax + Net lanes); null when Σ covered ≤ 0. */
    private record CoveredWeightedAvco(
            BigDecimal taxAvco,
            BigDecimal netAvco
    ) {
    }

    private record ShortfallSourceKey(
            String walletAddress,
            String networkId,
            String txHash,
            String normalizedType,
            String protocolName
    ) {
    }

    private static final class ShortfallSourceAccumulator {
        private final String walletAddress;
        private final String networkId;
        private final String txHash;
        private final Instant blockTimestamp;
        private final String normalizedType;
        private final String protocolName;
        private BigDecimal quantityShortfall = BigDecimal.ZERO;

        private ShortfallSourceAccumulator(AssetLedgerPoint seed) {
            this.walletAddress = seed.getWalletAddress();
            this.networkId = seed.getNetworkId() == null ? null : seed.getNetworkId().name();
            this.txHash = seed.getTxHash();
            this.blockTimestamp = seed.getBlockTimestamp();
            this.normalizedType = seed.getNormalizedType();
            this.protocolName = seed.getProtocolName();
        }

        private void add(BigDecimal delta) {
            quantityShortfall = quantityShortfall.add(zeroIfNull(delta), MC);
        }

        private BigDecimal quantityShortfall() {
            return quantityShortfall;
        }

        private Instant blockTimestamp() {
            return blockTimestamp;
        }

        private ShortfallSourceView toView() {
            return new ShortfallSourceView(
                    walletAddress,
                    networkId,
                    txHash,
                    blockTimestamp,
                    normalizedType,
                    protocolName,
                    quantityShortfall
            );
        }
    }

    private record AllowedScope(Map<String, Set<NetworkId>> networksByAddress) {
        private static AllowedScope from(List<UserSession.SessionWallet> wallets) {
            Map<String, Set<NetworkId>> mapping = new LinkedHashMap<>();
            for (UserSession.SessionWallet wallet : wallets) {
                String address = normalizeAddress(wallet.getAddress());
                if (address.isBlank()) {
                    continue;
                }
                mapping.computeIfAbsent(address, ignored -> new LinkedHashSet<>())
                        .addAll(wallet.getNetworks() == null ? List.of() : wallet.getNetworks());
            }
            return new AllowedScope(mapping);
        }

        private List<String> walletAddresses() {
            return List.copyOf(networksByAddress.keySet());
        }

        private boolean includes(String walletAddress, NetworkId networkId) {
            if (networkId == null) {
                return false;
            }
            Set<NetworkId> allowedNetworks = networksByAddress.get(normalizeAddress(walletAddress));
            return allowedNetworks != null && allowedNetworks.contains(networkId);
        }
    }

    private static final class BybitFamilyUmbrellaAccumulator {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal coveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        private final LinkedHashSet<String> priceLookupCandidates = new LinkedHashSet<>();
        private String fallbackSymbol;
        private final List<VenuePositionSlice> venueSlices = new ArrayList<>();

        private void addVenue(String walletAddress, BigDecimal currentQuantity, AssetLedgerPoint latestPoint) {
            BigDecimal exactCoveredQuantity = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(currentQuantity);
            quantity = quantity.add(currentQuantity, MC);
            coveredQuantity = coveredQuantity.add(exactCoveredQuantity, MC);
            if (latestPoint != null && latestPoint.getAvcoAfterUsd() != null && exactCoveredQuantity.signum() > 0) {
                totalCostBasisUsd = totalCostBasisUsd.add(
                        latestPoint.getAvcoAfterUsd().multiply(exactCoveredQuantity, MC),
                        MC
                );
            }
            if (latestPoint != null && exactCoveredQuantity.signum() > 0) {
                BigDecimal netAvcoPoint = latestPoint.getNetAvcoAfterUsd() != null
                        ? latestPoint.getNetAvcoAfterUsd()
                        : latestPoint.getAvcoAfterUsd();
                if (netAvcoPoint != null) {
                    netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                            netAvcoPoint.multiply(exactCoveredQuantity, MC),
                            MC
                    );
                }
            }
            if (latestPoint != null && latestPoint.getAssetSymbol() != null) {
                priceLookupCandidates.addAll(BybitUmbrellaSupport.priceLookupCandidates(latestPoint.getAssetSymbol()));
                if (fallbackSymbol == null) {
                    fallbackSymbol = latestPoint.getAssetSymbol();
                }
            }
            venueSlices.add(new VenuePositionSlice(
                    walletAddress,
                    latestPoint == null ? null : latestPoint.getAssetSymbol(),
                    latestPoint == null ? null : latestPoint.getAssetContract(),
                    currentQuantity,
                    exactCoveredQuantity,
                    latestPoint
            ));
        }

        private BigDecimal quantity() {
            return quantity;
        }

        private BigDecimal coveredQuantity() {
            return coveredQuantity;
        }

        private BigDecimal totalCostBasisUsd() {
            return totalCostBasisUsd;
        }

        private BigDecimal netTotalCostBasisUsd() {
            return netTotalCostBasisUsd;
        }

        private List<String> priceLookupCandidates() {
            return List.copyOf(priceLookupCandidates);
        }

        private String fallbackSymbol() {
            return fallbackSymbol;
        }

        private List<VenuePositionSlice> venueSlices() {
            return venueSlices;
        }
    }

    private record VenuePositionSlice(
            String walletAddress,
            String assetSymbol,
            String assetContract,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            AssetLedgerPoint latestPoint
    ) {
    }

    private static final class AggregatedState {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        private BigDecimal totalNetRealisedPnlUsd = BigDecimal.ZERO;
        private BigDecimal totalGasPaidUsd = BigDecimal.ZERO;

        private void apply(DisplayEventAccumulator accumulator) {
            quantity = quantity.add(accumulator.quantityDelta, MC);
            uncoveredQuantity = uncoveredQuantity.add(accumulator.uncoveredQuantityDelta, MC);
            totalCostBasisUsd = totalCostBasisUsd.add(accumulator.costBasisDeltaUsd, MC);
            netTotalCostBasisUsd = netTotalCostBasisUsd.add(accumulator.netCostBasisDeltaUsd, MC);
            totalRealisedPnlUsd = totalRealisedPnlUsd.add(accumulator.realisedPnlDeltaUsd, MC);
            totalNetRealisedPnlUsd = totalNetRealisedPnlUsd.add(accumulator.netRealisedPnlDeltaUsd, MC);
            totalGasPaidUsd = totalGasPaidUsd.add(accumulator.gasDeltaUsd, MC);
            if (quantity.signum() < 0) {
                quantity = BigDecimal.ZERO;
            }
            if (uncoveredQuantity.signum() < 0) {
                uncoveredQuantity = BigDecimal.ZERO;
            }
            if (uncoveredQuantity.compareTo(quantity) > 0) {
                uncoveredQuantity = quantity;
            }
            if (totalCostBasisUsd.signum() < 0) {
                totalCostBasisUsd = BigDecimal.ZERO;
            }
            if (netTotalCostBasisUsd.signum() < 0) {
                netTotalCostBasisUsd = BigDecimal.ZERO;
            }
        }

        private BigDecimal coveredQuantity() {
            return quantity.subtract(uncoveredQuantity, MC).max(BigDecimal.ZERO);
        }

        private BigDecimal avco() {
            BigDecimal coveredQuantity = coveredQuantity();
            return coveredQuantity.signum() <= 0 ? null : totalCostBasisUsd.divide(coveredQuantity, MC);
        }

        private BigDecimal netAvco() {
            BigDecimal coveredQuantity = coveredQuantity();
            return coveredQuantity.signum() <= 0 ? null : netTotalCostBasisUsd.divide(coveredQuantity, MC);
        }
    }

    private static final class EventAccumulator {
        private final String normalizedTransactionId;
        private final String txHash;
        private final Instant blockTimestamp;
        private final String normalizedType;
        private final String protocolName;
        private final String lifecycleKind;
        private final String lifecycleStage;
        private final String primaryWalletAddress;
        private final String matchedCounterparty;
        private final String counterpartyAddress;
        private final LinkedHashSet<String> basisEffects = new LinkedHashSet<>();
        private final LinkedHashSet<String> walletAddresses = new LinkedHashSet<>();
        private final LinkedHashSet<String> networkIds = new LinkedHashSet<>();
        private final List<EventFlowView> flows;
        private BigDecimal quantityDelta = BigDecimal.ZERO;
        private BigDecimal costBasisDeltaUsd = BigDecimal.ZERO;
        private BigDecimal netCostBasisDeltaUsd = BigDecimal.ZERO;
        private BigDecimal realisedPnlDeltaUsd = BigDecimal.ZERO;
        private BigDecimal netRealisedPnlDeltaUsd = BigDecimal.ZERO;
        private BigDecimal gasDeltaUsd = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantityDelta = BigDecimal.ZERO;
        private final List<AssetLedgerPoint> memberPoints = new ArrayList<>();

        private EventAccumulator(AssetLedgerPoint seed, NormalizedTransaction transaction) {
            this.normalizedTransactionId = seed.getNormalizedTransactionId();
            this.txHash = seed.getTxHash();
            this.blockTimestamp = seed.getBlockTimestamp();
            this.normalizedType = seed.getNormalizedType();
            this.protocolName = protocolName(seed, transaction);
            this.lifecycleKind = seed.getLifecycleKind() == null ? null : seed.getLifecycleKind().name();
            this.lifecycleStage = seed.getLifecycleStage() == null ? null : seed.getLifecycleStage().name();
            this.primaryWalletAddress = blank(seed.getWalletAddress())
                    ? transaction == null ? null : transaction.getWalletAddress()
                    : seed.getWalletAddress();
            this.matchedCounterparty = firstNonBlank(
                    seed.getMatchedCounterparty(),
                    transaction == null ? null : transaction.getMatchedCounterparty()
            );
            this.counterpartyAddress = transaction == null ? null : transaction.getCounterpartyAddress();
            this.flows = eventFlows(transaction);
        }

        private void add(AssetLedgerPoint point) {
            if (point.getBasisEffect() != null) {
                basisEffects.add(point.getBasisEffect().name());
            }
            if (point.getWalletAddress() != null) {
                walletAddresses.add(point.getWalletAddress());
            }
            if (point.getNetworkId() != null) {
                networkIds.add(point.getNetworkId().name());
            }
            quantityDelta = quantityDelta.add(zeroIfNull(point.getQuantityDelta()), MC);
            costBasisDeltaUsd = costBasisDeltaUsd.add(zeroIfNull(point.getCostBasisDeltaUsd()), MC);
            netCostBasisDeltaUsd = netCostBasisDeltaUsd.add(
                    zeroIfNull(point.getNetCostBasisDeltaUsd() != null
                            ? point.getNetCostBasisDeltaUsd()
                            : point.getCostBasisDeltaUsd()),
                    MC
            );
            realisedPnlDeltaUsd = realisedPnlDeltaUsd.add(zeroIfNull(point.getRealisedPnlDeltaUsd()), MC);
            netRealisedPnlDeltaUsd = netRealisedPnlDeltaUsd.add(
                    zeroIfNull(point.getNetRealisedPnlDeltaUsd() != null
                            ? point.getNetRealisedPnlDeltaUsd()
                            : point.getRealisedPnlDeltaUsd()),
                    MC
            );
            gasDeltaUsd = gasDeltaUsd.add(zeroIfNull(point.getGasDeltaUsd()), MC);
            uncoveredQuantityDelta = uncoveredQuantityDelta.add(zeroIfNull(point.getUncoveredQuantityDelta()), MC);
            memberPoints.add(point);
        }

        private List<AssetLedgerPoint> memberPoints() {
            return List.copyOf(memberPoints);
        }
    }

    private static final class DisplayEventAccumulator {
        private final String eventGroupId;
        private final String normalizedTransactionId;
        private final String txHash;
        private final Instant blockTimestamp;
        private final String normalizedType;
        private final String protocolName;
        private final String lifecycleKind;
        private final String lifecycleStage;
        private final LinkedHashSet<String> basisEffects = new LinkedHashSet<>();
        private final LinkedHashSet<String> walletAddresses = new LinkedHashSet<>();
        private final LinkedHashSet<String> networkIds = new LinkedHashSet<>();
        private final List<EventFlowView> flows;
        private final List<String> memberNormalizedTransactionIds;
        private final String fromAddress;
        private final String toAddress;
        private final BigDecimal quantityDelta;
        private final BigDecimal costBasisDeltaUsd;
        private final BigDecimal netCostBasisDeltaUsd;
        private final BigDecimal realisedPnlDeltaUsd;
        private final BigDecimal netRealisedPnlDeltaUsd;
        private final BigDecimal gasDeltaUsd;
        private final BigDecimal uncoveredQuantityDelta;
        private final List<AssetLedgerPoint> memberPoints;

        private DisplayEventAccumulator(
                String eventGroupId,
                String normalizedTransactionId,
                String txHash,
                Instant blockTimestamp,
                String normalizedType,
                String protocolName,
                String lifecycleKind,
                String lifecycleStage,
                Set<String> basisEffects,
                Set<String> walletAddresses,
                Set<String> networkIds,
                List<EventFlowView> flows,
                List<String> memberNormalizedTransactionIds,
                String fromAddress,
                String toAddress,
                BigDecimal quantityDelta,
                BigDecimal costBasisDeltaUsd,
                BigDecimal netCostBasisDeltaUsd,
                BigDecimal realisedPnlDeltaUsd,
                BigDecimal netRealisedPnlDeltaUsd,
                BigDecimal gasDeltaUsd,
                BigDecimal uncoveredQuantityDelta,
                List<AssetLedgerPoint> memberPoints
        ) {
            this.eventGroupId = eventGroupId;
            this.normalizedTransactionId = normalizedTransactionId;
            this.txHash = txHash;
            this.blockTimestamp = blockTimestamp;
            this.normalizedType = normalizedType;
            this.protocolName = protocolName;
            this.lifecycleKind = lifecycleKind;
            this.lifecycleStage = lifecycleStage;
            this.basisEffects.addAll(basisEffects);
            this.walletAddresses.addAll(walletAddresses);
            this.networkIds.addAll(networkIds);
            this.flows = List.copyOf(flows);
            this.memberNormalizedTransactionIds = List.copyOf(memberNormalizedTransactionIds);
            this.fromAddress = fromAddress;
            this.toAddress = toAddress;
            this.quantityDelta = quantityDelta;
            this.costBasisDeltaUsd = costBasisDeltaUsd;
            this.netCostBasisDeltaUsd = netCostBasisDeltaUsd;
            this.realisedPnlDeltaUsd = realisedPnlDeltaUsd;
            this.netRealisedPnlDeltaUsd = netRealisedPnlDeltaUsd;
            this.gasDeltaUsd = gasDeltaUsd;
            this.uncoveredQuantityDelta = uncoveredQuantityDelta;
            this.memberPoints = List.copyOf(memberPoints);
        }

        private List<AssetLedgerPoint> memberPoints() {
            return memberPoints;
        }

        private static DisplayEventAccumulator single(EventAccumulator event) {
            return new DisplayEventAccumulator(
                    event.normalizedTransactionId,
                    event.normalizedTransactionId,
                    event.txHash,
                    event.blockTimestamp,
                    event.normalizedType,
                    event.protocolName,
                    event.lifecycleKind,
                    event.lifecycleStage,
                    event.basisEffects,
                    event.walletAddresses,
                    event.networkIds,
                    event.flows,
                    List.of(event.normalizedTransactionId),
                    inferredFromAddress(event),
                    inferredToAddress(event),
                    event.quantityDelta,
                    event.costBasisDeltaUsd,
                    event.netCostBasisDeltaUsd,
                    event.realisedPnlDeltaUsd,
                    event.netRealisedPnlDeltaUsd,
                    event.gasDeltaUsd,
                    event.uncoveredQuantityDelta,
                    event.memberPoints()
            );
        }

        private static DisplayEventAccumulator internalTransfer(EventAccumulator outbound, EventAccumulator inbound) {
            LinkedHashSet<String> basisEffects = new LinkedHashSet<>();
            basisEffects.addAll(outbound.basisEffects);
            basisEffects.addAll(inbound.basisEffects);
            LinkedHashSet<String> walletAddresses = new LinkedHashSet<>();
            walletAddresses.addAll(outbound.walletAddresses);
            walletAddresses.addAll(inbound.walletAddresses);
            LinkedHashSet<String> networkIds = new LinkedHashSet<>();
            networkIds.addAll(outbound.networkIds);
            networkIds.addAll(inbound.networkIds);
            List<EventFlowView> flows = new ArrayList<>(outbound.flows);
            flows.addAll(inbound.flows);
            List<AssetLedgerPoint> memberPoints = new ArrayList<>(outbound.memberPoints());
            memberPoints.addAll(inbound.memberPoints());
            return new DisplayEventAccumulator(
                    internalTransferGroupId(outbound, inbound),
                    outbound.normalizedTransactionId,
                    outbound.txHash,
                    outbound.blockTimestamp,
                    "INTERNAL_TRANSFER",
                    firstNonBlank(outbound.protocolName, inbound.protocolName),
                    firstNonBlank(outbound.lifecycleKind, inbound.lifecycleKind),
                    firstNonBlank(outbound.lifecycleStage, inbound.lifecycleStage),
                    basisEffects,
                    walletAddresses,
                    networkIds,
                    flows,
                    List.of(outbound.normalizedTransactionId, inbound.normalizedTransactionId),
                    outbound.primaryWalletAddress,
                    inbound.primaryWalletAddress,
                    outbound.quantityDelta.add(inbound.quantityDelta, MC),
                    outbound.costBasisDeltaUsd.add(inbound.costBasisDeltaUsd, MC),
                    outbound.netCostBasisDeltaUsd.add(inbound.netCostBasisDeltaUsd, MC),
                    outbound.realisedPnlDeltaUsd.add(inbound.realisedPnlDeltaUsd, MC),
                    outbound.netRealisedPnlDeltaUsd.add(inbound.netRealisedPnlDeltaUsd, MC),
                    outbound.gasDeltaUsd.add(inbound.gasDeltaUsd, MC),
                    outbound.uncoveredQuantityDelta.add(inbound.uncoveredQuantityDelta, MC),
                    memberPoints
            );
        }
    }

    private static String protocolName(AssetLedgerPoint point, NormalizedTransaction transaction) {
        if (transaction != null && transaction.getProtocolName() != null && !transaction.getProtocolName().isBlank()) {
            return transaction.getProtocolName();
        }
        return point.getProtocolName();
    }

    private static List<EventFlowView> eventFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .map(flow -> new EventFlowView(
                        flow.getRole() == null ? null : flow.getRole().name(),
                        flow.getAssetContract(),
                        flow.getAssetSymbol(),
                        flow.getQuantityDelta(),
                        flow.getUnitPriceUsd(),
                        flow.getValueUsd(),
                        flow.getPriceSource() == null ? null : flow.getPriceSource().name(),
                        flow.getLogIndex()
                ))
                .toList();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String inferredFromAddress(EventAccumulator event) {
        if (isExternalTransferOut(event)) {
            return event.primaryWalletAddress;
        }
        if (isExternalTransferIn(event)) {
            return firstNonBlank(event.matchedCounterparty, event.counterpartyAddress);
        }
        String counterparty = firstNonBlank(event.counterpartyAddress, event.matchedCounterparty);
        if (blank(counterparty) || blank(event.primaryWalletAddress)) {
            return null;
        }
        EventDirection direction = inferEventDirection(event);
        if (direction == EventDirection.INBOUND) {
            return counterparty;
        }
        if (direction == EventDirection.OUTBOUND || direction == EventDirection.MIXED) {
            return event.primaryWalletAddress;
        }
        return null;
    }

    private static String inferredToAddress(EventAccumulator event) {
        if (isExternalTransferOut(event)) {
            return firstNonBlank(event.matchedCounterparty, event.counterpartyAddress);
        }
        if (isExternalTransferIn(event)) {
            return event.primaryWalletAddress;
        }
        String counterparty = firstNonBlank(event.counterpartyAddress, event.matchedCounterparty);
        if (blank(counterparty) || blank(event.primaryWalletAddress)) {
            return null;
        }
        EventDirection direction = inferEventDirection(event);
        if (direction == EventDirection.INBOUND) {
            return event.primaryWalletAddress;
        }
        if (direction == EventDirection.OUTBOUND || direction == EventDirection.MIXED) {
            return counterparty;
        }
        return null;
    }

    private enum EventDirection {
        OUTBOUND,
        INBOUND,
        MIXED,
        UNKNOWN
    }

    private static EventDirection inferEventDirection(EventAccumulator event) {
        boolean outbound = event.quantityDelta.signum() < 0
                || event.basisEffects.contains("CARRY_OUT")
                || event.basisEffects.contains("DISPOSE")
                || event.basisEffects.contains("REALLOCATE_OUT")
                || event.basisEffects.contains("GAS_ONLY");
        boolean inbound = event.quantityDelta.signum() > 0
                || event.basisEffects.contains("CARRY_IN")
                || event.basisEffects.contains("ACQUIRE")
                || event.basisEffects.contains("REALLOCATE_IN");
        if (outbound && inbound) {
            return EventDirection.MIXED;
        }
        if (outbound) {
            return EventDirection.OUTBOUND;
        }
        if (inbound) {
            return EventDirection.INBOUND;
        }
        return EventDirection.UNKNOWN;
    }

    private static String internalTransferGroupId(EventAccumulator outbound, EventAccumulator inbound) {
        String txHash = blank(outbound.txHash) ? outbound.normalizedTransactionId : outbound.txHash;
        return txHash
                + ":internal:"
                + normalizeAddress(outbound.primaryWalletAddress)
                + ":"
                + normalizeAddress(inbound.primaryWalletAddress);
    }

    private static String firstNonBlank(String left, String right) {
        if (!blank(left)) {
            return left;
        }
        return blank(right) ? null : right;
    }

    private static String fallbackFamilyIdentity(NetworkId networkId, String assetSymbol, String assetContract) {
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(assetSymbol, assetContract);
        if (familyIdentity != null && familyIdentity.startsWith("FAMILY:")) {
            return familyIdentity;
        }
        String symbol = normalizeSymbol(assetSymbol);
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(networkId, assetSymbol, assetContract);
        if (!blank(identity)) {
            return identity;
        }
        return symbol.isBlank() ? null : "SYMBOL:" + symbol;
    }

    private static String resolvedFamilyIdentity(
            AssetLedgerPoint latestPoint,
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
        String fallbackFamilyIdentity = fallbackFamilyIdentity(networkId, assetSymbol, assetContract);
        if (latestPoint == null || blank(latestPoint.getAccountingFamilyIdentity())) {
            return fallbackFamilyIdentity;
        }
        String latestFamilyIdentity = latestPoint.getAccountingFamilyIdentity();
        if (!latestFamilyIdentity.startsWith("FAMILY:")
                && fallbackFamilyIdentity != null
                && fallbackFamilyIdentity.startsWith("FAMILY:")) {
            return fallbackFamilyIdentity;
        }
        return latestFamilyIdentity;
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static int unresolvedFlagCountAfter(AssetLedgerPoint latestPoint) {
        if (latestPoint == null || latestPoint.getUnresolvedFlagCountAfter() == null) {
            return 0;
        }
        return latestPoint.getUnresolvedFlagCountAfter();
    }

    private static String uncoveredReason(
            AssetLedgerPoint latestPoint,
            BigDecimal currentQuantity,
            BigDecimal coveredQuantity
    ) {
        if (latestPoint == null) {
            return "missing_replay_point";
        }
        boolean uncoveredQuantity = coveredQuantity == null || currentQuantity == null
                ? true
                : coveredQuantity.compareTo(currentQuantity) < 0;
        boolean incompleteHistory = Boolean.TRUE.equals(latestPoint.getHasIncompleteHistoryAfter());
        boolean unresolvedFlags = Boolean.TRUE.equals(latestPoint.getHasUnresolvedFlagsAfter());
        if (uncoveredQuantity && !incompleteHistory && !unresolvedFlags && isYieldAccrualCandidate(latestPoint)) {
            return "yield_accrual";
        }
        if (uncoveredQuantity) {
            return "coverage_gap";
        }
        if (incompleteHistory || unresolvedFlags) {
            return "history_flags";
        }
        return null;
    }

    private static boolean isYieldAccrualCandidate(AssetLedgerPoint latestPoint) {
        if (latestPoint == null || latestPoint.getBasisEffect() != AssetLedgerPoint.BasisEffect.REALLOCATE_IN) {
            return false;
        }
        return latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.LENDING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.STAKING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.VAULT;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
