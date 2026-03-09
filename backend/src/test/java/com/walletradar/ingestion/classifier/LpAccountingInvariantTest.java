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
class LpAccountingInvariantTest {

    private LpClassifier lpClassifier;
    private TransferClassifier transferClassifier;

    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.ofEntries(
                Map.entry("0x943e6e07a7e8e791dafc44083e54041d743c46e9", "Uniswap V3"),
                Map.entry("0x4529a01c7a0410167c5740c487a8de60232617bf", "Uniswap V4"),
                Map.entry("0x1f98400000000000000000000000000000000004", "Uniswap V4")
        ));
        ProtocolRegistry registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        LendClassifier lendClassifier = new LendClassifier(registry, evmTokenDecimalsResolver, ingestionNetworkProperties);
        lpClassifier = new LpClassifier(registry, evmTokenDecimalsResolver, lendClassifier);
        transferClassifier = new TransferClassifier(registry, evmTokenDecimalsResolver, lendClassifier);
    }

    @Test
    void lpEntryExitClaim_nativeAndErc20_avoidDoubleCount() {
        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String manager = "0x943e6e07a7e8e791dafc44083e54041d743c46e9";
        String managerTopic = "0x000000000000000000000000943e6e07a7e8e791dafc44083e54041d743c46e9";
        String walletTopic = "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        String routerTopic = "0x00000000000000000000000065081cb48d74a32e9ccfed75164b8c09972dbcf1";
        String usdc = "0x078d782b760474a361dda0af3839290b0ef57ad6";
        String weth = "0x4200000000000000000000000000000000000006";

        when(evmTokenDecimalsResolver.getDecimals(eq("UNICHAIN"), eq(usdc))).thenReturn(6);
        when(evmTokenDecimalsResolver.getSymbol(eq("UNICHAIN"), eq(usdc))).thenReturn("USDC");

        RawTransaction entry = new RawTransaction();
        entry.setNetworkId("UNICHAIN");
        entry.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("value", "19999999577008510")
                .append("input", "0xac9650d8...219f5d17...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, routerTopic))
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
                                .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, managerTopic, routerTopic))
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

        RawTransaction partialExit = new RawTransaction();
        partialExit.setNetworkId("UNICHAIN");
        partialExit.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("input", "0xac9650d8...0c49ccbe...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        managerTopic,
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

        RawTransaction claim = new RawTransaction();
        claim.setNetworkId("UNICHAIN");
        claim.setRawData(new Document("from", wallet)
                .append("to", manager)
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("input", "0xac9650d8...fc6f7865...")
                .append("logs", List.of(
                        new Document("address", usdc)
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        managerTopic,
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

        assertNoDoubleCount(entry, wallet, EconomicEventType.LP_ENTRY, 2);
        assertNoDoubleCount(partialExit, wallet, EconomicEventType.LP_EXIT_PARTIAL, 2);
        assertNoDoubleCount(claim, wallet, EconomicEventType.LP_FEE_CLAIM, 2);
    }

    @Test
    void positionNftTransfer_isEvidence_notEconomicAsset() {
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

        List<RawClassifiedEvent> lpEvents = lpClassifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(lpEvents).extracting(RawClassifiedEvent::getEventType)
                .containsOnly(EconomicEventType.LP_ENTRY);
        assertThat(lpEvents).extracting(RawClassifiedEvent::getAssetContract)
                .doesNotContain(manager)
                .contains("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", usdt0);
        assertThat(transferEvents).isEmpty();
    }

    private void assertNoDoubleCount(
            RawTransaction tx,
            String wallet,
            EconomicEventType eventType,
            int expectedEvents
    ) {
        List<RawClassifiedEvent> lpEvents = lpClassifier.classify(tx, wallet);
        List<RawClassifiedEvent> transferEvents = transferClassifier.classify(tx, wallet);

        assertThat(lpEvents).hasSize(expectedEvents);
        assertThat(lpEvents).extracting(RawClassifiedEvent::getEventType).containsOnly(eventType);
        assertThat(lpEvents).extracting(RawClassifiedEvent::getAssetContract).doesNotHaveDuplicates();
        assertThat(transferEvents).isEmpty();
    }
}
