package com.walletradar.application.liquiditypools.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.application.liquiditypools.enrichment.GmxCollectedFeesReader;
import com.walletradar.application.liquiditypools.persistence.LpEarningPoint;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionLpQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final EnumSet<NormalizedTransactionType> LP_TYPES = EnumSet.of(
            NormalizedTransactionType.LP_ENTRY,
            NormalizedTransactionType.LP_ENTRY_REQUEST,
            NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
            NormalizedTransactionType.LP_EXIT,
            NormalizedTransactionType.LP_EXIT_PARTIAL,
            NormalizedTransactionType.LP_EXIT_FINAL,
            NormalizedTransactionType.LP_EXIT_REQUEST,
            NormalizedTransactionType.LP_EXIT_SETTLEMENT,
            NormalizedTransactionType.LP_ADJUST,
            NormalizedTransactionType.LP_FEE_CLAIM,
            NormalizedTransactionType.LP_POSITION_STAKE,
            NormalizedTransactionType.LP_POSITION_UNSTAKE
    );
    private static final EnumSet<NormalizedTransactionType> ENTRY_TYPES = EnumSet.of(
            NormalizedTransactionType.LP_ENTRY,
            NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
            NormalizedTransactionType.LP_ADJUST
    );
    private static final EnumSet<NormalizedTransactionType> EXIT_TYPES = EnumSet.of(
            NormalizedTransactionType.LP_EXIT,
            NormalizedTransactionType.LP_EXIT_PARTIAL,
            NormalizedTransactionType.LP_EXIT_FINAL,
            NormalizedTransactionType.LP_EXIT_SETTLEMENT
    );

    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final MongoOperations mongoOperations;
    private final LpPositionSnapshotService snapshotService;
    private final LpEarningPointService earningPointService;
    private final HistoricalPriceCacheService historicalPriceCacheService;
    private final PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;
    private final LiquidityPoolsProperties properties;
    private final Cache<String, SessionLpView> sessionLpCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(45, TimeUnit.SECONDS)
            .build();

    public Optional<SessionLpView> findSessionLp(String sessionId) {
        return findSessionLp(sessionId, LpPositionScope.ACTIVE);
    }

    public Optional<SessionLpView> findSessionLp(String sessionId, LpPositionScope scope) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalizedSessionId = sessionId.trim();
        String cacheKey = normalizedSessionId + "|" + scope.name();
        SessionLpView cached = sessionLpCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SessionLpView> loaded = userSessionRepository.findById(normalizedSessionId)
                .map(session -> toView(session, scope, null));
        loaded.ifPresent(view -> sessionLpCache.put(cacheKey, view));
        return loaded;
    }

    public Optional<LpPositionView> findSessionLpPosition(
            String sessionId,
            String correlationId,
            LpPositionScope scope
    ) {
        if (sessionId == null || sessionId.isBlank() || correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> toView(session, scope, correlationId.trim()))
                .flatMap(view -> view.positions().stream().findFirst());
    }

    public boolean ownsCorrelationId(String sessionId, String correlationId) {
        return findSessionLpPosition(sessionId, correlationId, LpPositionScope.ALL).isPresent();
    }

    private SessionLpView toView(UserSession session, LpPositionScope scope, String targetCorrelationId) {
        List<String> wallets = walletAddresses(session);
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<NormalizedTransaction> txs = loadLpTransactions(wallets);
        List<LpReceiptBasisPool> basisPools = loadBasisPools(universeScope.accountingUniverseId());
        List<AssetLedgerPoint> ledgerPoints = loadLedgerPoints(universeScope.accountingUniverseId());
        Map<String, LpPositionSnapshot> snapshots = snapshotService.findByUniverseId(universeScope.accountingUniverseId())
                .stream()
                .collect(Collectors.toMap(LpPositionSnapshot::getCorrelationId, s -> s, (a, b) -> a, LinkedHashMap::new));
        Map<String, BigDecimal> prices = loadCurrentPrices(txs, basisPools, snapshots);
        HistoricalFlowUsdResolver historicalFlowUsdResolver = new HistoricalFlowUsdResolver(
                historicalPriceCacheService,
                priceExternalSourceOrchestrator
        );

        Map<String, PositionAccumulator> accumulators = new LinkedHashMap<>();
        for (NormalizedTransaction tx : txs) {
            String corr = tx.getCorrelationId();
            if (corr == null || !isLpCorrelation(corr)) {
                continue;
            }
            accumulators.computeIfAbsent(corr, key -> new PositionAccumulator(key, tx))
                    .addTransaction(tx, historicalFlowUsdResolver);
        }
        for (LpReceiptBasisPool pool : basisPools) {
            if (pool.getLpCorrelationId() != null) {
                accumulators.computeIfAbsent(pool.getLpCorrelationId(),
                        key -> new PositionAccumulator(key, null)).addBasis(pool);
            }
        }
        for (AssetLedgerPoint point : ledgerPoints) {
            if (point.getCorrelationId() != null && isLpCorrelation(point.getCorrelationId())) {
                accumulators.computeIfAbsent(point.getCorrelationId(),
                        key -> new PositionAccumulator(key, null)).addLedger(point);
            }
        }

        List<LpPositionView> positions;
        if (targetCorrelationId != null) {
            PositionAccumulator target = accumulators.get(targetCorrelationId);
            if (target == null) {
                positions = List.of();
            } else {
                LpPositionView view = toPositionView(
                        target,
                        snapshots.get(target.correlationId()),
                        prices,
                        ledgerPoints,
                        historicalFlowUsdResolver
                );
                positions = isDust(view) || !matchesScope(view, scope) ? List.of() : List.of(view);
            }
        } else {
            positions = accumulators.values().stream()
                    .sorted(Comparator.comparing(PositionAccumulator::enteredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(acc -> toPositionView(acc, snapshots.get(acc.correlationId()), prices, ledgerPoints, historicalFlowUsdResolver))
                    .filter(p -> !isDust(p))
                    .filter(p -> matchesScope(p, scope))
                    .toList();
        }

        return new SessionLpView(session.getId(), buildSummary(positions), positions);
    }

    private static boolean matchesScope(LpPositionView position, LpPositionScope scope) {
        boolean closed = "closed".equals(position.status());
        return switch (scope) {
            case ACTIVE -> !closed;
            case CLOSED -> closed;
            case ALL -> true;
        };
    }

    private static String derivePair(String correlationId, LpPositionSnapshot snapshot) {
        String fromSnapshot = derivePairFromSnapshot(snapshot);
        if (fromSnapshot != null) return fromSnapshot;
        return derivePairFromCorrelationId(correlationId);
    }

    private static String derivePairFromSnapshot(LpPositionSnapshot snapshot) {
        if (snapshot == null) return null;
        String sym0 = snapshot.getToken0() != null ? snapshot.getToken0().getSym() : null;
        String sym1 = snapshot.getToken1() != null ? snapshot.getToken1().getSym() : null;
        if (sym0 == null && sym1 == null) return null;
        if (sym1 == null) return sym0;
        if (sym0 == null) return sym1;
        return sym0 + "/" + sym1;
    }

    /**
     * Derives a human-readable pair label from the correlationId when no snapshot or transaction
     * token symbols are available. Supports:
     * <ul>
     *   <li>{@code gmx-lp:{network}:{slug}} → slug tokens uppercased (e.g. "weth-usdc" → "WETH/USDC")</li>
     *   <li>{@code pendle-lp:{network}:{slug}} → slug uppercased</li>
     * </ul>
     */
    private static String derivePairFromCorrelationId(String correlationId) {
        if (correlationId == null) return null;
        String slug = null;
        if (correlationId.startsWith("gmx-lp:")) {
            String[] parts = correlationId.split(":", 3);
            if (parts.length >= 3 && !parts[2].isBlank()) {
                slug = parts[2];
            }
        } else if (correlationId.startsWith("pendle-lp:")) {
            String[] parts = correlationId.split(":", 3);
            if (parts.length >= 3 && !parts[2].isBlank()) {
                slug = parts[2];
            }
        }
        if (slug == null) return null;
        // "weth-usdc" → "WETH/USDC"; "pendle-lpt" → "PENDLE-LPT"
        String[] tokens = slug.split("-");
        if (tokens.length >= 2) {
            return java.util.Arrays.stream(tokens)
                    .map(t -> t.toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.joining("/"));
        }
        return slug.toUpperCase(Locale.ROOT);
    }

    private LpPositionView toPositionView(
            PositionAccumulator acc,
            LpPositionSnapshot snapshot,
            Map<String, BigDecimal> prices,
            List<AssetLedgerPoint> ledgerPoints,
            HistoricalFlowUsdResolver historicalFlowUsdResolver
    ) {
        boolean closed = resolveClosed(acc, snapshot);
        BigDecimal costBasisUsd = acc.costBasisUsd();
        BigDecimal claimedUsd = acc.claimedFeesUsd();
        BigDecimal realizedUsd = acc.realizedPnlUsd();
        BigDecimal withdrawnUsd = acc.withdrawnUsd();

        BigDecimal tvlUsd = closed ? BigDecimal.ZERO : markTvl(acc, snapshot, prices);
        LpFieldPrecision tvlPrecision = closed ? LpFieldPrecision.EXACT
                : tvlUsd != null ? LpFieldPrecision.ESTIMATE : LpFieldPrecision.UNAVAILABLE;

        Instant latestClaimAt = acc.latestClaimAt();
        BigDecimal unclaimedUsd = BigDecimal.ZERO;
        LpFieldPrecision unclaimedPrecision = LpFieldPrecision.UNAVAILABLE;
        if (!closed && snapshot != null && snapshot.getUnclaimedFeesByToken() != null
                && !snapshot.getUnclaimedFeesByToken().isEmpty()) {
            if (latestClaimAt == null || (snapshot.getSnapshotAt() != null && snapshot.getSnapshotAt().isAfter(latestClaimAt))) {
                unclaimedUsd = priceTokenMapUsd(snapshot.getUnclaimedFeesByToken(), prices);
                unclaimedPrecision = unclaimedUsd.signum() > 0 ? LpFieldPrecision.ESTIMATE : LpFieldPrecision.UNAVAILABLE;
            }
        } else if (!closed && snapshot != null && snapshot.getUnclaimedFeesUsd() != null) {
            if (latestClaimAt == null || (snapshot.getSnapshotAt() != null && snapshot.getSnapshotAt().isAfter(latestClaimAt))) {
                unclaimedUsd = snapshot.getUnclaimedFeesUsd();
                unclaimedPrecision = LpFieldPrecision.ESTIMATE;
            }
        }

        // GMX / GLV: fees compound into pool token price rather than being claimable separately.
        // Compute earned fees from the delta of cumulativeFeeUsdPerPoolValue (Subsquid GraphQL)
        // multiplied by the user's net-deposited principal (total deposits minus total withdrawals).
        // Using net-deposited avoids overcounting when the user made partial exits — the base
        // correctly shrinks by the amount already returned to the user.
        if (!closed && acc.singleReceiptFamily()
                && snapshot != null
                && snapshot.getEntryFeePerPoolValue() != null
                && snapshot.getCurrentFeePerPoolValue() != null) {
            BigDecimal grossDeposited = acc.depositedMarketUsd();
            BigDecimal withdrawn = acc.withdrawnUsd();
            BigDecimal netDeposited = grossDeposited.subtract(withdrawn, MC);
            // If net is negative (shouldn't normally happen) fall back to gross deposited.
            BigDecimal feeBase = netDeposited.signum() > 0 ? netDeposited : grossDeposited;
            if (feeBase != null && feeBase.signum() > 0) {
                BigDecimal delta = snapshot.getCurrentFeePerPoolValue()
                        .subtract(snapshot.getEntryFeePerPoolValue(), MC);
                if (delta.signum() > 0) {
                    BigDecimal earnedFees = delta.divide(GmxCollectedFeesReader.FEE_SCALE, MC)
                            .multiply(feeBase, MC);
                    if (earnedFees.signum() > 0) {
                        unclaimedUsd = earnedFees;
                        unclaimedPrecision = LpFieldPrecision.ESTIMATE;
                    }
                }
            }
        }

        BigDecimal feesTotal = claimedUsd.add(unclaimedUsd, MC);
        LpPositionView.IlView il = buildIl(acc, snapshot, closed, prices, tvlUsd);
        BigDecimal hodlNow = acc.hodlValueFromEntryUsd(prices);

        // AVCO cost basis (used for closed-position tax accounting & "Deposited" display)
        BigDecimal depositsUsd = costBasisUsd.signum() > 0 ? costBasisUsd : acc.depositedMarketUsd();
        boolean hasDeposits = depositsUsd != null && depositsUsd.signum() > 0;
        BigDecimal depositedMarketUsd = acc.depositedMarketUsd();
        LpFieldPrecision depositedMarketPrecision = depositedMarketUsd != null && depositedMarketUsd.signum() > 0
                ? LpFieldPrecision.EXACT
                : LpFieldPrecision.UNAVAILABLE;

        // For OPEN position PnL we isolate LP-specific performance (IL + fees),
        // independent of the user's pre-LP asset price history:
        //
        //  1. Prefer depositedMarketUsd (entry flows' valueUsd) — exact entry market price.
        //     NOTE: the current pricing pipeline does NOT set valueUsd on LP TRANSFER flows,
        //     so this is almost always zero right now.
        //  2. Fall back to hodlNow (entry qty × current price). With this base:
        //       netPnl = tvlUsd − hodlNow + fees + partialExits
        //             = (IL at current prices) + fees
        //     This is the canonical LP-analytics metric: how much did you gain/lose
        //     FROM being in the LP, compared to simply holding those tokens.
        //  3. Last resort: AVCO cost basis (shows asset-level gain/loss vs average cost;
        //     confusing for a freshly-opened LP position whose user has a high-basis asset).
        //
        // For CLOSED positions we always use AVCO (tax-correct realized PnL).
        BigDecimal openBase;
        boolean openBaseIsHodl = false;
        if (!closed) {
            if (acc.depositedMarketUsd().signum() > 0) {
                openBase = acc.depositedMarketUsd();
            } else if (hodlNow != null && hodlNow.signum() > 0) {
                openBase = hodlNow;
                openBaseIsHodl = true;
            } else {
                openBase = depositsUsd;
            }
        } else {
            openBase = depositsUsd;
        }
        log.debug("LP PnL base corrId={} closed={} depMarket={} hodlNow={} costBasis={} openBase={} isHodl={}",
                acc.correlationId(), closed,
                acc.depositedMarketUsd().toPlainString(),
                hodlNow != null ? hodlNow.toPlainString() : "null",
                costBasisUsd.toPlainString(),
                openBase != null ? openBase.toPlainString() : "null",
                openBaseIsHodl);
        boolean hasOpenBase = openBase != null && openBase.signum() > 0;

        // Price appreciation = how much the value of the originally deposited tokens has changed.
        // Meaningful only when openBase reflects an actual historical entry price.
        // When we fall back to hodlNow, the difference is always zero — suppress it.
        BigDecimal priceAppreciation = closed || !hasOpenBase || hodlNow == null || openBaseIsHodl
                ? null
                : hodlNow.subtract(openBase, MC);
        LpFieldPrecision priceAppPrecision = closed || acc.singleReceiptFamily()
                ? LpFieldPrecision.NOT_APPLICABLE
                : !hasOpenBase || hodlNow == null || openBaseIsHodl
                        ? LpFieldPrecision.UNAVAILABLE
                        : LpFieldPrecision.ESTIMATE;

        BigDecimal netPnl = null;
        LpFieldPrecision netPnlPrecision = LpFieldPrecision.UNAVAILABLE;
        if (!closed && hasOpenBase && tvlUsd != null) {
            netPnl = tvlUsd.add(feesTotal, MC).add(withdrawnUsd, MC).subtract(openBase, MC);
            netPnlPrecision = LpFieldPrecision.ESTIMATE;
        } else if (closed && hasDeposits) {
            netPnl = withdrawnUsd.add(feesTotal, MC).subtract(depositsUsd, MC);
            netPnlPrecision = LpFieldPrecision.ESTIMATE;
        }

        BigDecimal accountingUnrealized = closed || tvlUsd == null ? null : tvlUsd.add(unclaimedUsd, MC).subtract(costBasisUsd, MC);

        List<LpEarningPoint> earningSeries = earningPointService.findSeriesByCorrelationId(acc.correlationId());
        // Pass closedAt only for truly closed positions to bound the APR period correctly.
        // Open positions with partial exits must use Instant.now() as the period end.
        // For APR: prefer hodlNow as denominator for open positions (same current-price basis
        // as the netPnl numerator), so APR = feesTotal / hodlNow / days * 365.
        //
        // For CLOSED positions without AVCO deposit basis (depositsUsd = 0), fall back to
        // hodlNow (entryQty × currentPrice) as the principal estimate — an approximation
        // using current prices rather than historical entry prices.
        //
        // For CLOSED positions without separate fee records (feesTotal = 0), infer fees
        // as: feesProxy = max(0, withdrawnUsd − hodlNow).  This represents the net LP
        // advantage over HODL at current prices: fees earned minus IL.  If positive, the
        // LP was net profitable and we can annualise the return.
        BigDecimal aprBase = closed
                ? (hasDeposits ? depositsUsd : (hodlNow != null && hodlNow.signum() > 0 ? hodlNow : null))
                : openBase;
        // For closed positions: sum ALL daily earnings from the series (no 30-day cap).
        // Use the larger of (series sum, claimedFeesUsd) — both estimate the same thing but
        // series entries can contain near-zero noise (1E-33 etc.) while claimed fees from
        // ledger are exact. Taking the max ensures noise doesn't overwrite real fee data.
        BigDecimal feesForApr = feesTotal;
        if (closed && !earningSeries.isEmpty()) {
            // Sum only non-negative daily earnings: the close-day entry is often a negative
            // correction (unwind of accruals) that would zero out the real series total.
            BigDecimal seriesSum = earningSeries.stream()
                    .map(p -> p.getDailyEarnedUsd() != null ? p.getDailyEarnedUsd() : BigDecimal.ZERO)
                    .filter(d -> d.signum() > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Only prefer the series sum when it meaningfully exceeds the claimed fees
            if (seriesSum.compareTo(new BigDecimal("0.001")) > 0) {
                feesForApr = feesForApr != null && feesForApr.compareTo(seriesSum) > 0
                        ? feesForApr : seriesSum;
            }
        }
        // For closed positions without separate fee records (feesTotal = 0), infer fees
        // as: max(0, withdrawnUsd − hodlNow). This represents the net LP advantage over HODL
        // at current prices: fees earned minus IL. Only apply when hodlNow > 0 so the proxy
        // is meaningful (entry tokens are priced).
        if (closed && (feesForApr == null || feesForApr.signum() == 0)
                && acc.withdrawnUsd().signum() > 0 && hodlNow != null && hodlNow.signum() > 0) {
            BigDecimal feesProxy = acc.withdrawnUsd().subtract(hodlNow, MC);
            if (feesProxy.signum() > 0) {
                feesForApr = feesProxy;
            }
        }
        LpPositionView.AprView apr = buildApr(snapshot, earningSeries, feesForApr, aprBase, acc.enteredAt(), closed ? acc.closedAt() : null);
        Map<String, BigDecimal> txLedgerUsd = ledgerUsdByTransaction(ledgerPoints, acc.correlationId());

        List<LpPositionView.FeeTokenView> perTokenFees;
        if (!closed && snapshot != null && snapshot.getUnclaimedFeesByToken() != null) {
            perTokenFees = snapshot.getUnclaimedFeesByToken().entrySet().stream()
                    .map(e -> {
                        BigDecimal qty = e.getValue();
                        BigDecimal usd = markQty(e.getKey(), qty, prices);
                        return new LpPositionView.FeeTokenView(e.getKey(), qty, usd, null, null);
                    })
                    .toList();
        } else {
            perTokenFees = List.of();
        }

        String resolvedProtocol = acc.protocol() != null ? acc.protocol()
                : snapshot != null ? snapshot.getProtocol() : null;
        // Prefer snapshot family (e.g. GLV_LP vs GMX_LP) over the accumulator heuristic which
        // can only distinguish CL_NFT/GMX_LP/PENDLE_LP from the correlationId prefix.
        String resolvedFamily = snapshot != null && snapshot.getFamily() != null
                ? snapshot.getFamily()
                : acc.family();
        return new LpPositionView(
                acc.correlationId(),
                resolvedProtocol,
                resolvedFamily,
                acc.networkId(),
                acc.walletAddress(),
                acc.pair() != null ? acc.pair() : derivePair(acc.correlationId(), snapshot),
                acc.tokenId(),
                resolveStatus(closed, snapshot),
                snapshot != null && snapshot.getStaked() != null ? snapshot.getStaked() : acc.staked(),
                tokenView(acc, snapshot, 0, prices, closed),
                tokenView(acc, snapshot, 1, prices, closed),
                snapshot != null ? snapshot.getFeeTierPct() : null,
                rangeView(snapshot, acc.singleReceiptFamily(), closed),
                tvlUsd,
                tvlPrecision,
                costBasisUsd,
                LpFieldPrecision.EXACT,
                depositedMarketUsd,
                depositedMarketPrecision,
                entryTokenView(acc, snapshot, 0, closed),
                entryTokenView(acc, snapshot, 1, closed),
                withdrawnUsd,
                LpFieldPrecision.EXACT,
                new LpPositionView.FeesView(
                        claimedUsd,
                        unclaimedUsd,
                        perTokenFees,
                        LpFieldPrecision.EXACT,
                        unclaimedPrecision
                ),
                il,
                priceAppreciation,
                priceAppPrecision,
                netPnl,
                netPnlPrecision,
                accountingUnrealized,
                closed ? LpFieldPrecision.NOT_APPLICABLE : LpFieldPrecision.ESTIMATE,
                apr,
                earningSeries.stream()
                        .map(p -> new LpPositionView.EarningDayView(
                                p.getDay(), p.getDailyEarnedUsd(), LpFieldPrecision.ESTIMATE))
                        .toList(),
                earningSeries.stream()
                        .map(p -> new LpPositionView.AprDayView(
                                p.getDay(), p.getDailyAprPct(), LpFieldPrecision.ESTIMATE))
                        .toList(),
                acc.txns().stream().map(tx -> toTxnView(tx, prices, txLedgerUsd, historicalFlowUsdResolver)).toList(),
                acc.enteredAt(),
                acc.closedAt(),
                snapshot != null ? snapshot.getSnapshotAt() : null,
                snapshot != null ? snapshot.getSnapshotStale() : null,
                snapshot != null ? snapshot.getUnavailableReason() : null
        );
    }

    private static LpPositionView.IlView buildIl(
            PositionAccumulator acc,
            LpPositionSnapshot snapshot,
            boolean closed,
            Map<String, BigDecimal> prices,
            BigDecimal tvlUsd
    ) {
        if (closed || acc.singleReceiptFamily()) {
            return new LpPositionView.IlView(null, null, LpFieldPrecision.NOT_APPLICABLE);
        }
        BigDecimal hodl = acc.hodlValueFromEntryUsd(prices);
        if (hodl == null || tvlUsd == null) {
            return new LpPositionView.IlView(null, null, LpFieldPrecision.UNAVAILABLE);
        }
        BigDecimal ilUsd = tvlUsd.subtract(hodl, MC);
        BigDecimal ilPct = hodl.signum() == 0 ? null : ilUsd.divide(hodl, MC).multiply(BigDecimal.valueOf(100), MC);
        return new LpPositionView.IlView(ilPct, ilUsd, LpFieldPrecision.ESTIMATE);
    }

    private static boolean resolveClosed(PositionAccumulator acc, LpPositionSnapshot snapshot) {
        if (acc.closed()) {
            return true;
        }
        if (snapshot != null && "closed".equals(snapshot.getStatus())) {
            return true;
        }
        // Snapshot explicitly says the position is open — trust it over the transaction heuristic.
        // This is critical for single-receipt protocols (GMX, Pendle) where LP tokens received
        // on entry are positive flows (not tracked as netQtyBySymbol) while exit settlements
        // return underlying tokens (also positive) that get negated, making fullyExited() return
        // a false positive "closed".
        if (snapshot != null
                && ("in_range".equals(snapshot.getStatus()) || "out_of_range".equals(snapshot.getStatus()))) {
            return false;
        }
        return acc.fullyExited();
    }

    private static String resolveStatus(boolean closed, LpPositionSnapshot snapshot) {
        if (closed) {
            return "closed";
        }
        if (snapshot == null || snapshot.getSnapshotAt() == null || Boolean.TRUE.equals(snapshot.getSnapshotStale())) {
            return "unknown";
        }
        String snapshotStatus = snapshot.getStatus();
        if (snapshotStatus == null || snapshotStatus.isBlank()) {
            return "unknown";
        }
        return snapshotStatus;
    }

    private static LpPositionView.TokenView entryTokenView(
            PositionAccumulator acc,
            LpPositionSnapshot snapshot,
            int index,
            boolean closed
    ) {
        // For single-receipt families, token1 has no entry data (only one LP receipt token).
        if (index == 1 && acc.singleReceiptFamily()) {
            LpPositionSnapshot.TokenSide side1 = snapshot != null ? snapshot.getToken1() : null;
            if (side1 == null) {
                return new LpPositionView.TokenView(null, null, null, null, null,
                        LpFieldPrecision.NOT_APPLICABLE, LpFieldPrecision.NOT_APPLICABLE);
            }
        }
        String sym = snapshotTokenSymbol(snapshot, index);
        if (sym == null) {
            sym = acc.tokenSymbol(index);
        }
        if (sym == null) {
            sym = acc.entrySymbol(index);
        }
        if (sym == null) {
            return new LpPositionView.TokenView(null, null, null, null, null,
                    LpFieldPrecision.UNAVAILABLE, LpFieldPrecision.UNAVAILABLE);
        }
        BigDecimal qty = acc.entryQtyForSymbol(sym);
        if (qty == null) {
            qty = acc.entryQty(index);
        }
        BigDecimal usd;
        LpFieldPrecision usdPrec;
        if (closed) {
            // For closed positions: show AVCO cost basis (tax-relevant historical cost).
            usd = acc.basisUsdForSymbol(sym);
            if (usd == null) {
                usd = acc.entryValueForSymbol(sym);
            }
            usdPrec = usd == null ? LpFieldPrecision.UNAVAILABLE : LpFieldPrecision.ESTIMATE;
        } else {
            // For open positions: show the historical entry market value for this token side.
            // If historical valuation is unavailable, keep USD empty instead of silently
            // re-marking the entry quantity at the CURRENT price under the misleading
            // "At entry" label.
            usd = acc.entryValueForSymbol(sym);
            usdPrec = usd == null ? LpFieldPrecision.UNAVAILABLE : LpFieldPrecision.ESTIMATE;
        }
        return new LpPositionView.TokenView(
                sym,
                null,
                qty,
                usd,
                null,
                qty == null ? LpFieldPrecision.UNAVAILABLE : LpFieldPrecision.EXACT,
                usdPrec
        );
    }

    private static String snapshotTokenSymbol(LpPositionSnapshot snapshot, int index) {
        if (snapshot == null) {
            return null;
        }
        LpPositionSnapshot.TokenSide side = index == 0 ? snapshot.getToken0() : snapshot.getToken1();
        return side != null && side.getSym() != null ? side.getSym().trim().toUpperCase(Locale.ROOT) : null;
    }

    private static LpPositionView.RangeView rangeView(LpPositionSnapshot snapshot, boolean singleReceipt, boolean closed) {
        if (singleReceipt || closed || snapshot == null) {
            return new LpPositionView.RangeView(null, null, null, null, null, null, List.of(), LpFieldPrecision.NOT_APPLICABLE);
        }
        List<LpPositionView.LiquidityBinView> bins = snapshot.getLiquidityBins() == null ? List.of() :
                snapshot.getLiquidityBins().stream()
                        .map(b -> new LpPositionView.LiquidityBinView(
                                b.getTickLower(), b.getTickUpper(),
                                b.getPriceLower(), b.getPriceUpper(),
                                b.getLiquidityShare()))
                        .toList();
        return new LpPositionView.RangeView(
                snapshot.getPriceLow(),
                snapshot.getPriceHigh(),
                snapshot.getPriceCurrent(),
                snapshot.getTickLower(),
                snapshot.getTickUpper(),
                snapshot.getCurrentTick(),
                bins,
                LpFieldPrecision.ESTIMATE
        );
    }

    private static LpPositionView.TokenView tokenView(
            PositionAccumulator acc,
            LpPositionSnapshot snapshot,
            int index,
            Map<String, BigDecimal> prices,
            boolean closed
    ) {
        LpPositionSnapshot.TokenSide side = snapshot != null
                ? (index == 0 ? snapshot.getToken0() : snapshot.getToken1()) : null;
        // For single-receipt protocols (GMX, Pendle), netQtyBySymbol is polluted by
        // exit-settlement inbound flows (WETH/USDC received on exit). Only show token1
        // if the snapshot explicitly has a token1 — otherwise return an empty slot.
        if (index == 1 && acc.singleReceiptFamily() && side == null) {
            return new LpPositionView.TokenView(null, null, null, null, null,
                    LpFieldPrecision.NOT_APPLICABLE, LpFieldPrecision.NOT_APPLICABLE);
        }
        String sym = side != null && side.getSym() != null ? side.getSym() : acc.tokenSymbol(index);
        String contract = side != null ? side.getContract() : acc.tokenContract(index);
        BigDecimal qty = closed ? BigDecimal.ZERO : side != null ? side.getQty() : acc.netQty(index);
        BigDecimal usd = closed ? BigDecimal.ZERO : markQty(sym, qty, prices);
        // hodlUsd = entryQty for matching canonical symbol × currentPrice
        BigDecimal hodlUsd = null;
        if (!closed && sym != null) {
            BigDecimal entryQty = acc.entryQtyForHodl(sym);
            if (entryQty != null) {
                hodlUsd = markQty(sym, entryQty, prices);
            }
        }
        return new LpPositionView.TokenView(
                sym, contract, qty, usd, hodlUsd,
                LpFieldPrecision.EXACT,
                usd == null ? LpFieldPrecision.UNAVAILABLE : LpFieldPrecision.ESTIMATE
        );
    }

    private static BigDecimal markTvl(PositionAccumulator acc, LpPositionSnapshot snapshot, Map<String, BigDecimal> prices) {
        // For single-receipt protocols (GMX, Pendle), netQtyBySymbol is polluted by exit-settlement
        // inbound flows (WETH/USDC received on exit). The snapshot's stored TVL — computed by the
        // enrichment service from the on-chain GM/GLV balance — is authoritative.
        if (acc.singleReceiptFamily()) {
            if (snapshot != null && snapshot.getTvlUsd() != null && snapshot.getTvlUsd().signum() >= 0) {
                return snapshot.getTvlUsd();
            }
            return null;
        }
        BigDecimal fromTokens = BigDecimal.ZERO;
        boolean any = false;
        for (int i = 0; i < 2; i++) {
            LpPositionSnapshot.TokenSide side = snapshot != null
                    ? (i == 0 ? snapshot.getToken0() : snapshot.getToken1()) : null;
            String sym = side != null && side.getSym() != null ? side.getSym() : acc.tokenSymbol(i);
            BigDecimal qty = side != null ? side.getQty() : acc.netQty(i);
            BigDecimal usd = markQty(sym, qty, prices);
            if (usd != null) {
                fromTokens = fromTokens.add(usd, MC);
                any = true;
            }
        }
        if (any) {
            return fromTokens;
        }
        // Fall back to the TVL stored in the snapshot (e.g. protocols where the LP token
        // is priced by the enrichment service but not in the standard prices map).
        if (snapshot != null && snapshot.getTvlUsd() != null && snapshot.getTvlUsd().signum() > 0) {
            return snapshot.getTvlUsd();
        }
        return null;
    }

    private static BigDecimal markQty(String symbol, BigDecimal qty, Map<String, BigDecimal> prices) {
        if (qty == null || symbol == null || qty.signum() == 0) {
            return null;
        }
        BigDecimal price = priceLookup(symbol, prices);
        return price == null ? null : qty.multiply(price, MC);
    }

    private static BigDecimal priceLookup(String symbol, Map<String, BigDecimal> prices) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(symbol)) {
            return BigDecimal.ONE;
        }
        String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(symbol);
        BigDecimal price = prices.get(canonical);
        if (price != null) {
            return price;
        }
        return prices.get(symbol.toUpperCase(Locale.ROOT));
    }

    private static BigDecimal priceTokenMapUsd(Map<String, BigDecimal> perToken, Map<String, BigDecimal> prices) {
        if (perToken == null || perToken.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (var entry : perToken.entrySet()) {
            BigDecimal usd = markQty(entry.getKey(), entry.getValue(), prices);
            if (usd != null) {
                total = total.add(usd, MC);
                any = true;
            }
        }
        return any ? total : BigDecimal.ZERO;
    }

    private static Map<String, BigDecimal> ledgerUsdByTransaction(List<AssetLedgerPoint> ledgerPoints, String correlationId) {
        Map<String, BigDecimal> byTx = new LinkedHashMap<>();
        for (AssetLedgerPoint point : ledgerPoints) {
            if (point == null || point.getNormalizedTransactionId() == null) {
                continue;
            }
            if (!correlationId.equals(point.getCorrelationId())) {
                continue;
            }
            if (point.getCostBasisDeltaUsd() != null && point.getCostBasisDeltaUsd().signum() != 0) {
                byTx.merge(point.getNormalizedTransactionId(), point.getCostBasisDeltaUsd().abs(), BigDecimal::add);
            }
        }
        return byTx;
    }

    private static LpPositionView.AprView buildApr(
            LpPositionSnapshot snapshot,
            List<LpEarningPoint> series,
            BigDecimal feesTotalUsd,
            BigDecimal depositsUsd,
            Instant enteredAt,
            Instant closedAt
    ) {
        // For open positions: use current APR from snapshot (point-in-time annualized fee rate)
        BigDecimal now = closedAt == null && snapshot != null ? snapshot.getAprNow() : null;

        // Lifetime APR = totalFees / depositAmount / daysActive * 365
        // For closed positions: daysActive = closedAt - enteredAt (not "now")
        BigDecimal lifetimeApr = null;
        if (feesTotalUsd != null && feesTotalUsd.compareTo(BigDecimal.ZERO) > 0
                && depositsUsd != null && depositsUsd.compareTo(BigDecimal.ZERO) > 0
                && enteredAt != null) {
            Instant periodEnd = closedAt != null ? closedAt : Instant.now();
            long daysActive = Math.max(1, java.time.Duration.between(enteredAt, periodEnd).toDays());
            lifetimeApr = feesTotalUsd
                    .divide(depositsUsd, MC)
                    .divide(BigDecimal.valueOf(daysActive), MC)
                    .multiply(BigDecimal.valueOf(36500), MC);
        }
        return new LpPositionView.AprView(
                now,
                lifetimeApr,
                now == null && lifetimeApr == null ? LpFieldPrecision.UNAVAILABLE : LpFieldPrecision.ESTIMATE
        );
    }

    private LpSummaryView buildSummary(List<LpPositionView> positions) {
        BigDecimal activeTvl = BigDecimal.ZERO;
        BigDecimal feesEarned = BigDecimal.ZERO;
        BigDecimal unclaimed = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        int inRange = 0;
        int outOfRange = 0;
        for (LpPositionView p : positions) {
            if (!"closed".equals(p.status())) {
                activeTvl = activeTvl.add(zero(p.tvlUsd()), MC);
                if ("in_range".equals(p.status())) {
                    inRange++;
                } else if ("out_of_range".equals(p.status())) {
                    outOfRange++;
                }
            }
            feesEarned = feesEarned.add(zero(p.fees().claimedUsd()), MC);
            unclaimed = unclaimed.add(zero(p.fees().unclaimedUsd()), MC);
            realized = realized.add(zero(p.fees().claimedUsd()), MC);
        }
        return new LpSummaryView(
                activeTvl, feesEarned, unclaimed, inRange, outOfRange, realized,
                LpFieldPrecision.ESTIMATE, LpFieldPrecision.EXACT, LpFieldPrecision.ESTIMATE, LpFieldPrecision.EXACT
        );
    }

    private boolean isDust(LpPositionView position) {
        if (properties.getDustThresholdUsd() == null) {
            return false;
        }
        if ("closed".equals(position.status())) {
            return false;
        }
        // Positions with recorded transactions are real LP activity, never dust
        // (covers GMX / protocol-settlement positions that have no on-chain snapshot yet)
        if (position.txns() != null && !position.txns().isEmpty()) {
            return false;
        }
        if (position.costBasisUsd() != null && position.costBasisUsd().compareTo(properties.getDustThresholdUsd()) >= 0) {
            return false;
        }
        if (position.depositedMarketUsd() != null && position.depositedMarketUsd().compareTo(properties.getDustThresholdUsd()) >= 0) {
            return false;
        }
        if (position.snapshotAt() != null) {
            return false;
        }
        BigDecimal tvl = zero(position.tvlUsd());
        return tvl.compareTo(properties.getDustThresholdUsd()) < 0;
    }

    private LpPositionView.TxnView toTxnView(
            NormalizedTransaction tx,
            Map<String, BigDecimal> prices,
            Map<String, BigDecimal> txLedgerUsd,
            HistoricalFlowUsdResolver historicalFlowUsdResolver
    ) {
        List<NormalizedTransaction.Flow> flows = tx.getFlows() == null ? List.of() : tx.getFlows().stream()
                .filter(flow -> flow != null
                        && flow.getAssetSymbol() != null
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0
                        && flow.getRole() != NormalizedLegRole.FEE
                        && !isLpReceiptSymbol(flow.getAssetSymbol()))
                .toList();
        NormalizedTransaction.Flow flow0 = flows.isEmpty() ? null : flows.getFirst();
        NormalizedTransaction.Flow flow1 = flows.size() > 1 ? flows.get(1) : null;

        BigDecimal leg0Usd = flowUsd(tx, flow0, prices, historicalFlowUsdResolver);
        BigDecimal leg1Usd = flowUsd(tx, flow1, prices, historicalFlowUsdResolver);
        BigDecimal gasFee = tx.getFlows() == null ? null : tx.getFlows().stream()
                .filter(f -> f != null && f.getRole() == NormalizedLegRole.FEE && f.getValueUsd() != null)
                .map(NormalizedTransaction.Flow::getValueUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Primary: sum of priced legs (consistent with per-leg USD shown); fallback: ledger
        BigDecimal totalUsd;
        if (leg0Usd != null || leg1Usd != null) {
            totalUsd = sumNullable(leg0Usd, leg1Usd);
        } else {
            totalUsd = txLedgerUsd.get(tx.getId());
        }

        return new LpPositionView.TxnView(
                tx.getId(),
                tx.getTxHash(),
                tx.getBlockTimestamp(),
                tx.getType().name(),
                flow0 != null ? flow0.getAssetSymbol() : null,
                flow0 != null ? flow0.getQuantityDelta() : null,
                leg0Usd,
                flow1 != null ? flow1.getAssetSymbol() : null,
                flow1 != null ? flow1.getQuantityDelta() : null,
                leg1Usd,
                totalUsd,
                gasFee != null && gasFee.signum() > 0 ? gasFee : null
        );
    }

    private static BigDecimal flowUsd(
            NormalizedTransaction tx,
            NormalizedTransaction.Flow flow,
            Map<String, BigDecimal> prices,
            HistoricalFlowUsdResolver historicalFlowUsdResolver
    ) {
        if (flow == null) {
            return null;
        }
        BigDecimal historicalUsd = flowUsdAtEvent(tx, flow, historicalFlowUsdResolver);
        if (historicalUsd != null) {
            return historicalUsd;
        }
        if (flow.getQuantityDelta() == null || flow.getAssetSymbol() == null) {
            return null;
        }
        // GM/GLV receipt tokens in LP_ENTRY_SETTLEMENT have no reliable historical price —
        // falling back to current market price is misleading because it implies the settlement
        // was worth less than the actual deposit (ETH price movement). Suppress the fallback
        // so that the total comes from the ledger cost basis instead.
        if (tx.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                && isGmxReceiptSymbol(flow.getAssetSymbol())) {
            return null;
        }
        return markQty(flow.getAssetSymbol(), flow.getQuantityDelta().abs(), prices);
    }

    private static boolean isGmxReceiptSymbol(String symbol) {
        if (symbol == null) return false;
        String upper = symbol.toUpperCase(Locale.ROOT);
        return upper.startsWith("GM:") || upper.startsWith("GLV");
    }

    private static BigDecimal flowUsdAtEvent(
            NormalizedTransaction tx,
            NormalizedTransaction.Flow flow,
            HistoricalFlowUsdResolver historicalFlowUsdResolver
    ) {
        if (flow == null) {
            return null;
        }
        if (flow.getValueUsd() != null) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null && flow.getQuantityDelta() != null) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
        }
        return historicalFlowUsdResolver == null ? null : historicalFlowUsdResolver.resolve(tx, flow);
    }

    private static BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        return zero(a).add(zero(b), MC);
    }

    private static boolean isLpReceiptSymbol(String symbol) {
        return symbol != null && symbol.toUpperCase(Locale.ROOT).startsWith("LP-RECEIPT:");
    }

    private List<String> walletAddresses(UserSession session) {
        if (session.getWallets() == null) {
            return List.of();
        }
        return session.getWallets().stream()
                .map(w -> normalizeAddress(w.getAddress()))
                .filter(a -> !a.isBlank())
                .toList();
    }

    private List<NormalizedTransaction> loadLpTransactions(List<String> wallets) {
        if (wallets.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("walletAddress").in(wallets)
                .and("status").is(NormalizedTransactionStatus.CONFIRMED)
                .and("type").in(LP_TYPES)
                .and("correlationId").regex("^(lp-position:|pendle-lp:|gmx-lp:)"));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private List<LpReceiptBasisPool> loadBasisPools(String universeId) {
        Query query = new Query(Criteria.where("universeId").is(universeId));
        return mongoOperations.find(query, LpReceiptBasisPool.class);
    }

    private List<AssetLedgerPoint> loadLedgerPoints(String universeId) {
        Query query = new Query(Criteria.where("accountingUniverseId").is(universeId)
                .and("lifecycleKind").is(AssetLedgerPoint.LifecycleKind.LP));
        return mongoOperations.find(query, AssetLedgerPoint.class);
    }

    private Map<String, BigDecimal> loadCurrentPrices(
            List<NormalizedTransaction> txs,
            List<LpReceiptBasisPool> basisPools,
            Map<String, LpPositionSnapshot> snapshots
    ) {
        Set<String> symbols = new java.util.LinkedHashSet<>();
        for (NormalizedTransaction tx : txs) {
            if (tx.getFlows() != null) {
                tx.getFlows().forEach(f -> addSymbol(symbols, f.getAssetSymbol()));
            }
        }
        basisPools.forEach(p -> addSymbol(symbols, p.getAssetSymbol()));
        snapshots.values().forEach(s -> {
            if (s.getToken0() != null) {
                addSymbol(symbols, s.getToken0().getSym());
            }
            if (s.getToken1() != null) {
                addSymbol(symbols, s.getToken1().getSym());
            }
            if (s.getUnclaimedFeesByToken() != null) {
                s.getUnclaimedFeesByToken().keySet().forEach(sym -> addSymbol(symbols, sym));
            }
        });
        Set<String> lookupSymbols = new java.util.LinkedHashSet<>();
        for (String sym : symbols) {
            lookupSymbols.add(sym);
            String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym);
            if (!canonical.isBlank()) {
                lookupSymbols.add(canonical);
            }
        }
        if (lookupSymbols.isEmpty()) {
            return Map.of();
        }
        Query query = new Query(Criteria.where("symbol").in(lookupSymbols));
        Map<String, BigDecimal> raw = mongoOperations.find(query, CurrentPriceQuoteDocument.class).stream()
                .collect(Collectors.toMap(
                        q -> q.getSymbol().toUpperCase(Locale.ROOT),
                        CurrentPriceQuoteDocument::getPriceUsd,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        Map<String, BigDecimal> resolved = new LinkedHashMap<>();
        for (String sym : symbols) {
            BigDecimal price = priceLookup(sym, raw);
            if (price != null) {
                resolved.put(sym.toUpperCase(Locale.ROOT), price);
            }
        }
        return resolved;
    }

    private static void addSymbol(Set<String> symbols, String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            symbols.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static boolean isLpCorrelation(String correlationId) {
        return correlationId.startsWith("lp-position:")
                || correlationId.startsWith("pendle-lp:")
                || correlationId.startsWith("gmx-lp:");
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class PositionAccumulator {
        private final String correlationId;
        private String protocol;
        private String family;
        private String networkId;
        private String walletAddress;
        private String tokenId;
        private boolean staked;
        private boolean closed;
        /** True if at least one LP_ENTRY / LP_ENTRY_SETTLEMENT / LP_ADJUST transaction was seen. */
        private boolean hasLpEntry;
        /** Total count of LP_POSITION_STAKE and LP_POSITION_UNSTAKE transactions. */
        private int stakeUnstakeCount;
        private Instant enteredAt;
        private Instant closedAt;
        private Instant latestClaimAt;
        private BigDecimal costBasisUsd = BigDecimal.ZERO;
        private BigDecimal claimedFeesUsd = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsd = BigDecimal.ZERO;
        private BigDecimal withdrawnUsd = BigDecimal.ZERO;
        private BigDecimal depositedMarketUsd = BigDecimal.ZERO;
        /** Number of LpReceiptBasisPool records seen for this position. >0 means cost-basis pipeline ran. */
        private int basisPoolCount = 0;
        /** Total LP receipt quantity still held across all basis pool records. 0 means all receipts burned. */
        private BigDecimal totalLpReceiptQtyHeld = BigDecimal.ZERO;
        private final Map<String, BigDecimal> netQtyBySymbol = new LinkedHashMap<>();
        private final Map<String, BigDecimal> entryValueBySymbol = new LinkedHashMap<>();
        private final Map<String, BigDecimal> entryQtyBySymbol = new LinkedHashMap<>();
        /** Per-asset cost basis USD keyed by canonical market symbol (e.g. ETH, USDC). */
        private final Map<String, BigDecimal> basisUsdBySymbol = new LinkedHashMap<>();
        private final List<NormalizedTransaction> txns = new ArrayList<>();

        PositionAccumulator(String correlationId, NormalizedTransaction seed) {
            this.correlationId = correlationId;
            if (correlationId.startsWith("lp-position:")) {
                family = "CL_NFT";
                String[] parts = correlationId.split(":");
                if (parts.length >= 4) {
                    tokenId = parts[3];
                }
            } else if (correlationId.startsWith("gmx-lp:")) {
                // GLV positions (gmx-lp:{network}:glv-{slug}) get a distinct family;
                // the snapshot will override this in toPositionView, but use GLV_LP here
                // so that singleReceiptFamily() and other accumulator-level checks work correctly.
                String[] parts = correlationId.split(":", 4);
                family = (parts.length >= 3 && parts[2].startsWith("glv-")) ? "GLV_LP" : "GMX_LP";
            } else if (correlationId.startsWith("pendle-lp:")) {
                family = "PENDLE_LP";
            }
            if (seed != null) {
                protocol = nonBridgeProtocol(seed.getProtocolName());
                networkId = seed.getNetworkId() != null ? seed.getNetworkId().name() : null;
                walletAddress = seed.getWalletAddress();
            }
        }

        void addTransaction(NormalizedTransaction tx, HistoricalFlowUsdResolver historicalFlowUsdResolver) {
            txns.add(tx);
            if (protocol == null) {
                protocol = nonBridgeProtocol(tx.getProtocolName());
            }
            if (networkId == null && tx.getNetworkId() != null) {
                networkId = tx.getNetworkId().name();
            }
            if (walletAddress == null) {
                walletAddress = tx.getWalletAddress();
            }
            // enteredAt = earliest LP_ENTRY / LP_ENTRY_SETTLEMENT timestamp; fall back to any earliest tx
            if (tx.getBlockTimestamp() != null) {
                if (ENTRY_TYPES.contains(tx.getType())) {
                    // Prefer ENTRY_TYPES timestamps — reset to this if no entry seen yet or this is earlier
                    if (enteredAt == null || tx.getBlockTimestamp().isBefore(enteredAt)) {
                        enteredAt = tx.getBlockTimestamp();
                    }
                } else if (enteredAt == null) {
                    // Fall back: use the current tx timestamp until we find an ENTRY_TYPE
                    enteredAt = tx.getBlockTimestamp();
                }
            }
            if (tx.getType() == NormalizedTransactionType.LP_EXIT_FINAL) {
                closed = true;
                closedAt = tx.getBlockTimestamp();
            }
            // Track latest exit timestamp as a fallback closedAt for positions that
            // exit via LP_EXIT / LP_EXIT_PARTIAL without an explicit LP_EXIT_FINAL
            if (EXIT_TYPES.contains(tx.getType()) && tx.getBlockTimestamp() != null) {
                if (closedAt == null || tx.getBlockTimestamp().isAfter(closedAt)) {
                    closedAt = tx.getBlockTimestamp();
                }
            }
            if (tx.getType() == NormalizedTransactionType.LP_POSITION_STAKE) {
                staked = true;
                stakeUnstakeCount++;
            }
            if (tx.getType() == NormalizedTransactionType.LP_POSITION_UNSTAKE) {
                stakeUnstakeCount++;
            }
            if (ENTRY_TYPES.contains(tx.getType())) {
                hasLpEntry = true;
            }
            if (tx.getFlows() == null) {
                return;
            }
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow.getAssetSymbol() == null || flow.getQuantityDelta() == null) {
                    continue;
                }
                if (isLpReceiptSymbol(flow.getAssetSymbol())) {
                    continue;
                }
                // Skip gas-fee flows — these are network fees, not LP deposits/withdrawals.
                if (NormalizedLegRole.FEE == flow.getRole()) {
                    continue;
                }
                String sym = flow.getAssetSymbol().toUpperCase(Locale.ROOT);
                if (ENTRY_TYPES.contains(tx.getType()) && flow.getQuantityDelta().signum() < 0) {
                    BigDecimal entryQty = flow.getQuantityDelta().abs();
                    netQtyBySymbol.merge(sym, entryQty, BigDecimal::add);
                    entryQtyBySymbol.merge(sym, entryQty, BigDecimal::add);
                    BigDecimal entryUsd = flowUsdAtEvent(tx, flow, historicalFlowUsdResolver);
                    if (entryUsd != null && entryUsd.signum() > 0) {
                        entryValueBySymbol.merge(sym, entryUsd, BigDecimal::add);
                        depositedMarketUsd = depositedMarketUsd.add(entryUsd, MC);
                    }
                }
                // Single-receipt protocols (GMX, Pendle): LP_ENTRY_SETTLEMENT receives the LP
                // receipt token (positive inflow, no historical price) plus a small execution-fee
                // refund in ETH (which DOES have a price). Track only the LP receipt token
                // (unpriced) for "At Entry" quantity; exclude fee refunds.
                if (singleReceiptFamily()
                        && tx.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                        && flow.getQuantityDelta().signum() > 0) {
                    BigDecimal settlementUsd = flowUsdAtEvent(tx, flow, historicalFlowUsdResolver);
                    if (settlementUsd == null || settlementUsd.signum() == 0) {
                        // No historical price → this is the LP receipt token (GM/GLV), not a fee refund
                        entryQtyBySymbol.merge(sym, flow.getQuantityDelta(), BigDecimal::add);
                    }
                    // depositedMarketUsd is populated from LP_ENTRY_REQUEST (see below), not here.
                }
                // Single-receipt protocols (GMX, Pendle): LP_ENTRY_REQUEST records the actual assets
                // deposited (USDC + execution ETH). Track non-FEE negative flows as depositedMarketUsd.
                // Note: FEE-role flows are already skipped by the guard above (line 1124).
                if (singleReceiptFamily()
                        && tx.getType() == NormalizedTransactionType.LP_ENTRY_REQUEST
                        && flow.getQuantityDelta().signum() < 0) {
                    BigDecimal requestUsd = flowUsdAtEvent(tx, flow, historicalFlowUsdResolver);
                    if (requestUsd != null && requestUsd.signum() > 0) {
                        depositedMarketUsd = depositedMarketUsd.add(requestUsd, MC);
                    }
                }
                if (EXIT_TYPES.contains(tx.getType()) && flow.getQuantityDelta().signum() != 0) {
                    BigDecimal exitQty = flow.getQuantityDelta().signum() > 0
                            ? flow.getQuantityDelta()
                            : flow.getQuantityDelta().abs();
                    // Use negate+add instead of subtract: Map.merge inserts the value directly when
                    // the key is absent, so subtract would store a positive exitQty (wrong for a
                    // position that has no prior LP_ENTRY in this accumulator). Negating the value
                    // before merging ensures the key-absent case stores -exitQty (correct net zero
                    // minus exit = negative), while the key-present case produces the same result:
                    // existing + (-exitQty) == existing - exitQty.
                    netQtyBySymbol.merge(sym, exitQty.negate(), BigDecimal::add);
                    BigDecimal exitUsd = flowUsdAtEvent(tx, flow, historicalFlowUsdResolver);
                    if (exitUsd != null && exitUsd.signum() > 0) {
                        withdrawnUsd = withdrawnUsd.add(exitUsd, MC);
                    }
                }
            }
        }

        void addBasis(LpReceiptBasisPool pool) {
            basisPoolCount++;
            if (pool.getBasisHeldUsd() != null) {
                costBasisUsd = costBasisUsd.add(pool.getBasisHeldUsd(), MC);
            }
            if (pool.getQtyHeld() != null) {
                totalLpReceiptQtyHeld = totalLpReceiptQtyHeld.add(pool.getQtyHeld(), MC);
            }
            // Track per-asset basis for entryTokenView
            if (pool.getAssetSymbol() != null && pool.getBasisHeldUsd() != null && pool.getBasisHeldUsd().signum() > 0) {
                String sym = pool.getAssetSymbol().toUpperCase(Locale.ROOT);
                String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym);
                String key = canonical.isBlank() ? sym : canonical;
                basisUsdBySymbol.merge(key, pool.getBasisHeldUsd(), BigDecimal::add);
            }
        }

        BigDecimal basisUsdForSymbol(String sym) {
            if (sym == null || sym.isBlank()) return null;
            String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym.toUpperCase(Locale.ROOT));
            String key = canonical.isBlank() ? sym.toUpperCase(Locale.ROOT) : canonical;
            BigDecimal usd = basisUsdBySymbol.get(key);
            if (usd == null) {
                usd = basisUsdBySymbol.get(sym.toUpperCase(Locale.ROOT));
            }
            return usd != null && usd.signum() > 0 ? usd : null;
        }

        BigDecimal entryQtyForHodl(String sym) {
            return entryQtyForSymbol(sym);
        }

        BigDecimal entryQtyForSymbol(String sym) {
            if (sym == null || sym.isBlank()) return null;
            String targetCanonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym.toUpperCase(Locale.ROOT));
            String targetKey = targetCanonical.isBlank() ? sym.toUpperCase(Locale.ROOT) : targetCanonical;
            for (var entry : entryQtyBySymbol.entrySet()) {
                String entryCanonical = CanonicalAssetCatalog.canonicalMarketSymbol(entry.getKey());
                String entryKey = entryCanonical.isBlank() ? entry.getKey() : entryCanonical;
                if (targetKey.equals(entryKey)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        BigDecimal entryValueForSymbol(String sym) {
            if (sym == null || sym.isBlank()) return null;
            String targetCanonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym.toUpperCase(Locale.ROOT));
            String targetKey = targetCanonical.isBlank() ? sym.toUpperCase(Locale.ROOT) : targetCanonical;
            for (var entry : entryValueBySymbol.entrySet()) {
                String entryCanonical = CanonicalAssetCatalog.canonicalMarketSymbol(entry.getKey());
                String entryKey = entryCanonical.isBlank() ? entry.getKey() : entryCanonical;
                if (targetKey.equals(entryKey)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        void addLedger(AssetLedgerPoint point) {
            if (NormalizedTransactionType.LP_FEE_CLAIM.name().equals(point.getNormalizedType())) {
                if (point.getCostBasisDeltaUsd() != null) {
                    claimedFeesUsd = claimedFeesUsd.add(point.getCostBasisDeltaUsd(), MC);
                }
                if (point.getBlockTimestamp() != null
                        && (latestClaimAt == null || point.getBlockTimestamp().isAfter(latestClaimAt))) {
                    latestClaimAt = point.getBlockTimestamp();
                }
            }
            if (point.getRealisedPnlDeltaUsd() != null) {
                realizedPnlUsd = realizedPnlUsd.add(point.getRealisedPnlDeltaUsd(), MC);
            }
        }

        boolean singleReceiptFamily() {
            return "GMX_LP".equals(family) || "GLV_LP".equals(family) || "PENDLE_LP".equals(family);
        }

        String pair() {
            String s0 = entrySymbol(0);
            String s1 = entrySymbol(1);
            if (s0 == null) {
                s0 = tokenSymbol(0);
            }
            if (s1 == null) {
                s1 = tokenSymbol(1);
            }
            if (s0 == null && s1 == null) {
                return null;
            }
            if (s1 == null) {
                return s0;
            }
            return s0 + "/" + s1;
        }

        String tokenSymbol(int index) {
            return symbolAt(index, netQtyBySymbol);
        }

        String entrySymbol(int index) {
            return symbolAt(index, entryQtyBySymbol);
        }

        BigDecimal entryQty(int index) {
            String sym = entrySymbol(index);
            return sym == null ? null : entryQtyBySymbol.get(sym);
        }

        BigDecimal entryUsd(int index) {
            String sym = entrySymbol(index);
            return sym == null ? null : entryValueBySymbol.get(sym);
        }

        private static String symbolAt(int index, Map<String, BigDecimal> symbols) {
            var entries = new ArrayList<>(symbols.keySet());
            entries.sort(String.CASE_INSENSITIVE_ORDER);
            if (index >= entries.size()) {
                return null;
            }
            return entries.get(index);
        }

        boolean fullyExited() {
            if (closed) {
                return true;
            }
            // Most reliable signal: cost-basis pipeline processed the position AND all LP receipts are burned.
            // basisPoolCount > 0 means the pipeline ran; costBasisUsd == 0 means USD basis is gone.
            // totalLpReceiptQtyHeld == 0 confirms the receipt quantity itself is zero (receipt burned).
            // This correctly handles unpriced tokens: if qtyHeld > 0 the receipt is still outstanding
            // even when basisHeldUsd = 0 (e.g. XYZ token has no price, but position is still open).
            if (basisPoolCount > 0 && costBasisUsd.signum() == 0
                    && totalLpReceiptQtyHeld.signum() == 0) {
                return true;
            }
            if (netQtyBySymbol.isEmpty()) {
                if (costBasisUsd.signum() == 0 && withdrawnUsd.signum() > 0) return true;
                // Staking-only vault (e.g. Velodrome gauge wrapper): has STAKE/UNSTAKE transactions
                // but no LP_ENTRY/EXIT. No real LP exposure of its own — treat as closed so it
                // doesn't appear as a phantom active position.
                if (!hasLpEntry && stakeUnstakeCount > 0 && costBasisUsd.signum() == 0) return true;
                return false;
            }
            // For AMM (CL) positions, the exit may return only one token due to IL.
            // Consider exited if at least one token quantity went negative (over-withdrawn = fees included)
            // AND no token still has a meaningful positive balance.
            boolean anyNegative = netQtyBySymbol.values().stream()
                    .anyMatch(qty -> qty != null && qty.signum() < 0);
            boolean allNonPositive = netQtyBySymbol.values().stream()
                    .allMatch(qty -> qty == null || qty.signum() <= 0);
            return allNonPositive || anyNegative;
        }

        String tokenContract(int index) {
            return null;
        }

        BigDecimal netQty(int index) {
            String sym = tokenSymbol(index);
            return sym == null ? BigDecimal.ZERO : netQtyBySymbol.getOrDefault(sym, BigDecimal.ZERO);
        }

        BigDecimal hodlValueFromEntryUsd(Map<String, BigDecimal> prices) {
            if (entryQtyBySymbol.isEmpty()) {
                return hodlValueUsd(prices);
            }
            BigDecimal total = BigDecimal.ZERO;
            boolean any = false;
            for (var entry : entryQtyBySymbol.entrySet()) {
                BigDecimal usd = markQty(entry.getKey(), entry.getValue(), prices);
                if (usd != null) {
                    total = total.add(usd, MC);
                    any = true;
                }
            }
            return any ? total : null;
        }

        BigDecimal hodlValueUsd(Map<String, BigDecimal> prices) {
            BigDecimal total = BigDecimal.ZERO;
            boolean any = false;
            for (var entry : netQtyBySymbol.entrySet()) {
                BigDecimal price = prices.get(entry.getKey());
                if (price == null) {
                    continue;
                }
                total = total.add(entry.getValue().multiply(price, MC), MC);
                any = true;
            }
            return any ? total : null;
        }

        String correlationId() { return correlationId; }
        String protocol() { return protocol; }
        String family() { return family; }
        String networkId() { return networkId; }
        String walletAddress() { return walletAddress; }
        String tokenId() { return tokenId; }
        boolean staked() { return staked; }
        boolean closed() { return closed; }
        Instant enteredAt() { return enteredAt; }
        Instant closedAt() { return closedAt; }
        Instant latestClaimAt() { return latestClaimAt; }
        BigDecimal costBasisUsd() { return costBasisUsd; }
        BigDecimal claimedFeesUsd() { return claimedFeesUsd; }
        BigDecimal realizedPnlUsd() { return realizedPnlUsd; }
        BigDecimal withdrawnUsd() { return withdrawnUsd; }
        BigDecimal depositedMarketUsd() { return depositedMarketUsd; }
        List<NormalizedTransaction> txns() {
            return txns.stream()
                    .sorted(Comparator.comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }
    }

    private static final java.util.Set<String> BRIDGE_PROTOCOLS = java.util.Set.of(
            "LI.FI", "Across", "Stargate", "Hop", "Celer", "Axelar", "Wormhole",
            "LayerZero", "Synapse", "Connext", "deBridge", "Jumper"
    );

    /**
     * Returns null if the protocol is a bridge/aggregator (not an LP protocol),
     * otherwise returns the protocol as-is.
     * Bridge routers mediate LP entry but are not the actual LP protocol.
     */
    private static String nonBridgeProtocol(String protocol) {
        if (protocol == null) return null;
        String trimmed = protocol.trim();
        return BRIDGE_PROTOCOLS.stream().anyMatch(b -> b.equalsIgnoreCase(trimmed)) ? null : trimmed;
    }

    private static final class HistoricalFlowUsdResolver {
        private final HistoricalPriceCacheService historicalPriceCacheService;
        private final PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;
        private final Map<String, BigDecimal> cache = new LinkedHashMap<>();

        private HistoricalFlowUsdResolver(
                HistoricalPriceCacheService historicalPriceCacheService,
                PriceExternalSourceOrchestrator priceExternalSourceOrchestrator
        ) {
            this.historicalPriceCacheService = historicalPriceCacheService;
            this.priceExternalSourceOrchestrator = priceExternalSourceOrchestrator;
        }

        private BigDecimal resolve(NormalizedTransaction tx, NormalizedTransaction.Flow flow) {
            if (tx == null || flow == null || tx.getBlockTimestamp() == null
                    || flow.getAssetSymbol() == null || flow.getQuantityDelta() == null) {
                return null;
            }
            BigDecimal qtyAbs = flow.getQuantityDelta().abs();
            if (qtyAbs.signum() == 0) {
                return null;
            }
            if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
                return qtyAbs;
            }
            String cacheKey = tx.getId() + "|" + tx.getBlockTimestamp().toEpochMilli() + "|"
                    + flow.getAssetContract() + "|" + flow.getAssetSymbol() + "|" + qtyAbs.toPlainString();
            if (cache.containsKey(cacheKey)) {
                return cache.get(cacheKey);
            }

            List<String> candidateSymbols = CanonicalAssetCatalog.marketEquivalentSymbols(flow.getAssetSymbol());
            if (candidateSymbols == null || candidateSymbols.isEmpty()) {
                candidateSymbols = List.of(flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT));
            }
            PriceRequest request = new PriceRequest(
                    tx.getId(),
                    tx.getSource() == null ? NormalizedTransactionSource.ON_CHAIN : tx.getSource(),
                    tx.getNetworkId(),
                    flow.getAssetContract(),
                    flow.getAssetSymbol(),
                    tx.getBlockTimestamp()
            );
            BigDecimal resolved = null;
            for (PriceSource source : priceExternalSourceOrchestrator.prioritizedSources(request)) {
                Optional<PriceQuote> quote = historicalPriceCacheService.findCanonicalQuote(
                        candidateSymbols,
                        tx.getBlockTimestamp(),
                        source
                );
                if (quote.isPresent() && quote.get().unitPriceUsd() != null && quote.get().unitPriceUsd().signum() > 0) {
                    resolved = qtyAbs.multiply(quote.get().unitPriceUsd(), MC);
                    break;
                }
            }
            cache.put(cacheKey, resolved);
            return resolved;
        }
    }
}
