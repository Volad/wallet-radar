package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * LP-position NFT lifecycle helpers for position managers and DEX stake contracts.
 */
public final class LpPositionLifecycleSupport {

    // Cross-protocol position-manager ABI vocabulary — single source of truth in
    // LpPositionManagerAbi (classpath:lp-position-manager-abi.json), shared with
    // LpPositionCorrelationSupport so the coupled classify/correlate selector sets cannot drift.
    private static final String MINT_SELECTOR = LpPositionManagerAbi.MINT_SELECTOR;
    private static final String STRUCT_MINT_SELECTOR = LpPositionManagerAbi.STRUCT_MINT_SELECTOR;
    private static final String INCREASE_LIQUIDITY_SELECTOR = LpPositionManagerAbi.INCREASE_LIQUIDITY_SELECTOR;
    private static final String MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR = LpPositionManagerAbi.MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR;
    private static final String DECREASE_LIQUIDITY_SELECTOR = LpPositionManagerAbi.DECREASE_LIQUIDITY_SELECTOR;
    private static final String COLLECT_SELECTOR = LpPositionManagerAbi.COLLECT_SELECTOR;
    private static final String BURN_SELECTOR = LpPositionManagerAbi.BURN_SELECTOR;
    private static final String MULTICALL_SELECTOR = LpPositionManagerAbi.MULTICALL_SELECTOR;
    private static final String MODIFY_LIQUIDITIES_SELECTOR = LpPositionManagerAbi.MODIFY_LIQUIDITIES_SELECTOR;
    private static final String STAKE_DEPOSIT_SELECTOR = LpPositionManagerAbi.STAKE_DEPOSIT_SELECTOR;
    private static final String STAKE_WITHDRAW_SELECTOR = LpPositionManagerAbi.STAKE_WITHDRAW_SELECTOR;
    /** Aura Finance BaseRewardPool {@code withdrawAndUnwrap(uint256 amount, bool claim)} */
    private static final String AURA_WITHDRAW_AND_UNWRAP_SELECTOR = LpPositionManagerAbi.AURA_WITHDRAW_AND_UNWRAP_SELECTOR;
    private static final String SAFE_TRANSFER_FROM_SELECTOR = LpPositionManagerAbi.SAFE_TRANSFER_FROM_SELECTOR;
    private static final String SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR = LpPositionManagerAbi.SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR;
    private static final String ERC721_TRANSFER_TOPIC = LpPositionManagerAbi.ERC721_TRANSFER_TOPIC;
    private static final String MODIFY_LIQUIDITY_TOPIC = LpPositionManagerAbi.MODIFY_LIQUIDITY_TOPIC;

    private LpPositionLifecycleSupport() {
    }

