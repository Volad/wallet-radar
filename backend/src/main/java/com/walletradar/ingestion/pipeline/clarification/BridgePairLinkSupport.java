package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.accounting.support.BridgeAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction.Flow;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cycle/13: shared bridge-pair helpers — continuity eligibility, primary flow selection,
 * and symmetric TRANSFER retagging for move-basis replay.
 */
public final class BridgePairLinkSupport {

    private BridgePairLinkSupport() {
    }

    /**
     * Whether a linked bridge OUT/IN pair should carry basis (not monetary SELL/BUY).
     */
    public static boolean supportsPlainMoveBasis(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        Optional<Flow> sourcePrincipal = selectPrimaryPrincipalFlow(source, -1);
        Optional<Flow> destinationPrincipal = selectPrimaryPrincipalFlow(destination, 1);
        if (sourcePrincipal.isEmpty() || destinationPrincipal.isEmpty()) {
            return false;
        }
        Flow sourceFlow = sourcePrincipal.orElseThrow();
        Flow destinationFlow = destinationPrincipal.orElseThrow();
        if (!supportsBridgeContinuity(sourceFlow, destinationFlow)) {
            return false;
        }
        return sourceFlow.getQuantityDelta() != null && destinationFlow.getQuantityDelta() != null;
    }

    /**
     * Demotes principal flows on a bridge leg from BUY/SELL to TRANSFER and clears price fields
     * so AVCO replay performs basis carry instead of monetary disposal.
     */
    public static boolean retagPrincipalFlowsForBridgeContinuity(NormalizedTransaction transaction, Instant now) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                flow.setRole(NormalizedLegRole.TRANSFER);
                changed = true;
            }
            if (flow.getUnitPriceUsd() != null) {
                flow.setUnitPriceUsd(null);
                changed = true;
            }
            if (flow.getValueUsd() != null) {
                flow.setValueUsd(null);
                changed = true;
            }
            if (flow.getPriceSource() != null) {
                flow.setPriceSource(null);
                changed = true;
            }
            if (flow.getAvcoAtTimeOfSale() != null) {
                flow.setAvcoAtTimeOfSale(null);
                changed = true;
            }
            if (flow.getRealisedPnlUsd() != null) {
                flow.setRealisedPnlUsd(null);
                changed = true;
            }
        }
        if (changed && now != null) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    public static Optional<Flow> selectPrimaryPrincipalFlow(NormalizedTransaction transaction, int direction) {
        List<Flow> candidates = principalFlows(transaction, direction);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }
        return candidates.stream()
                .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
    }

    private static List<Flow> principalFlows(NormalizedTransaction transaction, int direction) {
        if (transaction == null || transaction.getFlows() == null) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null
                        && Integer.signum(flow.getQuantityDelta().signum()) == direction)
                .toList();
    }

    public static boolean supportsBridgeContinuity(Flow sourceFlow, Flow destinationFlow) {
        String sourceAsset = BridgeAssetFamilySupport.continuityIdentity(sourceFlow);
        String destinationAsset = BridgeAssetFamilySupport.continuityIdentity(destinationFlow);
        if (hasText(sourceAsset) && sourceAsset.equals(destinationAsset)) {
            return true;
        }
        return supportsCanonicalBridgeAlias(sourceAsset, destinationAsset, sourceFlow, destinationFlow);
    }

    private static boolean supportsCanonicalBridgeAlias(
            String sourceIdentity,
            String destinationIdentity,
            Flow sourceFlow,
            Flow destinationFlow
    ) {
        if (isFamilyIdentity(sourceIdentity) || isFamilyIdentity(destinationIdentity)) {
            return false;
        }
        return CanonicalAssetCatalog.sameCanonicalSymbol(
                sourceFlow == null ? null : sourceFlow.getAssetSymbol(),
                destinationFlow == null ? null : destinationFlow.getAssetSymbol()
        );
    }

    private static boolean isFamilyIdentity(String identity) {
        return hasText(identity) && identity.startsWith("FAMILY:");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
