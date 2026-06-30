package com.walletradar.liquiditypools.application;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.common.refresh.RefreshTrigger;
import com.walletradar.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.liquiditypools.enrichment.LpDepthFetchPolicy;
import com.walletradar.liquiditypools.enrichment.LpOnChainEnrichmentService;
import com.walletradar.liquiditypools.enrichment.LpPositionContext;
import com.walletradar.liquiditypools.persistence.LpEarningPoint;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LpPositionRefreshService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Duration MANUAL_REFRESH_TIMEOUT = Duration.ofSeconds(90);
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

    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final MongoOperations mongoOperations;
    private final LpOnChainEnrichmentService enrichmentService;
    private final LpPositionSnapshotService snapshotService;
    private final LpEarningPointService earningPointService;
    private final LiquidityPoolsProperties properties;
    private final LpPositionRefreshStateService refreshStateService;

    public List<String> discoverSessionIdsWithOpenPositions() {
        return userSessionRepository.findAll().stream()
                .map(UserSession::getId)
                .filter(sessionId -> !discoverOpenContextsForSession(sessionId).isEmpty())
                .toList();
    }

    public Map<String, LpPositionContext> discoverOpenContextsForSession(String sessionId) {
        return userSessionRepository.findById(sessionId)
                .map(this::discoverOpenContexts)
                .orElse(Map.of());
    }

    public RefreshResult refreshAllOpenPositionsWithState(RefreshTrigger trigger) {
        if (!properties.isEnabled()) {
            return new RefreshResult(0, 0, 0);
        }
        int total = 0;
        int saved = 0;
        int skipped = 0;
        for (UserSession session : userSessionRepository.findAll()) {
            RefreshResult sessionResult = refreshAllOpenForSessionWithState(session.getId(), trigger);
            total += sessionResult.positions();
            saved += sessionResult.saved();
            skipped += sessionResult.skipped();
        }
        log.info("LP position refresh complete positions={} saved={} skipped={}", total, saved, skipped);
        return new RefreshResult(total, saved, skipped);
    }

    public Optional<LpPositionSnapshot> refreshOnDemandWithState(String sessionId, String correlationId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return discoverOpenContextsForSession(sessionId).values().stream()
                .filter(ctx -> correlationId.equals(ctx.correlationId()))
                .findFirst()
                .flatMap(ctx -> refreshPosition(sessionId, ctx, false));
    }

    public RefreshResult refreshAllOpenForSessionWithState(String sessionId, RefreshTrigger trigger) {
        if (!properties.isEnabled()) {
            return new RefreshResult(0, 0, 0);
        }
        Map<String, LpPositionContext> contexts = discoverOpenContextsForSession(sessionId);
        int saved = 0;
        int skipped = 0;
        boolean persistStaleOnFailure = trigger != RefreshTrigger.MANUAL && trigger != RefreshTrigger.BULK;
        for (LpPositionContext context : contexts.values()) {
            if (refreshPosition(sessionId, context, persistStaleOnFailure).isPresent()) {
                saved++;
            } else {
                skipped++;
            }
        }
        log.info("LP session refresh complete sessionId={} positions={} saved={} skipped={}",
                sessionId, contexts.size(), saved, skipped);
        return new RefreshResult(contexts.size(), saved, skipped);
    }

    private Optional<LpPositionSnapshot> refreshPosition(
            String sessionId,
            LpPositionContext context,
            boolean persistStaleOnFailure
    ) {
        if (context.closed()) {
            return Optional.empty();
        }
        String correlationId = context.correlationId();
        refreshStateService.markUpdating(correlationId);
        try {
            LpOnChainEnrichmentService.EnrichmentResult result = persistStaleOnFailure
                    ? enrichmentService.enrich(context)
                    : enrichOnDemand(context);
            if (!result.fresh()) {
                if (!persistStaleOnFailure) {
                    log.warn("LP on-demand refresh skipped persisting stale snapshot correlationId={} reason={}",
                            correlationId,
                            result.failureReason() != null ? result.failureReason() : "enrichment unavailable");
                    Optional<LpPositionSnapshot> existing = snapshotService.findByCorrelationId(correlationId);
                    refreshStateService.markFailed(correlationId,
                            result.failureReason() != null ? result.failureReason() : "enrichment unavailable");
                    return existing;
                }
            }
            LpPositionSnapshot snapshot = result.snapshot().orElseGet(() ->
                    persistStaleOnFailure
                            ? createShellSnapshot(context, result.failureReason())
                            : null);
            if (snapshot == null) {
                Optional<LpPositionSnapshot> existing = snapshotService.findByCorrelationId(correlationId);
                if (existing.isPresent()) {
                    refreshStateService.markSynced(correlationId);
                } else {
                    refreshStateService.markFailed(correlationId,
                            result.failureReason() != null ? result.failureReason() : "enrichment unavailable");
                }
                return existing;
            }
            snapshot.setUniverseId(context.universeId());
            applyMarks(snapshot);
            snapshotService.upsert(snapshot);
            upsertTodayEarningPoint(context, snapshot);
            refreshStateService.markSynced(correlationId);
            return Optional.of(snapshot);
        } catch (Exception error) {
            refreshStateService.markFailed(correlationId, error.toString());
            throw error;
        }
    }

    private LpOnChainEnrichmentService.EnrichmentResult enrichOnDemand(LpPositionContext context) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lp-on-demand-enrich");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<LpOnChainEnrichmentService.EnrichmentResult> future = executor.submit(() ->
                    LpDepthFetchPolicy.callSkippingRpcFetch(() -> enrichmentService.enrich(context)));
            return future.get(MANUAL_REFRESH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            log.warn("LP on-demand refresh timed out correlationId={} timeoutSec={}",
                    context.correlationId(), MANUAL_REFRESH_TIMEOUT.getSeconds());
            return LpOnChainEnrichmentService.EnrichmentResult.failed(
                    "Refresh timed out after " + MANUAL_REFRESH_TIMEOUT.getSeconds() + "s");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return LpOnChainEnrichmentService.EnrichmentResult.failed("Refresh interrupted");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            return LpOnChainEnrichmentService.EnrichmentResult.failed(cause.toString());
        } finally {
            executor.shutdownNow();
        }
    }

    private LpPositionSnapshot createShellSnapshot(LpPositionContext context, String failureReason) {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(context.correlationId());
        snapshot.setNetworkId(context.networkId() != null ? context.networkId().name() : null);
        snapshot.setWalletAddress(context.walletAddress());
        snapshot.setProtocol(context.protocol());
        snapshot.setFamily(context.family());
        snapshot.setStatus("unknown");
        snapshot.setStaked(context.staked());
        snapshot.setSnapshotAt(Instant.now());
        snapshot.setSnapshotStale(true);
        snapshot.setUnavailableReason(failureReason != null ? failureReason : "On-chain enrichment unavailable");
        return snapshot;
    }

    private void upsertTodayEarningPoint(LpPositionContext context, LpPositionSnapshot snapshot) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BigDecimal claimedUsd = claimedFeesUsd(context.universeId(), context.correlationId());
        BigDecimal unclaimedUsd = zeroIfNull(snapshot.getUnclaimedFeesUsd());
        BigDecimal cumulative = claimedUsd.add(unclaimedUsd, MC);

        Optional<LpEarningPoint> priorDay = earningPointService.findByCorrelationIdAndDay(
                context.correlationId(), today.minusDays(1));
        BigDecimal priorCumulative = priorDay.map(LpEarningPoint::getCumulativeEarnedUsd).orElse(claimedUsd);
        BigDecimal daily = cumulative.subtract(priorCumulative, MC);

        BigDecimal tvl = zeroIfNull(snapshot.getTvlUsd());
        BigDecimal dailyApr = tvl.signum() > 0
                ? daily.divide(tvl, MC).multiply(BigDecimal.valueOf(36500), MC)
                : null;

        LpEarningPoint point = new LpEarningPoint();
        point.setCorrelationId(context.correlationId());
        point.setUniverseId(context.universeId());
        point.setDay(today);
        point.setCumulativeEarnedUsd(cumulative);
        point.setDailyEarnedUsd(daily);
        point.setDailyAprPct(dailyApr);
        point.setPositionValueUsd(tvl);
        point.setUpdatedAt(Instant.now());
        earningPointService.upsertDailyPoint(point);
    }

    private BigDecimal claimedFeesUsd(String universeId, String correlationId) {
        Query query = new Query(Criteria.where("accountingUniverseId").is(universeId)
                .and("correlationId").is(correlationId)
                .and("lifecycleKind").is(AssetLedgerPoint.LifecycleKind.LP)
                .and("normalizedType").is(NormalizedTransactionType.LP_FEE_CLAIM.name()));
        List<AssetLedgerPoint> points = mongoOperations.find(query, AssetLedgerPoint.class);
        return points.stream()
                .map(AssetLedgerPoint::getCostBasisDeltaUsd)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Prices token quantities on the snapshot using current_price_quotes from MongoDB.
     * Sets tvlUsd and unclaimedFeesUsd so the earning point service can compute APR.
     */
    private void applyMarks(LpPositionSnapshot snapshot) {
        Set<String> symbols = new LinkedHashSet<>();
        if (snapshot.getToken0() != null && snapshot.getToken0().getSym() != null) {
            symbols.add(snapshot.getToken0().getSym().toUpperCase(Locale.ROOT));
        }
        if (snapshot.getToken1() != null && snapshot.getToken1().getSym() != null) {
            symbols.add(snapshot.getToken1().getSym().toUpperCase(Locale.ROOT));
        }
        if (snapshot.getUnclaimedFeesByToken() != null) {
            snapshot.getUnclaimedFeesByToken().keySet()
                    .forEach(s -> symbols.add(s.toUpperCase(Locale.ROOT)));
        }
        if (symbols.isEmpty()) return;

        Set<String> lookupSymbols = new LinkedHashSet<>(symbols);
        for (String sym : symbols) {
            String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym);
            if (!canonical.isBlank()) lookupSymbols.add(canonical);
        }

        Map<String, BigDecimal> prices = mongoOperations.find(
                new Query(Criteria.where("symbol").in(lookupSymbols)),
                CurrentPriceQuoteDocument.class
        ).stream().collect(Collectors.toMap(
                q -> q.getSymbol().toUpperCase(Locale.ROOT),
                CurrentPriceQuoteDocument::getPriceUsd,
                (a, b) -> a,
                LinkedHashMap::new
        ));

        BigDecimal tvl = BigDecimal.ZERO;
        boolean tvlPriced = false;
        for (int i = 0; i < 2; i++) {
            LpPositionSnapshot.TokenSide side = i == 0 ? snapshot.getToken0() : snapshot.getToken1();
            if (side == null || side.getSym() == null || side.getQty() == null) continue;
            BigDecimal price = lookupPrice(side.getSym(), prices);
            if (price != null) {
                tvl = tvl.add(side.getQty().multiply(price, MC), MC);
                tvlPriced = true;
            }
        }
        if (tvlPriced) snapshot.setTvlUsd(tvl);

        if (snapshot.getUnclaimedFeesByToken() != null && !snapshot.getUnclaimedFeesByToken().isEmpty()) {
            BigDecimal feesUsd = BigDecimal.ZERO;
            boolean feesPriced = false;
            for (var entry : snapshot.getUnclaimedFeesByToken().entrySet()) {
                if (entry.getValue() == null) continue;
                BigDecimal price = lookupPrice(entry.getKey(), prices);
                if (price != null) {
                    feesUsd = feesUsd.add(entry.getValue().multiply(price, MC), MC);
                    feesPriced = true;
                }
            }
            if (feesPriced) snapshot.setUnclaimedFeesUsd(feesUsd);
        }
    }

    private static BigDecimal lookupPrice(String sym, Map<String, BigDecimal> prices) {
        if (sym == null || sym.isBlank()) return null;
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(sym)) return BigDecimal.ONE;
        String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(sym.toUpperCase(Locale.ROOT));
        BigDecimal price = prices.get(canonical.isBlank() ? sym.toUpperCase(Locale.ROOT) : canonical);
        if (price != null) return price;
        return prices.get(sym.toUpperCase(Locale.ROOT));
    }

    private Map<String, LpPositionContext> discoverOpenContexts(UserSession session) {
        Map<String, LpPositionContext> contexts = new LinkedHashMap<>();
        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        List<String> wallets = scope.onChainWalletRefs().stream()
                .map(LpPositionRefreshService::normalizeAddress)
                .toList();
        if (wallets.isEmpty()) {
            return contexts;
        }
        Query txQuery = new Query(Criteria.where("walletAddress").in(wallets)
                .and("status").is(NormalizedTransactionStatus.CONFIRMED)
                .and("type").in(LP_TYPES)
                .and("correlationId").regex("^(lp-position:|pendle-lp:|gmx-lp:)"));
        List<NormalizedTransaction> txs = mongoOperations.find(txQuery, NormalizedTransaction.class);

        Map<String, Boolean> closedByCorrelation = new LinkedHashMap<>();
        for (NormalizedTransaction tx : txs) {
            String corr = tx.getCorrelationId();
            if (corr == null) {
                continue;
            }
            if (tx.getType() == NormalizedTransactionType.LP_EXIT_FINAL) {
                closedByCorrelation.put(corr, true);
            } else {
                closedByCorrelation.putIfAbsent(corr, false);
            }
        }

        // Load only basis pools with qty > 0 from the DB to avoid iterating closed positions.
        // Closed pools (qty = 0) are excluded in the loop below anyway, but pre-filtering at
        // the DB layer avoids loading all 82+ historical pools when only ~6 are open.
        Query basisQuery = new Query(new Criteria().andOperator(
                Criteria.where("universeId").is(scope.accountingUniverseId()),
                Criteria.where("qtyHeld").gt(0)
        ));
        List<LpReceiptBasisPool> basisPools = mongoOperations.find(basisQuery, LpReceiptBasisPool.class);
        // Track corr IDs that were intentionally excluded by the basisPool loop
        // (zero qty held + LP activity recorded) so the TX fallback loop won't re-add them.
        java.util.Set<String> excludedByBasisPool = new java.util.LinkedHashSet<>();
        for (LpReceiptBasisPool pool : basisPools) {
            String corr = pool.getLpCorrelationId();
            if (corr == null || Boolean.TRUE.equals(closedByCorrelation.get(corr))) {
                continue;
            }
            boolean hasQty = pool.getQtyHeld() != null && pool.getQtyHeld().signum() > 0;
            if (!hasQty) {
                // LP receipt fully burned (qty=0): skip RPC regardless of whether an
                // LP_EXIT_FINAL event was recorded (some exits use other tx types).
                excludedByBasisPool.add(corr);
                continue;
            }
            contexts.putIfAbsent(corr, buildContext(scope.accountingUniverseId(), corr, pool, txs, closedByCorrelation));
        }

        for (NormalizedTransaction tx : txs) {
            String corr = tx.getCorrelationId();
            if (corr == null || Boolean.TRUE.equals(closedByCorrelation.get(corr))) {
                continue;
            }
            // Skip positions that the basisPool loop already excluded (LP receipt fully burned).
            if (excludedByBasisPool.contains(corr)) {
                continue;
            }
            contexts.putIfAbsent(corr, buildContext(scope.accountingUniverseId(), corr, null, txs, closedByCorrelation));
        }
        return contexts;
    }

    private LpPositionContext buildContext(
            String universeId,
            String correlationId,
            LpReceiptBasisPool basisPool,
            List<NormalizedTransaction> txs,
            Map<String, Boolean> closedByCorrelation
    ) {
        NormalizedTransaction sample = txs.stream()
                .filter(tx -> correlationId.equals(tx.getCorrelationId()))
                .findFirst()
                .orElse(null);
        NetworkId networkId = basisPool != null ? basisPool.getNetworkId()
                : sample != null ? sample.getNetworkId() : NetworkId.ETHEREUM;
        String wallet = basisPool != null ? basisPool.getWalletAddress()
                : sample != null ? sample.getWalletAddress() : "";
        // Bridge routers (LI.FI, Across, Stargate…) may have been the tx entry point,
        // but they are not the actual LP protocol. Discard the bridge label so that the
        // enrichment reader can resolve the real protocol from the NFT PM contract address.
        String rawProtocol = sample != null ? sample.getProtocolName() : null;
        String protocol = isBridgeProtocol(rawProtocol) ? null : rawProtocol;
        String nfpm = null;
        String tokenId = null;
        String lpToken = basisPool != null ? basisPool.getAssetContract() : null;
        String marketSlug = null;
        if (correlationId.startsWith("lp-position:")) {
            String[] parts = correlationId.split(":");
            if (parts.length >= 4) {
                nfpm = parts[2];
                tokenId = parts[3];
            }
        } else if (correlationId.startsWith("gmx-lp:")) {
            String[] parts = correlationId.split(":");
            if (parts.length >= 3) {
                marketSlug = parts[2];
            }
            // GMX positions have no basis pool; extract the GM/GLV token contract from entry flows.
            // Prefer GM: tokens (GMX v2 per-pool tokens) over GLV (vault wrapper).
            if (lpToken == null) {
                lpToken = extractGmxLpToken(txs, correlationId);
            }
        }
        boolean staked = txs.stream()
                .anyMatch(tx -> correlationId.equals(tx.getCorrelationId())
                        && tx.getType() == NormalizedTransactionType.LP_POSITION_STAKE);
        return new LpPositionContext(
                correlationId,
                universeId,
                networkId,
                normalizeAddress(wallet),
                protocol,
                inferFamily(correlationId),
                nfpm,
                null,
                tokenId,
                lpToken,
                marketSlug,
                Boolean.TRUE.equals(closedByCorrelation.get(correlationId)),
                staked
        );
    }

    /**
     * Extracts the LP token contract for a GMX position (GM or GLV token) from entry settlement flows.
     * Prefers GM: tokens (GMX v2 per-pool) over GLV vault wrapper tokens.
     */
    private static String extractGmxLpToken(List<NormalizedTransaction> txs, String correlationId) {
        // First pass: look for GM: tokens
        for (NormalizedTransaction tx : txs) {
            if (!correlationId.equals(tx.getCorrelationId())) continue;
            if (tx.getType() != NormalizedTransactionType.LP_ENTRY_SETTLEMENT) continue;
            if (tx.getFlows() == null) continue;
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                String sym = flow.getAssetSymbol();
                String contract = flow.getAssetContract();
                if (sym != null && sym.startsWith("GM:") && contract != null && !contract.isBlank()
                        && flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0) {
                    return contract;
                }
            }
        }
        // Second pass: fall back to GLV tokens
        for (NormalizedTransaction tx : txs) {
            if (!correlationId.equals(tx.getCorrelationId())) continue;
            if (tx.getType() != NormalizedTransactionType.LP_ENTRY_SETTLEMENT) continue;
            if (tx.getFlows() == null) continue;
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                String sym = flow.getAssetSymbol();
                String contract = flow.getAssetContract();
                if (sym != null && sym.startsWith("GLV") && contract != null && !contract.isBlank()
                        && flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0) {
                    return contract;
                }
            }
        }
        return null;
    }

    private static String inferFamily(String correlationId) {
        if (correlationId.startsWith("lp-position:")) {
            return "CL_NFT";
        }
        if (correlationId.startsWith("gmx-lp:")) {
            return "GMX_LP";
        }
        if (correlationId.startsWith("pendle-lp:")) {
            return "PENDLE_LP";
        }
        return "FUNGIBLE_LP";
    }

    private static final java.util.Set<String> BRIDGE_PROTOCOL_NAMES = java.util.Set.of(
            "LI.FI", "Across", "Stargate", "Hop", "Celer", "Axelar", "Wormhole",
            "LayerZero", "Synapse", "Connext", "deBridge", "Jumper"
    );

    private static boolean isBridgeProtocol(String protocol) {
        if (protocol == null) return false;
        String normalized = protocol.trim();
        return BRIDGE_PROTOCOL_NAMES.stream()
                .anyMatch(b -> b.equalsIgnoreCase(normalized));
    }

    private static String normalizeAddress(String address) {
        if (address == null) {
            return "";
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record RefreshResult(int positions, int saved, int skipped) {
    }
}
