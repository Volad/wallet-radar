package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.ConfidenceLevel;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
            List<RawClassifiedEvent> rawEvents
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId);
        tx.setWalletAddress(walletAddress);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setType(resolveType(rawEvents));
        tx.setLegs(toLegs(rawEvents));
        tx.setConfidence(ConfidenceLevel.HIGH);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());

        List<String> reasons = NormalizedTransactionValidator.missingDataReasons(tx.getType(), tx.getLegs());
        tx.setMissingDataReasons(reasons);
        tx.setStatus(reasons.isEmpty()
                ? NormalizedTransactionStatus.PENDING_PRICE
                : NormalizedTransactionStatus.PENDING_CLARIFICATION);
        tx.setClarificationAttempts(0);
        tx.setPricingAttempts(0);
        tx.setStatAttempts(0);
        return tx;
    }

    private static List<NormalizedTransaction.Leg> toLegs(List<RawClassifiedEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return List.of();
        }
        List<NormalizedTransaction.Leg> out = new ArrayList<>(rawEvents.size());
        for (RawClassifiedEvent raw : rawEvents) {
            NormalizedTransaction.Leg leg = new NormalizedTransaction.Leg();
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
            case INTERNAL_TRANSFER, EXTERNAL_TRANSFER_OUT, EXTERNAL_INBOUND -> NormalizedLegRole.TRANSFER;
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
            return NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
        }
        Set<com.walletradar.domain.EconomicEventType> types = new LinkedHashSet<>();
        for (RawClassifiedEvent e : rawEvents) {
            if (e != null && e.getEventType() != null) {
                types.add(e.getEventType());
            }
        }
        if (types.contains(com.walletradar.domain.EconomicEventType.SWAP_BUY)
                || types.contains(com.walletradar.domain.EconomicEventType.SWAP_SELL)) {
            return NormalizedTransactionType.SWAP;
        }
        if (types.size() == 1) {
            com.walletradar.domain.EconomicEventType one = types.iterator().next();
            return mapType(one);
        }
        if (types.contains(com.walletradar.domain.EconomicEventType.EXTERNAL_INBOUND)
                && types.contains(com.walletradar.domain.EconomicEventType.EXTERNAL_TRANSFER_OUT)) {
            return NormalizedTransactionType.SWAP;
        }
        return NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
    }

    private static NormalizedTransactionType mapType(com.walletradar.domain.EconomicEventType type) {
        return switch (type) {
            case SWAP_BUY, SWAP_SELL -> NormalizedTransactionType.SWAP;
            case INTERNAL_TRANSFER -> NormalizedTransactionType.INTERNAL_TRANSFER;
            case STAKE_DEPOSIT -> NormalizedTransactionType.STAKE_DEPOSIT;
            case STAKE_WITHDRAWAL -> NormalizedTransactionType.STAKE_WITHDRAWAL;
            case LP_ENTRY -> NormalizedTransactionType.LP_ENTRY;
            case LP_EXIT -> NormalizedTransactionType.LP_EXIT;
            case LEND_DEPOSIT -> NormalizedTransactionType.LEND_DEPOSIT;
            case LEND_WITHDRAWAL -> NormalizedTransactionType.LEND_WITHDRAWAL;
            case BORROW -> NormalizedTransactionType.BORROW;
            case REPAY -> NormalizedTransactionType.REPAY;
            case EXTERNAL_TRANSFER_OUT -> NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
            case EXTERNAL_INBOUND -> NormalizedTransactionType.EXTERNAL_INBOUND;
            case MANUAL_COMPENSATING -> NormalizedTransactionType.MANUAL_COMPENSATING;
        };
    }

}
