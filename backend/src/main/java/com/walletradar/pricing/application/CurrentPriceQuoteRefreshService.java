package com.walletradar.pricing.application;

import com.walletradar.domain.common.Decimal128Support;
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
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Refreshes datastore-backed current quote snapshots outside dashboard GET requests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrentPriceQuoteRefreshService {

    private final MongoOperations mongoOperations;
    private final PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;

    public int refreshForSessionBalances(String sessionId, Instant requestedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Instant refreshTime = requestedAt == null ? Instant.now() : requestedAt;
        Set<String> symbols = loadCanonicalSymbols(sessionId);
        int refreshed = 0;
        for (String symbol : symbols) {
            if (CanonicalAssetCatalog.isUsdStablecoin(null, null, symbol, null)) {
                upsert(symbol, new PriceQuote(
                        BigDecimal.ONE,
                        PriceSource.STABLECOIN,
                        refreshTime,
                        "USD",
                        "stablecoin-policy"
                ), refreshTime);
                refreshed++;
                continue;
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
                continue;
            }
            upsert(symbol, quote.orElseThrow(), refreshTime);
            refreshed++;
        }
        log.info("Current quote refresh complete: sessionId={}, symbols={}, refreshed={}", sessionId, symbols.size(), refreshed);
        return refreshed;
    }

    private Set<String> loadCanonicalSymbols(String sessionId) {
        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        List<Document> balances = mongoOperations.find(query, Document.class, "on_chain_balances");
        Set<String> symbols = new LinkedHashSet<>();
        if (balances == null) {
            return symbols;
        }
        for (Document balance : balances) {
            BigDecimal quantity = readQuantity(balance);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = CanonicalAssetCatalog.canonicalMarketSymbol(balance.getString("assetSymbol"));
            if (symbol != null && !symbol.isBlank()) {
                symbols.add(symbol.trim().toUpperCase(Locale.ROOT));
            }
        }
        return symbols;
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
}
