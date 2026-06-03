package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * LP-position NFT lifecycle helpers for position managers and DEX stake contracts.
 */
public final class LpPositionLifecycleSupport {

    private static final String MINT_SELECTOR = "0x88316456";
    private static final String STRUCT_MINT_SELECTOR = "0xb5007d1f";
    private static final String INCREASE_LIQUIDITY_SELECTOR = "0x4f1eb3d8";
    private static final String MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR = "0x219f5d17";
    private static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
    private static final String COLLECT_SELECTOR = "0xfc6f7865";
    private static final String BURN_SELECTOR = "0x00f714ce";
    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String MODIFY_LIQUIDITIES_SELECTOR = "0xdd46508f";
    private static final String STAKE_DEPOSIT_SELECTOR = "0xb6b55f25";
    private static final String STAKE_WITHDRAW_SELECTOR = "0x2e1a7d4d";
    private static final String SAFE_TRANSFER_FROM_SELECTOR = "0x42842e0e";
    private static final String SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR = "0xb88d4fde";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String MODIFY_LIQUIDITY_TOPIC =
            "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec";

    private LpPositionLifecycleSupport() {
    }

    public static NormalizedTransactionType resolvePositionManagerType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            Optional<ProtocolRegistryEntry> decodedFromEntry,
            Optional<ProtocolRegistryEntry> decodedToEntry
    ) {
        return switch (String.valueOf(view.methodId())) {
            case MINT_SELECTOR, STRUCT_MINT_SELECTOR, INCREASE_LIQUIDITY_SELECTOR, MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR ->
                    hasOutboundNonFeeLeg(movementLegs) || hasPositionNftMintLog(view)
                            ? NormalizedTransactionType.LP_ENTRY
                            : null;
            case DECREASE_LIQUIDITY_SELECTOR -> hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : NormalizedTransactionType.LP_FEE_CLAIM;
            case COLLECT_SELECTOR -> hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_FEE_CLAIM
                    : null;
            case BURN_SELECTOR -> hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : null;
            case MODIFY_LIQUIDITIES_SELECTOR -> resolveModifyLiquiditiesType(view, movementLegs);
            case SAFE_TRANSFER_FROM_SELECTOR, SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR ->
                    resolveSafeTransferType(decodedFromEntry, decodedToEntry).orElse(null);
            default -> null;
        };
    }

    public static NormalizedTransactionType resolveDexStakeContractType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        return switch (String.valueOf(view.methodId())) {
            case STAKE_DEPOSIT_SELECTOR -> NormalizedTransactionType.LP_POSITION_STAKE;
            case STAKE_WITHDRAW_SELECTOR -> NormalizedTransactionType.LP_POSITION_UNSTAKE;
            case MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR -> hasNonFeeMovement(movementLegs)
                    ? NormalizedTransactionType.LP_ENTRY
                    : null;
            case BURN_SELECTOR -> CalldataDecodingSupport.decodeAddressArgument(view.inputData(), 1) == null
                    ? null
                    : hasNonFeeMovement(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : NormalizedTransactionType.LP_POSITION_UNSTAKE;
            default -> null;
        };
    }

    public static NormalizedTransactionType resolveDexStakeContractMulticallType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!MULTICALL_SELECTOR.equals(String.valueOf(view.methodId()))) {
            return null;
        }

        String inputData = view.inputData();
        boolean decreaseLiquidity = CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR);
        boolean collect = CalldataDecodingSupport.containsEmbeddedSelector(inputData, COLLECT_SELECTOR);
        boolean burn = CalldataDecodingSupport.containsEmbeddedSelector(inputData, BURN_SELECTOR);

        if ((decreaseLiquidity || burn) && hasInboundNonFeeLeg(movementLegs)) {
            return NormalizedTransactionType.LP_EXIT;
        }
        // collect-only multicall (no decreaseLiquidity/burn in calldata): route through
        // hasPositionReductionEvidence regardless of outbound dust legs (tiny WETH-to-ETH sweep
        // or other contract-mandated outflows must not suppress LP_FEE_CLAIM classification).
        if (collect && hasInboundNonFeeLeg(movementLegs)) {
            return LpPrincipalCloseEvidence.hasPositionReductionEvidence(view)
                    ? NormalizedTransactionType.LP_EXIT
                    : NormalizedTransactionType.LP_FEE_CLAIM;
        }
        if (hasAnyErc721TransferToWallet(view) && hasInboundNonFeeLeg(movementLegs)) {
            return NormalizedTransactionType.LP_EXIT;
        }
        return null;
    }

    public static NormalizedTransactionType resolvePositionManagerMulticallType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!MULTICALL_SELECTOR.equals(String.valueOf(view.methodId()))) {
            return null;
        }

        String inputData = view.inputData();
        boolean mint = CalldataDecodingSupport.containsEmbeddedSelector(inputData, MINT_SELECTOR);
        boolean structMint = CalldataDecodingSupport.containsEmbeddedSelector(inputData, STRUCT_MINT_SELECTOR);
        boolean increaseLiquidity = CalldataDecodingSupport.containsEmbeddedSelector(inputData, INCREASE_LIQUIDITY_SELECTOR);
        boolean stakedIncreaseLiquidity = CalldataDecodingSupport.containsEmbeddedSelector(inputData, MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR);
        boolean decreaseLiquidity = CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR);
        boolean collect = CalldataDecodingSupport.containsEmbeddedSelector(inputData, COLLECT_SELECTOR);
        boolean modifyLiquidities = CalldataDecodingSupport.containsEmbeddedSelector(inputData, MODIFY_LIQUIDITIES_SELECTOR);
        if ((mint || structMint || increaseLiquidity || stakedIncreaseLiquidity) && hasOutboundNonFeeLeg(movementLegs)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if ((mint || structMint || modifyLiquidities) && hasPositionNftMintLog(view)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (decreaseLiquidity && hasInboundNonFeeLeg(movementLegs)) {
            return NormalizedTransactionType.LP_EXIT;
        }
        if (collect && hasInboundNonFeeLeg(movementLegs) && !decreaseLiquidity && !hasOutboundNonFeeLeg(movementLegs)) {
            return NormalizedTransactionType.LP_FEE_CLAIM;
        }
        if (modifyLiquidities) {
            return resolveModifyLiquiditiesType(view, movementLegs);
        }
        return null;
    }

    public static String decodeSafeTransferFromAddress(OnChainRawTransactionView view) {
        return CalldataDecodingSupport.decodeAddressArgument(view.inputData(), 0);
    }

    public static String decodeSafeTransferToAddress(OnChainRawTransactionView view) {
        return CalldataDecodingSupport.decodeAddressArgument(view.inputData(), 1);
    }

    public static boolean isDexStakeContract(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.DEX
                && entry.role() == ProtocolRegistryRole.STAKE_CONTRACT;
    }

    private static Optional<NormalizedTransactionType> resolveSafeTransferType(
            Optional<ProtocolRegistryEntry> decodedFromEntry,
            Optional<ProtocolRegistryEntry> decodedToEntry
    ) {
        if (decodedToEntry.filter(LpPositionLifecycleSupport::isDexStakeContract).isPresent()) {
            return Optional.of(NormalizedTransactionType.LP_POSITION_STAKE);
        }
        if (decodedFromEntry.filter(LpPositionLifecycleSupport::isDexStakeContract).isPresent()) {
            return Optional.of(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        }
        return Optional.empty();
    }

    private static boolean hasInboundNonFeeLeg(List<RawLeg> movementLegs) {
        return movementLegs.stream().filter(leg -> !leg.fee()).anyMatch(leg -> leg.quantityDelta().signum() > 0);
    }

    private static boolean hasNonFeeMovement(List<RawLeg> movementLegs) {
        return movementLegs.stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() != 0);
    }

    private static boolean hasOutboundNonFeeLeg(List<RawLeg> movementLegs) {
        return movementLegs.stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
    }

    private static NormalizedTransactionType resolveModifyLiquiditiesType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (hasOutboundNonFeeTokenLeg(movementLegs)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (hasInboundNonFeeTokenLeg(movementLegs)) {
            return NormalizedTransactionType.LP_EXIT;
        }
        return resolveModifyLiquiditiesFromLogs(view);
    }

    private static NormalizedTransactionType resolveModifyLiquiditiesFromLogs(OnChainRawTransactionView view) {
        if (view == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!MODIFY_LIQUIDITY_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            BigInteger amount0 = decodeSignedWord(logData(log), 0);
            BigInteger amount1 = decodeSignedWord(logData(log), 1);
            if (isPositive(amount0) || isPositive(amount1)) {
                return NormalizedTransactionType.LP_ENTRY;
            }
            if (isNegative(amount0) || isNegative(amount1)) {
                return NormalizedTransactionType.LP_EXIT;
            }
        }
        if (hasPositionNftMintLog(view)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        return null;
    }

    private static boolean hasOutboundNonFeeTokenLeg(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.assetContract() != null && leg.quantityDelta().signum() < 0);
    }

    private static boolean hasInboundNonFeeTokenLeg(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.assetContract() != null && leg.quantityDelta().signum() > 0);
    }

    public static boolean hasPositionNftMintLog(OnChainRawTransactionView view) {
        String positionManager = OnChainRawTransactionView.normalizeAddress(view.toAddress());
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (positionManager == null || wallet == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!positionManager.equals(OnChainRawTransactionView.normalizeAddress(stringValue(log.get("address"))))) {
                continue;
            }
            if (!ERC721_TRANSFER_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            List<String> topics = normalizedTopics(log);
            if (topics.size() < 3) {
                continue;
            }
            if (!zeroAddressTopic(topics.get(1))) {
                continue;
            }
            if (wallet.equals(topicAddress(topics.get(2)))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAnyErc721TransferToWallet(OnChainRawTransactionView view) {
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
            // Requiring 4 topics prevents ERC-20 transfers (e.g. USDC sent to wallet) from being
            // misidentified as NFT transfers, which would incorrectly trigger LP_EXIT classification.
            if (topics.size() < 4) {
                continue;
            }
            if (wallet.equals(topicAddress(topics.get(2)))) {
                return true;
            }
        }
        return false;
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
                .map(LpPositionLifecycleSupport::stringValue)
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

    private static boolean zeroAddressTopic(String topic) {
        String address = topicAddress(topic);
        return "0x0000000000000000000000000000000000000000".equals(address);
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

    private static boolean isPositive(BigInteger value) {
        return value != null && value.signum() > 0;
    }

    private static boolean isNegative(BigInteger value) {
        return value != null && value.signum() < 0;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
