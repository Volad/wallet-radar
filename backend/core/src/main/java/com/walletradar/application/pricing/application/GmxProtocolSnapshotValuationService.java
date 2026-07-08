package com.walletradar.application.pricing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.resolver.external.defillama.DefiLlamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds current GMX market-token quote snapshots outside dashboard reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmxProtocolSnapshotValuationService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int GM_TOKEN_DECIMALS = 18;
    private static final String TOTAL_SUPPLY_SELECTOR = "0x18160ddd";

    private final WebClient.Builder webClientBuilder;
    private final DefiLlamaClient defiLlamaClient;
    private final ExternalPricingEndpointProperties endpointProperties;

    private final Map<NetworkId, SnapshotCacheEntry> snapshotCache = new LinkedHashMap<>();
    private final Map<NetworkId, ApyCacheEntry> apyCache = new LinkedHashMap<>();

    /**
     * Returns the protocol-reported fee APY (0–100 scale) for a GM/GLV market token, or empty if unavailable.
     * The fee APY is sourced from GMX's /apy?period=7d endpoint. Values are returned in decimal (0–1) by
     * the API and converted to percentage (0–100) here for consistency with the rest of the system.
     */
    public Optional<BigDecimal> resolveMarketFeeApr(
            NetworkId networkId,
            String marketTokenAddress
    ) {
        if (networkId == null || !present(marketTokenAddress)) {
            return Optional.empty();
        }
        ChainEndpoints endpoints = endpoints(networkId).orElse(null);
        if (endpoints == null) {
            return Optional.empty();
        }
        String normalizedToken = normalizeAddress(marketTokenAddress);
        Map<String, BigDecimal> apyByToken = loadApySnapshot(networkId, endpoints, Instant.now()).orElse(null);
        if (apyByToken == null) {
            return Optional.empty();
        }
        BigDecimal apy = apyByToken.get(normalizedToken);
        if (apy == null || apy.signum() <= 0) {
            return Optional.empty();
        }
        // /apy returns values in 0–1 range; convert to 0–100 percentage scale.
        return Optional.of(apy.multiply(BigDecimal.valueOf(100), MC));
    }

    private Optional<Map<String, BigDecimal>> loadApySnapshot(
            NetworkId networkId,
            ChainEndpoints endpoints,
            Instant requestedAt
    ) {
        ApyCacheEntry cached = apyCache.get(networkId);
        if (cached != null && Duration.between(cached.fetchedAt(), requestedAt).compareTo(endpointProperties.getGmxApyCacheTtl()) < 0) {
            return Optional.of(cached.apyByToken());
        }

        JsonNode apyResponse = fetchFromAnyEndpoint(endpoints.apiBaseUrls(), "/apy?period=7d").orElse(null);
        if (apyResponse == null) {
            return Optional.empty();
        }

        Map<String, BigDecimal> apyByToken = new LinkedHashMap<>();
        // markets section
        JsonNode marketsNode = apyResponse.path("markets");
        if (marketsNode.isObject()) {
            marketsNode.fields().forEachRemaining(entry -> {
                BigDecimal apy = decimal(entry.getValue().path("apy").asText(null));
                if (apy.signum() > 0) {
                    apyByToken.put(normalizeAddress(entry.getKey()), apy);
                }
            });
        }
        // glvs section
        JsonNode glvsNode = apyResponse.path("glvs");
        if (glvsNode.isObject()) {
            glvsNode.fields().forEachRemaining(entry -> {
                BigDecimal apy = decimal(entry.getValue().path("apy").asText(null));
                if (apy.signum() > 0) {
                    apyByToken.put(normalizeAddress(entry.getKey()), apy);
                }
            });
        }

        apyCache.put(networkId, new ApyCacheEntry(apyByToken, requestedAt));
        log.debug("GMX APY snapshot loaded: network={} markets={}", networkId, apyByToken.size());
        return Optional.of(apyByToken);
    }

    public Optional<PriceQuote> resolveMarketTokenQuote(
            NetworkId networkId,
            String marketTokenAddress,
            String assetSymbol,
            Instant requestedAt
    ) {
        if (!isGmxSymbol(assetSymbol) || networkId == null || !present(marketTokenAddress)) {
            return Optional.empty();
        }
        ChainEndpoints endpoints = endpoints(networkId).orElse(null);
        if (endpoints == null) {
            return Optional.empty();
        }

        String normalizedMarketToken = normalizeAddress(marketTokenAddress);
        Instant refreshTime = requestedAt == null ? Instant.now() : requestedAt;

        // DefiLlama provides the accurate GM token price that accounts for GMX v2 PnL
        // and internal pool dynamics; prefer it over our own pool-value / totalSupply formula.
        Optional<BigDecimal> defiLlamaPrice = defiLlamaClient.currentPrice(networkId, normalizedMarketToken);
        if (defiLlamaPrice.isPresent()) {
            log.debug("GMX market token priced via DefiLlama: network={} token={} price={}",
                    networkId, normalizedMarketToken, defiLlamaPrice.get());
            return Optional.of(new PriceQuote(
                    defiLlamaPrice.get(),
                    PriceSource.DEFILLAMA,
                    refreshTime,
                    "USD",
                    "defillama:" + DefiLlamaClient.chainSlug(networkId).orElse(networkId.name().toLowerCase())
                            + ":" + normalizedMarketToken
            ));
        }

        log.debug("DefiLlama price unavailable for GMX token {}, falling back to pool-value formula", normalizedMarketToken);
        GmxSnapshot snapshot = loadSnapshot(networkId, endpoints, refreshTime).orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        MarketInfo marketInfo = snapshot.marketsByToken().get(normalizedMarketToken);
        if (marketInfo == null) {
            return Optional.empty();
        }
        BigDecimal totalSupply = fetchTotalSupply(endpoints.rpcUrls(), normalizedMarketToken).orElse(BigDecimal.ZERO);
        if (totalSupply.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal longValueUsd = tokenPoolValueUsd(snapshot, marketInfo.longToken(), marketInfo.poolAmountLong());
        BigDecimal shortValueUsd = tokenPoolValueUsd(snapshot, marketInfo.shortToken(), marketInfo.poolAmountShort());
        BigDecimal poolValueUsd = longValueUsd.add(shortValueUsd, MC);
        if (poolValueUsd.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal priceUsd = poolValueUsd.divide(totalSupply, MC);
        return Optional.of(new PriceQuote(
                priceUsd,
                PriceSource.PROTOCOL_SNAPSHOT,
                refreshTime,
                "USD",
                "gmx-v2:" + networkId.name() + ":" + normalizedMarketToken + ":" + marketInfo.name()
        ));
    }

    private Optional<GmxSnapshot> loadSnapshot(NetworkId networkId, ChainEndpoints endpoints, Instant requestedAt) {
        SnapshotCacheEntry cached = snapshotCache.get(networkId);
        if (cached != null && Duration.between(cached.fetchedAt(), requestedAt).compareTo(endpointProperties.getGmxSnapshotTtl()) < 0) {
            return Optional.of(cached.snapshot());
        }

        JsonNode marketsInfo = fetchFromAnyEndpoint(endpoints.apiBaseUrls(), "/markets/info").orElse(null);
        JsonNode tokens = fetchFromAnyEndpoint(endpoints.apiBaseUrls(), "/tokens").orElse(null);
        JsonNode tickers = fetchFromAnyEndpoint(endpoints.apiBaseUrls(), "/prices/tickers").orElse(null);
        if (marketsInfo == null || tokens == null || tickers == null) {
            return Optional.empty();
        }

        GmxSnapshot snapshot = new GmxSnapshot(
                parseMarkets(marketsInfo.path("markets")),
                parseTokenDecimals(tokens.path("tokens")),
                parseTokenPrices(tickers)
        );
        snapshotCache.put(networkId, new SnapshotCacheEntry(snapshot, requestedAt));
        return Optional.of(snapshot);
    }

    private Optional<JsonNode> fetchFromAnyEndpoint(List<String> baseUrls, String path) {
        for (String baseUrl : baseUrls) {
            try {
                JsonNode body = webClientBuilder
                        .baseUrl(baseUrl)
                        .build()
                        .get()
                        .uri(path)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(endpointProperties.getRequestTimeout());
                if (body != null && !body.isMissingNode() && !body.isNull()) {
                    return Optional.of(body);
                }
            } catch (WebClientResponseException error) {
                log.debug("GMX snapshot endpoint failed: url={}{} status={}", baseUrl, path, error.getStatusCode().value());
            } catch (RuntimeException error) {
                log.debug("GMX snapshot endpoint failed: url={}{} error={}", baseUrl, path, error.getMessage());
            }
        }
        return Optional.empty();
    }

    private Map<String, MarketInfo> parseMarkets(JsonNode markets) {
        Map<String, MarketInfo> byMarketToken = new LinkedHashMap<>();
        if (markets == null || !markets.isArray()) {
            return byMarketToken;
        }
        for (JsonNode market : markets) {
            String marketToken = normalizeAddress(market.path("marketToken").asText(null));
            if (!present(marketToken)) {
                continue;
            }
            byMarketToken.put(marketToken, new MarketInfo(
                    market.path("name").asText("GMX market"),
                    normalizeAddress(market.path("longToken").asText(null)),
                    normalizeAddress(market.path("shortToken").asText(null)),
                    decimal(market.path("poolAmountLong").asText(null)),
                    decimal(market.path("poolAmountShort").asText(null))
            ));
        }
        return byMarketToken;
    }

    private Map<String, Integer> parseTokenDecimals(JsonNode tokens) {
        Map<String, Integer> decimalsByToken = new LinkedHashMap<>();
        if (tokens == null || !tokens.isArray()) {
            return decimalsByToken;
        }
        for (JsonNode token : tokens) {
            String address = normalizeAddress(token.path("address").asText(null));
            if (present(address) && token.hasNonNull("decimals")) {
                decimalsByToken.put(address, Math.max(0, token.path("decimals").asInt()));
            }
        }
        return decimalsByToken;
    }

    private Map<String, RawTokenPrice> parseTokenPrices(JsonNode tickers) {
        Map<String, RawTokenPrice> pricesByToken = new LinkedHashMap<>();
        if (tickers == null || !tickers.isArray()) {
            return pricesByToken;
        }
        for (JsonNode ticker : tickers) {
            String address = normalizeAddress(ticker.path("tokenAddress").asText(null));
            if (!present(address)) {
                continue;
            }
            BigDecimal minPrice = decimal(ticker.path("minPrice").asText(null));
            BigDecimal maxPrice = decimal(ticker.path("maxPrice").asText(null));
            if (minPrice.signum() <= 0 && maxPrice.signum() <= 0) {
                continue;
            }
            pricesByToken.put(address, new RawTokenPrice(minPrice, maxPrice));
        }
        return pricesByToken;
    }

    private BigDecimal tokenPoolValueUsd(GmxSnapshot snapshot, String tokenAddress, BigDecimal rawAmount) {
        if (!present(tokenAddress) || rawAmount == null || rawAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Integer decimals = snapshot.decimalsByToken().get(tokenAddress);
        RawTokenPrice rawPrice = snapshot.pricesByToken().get(tokenAddress);
        if (decimals == null || rawPrice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal quantity = rawAmount.divide(powerOfTen(decimals), MC);
        BigDecimal priceUsd = rawPrice.midPrice().divide(powerOfTen(30 - decimals), MC);
        return quantity.multiply(priceUsd, MC);
    }

    private Optional<BigDecimal> fetchTotalSupply(List<String> rpcUrls, String marketTokenAddress) {
        for (String rpcUrl : rpcUrls) {
            try {
                JsonNode response = webClientBuilder
                        .baseUrl(rpcUrl)
                        .build()
                        .post()
                        .bodyValue(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "eth_call",
                                "params", List.of(
                                        Map.of("to", marketTokenAddress, "data", TOTAL_SUPPLY_SELECTOR),
                                        "latest"
                                )
                        ))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(endpointProperties.getRequestTimeout());
                String result = response == null ? null : response.path("result").asText(null);
                BigDecimal supply = decodeUint256(result).divide(powerOfTen(GM_TOKEN_DECIMALS), MC);
                if (supply.signum() > 0) {
                    return Optional.of(supply);
                }
            } catch (RuntimeException error) {
                log.debug("GMX market token totalSupply failed: rpcUrl={} token={} error={}",
                        rpcUrl,
                        marketTokenAddress,
                        error.getMessage()
                );
            }
        }
        return Optional.empty();
    }

    private Optional<ChainEndpoints> endpoints(NetworkId networkId) {
        ExternalPricingEndpointProperties.GmxChainEndpoints chain = endpointProperties.getGmx().get(networkId);
        if (chain == null
                || chain.getApiBaseUrls() == null
                || chain.getApiBaseUrls().isEmpty()
                || chain.getRpcUrls() == null
                || chain.getRpcUrls().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ChainEndpoints(chain.getApiBaseUrls(), chain.getRpcUrls()));
    }

    private boolean isGmxSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("GM:") || normalized.startsWith("GLV");
    }

    private BigDecimal decodeUint256(String hex) {
        if (hex == null || hex.isBlank() || "0x".equalsIgnoreCase(hex)) {
            return BigDecimal.ZERO;
        }
        String normalized = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (normalized.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(new BigInteger(normalized, 16));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal powerOfTen(int exponent) {
        if (exponent <= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.TEN.pow(exponent, MC);
    }

    private String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record ChainEndpoints(List<String> apiBaseUrls, List<String> rpcUrls) {
    }

    private record GmxSnapshot(
            Map<String, MarketInfo> marketsByToken,
            Map<String, Integer> decimalsByToken,
            Map<String, RawTokenPrice> pricesByToken
    ) {
    }

    private record SnapshotCacheEntry(GmxSnapshot snapshot, Instant fetchedAt) {
    }

    private record ApyCacheEntry(Map<String, BigDecimal> apyByToken, Instant fetchedAt) {
    }

    private record MarketInfo(
            String name,
            String longToken,
            String shortToken,
            BigDecimal poolAmountLong,
            BigDecimal poolAmountShort
    ) {
    }

    private record RawTokenPrice(BigDecimal minPrice, BigDecimal maxPrice) {
        private BigDecimal midPrice() {
            if (minPrice.signum() <= 0) {
                return maxPrice;
            }
            if (maxPrice.signum() <= 0) {
                return minPrice;
            }
            return minPrice.add(maxPrice, MC).divide(BigDecimal.valueOf(2), MC);
        }
    }
}
