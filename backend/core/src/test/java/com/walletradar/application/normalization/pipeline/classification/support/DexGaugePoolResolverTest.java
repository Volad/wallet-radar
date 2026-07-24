package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.RpcRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies on-chain v2/CL gauge grammar detection.
 *
 * <p>Evidence anchors (Optimism on-chain reads):
 * <ul>
 *   <li>Velodrome v2 (AMM) gauge {@code 0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad}: {@code nft()}
 *       reverts, {@code pool()} = the staked USD₮0/USDT AMM LP token
 *       {@code 0x4da46c6afe7322b66efefda1f702605cbe08e0bd} — a fungible v2 gauge.</li>
 *   <li>A CL/Slipstream gauge exposes {@code nft()} (the NonfungiblePositionManager) and must keep
 *       the NFPM + tokenId path (resolver returns empty).</li>
 * </ul>
 */
class DexGaugePoolResolverTest {

    private static final String POOL_SELECTOR = "0x" + EvmAbiSupport.selector("pool()");
    private static final String NFT_SELECTOR = "0x" + EvmAbiSupport.selector("nft()");

    private static final String V2_GAUGE = "0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad";
    private static final String STAKED_LP_TOKEN = "0x4da46c6afe7322b66efefda1f702605cbe08e0bd";
    private static final String CL_GAUGE = "0x1111111111111111111111111111111111111111";
    private static final String NFPM = "0x416b433906b1b72fa758e166e239c43d68dc6f29";

    @Test
    @DisplayName("v2 gauge (nft reverts, pool present) resolves to the staked AMM LP token")
    void v2GaugeResolvesStakedLpToken() {
        DexGaugePoolResolver resolver = resolver(client((endpoint, method, params) -> {
            String data = data(params);
            if (data.startsWith(NFT_SELECTOR)) {
                return revert(); // v2 gauge: nft() reverts
            }
            if (data.startsWith(POOL_SELECTOR)) {
                return Mono.just(okAddress(STAKED_LP_TOKEN));
            }
            return revert();
        }));

        Optional<String> resolved = resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, V2_GAUGE);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow()).isEqualToIgnoringCase(STAKED_LP_TOKEN);
    }

    @Test
    @DisplayName("CL gauge (nft present) resolves to empty so the NFPM + tokenId path is kept")
    void clGaugeKeepsNfpmPath() {
        DexGaugePoolResolver resolver = resolver(client((endpoint, method, params) -> {
            String data = data(params);
            if (data.startsWith(NFT_SELECTOR)) {
                return Mono.just(okAddress(NFPM)); // CL gauge: nft() returns the NFPM
            }
            return revert();
        }));

        assertThat(resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, CL_GAUGE)).isEmpty();
    }

    @Test
    @DisplayName("unresolvable gauge (both calls revert) fails safe to empty")
    void unresolvableGaugeIsEmpty() {
        DexGaugePoolResolver resolver = resolver(client((endpoint, method, params) -> revert()));

        assertThat(resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, V2_GAUGE)).isEmpty();
    }

    @Test
    @DisplayName("result (including the pool address) is cached per gauge to avoid repeat RPC")
    void cachesPerGauge() {
        AtomicInteger calls = new AtomicInteger();
        DexGaugePoolResolver resolver = resolver(client((endpoint, method, params) -> {
            calls.incrementAndGet();
            String data = data(params);
            if (data.startsWith(POOL_SELECTOR)) {
                return Mono.just(okAddress(STAKED_LP_TOKEN));
            }
            return revert();
        }));

        assertThat(resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, V2_GAUGE)).isPresent();
        int afterFirst = calls.get();
        assertThat(resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, V2_GAUGE)).isPresent();

        assertThat(calls.get()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("null / blank inputs fail safe to empty without RPC")
    void invalidInputsAreEmpty() {
        AtomicInteger calls = new AtomicInteger();
        DexGaugePoolResolver resolver = resolver(client((endpoint, method, params) -> {
            calls.incrementAndGet();
            return revert();
        }));

        assertThat(resolver.resolveFungibleGaugeStakedLpToken(null, V2_GAUGE)).isEmpty();
        assertThat(resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, null)).isEmpty();
        assertThat(resolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, "  ")).isEmpty();
        assertThat(calls.get()).isZero();
    }

    private DexGaugePoolResolver resolver(EvmRpcClient client) {
        RpcEndpointRotator rotator = new RpcEndpointRotator(
                List.of("https://rpc.test/optimism"), new RetryPolicy(0, 0, 1));
        return new DexGaugePoolResolver(
                client,
                new ObjectMapper(),
                Map.of(NetworkId.OPTIMISM.name(), rotator));
    }

    private static Mono<String> revert() {
        return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}");
    }

    private static String okAddress(String address) {
        String bare = address.startsWith("0x") ? address.substring(2) : address;
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + "0".repeat(64 - bare.length()) + bare + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static String data(Object params) {
        List<Object> list = (List<Object>) params;
        Map<String, String> tx = (Map<String, String>) list.get(0);
        return tx.get("data");
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
                throw new UnsupportedOperationException("batchCall not used by DexGaugePoolResolver");
            }
        };
    }
}
