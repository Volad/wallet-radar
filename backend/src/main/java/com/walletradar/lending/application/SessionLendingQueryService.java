package com.walletradar.lending.application;

import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionLendingQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal CYCLE_DUST_TOLERANCE = new BigDecimal("0.0001");
    private static final Duration LOOP_GROUP_WINDOW = Duration.ofHours(24);
    private static final Duration HISTORICAL_STABLE_PRICE_WINDOW = Duration.ofHours(36);
    private static final Duration HISTORICAL_VOLATILE_PRICE_WINDOW = Duration.ofMinutes(5);
    private static final Duration HISTORICAL_WRAPPER_PRICE_WINDOW = Duration.ofDays(90);
    private static final EnumSet<NormalizedTransactionType> LENDING_TYPES = EnumSet.of(
            NormalizedTransactionType.LENDING_DEPOSIT,
            NormalizedTransactionType.LENDING_WITHDRAW,
            NormalizedTransactionType.LENDING_LOOP_OPEN,
            NormalizedTransactionType.LENDING_LOOP_REBALANCE,
            NormalizedTransactionType.LENDING_LOOP_DECREASE,
            NormalizedTransactionType.LENDING_LOOP_CLOSE,
            NormalizedTransactionType.BORROW,
            NormalizedTransactionType.REPAY
    );
    private static final EnumSet<NormalizedTransactionType> HISTORY_CANDIDATE_TYPES = EnumSet.of(
            NormalizedTransactionType.LENDING_DEPOSIT,
            NormalizedTransactionType.LENDING_WITHDRAW,
            NormalizedTransactionType.LENDING_LOOP_OPEN,
            NormalizedTransactionType.LENDING_LOOP_REBALANCE,
            NormalizedTransactionType.LENDING_LOOP_DECREASE,
            NormalizedTransactionType.LENDING_LOOP_CLOSE,
            NormalizedTransactionType.BORROW,
            NormalizedTransactionType.REPAY,
            NormalizedTransactionType.VAULT_DEPOSIT,
            NormalizedTransactionType.VAULT_WITHDRAW,
            NormalizedTransactionType.REWARD_CLAIM
    );

    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final MongoOperations mongoOperations;
    private final LendingMarketMetricEstimator metricEstimator;

    public Optional<SessionLendingView> findSessionLending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim()).map(this::toView);
    }

    private SessionLendingView toView(UserSession session) {
        List<String> walletAddresses = walletAddresses(session);
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<NormalizedTransaction> history = loadHistory(walletAddresses);
        List<NormalizedTransaction> cashExitSwaps = loadCashExitSwaps(walletAddresses, history);
        List<AssetLedgerPoint> ledgerPoints = loadLedgerPoints(universeScope.accountingUniverseId());
        List<OnChainBalance> balances = loadBalances(session.getId(), walletAddresses);
        Map<BucketKey, AssetLedgerPoint> latestLendingPointByBucket = latestLendingPointByBucket(ledgerPoints);
        Map<String, BigDecimal> prices = loadCurrentPrices(priceCandidates(balances, history));
        Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices = loadHistoricalPrices(history);

        Map<GroupKey, GroupAccumulator> groups = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : history) {
            GroupKey key = groupKey(transaction);
            groups.computeIfAbsent(key, GroupAccumulator::new)
                    .addHistory(transaction, prices, historicalPrices, marketKey(transaction));
        }
        groups.values().forEach(group -> group.addLinkedCashExitSwaps(cashExitSwaps));

        for (OnChainBalance balance : balances) {
            BigDecimal quantity = zeroIfNull(balance.getQuantity());
            if (quantity.signum() <= 0) {
                continue;
            }
            if (!LendingAssetSymbolSupport.isLendingPositionSymbol(balance.getAssetSymbol())) {
                continue;
            }
            BucketKey bucketKey = new BucketKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    AccountingAssetIdentitySupport.positionAssetIdentity(
                            balance.getNetworkId(),
                            balance.getAssetSymbol(),
                            balance.getAssetContract()
                    )
            );
            AssetLedgerPoint latestPoint = latestLendingPointByBucket.get(bucketKey);
            if (latestPoint == null) {
                continue;
            }
            GroupKey key = new GroupKey(
                    LendingProtocolNameSupport.displayProtocol(
                            latestPoint.getProtocolName(),
                            latestPoint.getAssetSymbol()
                    ),
                    balance.getNetworkId(),
                    normalizeAddress(balance.getWalletAddress())
            );
            groups.computeIfAbsent(key, GroupAccumulator::new).addPosition(
                    balance,
                    latestPoint,
                    prices,
                    metricEstimator,
                    marketKey(key.protocol(), balance.getNetworkId(), positionMarketAsset(key.protocol(), balance.getAssetSymbol()))
            );
        }
        List<LendingGroupView> groupViews = groups.values().stream()
                .map(accumulator -> accumulator.toView(metricEstimator))
                .filter(group -> !group.history().isEmpty() || !group.positions().isEmpty())
                .sorted(Comparator.comparing(LendingGroupView::status).reversed()
                        .thenComparing(LendingGroupView::protocol)
                        .thenComparing(LendingGroupView::networkId))
                .toList();

        BigDecimal totalSupply = groupViews.stream()
                .map(LendingGroupView::supplyUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalBorrow = groupViews.stream()
                .map(LendingGroupView::borrowUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        int openGroups = (int) groupViews.stream().filter(group -> "OPEN".equals(group.status())).count();
        int closedGroups = groupViews.size() - openGroups;
        int protocols = (int) groupViews.stream().map(LendingGroupView::protocol).distinct().count();

        return new SessionLendingView(
                session.getId(),
                new LendingSummaryView(
                        totalSupply,
                        totalBorrow,
                        totalSupply.subtract(totalBorrow, MC),
                        openGroups,
                        closedGroups,
                        protocols
                ),
                groupViews
        );
    }

    private List<NormalizedTransaction> loadHistory(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                        Criteria.where("walletAddress").in(walletAddresses),
                        Criteria.where("status").is(NormalizedTransactionStatus.CONFIRMED),
                        Criteria.where("excludedFromAccounting").ne(true),
                        Criteria.where("type").in(HISTORY_CANDIDATE_TYPES)
                ))
                .with(Sort.by(
                        Sort.Order.asc("blockTimestamp"),
                        Sort.Order.asc("transactionIndex"),
                        Sort.Order.asc("_id")
                ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::isLendingHistoryRow)
                .toList();
    }

    private List<AssetLedgerPoint> loadLedgerPoints(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return List.of();
        }
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("accountingUniverseId").is(accountingUniverseId),
                        Criteria.where("lifecycleKind").in(
                                AssetLedgerPoint.LifecycleKind.LENDING,
                                AssetLedgerPoint.LifecycleKind.LOOP,
                                AssetLedgerPoint.LifecycleKind.VAULT
                        )
                ))
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

    private List<NormalizedTransaction> loadCashExitSwaps(
            List<String> walletAddresses,
            List<NormalizedTransaction> history
    ) {
        if (walletAddresses.isEmpty() || history.isEmpty()) {
            return List.of();
        }
        Instant min = history.stream()
                .map(NormalizedTransaction::getBlockTimestamp)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        Instant max = history.stream()
                .map(NormalizedTransaction::getBlockTimestamp)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        if (min == null || max == null) {
            return List.of();
        }
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                        Criteria.where("walletAddress").in(walletAddresses),
                        Criteria.where("status").is(NormalizedTransactionStatus.CONFIRMED),
                        Criteria.where("excludedFromAccounting").ne(true),
                        Criteria.where("type").is(NormalizedTransactionType.SWAP),
                        Criteria.where("blockTimestamp").gte(min).lte(max.plus(Duration.ofMinutes(15)))
                ))
                .with(Sort.by(
                        Sort.Order.asc("blockTimestamp"),
                        Sort.Order.asc("transactionIndex"),
                        Sort.Order.asc("_id")
                ));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private List<OnChainBalance> loadBalances(String sessionId, List<String> walletAddresses) {
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

    private Map<BucketKey, AssetLedgerPoint> latestLendingPointByBucket(List<AssetLedgerPoint> points) {
        Map<BucketKey, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            if (point.getBasisEffect() == AssetLedgerPoint.BasisEffect.GAS_ONLY) {
                continue;
            }
            BucketKey key = new BucketKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingAssetIdentity()
            );
            latest.put(key, point);
        }
        return latest;
    }

    private Set<String> priceCandidates(List<OnChainBalance> balances, List<NormalizedTransaction> history) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (OnChainBalance balance : balances) {
            addPriceCandidates(symbols, balance.getAssetSymbol());
        }
        for (NormalizedTransaction transaction : history) {
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                addPriceCandidates(symbols, flow.getAssetSymbol());
            }
        }
        return symbols;
    }

    private void addPriceCandidates(Set<String> symbols, String assetSymbol) {
        String underlying = LendingAssetSymbolSupport.underlyingSymbol(assetSymbol);
        addPriceSymbolVariants(symbols, underlying);
    }

    private void addPriceSymbolVariants(Set<String> symbols, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String normalized = LendingAssetSymbolSupport.normalizeSymbol(symbol);
        symbols.add(normalized);
        symbols.add(normalized.toLowerCase(Locale.ROOT));
        symbols.add(CanonicalAssetCatalog.canonicalMarketSymbol(normalized));
        if (normalized.startsWith("WST") && normalized.length() > 3) {
            symbols.add("wst" + normalized.substring(3));
        }
    }

    private Map<String, BigDecimal> loadCurrentPrices(Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (String symbol : symbols) {
            if (CanonicalAssetCatalog.isUsdStablecoin(null, null, symbol, null)) {
                prices.put(LendingAssetSymbolSupport.normalizeSymbol(symbol), BigDecimal.ONE);
            }
        }
        Query query = Query.query(Criteria.where("symbol").in(symbols))
                .with(Sort.by(
                        Sort.Order.desc("pricedAt"),
                        Sort.Order.desc("fetchedAt")
                ));
        for (CurrentPriceQuoteDocument document : mongoOperations.find(query, CurrentPriceQuoteDocument.class)) {
            if (document.getPriceUsd() == null) {
                continue;
            }
            prices.putIfAbsent(LendingAssetSymbolSupport.normalizeSymbol(document.getSymbol()), document.getPriceUsd());
        }
        return prices;
    }

    private Map<String, NavigableMap<Instant, BigDecimal>> loadHistoricalPrices(List<NormalizedTransaction> history) {
        if (history.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        Instant min = null;
        Instant max = null;
        for (NormalizedTransaction transaction : history) {
            if (transaction.getBlockTimestamp() == null) {
                continue;
            }
            min = min == null || transaction.getBlockTimestamp().isBefore(min) ? transaction.getBlockTimestamp() : min;
            max = max == null || transaction.getBlockTimestamp().isAfter(max) ? transaction.getBlockTimestamp() : max;
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                String symbol = LendingAssetSymbolSupport.underlyingSymbol(flow.getAssetSymbol());
                addPriceSymbolVariants(symbols, symbol);
            }
        }
        if (symbols.isEmpty() || min == null || max == null) {
            return Map.of();
        }

        Query query = Query.query(Criteria.where("symbol").in(symbols)
                        .and("bucketStart").gte(min.minus(HISTORICAL_STABLE_PRICE_WINDOW).truncatedTo(ChronoUnit.MINUTES))
                        .lte(max.plus(HISTORICAL_STABLE_PRICE_WINDOW).truncatedTo(ChronoUnit.MINUTES)))
                .with(Sort.by(Sort.Order.asc("bucketStart")));
        List<HistoricalPriceDocument> documents = mongoOperations.find(query, HistoricalPriceDocument.class);
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }

        Map<String, NavigableMap<Instant, BigDecimal>> bySymbol = new LinkedHashMap<>();
        for (HistoricalPriceDocument document : documents) {
            if (document.getSymbol() == null || document.getBucketStart() == null || document.getPriceUsd() == null) {
                continue;
            }
            String symbol = LendingAssetSymbolSupport.normalizeSymbol(document.getSymbol());
            bySymbol.computeIfAbsent(symbol, ignored -> new TreeMap<>())
                    .putIfAbsent(document.getBucketStart(), document.getPriceUsd());
        }
        return bySymbol;
    }

    private BigDecimal resolvePrice(
            Map<String, BigDecimal> prices,
            Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
            String assetSymbol,
            Instant timestamp
    ) {
        String underlying = LendingAssetSymbolSupport.underlyingSymbol(assetSymbol);
        BigDecimal historical = resolveHistoricalPrice(historicalPrices, underlying, timestamp);
        if (historical == null) {
            historical = resolveHistoricalPrice(
                    historicalPrices,
                    CanonicalAssetCatalog.canonicalMarketSymbol(underlying),
                    timestamp
            );
        }
        if (historical != null) {
            return historical;
        }
        BigDecimal direct = prices.get(LendingAssetSymbolSupport.normalizeSymbol(underlying));
        if (direct != null) {
            return direct;
        }
        BigDecimal canonical = prices.get(CanonicalAssetCatalog.canonicalMarketSymbol(underlying));
        return canonical == null ? BigDecimal.ZERO : canonical;
    }

    private BigDecimal resolvePrice(Map<String, BigDecimal> prices, String assetSymbol) {
        return resolvePrice(prices, Map.of(), assetSymbol, null);
    }

    private BigDecimal resolveHistoricalPrice(
            Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
            String assetSymbol,
            Instant timestamp
    ) {
        if (timestamp == null || historicalPrices.isEmpty()) {
            return null;
        }
        String symbol = LendingAssetSymbolSupport.normalizeSymbol(assetSymbol);
        NavigableMap<Instant, BigDecimal> byTime = historicalPrices.get(symbol);
        if (byTime == null || byTime.isEmpty()) {
            return null;
        }
        Instant bucket = timestamp.truncatedTo(ChronoUnit.MINUTES);
        Map.Entry<Instant, BigDecimal> floor = byTime.floorEntry(bucket);
        Map.Entry<Instant, BigDecimal> ceiling = byTime.ceilingEntry(bucket);
        Map.Entry<Instant, BigDecimal> nearest = nearestPrice(bucket, floor, ceiling);
        if (nearest == null) {
            return null;
        }
        Duration maxAge = isWrapperMarketPricedSymbol(symbol)
                ? HISTORICAL_WRAPPER_PRICE_WINDOW
                : LendingAssetSymbolSupport.isStable(symbol)
                ? HISTORICAL_STABLE_PRICE_WINDOW
                : HISTORICAL_VOLATILE_PRICE_WINDOW;
        Duration age = Duration.between(nearest.getKey(), bucket).abs();
        return age.compareTo(maxAge) <= 0 ? nearest.getValue() : null;
    }

    private boolean isWrapperMarketPricedSymbol(String symbol) {
        String normalized = LendingAssetSymbolSupport.normalizeSymbol(symbol);
        return normalized.equals("WSTETH") || normalized.equals("WSTUSR");
    }

    private Map.Entry<Instant, BigDecimal> nearestPrice(
            Instant target,
            Map.Entry<Instant, BigDecimal> left,
            Map.Entry<Instant, BigDecimal> right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        Duration leftDistance = Duration.between(left.getKey(), target).abs();
        Duration rightDistance = Duration.between(right.getKey(), target).abs();
        return leftDistance.compareTo(rightDistance) <= 0 ? left : right;
    }

    private GroupKey groupKey(NormalizedTransaction transaction) {
        return new GroupKey(
                LendingProtocolNameSupport.resolveProtocol(transaction),
                transaction.getNetworkId(),
                normalizeAddress(transaction.getWalletAddress())
        );
    }

    private String marketKey(NormalizedTransaction transaction) {
        String protocol = LendingProtocolNameSupport.resolveProtocol(transaction);
        return marketKey(protocol, transaction.getNetworkId(), marketAsset(transaction, protocol));
    }

    private String marketKey(String protocol, NetworkId networkId, String assetSymbol) {
        String protocolPart = protocol == null || protocol.isBlank() ? "Unknown" : protocol.trim();
        String networkPart = networkId == null ? "UNKNOWN" : networkId.name();
        String assetPart = assetSymbol == null || assetSymbol.isBlank()
                ? "account-pool"
                : LendingAssetSymbolSupport.displaySymbol(assetSymbol);
        return protocolPart + ":" + networkPart + ":" + assetPart;
    }

    private String marketAsset(NormalizedTransaction transaction, String protocol) {
        String normalizedProtocol = protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
        if (normalizedProtocol.startsWith("AAVE")) {
            return "account-pool";
        }
        if (normalizedProtocol.startsWith("COMPOUND")) {
            return "comet-base-market";
        }
        if (normalizedProtocol.startsWith("FLUID")) {
            String matchedCounterparty = normalizeAddress(transaction.getMatchedCounterparty());
            if (!matchedCounterparty.isBlank() && matchedCounterparty.matches("^0x[a-f0-9]{40}$")) {
                return "vault-" + matchedCounterparty.substring(2, 10);
            }
            return preferredMarketAsset(safeFlows(transaction), normalizedProtocol)
                    .map(asset -> "vault-" + LendingAssetSymbolSupport.lifecycleAsset(asset))
                    .orElse("vault-account");
        }
        if (normalizedProtocol.startsWith("EULER")) {
            return "evk-account";
        }
        if (normalizedProtocol.startsWith("MORPHO")) {
            return preferredMarketAsset(safeFlows(transaction), normalizedProtocol)
                    .orElseGet(() -> safeFlows(transaction).stream()
                            .map(NormalizedTransaction.Flow::getAssetSymbol)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse("account-pool"));
        }
        if (transaction.getType() != null && transaction.getType().name().startsWith("LENDING_LOOP")) {
            return normalizedProtocol.startsWith("EULER") ? "evk-loop-account" : "loop-account";
        }
        return preferredMarketAsset(safeFlows(transaction), normalizedProtocol)
                .orElseGet(() -> safeFlows(transaction).stream()
                        .map(NormalizedTransaction.Flow::getAssetSymbol)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("account-pool"));
    }

    private String positionMarketAsset(String protocol, String assetSymbol) {
        String normalizedProtocol = protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
        if (normalizedProtocol.startsWith("AAVE")) {
            return "account-pool";
        }
        if (normalizedProtocol.startsWith("COMPOUND")) {
            return "comet-base-market";
        }
        if (normalizedProtocol.startsWith("FLUID")) {
            return "vault-account";
        }
        if (normalizedProtocol.startsWith("EULER")) {
            return "evk-account";
        }
        return assetSymbol;
    }

    private Optional<String> preferredMarketAsset(
            List<NormalizedTransaction.Flow> flows,
            String normalizedProtocol
    ) {
        return flows.stream()
                .map(NormalizedTransaction.Flow::getAssetSymbol)
                .filter(Objects::nonNull)
                .filter(symbol -> !symbol.isBlank())
                .filter(symbol -> isMarketSymbol(symbol, normalizedProtocol))
                .findFirst();
    }

    private boolean isMarketSymbol(String symbol, String normalizedProtocol) {
        String normalized = LendingAssetSymbolSupport.normalizeSymbol(symbol).replace("-", "");
        if (normalized.isBlank()) {
            return false;
        }
        if (normalizedProtocol.startsWith("MORPHO")) {
            return !LendingAssetSymbolSupport.isStable(symbol)
                    && !"ETH".equals(normalized)
                    && !"WETH".equals(normalized)
                    && !"BTC".equals(normalized)
                    && !"WBTC".equals(normalized);
        }
        if (normalizedProtocol.startsWith("EULER")) {
            return normalized.startsWith("E") || normalized.startsWith("EVK");
        }
        return LendingAssetSymbolSupport.isLendingPositionSymbol(symbol);
    }

    private boolean isLendingHistoryRow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        if (LENDING_TYPES.contains(transaction.getType())) {
            return true;
        }
        if (transaction.getType() == NormalizedTransactionType.REWARD_CLAIM) {
            return LendingProtocolNameSupport.isKnownLendingProtocol(LendingProtocolNameSupport.resolveProtocol(transaction));
        }
        if (transaction.getType() == NormalizedTransactionType.VAULT_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW) {
            if (LendingProtocolNameSupport.isKnownLendingProtocol(LendingProtocolNameSupport.resolveProtocol(transaction))) {
                return true;
            }
            return safeFlows(transaction).stream()
                    .map(NormalizedTransaction.Flow::getAssetSymbol)
                    .anyMatch(LendingAssetSymbolSupport::isLendingPositionSymbol);
        }
        return false;
    }

    private static List<String> walletAddresses(UserSession session) {
        return session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .filter(Objects::nonNull)
                .map(SessionLendingQueryService::normalizeAddress)
                .toList();
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static List<NormalizedTransaction.Flow> safeFlows(NormalizedTransaction transaction) {
        return transaction.getFlows() == null ? List.of() : transaction.getFlows();
    }

    public record SessionLendingView(
            String sessionId,
            LendingSummaryView summary,
            List<LendingGroupView> groups
    ) {
    }

    public record LendingSummaryView(
            BigDecimal totalSuppliedUsd,
            BigDecimal totalBorrowedUsd,
            BigDecimal netExposureUsd,
            Integer openGroups,
            Integer closedGroups,
            Integer protocols
    ) {
    }

    public record LendingGroupView(
            String id,
            String protocol,
            String networkId,
            String walletAddress,
            String status,
            BigDecimal healthFactor,
            String healthLabel,
            BigDecimal healthProgress,
            String healthStatus,
            String healthSource,
            BigDecimal supplyUsd,
            BigDecimal borrowUsd,
            BigDecimal netExposureUsd,
            List<LendingPositionView> positions,
            List<LendingCycleView> cycles,
            List<LendingHistoryEntryView> history
    ) {
    }

    public record LendingPositionView(
            String id,
            String marketKey,
            String side,
            String assetSymbol,
            String underlyingSymbol,
            String assetContract,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal valueUsd,
            BigDecimal earnedUsd,
            BigDecimal apyPct,
            String metricStatus,
            String metricSource
    ) {
    }

    public record LendingHistoryEntryView(
            String id,
            String txHash,
            String marketKey,
            String cycleId,
            String networkId,
            String walletAddress,
            Instant blockTimestamp,
            String type,
            String eventSubtype,
            String displayType,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            BigDecimal feeUsd,
            Map<String, BigDecimal> feeQuantityByAsset,
            String loopId
    ) {
    }

    public record LendingCycleView(
            String id,
            String marketKey,
            String marketLabel,
            String status,
            Instant startTimestamp,
            Instant closeTimestamp,
            String startTxHash,
            String closeTxHash,
            String statusDetail,
            String warningReason,
            Map<String, BigDecimal> assetDenominatedPnlByAsset,
            Map<String, String> assetDenominatedPrecisionByAsset,
            Map<String, String> assetDenominatedReasonByAsset,
            String primaryAssetPnlSummary,
            String largePnlReason,
            List<String> largePnlReasons,
            String primaryLargePnlReason,
            LendingAssetDeltasView assetDeltas,
            LendingPnlView realizedPnl,
            LendingPnlView unrealizedPnl,
            LendingPnlBreakdownView pnlBreakdown,
            LendingPnlAssetBreakdownView pnlAssetBreakdown,
            LendingTotalValuationView totalValuation,
            Map<String, List<LendingObservedFlowView>> observedFlowsByAsset,
            BigDecimal peakSupplyUsd,
            BigDecimal peakBorrowUsd,
            Long durationDays,
            List<LendingPositionView> positions,
            List<LendingHistoryEntryView> events,
            List<LendingTxGroupView> txGroups
    ) {
    }

    public record LendingPnlBreakdownView(
            BigDecimal interestEarnedUsd,
            BigDecimal interestPaidUsd,
            BigDecimal gasUsd,
            BigDecimal netPnlUsd,
            String precision,
            String method,
            String reason
    ) {
    }

    public record LendingPnlAssetBreakdownView(
            Map<String, BigDecimal> supplyIncomeByAsset,
            Map<String, BigDecimal> borrowCostByAsset,
            Map<String, BigDecimal> rewardsByAsset,
            Map<String, BigDecimal> gasByAsset,
            Map<String, BigDecimal> netIncomeByAsset,
            Map<String, String> precisionByAsset,
            Map<String, String> reasonByAsset
    ) {
    }

    public record LendingTotalValuationView(
            BigDecimal principalInUsd,
            BigDecimal principalOutUsd,
            BigDecimal borrowedUsd,
            BigDecimal repaidUsd,
            BigDecimal rewardsUsd,
            BigDecimal feesUsd,
            BigDecimal gasUsd,
            BigDecimal totalUsdPnl,
            BigDecimal currentUsdValue,
            BigDecimal unrealizedTotalUsdPnl,
            String totalUsdPnlPrecision,
            BigDecimal yieldOnlyPnl,
            String yieldOnlyPnlPrecision,
            String valuationMethod,
            String unavailableReason
    ) {
    }

    public record LendingObservedFlowView(
            String assetSymbol,
            String assetContract,
            BigDecimal quantity,
            String sourceTxHash,
            String sourceKind,
            Boolean isAuthoritativeForPnl,
            String unavailableReason
    ) {
    }

    public record LendingTxGroupView(
            String id,
            String type,
            Instant timestamp,
            String dateLabel,
            Integer loopSteps,
            String loopAssetIn,
            String loopAssetOut,
            List<LendingTxItemView> items
    ) {
    }

    public record LendingTxItemView(
            String id,
            String type,
            String label,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            String txHash,
            Instant blockTimestamp
    ) {
    }

    public record LendingAssetDeltasView(
            Map<String, BigDecimal> principalInByAsset,
            Map<String, BigDecimal> principalOutByAsset,
            Map<String, BigDecimal> principalOutCashByAsset,
            Map<String, BigDecimal> internalReceiptMovementByAsset,
            Map<String, BigDecimal> borrowedByAsset,
            Map<String, BigDecimal> repaidByAsset,
            Map<String, BigDecimal> withdrawnByAsset,
            Map<String, BigDecimal> rewardByAsset,
            Map<String, BigDecimal> feesByAsset,
            Map<String, BigDecimal> netCashDeltaByAsset
    ) {
    }

    public record LendingPnlView(
            BigDecimal valueUsd,
            String precision,
            String method
    ) {
    }

    private record GroupKey(String protocol, NetworkId networkId, String walletAddress) {
        private String id() {
            return (protocol + ":" + networkId + ":" + walletAddress).toLowerCase(Locale.ROOT);
        }
    }

    private record BucketKey(String walletAddress, NetworkId networkId, String accountingAssetIdentity) {
    }

    private final class GroupAccumulator {
        private final GroupKey key;
        private final List<LendingPositionView> positions = new ArrayList<>();
        private final List<LendingHistoryEntryView> history = new ArrayList<>();
        private BigDecimal supplyUsd = BigDecimal.ZERO;
        private BigDecimal borrowUsd = BigDecimal.ZERO;
        private BigDecimal closedEarnedUsd = BigDecimal.ZERO;

        private GroupAccumulator(GroupKey key) {
            this.key = key;
        }

        private void addPosition(
                OnChainBalance balance,
                AssetLedgerPoint latestPoint,
                Map<String, BigDecimal> prices,
                LendingMarketMetricEstimator estimator,
                String marketKey
        ) {
            String side = isBorrowPosition(balance, latestPoint) ? "BORROW" : "SUPPLY";
            BigDecimal quantity = zeroIfNull(balance.getQuantity());
            BigDecimal covered = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(quantity);
            if (covered.signum() <= 0) {
                covered = estimateCoveredQuantity(marketKey, balance.getAssetSymbol()).min(quantity);
            }
            BigDecimal price = resolvePrice(prices, balance.getAssetSymbol());
            BigDecimal valueUsd = quantity.multiply(price, MC);
            BigDecimal earnedUsd = "SUPPLY".equals(side)
                    ? quantity.subtract(covered, MC).max(BigDecimal.ZERO).multiply(price, MC)
                    : BigDecimal.ZERO;
            if ("BORROW".equals(side)) {
                borrowUsd = borrowUsd.add(valueUsd, MC);
            } else {
                supplyUsd = supplyUsd.add(valueUsd, MC);
            }
            LendingMarketMetricEstimator.MetricSnapshot metric = estimator.estimate(
                    key.protocol(),
                    side,
                    LendingAssetSymbolSupport.underlyingSymbol(balance.getAssetSymbol()),
                    supplyUsd,
                    borrowUsd
            );
            positions.add(new LendingPositionView(
                    key.id() + ":" + side.toLowerCase(Locale.ROOT) + ":" + balance.getAssetContract(),
                    marketKey,
                    side,
                    LendingAssetSymbolSupport.displaySymbol(balance.getAssetSymbol()),
                    LendingAssetSymbolSupport.underlyingSymbol(balance.getAssetSymbol()),
                    balance.getAssetContract(),
                    quantity,
                    covered,
                    valueUsd,
                    earnedUsd,
                    metric.apyPct(),
                    metric.status(),
                    metric.source()
            ));
        }

        private void addHistory(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
                String marketKey
        ) {
            List<HistoryAmount> amounts = historyAmounts(transaction, prices, historicalPrices);
            for (int index = 0; index < amounts.size(); index++) {
                HistoryAmount amount = amounts.get(index);
                history.add(new LendingHistoryEntryView(
                        transaction.getId() + (amount.syntheticIndex() == null ? "" : ":" + amount.syntheticIndex()),
                        transaction.getTxHash(),
                        marketKey,
                        null,
                        transaction.getNetworkId() == null ? null : transaction.getNetworkId().name(),
                        normalizeAddress(transaction.getWalletAddress()),
                        transaction.getBlockTimestamp(),
                        amount.type(),
                        amount.eventSubtype(),
                        amount.displayType(),
                        amount.assetSymbol(),
                        amount.quantity(),
                        amount.valueUsd(),
                        index == 0 ? amount.feeUsd() : BigDecimal.ZERO,
                        index == 0 ? amount.feeQuantityByAsset() : Map.of(),
                        loopId(transaction)
                ));
                if (index != 0) {
                    continue;
                }
                if (transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                        || transaction.getType() == NormalizedTransactionType.LENDING_LOOP_CLOSE
                        || transaction.getType() == NormalizedTransactionType.LENDING_LOOP_DECREASE
                        || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW) {
                    closedEarnedUsd = closedEarnedUsd.add(positiveBuyValue(transaction), MC);
                } else if (transaction.getType() == NormalizedTransactionType.REWARD_CLAIM) {
                    closedEarnedUsd = closedEarnedUsd.add(amount.valueUsd(), MC);
                }
            }
        }

        private void addLinkedCashExitSwaps(List<NormalizedTransaction> cashExitSwaps) {
            if (cashExitSwaps.isEmpty() || history.isEmpty() || !isEulerProtocol()) {
                return;
            }
            Set<String> usedSwapIds = new LinkedHashSet<>();
            List<LendingHistoryEntryView> additions = new ArrayList<>();
            List<LendingHistoryEntryView> orderedHistory = history.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList();
            for (LendingHistoryEntryView exit : orderedHistory) {
                if (!isEulerCashExitSource(exit)) {
                    continue;
                }
                Optional<NormalizedTransaction> linkedSwap = cashExitSwaps.stream()
                        .filter(swap -> !usedSwapIds.contains(swap.getId()))
                        .filter(swap -> sameWalletNetwork(exit, swap))
                        .filter(swap -> isAfterWithin(exit.blockTimestamp(), swap.getBlockTimestamp(), Duration.ofMinutes(15)))
                        .filter(this::isStableToStableCashExitSwap)
                        .findFirst();
                if (linkedSwap.isEmpty()) {
                    continue;
                }
                Optional<NormalizedTransaction.Flow> cashBuy = stableBuyFlow(linkedSwap.get());
                if (cashBuy.isEmpty()) {
                    continue;
                }
                usedSwapIds.add(linkedSwap.get().getId());
                NormalizedTransaction.Flow flow = cashBuy.get();
                BigDecimal quantity = zeroIfNull(flow.getQuantityDelta()).abs();
                additions.add(new LendingHistoryEntryView(
                        linkedSwap.get().getId() + ":lending-cash-exit",
                        linkedSwap.get().getTxHash(),
                        exit.marketKey(),
                        null,
                        linkedSwap.get().getNetworkId() == null ? exit.networkId() : linkedSwap.get().getNetworkId().name(),
                        normalizeAddress(linkedSwap.get().getWalletAddress()),
                        linkedSwap.get().getBlockTimestamp(),
                        "LENDING_CASH_EXIT",
                        "LINKED_POST_EXIT_SWAP",
                        "Cash exit",
                        LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()),
                        quantity,
                        flow.getValueUsd() == null ? quantity : flow.getValueUsd().abs(),
                        BigDecimal.ZERO,
                        Map.of(),
                        exit.loopId()
                ));
            }
            history.addAll(additions);
        }

        private boolean isEulerCashExitSource(LendingHistoryEntryView event) {
            return event != null
                    && ("LENDING_LOOP_CLOSE".equals(event.type()) || "LENDING_LOOP_DECREASE".equals(event.type()))
                    && event.valueUsd() != null
                    && event.valueUsd().signum() > 0;
        }

        private boolean isEulerProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("EULER");
        }

        private boolean isInternalReceiptExit(LendingHistoryEntryView event) {
            return event != null
                    && ("LENDING_WITHDRAW".equals(event.type())
                    || "VAULT_WITHDRAW".equals(event.type())
                    || "LENDING_LOOP_CLOSE".equals(event.type())
                    || "LENDING_LOOP_DECREASE".equals(event.type()))
                    && LendingAssetSymbolSupport.isLendingPositionSymbol(event.assetSymbol());
        }

        private boolean sameWalletNetwork(LendingHistoryEntryView exit, NormalizedTransaction swap) {
            return Objects.equals(exit.networkId(), swap.getNetworkId() == null ? null : swap.getNetworkId().name())
                    && Objects.equals(exit.walletAddress(), normalizeAddress(swap.getWalletAddress()));
        }

        private boolean isAfterWithin(Instant source, Instant candidate, Duration window) {
            if (source == null || candidate == null || candidate.isBefore(source)) {
                return false;
            }
            return !Duration.between(source, candidate).minus(window).isPositive();
        }

        private boolean swapConsumesExitValue(NormalizedTransaction swap, BigDecimal exitValueUsd) {
            return safeFlows(swap).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.SELL)
                    .filter(flow -> flow.getValueUsd() != null || flow.getQuantityDelta() != null)
                    .anyMatch(flow -> valuesMatch(exitValueUsd, flow.getValueUsd(), flow.getQuantityDelta()));
        }

        private boolean isStableToStableCashExitSwap(NormalizedTransaction swap) {
            return safeFlows(swap).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.SELL)
                    .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0)
                    .anyMatch(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    && stableBuyFlow(swap).isPresent();
        }

        private boolean valuesMatch(BigDecimal expected, BigDecimal valueUsd, BigDecimal quantity) {
            BigDecimal actual = valueUsd != null ? valueUsd.abs() : zeroIfNull(quantity).abs();
            return actual.subtract(expected, MC).abs().compareTo(new BigDecimal("0.000001")) <= 0;
        }

        private Optional<NormalizedTransaction.Flow> stableBuyFlow(NormalizedTransaction swap) {
            return safeFlows(swap).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.BUY)
                    .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0)
                    .filter(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
        }

        private LendingGroupView toView(LendingMarketMetricEstimator estimator) {
            boolean open = positions.stream().anyMatch(position -> position.quantity().signum() > 0);
            LendingMarketMetricEstimator.MetricSnapshot metric = estimator.estimate(
                    key.protocol(),
                    "GROUP",
                    "",
                    supplyUsd,
                    borrowUsd
            );
            List<LendingPositionView> sortedPositions = positions.stream()
                    .sorted(Comparator.comparing(LendingPositionView::side).reversed()
                            .thenComparing(LendingPositionView::underlyingSymbol))
                    .toList();
            List<LendingHistoryEntryView> cycleHistory = cycleBuildingHistory();
            List<LendingCycleView> cycles = buildCycles(sortedPositions, cycleHistory);
            List<LendingHistoryEntryView> sortedHistory = cycles.stream()
                    .flatMap(cycle -> cycle.events().stream())
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp).reversed())
                    .toList();
            return new LendingGroupView(
                    key.id(),
                    key.protocol(),
                    key.networkId() == null ? null : key.networkId().name(),
                    key.walletAddress(),
                    open ? "OPEN" : "CLOSED",
                    metric.healthFactor(),
                    metric.healthLabel(),
                    metric.healthProgress(),
                    metric.status(),
                    metric.source(),
                    supplyUsd.add(closedEarnedUsd, MC),
                    borrowUsd,
                    supplyUsd.subtract(borrowUsd, MC).add(closedEarnedUsd, MC),
                    sortedPositions,
                    cycles,
                    sortedHistory
            );
        }

        private List<LendingHistoryEntryView> cycleBuildingHistory() {
            if (!isMorphoProtocol()) {
                return history;
            }
            List<LendingHistoryEntryView> ordered = history.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList();
            List<LendingHistoryEntryView> result = new ArrayList<>();
            for (LendingHistoryEntryView event : ordered) {
                result.add(retargetMorphoLinkedCollateralOut(event, result));
            }
            return result;
        }

        private LendingHistoryEntryView retargetMorphoLinkedCollateralOut(
                LendingHistoryEntryView event,
                List<LendingHistoryEntryView> previousEvents
        ) {
            if (!isMorphoLinkedCollateralOut(event)) {
                return event;
            }
            return previousEvents.stream()
                    .filter(candidate -> isMorphoBundlerUnwindAnchor(candidate, event))
                    .max(Comparator.comparing(LendingHistoryEntryView::blockTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(anchor -> withMarketKey(event, anchor.marketKey()))
                    .orElse(event);
        }

        private boolean isMorphoLinkedCollateralOut(LendingHistoryEntryView event) {
            if (event == null || event.eventSubtype() == null || !isClosingEvent(event)) {
                return false;
            }
            String subtype = event.eventSubtype().toUpperCase(Locale.ROOT);
            return subtype.contains("MORPHO_BUNDLER_COLLATERAL_OUT")
                    && !LendingAssetSymbolSupport.isStable(event.assetSymbol());
        }

        private boolean isMorphoBundlerUnwindAnchor(
                LendingHistoryEntryView candidate,
                LendingHistoryEntryView linkedOutput
        ) {
            if (candidate == null
                    || linkedOutput == null
                    || candidate.eventSubtype() == null
                    || !isClosingEvent(candidate)
                    || Objects.equals(candidate.marketKey(), linkedOutput.marketKey())) {
                return false;
            }
            String subtype = candidate.eventSubtype().toUpperCase(Locale.ROOT);
            return subtype.contains("MORPHO_BUNDLER_COLLATERAL_OUT")
                    && LendingAssetSymbolSupport.isStable(candidate.assetSymbol())
                    && Objects.equals(candidate.walletAddress(), linkedOutput.walletAddress())
                    && Objects.equals(candidate.networkId(), linkedOutput.networkId())
                    && isAfterWithin(candidate.blockTimestamp(), linkedOutput.blockTimestamp(), Duration.ofMinutes(15));
        }

        private LendingHistoryEntryView withMarketKey(LendingHistoryEntryView event, String marketKey) {
            return new LendingHistoryEntryView(
                    event.id(),
                    event.txHash(),
                    marketKey,
                    event.cycleId(),
                    event.networkId(),
                    event.walletAddress(),
                    event.blockTimestamp(),
                    event.type(),
                    event.eventSubtype(),
                    event.displayType(),
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.feeUsd(),
                    event.feeQuantityByAsset(),
                    event.loopId()
            );
        }

        private List<LendingCycleView> buildCycles(
                List<LendingPositionView> sortedPositions,
                List<LendingHistoryEntryView> cycleHistory
        ) {
            Map<String, List<LendingPositionView>> positionsByMarket = sortedPositions.stream()
                    .collect(Collectors.groupingBy(
                            LendingPositionView::marketKey,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            Map<String, List<LendingHistoryEntryView>> historyByMarket = cycleHistory.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .collect(Collectors.groupingBy(
                            LendingHistoryEntryView::marketKey,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            LinkedHashSet<String> marketKeys = new LinkedHashSet<>();
            marketKeys.addAll(historyByMarket.keySet());
            marketKeys.addAll(positionsByMarket.keySet());

            List<LendingCycleView> cycles = new ArrayList<>();
            for (String marketKey : marketKeys) {
                List<LendingHistoryEntryView> marketHistory = historyByMarket.getOrDefault(marketKey, List.of());
                List<LendingPositionView> marketPositions = positionsByMarket.getOrDefault(marketKey, List.of());
                cycles.addAll(buildMarketCycles(marketKey, marketHistory, marketPositions));
            }
            return cycles.stream()
                    .sorted(Comparator.comparing(LendingCycleView::status).reversed()
                            .thenComparing(LendingCycleView::startTimestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }

        private List<LendingCycleView> buildMarketCycles(
                String marketKey,
                List<LendingHistoryEntryView> marketHistory,
                List<LendingPositionView> marketPositions
        ) {
            if (usesConcurrentAssetCycles()) {
                return buildConcurrentAssetCycles(marketKey, marketHistory, marketPositions);
            }
            if (marketHistory.isEmpty() && !marketPositions.isEmpty()) {
                String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-1";
                return List.of(toCycle(cycleId, marketKey, "OPEN", List.of(), marketPositions, new DeltaAccumulator()));
            }

            List<LendingCycleView> result = new ArrayList<>();
            List<LendingHistoryEntryView> currentEvents = new ArrayList<>();
            DeltaAccumulator deltas = new DeltaAccumulator();
            CycleState cycleState = new CycleState();
            int cycleIndex = 1;
            boolean marketHasOpenPosition = marketPositions.stream().anyMatch(position -> position.quantity().signum() > 0);

            for (int index = 0; index < marketHistory.size(); index++) {
                LendingHistoryEntryView event = effectiveSequentialEvent(cycleState, marketHistory.get(index));
                if (currentEvents.isEmpty() && !isOpeningEvent(event)) {
                    if (attachPostCloseEventToPreviousCycle(result, event)) {
                        continue;
                    }
                    if ("REWARD_CLAIM".equals(event.type())) {
                        continue;
                    }
                    DeltaAccumulator orphanDeltas = new DeltaAccumulator();
                    orphanDeltas.add(event);
                    String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":orphan-" + cycleIndex++;
                    result.add(toCycle(cycleId, marketKey, "AMBIGUOUS_NEEDS_REVIEW", List.of(event), List.of(), orphanDeltas));
                    continue;
                }
                if (!currentEvents.isEmpty() && isUnmatchedClosingEvent(cycleState, event)) {
                    DeltaAccumulator orphanDeltas = new DeltaAccumulator();
                    orphanDeltas.add(event);
                    String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":orphan-" + cycleIndex++;
                    result.add(toCycle(cycleId, marketKey, "AMBIGUOUS_NEEDS_REVIEW", List.of(event), List.of(), orphanDeltas));
                    continue;
                }
                if (currentEvents.isEmpty()) {
                    deltas = new DeltaAccumulator();
                    cycleState = new CycleState();
                }
                currentEvents.add(event);
                deltas.add(event);
                cycleState.add(event);

                boolean closeNow = cycleState.isFlat() && isClosingEvent(event);
                if (closeNow) {
                    String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex++;
                    result.add(toCycle(cycleId, marketKey, "CLOSED", currentEvents, List.of(), deltas));
                    currentEvents = new ArrayList<>();
                }
            }

            if (!currentEvents.isEmpty()) {
                String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex;
                String status = marketHasOpenPosition ? "OPEN" : "CLOSED";
                result.add(toCycle(cycleId, marketKey, status, currentEvents, marketPositions, deltas));
            }
            return result;
        }

        private boolean attachPostCloseEventToPreviousCycle(
                List<LendingCycleView> result,
                LendingHistoryEntryView event
        ) {
            if (!(isCompoundProtocol() || isEulerProtocol())
                    || result.isEmpty()
                    || event == null
                    || (!isClosingEvent(event)
                    && !"REWARD_CLAIM".equals(event.type())
                    && !"LENDING_CASH_EXIT".equals(event.type()))) {
                return false;
            }
            if (!"LENDING_CASH_EXIT".equals(event.type())
                    && !isCompoundProtocol()
                    && isEulerProtocol()) {
                return false;
            }
            if (!"LENDING_CASH_EXIT".equals(event.type())
                    && isClosingEvent(event)
                    && zeroIfNull(event.quantity()).compareTo(CYCLE_DUST_TOLERANCE) > 0) {
                return false;
            }
            LendingCycleView previous = result.get(result.size() - 1);
            if (!"CLOSED".equals(previous.status())) {
                return false;
            }
            List<LendingHistoryEntryView> events = new ArrayList<>(previous.events());
            events.add(event);
            List<LendingHistoryEntryView> ascending = events.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList();
            DeltaAccumulator deltas = new DeltaAccumulator();
            ascending.forEach(deltas::add);
            result.set(result.size() - 1, toCycle(
                    previous.id(),
                    previous.marketKey(),
                    previous.status(),
                    ascending,
                    previous.positions(),
                    deltas
            ));
            return true;
        }

        private LendingHistoryEntryView effectiveSequentialEvent(CycleState cycleState, LendingHistoryEntryView event) {
            if (!isCompoundProtocol()
                    || event == null
                    || !"LENDING_DEPOSIT".equals(event.type())
                    || !LendingAssetSymbolSupport.isStable(event.assetSymbol())
                    || cycleState == null
                    || !cycleState.hasAnyDebt()) {
                return event;
            }
            return new LendingHistoryEntryView(
                    event.id(),
                    event.txHash(),
                    event.marketKey(),
                    event.cycleId(),
                    event.networkId(),
                    event.walletAddress(),
                    event.blockTimestamp(),
                    "REPAY",
                    "COMET_BASE_REPAY",
                    "Repay",
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.feeUsd(),
                    event.feeQuantityByAsset(),
                    event.loopId()
            );
        }

        private boolean usesConcurrentAssetCycles() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("AAVE");
        }

        private List<LendingCycleView> buildConcurrentAssetCycles(
                String marketKey,
                List<LendingHistoryEntryView> marketHistory,
                List<LendingPositionView> marketPositions
        ) {
            if (marketHistory.isEmpty() && !marketPositions.isEmpty()) {
                return positionsOnlyCycles(marketKey, marketPositions);
            }

            List<LendingCycleView> result = new ArrayList<>();
            List<MutableCycle> openCycles = new ArrayList<>();
            int cycleIndex = 1;
            int orphanIndex = 1;

            for (LendingHistoryEntryView event : marketHistory) {
                MutableCycle target = selectCycle(openCycles, event);
                if (target == null) {
                    if ("REWARD_CLAIM".equals(event.type())) {
                        continue;
                    }
                    if (isOpeningEvent(event)) {
                        target = new MutableCycle(marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex++);
                        openCycles.add(target);
                    } else {
                        DeltaAccumulator orphanDeltas = new DeltaAccumulator();
                        orphanDeltas.add(event);
                        String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":orphan-" + orphanIndex++;
                        result.add(toCycle(cycleId, marketKey, "AMBIGUOUS_NEEDS_REVIEW", List.of(event), List.of(), orphanDeltas));
                        continue;
                    }
                }

                target.add(event);
                if (isClosingEvent(event) && target.isFlat()) {
                    result.add(toCycle(target.id(), marketKey, "CLOSED", target.events(), List.of(), target.deltas()));
                    openCycles.remove(target);
                }
            }

            LinkedHashSet<LendingPositionView> assignedPositions = new LinkedHashSet<>();
            for (MutableCycle cycle : openCycles) {
                List<LendingPositionView> cyclePositions = positionsForCycle(cycle.state(), marketPositions);
                assignedPositions.addAll(cyclePositions);
                String status = cyclePositions.stream().anyMatch(position -> position.quantity().signum() > 0)
                        ? "OPEN"
                        : "CLOSED";
                result.add(toCycle(
                        cycle.id(),
                        marketKey,
                        status,
                        cycle.events(),
                        "OPEN".equals(status) ? cyclePositions : List.of(),
                        cycle.deltas()
                ));
            }

            List<LendingPositionView> unassignedPositions = marketPositions.stream()
                    .filter(position -> !assignedPositions.contains(position))
                    .toList();
            if (!unassignedPositions.isEmpty()) {
                result.addAll(positionsOnlyCycles(marketKey, unassignedPositions, cycleIndex));
            }

            return result;
        }

        private List<LendingCycleView> positionsOnlyCycles(
                String marketKey,
                List<LendingPositionView> marketPositions
        ) {
            return positionsOnlyCycles(marketKey, marketPositions, 1);
        }

        private List<LendingCycleView> positionsOnlyCycles(
                String marketKey,
                List<LendingPositionView> marketPositions,
                int firstCycleIndex
        ) {
            Map<String, List<LendingPositionView>> positionsByAsset = marketPositions.stream()
                    .collect(Collectors.groupingBy(
                            position -> cycleStateAsset(position.assetSymbol()),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            List<LendingCycleView> cycles = new ArrayList<>();
            int cycleIndex = firstCycleIndex;
            for (List<LendingPositionView> positions : positionsByAsset.values()) {
                String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex++;
                cycles.add(toCycle(cycleId, marketKey, "OPEN", List.of(), positions, new DeltaAccumulator()));
            }
            return cycles;
        }

        private MutableCycle selectCycle(List<MutableCycle> openCycles, LendingHistoryEntryView event) {
            if (openCycles.isEmpty()) {
                return null;
            }
            String asset = cycleStateAsset(event.assetSymbol());
            if (isOpeningEvent(event)) {
                return latestBorrowFollowedBySupply(openCycles, event)
                        .or(() -> latestWithPositiveSupply(openCycles, asset))
                        .orElse(null);
            }
            if ("BORROW".equals(event.type())) {
                return latestWithPositiveDebt(openCycles, asset)
                        .or(() -> latestRecentlyOpened(openCycles, event))
                        .or(() -> latestWithAnySupply(openCycles))
                        .orElse(null);
            }
            if ("REPAY".equals(event.type())) {
                return latestWithPositiveDebt(openCycles, asset)
                        .or(() -> latestWithAnyDebt(openCycles))
                        .or(() -> latestWithAnySupply(openCycles))
                        .orElse(null);
            }
            if (isClosingEvent(event)) {
                return latestWithPositiveSupply(openCycles, asset).orElse(null);
            }
            if ("REWARD_CLAIM".equals(event.type()) && openCycles.size() == 1) {
                return openCycles.get(0);
            }
            return null;
        }

        private Optional<MutableCycle> latestBorrowFollowedBySupply(
                List<MutableCycle> openCycles,
                LendingHistoryEntryView supply
        ) {
            return openCycles.stream()
                    .filter(cycle -> cycle.lastEvent().map(last -> "BORROW".equals(last.type())).orElse(false))
                    .filter(cycle -> isWithinLoopWindow(cycle.lastTimestamp(), supply.blockTimestamp()))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestRecentlyOpened(
                List<MutableCycle> openCycles,
                LendingHistoryEntryView event
        ) {
            return openCycles.stream()
                    .filter(cycle -> cycle.lastEvent().map(this::isOpeningEvent).orElse(false))
                    .filter(cycle -> isWithinLoopWindow(cycle.lastTimestamp(), event.blockTimestamp()))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithPositiveSupply(List<MutableCycle> openCycles, String asset) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasPositiveSupply(asset))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithPositiveDebt(List<MutableCycle> openCycles, String asset) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasPositiveDebt(asset))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithAnySupply(List<MutableCycle> openCycles) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasAnySupply())
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithAnyDebt(List<MutableCycle> openCycles) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasAnyDebt())
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private boolean isWithinLoopWindow(Instant left, Instant right) {
            if (left == null || right == null) {
                return false;
            }
            return !Duration.between(left, right).abs().minus(LOOP_GROUP_WINDOW).isPositive();
        }

        private List<LendingPositionView> positionsForCycle(CycleState state, List<LendingPositionView> positions) {
            return positions.stream()
                    .filter(position -> {
                        String asset = cycleStateAsset(position.assetSymbol());
                        if ("BORROW".equals(position.side())) {
                            return state.hasPositiveDebt(asset);
                        }
                        return state.hasPositiveSupply(asset);
                    })
                    .toList();
        }

        private BigDecimal estimateCoveredQuantity(String marketKey, String assetSymbol) {
            String targetAsset = cycleStateAsset(assetSymbol);
            CycleState cycleState = new CycleState();
            boolean active = false;
            for (LendingHistoryEntryView event : history.stream()
                    .filter(candidate -> Objects.equals(candidate.marketKey(), marketKey))
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList()) {
                if (!active && !isOpeningEvent(event)) {
                    continue;
                }
                active = true;
                cycleState.add(event);
                if (isClosingEvent(event) && cycleState.isFlat()) {
                    cycleState = new CycleState();
                    active = false;
                }
            }
            return cycleState.supplyAmount(targetAsset).max(BigDecimal.ZERO);
        }

        private LendingCycleView toCycle(
                String cycleId,
                String marketKey,
                String status,
                List<LendingHistoryEntryView> events,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            List<LendingHistoryEntryView> cycleEvents = events.stream()
                    .map(event -> eventWithCycle(event, cycleId))
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp).reversed())
                    .toList();
            LendingHistoryEntryView start = events.isEmpty() ? null : events.get(0);
            LendingHistoryEntryView close = "CLOSED".equals(status) && !events.isEmpty()
                    ? events.stream().filter(this::isClosingEvent).reduce((first, second) -> second).orElse(events.get(events.size() - 1))
                    : null;
            BigDecimal unrealizedUsd = cyclePositions.stream()
                    .map(LendingPositionView::valueUsd)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
            LendingPnlBreakdownView pnlBreakdown = pnlBreakdown(status, cyclePositions, deltas);
            LendingPnlAssetBreakdownView pnlAssetBreakdown = pnlAssetBreakdown(status, cyclePositions, deltas);
            LendingTotalValuationView totalValuation = totalValuation(status, unrealizedUsd, pnlBreakdown, deltas);
            List<String> largePnlReasons = largePnlReasons(status, pnlBreakdown, totalValuation, pnlAssetBreakdown, deltas);
            String primaryLargePnlReason = largePnlReasons.isEmpty() ? null : largePnlReasons.get(0);
            CyclePeakView peaks = cyclePeaks(events);
            return new LendingCycleView(
                    cycleId,
                    marketKey,
                    marketLabel(marketKey),
                    status,
                    start == null ? null : start.blockTimestamp(),
                    close == null ? null : close.blockTimestamp(),
                    start == null ? null : start.txHash(),
                    close == null ? null : close.txHash(),
                    statusDetail(status, deltas),
                    warningReason(status, deltas),
                    pnlAssetBreakdown.netIncomeByAsset(),
                    pnlAssetBreakdown.precisionByAsset(),
                    pnlAssetBreakdown.reasonByAsset(),
                    primaryAssetPnlSummary(pnlAssetBreakdown),
                    primaryLargePnlReason,
                    largePnlReasons,
                    primaryLargePnlReason,
                    deltas.toView(),
                    new LendingPnlView(pnlBreakdown.netPnlUsd(), pnlBreakdown.precision(), pnlBreakdown.method()),
                    new LendingPnlView(
                            "OPEN".equals(status) ? unrealizedUsd : null,
                            "OPEN".equals(status) ? "ESTIMATED" : "UNAVAILABLE",
                            "OPEN".equals(status) ? "current-balance-current-quote" : "closed-cycle"
                    ),
                    pnlBreakdown,
                    pnlAssetBreakdown,
                    totalValuation,
                    observedFlowsByAsset(status, deltas),
                    peaks.peakSupplyUsd(),
                    peaks.peakBorrowUsd(),
                    durationDays(start, close),
                    cyclePositions,
                    cycleEvents,
                    txGroups(cycleId, events)
            );
        }

        private LendingTotalValuationView totalValuation(
                String status,
                BigDecimal currentUsdValue,
                LendingPnlBreakdownView yieldOnlyPnl,
                DeltaAccumulator deltas
        ) {
            return LendingCycleValuationCalculator.calculate(new LendingCycleValuationCalculator.Input(
                    status,
                    deltas.principalInUsd(),
                    deltas.principalOutCashUsd(),
                    deltas.borrowedUsd(),
                    deltas.repaidUsd(),
                    deltas.rewardUsd(),
                    deltas.feesUsd(),
                    deltas.feesUsd(),
                    currentUsdValue,
                    yieldOnlyPnl.netPnlUsd(),
                    yieldOnlyPnl.precision(),
                    deltas.hasWrapperOrShareExposure(),
                    deltas.hasMissingEventValuation(),
                    deltas.hasMissingGasUsdValuation(),
                    "CLOSED".equals(status) && deltas.hasUnresolvedPrincipalExitByAsset()
            ));
        }

        private String primaryAssetPnlSummary(LendingPnlAssetBreakdownView pnlAssetBreakdown) {
            return pnlAssetBreakdown.netIncomeByAsset().entrySet().stream()
                    .filter(entry -> entry.getValue() != null && entry.getValue().signum() != 0)
                    .max(Comparator.comparing(entry -> entry.getValue().abs()))
                    .map(entry -> formatAssetQuantity(entry.getValue()) + " " + entry.getKey())
                    .orElse(null);
        }

        private String formatAssetQuantity(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.signum() > 0 ? "+" + stripped.toPlainString() : stripped.toPlainString();
        }

        private List<String> largePnlReasons(
                String status,
                LendingPnlBreakdownView pnlBreakdown,
                LendingTotalValuationView totalValuation,
                LendingPnlAssetBreakdownView pnlAssetBreakdown,
                DeltaAccumulator deltas
        ) {
            if (!"CLOSED".equals(status)
                    || totalValuation.totalUsdPnl() == null
                    || totalValuation.totalUsdPnl().compareTo(new BigDecimal("-100")) >= 0) {
                return List.of();
            }
            LinkedHashSet<String> reasons = new LinkedHashSet<>();
            if (totalValuation.unavailableReason() != null) {
                reasons.add("MISSING_PRICE");
            }
            if (deltas.hasUnresolvedPrincipalExitByAsset()) {
                reasons.add("LIFECYCLE_LINKING_GAP");
            }
            if (deltas.hasInternalReceiptMovement()) {
                reasons.add("SHARE_RATE_EFFECT");
            } else if (deltas.hasNonStablePrincipalExposure()) {
                reasons.add("REALIZED_MARKET_MOVE");
            }
            if (pnlBreakdown.interestPaidUsd() != null
                    && pnlBreakdown.interestPaidUsd().compareTo(BigDecimal.ZERO) > 0) {
                reasons.add("BORROW_COST");
            }
            if (pnlBreakdown.gasUsd() != null && pnlBreakdown.gasUsd().compareTo(BigDecimal.ZERO) > 0
                    || pnlAssetBreakdown.gasByAsset().values().stream().anyMatch(value -> value.signum() > 0)) {
                reasons.add("GAS_COST");
            }
            return reasons.stream()
                    .sorted(Comparator.comparingInt(this::largePnlReasonPriority))
                    .toList();
        }

        private int largePnlReasonPriority(String reason) {
            return switch (reason) {
                case "LIFECYCLE_LINKING_GAP" -> 1;
                case "MIGRATION_UNRESOLVED" -> 2;
                case "MISSING_PRICE" -> 3;
                case "SHARE_RATE_UNAVAILABLE" -> 4;
                case "SHARE_RATE_EFFECT" -> 5;
                case "REALIZED_MARKET_MOVE" -> 6;
                case "BORROW_COST" -> 7;
                case "GAS_COST" -> 8;
                default -> 100;
            };
        }

        private String statusDetail(String status, DeltaAccumulator deltas) {
            String warning = warningReason(status, deltas);
            if ("CLOSED".equals(status) && warning != null) {
                return "closed/current-state-zero";
            }
            return status == null ? null : status.toLowerCase(Locale.ROOT);
        }

        private String warningReason(String status, DeltaAccumulator deltas) {
            if (!"CLOSED".equals(status)) {
                return null;
            }
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            if (normalizedProtocol.startsWith("FLUID") && key.networkId() == NetworkId.PLASMA) {
                if (!deltas.hasFluidLogOperateEvidence()) {
                    return "pnl_unavailable_missing_full_receipt_logs";
                }
                if (deltas.hasWstUsrExposure()) {
                    return "pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy";
                }
                return null;
            }
            boolean currentStateZeroClosureCanMissPrincipalExit = normalizedProtocol.startsWith("COMPOUND")
                    || normalizedProtocol.startsWith("FLUID")
                    || normalizedProtocol.startsWith("EULER")
                    || normalizedProtocol.startsWith("MORPHO");
            if (!currentStateZeroClosureCanMissPrincipalExit) {
                return null;
            }
            return deltas.hasUnresolvedPrincipalExitByAsset() ? "unresolved_principal_exit" : null;
        }

        private String pnlUnavailableReason(String status, DeltaAccumulator deltas) {
            if ("AMBIGUOUS_NEEDS_REVIEW".equals(status)) {
                return "unavailable:unresolved lifecycle";
            }
            String warning = warningReason(status, deltas);
            if ("pnl_unavailable_missing_full_receipt_logs".equals(warning)) {
                return "unavailable:pnl_unavailable_missing_full_receipt_logs";
            }
            if ("pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy".equals(warning)) {
                return "unavailable:pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy";
            }
            if ("CLOSED".equals(status) && deltas.hasIncompletePrincipalExit()) {
                return "unavailable:unresolved_principal_exit";
            }
            if (containsShareOrVaultAsset(deltas)) {
                return "unavailable:missing share-rate or historical price evidence";
            }
            if (deltas.hasNonStablePrincipalExposure()) {
                return "unavailable:missing yield-only valuation evidence";
            }
            return "asset-delta-only";
        }

        private LendingPnlBreakdownView pnlBreakdown(
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            BigDecimal openEarnedUsd = "OPEN".equals(status)
                    ? cyclePositions.stream()
                    .map(LendingPositionView::earnedUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC))
                    : BigDecimal.ZERO;
            BigDecimal closedEarnedUsd = deltas.principalOutUsd()
                    .subtract(deltas.principalInUsd(), MC)
                    .max(BigDecimal.ZERO)
                    .add(deltas.rewardUsd(), MC);
            BigDecimal interestEarnedUsd = openEarnedUsd.add(closedEarnedUsd, MC);
            BigDecimal interestPaidUsd = deltas.repaidUsd()
                    .subtract(deltas.borrowedUsd(), MC)
                    .max(BigDecimal.ZERO);
            BigDecimal gasUsd = deltas.feesUsd();
            String unavailableReason = pnlUnavailableReason(status, deltas);
            boolean unavailable = unavailableReason.startsWith("unavailable:");
            BigDecimal netPnlUsd = unavailable
                    ? null
                    : interestEarnedUsd.subtract(interestPaidUsd, MC).subtract(gasUsd, MC);
            String precision = unavailable ? "UNAVAILABLE" : ("OPEN".equals(status) ? "ESTIMATED" : "ESTIMATED");
            String method = unavailable ? unavailableReason : "interest-earned-minus-paid-minus-gas";
            String reason = unavailable ? unavailableReason.substring("unavailable:".length()) : null;
            return new LendingPnlBreakdownView(
                    unavailable ? null : interestEarnedUsd,
                    unavailable ? null : interestPaidUsd,
                    gasUsd,
                    netPnlUsd,
                    precision,
                    method,
                    reason
            );
        }

        private LendingPnlAssetBreakdownView pnlAssetBreakdown(
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            boolean assetPnlUnavailable = "AMBIGUOUS_NEEDS_REVIEW".equals(status)
                    || "CLOSED".equals(status) && (
                    deltas.hasUnresolvedPrincipalExitByAsset()
                            || "pnl_unavailable_missing_full_receipt_logs".equals(warningReason(status, deltas))
            );
            Map<String, BigDecimal> supplyIncomeByAsset = "OPEN".equals(status)
                    ? openSupplyIncomeByAsset(cyclePositions)
                    : assetPnlUnavailable
                    ? Map.of()
                    : subtractMaps(deltas.principalOutByAsset(), deltas.principalInByAsset());
            Map<String, BigDecimal> borrowCostByAsset = positiveEntries(subtractMaps(
                    deltas.repaidByAsset(),
                    deltas.borrowedByAsset()
            ));
            Map<String, BigDecimal> rewardsByAsset = positiveEntries(deltas.rewardByAsset());
            Map<String, BigDecimal> gasByAsset = positiveEntries(deltas.gasByAsset());
            Map<String, BigDecimal> netIncomeByAsset = netIncomeByAsset(
                    supplyIncomeByAsset,
                    borrowCostByAsset,
                    rewardsByAsset,
                    gasByAsset
            );
            Map<String, String> precisionByAsset = precisionByAsset(status, deltas, netIncomeByAsset.keySet());
            Map<String, String> reasonByAsset = reasonByAsset(status, deltas, precisionByAsset.keySet());
            return new LendingPnlAssetBreakdownView(
                    Map.copyOf(supplyIncomeByAsset),
                    Map.copyOf(borrowCostByAsset),
                    Map.copyOf(rewardsByAsset),
                    Map.copyOf(gasByAsset),
                    Map.copyOf(netIncomeByAsset),
                    Map.copyOf(precisionByAsset),
                    Map.copyOf(reasonByAsset)
            );
        }

        private Map<String, List<LendingObservedFlowView>> observedFlowsByAsset(
                String status,
                DeltaAccumulator deltas
        ) {
            String reason = pnlUnavailableReason(status, deltas);
            if (!reason.startsWith("unavailable:")) {
                return Map.of();
            }
            return deltas.observedFlowsByAsset(reason.substring("unavailable:".length()));
        }

        private Map<String, BigDecimal> openSupplyIncomeByAsset(List<LendingPositionView> cyclePositions) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (LendingPositionView position : cyclePositions) {
                if (!"SUPPLY".equals(position.side())) {
                    continue;
                }
                BigDecimal income = zeroIfNull(position.quantity())
                        .subtract(zeroIfNull(position.coveredQuantity()), MC);
                if (income.signum() <= 0) {
                    continue;
                }
                String asset = cycleStateAsset(position.underlyingSymbol());
                result.merge(asset, income, (left, right) -> left.add(right, MC));
            }
            return result;
        }

        private Map<String, BigDecimal> subtractMaps(
                Map<String, BigDecimal> left,
                Map<String, BigDecimal> right
        ) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (String asset : unionKeys(left, right)) {
                BigDecimal value = left.getOrDefault(asset, BigDecimal.ZERO)
                        .subtract(right.getOrDefault(asset, BigDecimal.ZERO), MC);
                if (value.signum() != 0) {
                    result.put(asset, value);
                }
            }
            return result;
        }

        private Map<String, BigDecimal> positiveEntries(Map<String, BigDecimal> source) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
                if (entry.getValue() != null && entry.getValue().signum() > 0) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }

        private Map<String, BigDecimal> netIncomeByAsset(
                Map<String, BigDecimal> supplyIncomeByAsset,
                Map<String, BigDecimal> borrowCostByAsset,
                Map<String, BigDecimal> rewardsByAsset,
                Map<String, BigDecimal> gasByAsset
        ) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (String asset : unionKeys(supplyIncomeByAsset, borrowCostByAsset, rewardsByAsset, gasByAsset)) {
                BigDecimal value = supplyIncomeByAsset.getOrDefault(asset, BigDecimal.ZERO)
                        .add(rewardsByAsset.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .subtract(borrowCostByAsset.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .subtract(gasByAsset.getOrDefault(asset, BigDecimal.ZERO), MC);
                if (value.signum() != 0) {
                    result.put(asset, value);
                }
            }
            return result;
        }

        @SafeVarargs
        private final Set<String> unionKeys(Map<String, BigDecimal>... sources) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Map<String, BigDecimal> source : sources) {
                keys.addAll(source.keySet());
            }
            return keys;
        }

        private Map<String, String> precisionByAsset(
                String status,
                DeltaAccumulator deltas,
                Set<String> derivedAssets
        ) {
            LinkedHashSet<String> assets = new LinkedHashSet<>(derivedAssets);
            assets.addAll(deltas.incompletePrincipalExitAssets());
            Map<String, String> precision = new LinkedHashMap<>();
            for (String asset : assets) {
                precision.put(asset, assetPnlUnavailableReason(status, deltas, asset) == null
                        ? ("OPEN".equals(status) ? "ESTIMATED" : "EXACT")
                        : "UNAVAILABLE");
            }
            return precision;
        }

        private Map<String, String> reasonByAsset(
                String status,
                DeltaAccumulator deltas,
                Set<String> assets
        ) {
            Map<String, String> reasons = new LinkedHashMap<>();
            for (String asset : assets) {
                String reason = assetPnlUnavailableReason(status, deltas, asset);
                if (reason != null) {
                    reasons.put(asset, reason);
                }
            }
            return reasons;
        }

        private String assetPnlUnavailableReason(String status, DeltaAccumulator deltas, String asset) {
            if ("AMBIGUOUS_NEEDS_REVIEW".equals(status)) {
                return "unresolved lifecycle";
            }
            if ("CLOSED".equals(status)
                    && "pnl_unavailable_missing_full_receipt_logs".equals(warningReason(status, deltas))) {
                return "pnl_unavailable_missing_full_receipt_logs";
            }
            if ("CLOSED".equals(status)
                    && "pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy".equals(warningReason(status, deltas))) {
                return "pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy";
            }
            if ("CLOSED".equals(status) && deltas.incompletePrincipalExitAssets().contains(asset)) {
                return "unresolved_principal_exit";
            }
            return null;
        }

        private CyclePeakView cyclePeaks(List<LendingHistoryEntryView> events) {
            BigDecimal currentSupply = BigDecimal.ZERO;
            BigDecimal currentBorrow = BigDecimal.ZERO;
            BigDecimal peakSupply = BigDecimal.ZERO;
            BigDecimal peakBorrow = BigDecimal.ZERO;
            for (LendingHistoryEntryView event : events) {
                BigDecimal valueUsd = zeroIfNull(event.valueUsd());
                switch (event.type()) {
                    case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN" ->
                            currentSupply = currentSupply.add(valueUsd, MC);
                    case "LENDING_WITHDRAW", "VAULT_WITHDRAW", "LENDING_LOOP_DECREASE", "LENDING_LOOP_CLOSE" ->
                            currentSupply = currentSupply.subtract(valueUsd, MC).max(BigDecimal.ZERO);
                    case "BORROW" -> currentBorrow = currentBorrow.add(valueUsd, MC);
                    case "REPAY" -> currentBorrow = currentBorrow.subtract(valueUsd, MC).max(BigDecimal.ZERO);
                    default -> {
                    }
                }
                peakSupply = peakSupply.max(currentSupply);
                peakBorrow = peakBorrow.max(currentBorrow);
            }
            return new CyclePeakView(peakSupply, peakBorrow);
        }

        private Long durationDays(LendingHistoryEntryView start, LendingHistoryEntryView close) {
            if (start == null || start.blockTimestamp() == null) {
                return null;
            }
            Instant end = close == null || close.blockTimestamp() == null ? Instant.now() : close.blockTimestamp();
            return Math.max(0L, Duration.between(start.blockTimestamp(), end).toDays());
        }

        private List<LendingTxGroupView> txGroups(String cycleId, List<LendingHistoryEntryView> events) {
            List<LendingHistoryEntryView> ordered = deduplicateDisplayEvents(events.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList());
            List<LendingTxGroupView> groups = new ArrayList<>();
            int index = 0;
            int groupIndex = 1;
            while (index < ordered.size()) {
                LendingHistoryEntryView event = ordered.get(index);
                if (isLoopStart(ordered, index)) {
                    List<LendingHistoryEntryView> loopEvents = new ArrayList<>();
                    while (index < ordered.size()) {
                        LendingHistoryEntryView candidate = ordered.get(index);
                        if (candidate.type().startsWith("LENDING_LOOP")) {
                            loopEvents.add(candidate);
                            index++;
                            continue;
                        }
                        if (isBorrowSupplyPair(ordered, index)) {
                            loopEvents.add(ordered.get(index));
                            loopEvents.add(ordered.get(index + 1));
                            index += 2;
                            continue;
                        }
                        break;
                    }
                    groups.add(txGroup(cycleId, groupIndex++, "loop", loopEvents));
                    continue;
                }
                groups.add(txGroup(cycleId, groupIndex++, groupType(event), List.of(event)));
                index++;
            }
            return groups;
        }

        private boolean isLoopStart(List<LendingHistoryEntryView> events, int index) {
            LendingHistoryEntryView event = events.get(index);
            return event.type().startsWith("LENDING_LOOP") || isBorrowSupplyPair(events, index);
        }

        private boolean isBorrowSupplyPair(List<LendingHistoryEntryView> events, int index) {
            if (index >= events.size() - 1) {
                return false;
            }
            LendingHistoryEntryView borrow = events.get(index);
            LendingHistoryEntryView supply = events.get(index + 1);
            if (!"BORROW".equals(borrow.type()) || !isOpeningEvent(supply)) {
                return false;
            }
            if (borrow.blockTimestamp() == null || supply.blockTimestamp() == null) {
                return false;
            }
            Duration gap = Duration.between(borrow.blockTimestamp(), supply.blockTimestamp()).abs();
            return !gap.minus(LOOP_GROUP_WINDOW).isPositive();
        }

        private LendingTxGroupView txGroup(
                String cycleId,
                int groupIndex,
                String type,
                List<LendingHistoryEntryView> events
        ) {
            List<LendingTxItemView> items = deduplicateDisplayEvents(events).stream().map(this::txItem).toList();
            Instant timestamp = events.isEmpty() ? null : events.get(0).blockTimestamp();
            return new LendingTxGroupView(
                    cycleId + ":group-" + groupIndex,
                    type,
                    timestamp,
                    timestamp == null ? null : timestamp.toString(),
                    "loop".equals(type) ? loopSteps(events) : null,
                    "loop".equals(type) ? loopAsset(events, false) : null,
                    "loop".equals(type) ? loopAsset(events, true) : null,
                    items
            );
        }

        private List<LendingHistoryEntryView> deduplicateDisplayEvents(List<LendingHistoryEntryView> events) {
            if (!isFluidProtocol()) {
                return events;
            }
            LinkedHashMap<String, LendingHistoryEntryView> unique = new LinkedHashMap<>();
            for (LendingHistoryEntryView event : events) {
                String key = "REPAY".equals(event.type())
                        ? String.join("|",
                        nullToEmpty(event.txHash()),
                        nullToEmpty(event.marketKey()),
                        nullToEmpty(cycleStateAsset(event.assetSymbol())),
                        zeroIfNull(event.quantity()).stripTrailingZeros().toPlainString())
                        : event.id();
                unique.putIfAbsent(key, event);
            }
            return new ArrayList<>(unique.values());
        }

        private LendingTxItemView txItem(LendingHistoryEntryView event) {
            return new LendingTxItemView(
                    event.id(),
                    event.type(),
                    event.displayType(),
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.txHash(),
                    event.blockTimestamp()
            );
        }

        private Integer loopSteps(List<LendingHistoryEntryView> events) {
            long borrowSteps = events.stream().filter(event -> "BORROW".equals(event.type())).count();
            if (borrowSteps > 0) {
                return Math.toIntExact(borrowSteps);
            }
            return Math.max(1, events.size());
        }

        private String loopAsset(List<LendingHistoryEntryView> events, boolean supplySide) {
            String label = events.stream()
                    .filter(event -> supplySide == isOpeningEvent(event))
                    .map(LendingHistoryEntryView::assetSymbol)
                    .map(SessionLendingQueryService::cycleStateAsset)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return label.isBlank() ? null : label;
        }

        private String groupType(LendingHistoryEntryView event) {
            if (isOpeningEvent(event)) {
                return "open";
            }
            if ("BORROW".equals(event.type())) {
                return "borrow";
            }
            if ("REWARD_CLAIM".equals(event.type())) {
                return "reward";
            }
            if (isClosingEvent(event)) {
                return "close";
            }
            return "mid";
        }

        private LendingHistoryEntryView eventWithCycle(LendingHistoryEntryView event, String cycleId) {
            return new LendingHistoryEntryView(
                    event.id(),
                    event.txHash(),
                    event.marketKey(),
                    cycleId,
                    event.networkId(),
                    event.walletAddress(),
                    event.blockTimestamp(),
                    event.type(),
                    event.eventSubtype(),
                    event.displayType(),
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.feeUsd(),
                    event.feeQuantityByAsset(),
                    event.loopId()
            );
        }

        private String marketLabel(String marketKey) {
            int index = marketKey == null ? -1 : marketKey.lastIndexOf(':');
            return index < 0 ? marketKey : marketKey.substring(index + 1);
        }

        private boolean isClosingEvent(LendingHistoryEntryView event) {
            return "LENDING_WITHDRAW".equals(event.type())
                    || "VAULT_WITHDRAW".equals(event.type())
                    || "REPAY".equals(event.type())
                    || "LENDING_LOOP_CLOSE".equals(event.type())
                    || "LENDING_LOOP_DECREASE".equals(event.type());
        }

        private boolean isUnmatchedClosingEvent(CycleState state, LendingHistoryEntryView event) {
            String asset = cycleStateAsset(event.assetSymbol());
            if (isFluidProtocol() && isClosingEvent(event) && (state.hasAnySupply() || state.hasAnyDebt())) {
                return false;
            }
            if (isEulerProtocol()
                    && isClosingEvent(event)
                    && LendingAssetSymbolSupport.isLendingPositionSymbol(event.assetSymbol())
                    && (state.hasAnySupply() || state.hasAnyDebt())) {
                return false;
            }
            if (isMorphoProtocol()
                    && isClosingEvent(event)
                    && (state.hasAnySupply() || state.hasAnyDebt())) {
                return false;
            }
            if ("REPAY".equals(event.type())) {
                return !state.hasPositiveDebt(asset) && !state.hasAnyDebt();
            }
            if ("LENDING_WITHDRAW".equals(event.type())
                    || "VAULT_WITHDRAW".equals(event.type())
                    || "LENDING_LOOP_CLOSE".equals(event.type())
                    || "LENDING_LOOP_DECREASE".equals(event.type())) {
                return !state.hasPositiveSupply(asset);
            }
            return false;
        }

        private boolean isFluidProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("FLUID");
        }

        private boolean isMorphoProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("MORPHO");
        }

        private boolean isCompoundProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("COMPOUND");
        }

        private boolean isOpeningEvent(LendingHistoryEntryView event) {
            return "LENDING_DEPOSIT".equals(event.type())
                    || "VAULT_DEPOSIT".equals(event.type())
                    || "LENDING_LOOP_OPEN".equals(event.type());
        }

        private boolean containsShareOrVaultAsset(DeltaAccumulator deltas) {
            return deltas.hasShareOrVaultAsset();
        }

        private boolean isBorrowPosition(OnChainBalance balance, AssetLedgerPoint point) {
            return LendingAssetSymbolSupport.isBorrowSymbol(balance.getAssetSymbol())
                    || point != null && (NormalizedTransactionType.BORROW.name().equals(point.getNormalizedType())
                    || NormalizedTransactionType.REPAY.name().equals(point.getNormalizedType()));
        }

        private List<HistoryAmount> historyAmounts(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
        ) {
            List<HistoryAmount> protocolChildLegAmounts = protocolChildLegAmounts(transaction, prices, historicalPrices);
            if (!protocolChildLegAmounts.isEmpty()) {
                return protocolChildLegAmounts;
            }
            List<HistoryAmount> compoundLoopAmounts = compoundLoopAmounts(transaction, prices, historicalPrices);
            if (!compoundLoopAmounts.isEmpty()) {
                return compoundLoopAmounts;
            }

            Optional<NormalizedTransaction.Flow> primary = preferredHistoryFlow(transaction)
                    .or(() -> safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                    .filter(flow -> flow.getQuantityDelta() != null)
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs())));
            String type = transaction.getType() == null ? "UNKNOWN" : transaction.getType().name();
            String eventSubtype = eventSubtype(transaction);
            if (primary.isEmpty()) {
                return List.of(new HistoryAmount(
                        null,
                        type,
                        eventSubtype,
                        displayType(transaction.getType(), eventSubtype),
                        "UNKNOWN",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        Map.of()
                ));
            }
            NormalizedTransaction.Flow flow = primary.get();
            BigDecimal feeUsd = safeFlows(transaction).stream()
                    .filter(candidate -> candidate.getRole() == NormalizedLegRole.FEE)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
            Map<String, BigDecimal> feeQuantityByAsset = feeQuantityByAsset(transaction);
            return List.of(new HistoryAmount(
                    null,
                    type,
                    eventSubtype,
                    displayType(transaction.getType(), eventSubtype),
                    LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()),
                    flowQuantity(flow),
                    flowValueUsd(flow, prices, historicalPrices, transaction.getBlockTimestamp()),
                    feeUsd,
                    feeQuantityByAsset
            ));
        }

        private List<HistoryAmount> protocolChildLegAmounts(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
        ) {
            if (transaction == null || (!isFluidProtocol() && !isMorphoProtocol()) || transaction.getMetadata() == null) {
                return List.of();
            }
            Object rawLegs = transaction.getMetadata().get("lendingChildLegs");
            if (!(rawLegs instanceof List<?> legs) || legs.isEmpty()) {
                return List.of();
            }
            BigDecimal feeUsd = safeFlows(transaction).stream()
                    .filter(candidate -> candidate.getRole() == NormalizedLegRole.FEE)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
            Map<String, BigDecimal> fees = feeQuantityByAsset(transaction);
            List<HistoryAmount> amounts = new ArrayList<>();
            int index = 0;
            for (Object rawLeg : legs) {
                if (!(rawLeg instanceof Document leg)) {
                    continue;
                }
                String type = stringValue(leg.get("type"));
                String assetSymbol = stringValue(leg.get("assetSymbol"));
                BigDecimal quantity = decimalValue(leg.get("quantity"));
                if (type == null || assetSymbol == null || quantity == null || quantity.signum() == 0) {
                    continue;
                }
                BigDecimal valueUsd = quantity.abs().multiply(resolvePrice(
                        prices,
                        historicalPrices,
                        assetSymbol,
                        transaction.getBlockTimestamp()
                ), MC);
                amounts.add(new HistoryAmount(
                        stringValue(leg.get("id")),
                        type,
                        stringValue(leg.get("eventSubtype")),
                        firstNonBlank(stringValue(leg.get("displayType")), displayType(type)),
                        LendingAssetSymbolSupport.displaySymbol(assetSymbol),
                        quantity.abs(),
                        valueUsd,
                        index == 0 ? feeUsd : BigDecimal.ZERO,
                        index == 0 ? fees : Map.of()
                ));
                index++;
            }
            return amounts;
        }

        private List<HistoryAmount> compoundLoopAmounts(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
        ) {
            if (transaction == null || transaction.getType() == null || (!isCompoundProtocol() && !isFluidProtocol())) {
                return List.of();
            }
            if (transaction.getType() != NormalizedTransactionType.LENDING_LOOP_OPEN
                    && transaction.getType() != NormalizedTransactionType.LENDING_LOOP_DECREASE
                    && transaction.getType() != NormalizedTransactionType.LENDING_LOOP_CLOSE) {
                return List.of();
            }
            List<NormalizedTransaction.Flow> nonFeeFlows = safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                    .filter(flow -> flow.getQuantityDelta() != null)
                    .toList();
            Optional<NormalizedTransaction.Flow> stableIn = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() > 0)
                    .filter(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            Optional<NormalizedTransaction.Flow> stableOut = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() < 0)
                    .filter(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            Optional<NormalizedTransaction.Flow> collateralIn = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() > 0)
                    .filter(flow -> !LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            Optional<NormalizedTransaction.Flow> collateralOut = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() < 0)
                    .filter(flow -> !LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            BigDecimal feeUsd = safeFlows(transaction).stream()
                    .filter(candidate -> candidate.getRole() == NormalizedLegRole.FEE)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
            Map<String, BigDecimal> fees = feeQuantityByAsset(transaction);

            List<HistoryAmount> amounts = new ArrayList<>();
            if (transaction.getType() == NormalizedTransactionType.LENDING_LOOP_OPEN) {
                collateralOut.ifPresent(flow -> amounts.add(historyAmount(
                        "supply",
                        "LENDING_LOOP_OPEN",
                        "SUPPLY_COLLATERAL",
                        "Supply collateral",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        feeUsd,
                        fees
                )));
                stableIn.ifPresent(flow -> amounts.add(historyAmount(
                        "borrow",
                        "BORROW",
                        "BORROW",
                        "Borrow",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        BigDecimal.ZERO,
                        Map.of()
                )));
            } else {
                stableOut.ifPresent(flow -> amounts.add(historyAmount(
                        "repay",
                        "REPAY",
                        "REPAY",
                        "Repay",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        feeUsd,
                        fees
                )));
                collateralIn.ifPresent(flow -> amounts.add(historyAmount(
                        "collateral",
                        transaction.getType().name(),
                        "WITHDRAW_COLLATERAL",
                        transaction.getType() == NormalizedTransactionType.LENDING_LOOP_CLOSE
                                ? "Loop close"
                                : "Loop decrease",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        BigDecimal.ZERO,
                        Map.of()
                )));
            }
            return amounts;
        }

        private HistoryAmount historyAmount(
                String syntheticIndex,
                String type,
                String eventSubtype,
                String displayType,
                NormalizedTransaction.Flow flow,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
                Instant timestamp,
                BigDecimal feeUsd,
                Map<String, BigDecimal> feeQuantityByAsset
        ) {
            return new HistoryAmount(
                    syntheticIndex,
                    type,
                    eventSubtype,
                    displayType,
                    LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()),
                    flowQuantity(flow),
                    flowValueUsd(flow, prices, historicalPrices, timestamp),
                    feeUsd,
                    feeQuantityByAsset
            );
        }

        private String displayType(String type) {
            if (type == null) {
                return "Unknown";
            }
            try {
                return displayType(NormalizedTransactionType.valueOf(type), null);
            } catch (IllegalArgumentException ignored) {
                return type;
            }
        }

        private String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first;
        }

        private String stringValue(Object value) {
            return value == null ? null : value.toString();
        }

        private BigDecimal decimalValue(Object value) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            if (value == null || value.toString().isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private BigDecimal flowQuantity(NormalizedTransaction.Flow flow) {
            return zeroIfNull(flow.getQuantityDelta()).abs();
        }

        private BigDecimal flowValueUsd(
                NormalizedTransaction.Flow flow,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
                Instant timestamp
        ) {
            BigDecimal quantity = flowQuantity(flow);
            return flow.getValueUsd() != null
                    ? flow.getValueUsd().abs()
                    : quantity.multiply(resolvePrice(
                            prices,
                            historicalPrices,
                            flow.getAssetSymbol(),
                            timestamp
                    ), MC);
        }

        private Map<String, BigDecimal> feeQuantityByAsset(NormalizedTransaction transaction) {
            Map<String, BigDecimal> fees = new LinkedHashMap<>();
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                if (flow.getRole() != NormalizedLegRole.FEE || flow.getQuantityDelta() == null) {
                    continue;
                }
                String asset = cycleStateAsset(flow.getAssetSymbol());
                if (asset.isBlank()) {
                    continue;
                }
                fees.merge(asset, flow.getQuantityDelta().abs(), (left, right) -> left.add(right, MC));
            }
            return Map.copyOf(fees);
        }

        private Optional<NormalizedTransaction.Flow> preferredHistoryFlow(NormalizedTransaction transaction) {
            if (transaction.getType() == null) {
                return Optional.empty();
            }
            List<NormalizedTransaction.Flow> nonFeeFlows = safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                    .filter(flow -> flow.getQuantityDelta() != null)
                    .toList();
            if (transaction.getType() == NormalizedTransactionType.REPAY
                    && "REPAY_WITH_ATOKENS".equals(eventSubtype(transaction))) {
                return nonFeeFlows.stream()
                        .filter(flow -> flow.getQuantityDelta().signum() < 0)
                        .filter(flow -> LendingAssetSymbolSupport.isBorrowSymbol(flow.getAssetSymbol()))
                        .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            }
            if (transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                    || transaction.getType() == NormalizedTransactionType.VAULT_DEPOSIT
                    || transaction.getType() == NormalizedTransactionType.REPAY) {
                return largestSpotFlow(nonFeeFlows, -1);
            }
            if (transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                    || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                    || transaction.getType() == NormalizedTransactionType.BORROW
                    || transaction.getType() == NormalizedTransactionType.REWARD_CLAIM) {
                return largestSpotFlow(nonFeeFlows, 1);
            }
            return Optional.empty();
        }

        private Optional<NormalizedTransaction.Flow> largestSpotFlow(List<NormalizedTransaction.Flow> flows, int signum) {
            return flows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() == signum)
                    .filter(flow -> !LendingAssetSymbolSupport.isLendingPositionSymbol(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
        }

        private String eventSubtype(NormalizedTransaction transaction) {
            if (transaction == null || transaction.getType() != NormalizedTransactionType.REPAY) {
                return transaction == null ? null : transaction.getEventSubtype();
            }
            if (transaction.getEventSubtype() != null && !transaction.getEventSubtype().isBlank()) {
                return transaction.getEventSubtype();
            }
            boolean debtBurn = false;
            boolean receiptBurn = false;
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
                    continue;
                }
                if (LendingAssetSymbolSupport.isBorrowSymbol(flow.getAssetSymbol())) {
                    debtBurn = true;
                } else if (isAaveReceiptSymbol(flow.getAssetSymbol())) {
                    receiptBurn = true;
                }
            }
            return debtBurn && receiptBurn ? "REPAY_WITH_ATOKENS" : null;
        }

        private boolean isAaveReceiptSymbol(String assetSymbol) {
            if (assetSymbol == null || assetSymbol.isBlank()) {
                return false;
            }
            String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
            return normalized.startsWith("A") && !LendingAssetSymbolSupport.isBorrowSymbol(normalized);
        }

        private BigDecimal positiveBuyValue(NormalizedTransaction transaction) {
            return safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.BUY)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
        }

        private String displayType(NormalizedTransactionType type, String eventSubtype) {
            if (type == null) {
                return "Unknown";
            }
            if (type == NormalizedTransactionType.REPAY && "REPAY_WITH_ATOKENS".equals(eventSubtype)) {
                return "Repay with aTokens";
            }
            return switch (type) {
                case LENDING_DEPOSIT -> "Deposit";
                case LENDING_WITHDRAW -> "Withdraw";
                case VAULT_DEPOSIT -> "Deposit";
                case VAULT_WITHDRAW -> "Withdraw";
                case BORROW -> "Borrow";
                case REPAY -> "Repay";
                case REWARD_CLAIM -> "Reward";
                case LENDING_LOOP_OPEN -> "Loop open";
                case LENDING_LOOP_REBALANCE -> "Loop rebalance";
                case LENDING_LOOP_DECREASE -> "Loop decrease";
                case LENDING_LOOP_CLOSE -> "Loop close";
                default -> type.name();
            };
        }

        private String loopId(NormalizedTransaction transaction) {
            if (transaction.getType() == null || !transaction.getType().name().startsWith("LENDING_LOOP")) {
                return null;
            }
            if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
                return transaction.getCorrelationId();
            }
            return "loop:" + key.id() + ":" + transaction.getBlockTimestamp();
        }
    }

    private record HistoryAmount(
            String syntheticIndex,
            String type,
            String eventSubtype,
            String displayType,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            BigDecimal feeUsd,
            Map<String, BigDecimal> feeQuantityByAsset
    ) {
    }

    private record CyclePeakView(BigDecimal peakSupplyUsd, BigDecimal peakBorrowUsd) {
    }

    private static final class MutableCycle {
        private final String id;
        private final List<LendingHistoryEntryView> events = new ArrayList<>();
        private final DeltaAccumulator deltas = new DeltaAccumulator();
        private final CycleState state = new CycleState();
        private Instant lastTimestamp;

        private MutableCycle(String id) {
            this.id = id;
        }

        private void add(LendingHistoryEntryView event) {
            events.add(event);
            deltas.add(event);
            state.add(event);
            lastTimestamp = event.blockTimestamp();
        }

        private String id() {
            return id;
        }

        private List<LendingHistoryEntryView> events() {
            return events;
        }

        private DeltaAccumulator deltas() {
            return deltas;
        }

        private CycleState state() {
            return state;
        }

        private Instant lastTimestamp() {
            return lastTimestamp;
        }

        private Optional<LendingHistoryEntryView> lastEvent() {
            return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
        }

        private boolean isFlat() {
            return state.isFlat();
        }
    }

    private static final class DeltaAccumulator {
        private final Map<String, BigDecimal> principalInByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> principalOutByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> principalOutCashByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> internalReceiptMovementByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> borrowedByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> repaidByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> withdrawnByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> rewardByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> feesByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> gasByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> netCashDeltaByAsset = new LinkedHashMap<>();
        private final Map<String, List<LendingObservedFlowView>> observedFlowsByAsset = new LinkedHashMap<>();
        private boolean hasShareOrVaultAsset;
        private boolean hasFluidLogOperateEvidence;
        private boolean hasWstUsrExposure;
        private boolean hasMissingEventValuation;
        private boolean hasMissingGasUsdValuation;
        private BigDecimal principalInUsd = BigDecimal.ZERO;
        private BigDecimal principalOutUsd = BigDecimal.ZERO;
        private BigDecimal principalOutCashUsd = BigDecimal.ZERO;
        private BigDecimal borrowedUsd = BigDecimal.ZERO;
        private BigDecimal repaidUsd = BigDecimal.ZERO;
        private BigDecimal rewardUsd = BigDecimal.ZERO;
        private BigDecimal feesUsd = BigDecimal.ZERO;
        private final Map<String, Set<String>> fluidRepayEvidenceSourcesByKey = new LinkedHashMap<>();

        private void add(LendingHistoryEntryView event) {
            String rawAsset = LendingAssetSymbolSupport.displaySymbol(event.assetSymbol());
            String asset = cycleStateAsset(rawAsset);
            if ("WSTUSR".equalsIgnoreCase(rawAsset)) {
                hasWstUsrExposure = true;
            }
            if (event.eventSubtype() != null && event.eventSubtype().contains("FLUID_LOG_OPERATE")) {
                hasFluidLogOperateEvidence = true;
            }
            if (LendingAssetSymbolSupport.isLendingPositionSymbol(rawAsset)
                    && !LendingAssetSymbolSupport.isBorrowSymbol(rawAsset)) {
                hasShareOrVaultAsset = true;
            }
            BigDecimal quantity = event.quantity() == null ? BigDecimal.ZERO : event.quantity().abs();
            if (quantity.signum() == 0) {
                return;
            }
            boolean aggregateRepay = !"REPAY".equals(event.type())
                    || !isDuplicateFluidRepayEvidence(event, asset, quantity);
            BigDecimal valueUsd = zeroIfNull(event.valueUsd());
            feesUsd = feesUsd.add(zeroIfNull(event.feeUsd()), MC);
            if (valueUsd.signum() == 0 && isValuableEconomicEvent(event)) {
                hasMissingEventValuation = true;
            }
            if (event.feeUsd() != null && event.feeUsd().signum() > 0) {
                add(feesByAsset, "USD", event.feeUsd());
            }
            for (Map.Entry<String, BigDecimal> feeEntry : event.feeQuantityByAsset().entrySet()) {
                if (feeEntry.getValue() != null && feeEntry.getValue().signum() > 0) {
                    add(gasByAsset, feeEntry.getKey(), feeEntry.getValue());
                    if (event.feeUsd() == null || event.feeUsd().signum() <= 0) {
                        hasMissingGasUsdValuation = true;
                    }
                }
            }
            switch (event.type()) {
                case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN" -> {
                    add(principalInByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity.negate());
                    addObservedFlow(event, asset, quantity.negate());
                    principalInUsd = principalInUsd.add(valueUsd, MC);
                }
                case "BORROW" -> {
                    add(borrowedByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    borrowedUsd = borrowedUsd.add(valueUsd, MC);
                }
                case "REPAY" -> {
                    if (aggregateRepay) {
                        addObservedFlow(event, asset, quantity.negate());
                        add(repaidByAsset, asset, quantity);
                        add(netCashDeltaByAsset, asset, quantity.negate());
                        repaidUsd = repaidUsd.add(valueUsd, MC);
                    }
                }
                case "LENDING_WITHDRAW", "VAULT_WITHDRAW", "LENDING_LOOP_CLOSE", "LENDING_LOOP_DECREASE" -> {
                    add(withdrawnByAsset, asset, quantity);
                    add(principalOutByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    principalOutUsd = principalOutUsd.add(valueUsd, MC);
                    if (isInternalReceiptMovement(event)) {
                        add(internalReceiptMovementByAsset, asset, quantity);
                    } else {
                        add(principalOutCashByAsset, asset, quantity);
                        principalOutCashUsd = principalOutCashUsd.add(valueUsd, MC);
                    }
                }
                case "LENDING_CASH_EXIT" -> {
                    add(principalOutCashByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    principalOutCashUsd = principalOutCashUsd.add(valueUsd, MC);
                }
                case "REWARD_CLAIM" -> {
                    add(rewardByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    rewardUsd = rewardUsd.add(valueUsd, MC);
                }
                default -> {
                    // Keep unknown lifecycle shapes visible through event rows.
                }
            }
        }

        private boolean isValuableEconomicEvent(LendingHistoryEntryView event) {
            return switch (event.type()) {
                case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN",
                     "BORROW", "REPAY", "LENDING_WITHDRAW", "VAULT_WITHDRAW",
                     "LENDING_LOOP_CLOSE", "LENDING_LOOP_DECREASE", "REWARD_CLAIM" -> true;
                default -> false;
            };
        }

        private LendingAssetDeltasView toView() {
            return new LendingAssetDeltasView(
                    Map.copyOf(principalInByAsset),
                    Map.copyOf(principalOutByAsset),
                    Map.copyOf(principalOutCashByAsset),
                    Map.copyOf(internalReceiptMovementByAsset),
                    Map.copyOf(borrowedByAsset),
                    Map.copyOf(repaidByAsset),
                    Map.copyOf(withdrawnByAsset),
                    Map.copyOf(rewardByAsset),
                    Map.copyOf(feesByAsset),
                    Map.copyOf(netCashDeltaByAsset)
            );
        }

        private void add(Map<String, BigDecimal> target, String asset, BigDecimal quantity) {
            target.merge(asset, quantity, (left, right) -> left.add(right, MC));
        }

        private void addObservedFlow(LendingHistoryEntryView event, String asset, BigDecimal signedQuantity) {
            if (event == null || signedQuantity == null || signedQuantity.signum() == 0) {
                return;
            }
            observedFlowsByAsset.computeIfAbsent(asset, ignored -> new ArrayList<>()).add(new LendingObservedFlowView(
                    event.assetSymbol(),
                    null,
                    signedQuantity,
                    event.txHash(),
                    observedSourceKind(event),
                    false,
                    null
            ));
        }

        private String observedSourceKind(LendingHistoryEntryView event) {
            if (event.eventSubtype() != null && event.eventSubtype().contains("LOG_OPERATE")) {
                return "DECODED_PROTOCOL_EVENT";
            }
            if (event.eventSubtype() != null && event.eventSubtype().contains("RECEIPT")) {
                return "RECEIPT_LOG";
            }
            return "WALLET_VISIBLE_TRANSFER";
        }

        private boolean isInternalReceiptMovement(LendingHistoryEntryView event) {
            if (event == null) {
                return false;
            }
            String symbol = event.assetSymbol();
            if (LendingAssetSymbolSupport.isLendingPositionSymbol(symbol)) {
                return true;
            }
            String subtype = event.eventSubtype() == null
                    ? ""
                    : event.eventSubtype().toUpperCase(Locale.ROOT);
            return subtype.contains("INTERNAL_RECEIPT")
                    || subtype.contains("INTERNAL_SHARE")
                    || subtype.contains("EVK_ACCOUNT")
                    || subtype.contains("EVK_SHARE");
        }

        private boolean isDuplicateFluidRepayEvidence(
                LendingHistoryEntryView event,
                String asset,
                BigDecimal quantity
        ) {
            String source = fluidRepayEvidenceSource(event);
            if (source == null) {
                return false;
            }
            String key = String.join("|",
                    nullToEmpty(event.txHash()),
                    nullToEmpty(event.marketKey()),
                    nullToEmpty(asset),
                    quantity.stripTrailingZeros().toPlainString()
            );
            Set<String> seenSources = fluidRepayEvidenceSourcesByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            boolean hasComplement = ("WALLET".equals(source) && seenSources.contains("LOG_OPERATE"))
                    || ("LOG_OPERATE".equals(source) && seenSources.contains("WALLET"));
            seenSources.add(source);
            return hasComplement;
        }

        private String fluidRepayEvidenceSource(LendingHistoryEntryView event) {
            String subtype = event == null || event.eventSubtype() == null
                    ? ""
                    : event.eventSubtype().toUpperCase(Locale.ROOT);
            if (subtype.contains("FLUID_LOG_OPERATE_REPAY")) {
                return "LOG_OPERATE";
            }
            if (subtype.contains("FLUID_WALLET_VISIBLE_REPAY")) {
                return "WALLET";
            }
            return null;
        }

        private Map<String, List<LendingObservedFlowView>> observedFlowsByAsset(String unavailableReason) {
            Map<String, List<LendingObservedFlowView>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<LendingObservedFlowView>> entry : observedFlowsByAsset.entrySet()) {
                result.put(entry.getKey(), entry.getValue().stream()
                        .map(flow -> new LendingObservedFlowView(
                                flow.assetSymbol(),
                                flow.assetContract(),
                                flow.quantity(),
                                flow.sourceTxHash(),
                                flow.sourceKind(),
                                false,
                                unavailableReason
                        ))
                        .toList());
            }
            return result;
        }

        private boolean hasAny(Map<String, BigDecimal> source) {
            return source.values().stream().anyMatch(value -> value != null && value.signum() > 0);
        }

        private boolean hasFluidLogOperateEvidence() {
            return hasFluidLogOperateEvidence;
        }

        private boolean hasWstUsrExposure() {
            return hasWstUsrExposure;
        }

        private boolean hasMissingEventValuation() {
            return hasMissingEventValuation;
        }

        private boolean hasMissingGasUsdValuation() {
            return hasMissingGasUsdValuation;
        }

        private boolean hasIncompletePrincipalExit() {
            return hasUnresolvedPrincipalExitByAsset();
        }

        private Map<String, BigDecimal> principalInByAsset() {
            return canonicalize(principalInByAsset);
        }

        private Map<String, BigDecimal> principalOutByAsset() {
            return canonicalize(principalOutByAsset);
        }

        private Map<String, BigDecimal> principalOutCashByAsset() {
            return canonicalize(principalOutCashByAsset);
        }

        private Map<String, BigDecimal> internalReceiptMovementByAsset() {
            return canonicalize(internalReceiptMovementByAsset);
        }

        private Map<String, BigDecimal> borrowedByAsset() {
            return canonicalize(borrowedByAsset);
        }

        private Map<String, BigDecimal> repaidByAsset() {
            return canonicalize(repaidByAsset);
        }

        private Map<String, BigDecimal> rewardByAsset() {
            return canonicalize(rewardByAsset);
        }

        private Map<String, BigDecimal> gasByAsset() {
            return canonicalize(gasByAsset);
        }

        private BigDecimal principalInUsd() {
            return principalInUsd;
        }

        private BigDecimal principalOutUsd() {
            return principalOutUsd;
        }

        private BigDecimal principalOutCashUsd() {
            return principalOutCashUsd;
        }

        private BigDecimal borrowedUsd() {
            return borrowedUsd;
        }

        private BigDecimal repaidUsd() {
            return repaidUsd;
        }

        private BigDecimal rewardUsd() {
            return rewardUsd;
        }

        private BigDecimal feesUsd() {
            return feesUsd;
        }

        private boolean hasUnresolvedPrincipalExitByAsset() {
            return !incompletePrincipalExitAssets().isEmpty();
        }

        private Set<String> incompletePrincipalExitAssets() {
            Map<String, BigDecimal> suppliedByLifecycleAsset = principalInByAsset();
            Map<String, BigDecimal> exitedByLifecycleAsset = principalOutByAsset();
            LinkedHashSet<String> assets = new LinkedHashSet<>();
            for (Map.Entry<String, BigDecimal> entry : suppliedByLifecycleAsset.entrySet()) {
                BigDecimal supplied = entry.getValue();
                if (supplied == null || supplied.signum() <= 0) {
                    continue;
                }
                BigDecimal exited = exitedByLifecycleAsset.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (exited.compareTo(CYCLE_DUST_TOLERANCE) <= 0) {
                    assets.add(entry.getKey());
                }
            }
            return assets;
        }

        private boolean hasNonStablePrincipalExposure() {
            return principalAssets().stream()
                    .anyMatch(asset -> !LendingAssetSymbolSupport.isStable(asset));
        }

        private boolean hasShareOrVaultAsset() {
            return hasShareOrVaultAsset;
        }

        private boolean hasInternalReceiptMovement() {
            return hasAny(internalReceiptMovementByAsset);
        }

        private boolean hasWrapperOrShareExposure() {
            return hasShareOrVaultAsset || hasWstUsrExposure;
        }

        private Set<String> principalAssets() {
            LinkedHashSet<String> assets = new LinkedHashSet<>();
            assets.addAll(canonicalize(principalInByAsset).keySet());
            assets.addAll(canonicalize(principalOutByAsset).keySet());
            assets.addAll(canonicalize(withdrawnByAsset).keySet());
            return assets;
        }

        private Map<String, BigDecimal> canonicalize(Map<String, BigDecimal> source) {
            Map<String, BigDecimal> canonical = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String asset = cycleStateAsset(entry.getKey());
                canonical.merge(asset, entry.getValue(), (left, right) -> left.add(right, MC));
            }
            return canonical;
        }

        private Set<String> allAssets() {
            LinkedHashSet<String> assets = new LinkedHashSet<>();
            assets.addAll(principalInByAsset.keySet());
            assets.addAll(principalOutByAsset.keySet());
            assets.addAll(borrowedByAsset.keySet());
            assets.addAll(repaidByAsset.keySet());
            assets.addAll(withdrawnByAsset.keySet());
            assets.addAll(rewardByAsset.keySet());
            assets.addAll(feesByAsset.keySet());
            assets.addAll(gasByAsset.keySet());
            assets.addAll(netCashDeltaByAsset.keySet());
            return assets;
        }
    }

    private static final class CycleState {
        private final Map<String, BigDecimal> supplyByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> debtByAsset = new LinkedHashMap<>();

        private void add(LendingHistoryEntryView event) {
            String asset = cycleStateAsset(event.assetSymbol());
            BigDecimal quantity = event.quantity() == null ? BigDecimal.ZERO : event.quantity().abs();
            if (quantity.signum() == 0) {
                return;
            }
            switch (event.type()) {
                case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN" -> add(supplyByAsset, asset, quantity);
                case "LENDING_WITHDRAW", "VAULT_WITHDRAW", "LENDING_LOOP_DECREASE", "LENDING_LOOP_CLOSE" ->
                        subtract(supplyByAsset, asset, quantity);
                case "BORROW" -> add(debtByAsset, asset, quantity);
                case "REPAY" -> subtract(debtByAsset, asset, quantity);
                default -> {
                    // Rewards and display-only rows do not change lifecycle open/close state.
                }
            }
        }

        private boolean isFlat() {
            return hasNoPositiveRemainder(supplyByAsset) && hasNoPositiveRemainder(debtByAsset);
        }

        private BigDecimal supplyAmount(String asset) {
            return supplyByAsset.getOrDefault(asset, BigDecimal.ZERO);
        }

        private boolean hasPositiveSupply(String asset) {
            return supplyByAsset.getOrDefault(asset, BigDecimal.ZERO).compareTo(CYCLE_DUST_TOLERANCE) > 0;
        }

        private boolean hasPositiveDebt(String asset) {
            return debtByAsset.getOrDefault(asset, BigDecimal.ZERO).compareTo(CYCLE_DUST_TOLERANCE) > 0;
        }

        private boolean hasAnySupply() {
            return hasPositiveRemainder(supplyByAsset);
        }

        private boolean hasAnyDebt() {
            return hasPositiveRemainder(debtByAsset);
        }

        private void add(Map<String, BigDecimal> target, String asset, BigDecimal quantity) {
            target.merge(asset, quantity, (left, right) -> left.add(right, MC));
        }

        private void subtract(Map<String, BigDecimal> target, String asset, BigDecimal quantity) {
            target.merge(asset, quantity.negate(), (left, right) -> left.add(right, MC));
        }

        private boolean hasNoPositiveRemainder(Map<String, BigDecimal> source) {
            return source.values().stream()
                    .filter(Objects::nonNull)
                    .allMatch(value -> value.compareTo(CYCLE_DUST_TOLERANCE) <= 0);
        }

        private boolean hasPositiveRemainder(Map<String, BigDecimal> source) {
            return source.values().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(value -> value.compareTo(CYCLE_DUST_TOLERANCE) > 0);
        }
    }

    private static String cycleStateAsset(String assetSymbol) {
        return LendingAssetSymbolSupport.displaySymbol(LendingAssetSymbolSupport.lifecycleAsset(assetSymbol));
    }
}
