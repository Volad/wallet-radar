package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
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
import java.util.List;

/**
 * NEW-13: demotes <em>residual</em> GMX V2 execution-fee refunds to a basis-neutral
 * {@code SPONSORED_GAS_IN} so they no longer fabricate a market {@code ACQUIRE}.
 *
 * <p>GMX keepers occasionally return the unused execution fee of a perp/LP order as a bare native
 * ETH inflow from a GMX handler. {@link GmxV2RefundClassifier} stamps such rows with
 * {@code protocolName="GMX V2"} + {@code GMX_EXECUTION_FEE_REFUND}. Two-step GLV/GM withdrawal
 * settlements that also carry that stamp are first promoted to {@code LP_EXIT_SETTLEMENT} by
 * {@link GmxWithdrawalSettlementLinkService} (NEW-09) so their released carry drains onto the
 * returned assets. Whatever is left after that pass is a genuine standalone execution-fee refund
 * (no matching open {@code gmx-lp:*} {@code LP_EXIT_REQUEST}); it is return-of-capital / gas dust
 * and must be basis-neutral, never a spot acquisition.</p>
 *
 * <p><b>Ordering is load-bearing:</b> this service MUST run strictly after
 * {@code gmxWithdrawalSettlementLink}. Because a genuine settlement is already
 * {@code LP_EXIT_SETTLEMENT} by then, it no longer matches the {@code EXTERNAL_TRANSFER_IN}
 * candidate filter here, so the NEW-09 settlement-promotion guard is preserved and this pass only
 * demotes leftovers.</p>
 *
 * <p>The reclassification mirrors the {@code SPONSORED_GAS_IN → GAS_ONLY} replay path
 * ({@code ReplayDispatcher.isSponsoredGasIn} → {@code GenericFlowReplayEngine.applySponsoredGasIn}):
 * the type becomes {@code SPONSORED_GAS_IN}, each inbound principal leg is reshaped to
 * {@code role=TRANSFER} with market pricing stripped (quantity preserved), and the
 * {@code GMX_EXECUTION_FEE_REFUND} marker is retained as provenance. No new {@code BasisEffect}
 * enum, no ADR — it reuses the existing basis-neutral gas-refund policy.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmxExecutionFeeRefundBasisNeutralService {

    private static final String GMX_V2_PROTOCOL = "GMX V2";
    private static final String FEE_REFUND_REASON = "GMX_EXECUTION_FEE_REFUND";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reclassifyResidualRefunds(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (NormalizedTransaction refund : candidates) {
            // A genuine fee refund is a pure inflow; an outbound principal disqualifies it (it is
            // then a real disposal/settlement, not a return-of-capital gas refund).
            if (!hasInboundPrincipalOnly(refund)) {
                continue;
            }
            if (reclassifyAsBasisNeutralGasRefund(refund, now)) {
                log.info("GMX_EXEC_FEE_REFUND_BASIS_NEUTRAL refund={} -> SPONSORED_GAS_IN", refund.getTxHash());
                dirty.add(refund);
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("GMX_EXEC_FEE_REFUND_BASIS_NEUTRAL demoted={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        // NEW-09 has already run: any refund with a matching open gmx-lp:* LP_EXIT_REQUEST is now
        // LP_EXIT_SETTLEMENT and is excluded by the EXTERNAL_TRANSFER_IN type filter below. The same
        // filter makes this pass idempotent (a demoted row is SPONSORED_GAS_IN, no longer a candidate).
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                Criteria.where("protocolName").is(GMX_V2_PROTOCOL),
                Criteria.where("missingDataReasons").in(FEE_REFUND_REASON)
        ));
        query.with(Sort.by(Sort.Direction.ASC, "blockTimestamp"));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean reclassifyAsBasisNeutralGasRefund(NormalizedTransaction refund, Instant now) {
        refund.setType(NormalizedTransactionType.SPONSORED_GAS_IN);

        if (refund.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : refund.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                    continue;
                }
                // Basis-neutral gas dust: replay books GAS_ONLY (cost 0). Strip the market mark so
                // no acquisition cost enters AVCO; keep the quantity so inventory stays consistent.
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setUnitPriceUsd(null);
                flow.setValueUsd(null);
                flow.setPriceSource(null);
                flow.setRealisedPnlUsd(null);
            }
        }

        // Retain GMX_EXECUTION_FEE_REFUND as provenance (distinguishes from Relay/LiFi sponsored gas).
        refund.setUpdatedAt(now);
        return true;
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
