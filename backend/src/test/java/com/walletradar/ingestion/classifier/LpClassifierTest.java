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
class LpClassifierTest {

    private LpClassifier classifier;
    private TransferClassifier transferClassifier;

    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.ofEntries(
                Map.entry("0xba12222222228d8ba445958a75a0704d566bf2c8", "Balancer"),
                Map.entry("0xbb00000000000000000000000000000000000001", "Balancer"),
                Map.entry("0x46a15b0b27311cedf172ab29e4f4766fbe7f4364", "PancakeSwap V3"),
                Map.entry("0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3", "PancakeSwap V3"),
                Map.entry("0xc36442b4a4522e871399cd717abdd847ab11fe88", "Uniswap V3"),
                Map.entry("0x827922686190790b37229fd06084350e74485b72", "Aerodrome")
        ));
        ProtocolRegistry registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        classifier = new LpClassifier(registry, evmTokenDecimalsResolver);
        transferClassifier = new TransferClassifier(registry, evmTokenDecimalsResolver);
    }

    @Test
    void classify_lpEntryPattern_emitsLpEntryLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String weth = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        String bpt = "0xbb00000000000000000000000000000000000001";
        String poolTopic = "0x000000000000000000000000ba12222222228d8ba445958a75a0704d566bf2c8";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(weth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(bpt))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(weth))).thenReturn("WETH");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(bpt))).thenReturn("BPT");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba12222222228d8ba445958a75a0704d566bf2c8")
                .append("functionName", "joinPool(bytes32,address,address,tuple)")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000003b9aca00"), // 1_000 USDC
                        new Document("address", weth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, poolTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000"), // 1 WETH
                        new Document("address", bpt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000004563918244f40000") // 5 BPT
                ))
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", bpt).append("tokenSymbol", "BPT").append("tokenName", "Balancer Pool Token")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(3);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LP_ENTRY);
        assertThat(events.stream().filter(e -> e.getQuantityDelta().signum() < 0)).hasSize(2);
        assertThat(events.stream().filter(e -> e.getQuantityDelta().signum() > 0)).hasSize(1);
        assertThat(events.stream().anyMatch(e -> bpt.equals(e.getAssetContract()) && e.getQuantityDelta().compareTo(new BigDecimal("5")) == 0)).isTrue();
    }

    @Test
    void classify_lpExitPattern_emitsLpExitLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String weth = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        String bpt = "0xbb00000000000000000000000000000000000001";
        String poolTopic = "0x000000000000000000000000ba12222222228d8ba445958a75a0704d566bf2c8";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(weth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(bpt))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(usdc))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(weth))).thenReturn("WETH");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(bpt))).thenReturn("BPT");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba12222222228d8ba445958a75a0704d566bf2c8")
                .append("functionName", "exitPool(bytes32,address,address,tuple)")
                .append("logs", List.of(
                        new Document("address", bpt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000004563918244f40000"), // 5 BPT
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000003b9aca00"), // 1_000 USDC
                        new Document("address", weth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000") // 1 WETH
                ))
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", bpt).append("tokenSymbol", "BPT").append("tokenName", "Balancer Pool Token")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(3);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LP_EXIT);
        assertThat(events.stream().filter(e -> e.getQuantityDelta().signum() < 0)).hasSize(1);
        assertThat(events.stream().filter(e -> e.getQuantityDelta().signum() > 0)).hasSize(2);
        assertThat(events.stream().anyMatch(e -> bpt.equals(e.getAssetContract()) && e.getQuantityDelta().compareTo(new BigDecimal("-5")) == 0)).isTrue();
    }

    @Test
    void classify_lfjAddLiquidityWithoutLpMint_emitsNetLpEntryLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String pairTopic = "0x0000000000000000000000008573f98175d816d520248b5facf40d309b1c9cee";
        String router = "0x18556da13313f3532c54711497a8fedac273220e";
        String ausd = "0x00000000efe302beaa2b3e6e1b18d08d69a9012a";
        String usdc = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(ausd))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(ausd))).thenReturn("AUSD");
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", router)
                .append("methodId", "0xa3c7271a")
                .append("functionName", "addLiquidity((address tokenX,address tokenY,...))")
                .append("logs", List.of(
                        new Document("address", ausd)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, pairTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000001dc13018")
                                .append("logIndex", "0x1"),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, pairTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000001ddc29ea")
                                .append("logIndex", "0x2"),
                        new Document("address", ausd)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, pairTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000000003")
                                .append("logIndex", "0x3"),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, pairTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000000003")
                                .append("logIndex", "0x4")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LP_ENTRY);
        assertThat(events.stream().allMatch(e -> e.getQuantityDelta().signum() < 0)).isTrue();
        assertThat(events.stream()
                .filter(e -> ausd.equals(e.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("-499.200021");
        assertThat(events.stream()
                .filter(e -> usdc.equals(e.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("-500.967911");
        assertThat(transferEvents).isEmpty();
    }

    @Test
    void classify_lfjRemoveLiquidityWithoutLpBurn_emitsLpExitInflows() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String pairTopic = "0x0000000000000000000000008573f98175d816d520248b5facf40d309b1c9cee";
        String router = "0x18556da13313f3532c54711497a8fedac273220e";
        String ausd = "0x00000000efe302beaa2b3e6e1b18d08d69a9012a";
        String usdc = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(ausd))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(ausd))).thenReturn("AUSD");
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", router)
                .append("methodId", "0xc22159b6")
                .append("functionName", "removeLiquidity(address tokenX,address tokenY,uint16 binStep,...)")
                .append("logs", List.of(
                        new Document("address", ausd)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, pairTopic, walletTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000062b9fb5")
                                .append("logIndex", "0x1"),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, pairTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000035711ffd")
                                .append("logIndex", "0x2")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LP_EXIT);
        assertThat(events.stream().allMatch(e -> e.getQuantityDelta().signum() > 0)).isTrue();
        assertThat(events.stream()
                .filter(e -> ausd.equals(e.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("103.522229");
        assertThat(events.stream()
                .filter(e -> usdc.equals(e.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("896.606205");
        assertThat(transferEvents).isEmpty();
    }

    @Test
    void classify_lfjRemoveLiquidityWithoutLpBurn_isDeterministic() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String pairTopic = "0x0000000000000000000000008573f98175d816d520248b5facf40d309b1c9cee";
        String router = "0x18556da13313f3532c54711497a8fedac273220e";
        String ausd = "0x00000000efe302beaa2b3e6e1b18d08d69a9012a";
        String usdc = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(ausd))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(ausd))).thenReturn("AUSD");
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", router)
                .append("methodId", "0xc22159b6")
                .append("functionName", "removeLiquidity(address tokenX,address tokenY,uint16 binStep,...)")
                .append("logs", List.of(
                        new Document("address", ausd)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, pairTopic, walletTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000062b9fb5")
                                .append("logIndex", "0x1"),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, pairTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000035711ffd")
                                .append("logIndex", "0x2")
                )));

        List<RawClassifiedEvent> first = classifier.classify(tx, wallet);
        List<RawClassifiedEvent> second = classifier.classify(tx, wallet);

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Test
    void classify_lpPositionNftTransferBackToWallet_emitsLpPositionUnstake() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String strategyTopic = "0x000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000006a68d";
        String nftContract = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3")
                .append("input", "0x00f714ce000000000000000000000000000000000000000000000000000000000006a68d")
                .append("logs", List.of(
                        new Document("address", nftContract)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        strategyTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x5")
                )));

        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(nftContract))).thenReturn("PCS-V3-POS");

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_POSITION_UNSTAKE);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("1");
        assertThat(event.getAssetContract()).isEqualTo(nftContract);
        assertThat(event.getPositionId()).isEqualTo("435853");
    }

    @Test
    void classify_lpPositionNftTransferToStrategy_emitsLpPositionStake() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String strategyTopic = "0x000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000006a68d";
        String nftContract = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("input", "0x42842e0e0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3000000000000000000000000000000000000000000000000000000000006a68d")
                .append("logs", List.of(
                        new Document("address", nftContract)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        strategyTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x452")
                )));

        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(nftContract))).thenReturn("PCS-V3-POS");

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_POSITION_STAKE);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(event.getAssetContract()).isEqualTo(nftContract);
        assertThat(event.getPositionId()).isEqualTo("435853");
    }

    @Test
    void classify_noLogsSafeTransferFromCalldata_emitsLpPositionStake() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String nftContract = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", nftContract)
                .append("methodId", "0x")
                .append("input", "0x42842e0e0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                        + "000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3"
                        + "000000000000000000000000000000000000000000000000000000000006a68d"));

        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(nftContract))).thenReturn("PCS-V3-POS");

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_POSITION_STAKE);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(event.getPositionId()).isEqualTo("435853");
    }

    @Test
    void classify_detailsOnlyMintHintWithoutTransferEvidence_doesNotEmitEconomicLpEvents() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String nftContract = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", nftContract)
                .append("methodId", "0x")
                .append("input", "0x883164560000000000000000000000000000000000000000000000000000000000000000")
                .append("explorer", new Document("details", new Document("method", "mint"))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_collectCallWithInboundErc20_emitsLpFeeClaim() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String managerTopic = "0x00000000000000000000000046a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("input", "0xac9650d8...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, managerTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000020c403")
                                .append("logIndex", "0x9")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_FEE_CLAIM);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("2.147331");
        assertThat(event.getAssetContract()).isEqualTo(usdc);
    }

    @Test
    void classify_harvestSelectorWithTokenId_setsPositionId() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String strategyTopic = "0x000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
        String tokenIdWord = "000000000000000000000000000000000000000000000000000000000006a68d";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3")
                .append("input", "0x18fccc76" + tokenIdWord)
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, strategyTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000020c403")
                                .append("logIndex", "0x9")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_FEE_CLAIM);
        assertThat(event.getPositionId()).isEqualTo("435853");
    }

    @Test
    void classify_genericClaimWithoutLpEvidence_doesNotEmitLpEvents() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String distributorTopic = "0x00000000000000000000000094312a608246cecfce6811db84b3ef4b2619054e";
        String fluid = "0x61e030a56d33e8260fdd81f03b162a79fe3449cd";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(fluid))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(fluid))).thenReturn("FLUID");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x94312a608246cecfce6811db84b3ef4b2619054e")
                .append("methodId", "0xbe5013dc")
                .append("functionName", "claim(address recipient_,uint256 cumulativeAmount_,uint8 positionType_,bytes32 positionId_,uint256 cycle_,bytes32[] merkleProof_,bytes metadata_)")
                .append("input", "0xbe5013dc0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                .append("logs", List.of(
                        new Document("address", fluid)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, distributorTopic, walletTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000006bec5d422fb54d2")
                                .append("logIndex", "0x1")
                )));

        List<RawClassifiedEvent> lpEvents = classifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(lpEvents).isEmpty();
        assertThat(transferEvents).hasSize(1);
        assertThat(transferEvents.get(0).getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
    }

    @Test
    void classify_mintPositionWithOutboundTokens_emitsLpEntryEconomicLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String routerTopic = "0x000000000000000000000000b775272e537cc670c65dc852908ad47015244eaf";
        String positionManager = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String weth = "0x4200000000000000000000000000000000000006";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000006a68d";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(weth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(weth))).thenReturn("WETH");
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", positionManager)
                .append("input", "0x883164560000000000000000000000000000000000000000000000000000000000000000")
                .append("logs", List.of(
                        new Document("address", weth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000be3b65c392795e")
                                .append("logIndex", "0x1"),
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000002b92ae5c")
                                .append("logIndex", "0x2"),
                        new Document("address", positionManager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x3")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LP_ENTRY);
        assertThat(events).allMatch(e -> e.getQuantityDelta().signum() < 0);
        assertThat(events).allMatch(e -> "435853".equals(e.getPositionId()));
        assertThat(events).extracting(RawClassifiedEvent::getAssetContract)
                .containsExactlyInAnyOrder(weth, usdc);
    }

    @Test
    void classify_decreaseLiquidityAndCollect_prefersLpExitPartialOverFeeClaim() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String managerTopic = "0x00000000000000000000000046a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("input", "0xac9650d8...0c49ccbe...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, managerTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000035626f")
                                .append("logIndex", "0x9")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_EXIT_PARTIAL);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("3.498607");
        assertThat(event.getAssetContract()).isEqualTo(usdc);
    }

    @Test
    void classify_decreaseLiquidityWithPoolToManagerTransfer_emitsLpExitPartial() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String manager = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String managerTopic = "0x00000000000000000000000046a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String poolTopic = "0x00000000000000000000000072ab388e2e2f6facef59e3c3fa2c4e29011c2d38";
        String weth = "0x4200000000000000000000000000000000000006";
        String tokenIdWord = "00000000000000000000000000000000000000000000000000000000000e5309";
        String amountHex = "0x0000000000000000000000000000000000000000000000000b0cecc70775f92d";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(weth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(weth))).thenReturn("WETH");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("input", "0xac9650d8...0c49ccbe..." + tokenIdWord)
                .append("logs", List.of(
                        new Document("address", weth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, managerTopic))
                                .append("data", amountHex)
                                .append("logIndex", "0x1b9")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_EXIT_PARTIAL);
        assertThat(event.getAssetContract()).isEqualTo(weth);
        assertThat(event.getQuantityDelta().signum()).isPositive();
    }

    @Test
    void classify_decreaseLiquidityCollectAndBurn_emitsLpExitFinal() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String managerTopic = "0x00000000000000000000000046a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000006a68d";
        String positionManager = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";

        when(evmTokenDecimalsResolver.getDecimals(eq("BASE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("BASE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", positionManager)
                .append("input", "0xac9650d8...0c49ccbe...fc6f7865...42966c68...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, managerTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000035626f")
                                .append("logIndex", "0x9"),
                        new Document("address", positionManager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        zeroTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0xa")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_EXIT_FINAL);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("3.498607");
        assertThat(event.getAssetContract()).isEqualTo(usdc);
    }

    @Test
    void transferClassifier_skipsLpPositionPatternToAvoidDoubleCounting() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String strategyTopic = "0x000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000006a68d";
        String nftContract = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3")
                .append("input", "0x00f714ce000000000000000000000000000000000000000000000000000000000006a68d")
                .append("logs", List.of(
                        new Document("address", nftContract)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        strategyTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x5")
                )));

        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);
        assertThat(transferEvents).isEmpty();
    }
}
