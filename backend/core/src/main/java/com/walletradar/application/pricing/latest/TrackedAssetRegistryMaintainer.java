package com.walletradar.application.pricing.latest;

import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.descriptor.NetworkRegistry;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds and maintains the {@code tracked_price_assets} registry.
 *
 * <p>On each invocation it unions all canonical symbols from:
 * <ul>
 *   <li>{@code on_chain_balances} (positive quantity)</li>
 *   <li>{@code bybit_live_balances} (positive umbrellaQty)</li>
 *   <li>{@code dzengi_live_balances} (positive umbrellaQty)</li>
 *   <li>LP position snapshots — token0, token1, unclaimed fee tokens</li>
 * </ul>
 *
 * <p>Stale entries (not seen for > {@code registryPruneTtlDays}) are pruned.
 *
 * <p>Kind derivation is deterministic:
 * <ul>
 *   <li>USD-stablecoin → {@code STABLECOIN}</li>
 *   <li>Symbols appearing in {@code dzengi_live_balances} with a dot suffix → {@code EQUITY}</li>
 *   <li>All others → {@code CRYPTO}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TrackedAssetRegistryMaintainer {

    private static final Logger log = LoggerFactory.getLogger(TrackedAssetRegistryMaintainer.class);

    private final MongoOperations mongoOperations;
    private final TrackedPriceAssetRepository trackedPriceAssetRepository;
    private final LatestPriceProperties latestPriceProperties;
    private final NetworkRegistry networkRegistry;

    /**
     * Rebuilds the tracked asset registry from all live balance and LP sources.
     *
     * @return number of unique symbols upserted
     */
    public int rebuild() {
        Collection<SymbolWithKind> symbols = collectAllSymbols().values();
        if (symbols.isEmpty()) {
            log.info("TrackedAssetRegistryMaintainer: no symbols found to track");
            return 0;
        }

        Instant now = Instant.now();
        int upserted = 0;
        for (SymbolWithKind entry : symbols) {
            mongoOperations.upsert(
                    Query.query(Criteria.where("_id").is(entry.symbol())),
                    new Update()
                            .set("symbol", entry.symbol())
                            .set("kind", entry.kind().name())
                            .set("lastSeenAt", now)
                            .setOnInsert("preferredSources", List.of()),
                    TrackedPriceAssetDocument.class
            );
            upserted++;
        }

        pruneStale(now);

        log.info("TrackedAssetRegistryMaintainer: upserted={}, total symbols={}", upserted, symbols.size());
        return upserted;
    }

    /**
     * Collects all tracked symbols from all balance/LP sources.
     * Uses a Map keyed by canonical symbol; EQUITY kind always wins over CRYPTO/UNKNOWN
     * to avoid false cross-source divergences (e.g. Bybit UUSDT crypto vs Dzengi U. equity).
     */
    private Map<String, SymbolWithKind> collectAllSymbols() {
        Map<String, SymbolWithKind> result = new LinkedHashMap<>();

        // WS-6 ordering fix: seed every network's native symbol FIRST so majors (notably non-EVM
        // SOL / TON) are always tracked — even on the first cycle where the registry is rebuilt
        // before non-EVM on_chain_balances have populated. Descriptor-driven, so no hardcoded list.
        seedNativeSymbols(result);

        // on_chain_balances
        List<Document> onChain = mongoOperations.find(new Query(), Document.class, "on_chain_balances");
        for (Document doc : onChain) {
            BigDecimal qty = readDecimal(doc.get("quantity"));
            if (qty == null || qty.signum() <= 0) continue;
            addSymbol(result, doc.getString("assetSymbol"), false);
        }

        // bybit_live_balances
        List<Document> bybitBalances = mongoOperations.find(new Query(), Document.class, "bybit_live_balances");
        for (Document doc : bybitBalances) {
            BigDecimal qty = readDecimal(doc.get("umbrellaQty"));
            if (qty == null || qty.signum() <= 0) continue;
            addSymbol(result, doc.getString("assetSymbol"), false);
        }

        // dzengi_live_balances — equity symbols (ending with ".") take priority if canonical matches a crypto
        List<Document> dzengiBalances = mongoOperations.find(new Query(), Document.class, "dzengi_live_balances");
        for (Document doc : dzengiBalances) {
            String raw = doc.getString("assetSymbol");
            if (raw == null || raw.isBlank() || "__EMPTY_UMBRELLA__".equals(raw)) continue;
            BigDecimal qty = readDecimal(doc.get("umbrellaQty"));
            if (qty == null || qty.signum() <= 0) continue;
            boolean equity = raw.endsWith(".");
            String canonical = equity
                    ? raw.substring(0, raw.length() - 1).trim().toUpperCase(Locale.ROOT)
                    : CanonicalAssetCatalog.canonicalMarketSymbol(raw);
            if (canonical != null && !canonical.isBlank()) {
                // Always override with EQUITY — it's the authoritative kind for this symbol
                result.put(canonical, new SymbolWithKind(canonical, TrackedPriceAssetDocument.Kind.EQUITY));
            }
        }

        // lp_position_snapshots — open positions
        Query lpQuery = new Query(Criteria.where("status").ne("closed"));
        List<Document> lpSnapshots = mongoOperations.find(lpQuery, Document.class, "lp_position_snapshots");
        for (Document snap : lpSnapshots) {
            addLpTokenSymbol(result, snap, "token0");
            addLpTokenSymbol(result, snap, "token1");
            Document fees = snap.get("unclaimedFeesByToken", Document.class);
            if (fees != null) {
                fees.keySet().forEach(sym -> addSymbol(result, sym, false));
            }
        }

        return result;
    }

    /** Seeds the native symbol of every wallet-supported network so majors are tracked pre-balance. */
    private void seedNativeSymbols(Map<String, SymbolWithKind> target) {
        for (NetworkId networkId : networkRegistry.walletSupportedNetworks()) {
            addSymbol(target, networkRegistry.nativeSymbol(networkId), false);
        }
    }

    private void addLpTokenSymbol(Map<String, SymbolWithKind> target, Document snap, String field) {
        Document token = snap.get(field, Document.class);
        if (token == null) return;
        addSymbol(target, token.getString("sym"), false);
    }

    /**
     * Adds a symbol to the map only if it is not already present, or if the incoming kind
     * is EQUITY (equity always wins to avoid false divergence between Bybit crypto and Dzengi equity).
     */
    private void addSymbol(Map<String, SymbolWithKind> target, String rawSymbol, boolean forceEquity) {
        if (rawSymbol == null || rawSymbol.isBlank()) return;
        String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(rawSymbol);
        if (canonical == null || canonical.isBlank()) return;
        TrackedPriceAssetDocument.Kind kind;
        if (CanonicalAssetCatalog.isUsdStablecoin(null, null, canonical, null)) {
            kind = TrackedPriceAssetDocument.Kind.STABLECOIN;
        } else if (forceEquity || CanonicalAssetCatalog.isEquityBacked(canonical)) {
            // WS-6 (B4): xStock jettons (AMZNx→AMZN, MSTRx→MSTR) are equity-priced; stamp EQUITY so
            // the Dzengi equity ticker (SYMBOL.) is matched instead of a crypto ticker.
            kind = TrackedPriceAssetDocument.Kind.EQUITY;
        } else {
            kind = TrackedPriceAssetDocument.Kind.CRYPTO;
        }
        // Only overwrite if the new kind is EQUITY (more authoritative than CRYPTO/UNKNOWN)
        target.merge(canonical, new SymbolWithKind(canonical, kind),
                (existing, incoming) -> incoming.kind() == TrackedPriceAssetDocument.Kind.EQUITY ? incoming : existing);
    }

    private void pruneStale(Instant now) {
        Instant pruneThreshold = now.minus(latestPriceProperties.getRegistryPruneTtlDays(), ChronoUnit.DAYS);
        trackedPriceAssetRepository.deleteByLastSeenAtBefore(pruneThreshold);
    }

    private static BigDecimal readDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Decimal128 d128) return d128.bigDecimalValue();
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (value instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private record SymbolWithKind(String symbol, TrackedPriceAssetDocument.Kind kind) {
    }
}
