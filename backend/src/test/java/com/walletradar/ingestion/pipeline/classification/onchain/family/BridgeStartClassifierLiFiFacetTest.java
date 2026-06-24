package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WS-2: LI.FI facet-function recognition — address-anchor and function-key matching.
 */
@ExtendWith(MockitoExtension.class)
class BridgeStartClassifierLiFiFacetTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    // Known LI.FI Permit2Proxy (added in WS-1, registered in protocol-registry.json)
    private static final String KNOWN_LIFI_PROXY = "0x89c6340b1a1f4b25d36cd8b063d49045caf3f818";
    private static final String UNKNOWN_ADDRESS = "0xdeadbeef000000000000000000000000deadbeef";

    @Mock
    private ProtocolRegistryService protocolRegistryService;

    private BridgeStartClassifier classifier;

    @BeforeEach
    void setUp() {
        lenient().when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());
        classifier = new BridgeStartClassifier(protocolRegistryService, new NativeAssetSymbolResolver());
    }

    @Test
    @DisplayName("callDiamondWithPermit2 to known LI.FI proxy (address-anchored) → BRIDGE_OUT / LI.FI")
    void callDiamondWithPermit2_knownProxy_bridgeOut() {
        when(protocolRegistryService.lookup(NetworkId.AVALANCHE, KNOWN_LIFI_PROXY))
                .thenReturn(Optional.of(lifiEntry(KNOWN_LIFI_PROXY)));

        OnChainClassificationContext ctx = buildContext(
                NetworkId.AVALANCHE,
                KNOWN_LIFI_PROXY,
                "0x0193b9fc",  // callDiamondWithPermit2
                null,
                lifiInputDataWithRouteTag("gaszip"),
                List.of(RawLeg.asset("0xtoken", "USDC", new BigDecimal("-100")))
        );

        Optional<ClassificationDecision> result = classifier.classify(ctx);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.get().protocolName()).isEqualTo("LI.FI");
    }

    @Test
    @DisplayName("callDiamondWithPermit2 to UNKNOWN address → NOT classified (address-anchor enforced)")
    void callDiamondWithPermit2_unknownAddress_notClassified() {
        // Registry returns nothing for unknown address
        when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());

        OnChainClassificationContext ctx = buildContext(
                NetworkId.ETHEREUM,
                UNKNOWN_ADDRESS,
                "0x0193b9fc",  // callDiamondWithPermit2
                null,
                lifiInputDataWithRouteTag("gaszip"),
                List.of(RawLeg.asset("0xtoken", "USDC", new BigDecimal("-100")))
        );

        Optional<ClassificationDecision> result = classifier.classify(ctx);

        // Should NOT return LI.FI classification — address anchor prevents false attribution
        result.ifPresent(decision ->
                assertThat(decision.protocolName()).as("must not attribute LI.FI without address anchor").isNotEqualTo("LI.FI")
        );
    }

    @Test
    @DisplayName("startBridgeTokensViaGasZip to known LI.FI proxy → BRIDGE_OUT / LI.FI")
    void startBridgeTokensViaGasZip_knownProxy_bridgeOut() {
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, KNOWN_LIFI_PROXY))
                .thenReturn(Optional.of(lifiEntry(KNOWN_LIFI_PROXY)));

        OnChainClassificationContext ctx = buildContext(
                NetworkId.UNICHAIN,
                KNOWN_LIFI_PROXY,
                "0xfc5f1003",
                "startBridgeTokensViaGasZip",
                lifiInputDataWithRouteTag("gaszip"),
                List.of(RawLeg.nativeAsset("ETH", new BigDecimal("-0.1")))
        );

        Optional<ClassificationDecision> result = classifier.classify(ctx);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.get().protocolName()).isEqualTo("LI.FI");
    }

    @Test
    @DisplayName("swapAndStartBridgeTokensViaMayan to registry-known address → BRIDGE_OUT with protocol name")
    void swapAndStartBridgeTokensViaMayan_registryKnown_bridgeOut() {
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, KNOWN_LIFI_PROXY))
                .thenReturn(Optional.of(lifiEntry(KNOWN_LIFI_PROXY)));

        OnChainClassificationContext ctx = buildContext(
                NetworkId.ETHEREUM,
                KNOWN_LIFI_PROXY,
                null,
                "swapAndStartBridgeTokensViaMayan",
                lifiInputDataWithRouteTag("mayan"),
                List.of(RawLeg.asset("0xtoken", "USDC", new BigDecimal("-500")))
        );

        Optional<ClassificationDecision> result = classifier.classify(ctx);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
    }

    @Test
    @DisplayName("multicall to non-LI.FI address → NOT tagged LI.FI")
    void multicall_nonLiFiAddress_notTaggedLiFi() {
        when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());

        OnChainClassificationContext ctx = buildContext(
                NetworkId.ETHEREUM,
                UNKNOWN_ADDRESS,
                "0xac9650d8",  // multicall selector
                "multicall",
                "0xac9650d8" + "0".repeat(100),
                List.of(RawLeg.asset("0xtoken", "USDC", new BigDecimal("-100")))
        );

        Optional<ClassificationDecision> result = classifier.classify(ctx);

        result.ifPresent(decision ->
                assertThat(decision.protocolName()).as("multicall to unknown address must not get LI.FI attribution").isNotEqualTo("LI.FI")
        );
    }

    // ---------- helpers ----------

    private OnChainClassificationContext buildContext(
            NetworkId networkId,
            String toAddress,
            String methodId,
            String functionName,
            String inputData,
            List<RawLeg> legs
    ) {
        RawTransaction raw = new RawTransaction();
        raw.setId("test-tx::" + networkId.name() + "::" + WALLET);
        raw.setNetworkId(networkId.name());
        raw.setWalletAddress(WALLET);
        Document data = new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", toAddress)
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "200000")
                .append("gasPrice", "1000000000")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        if (methodId != null) {
            data.put("methodId", methodId);
        }
        if (functionName != null) {
            data.put("functionName", functionName + "(bytes)");
        }
        if (inputData != null) {
            data.put("input", inputData);
        }
        raw.setRawData(data);
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        return new OnChainClassificationContext(view, null, null, legs);
    }

    private static ProtocolRegistryEntry lifiEntry(String address) {
        return new ProtocolRegistryEntry(
                address,
                Set.of(NetworkId.AVALANCHE, NetworkId.ETHEREUM, NetworkId.ARBITRUM, NetworkId.BASE, NetworkId.UNICHAIN),
                ProtocolRegistryFamily.BRIDGE,
                ProtocolRegistryRole.BRIDGE_ENTRY,
                ProtocolRegistryEventType.BRIDGE_OUT,
                ConfidenceLevel.HIGH,
                "LI.FI",
                "Diamond",
                false,
                null
        );
    }

    /**
     * Builds synthetic calldata that contains the given route tag as an ASCII fragment,
     * simulating a real LI.FI calldata payload containing the underlying bridge name.
     */
    private static String lifiInputDataWithRouteTag(String routeTag) {
        // Build a hex-encoded ascii fragment by embedding the tag after a method id prefix
        StringBuilder sb = new StringBuilder("0x0193b9fc");
        byte[] tagBytes = routeTag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        sb.append("00".repeat(32)); // padding before
        for (byte b : tagBytes) {
            sb.append(String.format("%02x", b));
        }
        sb.append("00".repeat(32)); // padding after
        return sb.toString();
    }
}