    public static NormalizedTransactionType resolvePositionManagerType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            Optional<ProtocolRegistryEntry> decodedFromEntry,
            Optional<ProtocolRegistryEntry> decodedToEntry
    ) {
        String methodId = String.valueOf(view.methodId());
        if (MINT_SELECTOR.equals(methodId)
                || STRUCT_MINT_SELECTOR.equals(methodId)
                || INCREASE_LIQUIDITY_SELECTOR.equals(methodId)
                || MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR.equals(methodId)) {
            return hasOutboundNonFeeLeg(movementLegs) || hasPositionNftMintLog(view)
                    ? NormalizedTransactionType.LP_ENTRY
                    : null;
        }
        if (DECREASE_LIQUIDITY_SELECTOR.equals(methodId)) {
            return hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : NormalizedTransactionType.LP_FEE_CLAIM;
        }
        if (COLLECT_SELECTOR.equals(methodId)) {
            return hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_FEE_CLAIM
                    : null;
        }
        if (BURN_SELECTOR.equals(methodId)) {
            return hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : null;
        }
        if (MODIFY_LIQUIDITIES_SELECTOR.equals(methodId)) {
            return resolveModifyLiquiditiesType(view, movementLegs);
        }
        if (SAFE_TRANSFER_FROM_SELECTOR.equals(methodId)
                || SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR.equals(methodId)) {
            return resolveSafeTransferType(decodedFromEntry, decodedToEntry).orElse(null);
        }
        return null;
    }

    public static NormalizedTransactionType resolveDexStakeContractType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        String methodId = String.valueOf(view.methodId());
        NormalizedTransactionType selectorType = null;
        if (STAKE_DEPOSIT_SELECTOR.equals(methodId)) {
            selectorType = NormalizedTransactionType.LP_POSITION_STAKE;
        } else if (STAKE_WITHDRAW_SELECTOR.equals(methodId)) {
            selectorType = NormalizedTransactionType.LP_POSITION_UNSTAKE;
        } else if (AURA_WITHDRAW_AND_UNWRAP_SELECTOR.equals(methodId)) {
            // Aura Finance BaseRewardPool withdrawAndUnwrap(uint256, bool): BPT returned + optional reward claim
            selectorType = NormalizedTransactionType.LP_POSITION_UNSTAKE;
        } else if (MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR.equals(methodId)) {
            selectorType = hasNonFeeMovement(movementLegs)
                    ? NormalizedTransactionType.LP_ENTRY
                    : null;
        } else if (BURN_SELECTOR.equals(methodId)) {
            selectorType = CalldataDecodingSupport.decodeAddressArgument(view.inputData(), 1) == null
                    ? null
                    : hasNonFeeMovement(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : NormalizedTransactionType.LP_POSITION_UNSTAKE;
        }
        if (selectorType != null) {
            return selectorType;
        }
        // Fallback: use net movement direction for DEX stake contracts with unknown selectors.
        // Excluded: multicall (handled by resolveDexStakeContractMulticallType) and SAFE_TRANSFER_FROM.
        if (MULTICALL_SELECTOR.equals(methodId)
                || SAFE_TRANSFER_FROM_SELECTOR.equals(methodId)
                || SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR.equals(methodId)) {
            return null;
        }
        return resolveDexStakeContractTypeByMovementLegs(movementLegs);
    }

    /**
     * Movement-leg direction fallback for DEX stake contracts with non-standard selectors.
     * Net outbound = STAKE (tokens going in); net inbound = UNSTAKE (tokens coming back).
     */
    private static NormalizedTransactionType resolveDexStakeContractTypeByMovementLegs(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return null;
        }
        int outbound = 0;
        int inbound = 0;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee()) {
                continue;
            }
            if (leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0) {
                outbound++;
            } else {
                inbound++;
            }
        }
        if (outbound > 0 && inbound == 0) {
            return NormalizedTransactionType.LP_POSITION_STAKE;
        }
        if (inbound > 0 && outbound == 0) {
            return NormalizedTransactionType.LP_POSITION_UNSTAKE;
        }
        // Mixed (e.g. claim rewards on unstake): treat as UNSTAKE since the principal comes back
        if (inbound > 0) {
            return NormalizedTransactionType.LP_POSITION_UNSTAKE;
        }
        return null;
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
        // ModifyLiquidity event logs are the authoritative source: liquidityDelta=0 means a pure
        // fee collection even when inbound token legs are present. Check logs first.
        NormalizedTransactionType fromLogs = resolveModifyLiquiditiesFromLogs(view);
        if (fromLogs != null) {
            return fromLogs;
        }
        // Fallback when logs are unavailable (e.g. Etherscan-sourced txs with no stored receipt).
        if (hasOutboundNonFeeTokenLeg(movementLegs)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (hasInboundNonFeeTokenLeg(movementLegs)) {
            return NormalizedTransactionType.LP_EXIT;
        }
        return null;
    }

    private static NormalizedTransactionType resolveModifyLiquiditiesFromLogs(OnChainRawTransactionView view) {
        if (view == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!MODIFY_LIQUIDITY_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            // ModifyLiquidity(PoolId indexed, address indexed, int24 tickLower, int24 tickUpper,
            //                 int256 liquidityDelta, bytes32 salt)
            // Non-indexed data layout: word0=tickLower, word1=tickUpper, word2=liquidityDelta, word3=salt
            BigInteger liquidityDelta = decodeSignedWord(logData(log), 2);
            if (isPositive(liquidityDelta)) {
                return NormalizedTransactionType.LP_ENTRY;
            }
            if (isNegative(liquidityDelta)) {
                return NormalizedTransactionType.LP_EXIT;
            }
            if (liquidityDelta != null) {
                // liquidityDelta == 0: no position liquidity changed, only fees collected
                return NormalizedTransactionType.LP_FEE_CLAIM;
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
        if (wallet == null) {
            return false;
        }
        // Primary check: ERC-721 mint emitted by the contract called directly (positionManager).
        // This is the standard pattern for direct NonfungiblePositionManager calls (Uniswap V3,
        // PancakeSwap V3). Only 3 topics are required here because the contract filter is already
        // a strong guard against ERC-20 Transfer collisions.
        if (positionManager != null) {
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
        }
        // Fallback: ERC-721 mint from ANY contract in the receipt.
        // Covers router → position-manager call chains (e.g. Katana/SushiSwap on Katana chain)
        // where the NonfungiblePositionManager is an indirect callee that emits the ERC-721 Transfer
        // but is NOT view.toAddress(). ERC-721 Transfers have 4 indexed topics (event, from, to, tokenId);
        // requiring 4 topics distinguishes them from ERC-20 Transfer logs (3 topics).
        for (Document log : view.persistedLogs()) {
            if (!ERC721_TRANSFER_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            List<String> topics = normalizedTopics(log);
            if (topics.size() < 4) {
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

    /**
     * Extracts the ERC-721 tokenId from the first Transfer log whose recipient is the wallet.
     *
     * <p>ERC-721 Transfer events have 4 indexed topics: [event_sig, from, to, tokenId].
     * This method requires exactly 4 topics to avoid confusing ERC-20 Transfer logs (3 topics)
     * with NFT Transfer logs.
     *
     * @return decimal string tokenId, or {@code null} if no matching log is found
     */
    public static String extractErc721TokenIdForWallet(OnChainRawTransactionView view) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!ERC721_TRANSFER_TOPIC.equals(firstTopic(log))) {
                continue;
            }
            List<String> topics = normalizedTopics(log);
            if (topics.size() < 4) {
                continue;
            }
            if (!wallet.equals(topicAddress(topics.get(2)))) {
                continue;
            }
            String tokenIdHex = topics.get(3);
            String normalized = tokenIdHex.startsWith("0x") ? tokenIdHex.substring(2) : tokenIdHex;
            if (normalized.isBlank()) {
                continue;
            }
            try {
                return new BigInteger(normalized, 16).toString(10);
            } catch (NumberFormatException ignored) {
                // malformed topic; try next log
            }
        }
        return null;
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
