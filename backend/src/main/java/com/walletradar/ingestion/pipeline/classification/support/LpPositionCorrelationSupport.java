package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/**
 * Resolves deterministic concentrated-LP position identity for replay continuity.
 */
public final class LpPositionCorrelationSupport {

    private static final String MINT_SELECTOR = "0x88316456";
    private static final String STRUCT_MINT_SELECTOR = "0xb5007d1f";
    private static final String INCREASE_LIQUIDITY_SELECTOR = "0x4f1eb3d8";
    private static final String MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR = "0x219f5d17";
    private static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
    private static final String COLLECT_SELECTOR = "0xfc6f7865";
    private static final String BURN_SELECTOR = "0x00f714ce";
    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String MODIFY_LIQUIDITIES_SELECTOR = "0xdd46508f";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final int ACTION_INCREASE_LIQUIDITY = 0x00;
    private static final int ACTION_DECREASE_LIQUIDITY = 0x01;
    private static final int ACTION_MINT_POSITION = 0x02;
    private static final int ACTION_BURN_POSITION = 0x03;
    private static final int ACTION_INCREASE_LIQUIDITY_FROM_DELTAS = 0x04;

    private LpPositionCorrelationSupport() {
    }

    public static String correlationId(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            String protocolName
    ) {
        return lifecycleCorrelationId(view, type, protocolName);
    }

    /**
     * Resolves the CL-NFT position correlation id.
     *
     * <p>RC-1 (ADR-018): the position identity is keyed by the NonfungiblePositionManager
     * <b>contract address</b> (derived from {@code rawData.to}), NOT the protocol slug. Protocol
     * slug ↔ NFPM is not bijective (Uniswap V3 NFPM and V4 PoolManager both map to {@code uniswap}),
     * and the slug is classifier-dependent, so a slug-keyed identity re-admits entry/exit splits.
     * The contract address is identical for the LP_ENTRY and LP_EXIT of the same position, so the
     * pool can never split. The {@code protocolName} argument is retained for signature
     * compatibility only and no longer participates in the identity (it is display-only).
     */
    public static String lifecycleCorrelationId(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            String protocolName
    ) {
        if (view == null || type == null || !supportsLpPositionCorrelation(type)) {
            return null;
        }
        BigInteger tokenId = resolvePositionTokenId(view);
        if (tokenId == null || tokenId.signum() < 0) {
            return null;
        }
        return contractKeyedCorrelationId(view, tokenId.toString());
    }

    /**
     * Builds {@code lp-position:<network>:<nfpmContractLowercased>:<tokenId>} from an already
     * resolved {@code tokenId}. Returns {@code null} when the NFPM contract cannot be resolved
     * (fail-safe — never fabricate a slug-keyed identity).
     */
    public static String contractKeyedCorrelationId(OnChainRawTransactionView view, String tokenId) {
        if (view == null || tokenId == null || tokenId.isBlank()) {
            return null;
        }
        String nfpmContract = resolvePositionManagerContract(view);
        if (nfpmContract == null) {
            return null;
        }
        return contractKeyedCorrelationId(view.networkId(), nfpmContract, tokenId);
    }

    /**
     * Pure builder: {@code lp-position:<network>:<contractLowercased>:<tokenId>} from an explicit
     * (already canonicalized) position-manager contract. RC-5 callers pass the wrapper-canonicalized
     * NFPM contract here so a staked position keys to the underlying NFPM, not the farming wrapper.
     */
    public static String contractKeyedCorrelationId(NetworkId networkId, String contract, String tokenId) {
        String normalizedContract = OnChainRawTransactionView.normalizeAddress(contract);
        if (normalizedContract == null || tokenId == null || tokenId.isBlank()) {
            return null;
        }
        String network = networkId == null ? "unknown" : networkId.name().toLowerCase(Locale.ROOT);
        return "lp-position:" + network + ":" + normalizedContract + ":" + tokenId.trim();
    }

    /**
     * Resolves the CL-NFT position correlation id from an <b>explicit canonical position-manager
     * contract</b> (RC-5): the classifier resolves the interacted contract, canonicalizes a known
     * staking/farming wrapper to the underlying NFPM, and passes the NFPM here. Returns {@code null}
     * when the type is not position-scoped, no tokenId resolves, or the contract is blank.
     */
    public static String contractKeyedLifecycleCorrelationId(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            String canonicalContract
    ) {
        if (view == null || type == null || !supportsLpPositionCorrelation(type)) {
            return null;
        }
        String tokenId = positionTokenId(view);
        if (tokenId == null) {
            return null;
        }
        return contractKeyedCorrelationId(view.networkId(), canonicalContract, tokenId);
    }

