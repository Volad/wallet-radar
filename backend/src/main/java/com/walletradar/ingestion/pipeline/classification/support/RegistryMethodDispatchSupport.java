package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.Set;

/**
 * Thin registry-backed routing helper for method-aware routers. Contains no final type selection.
 */
public final class RegistryMethodDispatchSupport {

    private static final Set<String> METHOD_AWARE_ROUTER_SELECTORS = Set.of(
            "0x7b939232",
            "0xac9650d8",
            "0xc16ae7a4",
            "0x3593564c",
            "0xae0b91e5"
    );

    private RegistryMethodDispatchSupport() {
    }

    public static boolean requiresMethodAwareDispatch(ProtocolRegistryEntry entry, OnChainRawTransactionView view) {
        if (entry == null || view == null) {
            return false;
        }
        if (BridgeSettlementSupport.requiresMethodAwareDispatch(entry, view)) {
            return true;
        }
        String functionName = view.functionName();
        if (METHOD_AWARE_ROUTER_SELECTORS.contains(view.methodId())) {
            return true;
        }
        if (entry.family() == ProtocolRegistryFamily.BRIDGE && "0x30c48952".equals(view.methodId())) {
            return true;
        }
        boolean routeLikeRole = entry.role() == ProtocolRegistryRole.ROUTER
                || entry.role() == ProtocolRegistryRole.EXCHANGE_ROUTER
                || entry.role() == ProtocolRegistryRole.POSITION_ROUTER
                || entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY;
        return routeLikeRole && containsAny(functionName, "multicall", "batch", "execute");
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
