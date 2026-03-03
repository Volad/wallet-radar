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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LendClassifierTest {

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
        classifier = new LendClassifier(registry, evmTokenDecimalsResolver);
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
}
