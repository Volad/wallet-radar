package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.adapter.evm.EvmTokenDecimalsResolver;
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
        classifier = new TransferClassifier(registry, evmTokenDecimalsResolver);
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
}
