package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.application.normalization.pipeline.classification.support.LiFiRouteSupport;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.classification.support.RelayBridgeClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.SameWalletSwapShapeSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit source-side bridge-start classifier that runs before broader bridge heuristics.
 */
@Component
public class BridgeStartClassifier implements OnChainFamilyClassifier {

    private static final Set<String> BRIDGE_START_SELECTORS = Set.of(
            "0x30c48952",
            "0xa6010a66",
            "0xa8f66666"
    );
    /**
     * LI.FI facet function prefixes that signal bridge-out initiation.
     * Both {@code startBridgeTokensVia<Bridge>} and {@code swapAndStartBridgeTokensVia<Bridge>}
     * are matched via prefix-key lookup in {@link #isBridgeStartFunctionKey(String)}.
     */
    private static final Set<String> BRIDGE_START_FUNCTION_KEY_PREFIXES = Set.of(
            // explicit-name facets (stable cross-version)
            "startbridgetokensviastargate",
            "startbridgetokensviasquid",
            "startbridgetokensviagaszip",
            "startbridgetokensviarelay",
            "startbridgetokensviaacross",
            "startbridgetokensviahop",
            "startbridgetokensviacbridge",
            "startbridgetokensviahopdiamond",
            "startbridgetokensviaoptimism",
            "startbridgetokensviabase",
            "startbridgetokensviasynapse",
            "startbridgetokensviaomni",
            "startbridgetokensviaamarok",
            "startbridgetokensviahyperlane",
            "startbridgetokensviasymbiosis",
            "startbridgetokensviamayan",
            "swapandstartbridgetokensviamayan",
            "swapandstartbridgetokensviastargate",
            "swapandstartbridgetokensviasquid",
            "swapandstartbridgetokensviagaszip",
            "swapandstartbridgetokensviarelay",
            "swapandstartbridgetokensviaacross",
            "swapandstartbridgetokensviahop",
            "swapandstartbridgetokensviacbridge",
            "swapandstartbridgetokensviahopdiamond",
            "swapandstartbridgetokensviaoptimism",
            "swapandstartbridgetokensviabase",
            "swapandstartbridgetokensviasynapse",
            "swapandstartbridgetokensviaomni",
            "swapandstartbridgetokensviaamarok",
            "swapandstartbridgetokensviahyperlane",
            "swapandstartbridgetokensviasymbiosis"
    );
    /**
     * LI.FI callDiamond* selectors (address-anchored — only fires when target is in known LI.FI set).
     * {@code callDiamondWithEIP2612Signature} = 0xd7a08473; {@code callDiamondWithPermit2} = 0x0193b9fc.
     */
    private static final Set<String> LI_FI_CALL_DIAMOND_SELECTORS = Set.of(
            "0xd7a08473",  // callDiamondWithEIP2612Signature
            "0x0193b9fc"   // callDiamondWithPermit2
    );
    private static final Set<String> LI_FI_DIAMOND_ROUTE_SELECTORS = Set.of(
            "0xd7a08473",
            "0xe9ae5c53",
            "0x0193b9fc",
            "0xfc5f1003"
    );
    private static final String TRANSFER_REMOTE_SELECTOR = "0x81b4e8b4";

    private final ProtocolRegistryService protocolRegistryService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public BridgeStartClassifier(
            ProtocolRegistryService protocolRegistryService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_ECONOMIC_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ClassificationDecision> explicitBridgeStart = classifyExplicitBridgeStart(context);
        if (explicitBridgeStart.isPresent()) {
            return explicitBridgeStart;
        }

        Optional<ClassificationDecision> liFiRouteBridge = classifyLiFiRouteBridge(context);
        if (liFiRouteBridge.isPresent()) {
            return liFiRouteBridge;
        }

        Optional<ClassificationDecision> relayDepositoryBridge = classifyRelayDepositoryBridge(context);
        if (relayDepositoryBridge.isPresent()) {
            return relayDepositoryBridge;
        }

        return classifyTransferRemoteBridge(context);
    }

