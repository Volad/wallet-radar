package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.Set;

/**
 * Known destination-side bridge settlement selectors that must classify as BRIDGE_IN on verified bridge contracts.
 */
public final class BridgeSettlementSupport {

    private static final Set<String> SETTLEMENT_SELECTORS = Set.of(
            "0x2e378115", // fillV3Relay
            "0xdeff4b24", // fillRelay
            "0xe2de2a03", // redeemWithFee
            "0xe5c1bf6e", // redeem(bytes cctpMsg,bytes cctpSigs)
            "0xcfc32570", // execute302
            "0x6befa3a5", // directFulfill
            "0xe4a974cc"  // expressExecuteWithToken
    );

    private BridgeSettlementSupport() {
    }

    public static boolean isSettlementSelector(OnChainRawTransactionView view) {
        return view != null && (SETTLEMENT_SELECTORS.contains(view.methodId())
                || containsAny(
                view.functionName(),
                "fillv3relay",
                "fillrelay",
                "redeemwithfee",
                "execute302",
                "directfulfill",
                "expressexecutewithtoken"
        ));
    }

    public static boolean requiresVerifiedBridgeEvidence(OnChainRawTransactionView view) {
        return view != null && ("0xe4a974cc".equals(view.methodId())
                || containsAny(view.functionName(), "expressexecutewithtoken"));
    }

    public static boolean requiresMethodAwareDispatch(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view
    ) {
        return isVerifiedBridgeContract(entry)
                && isSettlementSelector(view);
    }

    public static boolean isBridgeSettlement(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view
    ) {
        return requiresMethodAwareDispatch(entry, view);
    }

    private static boolean isVerifiedBridgeContract(ProtocolRegistryEntry entry) {
        if (entry == null || entry.family() != ProtocolRegistryFamily.BRIDGE) {
            return false;
        }
        return entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                || entry.role() == ProtocolRegistryRole.ROUTER;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
