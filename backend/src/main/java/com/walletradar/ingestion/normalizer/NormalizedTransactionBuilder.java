package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds canonical NormalizedTransaction from classifier events (ADR-025).
 */
@Component
public class NormalizedTransactionBuilder {

    public NormalizedTransaction build(
            String txHash,
            NetworkId networkId,
            String walletAddress,
            Instant blockTimestamp,
            List<RawClassifiedEvent> rawEvents,
            BigDecimal confidence
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId);
        tx.setWalletAddress(walletAddress);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setType(resolveType(rawEvents));
        tx.setGroupId(resolveGroupId(networkId, walletAddress, tx.getType(), rawEvents));
        List<NormalizedTransaction.Flow> flows = toFlows(rawEvents);
        tx.setFlows(flows);
        tx.setConfidence(confidence != null ? confidence : BigDecimal.ZERO);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());

        List<String> reasons = NormalizedTransactionValidator.missingDataReasons(tx.getType(), tx.getFlows());
        tx.setMissingDataReasons(reasons);
        tx.setStatus(resolveInitialStatus(tx.getType(), reasons));
        tx.setClarificationAttempts(0);
        tx.setPricingAttempts(0);
        tx.setStatAttempts(0);
        return tx;
    }

    private static List<NormalizedTransaction.Flow> toFlows(List<RawClassifiedEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return List.of();
        }
        List<NormalizedTransaction.Flow> out = new ArrayList<>(rawEvents.size());
        for (RawClassifiedEvent raw : rawEvents) {
            if (raw == null || raw.getEventType() == null) {
                continue;
            }
            if (raw.getEventType() == com.walletradar.domain.transaction.normalized.EconomicEventType.APPROVAL) {
                // Approval is a non-economic permission event: do not emit accounting flows.
                continue;
            }
            NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
            leg.setRole(resolveRole(raw));
            leg.setAssetContract(raw.getAssetContract() != null ? raw.getAssetContract() : "");
            leg.setAssetSymbol(raw.getAssetSymbol() != null ? raw.getAssetSymbol() : "");
            leg.setQuantityDelta(raw.getQuantityDelta() != null ? raw.getQuantityDelta() : BigDecimal.ZERO);
            leg.setInferred(false);
            leg.setLogIndex(raw.getLogIndex());
            out.add(leg);
        }
        return out;
    }

    private static NormalizedLegRole resolveRole(RawClassifiedEvent raw) {
        if (raw == null || raw.getEventType() == null) {
            return NormalizedLegRole.TRANSFER;
        }
        return switch (raw.getEventType()) {
            case EXTERNAL_TRANSFER_OUT, EXTERNAL_INBOUND, LP_ADJUST, LP_POSITION_STAKE, LP_POSITION_UNSTAKE, LP_POSITION_ENTRY, LP_POSITION_EXIT, APPROVAL -> NormalizedLegRole.TRANSFER;
            default -> {
                BigDecimal qty = raw.getQuantityDelta() != null ? raw.getQuantityDelta() : BigDecimal.ZERO;
                if (qty.signum() > 0) {
                    yield NormalizedLegRole.BUY;
                }
                if (qty.signum() < 0) {
                    yield NormalizedLegRole.SELL;
                }
                yield NormalizedLegRole.TRANSFER;
            }
        };
    }

    private static NormalizedTransactionType resolveType(List<RawClassifiedEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return NormalizedTransactionType.UNCLASSIFIED;
        }
        Set<com.walletradar.domain.transaction.normalized.EconomicEventType> types = new LinkedHashSet<>();
        for (RawClassifiedEvent e : rawEvents) {
            if (e != null && e.getEventType() != null) {
                types.add(e.getEventType());
            }
        }
        NormalizedTransactionType lpType = resolveLpPriorityType(types);
        if (lpType != null) {
            return lpType;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_BUY)
                || types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_SELL)) {
            return NormalizedTransactionType.SWAP;
        }
        if (types.size() == 1) {
            com.walletradar.domain.transaction.normalized.EconomicEventType one = types.iterator().next();
            return mapType(one);
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND)
                && types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_TRANSFER_OUT)) {
            if (hasDistinctExternalBidirectionalAssets(rawEvents)) {
                return NormalizedTransactionType.SWAP;
            }
            return NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
        }
        return NormalizedTransactionType.UNCLASSIFIED;
    }

    private static NormalizedTransactionType resolveLpPriorityType(
            Set<com.walletradar.domain.transaction.normalized.EconomicEventType> types
    ) {
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_EXIT_FINAL)) {
            return NormalizedTransactionType.LP_EXIT_FINAL;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_EXIT_PARTIAL)) {
            return NormalizedTransactionType.LP_EXIT_PARTIAL;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_EXIT)
                || types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_POSITION_EXIT)) {
            return NormalizedTransactionType.LP_EXIT;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_ENTRY)
                || types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_POSITION_ENTRY)) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_FEE_CLAIM)) {
            return NormalizedTransactionType.LP_FEE_CLAIM;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_POSITION_STAKE)) {
            return NormalizedTransactionType.LP_POSITION_STAKE;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_POSITION_UNSTAKE)) {
            return NormalizedTransactionType.LP_POSITION_UNSTAKE;
        }
        if (types.contains(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_ADJUST)) {
            return NormalizedTransactionType.LP_ADJUST;
        }
        return null;
    }

    private static boolean hasDistinctExternalBidirectionalAssets(List<RawClassifiedEvent> rawEvents) {
        Map<String, Integer> directionByAsset = new LinkedHashMap<>();
        for (RawClassifiedEvent event : rawEvents) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            if (event.getEventType() != com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND
                    && event.getEventType() != com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_TRANSFER_OUT) {
                continue;
            }
            String asset = event.getAssetContract();
            if (asset == null || asset.isBlank()) {
                continue;
            }
            BigDecimal qty = event.getQuantityDelta() != null ? event.getQuantityDelta() : BigDecimal.ZERO;
            int direction = Integer.signum(qty.signum());
            if (direction == 0) {
                continue;
            }
            directionByAsset.merge(asset.toLowerCase(), direction, (left, right) -> {
                if (left == right) {
                    return left;
                }
                return 0;
            });
        }
        boolean hasInboundOnlyAsset = false;
        boolean hasOutboundOnlyAsset = false;
        for (Integer direction : directionByAsset.values()) {
            if (direction == null) {
                continue;
            }
            if (direction > 0) {
                hasInboundOnlyAsset = true;
            } else if (direction < 0) {
                hasOutboundOnlyAsset = true;
            }
        }
        return hasInboundOnlyAsset && hasOutboundOnlyAsset;
    }

    private static NormalizedTransactionType mapType(com.walletradar.domain.transaction.normalized.EconomicEventType type) {
        return switch (type) {
            case SWAP_BUY, SWAP_SELL -> NormalizedTransactionType.SWAP;
            case STAKE_DEPOSIT -> NormalizedTransactionType.STAKE_DEPOSIT;
            case STAKE_WITHDRAWAL -> NormalizedTransactionType.STAKE_WITHDRAWAL;
            case LP_ENTRY -> NormalizedTransactionType.LP_ENTRY;
            case LP_EXIT -> NormalizedTransactionType.LP_EXIT;
            case LP_EXIT_PARTIAL -> NormalizedTransactionType.LP_EXIT_PARTIAL;
            case LP_EXIT_FINAL -> NormalizedTransactionType.LP_EXIT_FINAL;
            case LP_ADJUST -> NormalizedTransactionType.LP_ADJUST;
            case LP_POSITION_STAKE -> NormalizedTransactionType.LP_POSITION_STAKE;
            case LP_POSITION_UNSTAKE -> NormalizedTransactionType.LP_POSITION_UNSTAKE;
            case LP_POSITION_ENTRY -> NormalizedTransactionType.LP_ENTRY;
            case LP_POSITION_EXIT -> NormalizedTransactionType.LP_EXIT;
            case LP_FEE_CLAIM -> NormalizedTransactionType.LP_FEE_CLAIM;
            case LEND_DEPOSIT -> NormalizedTransactionType.LEND_DEPOSIT;
            case LEND_WITHDRAWAL -> NormalizedTransactionType.LEND_WITHDRAWAL;
            case BORROW -> NormalizedTransactionType.BORROW;
            case REPAY -> NormalizedTransactionType.REPAY;
            case EXTERNAL_TRANSFER_OUT -> NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
            case EXTERNAL_INBOUND -> NormalizedTransactionType.EXTERNAL_INBOUND;
            case APPROVAL -> NormalizedTransactionType.APPROVAL;
            case MANUAL_COMPENSATING -> NormalizedTransactionType.MANUAL_COMPENSATING;
        };
    }

    private static NormalizedTransactionStatus resolveInitialStatus(
            NormalizedTransactionType type,
            List<String> reasons
    ) {
        if (reasons != null && !reasons.isEmpty()) {
            return NormalizedTransactionStatus.PENDING_CLARIFICATION;
        }
        if (type == NormalizedTransactionType.APPROVAL) {
            return NormalizedTransactionStatus.PENDING_STAT;
        }
        return NormalizedTransactionStatus.PENDING_PRICE;
    }

    private static String resolveGroupId(
            NetworkId networkId,
            String walletAddress,
            NormalizedTransactionType type,
            List<RawClassifiedEvent> rawEvents
    ) {
        if (networkId == null || walletAddress == null || walletAddress.isBlank() || type == null || rawEvents == null || rawEvents.isEmpty()) {
            return null;
        }
        if (!isLpType(type)) {
            return null;
        }

        Set<String> positionIds = new LinkedHashSet<>();
        for (RawClassifiedEvent event : rawEvents) {
            if (event == null || event.getPositionId() == null || event.getPositionId().isBlank()) {
                continue;
            }
            positionIds.add(event.getPositionId().trim());
        }
        if (positionIds.size() != 1) {
            return null;
        }
        String positionId = positionIds.iterator().next();
        String normalizedWallet = walletAddress.trim().toLowerCase(Locale.ROOT);
        return "LP_POSITION:" + networkId.name() + ":" + normalizedWallet + ":" + positionId;
    }

    private static boolean isLpType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL
                || type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE
                || type == NormalizedTransactionType.LP_FEE_CLAIM;
    }

}
