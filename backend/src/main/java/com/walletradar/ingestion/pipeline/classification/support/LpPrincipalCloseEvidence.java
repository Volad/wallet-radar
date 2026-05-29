package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Principal LP close vs fee-claim gate from calldata, persisted logs, and movement shape.
 */
public final class LpPrincipalCloseEvidence {

    private static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
    private static final String BURN_SELECTOR = "0x00f714ce";
    private static final String COLLECT_SELECTOR = "0xfc6f7865";
    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String MODIFY_LIQUIDITIES_SELECTOR = "0xdd46508f";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String MODIFY_LIQUIDITY_TOPIC =
            "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec";
    private static final Set<String> STABLE_HARVEST_SYMBOLS = Set.of("USDC", "USDT", "USDT0", "USD₮0");
    private static final BigDecimal DUST_STABLECOIN_THRESHOLD = new BigDecimal("100");

    private LpPrincipalCloseEvidence() {
    }

    public static NormalizedTransactionType refineLifecycleType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        if (type == null || !isPrincipalExitCandidate(type)) {
            return type;
        }
        if (!hasPositionReductionEvidence(view)) {
            return NormalizedTransactionType.LP_FEE_CLAIM;
        }
        return type;
    }

    public static boolean hasPositionReductionEvidence(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        String methodId = normalizeSelector(view.methodId());
        if (DECREASE_LIQUIDITY_SELECTOR.equals(methodId) || BURN_SELECTOR.equals(methodId)) {
            return true;
        }
        String inputData = view.inputData();
        if (inputData != null && !inputData.isBlank()) {
            if (CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR)
                    || CalldataDecodingSupport.containsEmbeddedSelector(inputData, BURN_SELECTOR)) {
                return true;
            }
            if (containsEmbeddedSelector(inputData, COLLECT_SELECTOR)
                    && LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)) {
                return true;
            }
            if (CalldataDecodingSupport.containsEmbeddedSelector(inputData, MODIFY_LIQUIDITIES_SELECTOR)
                    && LpPositionCorrelationSupport.hasDecreaseOrBurnActionInCalldata(view)) {
                return true;
            }
        }
        if (hasErc721TransferFromWallet(view)) {
            return true;
        }
        if (hasNegativeModifyLiquidityLog(view)) {
            return true;
        }
        if (MODIFY_LIQUIDITIES_SELECTOR.equals(methodId)
                && (hasNegativeModifyLiquidityLog(view)
                || LpPositionCorrelationSupport.hasDecreaseOrBurnActionInCalldata(view))) {
            return true;
        }
        if (MULTICALL_SELECTOR.equals(methodId)
                && inputData != null
                && containsEmbeddedSelector(inputData, COLLECT_SELECTOR)
                && LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)) {
            return true;
        }
        return false;
    }

    private static boolean containsEmbeddedSelector(String inputData, String selector) {
        return CalldataDecodingSupport.containsEmbeddedSelector(inputData, selector);
    }

    public static boolean isHarvestOnlyRewardPattern(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return false;
        }
        List<RawLeg> effectiveLegs = movementLegs.stream()
                .filter(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() != 0)
                .toList();
        if (effectiveLegs.isEmpty()) {
            return false;
        }
        if (effectiveLegs.stream().anyMatch(leg -> leg.quantityDelta().signum() < 0)) {
            return false;
        }
        if (effectiveLegs.stream().allMatch(leg -> "CAKE".equalsIgnoreCase(leg.assetSymbol()))) {
            return true;
        }
        if (effectiveLegs.size() == 1 && isDustStablecoinLeg(effectiveLegs.getFirst())) {
            return true;
        }
        return false;
    }

    private static boolean isPrincipalExitCandidate(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static boolean isDustStablecoinLeg(RawLeg leg) {
        if (leg == null || !isStableHarvestSymbol(leg.assetSymbol())) {
            return false;
        }
        return leg.quantityDelta().abs().compareTo(DUST_STABLECOIN_THRESHOLD) <= 0;
    }

    private static boolean isStableHarvestSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return STABLE_HARVEST_SYMBOLS.contains(normalized);
    }

    private static boolean hasErc721TransferFromWallet(OnChainRawTransactionView view) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!ERC721_TRANSFER_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            List<String> topics = normalizedTopics(log);
            if (topics.size() < 3) {
                continue;
            }
            if (wallet.equals(topicAddress(topics.get(1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNegativeModifyLiquidityLog(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!MODIFY_LIQUIDITY_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            BigInteger amount0 = decodeSignedWord(logData(log), 0);
            BigInteger amount1 = decodeSignedWord(logData(log), 1);
            if (isNegative(amount0) || isNegative(amount1)) {
                return true;
            }
        }
        return false;
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

    private static String firstTopic(Document log) {
        List<String> topics = normalizedTopics(log);
        return topics.isEmpty() ? null : topics.getFirst();
    }

    private static List<String> normalizedTopics(Document log) {
        if (log == null) {
            return List.of();
        }
        Object topicsObject = log.get("topics");
        if (!(topicsObject instanceof List<?> topics)) {
            return List.of();
        }
        return topics.stream()
                .map(LpPrincipalCloseEvidence::stringValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::toLowerCase)
                .toList();
    }

    private static String logData(Document log) {
        return stringValue(log == null ? null : log.get("data"));
    }

    private static String topicAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private static BigInteger decodeSignedWord(String data, int wordIndex) {
        if (data == null) {
            return null;
        }
        String normalized = data.startsWith("0x") ? data.substring(2) : data;
        int start = wordIndex * 64;
        int end = start + 64;
        if (normalized.length() < end) {
            return null;
        }
        String word = normalized.substring(start, end);
        if (!word.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        BigInteger unsigned = new BigInteger(word, 16);
        if (word.charAt(0) >= '8') {
            return unsigned.subtract(BigInteger.ONE.shiftLeft(256));
        }
        return unsigned;
    }

    private static boolean isNegative(BigInteger value) {
        return value != null && value.signum() < 0;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
