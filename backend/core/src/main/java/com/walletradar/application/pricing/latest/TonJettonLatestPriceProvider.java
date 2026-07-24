package com.walletradar.application.pricing.latest;

import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.platform.networks.ton.price.TonPriceClient;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Latest-price provider for TON jettons (ADR-068, WS-6/B4). Prices jetton holdings by their master
 * contract via the free STON.fi {@code /v1/assets} feed, keyed in {@code current_price_quotes} by the
 * resolved canonical symbol.
 *
 * <p>Mapping is derived from {@code on_chain_balances} (networkId=TON): each row's {@code assetSymbol}
 * → canonical symbol and {@code assetContract} → the jetton master to price. Native TON and USD-pegged
 * stablecoins are skipped (TON is priced by CEX venues; stablecoins are pinned to $1). When several
 * masters canonicalize to the same symbol, the master held in the largest quantity wins.</p>
 *
 * <p>This is a <strong>fallback</strong> source (external feed) for the current mark-to-market and for
 * jettons whose historical basis cannot be swap-derived. It is the only source for long-tail TON
 * jettons (STON, XAUt) that no CEX lists. Never throws.</p>
 */
@Component
public class TonJettonLatestPriceProvider implements LatestPriceProvider {

    private static final Logger log = LoggerFactory.getLogger(TonJettonLatestPriceProvider.class);

    private static final String COLLECTION = "on_chain_balances";
    private static final String USD_QUOTE = "USD";
    private static final Pattern RAW_ADDRESS = Pattern.compile("^-?\\d+:[0-9a-fA-F]{64}$");
    /** Below Bybit (1) and Dzengi (2): CEX venues win for symbols they cover (e.g. native TON). */
    private static final int PRIORITY = 6;

    private final MongoOperations mongoOperations;
    private final TonPriceClient tonPriceClient;

    public TonJettonLatestPriceProvider(MongoOperations mongoOperations, TonPriceClient tonPriceClient) {
        this.mongoOperations = mongoOperations;
        this.tonPriceClient = tonPriceClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.STON_FI;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Map<String, NormalizedLatestQuote> fetchAll(Map<String, TrackedPriceAssetDocument.Kind> wantedSymbolsWithKind) {
        if (wantedSymbolsWithKind == null || wantedSymbolsWithKind.isEmpty()) {
            return Map.of();
        }

        Map<String, List<MasterQuantity>> mastersBySymbol;
        try {
            mastersBySymbol = collectTonMastersBySymbol(wantedSymbolsWithKind.keySet());
        } catch (Exception ex) {
            log.warn("TonJettonLatestPriceProvider: on_chain_balances scan failed: {}", ex.getMessage());
            return Map.of();
        }
        if (mastersBySymbol.isEmpty()) {
            return Map.of();
        }

        Map<String, BigDecimal> priceByMaster;
        try {
            priceByMaster = tonPriceClient.fetchAllPrices();
        } catch (Exception ex) {
            log.warn("TonJettonLatestPriceProvider: price fetch failed: {}", ex.getMessage());
            return Map.of();
        }
        if (priceByMaster == null || priceByMaster.isEmpty()) {
            return Map.of();
        }

        Instant now = Instant.now();
        Map<String, NormalizedLatestQuote> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<MasterQuantity>> entry : mastersBySymbol.entrySet()) {
            String symbol = entry.getKey();
            MasterQuantity chosen = pickPricedMaster(entry.getValue(), priceByMaster);
            if (chosen == null) {
                continue;
            }
            BigDecimal price = priceByMaster.get(rawKey(chosen.master()));
            result.put(symbol, new NormalizedLatestQuote(
                    symbol, price, USD_QUOTE, PriceSource.STON_FI, chosen.master(), now));
        }

        log.debug("TonJettonLatestPriceProvider: priced {} / {} ton symbols from {} masters",
                result.size(), mastersBySymbol.size(), priceByMaster.size());
        return result;
    }

    /**
     * Builds canonical symbol → candidate jetton masters from {@code on_chain_balances}
     * (networkId=TON, positive quantity), skipping native TON (descriptor {@code native-identity})
     * and pinned USD stablecoins and restricting to the wanted symbol set.
     */
    private Map<String, List<MasterQuantity>> collectTonMastersBySymbol(java.util.Set<String> wanted) {
        Query query = Query.query(Criteria.where("networkId").is(NetworkId.TON.name()));
        query.fields().include("assetSymbol").include("assetContract").include("quantity");

        Map<String, List<MasterQuantity>> result = new LinkedHashMap<>();
        for (Document doc : mongoOperations.find(query, Document.class, COLLECTION)) {
            String master = trimToNull(doc.getString("assetContract"));
            if (master == null || master.equalsIgnoreCase(NetworkNativeAssets.nativeIdentity(NetworkId.TON))) {
                continue;
            }
            String rawSymbol = trimToNull(doc.getString("assetSymbol"));
            if (CanonicalAssetCatalog.isUsdStablecoin(NetworkId.TON, master, rawSymbol, null)) {
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
            result.computeIfAbsent(canonical, k -> new ArrayList<>()).add(new MasterQuantity(master, quantity));
        }
        return result;
    }

    /** Chooses the priced master held in the largest quantity, logging a debug collision when >1 priced. */
    private MasterQuantity pickPricedMaster(List<MasterQuantity> candidates, Map<String, BigDecimal> priceByMaster) {
        MasterQuantity best = null;
        int priced = 0;
        for (MasterQuantity candidate : candidates) {
            if (!priceByMaster.containsKey(rawKey(candidate.master()))) {
                continue;
            }
            priced++;
            if (best == null || candidate.quantity().compareTo(best.quantity()) > 0) {
                best = candidate;
            }
        }
        if (priced > 1 && best != null) {
            log.debug("TonJettonLatestPriceProvider: {} priced masters collide on one symbol, using largest-qty master {}",
                    priced, best.master());
        }
        return best;
    }

    /**
     * Canonical lookup key for a jetton master: the raw {@code workchain:hex} form (lowercase), which
     * both STON.fi ({@code EQ…} friendly) and on-chain balance rows ({@code 0:hex}) resolve to.
     */
    private static String rawKey(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        for (String candidate : TonAddressCanonicalizer.lookupKeys(address)) {
            if (RAW_ADDRESS.matcher(candidate).matches()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        String trimmed = address.trim();
        return RAW_ADDRESS.matcher(trimmed).matches() ? trimmed.toLowerCase(Locale.ROOT) : null;
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

    private record MasterQuantity(String master, BigDecimal quantity) {
    }
}
