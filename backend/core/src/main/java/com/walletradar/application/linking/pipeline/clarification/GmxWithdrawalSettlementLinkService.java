package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Links two-step GMX V2 GLV/GM withdrawal <em>settlements</em> to their preceding
 * {@code LP_EXIT_REQUEST}.
 *
 * <p>GMX processes withdrawals asynchronously: the user submits a withdrawal request
 * ({@code LP_EXIT_REQUEST}, which already releases the position's carried basis into the
 * async lifecycle bucket keyed by {@code gmx-lp:*}), and a GMX keeper settles it within
 * seconds by returning the underlying WETH/USDC (and any unused execution fee) to the
 * wallet in a separate transaction.</p>
 *
 * <p>When that settlement is ingested <em>only</em> as an internal-transactions record —
 * no selector, no {@code functionName}, no logs (e.g. {@code executeGlvWithdrawal}
 * delivered as native ETH from a GMX handler) — the per-tx on-chain classifier cannot
 * recognise it as a withdrawal settlement. It is typed {@code EXTERNAL_TRANSFER_IN} and,
 * once {@link GmxV2RefundClassifier} stamps it, carries {@code protocolName="GMX V2"} plus
 * the {@code GMX_EXECUTION_FEE_REFUND} marker. Left alone it would fabricate a fresh market
 * ACQUIRE and strand the released carry in the async bucket.</p>
 *
 * <p>This pass re-types such candidates as {@code LP_EXIT_SETTLEMENT} and stamps them with
 * the matching {@code gmx-lp:*} correlationId so that the existing
 * {@code ASYNC_LP_EXIT_SETTLEMENT} replay route drains the carried basis onto the returned
 * assets ({@code REALLOCATE_IN}) instead of booking a market acquisition.</p>
 *
 * <p><b>Deterministic pairing (no amount heuristics):</b> the settlement is paired to the
 * unique open {@code gmx-lp:*} {@code LP_EXIT_REQUEST} for the <em>same wallet</em> whose
 * {@code blockTimestamp} is the nearest preceding one within
 * {@value #SETTLEMENT_WINDOW_SECONDS} seconds and that is not already settled. If two or
 * more distinct positions are open in-window (ambiguous), the settlement is left as-is —
 * never guessed. Genuine standalone execution-fee refunds with no matching open request
 * also stay untouched.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmxWithdrawalSettlementLinkService {

    private static final String GMX_LP_PREFIX = "gmx-lp:";
    private static final String GMX_V2_PROTOCOL = "GMX V2";
    /** Aligns with the paired {@code LP_EXIT_REQUEST}, which stores {@code protocolName="GMX"}. */
    private static final String GMX_PROTOCOL = "GMX";
    private static final String CP_TYPE_PROTOCOL = "PROTOCOL";
    private static final String FEE_REFUND_REASON = "GMX_EXECUTION_FEE_REFUND";
    /**
     * Backwards window applied from the settlement timestamp. GMX keepers settle within a
     * few seconds; 600s mirrors {@code GmxEntryRequestLinkService.SETTLEMENT_WINDOW_SECONDS}
     * and is conservative yet safe.
     */
    private static final long SETTLEMENT_WINDOW_SECONDS = 600;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int linkOutstandingWithdrawalSettlements(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (NormalizedTransaction settlement : candidates) {
            String wallet = settlement.getWalletAddress();
            Instant settlementTs = settlement.getBlockTimestamp();
            if (wallet == null || wallet.isBlank() || settlementTs == null) {
                continue;
            }
            // Fee-refund inbounds are pure inflows; an outbound principal disqualifies (not a settlement).
            if (!hasInboundPrincipalOnly(settlement)) {
                continue;
            }

            NormalizedTransaction request = findMatchingOpenExitRequest(wallet, settlementTs);
            if (request == null || request.getCorrelationId() == null) {
                continue;
            }

            reclassifyAsSettlement(settlement, request, now);
            log.info("GMX_WITHDRAWAL_SETTLEMENT_LINK settlement={} -> corrId={} (request={})",
                    settlement.getTxHash(), request.getCorrelationId(), request.getTxHash());
            dirty.add(settlement);
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("GMX_WITHDRAWAL_SETTLEMENT_LINK linked={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                Criteria.where("protocolName").is(GMX_V2_PROTOCOL),
                Criteria.where("missingDataReasons").in(FEE_REFUND_REASON)
        ));
        query.with(Sort.by(Sort.Direction.ASC, "blockTimestamp"));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    /**
     * Returns the unique open {@code gmx-lp:*} {@code LP_EXIT_REQUEST} for the wallet whose
     * timestamp is the nearest preceding one within the settlement window and that has not yet
     * been settled. Returns {@code null} when nothing matches or the in-window set is ambiguous
     * (two or more distinct positions).
     */
    NormalizedTransaction findMatchingOpenExitRequest(String walletAddress, Instant settlementTs) {
        Instant windowStart = settlementTs.minusSeconds(SETTLEMENT_WINDOW_SECONDS);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.LP_EXIT_REQUEST),
                Criteria.where("walletAddress").is(walletAddress),
                Criteria.where("correlationId").regex("^" + GMX_LP_PREFIX),
                Criteria.where("blockTimestamp").gte(windowStart).lte(settlementTs)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "blockTimestamp"));
        List<NormalizedTransaction> requests = mongoOperations.find(query, NormalizedTransaction.class);

        List<NormalizedTransaction> open = new ArrayList<>();
        Set<String> distinctCorrelationIds = new LinkedHashSet<>();
        for (NormalizedTransaction request : requests) {
            String correlationId = request.getCorrelationId();
            if (correlationId == null || correlationId.isBlank()) {
                continue;
            }
            if (isAlreadySettled(walletAddress, correlationId)) {
                continue;
            }
            open.add(request);
            distinctCorrelationIds.add(correlationId);
        }

        if (open.isEmpty()) {
            return null;
        }
        // Ambiguity guard: never guess between two distinct open positions in-window.
        if (distinctCorrelationIds.size() >= 2) {
            log.debug("GMX_WITHDRAWAL_SETTLEMENT_LINK ambiguous: {} open positions in-window for wallet={}",
                    distinctCorrelationIds.size(), walletAddress);
            return null;
        }
        // Sorted DESC → the first is the nearest-preceding request.
        return open.getFirst();
    }

    private boolean isAlreadySettled(String walletAddress, String correlationId) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.LP_EXIT_SETTLEMENT),
                Criteria.where("walletAddress").is(walletAddress),
                Criteria.where("correlationId").is(correlationId)
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private void reclassifyAsSettlement(NormalizedTransaction settlement,
                                        NormalizedTransaction request,
                                        Instant now) {
        settlement.setType(NormalizedTransactionType.LP_EXIT_SETTLEMENT);
        settlement.setCorrelationId(request.getCorrelationId());
        settlement.setProtocolName(GMX_PROTOCOL);
        settlement.setCounterpartyType(CP_TYPE_PROTOCOL);

        if (settlement.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : settlement.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                    continue;
                }
                // Carried basis is supplied by the async lifecycle bucket during replay, not by a
                // fresh market mark. Strip market pricing so the leg drains carry (REALLOCATE_IN).
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setUnitPriceUsd(null);
                flow.setValueUsd(null);
                flow.setPriceSource(null);
                flow.setRealisedPnlUsd(null);
            }
        }

        List<String> reasons = settlement.getMissingDataReasons();
        if (reasons != null) {
            reasons.remove(FEE_REFUND_REASON);
        }
        settlement.setUpdatedAt(now);
    }

    private static boolean hasInboundPrincipalOnly(NormalizedTransaction tx) {
        if (tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return false;
        }
        boolean hasInbound = false;
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                return false;
            }
            hasInbound = true;
        }
        return hasInbound;
    }
}
