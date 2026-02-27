package com.walletradar.ingestion.adapter.evm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvmNativeValueClarificationResolverTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String COUNTERPARTY = "0x00c600b30fb0400701010f4b080409018b9006e0";
    private static final String OTHER_COUNTERPARTY = "0x1111111111111111111111111111111111111111";
    private static final String OTHER_BURNER = "0xad89051bed8d96f045e8912ae1672c6c0bf8a85e";
    private static final String ARB_WETH = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ZERO_TOPIC = "0x0000000000000000000000000000000000000000000000000000000000000000";

    @Mock
    private EvmRpcClient evmRpcClient;
    @Mock
    private RateLimiter evmRpcRateLimiter;

    private EvmNativeValueClarificationResolver resolver;

    @BeforeEach
    void setUp() {
        RpcEndpointRotator defaultRotator = new RpcEndpointRotator(List.of("https://rpc.example"), null);
        resolver = new EvmNativeValueClarificationResolver(
                evmRpcClient,
                new ObjectMapper(),
                Map.of(NetworkId.ARBITRUM.name(), defaultRotator),
                defaultRotator,
                evmRpcRateLimiter
        );
        when(evmRpcRateLimiter.acquirePermission()).thenReturn(true);
    }

    @Test
    void clarify_usesTxValue_whenPositive() {
        NormalizedTransaction tx = pendingSwapWithoutBuy();
        when(evmRpcClient.call(any(), eq("eth_getTransactionByHash"), any()))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"value":"0x16345785d8a0000"}}
                        """));

        var result = resolver.clarify(tx);

        assertThat(result).isPresent();
        assertThat(result.get().inferenceReason()).isEqualTo("INFERRED_FROM_TX_VALUE");
        assertThat(result.get().inferredLegs()).hasSize(1);
        assertThat(result.get().inferredLegs().get(0).getQuantityDelta()).isEqualByComparingTo("0.1");
        verify(evmRpcClient, never()).call(any(), eq("eth_getTransactionReceipt"), any());
    }

    @Test
    void clarify_fallsBackToWrappedNativeUnwrap_whenTxValueIsZero() {
        NormalizedTransaction tx = pendingSwapWithoutBuy();
        when(evmRpcClient.call(any(), eq("eth_getTransactionByHash"), any()))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"value":"0x0"}}
                        """));
        when(evmRpcClient.call(any(), eq("eth_getTransactionReceipt"), any()))
                .thenReturn(Mono.just("""
                        {
                          "jsonrpc":"2.0",
                          "id":1,
                          "result":{
                            "logs":[
                              {
                                "address":"0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000000000000f42400"
                              },
                              {
                                "address":"%s",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000006b77977b933f8"
                              },
                              {
                                "address":"%s",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000017b36fe623f601"
                              }
                            ]
                          }
                        }
                        """.formatted(
                        TRANSFER_TOPIC, topic(WALLET), topic(COUNTERPARTY),
                        ARB_WETH, TRANSFER_TOPIC, topic(OTHER_BURNER), ZERO_TOPIC,
                        ARB_WETH, TRANSFER_TOPIC, topic(COUNTERPARTY), ZERO_TOPIC
                )));

        var result = resolver.clarify(tx);

        assertThat(result).isPresent();
        assertThat(result.get().inferenceReason()).isEqualTo("INFERRED_FROM_WRAPPED_NATIVE_UNWRAP");
        BigDecimal expected = new BigDecimal(new BigInteger("17b36fe623f601", 16))
                .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.HALF_UP);
        assertThat(result.get().inferredLegs().get(0).getQuantityDelta()).isEqualByComparingTo(expected);
    }

    @Test
    void clarify_returnsEmpty_whenWrappedNativeBurnIsAmbiguous() {
        NormalizedTransaction tx = pendingSwapWithoutBuy();
        when(evmRpcClient.call(any(), eq("eth_getTransactionByHash"), any()))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"value":"0x0"}}
                        """));
        when(evmRpcClient.call(any(), eq("eth_getTransactionReceipt"), any()))
                .thenReturn(Mono.just("""
                        {
                          "jsonrpc":"2.0",
                          "id":1,
                          "result":{
                            "logs":[
                              {
                                "address":"0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000000000000f42400"
                              },
                              {
                                "address":"0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000000000000f42400"
                              },
                              {
                                "address":"%s",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000000000000000001"
                              },
                              {
                                "address":"%s",
                                "topics":["%s","%s","%s"],
                                "data":"0x0000000000000000000000000000000000000000000000000000000000000002"
                              }
                            ]
                          }
                        }
                        """.formatted(
                        TRANSFER_TOPIC, topic(WALLET), topic(COUNTERPARTY),
                        TRANSFER_TOPIC, topic(WALLET), topic(OTHER_COUNTERPARTY),
                        ARB_WETH, TRANSFER_TOPIC, topic(COUNTERPARTY), ZERO_TOPIC,
                        ARB_WETH, TRANSFER_TOPIC, topic(OTHER_COUNTERPARTY), ZERO_TOPIC
                )));

        assertThat(resolver.clarify(tx)).isEmpty();
    }

    private static NormalizedTransaction pendingSwapWithoutBuy() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setTxHash("0x47e07d2d5560c415a75057ab18116b7d03eecb33f878eafb4d616b8561f413f8");
        tx.setWalletAddress(WALLET);
        tx.setType(NormalizedTransactionType.SWAP);
        NormalizedTransaction.Leg sell = new NormalizedTransaction.Leg();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetContract("0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        sell.setAssetSymbol("USDC");
        sell.setQuantityDelta(new BigDecimal("-16"));
        tx.setLegs(List.of(sell));
        return tx;
    }

    private static String topic(String address) {
        String hex = address.startsWith("0x") || address.startsWith("0X")
                ? address.substring(2)
                : address;
        return "0x" + "0".repeat(64 - hex.length()) + hex.toLowerCase();
    }
}
