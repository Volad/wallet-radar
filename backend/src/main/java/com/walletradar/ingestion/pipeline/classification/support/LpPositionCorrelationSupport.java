package com.walletradar.ingestion.pipeline.classification.support;

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
        if (view == null || type == null || !isPositionScopedLpType(type)) {
            return null;
        }
        BigInteger tokenId = resolvePositionTokenId(view);
        if (tokenId == null || tokenId.signum() < 0) {
            return null;
        }
        String networkId = view.networkId() == null ? "unknown" : view.networkId().name().toLowerCase(Locale.ROOT);
        String protocolSlug = normalizeProtocolName(protocolName);
        return "lp-position:" + networkId + ":" + protocolSlug + ":" + tokenId.toString();
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

    private static String normalizeProtocolName(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder(protocolName.length());
        for (char ch : protocolName.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '-') {
                builder.append('-');
            }
        }
        String normalized = builder.toString();
        if (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }
}
