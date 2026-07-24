package com.walletradar.application.costbasis.application.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.solana.JupiterSplTokenMetadataResolver;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.solana.SolanaRpcClient;
import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.jupiter.JupiterProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SolanaOnChainBalanceProviderTest {

    private static final String OWNER = "So1anaWa11etAbcDefGhiJkLmNoPqRsTuVwXyz12";
    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String UNKNOWN_MINT = "MemeMint1111111111111111111111111111111111";

    @Mock
    private SolanaRpcClient rpcClient;

    private SolanaOnChainBalanceProvider provider() {
        RpcEndpointRotator rotator = new RpcEndpointRotator(List.of("https://solana.rpc"), RetryPolicy.defaultPolicy());
        // Jupiter returns nothing: seeded USDC resolves via the static registry, the unknown mint
        // falls back to its raw mint as the symbol.
        JupiterClient jupiterClient = mock(JupiterClient.class);
        lenient().when(jupiterClient.fetchTokenMetadata(anyString())).thenReturn(Optional.empty());
        JupiterSplTokenMetadataResolver resolver =
                new JupiterSplTokenMetadataResolver(jupiterClient, new JupiterProperties());
        return new SolanaOnChainBalanceProvider(
                rpcClient,
                Map.of("SOLANA", rotator),
                rotator,
                new ObjectMapper(),
                resolver
        );
    }

    @Test
    void enumeratesNativeSolAndSplBalancesResolvingSeededSymbols() {
        lenient().when(rpcClient.call(anyString(), eq("getBalance"), any()))
                .thenReturn(Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"context\":{\"slot\":1},\"value\":2500000000}}"));
        lenient().when(rpcClient.call(anyString(), eq("getTokenAccountsByOwner"), any()))
                .thenReturn(Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":["
                        + tokenAccount(USDC_MINT, "1500000", 6)
                        + "," + tokenAccount(UNKNOWN_MINT, "1000000000", 9)
                        + "," + tokenAccount(USDC_MINT.replace('E', 'F'), "0", 6)
                        + "]}}"));

        List<OnChainBalanceProvider.ProviderBalance> balances = provider().fetchBalances(OWNER);

        assertThat(balances).hasSize(3);
        assertThat(balances)
                .filteredOn(OnChainBalanceProvider.ProviderBalance::nativeAsset)
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.assetSymbol()).isEqualTo("SOL");
                    assertThat(b.assetContract()).isEqualTo("NATIVE:SOLANA");
                    assertThat(b.quantity()).isEqualByComparingTo("2.5");
                });
        assertThat(balances)
                .filteredOn(b -> USDC_MINT.equals(b.assetContract()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.assetSymbol()).isEqualTo("USDC");
                    assertThat(b.quantity()).isEqualByComparingTo("1.5");
                });
        assertThat(balances)
                .filteredOn(b -> UNKNOWN_MINT.equals(b.assetContract()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.assetSymbol()).isEqualTo(UNKNOWN_MINT);
                    assertThat(b.quantity()).isEqualByComparingTo("1");
                });
    }

    @Test
    void returnsEmptyForBlankWallet() {
        assertThat(provider().fetchBalances("  ")).isEmpty();
    }

    private static String tokenAccount(String mint, String amount, int decimals) {
        return "{\"account\":{\"data\":{\"parsed\":{\"info\":{\"mint\":\"" + mint
                + "\",\"tokenAmount\":{\"amount\":\"" + amount + "\",\"decimals\":" + decimals + "}}}}}}";
    }
}
