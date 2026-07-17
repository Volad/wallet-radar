package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.normalization.support.PromoSpamTextSupport;
import org.bson.Document;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inbound-specific signals used to separate reward claims, plain inbound transfers, and promo/phishing dust.
 */
public final class InboundSignalSupport {

    private static final String AVALANCHE_HOMOGLYPH_USDC_CONTRACT = "0x318c6a3cb85952641cd253b2311b0cee30f44822";
    private static final String AVALANCHE_HOMOGLYPH_USDC_SENDER = "0x1a872b33479b10f57d308104004a4d5f57bf693f";
    private static final Set<String> ETHEREUM_SELF_DROP_PROMO_CONTRACTS = Set.of(
            "0x83819bf7e906bcf57e9f5b20453a2eff43f3845c",
            "0x522a8f36e23fe1c5018e28764fea161c5f951cad",
            "0xb62d08519a7cc5cbebee997468a7ee5546e89931"
    );
    private static final String BASE_BATCH_TRANSFER_PROMO_CONTRACT = "0x41e357ea17eed8e3ee32451f8e5cba824af58dbf";
    private static final String UNICHAIN_SEND_BATCH_PROMO_CONTRACT = "0x03c2868c6d7fd27575426f395ee081498b1120dd";
    private static final String POLYGON_ZHT_PROMO_CONTRACT = "0x90a9e2772d6b53c92ccbeaba6c31a02c22eac111";
    private static final String POLYGON_ZHT_PROMO_SENDER = "0x222884666fec64f6ab5368d9d7250d7103751f7a";
    private static final String ARBITRUM_XAUUSD_PROMO_CONTRACT = "0x51c25acea32ec4237fcde962ed7789d0941169bc";
    private static final String ARBITRUM_XAUUSD_PROMO_SENDER = "0x068a2418d4b1c8fda198b58b5035b8267675e40e";

    private static final Set<String> BASE_BATCH_TRANSFER_PROMO_MARKERS = Set.of(
            "surya",
            "surya pro"
    );
    private static final Set<String> BASE_MULTI_SEND_PROMO_MARKERS = Set.of(
            "tlou",
            "the  last of us",
            "swol",
            "snowy owl",
            "axome",
            "axolotl meme"
    );
    private static final Set<String> BASE_ARRAY_TRANSFER_PROMO_MARKERS = Set.of(
            "goofs",
            "goofs world",
            "pip",
            "pepium",
            "telegram @tronvanity88_bot"
    );
    private static final Set<String> BASE_BLANK_METHOD_PROMO_MARKERS = Set.of(
            "doodle",
            "doodle by virtuals",
            "nvd",
            "prime nvd",
            "oscar",
            "kpop oscar",
            "pickle",
            "face",
            "trump face",
            "dog",
            "stray dog"
    );
    private static final Set<String> ACHIVX_PROMO_MARKERS = Set.of(
            "achivx"
    );

    // Shared reward-claim core (RewardClaimSelectors) plus the generic claim(uint256,address,…)
    // selector that is claim-like for inbound classification but not a ScamFilter legit-bridge marker.
    private static final Set<String> CLAIM_LIKE_SELECTORS =
            RewardClaimSelectors.withExtra("0x5d4df3bf");

    private InboundSignalSupport() {
    }

    public static boolean isPromoPhishingInbound(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (isKnownPromoSpamFamily(view, movementLegs)) {
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
        return isKnownPromoSpamFamily(view, List.of());
    }

    public static boolean isKnownPromoSpamFamily(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view == null || view.networkId() == null) {
            return false;
        }
        if ("PLASMA".equals(view.networkId().name()) && "0x1939c1ff".equals(view.methodId())) {
            return hasInboundTokenToWallet(view);
        }
        if ("0xeec4378e".equals(view.methodId())) {
            return hasInboundTokenToWallet(view);
        }
        if ("BASE".equals(view.networkId().name())
                && "0xac9650d8".equals(view.methodId())
                && view.toAddress() == null
                && "multicall".equals(functionKey(view.functionName()))) {
            return hasInboundTokenToWallet(view);
        }
        return isAuditedPromoDistributionFamily(view, movementLegs);
    }

    private static boolean isAuditedPromoDistributionFamily(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!hasInboundTokenToWallet(view) || !hasInboundOnlyMovement(movementLegs)) {
            return false;
        }
        if (!view.explorerInternalTransfers().isEmpty()) {
            return false;
        }
        if (view.fromAddress() != null || view.toAddress() != null) {
            return false;
        }

        if (matchesRun12PromoInboundFamily(view)) {
            return true;
        }

        String networkId = view.networkId().name();
        String methodId = view.methodId();
        String functionKey = functionKey(view.functionName());

