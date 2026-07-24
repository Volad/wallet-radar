package com.walletradar.application.pricing.latest;

import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.jupiter.JupiterProperties;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Latest-price provider for Solana SPL tokens (ADR-068). Prices SPL holdings by their base58 mint
 * via the free Jupiter Price v3 API, keyed in {@code current_price_quotes} by the resolved canonical
 * symbol.
 *
 * <p>Mapping is derived from {@code on_chain_balances} (networkId=SOLANA): each row's
 * {@code assetSymbol} → canonical symbol and {@code assetContract} → the mint to price. Native SOL
 * and USD-pegged stablecoins are skipped (SOL is priced by CEX venues; stablecoins are pinned to
 * $1). When several mints canonicalize to the same symbol, the mint held in the largest quantity
 * wins.</p>
 *
 * <p>Lower priority than Bybit/Dzengi so majors still come from the CEX venues; this is the only
 * source for SPL memecoins. Never throws.</p>
 */
@Component
public class JupiterPriceLatestPriceProvider implements LatestPriceProvider {

    private static final Logger log = LoggerFactory.getLogger(JupiterPriceLatestPriceProvider.class);

    private static final String COLLECTION = "on_chain_balances";
    private static final String NATIVE_SOL_IDENTITY = "NATIVE:SOLANA";
    /**
     * Wrapped SOL SPL mint. wSOL is accounting- and price-identical to native SOL (it canonicalises
     * to SOL), so it must be priced by the CEX venues (Bybit/Dzengi), never as a distinct SPL via
     * Jupiter — otherwise a spurious JUPITER SOL quote competes with the authoritative CEX mark.
     */
    private static final String WRAPPED_SOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String USD_QUOTE = "USD";
    /** Below Bybit (1) and Dzengi (2): CEX venues win for symbols they cover. */
    private static final int PRIORITY = 5;

    private final MongoOperations mongoOperations;
    private final JupiterClient jupiterClient;
    private final JupiterProperties jupiterProperties;

    public JupiterPriceLatestPriceProvider(MongoOperations mongoOperations,
                                           JupiterClient jupiterClient,
                                           JupiterProperties jupiterProperties) {
        this.mongoOperations = mongoOperations;
        this.jupiterClient = jupiterClient;
        this.jupiterProperties = jupiterProperties;
    }

    @Override
    public PriceSource source() {
        return PriceSource.JUPITER;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Map<String, NormalizedLatestQuote> fetchAll(Map<String, TrackedPriceAssetDocument.Kind> wantedSymbolsWithKind) {
        if (!jupiterProperties.isEnabled() || wantedSymbolsWithKind == null || wantedSymbolsWithKind.isEmpty()) {
            return Map.of();
        }

        Map<String, List<MintQuantity>> mintsBySymbol;
        try {
            mintsBySymbol = collectSolanaMintsBySymbol(wantedSymbolsWithKind.keySet());
        } catch (Exception ex) {
            log.warn("JupiterPriceLatestPriceProvider: on_chain_balances scan failed: {}", ex.getMessage());
            return Map.of();
        }
        if (mintsBySymbol.isEmpty()) {
            return Map.of();
        }

        Map<String, BigDecimal> priceByMint;
        try {
            priceByMint = fetchPrices(distinctMints(mintsBySymbol));
        } catch (Exception ex) {
            log.warn("JupiterPriceLatestPriceProvider: price fetch failed: {}", ex.getMessage());
            return Map.of();
        }
        if (priceByMint.isEmpty()) {
            return Map.of();
        }

        Instant now = Instant.now();
        Map<String, NormalizedLatestQuote> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<MintQuantity>> entry : mintsBySymbol.entrySet()) {
            String symbol = entry.getKey();
            MintQuantity chosen = pickPricedMint(entry.getValue(), priceByMint);
            if (chosen == null) {
                continue;
            }
            BigDecimal price = priceByMint.get(chosen.mint());
            result.put(symbol, new NormalizedLatestQuote(
                    symbol, price, USD_QUOTE, PriceSource.JUPITER, chosen.mint(), now));
        }

        log.debug("JupiterPriceLatestPriceProvider: priced {} / {} solana symbols from {} mints",
                result.size(), mintsBySymbol.size(), priceByMint.size());
        return result;
    }

    /**
     * Builds canonical symbol → candidate mints from {@code on_chain_balances} (networkId=SOLANA,
     * positive quantity), skipping native SOL and pinned USD stablecoins and restricting to the
     * wanted symbol set.
     */
    private Map<String, List<MintQuantity>> collectSolanaMintsBySymbol(java.util.Set<String> wanted) {
        Query query = Query.query(Criteria.where("networkId").is(NetworkId.SOLANA.name()));
        query.fields().include("assetSymbol").include("assetContract").include("quantity");

        Map<String, List<MintQuantity>> result = new LinkedHashMap<>();
        for (Document doc : mongoOperations.find(query, Document.class, COLLECTION)) {
            String mint = trimToNull(doc.getString("assetContract"));
            if (mint == null || NATIVE_SOL_IDENTITY.equals(mint) || WRAPPED_SOL_MINT.equals(mint)) {
                continue;
            }
            String rawSymbol = trimToNull(doc.getString("assetSymbol"));
            if (CanonicalAssetCatalog.isUsdStablecoin(NetworkId.SOLANA, mint, rawSymbol, null)) {
                continue;
            }
            String canonical = canonical(rawSymbol);
            if (canonical == null || !wanted.contains(canonical)) {
                continue;
            }
            BigDecimal quantity = readDecimal(doc.get("quantity"));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            result.computeIfAbsent(canonical, k -> new ArrayList<>()).add(new MintQuantity(mint, quantity));
        }
        return result;
    }

    private List<String> distinctMints(Map<String, List<MintQuantity>> mintsBySymbol) {
        LinkedHashSet<String> mints = new LinkedHashSet<>();
        for (List<MintQuantity> candidates : mintsBySymbol.values()) {
            for (MintQuantity candidate : candidates) {
                mints.add(candidate.mint());
            }
        }
        return new ArrayList<>(mints);
    }

    private Map<String, BigDecimal> fetchPrices(List<String> mints) {
        int chunkSize = Math.max(1, jupiterProperties.getMaxIdsPerRequest());
        Map<String, BigDecimal> priceByMint = new LinkedHashMap<>();
        for (int start = 0; start < mints.size(); start += chunkSize) {
            List<String> chunk = mints.subList(start, Math.min(mints.size(), start + chunkSize));
            priceByMint.putAll(jupiterClient.fetchPrices(chunk));
        }
        return priceByMint;
    }

    /** Chooses the priced mint held in the largest quantity, logging a debug collision when >1 priced. */
    private MintQuantity pickPricedMint(List<MintQuantity> candidates, Map<String, BigDecimal> priceByMint) {
        MintQuantity best = null;
        int priced = 0;
        for (MintQuantity candidate : candidates) {
            if (!priceByMint.containsKey(candidate.mint())) {
                continue;
            }
            priced++;
            if (best == null || candidate.quantity().compareTo(best.quantity()) > 0) {
                best = candidate;
            }
        }
        if (priced > 1 && best != null) {
            log.debug("JupiterPriceLatestPriceProvider: {} priced mints collide on one symbol, using largest-qty mint {}",
                    priced, best.mint());
        }
        return best;
    }

    private static String canonical(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return null;
        }
        String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(rawSymbol);
        if (canonical == null || canonical.isBlank()) {
            return null;
        }
        return canonical.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal readDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Decimal128 d128) {
            return d128.bigDecimalValue();
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record MintQuantity(String mint, BigDecimal quantity) {
    }
}
