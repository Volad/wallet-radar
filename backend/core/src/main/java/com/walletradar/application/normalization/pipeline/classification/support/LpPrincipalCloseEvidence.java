package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
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
    // PancakeSwap V3 MasterChef contracts (farm for staked CL positions).
    // withdraw(tokenId, to) on these addresses = farm-unstake + CAKE harvest, never a principal exit.
    private static final Set<String> MASTERCHEF_V3_ADDRESSES = Set.of(
            "0x5e09acf80c0296740ec5d6f643005a4ef8daa694", // Arbitrum
            "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3"  // BASE
    );
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
        // Only downgrade to LP_FEE_CLAIM when liquidity=0 in the embedded decreaseLiquidity
        // AND all inflows are fee-only. Prevents false-positives on genuine CAKE-only exits
        // from out-of-range concentrated-liquidity pools.
        if (isHarvestOnlyRewardPattern(movementLegs) && hasZeroLiquidityDecrease(view)) {
            return NormalizedTransactionType.LP_FEE_CLAIM;
        }
        // MasterChef withdraw(tokenId, to) — direct 0x00f714ce call on farm contract.
        // Unstakes NFT from farm and distributes CAKE rewards; LP principal stays in pool.
        // Discriminated from NPM burn() (same selector) by ERC721 Transfer direction:
        // MasterChef returns NFT to wallet (Transfer(MasterChef→wallet)) whereas NPM burn
        // destroys it (Transfer(wallet→0x0)); hasAnyErc721TransferToWallet is true only for harvest.
        if (isHarvestOnlyRewardPattern(movementLegs) && isMasterChefWithdrawDirectCall(view)) {
            return NormalizedTransactionType.LP_FEE_CLAIM;
        }
        return type;
    }

    public static NormalizedTransactionType refineFinalExitType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        NormalizedTransactionType refined = refineLifecycleType(view, movementLegs, type);
        if (refined != NormalizedTransactionType.LP_EXIT) {
            return refined;
        }
        if (isFullPositionClose(view)) {
            return NormalizedTransactionType.LP_EXIT_FINAL;
        }
        return refined;
    }

    private static boolean isFullPositionClose(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        String methodId = normalizeSelector(view.methodId());
        if (BURN_SELECTOR.equals(methodId) && !isMasterChefWithdrawDirectCall(view)) {
            return true;
        }
        String inputData = view.inputData();
        if (inputData != null && CalldataDecodingSupport.containsEmbeddedSelector(inputData, BURN_SELECTOR)) {
            return !isMasterChefWithdrawDirectCall(view);
        }
        // A decreaseLiquidity(liquidity=0) multicall is a fee-collection pattern, NOT a full close.
        // Only treat it as a final exit when the BURN selector is also present (burns the position NFT).
        return false;
    }

    private static boolean isMasterChefWithdrawDirectCall(OnChainRawTransactionView view) {
        if (view == null) return false;
        String methodId = normalizeSelector(view.methodId());
        if (!BURN_SELECTOR.equals(methodId)) return false;
        // NPM burn(tokenId) and MasterChef withdraw(tokenId, to) share selector 0x00f714ce.
        // Primary discriminant: the `to` address of the transaction.
        //   - MasterChef withdraw: tx.to = MasterChef contract (known set)
        //   - NPM burn: tx.to = Position Manager (not in MASTERCHEF_V3_ADDRESSES)
        // Fallback for cases where toAddress is unavailable: check ERC721 Transfer direction.
        //   - MasterChef withdraw: emits Transfer(MasterChef→wallet) → hasAnyErc721TransferToWallet=true
        //   - NPM burn: emits Transfer(wallet→0x0) → hasAnyErc721TransferToWallet=false
        // If neither check is conclusive, fall through to LP_EXIT (safe).
        String toAddr = view.toAddress();
        boolean knownMasterChef = toAddr != null && MASTERCHEF_V3_ADDRESSES.contains(toAddr.toLowerCase(java.util.Locale.ROOT));
        if (!knownMasterChef && !LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)) {
            return false;
        }
        // Guard: if calldata also embeds decreaseLiquidity, this is a real principal exit
        String inputData = view.inputData();
        return inputData == null
                || !CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR);
    }

    static boolean hasZeroLiquidityDecrease(OnChainRawTransactionView view) {
        if (view == null) return false;
        String inputData = view.inputData();
        if (inputData == null || inputData.isBlank()) return false;
        String data = inputData.startsWith("0x") ? inputData.substring(2) : inputData;
        String selectorHex = DECREASE_LIQUIDITY_SELECTOR.startsWith("0x")
                ? DECREASE_LIQUIDITY_SELECTOR.substring(2) : DECREASE_LIQUIDITY_SELECTOR;
        // Only match at byte-aligned positions (even char index) to avoid false positives when
        // the selector bytes coincidentally appear as a suffix of another word (e.g. 40c49ccbe).
        int idx = data.indexOf(selectorHex);
        while (idx >= 0 && idx % 2 != 0) {
            idx = data.indexOf(selectorHex, idx + 1);
        }
        if (idx < 0) return false;
        // After 4-byte selector (8 hex chars): skip tokenId (64 hex), then read liquidity (64 hex)
        int liquidityStart = idx + 8 + 64;
        int liquidityEnd   = liquidityStart + 64;
        if (data.length() < liquidityEnd) return false;
        return data.substring(liquidityStart, liquidityEnd).matches("0{64}");
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
            // ERC-721 Transfer has tokenId as 4th indexed topic (4 total); ERC-20 Transfer only has 3.
            // Requiring 4 topics prevents ERC-20 outbound transfers (e.g. token sold/sent) from being
            // misidentified as NFT exits, which would incorrectly signal principal close evidence.
            if (topics.size() < 4) {
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