    /**
     * The resolved CL position tokenId (decimal string) or {@code null} when none is present in
     * calldata / multicall / mint-burn logs.
     */
    public static String positionTokenId(OnChainRawTransactionView view) {
        if (view == null) {
            return null;
        }
        BigInteger tokenId = resolvePositionTokenId(view);
        return (tokenId == null || tokenId.signum() < 0) ? null : tokenId.toString();
    }

    /**
     * The NonfungiblePositionManager contract that identifies the CL position (lowercased
     * {@code 0x…}).
     *
     * <p>Resolution order (RC-1):
     * <ol>
     *   <li>the position-NFT ERC-721 contract — the {@code address} of the ERC-721 {@code Transfer}
     *       log that mints (zero→wallet) or burns (wallet→zero) the position NFT. This is the true
     *       NFPM and is router-independent, so a router-wrapped entry and a direct exit still resolve
     *       to the same contract;</li>
     *   <li>{@code rawData.to} (the interacted position-manager contract) — for direct NFPM calls
     *       this equals the log address; it also covers {@code increaseLiquidity}/partial-decrease
     *       events that carry no mint/burn log;</li>
     *   <li>the interaction recipient — when the suppressed transfer-row path hides {@code to}.</li>
     * </ol>
     */
    public static String resolvePositionManagerContract(OnChainRawTransactionView view) {
        if (view == null) {
            return null;
        }
        String fromLog = resolvePositionNftContractFromLogs(view);
        if (fromLog != null) {
            return fromLog;
        }
        String contract = view.toAddress();
        if (contract == null) {
            contract = view.interactionToAddress();
        }
        return OnChainRawTransactionView.normalizeAddress(contract);
    }

