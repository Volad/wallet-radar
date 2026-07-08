package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Refreshes datastore-backed current quote snapshots outside dashboard GET requests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrentPriceQuoteRefreshService {

    private static final int QUOTE_REFRESH_LANES = 4;
    private static final Duration FRESH_QUOTE_TTL = Duration.ofMinutes(15);

    private final MongoOperations mongoOperations;
    private final PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;
    private final GmxProtocolSnapshotValuationService gmxProtocolSnapshotValuationService;
    private final UserSessionRepository userSessionRepository;

    /**
     * Lightweight refresh targeted at a known set of market symbols (e.g. LP position tokens).
     * Skips protocol-level or session-wide discovery — use when only a small, explicit set of
     * symbols needs a fresh quote (e.g. on-demand LP TVL recomputation).
     */
    public int refreshForSymbols(String sessionId, Set<String> symbols) {
        if (sessionId == null || sessionId.isBlank() || symbols == null || symbols.isEmpty()) {
            return 0;
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String sym : symbols) {
            if (sym == null || sym.isBlank()) continue;
            canonical.add(sym.toUpperCase(Locale.ROOT));
            String canon = CanonicalAssetCatalog.canonicalMarketSymbol(sym);
            if (!canon.isBlank()) canonical.add(canon);
        }
        return refreshSymbols(sessionId, canonical, Instant.now());
    }

    public int refreshForSessionBalances(String sessionId, Instant requestedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Instant refreshTime = requestedAt == null ? Instant.now() : requestedAt;
        Set<BalanceAsset> balanceAssets = loadCanonicalAssets(sessionId);
        int refreshed = refreshProtocolSymbols(sessionId, balanceAssets, refreshTime);
        Set<String> symbols = canonicalMarketSymbols(balanceAssets);
        // CEX-only holdings (assets that live solely on a Bybit umbrella and never appear in
        // on_chain_balances, e.g. ONDO/LINK/LDO/DOGE/LTC/XRP) otherwise never get a fresh current
        // quote and fall back to a stale "last trade" historical price — overstating the dashboard
        // CEX value. Include the session's live Bybit umbrella symbols in the refresh set.
        symbols.addAll(loadBybitLiveSymbols(sessionId));
        // LP reward tokens (e.g. CAKE from PancakeSwap MasterChef) appear only as pending
        // unclaimed fees in LP snapshots, never as held balances — add them explicitly.
        symbols.addAll(loadLpRewardTokenSymbols(sessionId));
        refreshed += refreshSymbols(sessionId, symbols, refreshTime);
        log.info(
                "Current quote refresh complete: sessionId={}, symbols={}, protocolAssets={}, refreshed={}",
                sessionId,
                symbols.size(),
                balanceAssets.stream().filter(BalanceAsset::isGmxProtocolPosition).count(),
                refreshed
        );
        return refreshed;
    }

    /**
     * Canonical market symbols for assets held on the session's Bybit umbrella accounts. These are
     * sourced from {@code bybit_live_balances} (positive umbrella quantity) so CEX-only positions
     * receive a fresh current quote instead of a stale last-trade historical price.
     */
    private Set<String> loadBybitLiveSymbols(String sessionId) {
        Set<String> symbols = new LinkedHashSet<>();
        Set<String> integrationIds = userSessionRepository.findById(sessionId)
                .map(this::bybitIntegrationIds)
                .orElse(Set.of());
        if (integrationIds.isEmpty()) {
            return symbols;
        }
        Query query = Query.query(Criteria.where("integrationId").in(integrationIds));
        List<Document> balances = mongoOperations.find(query, Document.class, "bybit_live_balances");
        if (balances == null) {
            return symbols;
        }
        for (Document balance : balances) {
            BigDecimal umbrellaQty = readDecimal(balance.get("umbrellaQty"));
            if (umbrellaQty == null || umbrellaQty.signum() <= 0) {
                continue;
            }
            String symbol = CanonicalAssetCatalog.canonicalMarketSymbol(balance.getString("assetSymbol"));
            if (symbol != null && !symbol.isBlank()) {
                symbols.add(symbol.trim().toUpperCase(Locale.ROOT));
            }
        }
        return symbols;
    }

    /**
     * Symbols of reward tokens that appear only as unclaimed fees in LP position snapshots
     * (e.g. CAKE from PancakeSwap MasterChef) and are never tracked as balance assets.
     * Queries open LP snapshots for the session's accounting universe.
     */
    private Set<String> loadLpRewardTokenSymbols(String sessionId) {
        Set<String> symbols = new LinkedHashSet<>();
        Optional<UserSession> session = userSessionRepository.findById(sessionId);
        if (session.isEmpty() || session.get().getAccountingUniverseId() == null) {
            return symbols;
        }
        String universeId = session.get().getAccountingUniverseId();
        Query q = new Query(Criteria.where("universeId").is(universeId)
                .and("status").ne("closed")
                .and("unclaimedFeesByToken").exists(true));
        q.fields().include("unclaimedFeesByToken");
        List<Document> snapshots = mongoOperations.find(q, Document.class, "lp_position_snapshots");
        for (Document snap : snapshots) {
            Document fees = snap.get("unclaimedFeesByToken", Document.class);
            if (fees == null) continue;
            for (String sym : fees.keySet()) {
                if (sym != null && !sym.isBlank()) {
                    symbols.add(sym.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return symbols;
    }

    private Set<String> bybitIntegrationIds(UserSession session) {
        Set<String> ids = new LinkedHashSet<>();
        if (session == null || session.getIntegrations() == null) {
            return ids;
        }
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null
                    || integration.getIntegrationId() == null
                    || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                    || integration.getAccountRef() == null
                    || !integration.getAccountRef().toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
                continue;
            }
            ids.add(integration.getIntegrationId());
        }
        return ids;
    }

    private int refreshProtocolSymbols(String sessionId, Set<BalanceAsset> balanceAssets, Instant refreshTime) {
        if (balanceAssets == null || balanceAssets.isEmpty()) {
            return 0;
        }
        int refreshed = 0;
        for (BalanceAsset balanceAsset : balanceAssets) {
            if (!balanceAsset.isGmxProtocolPosition()) {
                continue;
            }
            if (hasFreshCurrentQuote(balanceAsset.symbol(), PriceSource.PROTOCOL_SNAPSHOT, refreshTime)) {
                refreshed++;
                continue;
            }
            Optional<PriceQuote> quote = gmxProtocolSnapshotValuationService.resolveMarketTokenQuote(
                    balanceAsset.networkId(),
                    balanceAsset.assetContract(),
                    balanceAsset.symbol(),
                    refreshTime
            );
            if (quote.isEmpty()) {
                log.debug(
                        "GMX protocol quote unresolved: sessionId={}, networkId={}, symbol={}, assetContract={}",
                        sessionId,
                        balanceAsset.networkId(),
                        balanceAsset.symbol(),
                        balanceAsset.assetContract()
                );
                continue;
            }
            upsert(balanceAsset.symbol(), quote.orElseThrow(), refreshTime);
            refreshed++;
        }
        return refreshed;
    }

    private int refreshSymbols(String sessionId, Set<String> symbols, Instant refreshTime) {
        if (symbols == null || symbols.isEmpty()) {
            return 0;
        }
        List<Callable<Boolean>> tasks = symbols.stream()
                .map(symbol -> (Callable<Boolean>) () -> refreshSymbol(sessionId, symbol, refreshTime))
                .toList();
        int lanes = Math.max(1, Math.min(QUOTE_REFRESH_LANES, tasks.size()));
        ExecutorService executor = Executors.newFixedThreadPool(lanes);
        try {
            List<Future<Boolean>> futures = executor.invokeAll(tasks);
            int refreshed = 0;
            for (Future<Boolean> future : futures) {
                try {
                    if (Boolean.TRUE.equals(future.get())) {
                        refreshed++;
                    }
                } catch (ExecutionException error) {
                    log.warn("Current quote refresh task failed", error);
                }
            }
            return refreshed;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Current quote refresh interrupted", error);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean refreshSymbol(String sessionId, String symbol, Instant refreshTime) {
        if (CanonicalAssetCatalog.isUsdStablecoin(null, null, symbol, null)) {
            upsert(symbol, new PriceQuote(
                    BigDecimal.ONE,
                    PriceSource.STABLECOIN,
                    refreshTime,
                    "USD",
                    "stablecoin-policy"
            ), refreshTime);
            return true;
        }
        if (!isMarketQuoteEligible(symbol)) {
            log.debug("Current quote skipped for non-market symbol: sessionId={}, symbol={}", sessionId, symbol);
            return false;
        }
        if (hasFreshCurrentQuote(symbol, refreshTime)) {
            return true;
        }
        PriceRequest request = new PriceRequest(
                "current:" + symbol,
                NormalizedTransactionSource.ON_CHAIN,
                null,
                null,
                symbol,
                refreshTime
        );
        Optional<PriceQuote> quote = priceExternalSourceOrchestrator.resolveExternalOnly(request);
        if (quote.isEmpty()) {
            log.debug("Current quote unresolved: sessionId={}, symbol={}", sessionId, symbol);
            return false;
        }
        upsert(symbol, quote.orElseThrow(), refreshTime);
        return true;
    }

    private Set<BalanceAsset> loadCanonicalAssets(String sessionId) {
        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        List<Document> balances = mongoOperations.find(query, Document.class, "on_chain_balances");
        Set<BalanceAsset> assets = new LinkedHashSet<>();
        if (balances == null) {
            return assets;
        }
        for (Document balance : balances) {
            BigDecimal quantity = readQuantity(balance);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = CanonicalAssetCatalog.canonicalMarketSymbol(balance.getString("assetSymbol"));
            if (symbol != null && !symbol.isBlank()) {
                assets.add(new BalanceAsset(
                        symbol.trim().toUpperCase(Locale.ROOT),
                        readNetworkId(balance),
                        balance.getString("assetContract")
                ));
            }
        }
        return assets;
    }

    private Set<String> canonicalMarketSymbols(Set<BalanceAsset> balanceAssets) {
        Set<String> symbols = new LinkedHashSet<>();
        if (balanceAssets == null) {
            return symbols;
        }
        for (BalanceAsset balanceAsset : balanceAssets) {
            if (balanceAsset == null || balanceAsset.symbol() == null || balanceAsset.symbol().isBlank()) {
                continue;
            }
            symbols.add(balanceAsset.symbol().trim().toUpperCase(Locale.ROOT));
        }
        return symbols;
    }

    private NetworkId readNetworkId(Document balance) {
        if (balance == null) {
            return null;
        }
        Object value = balance.get("networkId");
        if (value instanceof NetworkId networkId) {
            return networkId;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return NetworkId.valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isMarketQuoteEligible(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return !normalized.startsWith("GM:")
                && !normalized.startsWith("GLV:")
                && !normalized.contains("/")
                && !normalized.contains("[")
                && !normalized.contains("]")
                && !normalized.contains(":")
                && !normalized.contains(" ");
    }

    private boolean hasFreshCurrentQuote(String symbol, Instant refreshTime) {
        return hasFreshCurrentQuote(symbol, null, refreshTime);
    }

    private boolean hasFreshCurrentQuote(String symbol, PriceSource source, Instant refreshTime) {
        if (symbol == null || symbol.isBlank() || refreshTime == null) {
            return false;
        }
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("symbol").is(symbol.trim().toUpperCase(Locale.ROOT)),
                Criteria.where("fetchedAt").gte(refreshTime.minus(FRESH_QUOTE_TTL))
        );
        if (source != null) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("source").is(source));
        }
        Query query = Query.query(criteria);
        query.limit(1);
        return mongoOperations.exists(query, CurrentPriceQuoteDocument.class);
    }

    private BigDecimal readDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Decimal128 decimal128) {
            return decimal128.bigDecimalValue();
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal readQuantity(Document balance) {
        if (balance == null) {
            return null;
        }
        Object value = balance.get("quantity");
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Decimal128 decimal128) {
            return decimal128.bigDecimalValue();
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void upsert(String symbol, PriceQuote quote, Instant fetchedAt) {
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        String id = CurrentPriceQuoteDocument.composeId(normalizedSymbol, quote.source());
        Update update = new Update()
                .set("symbol", normalizedSymbol)
                .set("source", quote.source())
                .set("priceUsd", Decimal128Support.normalize(quote.unitPriceUsd()))
                .set("quoteSymbol", quote.quoteSymbol())
                .set("pricedAt", quote.pricedAt())
                .set("fetchedAt", fetchedAt)
                .set("sourceReference", quote.sourceReference());
        mongoOperations.upsert(
                Query.query(Criteria.where("_id").is(id)),
                update,
                CurrentPriceQuoteDocument.class
        );
    }

    private record BalanceAsset(
            String symbol,
            NetworkId networkId,
            String assetContract
    ) {
        private boolean isGmxProtocolPosition() {
            if (symbol == null || symbol.isBlank()) {
                return false;
            }
            String normalized = symbol.trim().toUpperCase(Locale.ROOT);
            return normalized.startsWith("GM:") || normalized.startsWith("GLV");
        }
    }
}
