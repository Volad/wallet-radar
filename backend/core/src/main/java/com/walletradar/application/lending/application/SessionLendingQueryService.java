package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.application.lending.view.LendingGroupView;
import com.walletradar.application.lending.view.LendingSummaryView;
import com.walletradar.application.lending.view.SessionLendingView;
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
import java.time.temporal.ChronoUnit;
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
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionLendingQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Duration HISTORICAL_STABLE_PRICE_WINDOW = Duration.ofHours(36);
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
    private final LendingCycleBuilder cycleBuilder;
    private final Cache<String, SessionLendingView> sessionLendingCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(45, TimeUnit.SECONDS)
            .build();

    public Optional<SessionLendingView> findSessionLending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalizedSessionId = sessionId.trim();
        SessionLendingView cached = sessionLendingCache.getIfPresent(normalizedSessionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SessionLendingView> loaded = userSessionRepository.findById(normalizedSessionId).map(this::toView);
        loaded.ifPresent(view -> sessionLendingCache.put(normalizedSessionId, view));
        return loaded;
    }

    public boolean ownsGroupId(String sessionId, String groupId) {
        if (sessionId == null || sessionId.isBlank() || groupId == null || groupId.isBlank()) {
            return false;
        }
        String normalizedGroupId = groupId.trim().toLowerCase(Locale.ROOT);
        return findSessionLending(sessionId)
                .map(view -> view.groups().stream().anyMatch(group -> normalizedGroupId.equals(group.id())))
                .orElse(false);
    }

    private SessionLendingView toView(UserSession session) {
        List<String> walletAddresses = walletAddresses(session);
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<NormalizedTransaction> history = loadHistory(walletAddresses);
        List<NormalizedTransaction> cashExitSwaps = loadCashExitSwaps(walletAddresses, history);
        List<AssetLedgerPoint> ledgerPoints = loadLedgerPoints(universeScope.accountingUniverseId());
        List<OnChainBalance> balances = loadBalances(session.getId(), walletAddresses);
        Map<String, BigDecimal> prices = loadCurrentPrices(priceCandidates(balances, history));
        Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices = loadHistoricalPrices(history);

        List<LendingGroupView> groupViews = cycleBuilder.buildGroupViews(
                session.getId(),
                history,
                cashExitSwaps,
                ledgerPoints,
                balances,
                prices,
                historicalPrices
        );

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
                .filter(cycleBuilder::isLendingHistoryRow)
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

    private static List<NormalizedTransaction.Flow> safeFlows(NormalizedTransaction transaction) {
        return transaction.getFlows() == null ? List.of() : transaction.getFlows();
    }
}
