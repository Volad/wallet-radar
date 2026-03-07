package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.config.ProtocolRegistryProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferClassifierTest {

    private static final String ZKSYNC_WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    private TransferClassifier classifier;
    private ProtocolRegistry registry;

    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.of());
        registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry zksyncEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        zksyncEntry.setSyntheticNativeContracts(List.of("0x000000000000000000000000000000000000800a"));
        ingestionNetworkProperties.setNetwork(Map.of("ZKSYNC", zksyncEntry));
        LendClassifier lendClassifier = new LendClassifier(registry, evmTokenDecimalsResolver, ingestionNetworkProperties);
        classifier = new TransferClassifier(registry, evmTokenDecimalsResolver, lendClassifier);
    }

    @Test
    void classify_emptyRawData_returnsEmpty() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xabc");
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document());

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).isEmpty();
    }

    @Test
    void classify_singleTransferOut_emitsExteriorTransferOut() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String toTopic = "0x" + "0".repeat(24) + "5678567856785678567856785678567856785678";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", "0xToken")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0xde0b6b3a7640000")))); // 1e18

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(result.get(0).getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(result.get(0).getWalletAddress()).isEqualTo(wallet);
    }

    @Test
    void classify_singleTransferIn_emitsExternalInbound() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String fromTopic = "0x" + "0".repeat(24) + "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, fromTopic, walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
        assertThat(result.get(0).getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_swapLogPresent_returnsEmpty() {
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String swapTopic = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("topics", List.of(swapTopic)),
                new Document("address", "0xToken")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, "0xfrom", walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).isEmpty();
    }

    // --- Heuristic swap (ADR-019, T-032) ---

    @Test
    void classify_oneAssetOutOneAssetInNoSwapTopic_emitsSwapSellAndSwapBuy() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
        String wbtc = "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599";
        String toTopic = "0x" + "0".repeat(24) + "5678567856785678567856785678567856785678";
        String fromTopic = "0x" + "0".repeat(24) + "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(wbtc))).thenReturn(8);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(wbtc))).thenReturn("WBTC");
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000005f5e100"), // 100 USDC (6 decimals)
                new Document("address", wbtc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, fromTopic, walletTopic))
                        .append("data", "0x00000000000000000000000000000000000000000000000000000000000186a0")))); // 0.001 WBTC (8 decimals) = 100000
        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(usdc);
        assertThat(sell.getAssetSymbol()).isEqualTo("USDC");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-100");
        assertThat(buy.getAssetContract()).isEqualTo(wbtc);
        assertThat(buy.getAssetSymbol()).isEqualTo("WBTC");
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("0.001");
    }

    @Test
    void classify_oneAssetOutAndMintInToWallet_emitsExternalTransfersNotSwap() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String zeroTopic = "0x" + "0".repeat(64);
        String usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
        String mcusdc = "0xA60643c90A542a95026c0f1dBdB0615Ff42019cF";
        String toTopic = "0x" + "0".repeat(24) + "5678567856785678567856785678567856785678";

        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(mcusdc))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(mcusdc))).thenReturn("MCUSDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000065ec8780"), // 1710 USDC
                new Document("address", mcusdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                        .append("data", "0x00000000000000000000000000000000000000000000005b957ffd62c2788569")))); // 1689.426318... MCUSDC

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(RawClassifiedEvent::getEventType))
                .containsExactlyInAnyOrder(EconomicEventType.EXTERNAL_TRANSFER_OUT, EconomicEventType.EXTERNAL_INBOUND);
        assertThat(result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL)).isEmpty();
        assertThat(result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY)).isEmpty();
    }

    @Test
    void classify_twoTransfersOutSameAssetOneIn_emitsOneSwapSellAggregatedAndOneSwapBuy() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
        String weth = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";
        String toA = "0x" + "0".repeat(24) + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String toB = "0x" + "0".repeat(24) + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String fromTopic = "0x" + "0".repeat(24) + "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(weth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(weth))).thenReturn("WETH");
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toA))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000005f5e100"), // 100 USDC
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toB))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000002faf080"), // 50 USDC
                new Document("address", weth)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, fromTopic, walletTopic))
                        .append("data", "0xde0b6b3a7640000")))); // 1 WETH
        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(usdc);
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-150"); // 100 + 50
        assertThat(buy.getAssetContract()).isEqualTo(weth);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_twoDistinctAssetsOutOneIn_emitsExternalTransferNotSwap() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
        String dai = "0x6B175474E89094C44Da98b954EedeAC495271d0F";
        String weth = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";
        String toTopic = "0x" + "0".repeat(24) + "5678567856785678567856785678567856785678";
        String fromTopic = "0x" + "0".repeat(24) + "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(dai))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(weth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(dai))).thenReturn("DAI");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(weth))).thenReturn("WETH");
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0xde0b6b3a7640000"),
                new Document("address", dai)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0xde0b6b3a7640000"),
                new Document("address", weth)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, fromTopic, walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));
        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).hasSize(3);
        assertThat(result.stream().map(RawClassifiedEvent::getEventType))
                .containsExactlyInAnyOrder(EconomicEventType.EXTERNAL_TRANSFER_OUT, EconomicEventType.EXTERNAL_TRANSFER_OUT, EconomicEventType.EXTERNAL_INBOUND);
        assertThat(result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL)).isEmpty();
        assertThat(result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY)).isEmpty();
    }

    @Test
    void classify_sameAssetOutAndIn_emitsExternalTransferNotSwap() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
        String toTopic = "0x" + "0".repeat(24) + "5678567856785678567856785678567856785678";
        String fromTopic = "0x" + "0".repeat(24) + "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document("logs", List.of(
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0xde0b6b3a7640000"),
                new Document("address", usdc)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, fromTopic, walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));
        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).hasSize(2);
        assertThat(result.stream().map(RawClassifiedEvent::getEventType))
                .containsExactlyInAnyOrder(EconomicEventType.EXTERNAL_TRANSFER_OUT, EconomicEventType.EXTERNAL_INBOUND);
        assertThat(result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL)).isEmpty();
        assertThat(result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY)).isEmpty();
    }

    @Test
    void classify_tokenOutPlusInternalNativeInAndSwapFunction_emitsSwapWithNativeBuy() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerTopic = "0x" + "0".repeat(24) + "00c600b30fb0400701010f4b080409018b9006e0";
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("functionName", "swapExactAmountIn(address executor,tuple swapData,uint256 partnerAndFee,bytes permit,bytes executorData)")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000f42400")))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", "0x6a000f20005980200259b80c5102003040001068")
                                .append("to", wallet)
                                .append("value", "8561799836019201")
                                .append("isError", "0")))));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();

        assertThat(sell.getAssetContract()).isEqualTo(usdc);
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-16");
        assertThat(buy.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(buy.getAssetSymbol()).isEqualTo("ETH");
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("0.008561799836019201");
    }

    @Test
    void classify_tokenOutPlusInternalNativeInAndSwapSelectorHint_emitsSwapWithNativeBuy() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerTopic = "0x" + "0".repeat(24) + "00c600b30fb0400701010f4b080409018b9006e0";
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("methodId", "0x07ed2379")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000f42400")))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", "0x6a000f20005980200259b80c5102003040001068")
                                .append("to", wallet)
                                .append("value", "8561799836019201")
                                .append("isError", "0")))));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(usdc);
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-16");
        assertThat(buy.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("0.008561799836019201");
    }

    @Test
    void classify_swapSelectorWithRefund_sameAssetInOut_emitsNetSwap() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerTopic = "0x" + "0".repeat(24) + "6a000f20005980200259b80c5102003040001068";
        String poolTopic = "0x" + "0".repeat(24) + "8573f98175d816d520248b5facf40d309b1c9cee";
        String usdc = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
        String ausd = "0x00000000efe302beaa2b3e6e1b18d08d69a9012a";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(ausd))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(ausd))).thenReturn("AUSD");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x6a000f20005980200259b80c5102003040001068")
                .append("methodId", "0x7f457675")
                .append("functionName", "swapExactAmountOut(address,tuple,uint256,bytes,bytes)")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000001dd3fc8d"), // 500.432013
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, routerTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000007a120"), // 0.500000
                        new Document("address", ausd)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000001dd23b00")  // 500.316928
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(usdc);
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-499.932013");
        assertThat(buy.getAssetContract()).isEqualTo(ausd);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("500.316928");
    }

    @Test
    void classify_tokenOutPlusInternalNativeInWithoutSwapFunction_emitsExternalTransferOut() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerTopic = "0x" + "0".repeat(24) + "00c600b30fb0400701010f4b080409018b9006e0";
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("functionName", "approve(address spender, uint256 amount) returns (bool)")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000f42400")))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", "0x6a000f20005980200259b80c5102003040001068")
                                .append("to", wallet)
                                .append("value", "8561799836019201")
                                .append("isError", "0")))));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(result.get(0).getQuantityDelta()).isEqualByComparingTo("-16");
    }

    @Test
    void classify_vaultDepositPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String underlying = "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9";
        String receipt = "0x4a03f37e7d3fc243e3f99341d36f4b829bee5e03";
        String protocolTopic = "0x" + "0".repeat(24) + "52aa899454998be5b000ad077a46bbe360f4e497";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet).append("to", receipt).append("logs", List.of(
                new Document("address", underlying)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, protocolTopic))
                        .append("data", "0x0000000000000000000000000000000000000000000000000000000077359400"),
                new Document("address", receipt)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                        .append("data", "0x000000000000000000000000000000000000000000000000000000006e38f5b5")
        )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_wrappedNativeDepositPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String wrappedNative = "0x4200000000000000000000000000000000000006";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", wrappedNative)
                .append("value", "10000000000000")
                .append("input", "0xd0e30db0")
                .append("logs", List.of(
                        new Document("address", wrappedNative)
                                .append("topics", List.of(
                                        "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c",
                                        walletTopic
                                ))
                                .append("data", "0x000000000000000000000000000000000000000000000000000009184e72a000"),
                        new Document("address", wrappedNative)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000009184e72a000")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_vaultWithdrawalPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String receipt = "0x1a996cb54bb95462040408c06122d45d6cdb6096";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String protocolTopic = "0x" + "0".repeat(24) + "52aa899454998be5b000ad077a46bbe360f4e497";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet).append("to", receipt).append("logs", List.of(
                new Document("address", receipt)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                        .append("data", "0x000000000000000000000000000000000000000000000000000000003738299f"),
                new Document("address", underlying)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, protocolTopic, walletTopic))
                        .append("data", "0x000000000000000000000000000000000000000000000000000000003b92ce63")
        )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_multicallVaultDepositPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String router = "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String receipt = "0x7e97fa6893871a2751b5fe961978dccb2c201e65";
        String protocolTopic = "0x" + "0".repeat(24) + "9954afb60bb5a222714c478ac86990f221788b88";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", router)
                .append("methodId", "0x374f435d")
                .append("functionName", "multicall(tuple[] bundle)")
                .append("logs", List.of(
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, protocolTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000337055c0"),
                        new Document("address", receipt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000002e80eba9a8fdcf6ece")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_lpEntryPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String weth = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        String bpt = "0xbb00000000000000000000000000000000000001";
        String poolTopic = "0x" + "0".repeat(24) + "ba12222222228d8ba445958a75a0704d566bf2c8";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba12222222228d8ba445958a75a0704d566bf2c8")
                .append("functionName", "joinPool(bytes32,address,address,tuple)")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolTopic))
                                .append("data", "0x1"),
                        new Document("address", weth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolTopic))
                                .append("data", "0x1"),
                        new Document("address", bpt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x1")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_lpExitPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String weth = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        String bpt = "0xbb00000000000000000000000000000000000001";
        String poolTopic = "0x" + "0".repeat(24) + "ba12222222228d8ba445958a75a0704d566bf2c8";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba12222222228d8ba445958a75a0704d566bf2c8")
                .append("functionName", "exitPool(bytes32,address,address,tuple)")
                .append("logs", List.of(
                        new Document("address", bpt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x1"),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x1"),
                        new Document("address", weth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x1")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_lpExitFromPositionContext_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String managerTopic = "0x" + "0".repeat(24) + "46a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("input", "0xac9650d8...0c49ccbe...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, managerTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000035626f")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_claimWithWrapAndBurn_emitsOnlyNetInboundRewards() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String tokenA = "0x6533afac2e7bccb20dca161449a13a32d391fb00";
        String wrapped = "0x2c63f9da936624ac7313b972251d340260a4bf08";
        String arb = "0x912ce59144191c1204e64559fe8253a0e49e6548";
        String morpho = "0x40bd670a58238e6e230c430bbb5ce6ec0d40df48";
        String distributorTopic = "0x" + "0".repeat(24) + "3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        String defiTopic = "0x" + "0".repeat(24) + "def1fa4cefe67365ba046a7c630d6b885298e210";

        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(tokenA))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(wrapped))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(arb))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(anyString(), eq(morpho))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(tokenA))).thenReturn("aArbARB");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(wrapped))).thenReturn("aArbARBw");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(arb))).thenReturn("ARB");
        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(morpho))).thenReturn("MORPHO");

        String wrappedAmountHex = "0x" + String.format("%064x", new java.math.BigInteger("13585115969566921272"));
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("methodId", "0x71ee95c0")
                .append("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)")
                .append("logs", List.of(
                        new Document("address", tokenA)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, defiTopic, walletTopic))
                                .append("data", wrappedAmountHex),
                        new Document("address", wrapped)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, distributorTopic, walletTopic))
                                .append("data", wrappedAmountHex),
                        new Document("address", wrapped)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", wrappedAmountHex),
                        new Document("address", arb)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, distributorTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000002e3f2d3c2a1b0d4d"),
                        new Document("address", morpho)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, distributorTopic, walletTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000003c274f16a8cf577")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(e -> e.getEventType() == EconomicEventType.EXTERNAL_INBOUND);
        assertThat(result).allMatch(e -> e.getQuantityDelta().signum() > 0);
        assertThat(result.stream().map(RawClassifiedEvent::getAssetContract))
                .containsExactlyInAnyOrder(tokenA, arb, morpho);
        assertThat(result.stream().noneMatch(e -> wrapped.equalsIgnoreCase(e.getAssetContract()))).isTrue();
    }

    @Test
    void classify_batchWrappedRedeemPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String batchRouter = "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066";
        String receipt = "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String receiptTopic = "0x" + "0".repeat(24) + "44c10da836d2abe881b77bbb0b3dce5f85c0c1cc";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", batchRouter)
                .append("methodId", "0xc16ae7a4")
                .append("functionName", "batch(tuple[] items)")
                .append("logs", List.of(
                        new Document("address", receipt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000002baf296c"),
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, receiptTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000002c7750e1")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_aaveSupplyPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String underlying = "0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f";
        String receipt = "0x078f358208685046a11c85e8ad32895ded33a249";
        String receiptTopic = "0x" + "0".repeat(24) + "078f358208685046a11c85e8ad32895ded33a249";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", pool)
                .append("methodId", "0x617ba037")
                .append("functionName", "supply(address asset, uint256 amount, address onBehalfOf, uint16 referralCode)")
                .append("logs", List.of(
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, receiptTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000000291e"),
                        new Document("address", receipt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000000291f")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).isEmpty();
    }

    @Test
    void classify_aaveBorrowPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String reserveTopic = "0x" + "0".repeat(24) + "078f358208685046a11c85e8ad32895ded33a249";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String debtToken = "0x1234567890abcdef1234567890abcdef12345678";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", pool)
                .append("methodId", "0xa415bcad")
                .append("functionName", "borrow(address asset, uint256 amount, uint256 interestRateMode, uint16 referralCode, address onBehalfOf)")
                .append("logs", List.of(
                        new Document("address", debtToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000005f5e1"),
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, reserveTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000005f5e0")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).isEmpty();
    }

    @Test
    void classify_aaveRepayPattern_returnsEmptyForTransferClassifier() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String reserveTopic = "0x" + "0".repeat(24) + "078f358208685046a11c85e8ad32895ded33a249";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String debtToken = "0x1234567890abcdef1234567890abcdef12345678";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", pool)
                .append("methodId", "0x573ade81")
                .append("functionName", "repay(address asset, uint256 amount, uint256 rateMode, address onBehalfOf)")
                .append("logs", List.of(
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, reserveTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000003d0900"),
                        new Document("address", debtToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000003d0901")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);
        assertThat(result).isEmpty();
    }

    @Test
    void classify_nftMintWithNativeValue_emitsSwapSellNativeAndSwapBuyNft() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String nft = "0xc2f1602769ac3a2d3b3c8eeb56afca32d57fadb8";

        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(nft))).thenReturn("PIN");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", nft)
                .append("value", "330000000000000")
                .append("logs", List.of(
                        new Document("address", nft)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        "0x0000000000000000000000000000000000000000000000000000000000003c90"
                                ))
                                .append("data", "0x")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(2);
        RawClassifiedEvent sell = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = result.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();

        assertThat(sell.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(sell.getAssetSymbol()).isEqualTo("ETH");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-0.00033");
        assertThat(buy.getAssetContract()).isEqualTo(nft);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_nftMintWithoutNativeValue_emitsExternalInbound() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x" + "0".repeat(24) + "1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x" + "0".repeat(64);
        String nft = "0x03c4738ee98ae44591e1a4a4f3cab6641d95dd9a";

        when(evmTokenDecimalsResolver.getSymbol(anyString(), eq(nft))).thenReturn("BASENAME");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xa7d2607c6bd39ae9521e514026cbb078405ab322")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("address", nft)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        "0x0000000000000000000000000000000000000000000000000000000000000001"
                                ))
                                .append("data", "0x")
                )));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        RawClassifiedEvent inbound = result.getFirst();
        assertThat(inbound.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
        assertThat(inbound.getAssetContract()).isEqualTo(nft);
        assertThat(inbound.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_zkSyncDepositWithExtraNativeLegs_returnsEmptyBecauseLendTakesPrecedence() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/lend-deposit-extra-native-legs.json");

        List<RawClassifiedEvent> result = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_zkSyncWithdrawWithExtraNativeLegs_returnsEmptyBecauseLendTakesPrecedence() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/lend-withdraw-extra-native-legs.json");

        List<RawClassifiedEvent> result = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_zkSyncBorrowWithExtraNativeLegs_returnsEmptyBecauseLendTakesPrecedence() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/borrow-extra-native-legs.json");

        List<RawClassifiedEvent> result = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_zkSyncRepayWithExtraNativeLegs_returnsEmptyBecauseLendTakesPrecedence() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/repay-extra-native-legs.json");

        List<RawClassifiedEvent> result = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(result).isEmpty();
    }
}
