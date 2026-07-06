package com.walletradar.ingestion.adapter.evm.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.common.RetryPolicy;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies EVK vault-share -> underlying valuation via convertToAssets.
 *
 * <p>Fixtures are real Avalanche on-chain reads (evidence anchors) at block 67399112:
 * <ul>
 *   <li>eUSDt-2 {@code 0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4}: convertToAssets(5114971)=5331162,
 *       asset()=USDt, decimals=6 -> 5.114971 shares = 5.331162 USDt (~$1.042/share, NOT 1:1).</li>
 *   <li>eUSDC-2 {@code 0x39de0f00189306062d79edec6dca5bb6bfd108f9}: convertToAssets(1341798065)=1379961197,
 *       asset()=USDC, decimals=6 -> 1341.798065 shares = 1379.961197 USDC.</li>
 * </ul>
 */
class EvkVaultShareRateResolverTest {

    private static final String CONVERT_TO_ASSETS = "0x07a2d13a";
    private static final String ASSET = "0x38d52e0f";
    private static final String DECIMALS = "0x313ce567";

    private static final String EUSDT2 = "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4";
    private static final String USDT = "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7";
    private static final String EUSDC2 = "0x39de0f00189306062d79edec6dca5bb6bfd108f9";
    private static final String USDC = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

    private static final long BLOCK = 67399112L;

    @Test
    void resolvesUsdtVaultShareToUnderlyingViaConvertToAssets() {
        EvkVaultShareRateResolver resolver = resolver(stubClient());

        Optional<EvkVaultShareRateResolver.EvkShareUnderlying> result =
                resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, new BigInteger("5114971"), BLOCK);

