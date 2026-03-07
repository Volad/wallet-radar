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
class LendClassifierTest {

    private static final String ZKSYNC_WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    private LendClassifier classifier;

    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.of());
        ProtocolRegistry registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry zksyncEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        zksyncEntry.setSyntheticNativeContracts(List.of("0x000000000000000000000000000000000000800a"));
        IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule unichainRule =
                new IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule();
        unichainRule.setContract("0x2c7118c4c88b9841fcf839074c26ae8f035f2921");
        unichainRule.setSelectors(List.of("0xf2b9fdb8"));
        IngestionNetworkProperties.NetworkIngestionEntry unichainEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        unichainEntry.setOneLegLendRules(List.of(unichainRule));
        ingestionNetworkProperties.setNetwork(Map.of(
                "ZKSYNC", zksyncEntry,
                "UNICHAIN", unichainEntry
        ));
        classifier = new LendClassifier(registry, evmTokenDecimalsResolver, ingestionNetworkProperties);
    }

    @Test
    void classify_vaultDepositPattern_emitsLendDepositLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String underlying = "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9";
        String receipt = "0x4a03f37e7d3fc243e3f99341d36f4b829bee5e03";
        String protocolTopic = "0x00000000000000000000000052aa899454998be5b000ad077a46bbe360f4e497";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(receipt))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDT0");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(receipt))).thenReturn("fUSDT");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_DEPOSIT);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo(underlying);
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-2000");
        assertThat(in.getAssetContract()).isEqualTo(receipt);
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("1849.226677");
    }

    @Test
    void classify_mintButReceiptContractNotEqualTxTo_returnsEmpty() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String underlying = "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9";
        String receipt = "0x4a03f37e7d3fc243e3f99341d36f4b829bee5e03";
        String protocolTopic = "0x00000000000000000000000052aa899454998be5b000ad077a46bbe360f4e497";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet).append("to", "0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc").append("logs", List.of(
                new Document("address", underlying)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, protocolTopic))
                        .append("data", "0x1"),
                new Document("address", receipt)
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                        .append("data", "0x1")
        )));

        assertThat(classifier.classify(tx, wallet)).isEmpty();
    }

    @Test
    void classify_vaultWithdrawalPattern_emitsLendWithdrawalLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String receipt = "0x1a996cb54bb95462040408c06122d45d6cdb6096";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String protocolTopic = "0x00000000000000000000000052aa899454998be5b000ad077a46bbe360f4e497";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(receipt))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(receipt))).thenReturn("fUSDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_WITHDRAWAL);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo(receipt);
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-926.427551");
        assertThat(in.getAssetContract()).isEqualTo(underlying);
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("999.476835");
    }

    @Test
    void classify_multicallVaultDepositPattern_emitsLendDepositLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String router = "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String receipt = "0x7e97fa6893871a2751b5fe961978dccb2c201e65";
        String protocolTopic = "0x0000000000000000000000009954afb60bb5a222714c478ac86990f221788b88";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(receipt))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(receipt))).thenReturn("gtUSDCc");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_DEPOSIT);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo(underlying);
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-863");
        assertThat(in.getAssetContract()).isEqualTo(receipt);
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("857.839932590298984142");
    }

    @Test
    void classify_batchWrappedRedeemPattern_emitsLendWithdrawalLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String batchRouter = "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066";
        String receipt = "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String receiptTopic = "0x00000000000000000000000044c10da836d2abe881b77bbb0b3dce5f85c0c1cc";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(receipt))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(receipt))).thenReturn("eUSDC-6");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_WITHDRAWAL);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo(receipt);
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-732.899692");
        assertThat(in.getAssetContract()).isEqualTo(underlying);
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("746.016993");
    }

    @Test
    void classify_aaveSupplyPattern_emitsLendDepositLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String underlying = "0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f";
        String receipt = "0x078f358208685046a11c85e8ad32895ded33a249";
        String receiptTopic = "0x000000000000000000000000078f358208685046a11c85e8ad32895ded33a249";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(8);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(receipt))).thenReturn(8);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("WBTC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(receipt))).thenReturn("aArbWBTC");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_DEPOSIT);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo(underlying);
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-0.00010526");
        assertThat(in.getAssetContract()).isEqualTo(receipt);
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("0.00010527");
    }

    @Test
    void classify_aaveBorrowPattern_emitsBorrowUnderlying() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String reserveTopic = "0x000000000000000000000000078f358208685046a11c85e8ad32895ded33a249";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String debtToken = "0x1234567890abcdef1234567890abcdef12345678";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(debtToken))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.BORROW);
        assertThat(event.getAssetContract()).isEqualTo(underlying);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("0.390624");
    }

    @Test
    void classify_aaveRepayPattern_emitsRepayUnderlying() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String reserveTopic = "0x000000000000000000000000078f358208685046a11c85e8ad32895ded33a249";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String debtToken = "0x1234567890abcdef1234567890abcdef12345678";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(debtToken))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");

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

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.REPAY);
        assertThat(event.getAssetContract()).isEqualTo(underlying);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-4.000000");
    }

    @Test
    void classify_aaveRepayWithATokensSelector_emitsRepayUnderlying() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String reserveTopic = "0x000000000000000000000000078f358208685046a11c85e8ad32895ded33a249";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String debtToken = "0x1234567890abcdef1234567890abcdef12345678";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(debtToken))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", pool)
                .append("methodId", "0x2dad97d4")
                .append("logs", List.of(
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, reserveTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000003d0900"),
                        new Document("address", debtToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000003d0901")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.REPAY);
        assertThat(event.getAssetContract()).isEqualTo(underlying);
        assertThat(event.getQuantityDelta()).isNegative();
    }

    @Test
    void classify_aaveBorrowEthGatewaySelector_emitsBorrowUnderlying() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String reserveTopic = "0x000000000000000000000000078f358208685046a11c85e8ad32895ded33a249";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String debtToken = "0x1234567890abcdef1234567890abcdef12345678";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(debtToken))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", pool)
                .append("methodId", "0xe74f7b85")
                .append("logs", List.of(
                        new Document("address", debtToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000005f5e1"),
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, reserveTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000000005f5e0")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.BORROW);
        assertThat(event.getAssetContract()).isEqualTo(underlying);
        assertThat(event.getQuantityDelta()).isPositive();
    }

    @Test
    void classify_multicallVariantSelectorWithoutFunctionName_emitsLendDepositLegs() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String router = "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13";
        String underlying = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String receipt = "0x7e97fa6893871a2751b5fe961978dccb2c201e65";
        String protocolTopic = "0x0000000000000000000000009954afb60bb5a222714c478ac86990f221788b88";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(receipt))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(underlying))).thenReturn("USDC");
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(receipt))).thenReturn("gtUSDCc");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", router)
                .append("methodId", "0x5ae401dc")
                .append("logs", List.of(
                        new Document("address", underlying)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, protocolTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000000000000000337055c0"),
                        new Document("address", receipt)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x00000000000000000000000000000000000000000000002e80eba9a8fdcf6ece")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_DEPOSIT);
    }

    @Test
    void classify_zkSyncDepositWithExtraNativeLegs_emitsLendDeposit() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/lend-deposit-extra-native-legs.json");

        List<RawClassifiedEvent> events = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_DEPOSIT);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo("0x5aea5775959fbc2557cc8789bc1bf90a239d9a91");
        assertThat(in.getAssetContract()).isEqualTo("0xb7b93bcf82519bb757fd18b23a389245dbd8ca64");
    }

    @Test
    void classify_zkSyncDepositWithExtraNativeLegs_withoutConfiguredSyntheticNativeContract_returnsEmpty() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/lend-deposit-extra-native-legs.json");

        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        LendClassifier classifierWithoutSyntheticContracts =
                new LendClassifier(new DefaultProtocolRegistry(new ProtocolRegistryProperties()),
                        evmTokenDecimalsResolver,
                        ingestionNetworkProperties);

        List<RawClassifiedEvent> events = classifierWithoutSyntheticContracts.classify(tx, ZKSYNC_WALLET);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_zkSyncWithdrawWithExtraNativeLegs_emitsLendWithdrawal() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/lend-withdraw-extra-native-legs.json");

        List<RawClassifiedEvent> events = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_WITHDRAWAL);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo("0xd6cd2c0fc55936498726cacc497832052a9b2d1b");
        assertThat(in.getAssetContract()).isEqualTo("0x5a7d6b2f92c77fad6ccabd7ee0624e64907eaf3e");
    }

    @Test
    void classify_zkSyncBorrowWithExtraNativeLegs_emitsBorrow() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/borrow-extra-native-legs.json");

        List<RawClassifiedEvent> events = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.BORROW);
        assertThat(event.getAssetContract()).isEqualTo("0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4");
        assertThat(event.getAssetContract()).isNotEqualTo("0x000000000000000000000000000000000000800a");
        assertThat(event.getQuantityDelta()).isPositive();
    }

    @Test
    void classify_zkSyncRepayWithExtraNativeLegs_emitsRepay() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/repay-extra-native-legs.json");

        List<RawClassifiedEvent> events = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.REPAY);
        assertThat(event.getAssetContract()).isEqualTo("0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4");
        assertThat(event.getAssetContract()).isNotEqualTo("0x000000000000000000000000000000000000800a");
        assertThat(event.getQuantityDelta()).isNegative();
    }

    @Test
    void classify_zkSyncBorrowWithExtraNativeLegs_isDeterministic() {
        RawTransaction tx = ClassifierFixtureLoader
                .loadRawTransaction("fixtures/classifier/zksync/borrow-extra-native-legs.json");

        List<RawClassifiedEvent> first = classifier.classify(tx, ZKSYNC_WALLET);
        List<RawClassifiedEvent> second = classifier.classify(tx, ZKSYNC_WALLET);
        List<RawClassifiedEvent> third = classifier.classify(tx, ZKSYNC_WALLET);

        assertThat(first)
                .extracting(e -> e.getEventType().name() + "|" + e.getAssetContract() + "|" + e.getQuantityDelta())
                .containsExactlyElementsOf(second.stream()
                        .map(e -> e.getEventType().name() + "|" + e.getAssetContract() + "|" + e.getQuantityDelta())
                        .toList())
                .containsExactlyElementsOf(third.stream()
                        .map(e -> e.getEventType().name() + "|" + e.getAssetContract() + "|" + e.getQuantityDelta())
                        .toList());
    }

    @Test
    void classify_depositEthSelector_emitsLendDepositNativePlusAToken() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String gateway = "0x9b4e2fbe6f778c2ec0f7f8a6f8e2f66f65fa4f80";
        String aToken = "0xe50fa9b3c56f281b56c8ea8f3f3f660c9b7e8128";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(aToken))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(aToken))).thenReturn("aArbWETH");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", gateway)
                .append("methodId", "0x474cf53d")
                .append("value", "1000000000000000000")
                .append("logs", List.of(
                        new Document("address", aToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, zeroTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000")
                                .append("logIndex", "0x3")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_DEPOSIT);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(in.getAssetContract()).isEqualTo(aToken);
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_withdrawEthSelector_emitsLendWithdrawalATokenPlusNative() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String zeroTopic = "0x0000000000000000000000000000000000000000000000000000000000000000";
        String gateway = "0x9b4e2fbe6f778c2ec0f7f8a6f8e2f66f65fa4f80";
        String aToken = "0xe50fa9b3c56f281b56c8ea8f3f3f660c9b7e8128";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(aToken))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(aToken))).thenReturn("aArbWETH");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", gateway)
                .append("methodId", "0x80500d20")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("address", aToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, zeroTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000")
                                .append("logIndex", "0x5")
                ))
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", gateway)
                                .append("to", wallet)
                                .append("value", "1000000000000000000")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getEventType() == EconomicEventType.LEND_WITHDRAWAL);
        RawClassifiedEvent out = events.stream().filter(e -> e.getQuantityDelta().signum() < 0).findFirst().orElseThrow();
        RawClassifiedEvent in = events.stream().filter(e -> e.getQuantityDelta().signum() > 0).findFirst().orElseThrow();
        assertThat(out.getAssetContract()).isEqualTo(aToken);
        assertThat(out.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(in.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(in.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_unichainAllowlistedOneLegSupply_emitsLendDeposit() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String protocolTopic = "0x0000000000000000000000002c7118c4c88b9841fcf839074c26ae8f035f2921";
        String usdc = "0x078d782b760474a361dda0af3839290b0ef57ad6";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("UNICHAIN");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x2c7118c4c88b9841fcf839074c26ae8f035f2921")
                .append("methodId", "0xf2b9fdb8")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, protocolTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000003be10a9")
                                .append("logIndex", "0x7")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LEND_DEPOSIT);
        assertThat(event.getAssetContract()).isEqualTo(usdc);
        assertThat(event.getQuantityDelta()).isNegative();
    }

    @Test
    void classify_withdrawEthSelector_withoutInternalNative_emitsOneLegWithdrawal() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String gatewayTopic = "0x0000000000000000000000009b4e2fbe6f778c2ec0f7f8a6f8e2f66f65fa4f80";
        String aToken = "0xe50fa9b3c56f281b56c8ea8f3f3f660c9b7e8128";

        when(evmTokenDecimalsResolver.getDecimals(eq("ARBITRUM"), eq(aToken))).thenReturn(18);
        when(evmTokenDecimalsResolver.getSymbol(eq("ARBITRUM"), eq(aToken))).thenReturn("aArbWETH");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x9b4e2fbe6f778c2ec0f7f8a6f8e2f66f65fa4f80")
                .append("methodId", "0x80500d20")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("address", aToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, gatewayTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000001bc16d674ec80000"), // 2.0
                        new Document("address", aToken)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, gatewayTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000016345785d8a0000")  // 0.1
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LEND_WITHDRAWAL);
        assertThat(event.getAssetContract()).isEqualTo(aToken);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-1.9");
    }

    @Test
    void classify_redeemSelector_withoutReceiptBurn_emitsOneLegWithdrawal() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String protocolTopic = "0x0000000000000000000000003048925b3ea5a8c12eecccb8810f5f7544db54af";
        String usdc = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x3048925b3ea5a8c12eecccb8810f5f7544db54af")
                .append("methodId", "0xba087652")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, protocolTopic, walletTopic))
                                .append("data", "0x000000000000000000000000000000000000000000000000000000003b9aca00")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LEND_WITHDRAWAL);
        assertThat(event.getAssetContract()).isEqualTo(usdc);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("1000");
    }

    @Test
    void classify_withdrawSelector_edgeCase_69328dec_notUnclassified() {
        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String vaultTopic = "0x000000000000000000000000794a61358d6845594f94dc1db02a252b5b4814ad";
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(usdc))).thenReturn("USDC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x794a61358d6845594f94dc1db02a252b5b4814ad")
                .append("methodId", "0x69328dec")
                .append("value", "0")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, vaultTopic, walletTopic))
                                .append("data", "0x0000000000000000000000000000000000000000000000000000000000f42400")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LEND_WITHDRAWAL);
        assertThat(event.getAssetContract()).isEqualTo(usdc);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("16");
    }

    @Test
    void classify_withdrawSelector_69328dec_withSyntheticMintNoiseStillLendWithdrawal() {
        String wallet = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
        String pool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        String underlying = "0xc891eb4cbdeff6e073e859e987815ed1505c2acd";

        when(evmTokenDecimalsResolver.getDecimals(eq("AVALANCHE"), eq(underlying))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("AVALANCHE"), eq(underlying))).thenReturn("EURC");

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", pool)
                .append("methodId", "0x69328dec")
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", "0x0000000000000000000000000000000000000000")
                                .append("to", wallet)
                                .append("contractAddress", "0x8a9fde6925a839f6b1932d16b36ac026f8d3fbdb")
                                .append("tokenDecimal", "6")
                                .append("value", "2498358"),
                        new Document("from", "0x8a9fde6925a839f6b1932d16b36ac026f8d3fbdb")
                                .append("to", wallet)
                                .append("contractAddress", underlying)
                                .append("tokenDecimal", "6")
                                .append("value", "770000")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.LEND_WITHDRAWAL);
        assertThat(event.getAssetContract()).isEqualTo(underlying);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("0.77");
    }
}
