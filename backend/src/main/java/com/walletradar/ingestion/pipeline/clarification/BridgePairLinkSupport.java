package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.accounting.support.BridgeAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction.Flow;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        return supportsSupplementalMoveBasis(sourcePrincipal.orElseThrow(), destinationPrincipal.orElseThrow());
    }

    /**
     * Whether a supplemental LI.FI source leg should carry basis into one matched inbound flow
     * on an already-paired multi-flow destination (for example WETH OUT → ETH IN).
     */
    public static boolean supportsSupplementalMoveBasis(
            Flow matchedOutbound,
            Flow matchedInbound
    ) {
        if (matchedOutbound == null || matchedInbound == null) {
            return false;
        }
        if (!supportsBridgeContinuity(matchedOutbound, matchedInbound)) {
            return false;
        }
        return matchedOutbound.getQuantityDelta() != null && matchedInbound.getQuantityDelta() != null;
    }

    /**
     * Inbound flow on a multi-source destination linked to a supplemental bridge OUT via
     * {@code LINKED:<sourceTxHash>} counterparty metadata.
     */
    public static Optional<Flow> findSupplementalLinkedInboundFlow(
            NormalizedTransaction supplementalOutbound,
            NormalizedTransaction destination
    ) {
        if (supplementalOutbound == null || destination == null || !hasText(supplementalOutbound.getTxHash())) {
            return Optional.empty();
        }
        if (destination.getFlows() == null) {
            return Optional.empty();
        }
        String linkedCounterparty = supplementalLinkedCounterparty(supplementalOutbound.getTxHash());
        return destination.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> linkedCounterparty.equals(flow.getCounterpartyAddress()))
                .findFirst();
    }

    /**
     * Whether a supplemental bridge OUT is already linked for move-basis carry on a shared
     * destination (for example LI.FI WETH top-up → ETH IN on an already-paired bundle).
     */
    public static boolean hasSupplementalMoveBasisLinkage(
            NormalizedTransaction supplementalOutbound,
            NormalizedTransaction destination
    ) {
        Optional<Flow> linkedInbound = findSupplementalLinkedInboundFlow(supplementalOutbound, destination);
        if (linkedInbound.isEmpty() || supplementalOutbound.getFlows() == null) {
            return false;
        }
        Flow inbound = linkedInbound.orElseThrow();
        for (Flow outboundFlow : supplementalOutbound.getFlows()) {
            if (outboundFlow == null
                    || outboundFlow.getRole() == NormalizedLegRole.FEE
                    || outboundFlow.getQuantityDelta() == null
                    || outboundFlow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            if (supportsSupplementalMoveBasis(outboundFlow, inbound)) {
                return true;
            }
        }
        return false;
    }

    public static String supplementalLinkedCounterparty(String supplementalSourceTxHash) {
        return "LINKED:" + supplementalSourceTxHash.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Retags one matched supplemental inbound flow for bridge continuity without disturbing
     * the principal destination pair metadata on the transaction header.
     */
    public static boolean retagSupplementalInboundFlow(
            NormalizedTransaction destination,
            Flow matchedInbound,
            String supplementalSourceTxHash,
            Instant now
    ) {
        if (destination == null || matchedInbound == null || !hasText(supplementalSourceTxHash)) {
            return false;
        }
        String linkedCounterparty = "LINKED:" + supplementalSourceTxHash.trim().toLowerCase(Locale.ROOT);
        boolean changed = false;
        for (Flow flow : destination.getFlows()) {
            if (flow != matchedInbound) {
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
            if (!linkedCounterparty.equals(flow.getCounterpartyAddress())) {
                flow.setCounterpartyAddress(linkedCounterparty);
                changed = true;
            }
            if (!CounterpartyType.BRIDGE.equals(flow.getCounterpartyType())) {
                flow.setCounterpartyType(CounterpartyType.BRIDGE);
                changed = true;
            }
        }
        if (changed) {
            FlowCounterpartySupport.applyTransactionCounterparty(destination);
            if (now != null) {
                destination.setUpdatedAt(now);
            }
        }
        return changed;
    }

    /**
     * Demotes principal flows on a bridge leg from BUY/SELL to TRANSFER and clears price fields
     * so AVCO replay performs basis carry instead of monetary disposal.
     */
    /**
     * Stamps paired bridge IN flows with a bridge counterparty derived from the linked OUT leg.
     */
    public static boolean applyLinkedBridgeCounterparty(
            NormalizedTransaction source,
            NormalizedTransaction destination,
            Instant now
    ) {
        if (source == null || destination == null || destination.getFlows() == null) {
            return false;
        }
        String bridgeAddress = selectPrimaryPrincipalFlow(source, -1)
                .map(Flow::getCounterpartyAddress)
                .filter(BridgePairLinkSupport::hasText)
                .orElseGet(() -> hasText(source.getTxHash())
                        ? "LINKED:" + source.getTxHash().toLowerCase(Locale.ROOT)
                        : null);
        if (!hasText(bridgeAddress)) {
            return false;
        }
        boolean changed = false;
        for (Flow flow : destination.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (!hasText(flow.getCounterpartyAddress())) {
                flow.setCounterpartyAddress(bridgeAddress);
                changed = true;
            }
            // RC-4 (ADR-027): a proven linked LiFi/bridge corridor inbound is a BRIDGE
            // counterparty, not the relayer EOA. Overwrite an UNKNOWN_EOA stamp (and an absent
            // type) so corridor matching (RC-2) is unambiguous; never downgrade a stronger type.
            if (shouldTypeAsBridge(flow.getCounterpartyType())) {
                flow.setCounterpartyType(CounterpartyType.BRIDGE);
                changed = true;
            }
        }
        if (changed) {
            FlowCounterpartySupport.applyTransactionCounterparty(destination);
            if (now != null) {
                destination.setUpdatedAt(now);
            }
        }
        return changed;
    }

    private static boolean shouldTypeAsBridge(String currentType) {
        return !hasText(currentType)
                || CounterpartyType.UNKNOWN_EOA.equals(currentType)
                || CounterpartyType.UNKNOWN_CONTRACT.equals(currentType)
                || CounterpartyType.GENUINE_MISSING_SOURCE.equals(currentType);
    }

    /**
     * RC-12 BR-1: stamps {@code counterpartyType=BRIDGE} on a leg's principal flows so a linked
     * cross-network corridor renders as a connected bridge edge on the dashboard chart. Never
     * downgrades a stronger established counterparty type (CEX/PROTOCOL/PERSONAL_WALLET).
     */
    public static boolean stampBridgePrincipalCounterpartyType(NormalizedTransaction transaction, Instant now) {
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
            if (shouldTypeAsBridge(flow.getCounterpartyType())) {
                flow.setCounterpartyType(CounterpartyType.BRIDGE);
                changed = true;
            }
        }
        if (changed) {
            FlowCounterpartySupport.applyTransactionCounterparty(transaction);
            if (now != null) {
                transaction.setUpdatedAt(now);
            }
        }
        return changed;
    }

    public static boolean retagPrincipalFlowsForBridgeContinuity(NormalizedTransaction transaction, Instant now) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean changed = false;
        boolean isBridgeOut = transaction.getType() == NormalizedTransactionType.BRIDGE_OUT;
        for (Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            // On BRIDGE_OUT, secondary inbound (positive qty) flows such as fee refunds or
            // intermediate WETH→ETH steps must not be TRANSFER — that would produce a spurious
            // CARRY_IN in replay. If normalization already stamped the flow as TRANSFER, revert
            // it to BUY so it gets market-priced. Leave all other roles (BUY, SELL) untouched.
            if (isBridgeOut && flow.getQuantityDelta().signum() > 0) {
                if (flow.getRole() == NormalizedLegRole.TRANSFER) {
                    flow.setRole(NormalizedLegRole.BUY);
                    changed = true;
                }
                continue; // do NOT clear price fields for this flow
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

    /**
     * Retags one matched supplemental outbound flow for bridge continuity without retagging
     * co-located native gas legs on the same bridge-start transaction.
     */
    public static boolean retagSupplementalOutboundFlow(
            NormalizedTransaction source,
            Flow matchedOutbound,
            Instant now
    ) {
        if (source == null || matchedOutbound == null) {
            return false;
        }
        boolean changed = false;
        for (Flow flow : source.getFlows()) {
            if (flow != matchedOutbound) {
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
            source.setUpdatedAt(now);
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