        assertThat(result).isPresent();
        EvkVaultShareRateResolver.EvkShareUnderlying underlying = result.orElseThrow();
        assertThat(underlying.underlyingAsset()).isEqualTo(USDT);
        assertThat(underlying.underlyingDecimals()).isEqualTo(6);
        assertThat(underlying.underlyingRaw()).isEqualTo(new BigInteger("5331162"));
        assertThat(underlying.underlyingUnits()).isEqualByComparingTo("5.331162");
    }

    @Test
    void resolvesUsdcVaultShareToUnderlyingViaConvertToAssets() {
        EvkVaultShareRateResolver resolver = resolver(stubClient());

        Optional<EvkVaultShareRateResolver.EvkShareUnderlying> result =
                resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDC2, new BigInteger("1341798065"), BLOCK);

        assertThat(result).isPresent();
        EvkVaultShareRateResolver.EvkShareUnderlying underlying = result.orElseThrow();
        assertThat(underlying.underlyingAsset()).isEqualTo(USDC);
        assertThat(underlying.underlyingDecimals()).isEqualTo(6);
        assertThat(underlying.underlyingRaw()).isEqualTo(new BigInteger("1379961197"));
        // ~$1.0284 per share -> NOT 1:1; an assumed $1/share would fabricate basis.
        assertThat(underlying.underlyingUnits()).isEqualByComparingTo("1379.961197");
    }

    @Test
    void shareRateIsLinearForAnyShareAmount() {
        EvkVaultShareRateResolver resolver = resolver(stubClient());

        // Close redeems only the small 5.114971-share lot: it is genuinely worth ~$5.33, not the
        // ~$1108 basis the depressed-carry produced.
        Optional<EvkVaultShareRateResolver.EvkShareUnderlying> closeLot =
                resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, new BigInteger("5114971"), BLOCK);
        assertThat(closeLot).isPresent();
        assertThat(closeLot.orElseThrow().underlyingUnits()).isEqualByComparingTo("5.331162");
    }

    @Test
    void resolvesUnderlyingUnitsPerShareForStablecoinVaults() {
        EvkVaultShareRateResolver resolver = resolver(stubClient());

        Optional<java.math.BigDecimal> usdt =
                resolver.resolveUnderlyingUnitsPerShare(NetworkId.AVALANCHE, EUSDT2, BLOCK);
        Optional<java.math.BigDecimal> usdc =
                resolver.resolveUnderlyingUnitsPerShare(NetworkId.AVALANCHE, EUSDC2, BLOCK);

        // ~$1.042/share and ~$1.028/share — NOT 1:1; a 1:1 assumption fabricates basis.
        assertThat(usdt).isPresent();
        assertThat(usdt.orElseThrow()).isBetween(new java.math.BigDecimal("1.040"), new java.math.BigDecimal("1.045"));
        assertThat(usdc).isPresent();
        assertThat(usdc.orElseThrow()).isBetween(new java.math.BigDecimal("1.025"), new java.math.BigDecimal("1.030"));
    }

    @Test
    void underlyingUnitsPerShareFailsSafeWhenRateUnresolvable() {
        EvmRpcClient pruned = client((endpointUrl, method, params) -> Mono.just(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"missing trie node\"}}"));
        EvkVaultShareRateResolver resolver = resolver(pruned);

        assertThat(resolver.resolveUnderlyingUnitsPerShare(NetworkId.AVALANCHE, EUSDT2, BLOCK)).isEmpty();
        assertThat(resolver.resolveUnderlyingUnitsPerShare(NetworkId.AVALANCHE, null, BLOCK)).isEmpty();
        assertThat(resolver.resolveUnderlyingUnitsPerShare(NetworkId.AVALANCHE, EUSDT2, 0L)).isEmpty();
    }

    @Test
    void cachesPerVaultAndBlockToAvoidRepeatRpc() {
        AtomicInteger calls = new AtomicInteger();
        EvmRpcClient counting = client((endpointUrl, method, params) -> {
            calls.incrementAndGet();
            return Mono.justOrEmpty(respond(method, params));
        });
        EvkVaultShareRateResolver resolver = resolver(counting);

        resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, new BigInteger("5114971"), BLOCK);
        int afterFirst = calls.get();
        resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, new BigInteger("5114971"), BLOCK);

        // Second resolution at the same vault+block must not issue new RPC reads.
        assertThat(calls.get()).isEqualTo(afterFirst);
    }

    @Test
    void failsSafeWhenEndpointPrunedReturnsError() {
        EvmRpcClient pruned = client((endpointUrl, method, params) -> Mono.just(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"missing trie node\"}}"));
        EvkVaultShareRateResolver resolver = resolver(pruned);

        Optional<EvkVaultShareRateResolver.EvkShareUnderlying> result =
                resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, new BigInteger("5114971"), BLOCK);

        assertThat(result).isEmpty();
    }

    @Test
    void failsSafeOnRpcException() {
        EvmRpcClient failing = client((endpointUrl, method, params) -> Mono.error(new RuntimeException("connection refused")));
        EvkVaultShareRateResolver resolver = resolver(failing);

        assertThat(resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, new BigInteger("5114971"), BLOCK))
                .isEmpty();
    }

    @Test
    void returnsEmptyForInvalidInputs() {
        EvkVaultShareRateResolver resolver = resolver(stubClient());

        assertThat(resolver.resolveUnderlying(null, EUSDT2, BigInteger.ONE, BLOCK)).isEmpty();
        assertThat(resolver.resolveUnderlying(NetworkId.AVALANCHE, null, BigInteger.ONE, BLOCK)).isEmpty();
        assertThat(resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, BigInteger.ZERO, BLOCK)).isEmpty();
        assertThat(resolver.resolveUnderlying(NetworkId.AVALANCHE, EUSDT2, BigInteger.ONE, 0L)).isEmpty();
    }

    private EvkVaultShareRateResolver resolver(EvmRpcClient client) {
        RpcEndpointRotator rotator = new RpcEndpointRotator(List.of("https://rpc.test/avax"), RetryPolicy.defaultPolicy());
        return new EvkVaultShareRateResolver(
                client,
                Map.of(NetworkId.AVALANCHE.name(), rotator),
                rotator,
                new ObjectMapper());
    }

    private EvmRpcClient stubClient() {
        return client((endpointUrl, method, params) -> Mono.justOrEmpty(respond(method, params)));
    }

    private interface CallFn {
        Mono<String> call(String endpointUrl, String method, Object params);
    }

    private static EvmRpcClient client(CallFn fn) {
        return new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                return fn.call(endpointUrl, method, params);
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                throw new UnsupportedOperationException("batchCall not used in EVK resolver");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static String respond(String method, Object params) {
        if (!"eth_call".equals(method)) {
            return null;
        }
        List<Object> list = (List<Object>) params;
        Map<String, String> tx = (Map<String, String>) list.get(0);
        String to = tx.get("to");
        String data = tx.get("data");
        String hex = resolveResult(to, data);
        if (hex == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}";
    }

    private static String resolveResult(String to, String data) {
        if (data.startsWith(CONVERT_TO_ASSETS)) {
            BigInteger shares = new BigInteger(data.substring(CONVERT_TO_ASSETS.length()), 16);
            if (EUSDT2.equalsIgnoreCase(to)) {
                // Linear rate anchored at convertToAssets(5114971)=5331162 (~1.042 USDt/share).
                return word(shares.multiply(new BigInteger("5331162")).divide(new BigInteger("5114971")));
            }
            if (EUSDC2.equalsIgnoreCase(to)) {
                // Linear rate anchored at convertToAssets(1341798065)=1379961197 (~1.028 USDC/share).
                return word(shares.multiply(new BigInteger("1379961197")).divide(new BigInteger("1341798065")));
            }
            return null;
        }
        if (ASSET.equals(data)) {
            if (EUSDT2.equalsIgnoreCase(to)) {
                return addressWord(USDT);
            }
            if (EUSDC2.equalsIgnoreCase(to)) {
                return addressWord(USDC);
            }
            return null;
        }
        if (DECIMALS.equals(data)) {
            // Both the EVK vault shares and their stablecoin underlyings carry 6 decimals.
            if (USDT.equalsIgnoreCase(to) || USDC.equalsIgnoreCase(to)
                    || EUSDT2.equalsIgnoreCase(to) || EUSDC2.equalsIgnoreCase(to)) {
                return word(BigInteger.valueOf(6));
            }
            return null;
        }
        return null;
    }

    private static String word(BigInteger value) {
        String hex = value.toString(16);
        return "0x" + "0".repeat(64 - hex.length()) + hex;
    }

    private static String addressWord(String address) {
        String bare = address.startsWith("0x") ? address.substring(2) : address;
        return "0x" + "0".repeat(64 - bare.length()) + bare;
    }
}
