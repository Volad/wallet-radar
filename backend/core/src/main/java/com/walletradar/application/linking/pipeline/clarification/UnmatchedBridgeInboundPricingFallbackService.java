package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.application.PriceableFlowPolicy;
import com.walletradar.pricing.application.PricingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cycle/8 S3 + Cycle/11 S1: Fallback pricing for bridge legs whose continuity carry cannot
 * establish basis from an upstream peer.
 *
 * <p>{@link #reconcileOrphanInbounds()} handles {@code BRIDGE_IN} docs with no matching
 * {@code BRIDGE_OUT} in the session.</p>
 *
 * <p>{@link #reconcileUnsupportedOutbounds()} handles paired {@code BRIDGE_OUT} docs whose
 * principal leg is continuity-flagged but has no priced positive inflow on the same
 * wallet/network within the configured lookback window (e.g. preceded only by an unpriced
 * {@code VAULT_WITHDRAW}). Market pricing the OUT principal lets carry propagate basis to
 * the paired {@code BRIDGE_IN} and downstream positions.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UnmatchedBridgeInboundPricingFallbackService {

    private static final String BRIDGE_CORR_PREFIX = "bridge:";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final PricingProperties pricingProperties;

    public int reconcileOrphanInbounds() {
        Query inboundQuery = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                Criteria.where("continuityCandidate").is(true),
                Criteria.where("correlationId").regex("^" + BRIDGE_CORR_PREFIX)
        ));
        List<NormalizedTransaction> inbounds = mongoOperations.find(inboundQuery, NormalizedTransaction.class);
        if (inbounds.isEmpty()) {
            return 0;
        }
        Set<String> inboundCorrs = new HashSet<>();
        for (NormalizedTransaction tx : inbounds) {
            if (tx.getCorrelationId() != null) {
                inboundCorrs.add(tx.getCorrelationId());
            }
        }
        if (inboundCorrs.isEmpty()) {
            return 0;
        }
        Query outboundQuery = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("correlationId").in(inboundCorrs)
        ));
        outboundQuery.fields().include("correlationId");
        List<NormalizedTransaction> outbounds = mongoOperations.find(outboundQuery, NormalizedTransaction.class);
        Set<String> outboundCorrs = new HashSet<>();
        for (NormalizedTransaction tx : outbounds) {
            if (tx.getCorrelationId() != null) {
                outboundCorrs.add(tx.getCorrelationId());
            }
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction inbound : inbounds) {
            if (inbound.getCorrelationId() == null) {
                continue;
            }
            if (outboundCorrs.contains(inbound.getCorrelationId())) {
                continue;
            }
            if (clearContinuityAndRepriceInbound(inbound, now)) {
                // BR-2: a non-peg orphan inbound is market-priced but its carried basis is
                // unverified — flag it so it is not silently accepted as irreducible.
                flagNonPegOrphanIfNeeded(inbound);
                dirty.add(inbound);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "UNMATCHED_BRIDGE_INBOUND_PRICING_FALLBACK candidates={} unmatched={} reset_to_pending_price={}",
                    inbounds.size(),
                    inbounds.size() - outboundCorrs.size(),
                    dirty.size()
            );
        }
        return dirty.size();
    }

    /**
     * Reprices {@code BRIDGE_OUT} principals that are continuity-flagged but have no priced
     * upstream inflow within the lookback window on the source wallet/network.
     */
    public int reconcileUnsupportedOutbounds() {
        Query outboundQuery = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("continuityCandidate").is(true),
                Criteria.where("correlationId").regex("^" + BRIDGE_CORR_PREFIX)
        ));
        List<NormalizedTransaction> outbounds = mongoOperations.find(outboundQuery, NormalizedTransaction.class);
        if (outbounds.isEmpty()) {
            return 0;
        }
        Duration lookback = Duration.ofHours(resolveUpstreamLookbackHours());
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int upstreamPricedSkipped = 0;
        int unpricedPrincipalSkipped = 0;
        int pairedMoveBasisSkipped = 0;
        for (NormalizedTransaction outbound : outbounds) {
            NormalizedTransaction.Flow principal = selectPrincipalOutbound(outbound);
            if (principal == null || PriceableFlowPolicy.hasResolvedPrice(principal)) {
                unpricedPrincipalSkipped++;
                continue;
            }
            NormalizedTransaction pairedInbound = loadPairedMoveBasisInbound(outbound);
            if (pairedInbound != null) {
                // Properly linked move-basis pair: let replay carry basis from the outbound position.
                // Do NOT reprice the inbound at market price — that would cause AVCO spikes when the
                // outbound had accumulated non-zero basis via carry chains outside the lookback window.
                // (Shortfall repricing was removed here to prevent oscillation with BRIDGE_IN_SEALED_REPAIR.)
                pairedMoveBasisSkipped++;
                continue;
            }
            if (hasSupplementalMoveBasisLinkage(outbound)) {
                // Supplemental LI.FI source anchored to a shared destination: replay carries basis via
                // LINKED:<sourceHash> on the destination IN leg. Repricing here oscillates with LiFi
                // supplemental anchor repair every linking batch.
                pairedMoveBasisSkipped++;
                continue;
            }
            if (hasPricedUpstreamInflow(outbound, principal, lookback)) {
                upstreamPricedSkipped++;
                continue;
            }
            if (clearContinuityAndRepriceOutbound(outbound, now)) {
                dirty.add(outbound);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
        }
        log.info(
                "UNMATCHED_BRIDGE_OUTBOUND_REPRICE candidates={} upstream_priced_skipped={} "
                        + "unpriced_principal_skipped={} paired_move_basis_skipped={} repriced={}",
                outbounds.size(),
                upstreamPricedSkipped,
                unpricedPrincipalSkipped,
                pairedMoveBasisSkipped,
                dirty.size()
        );
        return dirty.size();
    }

    /**
     * Cycle/14: do not demote paired bridge OUT legs that should carry basis to a linked IN leg.
     */
    private boolean hasPairedMoveBasisInbound(NormalizedTransaction outbound) {
        return loadPairedMoveBasisInbound(outbound) != null;
    }

    private boolean hasSupplementalMoveBasisLinkage(NormalizedTransaction outbound) {
        if (outbound.getMatchedCounterparty() == null || outbound.getMatchedCounterparty().isBlank()) {
            return false;
        }
        NormalizedTransaction destination = loadMatchedDestination(outbound.getMatchedCounterparty());
        return destination != null && BridgePairLinkSupport.hasSupplementalMoveBasisLinkage(outbound, destination);
    }

    private NormalizedTransaction loadMatchedDestination(String destinationTxHash) {
        if (destinationTxHash == null || destinationTxHash.isBlank()) {
            return null;
        }
        Query query = Query.query(Criteria.where("txHash").is(destinationTxHash.trim().toLowerCase(Locale.ROOT)));
        List<NormalizedTransaction> matches = mongoOperations.find(query, NormalizedTransaction.class);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * Returns the paired BRIDGE_IN (or EXTERNAL_TRANSFER_IN) for the given BRIDGE_OUT using the
     * same selection priority as {@link #hasPairedMoveBasisInbound}, or {@code null} if none exists
     * or the pair does not support plain move-basis carry.
     */
    private NormalizedTransaction loadPairedMoveBasisInbound(NormalizedTransaction outbound) {
        if (outbound == null || outbound.getCorrelationId() == null || outbound.getCorrelationId().isBlank()) {
            return null;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("correlationId").is(outbound.getCorrelationId()),
                Criteria.where("_id").ne(outbound.getId()),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN)
                )
        ));
        List<NormalizedTransaction> inbounds = mongoOperations.find(query, NormalizedTransaction.class);
        if (inbounds.isEmpty()) {
            return null;
        }
        NormalizedTransaction inbound = inbounds.size() == 1
                ? inbounds.getFirst()
                : inbounds.stream()
                        .filter(candidate -> outbound.getMatchedCounterparty() != null
                                && outbound.getMatchedCounterparty().equalsIgnoreCase(candidate.getTxHash()))
                        .findFirst()
                        .orElse(inbounds.getFirst());
        return BridgePairLinkSupport.supportsPlainMoveBasis(outbound, inbound) ? inbound : null;
    }

    private int resolveUpstreamLookbackHours() {
        if (pricingProperties == null || pricingProperties.getBridgeOut() == null) {
            return 24;
        }
        int hours = pricingProperties.getBridgeOut().getUpstreamLookbackHours();
        return hours > 0 ? hours : 24;
    }

    private boolean hasPricedUpstreamInflow(
            NormalizedTransaction outbound,
            NormalizedTransaction.Flow principal,
            Duration lookback
    ) {
        if (outbound.getWalletAddress() == null
                || outbound.getNetworkId() == null
                || outbound.getBlockTimestamp() == null) {
            return false;
        }
        Instant windowStart = outbound.getBlockTimestamp().minus(lookback);
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("walletAddress").is(outbound.getWalletAddress()));
        criteria.add(Criteria.where("networkId").is(outbound.getNetworkId()));
        criteria.add(Criteria.where("blockTimestamp").gte(windowStart).lt(outbound.getBlockTimestamp()));
        criteria.add(Criteria.where("status").is(NormalizedTransactionStatus.CONFIRMED));
        if (outbound.getId() != null && !outbound.getId().isBlank()) {
            criteria.add(Criteria.where("_id").ne(outbound.getId()));
        }
        Query upstreamQuery = Query.query(new Criteria().andOperator(criteria.toArray(Criteria[]::new)));
        upstreamQuery.fields().include("flows");
        List<NormalizedTransaction> upstreamDocs = mongoOperations.find(upstreamQuery, NormalizedTransaction.class);
        String outboundFamily = AccountingAssetFamilySupport.continuityIdentity(principal);
        String outboundSymbol = normalizeSymbol(principal.getAssetSymbol());
        for (NormalizedTransaction upstream : upstreamDocs) {
            if (upstream.getFlows() == null) {
                continue;
            }
            for (NormalizedTransaction.Flow flow : upstream.getFlows()) {
                if (flow == null
                        || flow.getQuantityDelta() == null
                        || flow.getQuantityDelta().signum() <= 0
                        || flow.getUnitPriceUsd() == null) {
                    continue;
                }
                if (matchesUpstreamAsset(flow, outboundFamily, outboundSymbol)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesUpstreamAsset(
            NormalizedTransaction.Flow upstreamFlow,
            String outboundFamily,
            String outboundSymbol
    ) {
        if (outboundFamily != null && outboundFamily.startsWith("FAMILY:")) {
            return outboundFamily.equals(AccountingAssetFamilySupport.continuityIdentity(upstreamFlow));
        }
        return !outboundSymbol.isBlank()
                && outboundSymbol.equals(normalizeSymbol(upstreamFlow.getAssetSymbol()));
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    static NormalizedTransaction.Flow selectPrincipalOutbound(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return null;
        }
        NormalizedTransaction.Flow selected = null;
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            if (selected == null
                    || flow.getQuantityDelta().abs().compareTo(selected.getQuantityDelta().abs()) > 0) {
                selected = flow;
            }
        }
        return selected;
    }

    private static void flagNonPegOrphanIfNeeded(NormalizedTransaction inbound) {
        if (PegNeutralBridgeAssumptionSupport.isPegNeutralInbound(inbound)) {
            return;
        }
        List<String> reasons = inbound.getMissingDataReasons();
        if (reasons == null) {
            reasons = new ArrayList<>();
            inbound.setMissingDataReasons(reasons);
        }
        if (!reasons.contains(PegNeutralBridgeAssumptionSupport.NON_PEG_BASIS_UNVERIFIED_REASON)) {
            reasons.add(PegNeutralBridgeAssumptionSupport.NON_PEG_BASIS_UNVERIFIED_REASON);
        }
    }

    private boolean clearContinuityAndRepriceInbound(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            tx.setContinuityCandidate(false);
            changed = true;
        }
        if (tx.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null) {
                    continue;
                }
                if (flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() > 0) {
                    flow.setRole(NormalizedLegRole.BUY);
                    changed = true;
                }
            }
        }
        return finalizeRepriceTrigger(tx, now, changed);
    }

    private boolean clearContinuityAndRepriceOutbound(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            tx.setContinuityCandidate(false);
            changed = true;
        }
        if (tx.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null) {
                    continue;
                }
                if (flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() < 0) {
                    flow.setRole(NormalizedLegRole.SELL);
                    changed = true;
                }
            }
        }
        return finalizeRepriceTrigger(tx, now, changed);
    }

    private boolean finalizeRepriceTrigger(NormalizedTransaction tx, Instant now, boolean changed) {
        if (!changed) {
            return false;
        }
        if (tx.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            tx.setConfirmedAt(null);
        }
        tx.setUpdatedAt(now);
        return true;
    }
}