    private static String resolvePositionNftContractFromLogs(OnChainRawTransactionView view) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (topics.size() < 4 || !ERC721_TRANSFER_TOPIC.equals(topics.getFirst())) {
                continue;
            }
            String from = topicAddress(topics.get(1));
            String to = topicAddress(topics.get(2));
            boolean mintToWallet = zeroAddressTopic(topics.get(1)) && wallet.equals(to);
            boolean burnFromWallet = zeroAddressTopic(topics.get(2)) && wallet.equals(from);
            if (!mintToWallet && !burnFromWallet) {
                continue;
            }
            String logAddress = OnChainRawTransactionView.normalizeAddress(stringValue(log.get("address")));
            if (logAddress != null) {
                return logAddress;
            }
        }
        return null;
    }

    public static boolean supportsLpPositionCorrelation(NormalizedTransactionType type) {
        return isPositionScopedLpType(type) || type == NormalizedTransactionType.LP_FEE_CLAIM;
    }

    public static boolean hasDecreaseOrBurnActionInCalldata(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        String methodId = normalizeSelector(view.methodId());
        String inputData = view.inputData();
        if (DECREASE_LIQUIDITY_SELECTOR.equals(methodId) || BURN_SELECTOR.equals(methodId)) {
            return true;
        }
        if (inputData == null || inputData.isBlank()) {
            return false;
        }
        if (CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, BURN_SELECTOR)) {
            return true;
        }
        if (!MODIFY_LIQUIDITIES_SELECTOR.equals(methodId)
                && !CalldataDecodingSupport.containsEmbeddedSelector(inputData, MODIFY_LIQUIDITIES_SELECTOR)) {
            return false;
        }
        String unlockData = CalldataDecodingSupport.decodeDynamicBytesArgument(inputData, 0);
        if (unlockData == null || unlockData.isBlank()) {
            return false;
        }
        String actions = CalldataDecodingSupport.decodeTupleDynamicBytesArgument(unlockData, 0);
        if (actions == null || actions.length() <= 2) {
            return false;
        }
        String actionBytes = actions.startsWith("0x") ? actions.substring(2) : actions;
        for (int index = 0; index + 2 <= actionBytes.length(); index += 2) {
            int action = Integer.parseInt(actionBytes.substring(index, index + 2), 16);
            if (action == ACTION_DECREASE_LIQUIDITY || action == ACTION_BURN_POSITION) {
                return true;
            }
        }
        return false;
    }

    public static boolean requiresReceiptClarification(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null || !isPositionScopedLpType(type)) {
            return false;
        }
        if (resolvePositionTokenId(view) != null) {
            return false;
        }
        return isMintShapeWithoutPersistedTokenId(view);
    }

    public static boolean isPositionScopedLpType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static BigInteger resolvePositionTokenId(OnChainRawTransactionView view) {
        BigInteger direct = decodeDirectTokenId(view.methodId(), view.inputData());
        if (direct != null) {
            return direct;
        }
        BigInteger fromMulticall = decodeMulticallTokenId(view.inputData());
        if (fromMulticall != null) {
            return fromMulticall;
        }
        return decodeMintedTokenIdFromLogs(view);
    }

    private static boolean isMintShapeWithoutPersistedTokenId(OnChainRawTransactionView view) {
        String methodId = normalizeSelector(view.methodId());
        if (MINT_SELECTOR.equals(methodId)
                || STRUCT_MINT_SELECTOR.equals(methodId)) {
            return true;
        }
        // RC-6 (ADR-018): a direct Uniswap-V4 modifyLiquidities MINT assigns its tokenId on-chain
        // (absent from calldata, present only in the resulting ERC-721 mint log). Treat it as a
        // receipt-clarification mint shape so identity keys to the full PositionManager + minted
        // tokenId — never the truncated-contract aggregate. Pure decrease/burn modifyLiquidities
        // carry the tokenId in calldata and are resolved before this method (so they are excluded).
        if (MODIFY_LIQUIDITIES_SELECTOR.equals(methodId)) {
            return hasMintActionInModifyLiquidities(view.inputData())
                    || LpPositionLifecycleSupport.hasPositionNftMintLog(view);
        }
        if (!MULTICALL_SELECTOR.equals(methodId)) {
            return false;
        }
        String inputData = view.inputData();
        return CalldataDecodingSupport.containsEmbeddedSelector(inputData, MINT_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, STRUCT_MINT_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, MODIFY_LIQUIDITIES_SELECTOR)
                || LpPositionLifecycleSupport.hasPositionNftMintLog(view);
    }

    private static BigInteger decodeDirectTokenId(String methodId, String inputData) {
        String selector = normalizeSelector(methodId);
        if (selector == null || inputData == null || inputData.isBlank()) {
            return null;
        }
        return switch (selector) {
            case INCREASE_LIQUIDITY_SELECTOR,
                    MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR,
                    DECREASE_LIQUIDITY_SELECTOR,
                    COLLECT_SELECTOR,
                    BURN_SELECTOR -> CalldataDecodingSupport.decodeUint256Argument(inputData, 0);
            case MODIFY_LIQUIDITIES_SELECTOR -> decodeModifyLiquiditiesTokenId(inputData);
            default -> null;
        };
    }

    private static BigInteger decodeMulticallTokenId(String inputData) {
        if (inputData == null || inputData.isBlank()) {
            return null;
        }
        for (String subcall : CalldataDecodingSupport.decodeDynamicBytesArrayElements(inputData)) {
            String selector = normalizeSelector(subcall);
            if (selector == null) {
                continue;
            }
            if (!selector.equals(INCREASE_LIQUIDITY_SELECTOR)
                    && !selector.equals(MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR)
                    && !selector.equals(DECREASE_LIQUIDITY_SELECTOR)
                    && !selector.equals(COLLECT_SELECTOR)
                    && !selector.equals(BURN_SELECTOR)
                    && !selector.equals(MODIFY_LIQUIDITIES_SELECTOR)) {
                continue;
            }
            BigInteger tokenId = selector.equals(MODIFY_LIQUIDITIES_SELECTOR)
                    ? decodeModifyLiquiditiesTokenId(subcall)
                    : CalldataDecodingSupport.decodeUint256Argument(subcall, 0);
            if (tokenId != null) {
                return tokenId;
            }
        }
        return null;
    }

    private static BigInteger decodeModifyLiquiditiesTokenId(String inputData) {
        String unlockData = CalldataDecodingSupport.decodeDynamicBytesArgument(inputData, 0);
        if (unlockData == null || unlockData.isBlank()) {
            return null;
        }
        String actions = CalldataDecodingSupport.decodeTupleDynamicBytesArgument(unlockData, 0);
        List<String> params = CalldataDecodingSupport.decodeTupleDynamicBytesArrayElements(unlockData, 1);
        if (actions == null || actions.length() <= 2 || params.isEmpty()) {
            return null;
        }
        String actionBytes = actions.substring(2);
        int actionCount = actionBytes.length() / 2;
        int pairCount = Math.min(actionCount, params.size());
        for (int index = 0; index < pairCount; index++) {
            String actionHex = actionBytes.substring(index * 2, (index * 2) + 2);
            int action;
            try {
                action = Integer.parseInt(actionHex, 16);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!actionCarriesExistingPositionTokenId(action)) {
                continue;
            }
            BigInteger tokenId = CalldataDecodingSupport.decodeTupleUint256Argument(params.get(index), 0);
            if (tokenId != null && tokenId.signum() >= 0) {
                return tokenId;
            }
        }
        return null;
    }

    /**
     * RC-6: true when a V4 {@code modifyLiquidities} unlock-data action list contains a
     * {@code MINT_POSITION} (0x02) action (a brand-new position whose tokenId is assigned on-chain).
     */
    private static boolean hasMintActionInModifyLiquidities(String inputData) {
        if (inputData == null || inputData.isBlank()) {
            return false;
        }
        String unlockData = CalldataDecodingSupport.decodeDynamicBytesArgument(inputData, 0);
        if (unlockData == null || unlockData.isBlank()) {
            return false;
        }
        String actions = CalldataDecodingSupport.decodeTupleDynamicBytesArgument(unlockData, 0);
        if (actions == null || actions.length() <= 2) {
            return false;
        }
        String actionBytes = actions.startsWith("0x") ? actions.substring(2) : actions;
        for (int index = 0; index + 2 <= actionBytes.length(); index += 2) {
            int action;
            try {
                action = Integer.parseInt(actionBytes.substring(index, index + 2), 16);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (action == ACTION_MINT_POSITION) {
                return true;
            }
        }
        return false;
    }

    private static boolean actionCarriesExistingPositionTokenId(int action) {
        return action == ACTION_INCREASE_LIQUIDITY
                || action == ACTION_DECREASE_LIQUIDITY
                || action == ACTION_BURN_POSITION
                || action == ACTION_INCREASE_LIQUIDITY_FROM_DELTAS;
    }

    private static BigInteger decodeMintedTokenIdFromLogs(OnChainRawTransactionView view) {
        if (view == null) {
            return null;
        }
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return null;
        }
        String interactedContract = OnChainRawTransactionView.normalizeAddress(view.toAddress());
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (topics.size() < 4 || !ERC721_TRANSFER_TOPIC.equals(topics.getFirst())) {
                continue;
            }
            if (!zeroAddressTopic(topics.get(1)) || !wallet.equals(topicAddress(topics.get(2)))) {
                continue;
            }
            String logAddress = OnChainRawTransactionView.normalizeAddress(stringValue(log.get("address")));
            if (interactedContract != null && logAddress != null && !interactedContract.equals(logAddress)) {
                continue;
            }
            return parseTopicUint(topics.get(3));
        }
        return null;
    }

    private static List<String> normalizedTopics(Document log) {
        Object rawTopics = log == null ? null : log.get("topics");
        if (!(rawTopics instanceof List<?> topics)) {
            return List.of();
        }
        return topics.stream()
                .map(LpPositionCorrelationSupport::stringValue)
                .map(value -> value == null ? null : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static boolean zeroAddressTopic(String topic) {
        return topic != null
                && topic.matches("^0x0{24}[0-9a-f]{40}$")
                && topic.endsWith("0000000000000000000000000000000000000000");
    }

    private static String topicAddress(String topic) {
        if (topic == null || topic.length() != 66) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(topic.substring(26));
    }

    private static BigInteger parseTopicUint(String topic) {
        if (topic == null || !topic.startsWith("0x") || topic.length() != 66) {
            return null;
        }
        try {
            return new BigInteger(topic.substring(2), 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String normalizeSelector(String selector) {
        if (selector == null) {
            return null;
        }
        String normalized = selector.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x") || normalized.length() < 10) {
            return null;
        }
        return normalized.substring(0, 10);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }
}
