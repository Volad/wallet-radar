package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.support.PromoSpamTextSupport;
import org.bson.Document;

import java.util.List;
import java.util.Set;

/**
 * Inbound-specific signals used to separate reward claims, plain inbound transfers, and promo/phishing dust.
 */
public final class InboundSignalSupport {

    private static final Set<String> CLAIM_LIKE_SELECTORS = Set.of(
            "0x9fb67b58", // claimWithRecipient
            "0x71ee95c0", // Angle claim
            "0xb7034f7e", // Compound claim
            "0xbe5013dc", // FLUID claim
            "0x5eac6239", // Pendle claim
            "0x5d4df3bf", // generic claim(uint256,address,...)
            "0x8b681820", // BSC claim
            "0x379607f5", // stream claim
            "0x2f52ebb7"  // merkle claim
    );

    private InboundSignalSupport() {
    }

    public static boolean isPromoPhishingInbound(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (isKnownPromoSpamFamily(view)) {
            return true;
        }
        String walletAddress = view.walletAddress();
        if (walletAddress == null || walletAddress.equals(view.fromAddress())) {
            return false;
        }
        if (!hasInboundOnlyMovement(movementLegs)) {
            return false;
        }

        boolean hasInboundTokenToWallet = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferTo(transfer))) {
                continue;
            }
            if (walletAddress.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            hasInboundTokenToWallet = true;
            if (PromoSpamTextSupport.isSuspiciousTokenText(view.tokenTransferSymbol(transfer))
                    || PromoSpamTextSupport.isSuspiciousTokenText(view.tokenTransferName(transfer))) {
                return true;
            }
        }
        if (!hasInboundTokenToWallet) {
            return false;
        }
        return PromoSpamTextSupport.isSuspiciousTokenText(view.functionName());
    }

    public static boolean isKnownPromoSpamFamily(OnChainRawTransactionView view) {
        if (view == null || view.networkId() == null) {
            return false;
        }
        if (!"PLASMA".equals(view.networkId().name()) || !"0x1939c1ff".equals(view.methodId())) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (PromoSpamTextSupport.isSuspiciousTokenText(view.tokenTransferSymbol(transfer))
                    || PromoSpamTextSupport.isSuspiciousTokenText(view.tokenTransferName(transfer))) {
                return true;
            }
        }
        return PromoSpamTextSupport.isSuspiciousTokenText(view.functionName());
    }

    public static boolean hasExplicitClaimSignal(OnChainRawTransactionView view) {
        String functionName = view.functionName();
        return hasExplicitClaimSelector(view)
                || (functionName != null && functionName.contains("claim"));
    }

    public static boolean hasExplicitClaimSelector(OnChainRawTransactionView view) {
        return CLAIM_LIKE_SELECTORS.contains(view.methodId());
    }

    public static boolean hasRewardLikeSignal(OnChainRawTransactionView view) {
        String functionName = view.functionName();
        if (functionName != null && containsAny(functionName, "claim", "reward", "rewards", "airdrop", "bonus", "rebate")) {
            return true;
        }
        return CLAIM_LIKE_SELECTORS.contains(view.methodId());
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInboundOnlyMovement(List<RawLeg> movementLegs) {
        boolean hasInbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0) {
                return false;
            }
            hasInbound = true;
        }
        return hasInbound;
    }
}
