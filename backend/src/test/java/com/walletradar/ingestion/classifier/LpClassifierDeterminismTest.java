package com.walletradar.ingestion.classifier;

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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LpClassifierDeterminismTest {

    private LpClassifier classifier;

    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.ofEntries(
                Map.entry("0x4529a01c7a0410167c5740c487a8de60232617bf", "Uniswap V4"),
                Map.entry("0x1f98400000000000000000000000000000000004", "Uniswap V4")
        ));
        ProtocolRegistry registry = new DefaultProtocolRegistry(props);
        lenient().when(evmTokenDecimalsResolver.getDecimals(anyString(), anyString())).thenReturn(18);
        lenient().when(evmTokenDecimalsResolver.getSymbol(anyString(), anyString())).thenReturn("TOKEN");
        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        LendClassifier lendClassifier = new LendClassifier(registry, evmTokenDecimalsResolver, ingestionNetworkProperties);
        classifier = new LpClassifier(registry, evmTokenDecimalsResolver, lendClassifier);
    }

    @Test
    void sameReceipt_sameOrderedLpFlows() {
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

        String baseline = fingerprint(classifier.classify(tx, wallet));
        for (int i = 0; i < 30; i++) {
            assertThat(fingerprint(classifier.classify(tx, wallet))).isEqualTo(baseline);
        }
    }

    private String fingerprint(List<RawClassifiedEvent> events) {
        return events.stream()
                .map(event -> event.getEventType()
                        + "|" + event.getAssetContract()
                        + "|" + event.getQuantityDelta()
                        + "|" + event.getLogIndex()
                        + "|" + event.getPositionId())
                .collect(Collectors.joining("||"));
    }
}
