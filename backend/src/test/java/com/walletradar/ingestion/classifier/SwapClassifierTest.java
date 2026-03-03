package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.config.ProtocolRegistryProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwapClassifierTest {

    /** Arbitrum USDC (native). */
    private static final String ARB_USDC = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
    /** Arbitrum WETH. */
    private static final String ARB_WETH = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
    /** Velora/Uniswap V3–style Swap with fees (so TransferClassifier yields to SwapClassifier). */
    private static final String SWAP_TOPIC_V3_FEES = "0x19b47279256b2a23a1665c810c8d55a1758940ee09377d4f8d26497a3577dc83";

    private SwapClassifier classifier;
    private ProtocolRegistry registry;

    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.of("0x7a250d5630b4cf539739df2c5dacb4c659f2488d", "Uniswap V2"));
        registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        classifier = new SwapClassifier(registry, evmTokenDecimalsResolver);
    }

    @Test
    void classify_noSwapLog_returnsEmpty() {
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", "0xToken")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, "0xfrom", walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).isEmpty();
    }

    @Test
    void classify_hasSwapLogAndTransfer_emitsSwapBuyAndSell() {
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String swapTopic = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("topics", List.of(swapTopic)),
                new Document("address", "0xTokenA")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, "0xfrom", walletTopic))
                        .append("data", "0xde0b6b3a7640000"),
                new Document("address", "0xTokenB")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, "0xto"))
                        .append("data", "0x1bc16d674ec80000")))); // 2e18

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(RawClassifiedEvent::getEventType))
                .containsExactlyInAnyOrder(EconomicEventType.SWAP_BUY, EconomicEventType.SWAP_SELL);
    }

    /**
     * Real Arbitrum tx: 0xf2155c128224d3ff0786b91f304685224dda0687b32343ccce8d2849f49e55c6
     * Wallet 0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f sent 20 USDC, received ~0.010139 WETH (aggregator).
     * Ensures SwapClassifier emits both SWAP_SELL (USDC) and SWAP_BUY (WETH) so enricher can set inline price.
     */
    @Test
    void classify_arbitrumUsdcToWethAggregator_emitsSwapSellUsdcAndSwapBuyWeth() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String poolOrRouterTopic = "0x0000000000000000000000007fcdc35463e3770c2fb992716cd070b63540b947";
        String routerFromTopic = "0x00000000000000000000000099794a30eac50663f79e16cba223e2764f701cd4";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(ARB_USDC))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(ARB_WETH))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(ARB_USDC))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(ARB_WETH))).thenReturn("WETH");

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xf2155c128224d3ff0786b91f304685224dda0687b32343ccce8d2849f49e55c6");
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("blockNumber", "0x19b4b0c2").append("logs", List.of(
                new Document("topics", List.of(SWAP_TOPIC_V3_FEES)),
                new Document("address", ARB_USDC)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolOrRouterTopic))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000001312d00"), // 20e6 = 20 USDC
                new Document("address", ARB_WETH)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, routerFromTopic, walletTopic))
                        .append("data", "0x" + "0".repeat(49) + "24094f8b90569b")))); // 10139529493319979 wei = ~0.010139529493319979 WETH

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(ARB_USDC);
        assertThat(sell.getAssetSymbol()).isEqualTo("USDC");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-20");
        assertThat(buy.getAssetContract()).isEqualTo(ARB_WETH);
        assertThat(buy.getAssetSymbol()).isEqualTo("WETH");
        assertThat(buy.getQuantityDelta()).isPositive();
        assertThat(buy.getQuantityDelta()).isBetween(new BigDecimal("0.01"), new BigDecimal("0.02"));
    }

    /**
     * Same tx as above but aggregator pays in native ETH (no ERC-20 Transfer to wallet).
     * SwapClassifier infers SWAP_BUY from Swap log (V3 fees: amount0, amount1).
     */
    @Test
    void classify_arbitrumUsdcToWeth_onlySellTransfer_emitsSwapBuyFromSwapLog() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String poolTopic = "0x0000000000000000000000007fcdc35463e3770c2fb992716cd070b63540b947";
        String routerTopic = "0x00000000000000000000000099794a30eac50663f79e16cba223e2764f701cd4";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(ARB_USDC))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(ARB_WETH))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(ARB_USDC))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(ARB_WETH))).thenReturn("WETH");

        // Swap log data: amount0 (int256) = -10139529493319979, amount1 (int256) = 20000000 (32 bytes each, hex)
        String amount0Hex = "ffffffffffffffffffffffffffffffffffffffffffffffffffdbfa26d25c6ed5"; // 2^256 - 10139529493319979
        String amount1Hex = "0000000000000000000000000000000000000000000000000000000001312d00"; // 20000000
        String swapData = "0x" + amount0Hex + amount1Hex + "0".repeat(64 * 5); // rest of Velora Swap fields

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xf2155c128224d3ff0786b91f304685224dda0687b32343ccce8d2849f49e55c6");
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("blockNumber", "0x19b4b0c2").append("logs", List.of(
                new Document("address", "0x7fcdc35463e3770c2fb992716cd070b63540b947")
                        .append("topics", List.of(SWAP_TOPIC_V3_FEES))
                        .append("data", swapData),
                new Document("address", ARB_USDC)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolTopic))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000001312d00"),
                new Document("address", ARB_WETH)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, routerTopic))
                        .append("data", "0x" + "0".repeat(49) + "24094f8b90569b"),
                new Document("address", ARB_WETH)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, routerTopic, "0x0000000000000000000000000000000000000000"))
                        .append("data", "0x" + "0".repeat(49) + "24094f8b90569b"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(ARB_USDC);
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-20");
        assertThat(buy.getAssetContract()).isEqualTo(ARB_WETH);
        assertThat(buy.getQuantityDelta()).isPositive();
        assertThat(buy.getQuantityDelta()).isBetween(new BigDecimal("0.01"), new BigDecimal("0.02"));
    }

    @Test
    void classify_usesExplorerTokenMetaFallback_whenRpcMetaMissing() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerTopic = "0x00000000000000000000000089c6340b1a1f4b25d36cd8b063d49045caf3f818";
        String stargateTopic = "0x0000000000000000000000001231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        String usdt0 = "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(usdt0))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(usdt0))).thenReturn("");
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(ARB_USDC))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(ARB_USDC))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0x4569887f1a59c086b5612072b1bf0e49c4059d7d171253a7ba6b0b947152e47d");
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", usdt0)
                        .append("tokenDecimal", "6")
                        .append("tokenSymbol", "USD₮0"),
                new Document("contractAddress", ARB_USDC)
                        .append("tokenDecimal", "6")
                        .append("tokenSymbol", "USDC")
        ))).append("logs", List.of(
                new Document("topics", List.of(SWAP_TOPIC_V3_FEES)),
                new Document("address", usdt0)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                        .append("data", "0x00000000000000000000000000000000000000000000000000000000336dcae2"),
                new Document("address", ARB_USDC)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, stargateTopic, walletTopic))
                        .append("data", "0x00000000000000000000000000000000000000000000000000000000336eb7d5")
        )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(usdt0);
        assertThat(sell.getAssetSymbol()).isEqualTo("USD₮0");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-862.833378000000000000");
        assertThat(buy.getAssetContract()).isEqualTo(ARB_USDC);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("862.894037000000000000");
    }

    @Test
    void classify_oneSwapBuyAndNativeValue_emitsSyntheticNativeSwapSell() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerFromTopic = "0x0000000000000000000000006a000f20005980200259b80c5102003040001068";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
        String swapTopic = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x6a000f20005980200259b80c5102003040001068")
                .append("value", "13443587162395032")
                .append("logs", List.of(
                        new Document("topics", List.of(swapTopic)),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, routerFromTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000002625a00")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        assertThat(buy.getAssetContract()).isEqualTo(usdc);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("40");
        assertThat(sell.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-0.013443587162395032");
    }

    @Test
    void classify_oneSwapSellWithoutCounterLeg_downgradesToExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String poolTopic = "0x0000000000000000000000000a2854fbbd9b3ef66f17d47284e7f899b9509330";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
        String swapTopic = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x9dda6ef3d919c9bc8885d5560999a3640431e8e6")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("topics", List.of(swapTopic)),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000000627b")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        RawClassifiedEvent outbound = result.getFirst();
        assertThat(outbound.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(outbound.getAssetContract()).isEqualTo(usdc);
        assertThat(outbound.getQuantityDelta()).isEqualByComparingTo("-0.025211");
    }
}
