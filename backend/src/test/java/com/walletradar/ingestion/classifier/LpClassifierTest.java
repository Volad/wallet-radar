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
                Map.entry("0x55f4c8aba71a1e923edc303eb4feff14608cc226", "Pancake Infinity"),
                Map.entry("0x46a15b0b27311cedf172ab29e4f4766fbe7f4364", "PancakeSwap V3"),
                Map.entry("0xa815e2ed7f7d5b0c49fda367f249232a1b9d2883", "PancakeSwap V3"),
                Map.entry("0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3", "PancakeSwap V3"),
                Map.entry("0xc36442b4a4522e871399cd717abdd847ab11fe88", "Uniswap V3"),
                Map.entry("0x943e6e07a7e8e791dafc44083e54041d743c46e9", "Uniswap V3"),
                Map.entry("0x4529a01c7a0410167c5740c487a8de60232617bf", "Uniswap V4"),
                Map.entry("0x1f98400000000000000000000000000000000004", "Uniswap V4"),
                Map.entry("0x827922686190790b37229fd06084350e74485b72", "Aerodrome"),
                Map.entry("0x416b433906b1b72fa758e166e239c43d68dc6f29", "Velodrome Slipstream"),
                Map.entry("0x991d5546c4b442b4c5fdc4c8b8b8d131deb24702", "Slipstream")
        ));
        ProtocolRegistry registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry zksyncEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        zksyncEntry.setSyntheticNativeContracts(List.of("0x000000000000000000000000000000000000800a"));
        ingestionNetworkProperties.setNetwork(Map.of("ZKSYNC", zksyncEntry));
        LendClassifier lendClassifier = new LendClassifier(registry, evmTokenDecimalsResolver, ingestionNetworkProperties);
        classifier = new LpClassifier(registry, evmTokenDecimalsResolver, lendClassifier);
        transferClassifier = new TransferClassifier(registry, evmTokenDecimalsResolver, lendClassifier);
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
    void classify_pancakeInfinityBscMintPositionWithOutboundToken_emitsLpEntryEconomicLeg() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String infinityPositionManager = "0x55f4c8aba71a1e923edc303eb4feff14608cc226";
        String xyz = "0x9e9035aafecb30cfd5355a10f93a270e33bc4293";
        String vaultTopic = "0x000000000000000000000000238a358808379702088667322f80ac48bad5e6c4";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000009d352";

        when(evmTokenDecimalsResolver.getDecimals(eq("BSC"), eq(xyz))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("BSC"), eq(xyz))).thenReturn("XYZ");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BSC");
        tx.setRawData(new Document("from", wallet)
                .append("to", infinityPositionManager)
                .append("logs", List.of(
                        new Document("address", infinityPositionManager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0xc9"),
                        new Document("address", xyz)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        vaultTopic
                                ))
                                .append("data", "0x000000000000000000000000000000000000000000000fab8c4c3b325a3fffed")
                                .append("logIndex", "0xcd")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_ENTRY);
        assertThat(event.getAssetContract()).isEqualTo(xyz);
        assertThat(event.getAssetSymbol()).isEqualTo("XYZ");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-73999.999999999999999981");
        assertThat(event.getPositionId()).isEqualTo("643922");
        assertThat(event.getProtocolName()).isEqualTo("pancake infinity");
    }

    @Test
    void classify_unichainUniswapStylePositionManagerMintWithOutboundToken_emitsLpEntryEconomicLeg() {
        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String walletTopic = "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String manager = "0x943e6e07a7e8e791dafc44083e54041d743c46e9";
        String managerTopic = "0x000000000000000000000000943e6e07a7e8e791dafc44083e54041d743c46e9";
        String routerTopic = "0x00000000000000000000000065081cb48d74a32e9ccfed75164b8c09972dbcf1";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String usdc = "0x078d782b760474a361dda0af3839290b0ef57ad6";
        String weth = "0x4200000000000000000000000000000000000006";
        String tokenIdTopic = "0x0000000000000000000000000000000000000000000000000000000000000338";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("value", "207999999871136881")
                .append("input", "0xac9650d8")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        routerTopic
                                ))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000001eaecb7c")
                                .append("logIndex", "0x0"),
                        new Document("address", weth)
                                .append("topics", List.of(
                                        "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c",
                                        managerTopic
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000002e2bfbfd8e5358c")
                                .append("logIndex", "0x1"),
                        new Document("address", weth)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        managerTopic,
                                        routerTopic
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000002e2bfbfd8e5358c")
                                .append("logIndex", "0x2"),
                        new Document("address", manager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x4")
                ))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", manager)
                                .append("to", wallet)
                                .append("value", "60636360113893")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(RawClassifiedEvent::getEventType)
                .containsOnly(EconomicEventType.LP_ENTRY);
        assertThat(events).extracting(RawClassifiedEvent::getPositionId)
                .containsOnly("824");
        assertThat(events).extracting(RawClassifiedEvent::getAssetContract)
                .containsExactlyInAnyOrder(usdc, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

        RawClassifiedEvent usdcLeg = events.stream()
                .filter(event -> usdc.equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow();
        assertThat(usdcLeg.getAssetSymbol()).isEqualTo("USDC");
        assertThat(usdcLeg.getQuantityDelta()).isEqualByComparingTo("-514.771836");
        assertThat(usdcLeg.getProtocolName()).isEqualTo("uniswap v3");

        RawClassifiedEvent ethLeg = events.stream()
                .filter(event -> "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee".equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow();
        assertThat(ethLeg.getAssetSymbol()).isEqualTo("ETH");
        assertThat(ethLeg.getQuantityDelta()).isEqualByComparingTo("-0.207939363511022988");
        assertThat(ethLeg.getLogIndex()).isEqualTo(1);
        assertThat(ethLeg.getProtocolName()).isEqualTo("uniswap v3");
    }

    @Test
    void classify_unichainV3PartialExit_withInternalNativeSweep_emitsUsdcAndEth() {
        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String walletTopic = "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String manager = "0x943e6e07a7e8e791dafc44083e54041d743c46e9";
        String usdc = "0x078d782b760474a361dda0af3839290b0ef57ad6";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("input", "0xac9650d8...0c49ccbe...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        "0x000000000000000000000000943e6e07a7e8e791dafc44083e54041d743c46e9",
                                        walletTopic
                                ))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000001d312d4e")
                ))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", manager)
                                .append("to", wallet)
                                .append("value", "252982718838557593")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(RawClassifiedEvent::getEventType)
                .containsOnly(EconomicEventType.LP_EXIT_PARTIAL);
        assertThat(events).extracting(RawClassifiedEvent::getAssetContract)
                .containsExactlyInAnyOrder(usdc, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(events.stream()
                .filter(event -> usdc.equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("489.762126");
        assertThat(events.stream()
                .filter(event -> "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee".equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("0.252982718838557593");
    }

    @Test
    void classify_unichainV3FeeClaim_withInternalNativeSweep_emitsUsdcAndEth() {
        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String walletTopic = "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String manager = "0x943e6e07a7e8e791dafc44083e54041d743c46e9";
        String usdc = "0x078d782b760474a361dda0af3839290b0ef57ad6";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("input", "0xac9650d8...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        "0x000000000000000000000000943e6e07a7e8e791dafc44083e54041d743c46e9",
                                        walletTopic
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000004bf3de")
                ))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", manager)
                                .append("to", wallet)
                                .append("value", "1851514780985365")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(RawClassifiedEvent::getEventType)
                .containsOnly(EconomicEventType.LP_FEE_CLAIM);
        assertThat(events).extracting(RawClassifiedEvent::getAssetContract)
                .containsExactlyInAnyOrder(usdc, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(events.stream()
                .filter(event -> usdc.equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("4.977630");
        assertThat(events.stream()
                .filter(event -> "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee".equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("0.001851514780985365");
    }

    @Test
    void classify_knownManagerIncreaseLiquidity_withoutNftMint_emitsLpEntry() {
        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String walletTopic = "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String manager = "0x943e6e07a7e8e791dafc44083e54041d743c46e9";
        String managerTopic = "0x000000000000000000000000943e6e07a7e8e791dafc44083e54041d743c46e9";
        String routerTopic = "0x00000000000000000000000065081cb48d74a32e9ccfed75164b8c09972dbcf1";
        String usdc = "0x078d782b760474a361dda0af3839290b0ef57ad6";
        String weth = "0x4200000000000000000000000000000000000006";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("value", "19999999577008510")
                .append("input", "0xac9650d8...219f5d17...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        routerTopic
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000029ccd57")
                                .append("logIndex", "0x0"),
                        new Document("address", weth)
                                .append("topics", List.of(
                                        "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c",
                                        managerTopic
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000000470cfcf90d4b4d")
                                .append("logIndex", "0x1"),
                        new Document("address", weth)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        managerTopic,
                                        routerTopic
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000000470cfcf90d4b4d")
                                .append("logIndex", "0x2"),
                        new Document("address", "0x65081cb48d74a32e9ccfed75164b8c09972dbcf1")
                                .append("topics", List.of(
                                        "0x7a53080ba414158be7ec69b987b5fb7d07dee101fe85488f0853ae16239d0bde",
                                        managerTopic,
                                        "0x000000000000000000000000000000000000000000000000000000000002fd5a",
                                        "0x00000000000000000000000000000000000000000000000000000000000305c0"
                                ))
                                .append("data", "0x00")
                                .append("logIndex", "0x3"),
                        new Document("address", manager)
                                .append("topics", List.of(
                                        "0x3067048beee31b25b2f1681f88dac838c8bba36af25bfb2b7cf7473a5847e35f",
                                        "0x0000000000000000000000000000000000000000000000000000000000000338"
                                ))
                                .append("data", "0x00")
                                .append("logIndex", "0x4")
                ))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", manager)
                                .append("to", wallet)
                                .append("value", "995580862001")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(RawClassifiedEvent::getEventType)
                .containsOnly(EconomicEventType.LP_ENTRY);
        assertThat(events).extracting(RawClassifiedEvent::getAssetContract)
                .containsExactlyInAnyOrder(usdc, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
    }

    @Test
    void classify_unichainV4PositionManagerMint_doesNotEmitPositionNftEconomicLeg() {
        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String walletTopic = "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String manager = "0x4529a01c7a0410167c5740c487a8de60232617bf";
        String usdt0 = "0x9151434b16b9763660705744891fa906f660ecc5";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdt0))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdt0))).thenReturn("USD₮0");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("value", "615779357568571248")
                .append("input", "0xac9650d8")
                .append("logs", List.of(
                        new Document("address", "0x000000000022d473030f116ddee9f6b43ac78ba3")
                                .append("topics", List.of(
                                        "0xc6a377bfc4eb120024a8ac08eef205be16b817020812c73223e81d1bdb9708ec",
                                        walletTopic,
                                        "0x0000000000000000000000009151434b16b9763660705744891fa906f660ecc5",
                                        "0x0000000000000000000000004529a01c7a0410167c5740c487a8de60232617bf"
                                ))
                                .append("data", "0x00")
                                .append("logIndex", "0x0"),
                        new Document("address", manager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        "0x000000000000000000000000000000000000000000000000000000000000a717"
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x1"),
                        new Document("address", "0x1f98400000000000000000000000000000000004")
                                .append("topics", List.of(
                                        "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec",
                                        "0x04b7dd024db64cfbe325191c818266e4776918cd9eaf021c26949a859e654b16",
                                        "0x0000000000000000000000004529a01c7a0410167c5740c487a8de60232617bf"
                                ))
                                .append("data", "0x00")
                                .append("logIndex", "0x2"),
                        new Document("address", usdt0)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        "0x0000000000000000000000001f98400000000000000000000000000000000004"
                                ))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000002fc52806")
                                .append("logIndex", "0x3")
                ))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", manager)
                                .append("to", wallet)
                                .append("value", "15779357623930477")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(events).extracting(RawClassifiedEvent::getEventType)
                .containsOnly(EconomicEventType.LP_ENTRY);
        assertThat(events).extracting(RawClassifiedEvent::getAssetContract)
                .doesNotContain(manager)
                .contains(usdt0, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(events.stream()
                .filter(event -> "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee".equalsIgnoreCase(event.getAssetContract()))
                .findFirst()
                .orElseThrow()
                .getQuantityDelta()).isEqualByComparingTo("-0.599999999944640771");
        assertThat(transferEvents).isEmpty();
    }

    @Test
    void classify_zksyncPancakeV3PositionManagerMintWithOutboundToken_emitsLpEntryEconomicLeg() {
        assertReceiptOnlyKnownManagerEmitsLpEntry(
                "ZKSYNC",
                "0xa815e2ed7f7d5b0c49fda367f249232a1b9d2883",
                "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91",
                "ETH",
                "PancakeSwap V3"
        );
    }

    @Test
    void classify_optimismSlipstreamPositionManagerMintWithOutboundToken_emitsLpEntryEconomicLeg() {
        assertReceiptOnlyKnownManagerEmitsLpEntry(
                "OPTIMISM",
                "0x416b433906b1b72fa758e166e239c43d68dc6f29",
                "0x4200000000000000000000000000000000000006",
                "WETH",
                "Velodrome Slipstream"
        );
    }

    @Test
    void classify_unichainSlipstreamNftMint_isRecognizedAsLpPositionEntry() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String nftContract = "0x991d5546c4b442b4c5fdc4c8b8b8d131deb24702";
        String tokenIdTopic = "0x0000000000000000000000000000000000000000000000000000000000000338";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x63951637d667f23d5251dedc0f9123d22d8595be")
                .append("logs", List.of(
                        new Document("address", nftContract)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x11")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_POSITION_ENTRY);
        assertThat(event.getAssetContract()).isEqualTo(nftContract);
        assertThat(event.getPositionId()).isEqualTo("824");
        assertThat(event.getProtocolName()).isEqualTo("slipstream");
    }

    @Test
    void classify_positionMintAndOutboundToken_withoutKnownManagerContext_fallsBackToPositionLifecycle() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String infinityPositionManager = "0x55f4c8aba71a1e923edc303eb4feff14608cc226";
        String unknownRouter = "0x1111111111111111111111111111111111111111";
        String xyz = "0x9e9035aafecb30cfd5355a10f93a270e33bc4293";
        String vaultTopic = "0x000000000000000000000000238a358808379702088667322f80ac48bad5e6c4";
        String tokenIdTopic = "0x000000000000000000000000000000000000000000000000000000000009d352";

        when(evmTokenDecimalsResolver.getSymbol(eq("BSC"), eq(infinityPositionManager))).thenReturn("PCS-INFINITY-POSM");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BSC");
        tx.setRawData(new Document("from", wallet)
                .append("to", unknownRouter)
                .append("logs", List.of(
                        new Document("address", infinityPositionManager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0xc9"),
                        new Document("address", xyz)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        vaultTopic
                                ))
                                .append("data", "0x000000000000000000000000000000000000000000000fab8c4c3b325a3fffed")
                                .append("logIndex", "0xcd")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_POSITION_ENTRY);
        assertThat(event.getAssetContract()).isEqualTo(infinityPositionManager);
        assertThat(event.getPositionId()).isEqualTo("643922");
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

    @Test
    void classify_lendSelectorWithLpLikeFlows_doesNotEmitLpEvents() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String poolTopic = "0x0000000000000000000000008573f98175d816d520248b5facf40d309b1c9cee";
        String lpToken = "0x1111111111111111111111111111111111111111";
        String tokenA = "0x2222222222222222222222222222222222222222";
        String tokenB = "0x3333333333333333333333333333333333333333";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x18556da13313f3532c54711497a8fedac273220e")
                .append("methodId", "0x69328dec")
                .append("logs", List.of(
                        new Document("address", lpToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000000000a"),
                        new Document("address", tokenA)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                        new Document("address", tokenB)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, poolTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000000005")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_zapOutSelector_withLpRoundtrip_emitsLpExitPartialProceeds() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String routerTopic = "0x00000000000000000000000070f61901658aafb7ae57da0c30695ce4417e72b9";
        String lpToken = "0xc2535b24b47afc15379b55e3ad077bf720dbb34d";
        String cmEth = "0xe6829d9a7ee3040e1276fa75293bde931859e8fa";

        when(evmTokenDecimalsResolver.getDecimals(eq("MANTLE"), eq(cmEth))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("MANTLE"), eq(cmEth))).thenReturn("cmETH");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("MANTLE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x70f61901658aafb7ae57da0c30695ce4417e72b9")
                .append("methodId", "0x8b284b0e")
                .append("functionName", "zapOutV3SingleToken(uint256,uint256,tuple,tuple,bool)")
                .append("logs", List.of(
                        new Document("address", lpToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000008ac7230489e80000"), // 10.0
                        new Document("address", lpToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, routerTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000008ac7230489e80000"), // 10.0
                        new Document("address", cmEth)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, routerTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000001bc16d674ec80000")  // 2.0
                )));

        List<RawClassifiedEvent> lpEvents = classifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(lpEvents).hasSize(1);
        RawClassifiedEvent event = lpEvents.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_EXIT_PARTIAL);
        assertThat(event.getAssetContract()).isEqualTo(cmEth);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("2");
        assertThat(transferEvents).isEmpty();
    }

    private void assertReceiptOnlyKnownManagerEmitsLpEntry(String networkId,
                                                           String positionManager,
                                                           String principalToken,
                                                           String principalSymbol,
                                                           String protocolName) {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String vaultTopic = "0x000000000000000000000000238a358808379702088667322f80ac48bad5e6c4";
        String tokenIdTopic = "0x0000000000000000000000000000000000000000000000000000000000000338";

        when(evmTokenDecimalsResolver.getDecimals(eq(networkId), eq(principalToken))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq(networkId), eq(principalToken))).thenReturn(principalSymbol);

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId(networkId);
        tx.setRawData(new Document("from", wallet)
                .append("to", positionManager)
                .append("logs", List.of(
                        new Document("address", positionManager)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        zeroTopic,
                                        walletTopic,
                                        tokenIdTopic
                                ))
                                .append("data", "0x")
                                .append("logIndex", "0x1"),
                        new Document("address", principalToken)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        vaultTopic
                                ))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000")
                                .append("logIndex", "0x2")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LP_ENTRY);
        assertThat(event.getAssetContract()).isEqualTo(principalToken);
        assertThat(event.getAssetSymbol()).isEqualTo(principalSymbol);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(event.getPositionId()).isEqualTo("824");
        assertThat(event.getProtocolName()).isEqualTo(protocolName.toLowerCase());
    }
}