    private Optional<ClassificationDecision> classifyRelayDepositoryBridge(OnChainClassificationContext context) {
        if (!RelayBridgeClassificationSupport.isRelayDepositoryBridgeOut(context.view())
                || !RelayBridgeClassificationSupport.onlyOutbound(context.movementLegs())) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> entry = RelayBridgeClassificationSupport.resolveRelayDepositoryBridgeEntry(
                protocolRegistryService,
                context.view()
        );
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        ProtocolRegistryEntry bridgeEntry = entry.orElseThrow();
        return Optional.of(build(
                context,
                bridgeEntry.confidence() != null ? ClassificationSource.PROTOCOL_REGISTRY : ClassificationSource.METHOD_ID,
                bridgeEntry.confidence() != null ? bridgeEntry.confidence() : ConfidenceLevel.MEDIUM,
                List.of(),
                bridgeEntry.protocolName(),
                bridgeEntry.protocolVersion()
        ));
    }

    private Optional<ClassificationDecision> classifyExplicitBridgeStart(OnChainClassificationContext context) {
        if (!onlyOutbound(context.movementLegs())) {
            return Optional.empty();
        }
        String functionKey = functionKey(context.view().functionName());
        if (!BRIDGE_START_SELECTORS.contains(context.view().methodId())
                && !isBridgeStartFunctionKey(functionKey)) {
            return Optional.empty();
        }

        List<String> bridgePairReasons = bridgePairEvidenceReasons(context);
        Optional<ProtocolRegistryEntry> bridgeEntry = protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress())
                .filter(entry -> entry.family() == ProtocolRegistryFamily.BRIDGE
                        && (entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                        || entry.role() == ProtocolRegistryRole.ROUTER));
        if (bridgeEntry.isPresent()) {
            ProtocolRegistryEntry entry = bridgeEntry.get();
            return Optional.of(build(
                    context,
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    bridgePairReasons,
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }

        ClassificationSource source = BRIDGE_START_SELECTORS.contains(context.view().methodId())
                ? ClassificationSource.METHOD_ID
                : ClassificationSource.FUNCTION_NAME;
        return Optional.of(build(
                context,
                source,
                ConfidenceLevel.MEDIUM,
                bridgePairReasons,
                null,
                null
        ));
    }

    private Optional<ClassificationDecision> classifyLiFiRouteBridge(OnChainClassificationContext context) {
        boolean explicitLiFiRouteSelector = LI_FI_DIAMOND_ROUTE_SELECTORS.contains(context.view().methodId());
        // callDiamond* selectors must be address-anchored — pattern alone is not enough
        boolean callDiamondSelector = LI_FI_CALL_DIAMOND_SELECTORS.contains(context.view().methodId());
        Optional<ProtocolRegistryEntry> knownLiFiDiamondEntry = knownLiFiDiamondEntry(context);
        if (callDiamondSelector && knownLiFiDiamondEntry.isEmpty()) {
            // Address-anchor check: callDiamond* on unknown address → not LI.FI
            return Optional.empty();
        }
        if (!explicitLiFiRouteSelector && knownLiFiDiamondEntry.isEmpty()) {
            return Optional.empty();
        }
        if ("0x".equals(context.view().methodId())) {
            return Optional.empty();
        }
        if (!hasBridgeFunding(context)) {
            return Optional.empty();
        }
        if (!LiFiRouteSupport.hasRouteTag(context.view())) {
            return Optional.empty();
        }
        if (SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(context.movementLegs())) {
            return Optional.empty();
        }
        ProtocolRegistryEntry entry = knownLiFiDiamondEntry.orElse(null);
        return Optional.of(build(
                context,
                ClassificationSource.HEURISTIC,
                entry != null ? entry.confidence() : ConfidenceLevel.MEDIUM,
                List.of(),
                entry != null ? entry.protocolName() : null,
                entry != null ? entry.protocolVersion() : null
        ));
    }

    private Optional<ClassificationDecision> classifyTransferRemoteBridge(OnChainClassificationContext context) {
        if (!(TRANSFER_REMOTE_SELECTOR.equals(context.view().methodId())
                || "transferremote".equals(functionKey(context.view().functionName())))) {
            return Optional.empty();
        }
        if (!hasOutbound(context.movementLegs())) {
            return Optional.empty();
        }
        if (SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(context.movementLegs())) {
            return Optional.empty();
        }
        if (!hasNativeOutbound(context) || tokenOutboundCount(context) < 1) {
            return Optional.empty();
        }
        return Optional.of(build(
                context,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(),
                null,
                null
        ));
    }

    private ClassificationDecision build(
            OnChainClassificationContext context,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<String> missingDataReasons,
            String protocolName,
            String protocolVersion
    ) {
        return new ClassificationDecision(
                NormalizedTransactionType.BRIDGE_OUT,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_OUT, confidence),
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(
                        context.view(),
                        context.movementLegs(),
                        NormalizedTransactionType.BRIDGE_OUT
                ),
                missingDataReasons,
                null,
                null,
                null,
                null,
                null,
                protocolName,
                protocolVersion
        );
    }

