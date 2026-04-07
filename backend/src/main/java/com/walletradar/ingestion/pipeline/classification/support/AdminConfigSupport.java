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
            "0xe584b654", // approveForAll
            "0x0de54ba0", // setMinterApproval
            "0xc04a8a10", // approveDelegation
            "0x5e528956", // convertFlexibleToLockup
            "0x110496e5"  // allow(address manager,bool)
    );

    private static final Map<String, Set<String>> CONTRACT_SCOPED_METHOD_IDS = Map.of(
            "0x426fa03fb86e510d0dd9f70335cf102a98b10875", Set.of("0xe32954eb"), // Basenames L2 Resolver
            "0xd8ae986159e350b6535539b8a1e488658452f25e", Set.of("0x4c96a389"), // Fluid Wallet Factory Proxy
            "0x4e1dcf7ad4e460cfd30791ccc4f9c8a4f820ec67", Set.of("0x1688f0b9"), // Safe proxy factory
            "0x41c914ee0c7e1a5edcd0295623e6dc557b5abf3c", Set.of("0x7ac09bf7", "0x310bd74b"), // Velodrome Voter
            "0x0914b58783e47fb72cfd88f4203628e966514e03", Set.of("0x1249c58b"), // Steer OG NFT mint
            "0x7c78b18f496d3d37c44de09da4a5a76eb34b7e74", Set.of("0x986d8002")  // Mantle attestation/NFT mint
    );

    private static final Set<String> KNOWN_ADMIN_FUNCTION_PREFIXES = Set.of(
            "setrelayerapproval(",
            "lockdown(",
            "addownerwiththreshold(",
            "setuserusereserveascollateral(",
            "approveforall(",
            "setapprovalforall(",
            "setminterapproval(",
            "approvedelegation(",
            "createproxywithnonce(",
            "vote(",
            "reset(",
            "allow(",
            "convertflexibletolockup(",
            "mintmj("
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

        if (isSelfNoOp(view)) {
            return Optional.of(new AdminConfigMatch(ClassificationSource.HEURISTIC, ConfidenceLevel.MEDIUM));
        }

        return Optional.empty();
    }

    private static boolean isSelfNoOp(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        if (!safeEquals(view.walletAddress(), view.toAddress())) {
            return false;
        }
        return view.explorerTokenTransfers().isEmpty()
                && view.explorerInternalTransfers().isEmpty()
                && view.persistedLogs().isEmpty();
    }

    private static boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    public record AdminConfigMatch(
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence
    ) {
    }
}
