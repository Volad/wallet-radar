package com.walletradar.ingestion.wallet.query;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.support.AssetLedgerSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Session-scoped dashboard snapshot based on current balances and latest replay state.
 */
@Service
@RequiredArgsConstructor
public class SessionDashboardQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String ISSUE_YIELD_ACCRUAL = "yield_accrual";
    private static final String ISSUE_COVERAGE_GAP = "coverage_gap";
    private static final String ISSUE_HISTORY_FLAGS = "history_flags";
    private static final String ISSUE_MISSING_REPLAY_POINT = "missing_replay_point";
    private static final String PRICE_ISSUE_MISSING = "missing_price";
    private static final String PRICE_ISSUE_STALE = "stale_price";
    private static final String PRICE_ISSUE_HISTORICAL_FALLBACK = "historical_price_fallback";
    private static final long CURRENT_QUOTE_STALE_AFTER_SECONDS = 15 * 60;

    private final UserSessionRepository userSessionRepository;
    private final MongoOperations mongoOperations;
    private final AccountingUniverseService accountingUniverseService;

    public Optional<SessionDashboardView> findSessionDashboard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::toView);
    }

    private SessionDashboardView toView(UserSession session) {
        Instant responseTime = Instant.now();
        AllowedScope allowedScope = AllowedScope.from(session.getWallets());
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<String> walletAddresses = allowedScope.walletAddresses();

        List<AssetLedgerPoint> scopedLedgerPoints = loadAssetLedgerPoints(universeScope.accountingUniverseId());
        List<OnChainBalance> scopedBalances = loadOnChainBalances(session.getId(), walletAddresses).stream()
                .filter(balance -> allowedScope.includes(balance.getWalletAddress(), balance.getNetworkId()))
                .toList();

        Map<BucketKey, AssetLedgerPoint> latestPointByBucket = latestLedgerPointByBucket(scopedLedgerPoints);
        Map<FamilyRowKey, BigDecimal> realisedPnlByFamily = realisedPnlByFamily(scopedLedgerPoints);
        BigDecimal totalRealisedPnlUsd = scopedLedgerPoints.stream()
                .map(AssetLedgerPoint::getRealisedPnlDeltaUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));

        Map<BucketKey, OnChainBalance> latestBalances = latestBalanceByBucket(scopedBalances);
        Map<FamilyRowKey, TokenPositionAccumulator> rows = new LinkedHashMap<>();
        for (OnChainBalance balance : latestBalances.values()) {
            BigDecimal currentQuantity = zeroIfNull(balance.getQuantity());
            if (currentQuantity.signum() <= 0) {
                continue;
            }

            String accountingAssetIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            BucketKey bucketKey = new BucketKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    accountingAssetIdentity
            );
            AssetLedgerPoint latestPoint = latestPointByBucket.get(bucketKey);
            String familyIdentity = resolvedFamilyIdentity(
                    latestPoint,
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            String familyDisplaySymbol = latestPoint == null || blank(latestPoint.getFamilyDisplaySymbol())
                    ? AssetLedgerSupport.familyDisplaySymbol(familyIdentity, balance.getAssetSymbol())
                    : latestPoint.getFamilyDisplaySymbol();
            String rowSymbol = blank(familyDisplaySymbol) ? normalizeSymbol(balance.getAssetSymbol()) : familyDisplaySymbol;

            FamilyRowKey rowKey = new FamilyRowKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    familyIdentity
            );
            TokenPositionAccumulator accumulator = rows.computeIfAbsent(
                    rowKey,
                    ignored -> new TokenPositionAccumulator(
                            familyIdentity,
                            rowSymbol,
                            displayName(rowSymbol),
                            balance.getNetworkId(),
                            normalizeAddress(balance.getWalletAddress())
                    )
            );
            accumulator.addBalance(currentQuantity, latestPoint);
        }

        Map<String, DashboardPriceSnapshot> latestPricesBySymbol = loadLatestPrices(
                rows.values().stream()
                .flatMap(accumulator -> accumulator.priceLookupCandidates().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new)),
                responseTime
        );

        List<TokenPositionView> tokenPositions = rows.entrySet().stream()
                .map(entry -> entry.getValue().toView(
                        resolvePrice(latestPricesBySymbol, entry.getValue().priceLookupSymbol()),
                        zeroIfNull(realisedPnlByFamily.get(entry.getKey()))
                ))
                .filter(position -> position.quantity().signum() > 0)
                .sorted(Comparator.comparing(
                        TokenPositionView::marketValueUsd,
                        Comparator.nullsLast(BigDecimal::compareTo)
                ).reversed())
                .toList();

        BigDecimal portfolioValueUsd = tokenPositions.stream()
                .map(TokenPositionView::marketValueUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalUnrealizedPnlUsd = tokenPositions.stream()
                .map(TokenPositionView::unrealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalProvableBasisUsd = tokenPositions.stream()
                .map(TokenPositionView::provableBasisUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalUnrealizedPnlPct = totalProvableBasisUsd.signum() <= 0
                ? BigDecimal.ZERO
                : totalUnrealizedPnlUsd.multiply(BigDecimal.valueOf(100), MC).divide(totalProvableBasisUsd, MC);

        List<WalletView> wallets = session.getWallets().stream()
                .map(wallet -> new WalletView(
                        wallet.getAddress(),
                        wallet.getLabel(),
                        wallet.getColor(),
                        wallet.getNetworks().stream().map(Enum::name).toList()
                ))
                .toList();

        return new SessionDashboardView(
                session.getId(),
                new SummaryView(
                        portfolioValueUsd,
                        totalUnrealizedPnlUsd,
                        totalUnrealizedPnlPct,
                        totalRealisedPnlUsd
                ),
                wallets,
                tokenPositions
        );
    }

    private List<OnChainBalance> loadOnChainBalances(String sessionId, Collection<String> walletAddresses) {
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

    private List<AssetLedgerPoint> loadAssetLedgerPoints(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("accountingUniverseId").is(accountingUniverseId))
                .with(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("accountingAssetIdentity"),
                        Sort.Order.asc("blockTimestamp"),
                        Sort.Order.asc("transactionIndex"),
                        Sort.Order.asc("replaySequence")
                ));
        return mongoOperations.find(query, AssetLedgerPoint.class);
    }

    private Map<String, DashboardPriceSnapshot> loadLatestPrices(Collection<String> symbols, Instant responseTime) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, DashboardPriceSnapshot> latestPrices = new LinkedHashMap<>();
        for (String symbol : symbols) {
            if (CanonicalAssetCatalog.isUsdStablecoin(null, null, symbol, null)) {
                latestPrices.put(normalizeSymbol(symbol), DashboardPriceSnapshot.stablecoin(responseTime));
            }
        }

        Query currentQuery = Query.query(Criteria.where("symbol").in(symbols))
                .with(Sort.by(
                        Sort.Order.desc("pricedAt"),
                        Sort.Order.desc("fetchedAt")
                ));
        List<CurrentPriceQuoteDocument> currentQuotes = mongoOperations.find(currentQuery, CurrentPriceQuoteDocument.class);
        if (currentQuotes != null) {
            for (CurrentPriceQuoteDocument document : currentQuotes) {
                String symbol = normalizeSymbol(document.getSymbol());
                if (latestPrices.containsKey(symbol)) {
                    continue;
                }
                DashboardPriceSnapshot snapshot = DashboardPriceSnapshot.current(document, responseTime);
                if (snapshot.priceUsd() != null) {
                    latestPrices.put(symbol, snapshot);
                }
            }
        }

        Query historicalQuery = Query.query(Criteria.where("symbol").in(symbols))
                .with(Sort.by(
                        Sort.Order.desc("bucketStart"),
                        Sort.Order.desc("fetchedAt")
                ));
        List<HistoricalPriceDocument> historicalPrices = mongoOperations.find(historicalQuery, HistoricalPriceDocument.class);
        if (historicalPrices == null) {
            return latestPrices;
        }
        for (HistoricalPriceDocument document : historicalPrices) {
            String symbol = normalizeSymbol(document.getSymbol());
            latestPrices.putIfAbsent(symbol, DashboardPriceSnapshot.historicalFallback(document, responseTime));
        }
        return latestPrices;
    }

    private Map<BucketKey, OnChainBalance> latestBalanceByBucket(List<OnChainBalance> balances) {
        Map<BucketKey, OnChainBalance> latest = new LinkedHashMap<>();
        for (OnChainBalance balance : balances) {
            String assetIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            BucketKey key = new BucketKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    assetIdentity
            );
            latest.put(key, balance);
        }
        return latest;
    }

    private Map<BucketKey, AssetLedgerPoint> latestLedgerPointByBucket(List<AssetLedgerPoint> points) {
        Map<BucketKey, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            BucketKey key = new BucketKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingAssetIdentity()
            );
            latest.put(key, point);
        }
        return latest;
    }

    private Map<FamilyRowKey, BigDecimal> realisedPnlByFamily(List<AssetLedgerPoint> points) {
        Map<FamilyRowKey, BigDecimal> totals = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            if (point.getNetworkId() == null || blank(point.getAccountingFamilyIdentity())) {
                continue;
            }
            FamilyRowKey key = new FamilyRowKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingFamilyIdentity()
            );
            totals.merge(key, zeroIfNull(point.getRealisedPnlDeltaUsd()), (left, right) -> left.add(right, MC));
        }
        return totals;
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

    private static String displayName(String symbol) {
        return switch (normalizeSymbol(symbol)) {
            case "BTC" -> "Bitcoin";
            case "ETH" -> "Ethereum";
            case "AVAX" -> "Avalanche";
            case "USDC" -> "USD Coin";
            case "WBTC" -> "Wrapped Bitcoin";
            case "MNT" -> "Mantle";
            case "BNB" -> "BNB";
            case "MATIC" -> "Polygon";
            default -> blank(symbol) ? "Unknown asset" : symbol.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static DashboardPriceSnapshot resolvePrice(Map<String, DashboardPriceSnapshot> latestPricesBySymbol, String symbol) {
        for (String candidate : priceLookupCandidates(symbol)) {
            DashboardPriceSnapshot price = latestPricesBySymbol.get(candidate);
            if (price != null && price.priceUsd() != null) {
                return price;
            }
        }
        return DashboardPriceSnapshot.missing();
    }

    private static List<String> priceLookupCandidates(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return List.of();
        }
        String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(normalized);
        return switch (canonical) {
            case "BTC" -> List.of("BTC", "WBTC");
            case "ETH" -> List.of("ETH", "WETH");
            case "AVAX" -> List.of("AVAX", "WAVAX");
            case "MNT" -> List.of("MNT", "WMNT");
            default -> canonical.equals(normalized)
                    ? List.of(canonical)
                    : List.of(canonical, normalized);
        };
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record SessionDashboardView(
            String sessionId,
            SummaryView summary,
            List<WalletView> wallets,
            List<TokenPositionView> tokenPositions
    ) {
    }

    public record SummaryView(
            BigDecimal portfolioValueUsd,
            BigDecimal totalUnrealizedPnlUsd,
            BigDecimal totalUnrealizedPnlPct,
            BigDecimal totalRealizedPnlUsd
    ) {
    }

    public record WalletView(
            String address,
            String label,
            String color,
            List<String> networks
    ) {
    }

    public record TokenPositionView(
            String familyIdentity,
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal priceUsd,
            BigDecimal marketValueUsd,
            String priceSource,
            Instant pricedAt,
            Long stalenessSeconds,
            Boolean isLiveQuote,
            String priceIssue,
            BigDecimal avcoUsd,
            BigDecimal unrealizedPnlPct,
            BigDecimal unrealizedPnlUsd,
            BigDecimal realizedPnlUsd,
            String networkId,
            String walletAddress,
            String issue
    ) {
        public BigDecimal marketValueUsd() {
            return marketValueUsd == null ? BigDecimal.ZERO : marketValueUsd;
        }

        public BigDecimal provableBasisUsd() {
            return avcoUsd.multiply(coveredQuantity, MC);
        }
    }

    private record BucketKey(
            String walletAddress,
            NetworkId networkId,
            String accountingAssetIdentity
    ) {
    }

    private record FamilyRowKey(
            String walletAddress,
            NetworkId networkId,
            String familyIdentity
    ) {
    }

    private static final class TokenPositionAccumulator {
        private final String familyIdentity;
        private final String symbol;
        private final String name;
        private final NetworkId networkId;
        private final String walletAddress;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal coveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private String issue;

        private TokenPositionAccumulator(
                String familyIdentity,
                String symbol,
                String name,
                NetworkId networkId,
                String walletAddress
        ) {
            this.familyIdentity = familyIdentity;
            this.symbol = symbol;
            this.name = name;
            this.networkId = networkId;
            this.walletAddress = walletAddress;
        }

        private void addBalance(BigDecimal currentQuantity, AssetLedgerPoint latestPoint) {
            quantity = quantity.add(currentQuantity, MC);
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
            issue = mergeIssueCodes(issue, classifyIssue(latestPoint, currentQuantity, exactCoveredQuantity));
        }

        private String priceLookupSymbol() {
            return normalizeSymbol(symbol);
        }

        private List<String> priceLookupCandidates() {
            return SessionDashboardQueryService.priceLookupCandidates(priceLookupSymbol());
        }

        private TokenPositionView toView(DashboardPriceSnapshot priceSnapshot, BigDecimal realizedPnlUsd) {
            BigDecimal priceUsd = priceSnapshot.priceUsd() == null ? BigDecimal.ZERO : priceSnapshot.priceUsd();
            BigDecimal marketValueUsd = quantity.multiply(priceUsd, MC);
            BigDecimal avcoUsd = coveredQuantity.signum() <= 0
                    ? BigDecimal.ZERO
                    : totalCostBasisUsd.divide(coveredQuantity, MC);
            BigDecimal unrealizedPnlUsd = coveredQuantity.multiply(priceUsd, MC).subtract(totalCostBasisUsd, MC);
            BigDecimal unrealizedPnlPct = totalCostBasisUsd.signum() <= 0
                    ? BigDecimal.ZERO
                    : unrealizedPnlUsd.multiply(BigDecimal.valueOf(100), MC).divide(totalCostBasisUsd, MC);
            return new TokenPositionView(
                    familyIdentity,
                    symbol,
                    name,
                    quantity,
                    coveredQuantity,
                    priceUsd,
                    marketValueUsd,
                    priceSnapshot.priceSource(),
                    priceSnapshot.pricedAt(),
                    priceSnapshot.stalenessSeconds(),
                    priceSnapshot.isLiveQuote(),
                    priceSnapshot.priceIssue(),
                    avcoUsd,
                    unrealizedPnlPct,
                    unrealizedPnlUsd,
                    realizedPnlUsd,
                    networkId == null ? null : networkId.name(),
                    walletAddress,
                    issue
            );
        }
    }

    private static String classifyIssue(
            AssetLedgerPoint latestPoint,
            BigDecimal currentQuantity,
            BigDecimal exactCoveredQuantity
    ) {
        if (latestPoint == null) {
            return ISSUE_MISSING_REPLAY_POINT;
        }
        boolean uncoveredQuantity = exactCoveredQuantity.compareTo(currentQuantity) < 0;
        boolean incompleteHistory = Boolean.TRUE.equals(latestPoint.getHasIncompleteHistoryAfter());
        boolean unresolvedFlags = Boolean.TRUE.equals(latestPoint.getHasUnresolvedFlagsAfter());
        if (uncoveredQuantity && !incompleteHistory && !unresolvedFlags && isYieldAccrualCandidate(latestPoint)) {
            return ISSUE_YIELD_ACCRUAL;
        }
        if (uncoveredQuantity) {
            return ISSUE_COVERAGE_GAP;
        }
        if (incompleteHistory || unresolvedFlags) {
            return ISSUE_HISTORY_FLAGS;
        }
        return null;
    }

    private static boolean isYieldAccrualCandidate(AssetLedgerPoint latestPoint) {
        if (latestPoint.getBasisEffect() != AssetLedgerPoint.BasisEffect.REALLOCATE_IN) {
            return false;
        }
        return latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.LENDING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.STAKING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.VAULT;
    }

    private static String mergeIssueCodes(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return issueRank(right) > issueRank(left) ? right : left;
    }

    private static int issueRank(String issue) {
        return switch (issue) {
            case ISSUE_MISSING_REPLAY_POINT -> 4;
            case ISSUE_COVERAGE_GAP -> 3;
            case ISSUE_HISTORY_FLAGS -> 2;
            case ISSUE_YIELD_ACCRUAL -> 1;
            default -> 0;
        };
    }

    private record DashboardPriceSnapshot(
            BigDecimal priceUsd,
            String priceSource,
            Instant pricedAt,
            Long stalenessSeconds,
            Boolean isLiveQuote,
            String priceIssue
    ) {
        private static DashboardPriceSnapshot stablecoin(Instant responseTime) {
            return new DashboardPriceSnapshot(
                    BigDecimal.ONE,
                    PriceSource.STABLECOIN.name(),
                    responseTime,
                    0L,
                    true,
                    null
            );
        }

        private static DashboardPriceSnapshot current(CurrentPriceQuoteDocument document, Instant responseTime) {
            Instant pricedAt = document.getPricedAt() == null ? document.getFetchedAt() : document.getPricedAt();
            Long stalenessSeconds = stalenessSeconds(pricedAt, responseTime);
            String priceIssue = stalenessSeconds != null && stalenessSeconds > CURRENT_QUOTE_STALE_AFTER_SECONDS
                    ? PRICE_ISSUE_STALE
                    : null;
            return new DashboardPriceSnapshot(
                    document.getPriceUsd(),
                    document.getSource() == null ? null : document.getSource().name(),
                    pricedAt,
                    stalenessSeconds,
                    true,
                    priceIssue
            );
        }

        private static DashboardPriceSnapshot historicalFallback(HistoricalPriceDocument document, Instant responseTime) {
            Instant pricedAt = document.getFetchedAt() == null ? document.getBucketStart() : document.getFetchedAt();
            return new DashboardPriceSnapshot(
                    document.getPriceUsd(),
                    document.getSource() == null ? null : document.getSource().name(),
                    pricedAt,
                    stalenessSeconds(pricedAt, responseTime),
                    false,
                    PRICE_ISSUE_HISTORICAL_FALLBACK
            );
        }

        private static DashboardPriceSnapshot missing() {
            return new DashboardPriceSnapshot(
                    BigDecimal.ZERO,
                    null,
                    null,
                    null,
                    false,
                    PRICE_ISSUE_MISSING
            );
        }

        private static Long stalenessSeconds(Instant pricedAt, Instant responseTime) {
            if (pricedAt == null || responseTime == null) {
                return null;
            }
            return Math.max(0L, Duration.between(pricedAt, responseTime).toSeconds());
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
}
