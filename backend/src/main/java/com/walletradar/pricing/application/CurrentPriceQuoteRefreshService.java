package com.walletradar.pricing.application;

import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
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

    public int refreshForSessionBalances(String sessionId, Instant requestedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Instant refreshTime = requestedAt == null ? Instant.now() : requestedAt;
        Set<BalanceAsset> balanceAssets = loadCanonicalAssets(sessionId);
        int refreshed = refreshProtocolSymbols(sessionId, balanceAssets, refreshTime);
        Set<String> symbols = canonicalMarketSymbols(balanceAssets);
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
