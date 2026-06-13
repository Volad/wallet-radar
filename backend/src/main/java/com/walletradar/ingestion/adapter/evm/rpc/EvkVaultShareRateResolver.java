package com.walletradar.ingestion.adapter.evm.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the true underlying-asset value of an ERC-4626 / Euler EVK vault-share amount via the
 * on-chain {@code convertToAssets(uint256)} exchange rate, read at the historical block of the
 * transaction so basis (acquisition) and proceeds (disposal) are valued at the rate that actually
 * applied.
 *
 * <p>EVK vault shares are not 1:1 with their underlying; assuming $1/share fabricates basis (e.g.
 * a stablecoin lending share priced as $216/u). This resolver reads:
 * <ul>
 *   <li>{@code convertToAssets(shares)} — underlying raw amount for the share amount;</li>
 *   <li>{@code asset()} — the underlying token address;</li>
 *   <li>{@code decimals()} on the underlying token — to convert the raw amount to units.</li>
 * </ul>
 *
 * <p>Fail-safe: if any read fails (RPC error, pruned/non-archive endpoint, missing trie node), the
 * resolver returns {@link Optional#empty()} so callers can leave the leg unpriced / PENDING rather
 * than assume a 1:1 rate. The exchange rate (linear in shares) is cached per (network, vault, block)
 * and the underlying metadata per (network, vault) so a vault is read at most once per block.
 */
@Slf4j
@Component
public class EvkVaultShareRateResolver {

    /** keccak256("convertToAssets(uint256)")[0..4] */
    private static final String CONVERT_TO_ASSETS_SELECTOR = "0x07a2d13a";
    /** keccak256("asset()")[0..4] */
    private static final String ASSET_SELECTOR = "0x38d52e0f";
    /** keccak256("decimals()")[0..4] */
    private static final String DECIMALS_SELECTOR = "0x313ce567";

    private static final int WORD_HEX_LENGTH = 64;

    private final EvmRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;

    /** (network|vault|block|shares) -> exact convertToAssets underlying-raw result. */
    private final Map<String, BigInteger> assetsCache = new ConcurrentHashMap<>();
    /** (network|vault) -> resolved underlying token metadata. */
    private final Map<String, UnderlyingMetadata> underlyingCache = new ConcurrentHashMap<>();
    /** (network|vault|block) -> underlying whole-units per 1 whole share. */
    private final Map<String, BigDecimal> perShareCache = new ConcurrentHashMap<>();
    /** (network|vault) -> share-token (vault) decimals. */
    private final Map<String, Integer> shareDecimalsCache = new ConcurrentHashMap<>();

    public EvkVaultShareRateResolver(
            EvmRpcClient rpcClient,
            @Qualifier("evmRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
            @Qualifier("evmDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
            ObjectMapper objectMapper
    ) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the underlying-asset value of {@code sharesRaw} vault shares at {@code blockNumber}.
     *
     * @return the underlying valuation, or empty when the rate cannot be resolved (fail-safe).
     */
    public Optional<EvkShareUnderlying> resolveUnderlying(
            NetworkId network,
            String vaultContract,
            BigInteger sharesRaw,
            long blockNumber
    ) {
        if (network == null
                || vaultContract == null
                || vaultContract.isBlank()
                || sharesRaw == null
                || sharesRaw.signum() <= 0
                || blockNumber <= 0) {
            return Optional.empty();
        }
        String vault = vaultContract.trim().toLowerCase(Locale.ROOT);
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(network.name(), defaultRotator);
        if (rotator == null) {
            return Optional.empty();
        }

        UnderlyingMetadata underlying = resolveUnderlyingMetadata(network, vault, blockNumber, rotator);
        if (underlying == null) {
            return Optional.empty();
        }

        BigInteger underlyingRaw = resolveUnderlyingRaw(network, vault, sharesRaw, blockNumber, rotator);
        if (underlyingRaw == null || underlyingRaw.signum() < 0) {
            return Optional.empty();
        }
        return Optional.of(new EvkShareUnderlying(
                underlying.asset(),
                underlying.decimals(),
                underlyingRaw
        ));
    }

    /**
     * Resolves the underlying-asset whole-units backing exactly one whole vault share at
     * {@code blockNumber}, via {@code convertToAssets(10^shareDecimals)}.
     *
     * <p>For a USD-stablecoin vault this equals the USD basis/proceeds of one share (≈ $1.0x), so callers
     * can value a share leg as {@code units * perShare * underlyingUsd}. Returns empty (fail-safe) when
     * the rate cannot be resolved so callers leave the leg PENDING rather than assume a 1:1 rate.
     */
    public Optional<BigDecimal> resolveUnderlyingUnitsPerShare(
            NetworkId network,
            String vaultContract,
            long blockNumber
    ) {
        if (network == null || vaultContract == null || vaultContract.isBlank() || blockNumber <= 0) {
            return Optional.empty();
        }
        String vault = vaultContract.trim().toLowerCase(Locale.ROOT);
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(network.name(), defaultRotator);
        if (rotator == null) {
            return Optional.empty();
        }

        String perShareKey = network.name() + "|" + vault + "|" + blockNumber;
        BigDecimal cachedPerShare = perShareCache.get(perShareKey);
        if (cachedPerShare != null) {
            return Optional.of(cachedPerShare);
        }

        UnderlyingMetadata underlying = resolveUnderlyingMetadata(network, vault, blockNumber, rotator);
        if (underlying == null) {
            return Optional.empty();
        }
        Integer shareDecimals = resolveShareDecimals(network, vault, blockNumber, rotator);
        if (shareDecimals == null) {
            return Optional.empty();
        }
        BigInteger oneShareRaw = BigInteger.TEN.pow(shareDecimals);
        BigInteger underlyingRaw = resolveUnderlyingRaw(network, vault, oneShareRaw, blockNumber, rotator);
        if (underlyingRaw == null || underlyingRaw.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal perShare = new BigDecimal(underlyingRaw).movePointLeft(underlying.decimals());
        perShareCache.put(perShareKey, perShare);
        return Optional.of(perShare);
    }

    private Integer resolveShareDecimals(
            NetworkId network,
            String vault,
            long blockNumber,
            RpcEndpointRotator rotator
    ) {
        String key = network.name() + "|" + vault;
        Integer cached = shareDecimalsCache.get(key);
        if (cached != null) {
            return cached;
        }
        BigInteger decimalsValue = callUint(rotator, vault, DECIMALS_SELECTOR, blockNumber);
        if (decimalsValue == null || decimalsValue.signum() < 0 || decimalsValue.bitLength() > 7) {
            return null;
        }
        int decimals = decimalsValue.intValue();
        shareDecimalsCache.put(key, decimals);
        return decimals;
    }

    private BigInteger resolveUnderlyingRaw(
            NetworkId network,
            String vault,
            BigInteger sharesRaw,
            long blockNumber,
            RpcEndpointRotator rotator
    ) {
        String key = network.name() + "|" + vault + "|" + blockNumber + "|" + sharesRaw;
        BigInteger cached = assetsCache.get(key);
        if (cached != null) {
            return cached;
        }
        String shareWord = padUint(sharesRaw);
        BigInteger assetsRaw = callUint(rotator, vault, CONVERT_TO_ASSETS_SELECTOR + shareWord, blockNumber);
        if (assetsRaw == null || assetsRaw.signum() < 0) {
            return null;
        }
        assetsCache.put(key, assetsRaw);
        return assetsRaw;
    }

    private UnderlyingMetadata resolveUnderlyingMetadata(
            NetworkId network,
            String vault,
            long blockNumber,
            RpcEndpointRotator rotator
    ) {
        String key = network.name() + "|" + vault;
        UnderlyingMetadata cached = underlyingCache.get(key);
        if (cached != null) {
            return cached;
        }
        String assetAddress = callAddress(rotator, vault, ASSET_SELECTOR, blockNumber);
        if (assetAddress == null || assetAddress.isBlank()) {
            return null;
        }
        BigInteger decimalsValue = callUint(rotator, assetAddress, DECIMALS_SELECTOR, blockNumber);
        if (decimalsValue == null || decimalsValue.signum() < 0 || decimalsValue.bitLength() > 7) {
            return null;
        }
        UnderlyingMetadata metadata = new UnderlyingMetadata(assetAddress, decimalsValue.intValue());
        underlyingCache.put(key, metadata);
        return metadata;
    }

    private BigInteger callUint(RpcEndpointRotator rotator, String to, String data, long blockNumber) {
        String result = callContract(rotator, to, data, blockNumber);
        return parseUint(result);
    }

    private String callAddress(RpcEndpointRotator rotator, String to, String data, long blockNumber) {
        String result = callContract(rotator, to, data, blockNumber);
        if (result == null) {
            return null;
        }
        String hex = result.startsWith("0x") ? result.substring(2) : result;
        if (hex.length() < WORD_HEX_LENGTH) {
            return null;
        }
        String word = hex.substring(0, WORD_HEX_LENGTH);
        String address = "0x" + word.substring(WORD_HEX_LENGTH - 40);
        if (address.equals("0x0000000000000000000000000000000000000000")) {
            return null;
        }
        return address.toLowerCase(Locale.ROOT);
    }

    private String callContract(RpcEndpointRotator rotator, String to, String data, long blockNumber) {
        String blockTag = "0x" + Long.toHexString(blockNumber);
        Object params = List.of(Map.of("to", to, "data", data), blockTag);
        for (String endpoint : rotator.getEndpoints()) {
            try {
                String json = rpcClient.call(endpoint, "eth_call", params).block();
                if (json == null) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(json);
                if (!root.path("error").isMissingNode() && !root.path("error").isNull()) {
                    log.debug("EVK eth_call error to={} data={} block={} endpoint={} error={}",
                            to, data, blockNumber, endpoint, root.path("error").toString());
                    continue;
                }
                JsonNode resultNode = root.path("result");
                if (resultNode.isMissingNode() || resultNode.isNull()) {
                    continue;
                }
                String result = resultNode.asText(null);
                if (result != null && !result.isBlank() && !"0x".equalsIgnoreCase(result)) {
                    return result;
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
                log.debug("EVK eth_call failed to={} data={} block={} endpoint={} cause={}",
                        to, data, blockNumber, endpoint, ex.getMessage());
            }
        }
        return null;
    }

    private static BigInteger parseUint(String hex) {
        if (hex == null || hex.isBlank() || "0x".equalsIgnoreCase(hex)) {
            return null;
        }
        String normalized = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (normalized.isBlank() || !normalized.matches("[0-9a-fA-F]+")) {
            return null;
        }
        try {
            return new BigInteger(normalized, 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String padUint(BigInteger value) {
        String hex = value.toString(16);
        if (hex.length() > WORD_HEX_LENGTH) {
            return hex.substring(hex.length() - WORD_HEX_LENGTH);
        }
        return "0".repeat(WORD_HEX_LENGTH - hex.length()) + hex;
    }

    /** Underlying valuation of a share amount: token address, its decimals, and the raw underlying amount. */
    public record EvkShareUnderlying(String underlyingAsset, int underlyingDecimals, BigInteger underlyingRaw) {

        /** Underlying amount in whole units. */
        public BigDecimal underlyingUnits() {
            return new BigDecimal(underlyingRaw).movePointLeft(underlyingDecimals);
        }
    }

    private record UnderlyingMetadata(String asset, int decimals) {
    }
}
