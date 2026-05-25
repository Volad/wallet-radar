package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Relay / depository bridge patterns: solver payout inbounds and {@code startBridgeTokensViaRelayDepository} outbounds.
 */
public final class RelayBridgeClassificationSupport {

    public static final String RELAY_DEPOSITORY_METHOD_SELECTOR = "0x092e8fa4";
    private static final Set<String> RELAY_DEPOSITORY_FUNCTION_KEYS = Set.of(
            "startbridgetokensviarelaydepository"
    );

    private RelayBridgeClassificationSupport() {
    }

    public static boolean isRelayDepositoryBridgeOut(OnChainRawTransactionView view) {
        if (view == null || !matchesRelayDepositorySelector(view)) {
            return false;
        }
        return hasOutboundPrincipal(view);
    }

    public static Optional<ProtocolRegistryEntry> resolveRelayDepositoryBridgeEntry(
            ProtocolRegistryService protocolRegistryService,
            OnChainRawTransactionView view
    ) {
        if (protocolRegistryService == null || view == null || view.networkId() == null) {
            return Optional.empty();
        }
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putRelayBridgeCandidate(candidates, protocolRegistryService, view.networkId(), view.toAddress());
        String walletAddress = view.walletAddress();
        for (Document transfer : view.explorerTokenTransfers()) {
            if (walletAddress != null && !walletAddress.equalsIgnoreCase(view.tokenTransferFrom(transfer))) {
                continue;
            }
            putRelayBridgeCandidate(
                    candidates,
                    protocolRegistryService,
                    view.networkId(),
                    view.tokenTransferTo(transfer)
            );
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            if (walletAddress != null && !walletAddress.equalsIgnoreCase(view.internalTransferFrom(transfer))) {
                continue;
            }
            putRelayBridgeCandidate(
                    candidates,
                    protocolRegistryService,
                    view.networkId(),
                    view.internalTransferTo(transfer)
            );
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.values().iterator().next());
        }
        if (candidates.isEmpty() && matchesRelayDepositorySelector(view)) {
            return Optional.of(syntheticRelayBridgeEntry(view.networkId()));
        }
        return Optional.empty();
    }

    public static Optional<ProtocolRegistryEntry> resolveRelayPayoutInboundEntry(
            ProtocolRegistryService protocolRegistryService,
            OnChainRawTransactionView view
    ) {
        if (protocolRegistryService == null || view == null || view.networkId() == null) {
            return Optional.empty();
        }
        String walletAddress = view.walletAddress();
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        for (Document transfer : view.explorerTokenTransfers()) {
            if (walletAddress != null && !walletAddress.equalsIgnoreCase(view.tokenTransferTo(transfer))) {
                continue;
            }
            putRelayPayoutCandidate(
                    candidates,
                    protocolRegistryService,
                    view.networkId(),
                    view.tokenTransferFrom(transfer)
            );
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            if (walletAddress != null && !walletAddress.equalsIgnoreCase(view.internalTransferTo(transfer))) {
                continue;
            }
            putRelayPayoutCandidate(
                    candidates,
                    protocolRegistryService,
                    view.networkId(),
                    view.internalTransferFrom(transfer)
            );
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.values().iterator().next());
        }
        return Optional.empty();
    }

    public static boolean isRelayPayoutEntry(ProtocolRegistryEntry entry) {
        if (entry == null) {
            return false;
        }
        return isRelayProtocol(entry.protocolName())
                && entry.role() == ProtocolRegistryRole.GAS_PAYER;
    }

    public static boolean isRelayBridgeEntry(ProtocolRegistryEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.family() != ProtocolRegistryFamily.BRIDGE) {
            return false;
        }
        return entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                || entry.role() == ProtocolRegistryRole.BRIDGE_EXIT
                || entry.role() == ProtocolRegistryRole.ROUTER;
    }

    public static boolean isRelayProtocol(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return false;
        }
        return protocolName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains("relay");
    }

    private static boolean matchesRelayDepositorySelector(OnChainRawTransactionView view) {
        if (RELAY_DEPOSITORY_METHOD_SELECTOR.equalsIgnoreCase(view.methodId())) {
            return true;
        }
        String functionKey = functionKey(view.functionName());
        return RELAY_DEPOSITORY_FUNCTION_KEYS.contains(functionKey);
    }

    private static String functionKey(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return "";
        }
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('(');
        if (separator > 0) {
            return normalized.substring(0, separator);
        }
        return normalized;
    }

    private static boolean hasOutboundPrincipal(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        boolean hasOutbound = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            if (view.tokenTransferQuantity(transfer) != null && view.tokenTransferQuantity(transfer).signum() < 0) {
                hasOutbound = true;
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (!view.internalTransferErrored(transfer)
                    && view.internalTransferQuantity(transfer) != null
                    && view.internalTransferQuantity(transfer).signum() < 0) {
                hasOutbound = true;
            }
        }
        if (view.rawValue() != null && view.rawValue().signum() > 0) {
            hasOutbound = true;
        }
        return hasOutbound;
    }

    private static void putRelayBridgeCandidate(
            Map<String, ProtocolRegistryEntry> candidates,
            ProtocolRegistryService protocolRegistryService,
            NetworkId networkId,
            String address
    ) {
        protocolRegistryService.lookup(networkId, address)
                .filter(entry -> isRelayBridgeEntry(entry) || isRelayProtocol(entry.protocolName()))
                .ifPresent(entry -> candidates.putIfAbsent(entry.contractAddress(), entry));
    }

    private static void putRelayPayoutCandidate(
            Map<String, ProtocolRegistryEntry> candidates,
            ProtocolRegistryService protocolRegistryService,
            NetworkId networkId,
            String address
    ) {
        protocolRegistryService.lookup(networkId, address)
                .filter(RelayBridgeClassificationSupport::isRelayPayoutEntry)
                .ifPresent(entry -> candidates.putIfAbsent(entry.contractAddress(), entry));
    }

    private static ProtocolRegistryEntry syntheticRelayBridgeEntry(NetworkId networkId) {
        return new ProtocolRegistryEntry(
                "",
                Set.of(networkId),
                ProtocolRegistryFamily.BRIDGE,
                ProtocolRegistryRole.BRIDGE_ENTRY,
                ProtocolRegistryEventType.BRIDGE_OUT,
                com.walletradar.domain.common.ConfidenceLevel.MEDIUM,
                "Relay",
                "Depository",
                false,
                null
        );
    }

    public static boolean onlyInbound(List<RawLeg> movementLegs) {
        boolean hasInbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0) {
                return false;
            }
            hasInbound = true;
        }
        return hasInbound;
    }

    public static boolean onlyOutbound(List<RawLeg> movementLegs) {
        boolean hasOutbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() > 0) {
                return false;
            }
            hasOutbound = true;
        }
        return hasOutbound;
    }
}