    private List<String> bridgePairEvidenceReasons(OnChainClassificationContext context) {
        if (context.view().hasFullReceiptClarificationEvidence()) {
            return List.of();
        }
        return List.of(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED);
    }

    private boolean onlyOutbound(List<RawLeg> movementLegs) {
        boolean hasInbound = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        return hasOutbound && !hasInbound;
    }

    private boolean hasOutbound(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
    }

    private boolean hasBridgeFunding(OnChainClassificationContext context) {
        return hasOutbound(context.movementLegs())
                || (context.view().rawValue() != null && context.view().rawValue().signum() > 0);
    }

    private Optional<ProtocolRegistryEntry> knownLiFiDiamondEntry(OnChainClassificationContext context) {
        return protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress())
                .filter(entry -> entry.family() == ProtocolRegistryFamily.BRIDGE)
                .filter(entry -> entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                        || entry.role() == ProtocolRegistryRole.ROUTER)
                .filter(entry -> isLiFiProtocol(entry.protocolName()));
    }

    private boolean isLiFiProtocol(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return false;
        }
        String normalized = protocolName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("lifi");
    }

    private boolean hasNativeOutbound(OnChainClassificationContext context) {
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(context.view().networkId());
        if (nativeSymbol == null) {
            return false;
        }
        return context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.quantityDelta().signum() < 0
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
    }

    private int tokenOutboundCount(OnChainClassificationContext context) {
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(context.view().networkId());
        return (int) context.movementLegs().stream()
                .filter(leg -> !leg.fee() && leg.quantityDelta().signum() < 0)
                .filter(leg -> nativeSymbol == null || !nativeSymbol.equalsIgnoreCase(leg.assetSymbol()))
                .map(leg -> {
                    if (leg.assetContract() != null && !leg.assetContract().isBlank()) {
                        return leg.assetContract().toLowerCase(Locale.ROOT);
                    }
                    return "symbol:" + (leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT));
                })
                .distinct()
                .count();
    }

    private String functionKey(String functionName) {
        if (functionName == null) {
            return "";
        }
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int signatureSeparator = normalized.indexOf('(');
        if (signatureSeparator > 0) {
            return normalized.substring(0, signatureSeparator);
        }
        return normalized;
    }

    /**
     * Returns {@code true} when the normalized function key matches a known LI.FI bridge-start
     * pattern. Covers both {@code startBridgeTokensVia<Bridge>} and
     * {@code swapAndStartBridgeTokensVia<Bridge>} across all supported underlying bridges.
     * Uses an exact-set lookup for stability (no regex at runtime).
     */
    private static boolean isBridgeStartFunctionKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (BRIDGE_START_FUNCTION_KEY_PREFIXES.contains(key)) {
            return true;
        }
        // Generic prefix guard for any future LI.FI facet variants
        return key.startsWith("startbridgetokensvia") || key.startsWith("swapandstartbridgetokensvia");
    }
}