        if ("BASE".equals(networkId)) {
            if ("0xe34a5d4d".equals(methodId)
                    && "batchtransfer".equals(functionKey)
                    && hasInboundTokenMarker(view, BASE_BATCH_TRANSFER_PROMO_MARKERS)) {
                return true;
            }
            if ("0x9ec68f0f".equals(methodId)
                    && "multisend".equals(functionKey)
                    && hasInboundTokenMarker(view, BASE_MULTI_SEND_PROMO_MARKERS)) {
                return true;
            }
            if ("0xa06c1a33".equals(methodId)
                    && "transfer".equals(functionKey)
                    && hasInboundTokenMarker(view, BASE_ARRAY_TRANSFER_PROMO_MARKERS)) {
                return true;
            }
            if (functionKey.isBlank()
                    && hasInboundTokenMarker(view, BASE_BLANK_METHOD_PROMO_MARKERS)) {
                return true;
            }
        }

        if (("BASE".equals(networkId) || "ARBITRUM".equals(networkId) || "OPTIMISM".equals(networkId))
                && "0x88d695b2".equals(methodId)
                && "batchtransfer".equals(functionKey)
                && hasInboundTokenMarker(view, ACHIVX_PROMO_MARKERS)) {
            return true;
        }

        return false;
    }

    private static boolean matchesRun12PromoInboundFamily(OnChainRawTransactionView view) {
        String networkId = view.networkId().name();
        String methodId = view.methodId();
        String functionKey = functionKey(view.functionName());

        if ("AVALANCHE".equals(networkId)
                && "0xa9059cbb".equals(methodId)
                && "transfer".equals(functionKey)
                && hasInboundTransferFrom(view, AVALANCHE_HOMOGLYPH_USDC_CONTRACT, AVALANCHE_HOMOGLYPH_USDC_SENDER)) {
            return true;
        }

        if ("ETHEREUM".equals(networkId) && hasInboundSelfDropFromTokenContract(view, ETHEREUM_SELF_DROP_PROMO_CONTRACTS)) {
            return true;
        }

        if ("BASE".equals(networkId)
                && "0x1239ec8c".equals(methodId)
                && "batchtransfer".equals(functionKey)
                && hasInboundTransferOnContract(view, BASE_BATCH_TRANSFER_PROMO_CONTRACT)) {
            return true;
        }

        if ("UNICHAIN".equals(networkId)
                && "0x9f1b6858".equals(methodId)
                && "sendbatchtokens".equals(functionKey)
                && hasInboundTransferOnContract(view, UNICHAIN_SEND_BATCH_PROMO_CONTRACT)) {
            return true;
        }

        if ("POLYGON".equals(networkId)
                && "0xa9059cbb".equals(methodId)
                && "transfer".equals(functionKey)
                && hasInboundTransferFrom(view, POLYGON_ZHT_PROMO_CONTRACT, POLYGON_ZHT_PROMO_SENDER)) {
            return true;
        }

        return "ARBITRUM".equals(networkId)
                && hasInboundTransferFrom(view, ARBITRUM_XAUUSD_PROMO_CONTRACT, ARBITRUM_XAUUSD_PROMO_SENDER);
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

    private static boolean hasInboundTokenToWallet(OnChainRawTransactionView view) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (walletAddress.equals(view.tokenTransferTo(transfer))
                    && !walletAddress.equals(view.tokenTransferFrom(transfer))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInboundTransferOnContract(
            OnChainRawTransactionView view,
            String contractAddress
    ) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferTo(transfer))
                    || walletAddress.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            if (contractAddress.equalsIgnoreCase(normalizeAddress(view.tokenTransferContract(transfer)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInboundTransferFrom(
            OnChainRawTransactionView view,
            String contractAddress,
            String senderAddress
    ) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferTo(transfer))
                    || walletAddress.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            if (contractAddress.equalsIgnoreCase(normalizeAddress(view.tokenTransferContract(transfer)))
                    && senderAddress.equalsIgnoreCase(normalizeAddress(view.tokenTransferFrom(transfer)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInboundSelfDropFromTokenContract(
            OnChainRawTransactionView view,
            Set<String> tokenContracts
    ) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            String contractAddress = normalizeAddress(view.tokenTransferContract(transfer));
            String senderAddress = normalizeAddress(view.tokenTransferFrom(transfer));
            if (!walletAddress.equals(view.tokenTransferTo(transfer))
                    || walletAddress.equals(senderAddress)
                    || contractAddress == null
                    || senderAddress == null) {
                continue;
            }
            if (tokenContracts.contains(contractAddress) && contractAddress.equals(senderAddress)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasInboundTokenMarker(
            OnChainRawTransactionView view,
            Set<String> markers
    ) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferTo(transfer))
                    || walletAddress.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            if (matchesMarker(view.tokenTransferSymbol(transfer), markers)
                    || matchesMarker(view.tokenTransferName(transfer), markers)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesMarker(String value, Set<String> markers) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return markers.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static String functionKey(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return "";
        }
        String normalized = functionName.trim().toLowerCase();
        int signatureSeparator = normalized.indexOf('(');
        return signatureSeparator > 0 ? normalized.substring(0, signatureSeparator) : normalized;
    }
}
