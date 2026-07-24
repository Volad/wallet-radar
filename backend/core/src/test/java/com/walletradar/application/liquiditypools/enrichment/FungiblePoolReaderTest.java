package com.walletradar.application.liquiditypools.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.RpcRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies fungible LP-token pair resolution.
 *
 * <p>Evidence anchor: the Velodrome v2 gauge {@code 0xbc6043a5…} on Optimism stakes the AMM LP token
 * {@code 0x4da46c6a…} whose {@code token0()} = USD₮0 and {@code token1()} = USDT. The reader must
 * surface the two-sided pair (not the LP token's own symbol) and value the staked balance from the
 * gauge, since the wallet's direct LP balance is 0 while staked.
 */
class FungiblePoolReaderTest {

    private static final String BALANCE_OF = "0x" + EvmAbiSupport.selector("balanceOf(address)");
    private static final String SYMBOL = "0x" + EvmAbiSupport.selector("symbol()");
    private static final String DECIMALS = "0x" + EvmAbiSupport.selector("decimals()");
    private static final String TOTAL_SUPPLY = "0x" + EvmAbiSupport.selector("totalSupply()");
    private static final String GET_RESERVES = "0x" + EvmAbiSupport.selector("getReserves()");
    private static final String TOKEN0 = "0x" + EvmAbiSupport.selector("token0()");
    private static final String TOKEN1 = "0x" + EvmAbiSupport.selector("token1()");

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String GAUGE = "0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad";
    private static final String LP_TOKEN = "0x4da46c6afe7322b66efefda1f702605cbe08e0bd";
    private static final String TOKEN_A = "0x01bff41798a0bcf287b996046ca68b395dbc1071"; // USD₮0
    private static final String TOKEN_B = "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58"; // USDT
    private static final String PLAIN_ERC20 = "0x2222222222222222222222222222222222222222";

    @Test
    @DisplayName("AMM LP token exposing token0()/token1() yields a two-sided pair from staked balance")
    void ammLpTokenYieldsTwoSidedPair() {
        FungiblePoolReader reader = new FungiblePoolReader(rpc(client((endpoint, method, params) -> {
            String to = to(params);
            String data = data(params);
            if (LP_TOKEN.equalsIgnoreCase(to) && data.startsWith(TOKEN0)) {
                return Mono.just(okAddress(TOKEN_A));
            }
            if (LP_TOKEN.equalsIgnoreCase(to) && data.startsWith(TOKEN1)) {
                return Mono.just(okAddress(TOKEN_B));
            }
            // Staked balance lives on the gauge; wallet's direct LP balance is 0.
            if (GAUGE.equalsIgnoreCase(to) && data.startsWith(BALANCE_OF)) {
                return Mono.just(okUint(1_000));
            }
            if (LP_TOKEN.equalsIgnoreCase(to) && data.startsWith(TOTAL_SUPPLY)) {
                return Mono.just(okUint(10_000));
            }
            if (LP_TOKEN.equalsIgnoreCase(to) && data.startsWith(GET_RESERVES)) {
                return Mono.just(okReserves(1_000_000, 2_000_000));
            }
            if (data.startsWith(DECIMALS)) {
                return Mono.just(okUint(6));
            }
            if (TOKEN_A.equalsIgnoreCase(to) && data.startsWith(SYMBOL)) {
                return Mono.just(okString("USDT0"));
            }
            if (TOKEN_B.equalsIgnoreCase(to) && data.startsWith(SYMBOL)) {
                return Mono.just(okString("USDT"));
            }
            return revert();
        })));

        LpPositionContext context = vaultContext(LP_TOKEN, GAUGE, true);
        Optional<LpPositionSnapshot> result = reader.read(context);

        assertThat(result).isPresent();
        LpPositionSnapshot snapshot = result.orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo("in_range");
        assertThat(snapshot.getStaked()).isTrue();
        assertThat(snapshot.getToken0()).isNotNull();
        assertThat(snapshot.getToken1()).isNotNull();
        assertThat(snapshot.getToken0().getSym()).isEqualTo("USDT0");
        assertThat(snapshot.getToken0().getContract()).isEqualToIgnoringCase(TOKEN_A);
        assertThat(snapshot.getToken1().getSym()).isEqualTo("USDT");
        assertThat(snapshot.getToken1().getContract()).isEqualToIgnoringCase(TOKEN_B);
        // share = 1000 / 10000 = 0.1; reserve0 = 1.0, reserve1 = 2.0 (6 decimals)
        assertThat(snapshot.getToken0().getQty()).isEqualByComparingTo("0.1");
        assertThat(snapshot.getToken1().getQty()).isEqualByComparingTo("0.2");
    }

    @Test
    @DisplayName("plain ERC-20 (no token0()/token1(), no reserves) fabricates no pair")
    void plainErc20YieldsNoFabricatedPair() {
        FungiblePoolReader reader = new FungiblePoolReader(rpc(client((endpoint, method, params) -> {
            String data = data(params);
            if (data.startsWith(TOTAL_SUPPLY)) {
                return Mono.just(okUint(10_000));
            }
            if (data.startsWith(BALANCE_OF)) {
                return Mono.just(okUint(0)); // fully staked / not held directly
            }
            // token0()/token1()/getReserves() all revert on a plain ERC-20.
            return revert();
        })));

        LpPositionContext context = vaultContext(PLAIN_ERC20, null, false);

        assertThat(reader.read(context)).isEmpty();
    }

    private static LpPositionContext vaultContext(String lpToken, String gauge, boolean staked) {
        String correlationId = gauge == null
                ? "lp-position:optimism:" + lpToken + ":vault"
                : "lp-position:optimism:" + lpToken + ":vault:" + gauge;
        return new LpPositionContext(
                correlationId,
                "universe-1",
                NetworkId.OPTIMISM,
                WALLET,
                "Velodrome",
                "FUNGIBLE_LP",
                lpToken,
                gauge,
                "vault",
                lpToken,
                null,
                null,
                false,
                staked,
                null);
    }

    private LpRpcSupport rpc(EvmRpcClient client) {
        RpcEndpointRotator rotator = new RpcEndpointRotator(
                List.of("https://rpc.test/optimism"), new RetryPolicy(0, 0, 1));
        return new LpRpcSupport(client, new ObjectMapper(), Map.of(NetworkId.OPTIMISM.name(), rotator));
    }

    private static Mono<String> revert() {
        return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}");
    }

    private static String okUint(long value) {
        return result(word(BigInteger.valueOf(value)));
    }

    private static String okAddress(String address) {
        String bare = address.startsWith("0x") ? address.substring(2) : address;
        return result("0x" + "0".repeat(64 - bare.length()) + bare);
    }

    private static String okReserves(long reserve0, long reserve1) {
        String hex = word(BigInteger.valueOf(reserve0)).substring(2)
                + word(BigInteger.valueOf(reserve1)).substring(2)
                + word(BigInteger.ZERO).substring(2);
        return result("0x" + hex);
    }

    /** ABI-encodes a dynamic string return value: offset | length | data. */
    private static String okString(String symbol) {
        byte[] bytes = symbol.getBytes(StandardCharsets.US_ASCII);
        String dataHex = HexFormat.of().formatHex(bytes);
        int paddedBytes = ((bytes.length + 31) / 32) * 32;
        dataHex = dataHex + "0".repeat(paddedBytes * 2 - dataHex.length());
        String offset = word(BigInteger.valueOf(32)).substring(2);
        String length = word(BigInteger.valueOf(bytes.length)).substring(2);
        return result("0x" + offset + length + dataHex);
    }

    private static String result(String hex) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}";
    }

    private static String word(BigInteger value) {
        String hex = value.toString(16);
        return "0x" + "0".repeat(64 - hex.length()) + hex;
    }

    @SuppressWarnings("unchecked")
    private static String to(Object params) {
        List<Object> list = (List<Object>) params;
        Map<String, String> tx = (Map<String, String>) list.get(0);
        return tx.get("to");
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
                throw new UnsupportedOperationException("batchCall not used by FungiblePoolReader");
            }
        };
    }
}
