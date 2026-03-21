package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Conservative detection for known non-economic admin/config contract calls.
 */
public final class AdminConfigSupport {

    private static final Set<String> KNOWN_ADMIN_METHOD_IDS = Set.of(
            "0xfa6e671d", // setRelayerApproval
            "0xcc53287f", // lockdown
            "0x14717656", // addOwnerWithThreshold
            "0x5a3b74b9", // setUserUseReserveAsCollateral
            "0xe584b654"  // approveForAll
    );

    private static final Map<String, Set<String>> CONTRACT_SCOPED_METHOD_IDS = Map.of(
            "0x426fa03fb86e510d0dd9f70335cf102a98b10875", Set.of("0xe32954eb"), // Basenames L2 Resolver
            "0xd8ae986159e350b6535539b8a1e488658452f25e", Set.of("0x4c96a389")  // Fluid Wallet Factory Proxy
    );

    private static final Set<String> KNOWN_ADMIN_FUNCTION_PREFIXES = Set.of(
            "setrelayerapproval(",
            "lockdown(",
            "addownerwiththreshold(",
            "setuserusereserveascollateral(",
            "approveforall(",
            "setapprovalforall("
    );

    private AdminConfigSupport() {
    }

    public static Optional<AdminConfigMatch> match(OnChainRawTransactionView view, boolean hasNonFeeMovement) {
        if (view == null || hasNonFeeMovement) {
            return Optional.empty();
        }

        String methodId = view.methodId();
        if (KNOWN_ADMIN_METHOD_IDS.contains(methodId)) {
            return Optional.of(new AdminConfigMatch(ClassificationSource.METHOD_ID, ConfidenceLevel.HIGH));
        }

        String normalizedContract = OnChainRawTransactionView.normalizeAddress(view.toAddress());
        if (normalizedContract != null) {
            Set<String> supportedSelectors = CONTRACT_SCOPED_METHOD_IDS.get(normalizedContract);
            if (supportedSelectors != null && supportedSelectors.contains(methodId)) {
                return Optional.of(new AdminConfigMatch(ClassificationSource.METHOD_ID, ConfidenceLevel.HIGH));
            }
        }

        String functionName = view.functionName();
        if (functionName != null) {
            String normalizedFunction = functionName.trim().toLowerCase(Locale.ROOT);
            for (String prefix : KNOWN_ADMIN_FUNCTION_PREFIXES) {
                if (normalizedFunction.startsWith(prefix)) {
                    return Optional.of(new AdminConfigMatch(ClassificationSource.FUNCTION_NAME, ConfidenceLevel.MEDIUM));
                }
            }
        }

        return Optional.empty();
    }

    public record AdminConfigMatch(
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence
    ) {
    }
}
