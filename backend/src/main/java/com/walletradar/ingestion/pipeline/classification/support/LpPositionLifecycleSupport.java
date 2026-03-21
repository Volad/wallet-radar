package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;
import java.util.Optional;

/**
 * LP-position NFT lifecycle helpers for position managers and DEX stake contracts.
 */
public final class LpPositionLifecycleSupport {

    private static final String MINT_SELECTOR = "0x88316456";
    private static final String INCREASE_LIQUIDITY_SELECTOR = "0x4f1eb3d8";
    private static final String MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR = "0x219f5d17";
    private static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
    private static final String COLLECT_SELECTOR = "0xfc6f7865";
    private static final String BURN_SELECTOR = "0x00f714ce";
    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String MODIFY_LIQUIDITIES_SELECTOR = "0xdd46508f";
    private static final String SAFE_TRANSFER_FROM_SELECTOR = "0x42842e0e";
    private static final String SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR = "0xb88d4fde";

    private LpPositionLifecycleSupport() {
    }

    public static NormalizedTransactionType resolvePositionManagerType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            Optional<ProtocolRegistryEntry> decodedFromEntry,
            Optional<ProtocolRegistryEntry> decodedToEntry
    ) {
        return switch (String.valueOf(view.methodId())) {
            case MINT_SELECTOR, INCREASE_LIQUIDITY_SELECTOR -> NormalizedTransactionType.LP_ENTRY;
            case DECREASE_LIQUIDITY_SELECTOR -> hasInboundNonFeeLeg(movementLegs)
                    ? NormalizedTransactionType.LP_EXIT
                    : NormalizedTransactionType.LP_FEE_CLAIM;
            case COLLECT_SELECTOR -> NormalizedTransactionType.LP_FEE_CLAIM;
            case BURN_SELECTOR -> NormalizedTransactionType.LP_EXIT;
            case MODIFY_LIQUIDITIES_SELECTOR -> resolveModifyLiquiditiesType(movementLegs);
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

    public static NormalizedTransactionType resolvePositionManagerMulticallType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!MULTICALL_SELECTOR.equals(String.valueOf(view.methodId()))) {
            return null;
        }

        String inputData = view.inputData();
        boolean mint = CalldataDecodingSupport.containsEmbeddedSelector(inputData, MINT_SELECTOR);
        boolean increaseLiquidity = CalldataDecodingSupport.containsEmbeddedSelector(inputData, INCREASE_LIQUIDITY_SELECTOR);
        if ((mint || increaseLiquidity) && hasOutboundNonFeeLeg(movementLegs)) {
            return NormalizedTransactionType.LP_ENTRY;
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

    private static NormalizedTransactionType resolveModifyLiquiditiesType(List<RawLeg> movementLegs) {
        if (hasOutboundNonFeeTokenLeg(movementLegs)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (hasInboundNonFeeTokenLeg(movementLegs)) {
            return NormalizedTransactionType.LP_EXIT;
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
}
