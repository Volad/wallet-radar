package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.UncoveredExternalInboundSupport;
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
import java.util.Locale;

/**
 * B2b — reclassifies a genuinely <em>sourceless</em> {@code BRIDGE_IN} as an uncovered
 * {@code EXTERNAL_TRANSFER_IN} so it stops fabricating a market-at-arrival cost basis.
 *
 * <p>A {@code BRIDGE_IN} models funds arriving from another chain. When no correlatable source leg
 * (a {@code BRIDGE_OUT} / linked corridor peer) exists in our transaction universe after every
 * pairing pass has run, the funds' true cost basis is unknown. Today such a lone inbound is priced
 * at the spot market of its arrival block — inventing a basis the wallet never paid, which silently
 * dilutes the pooled AVCO and can later surface as phantom P&amp;L (audit B2b: {@code 0xb71f4e…}
 * ≈ +$47, {@code 0xaddb9f…} ≈ +$20).</p>
 *
 * <p>This terminal pass (runs after all bridge/corridor pairing) finds ON_CHAIN {@code BRIDGE_IN}
 * rows that are still <b>unlinked</b> — no {@code bridge:} correlation and not
 * {@code continuityCandidate} — and that are <b>not peg-neutral</b> (a bridged-in stablecoin's
 * basis ≈ its peg, so market pricing stays basis-neutral and is left untouched by
 * {@link PegNeutralBridgeAssumptionSupport}). It reclassifies them to {@code EXTERNAL_TRANSFER_IN}
 * and stamps {@link UncoveredExternalInboundSupport#SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN}. At
 * replay that marker routes the leg onto the uncovered / incomplete-history (PENDING) path instead
 * of the market-priced acquisition path, so the position grows in quantity while its basis stays
 * unknown (uncovered) — the honest treatment for capital of unprovable origin.</p>
 *
 * <p>Deterministic and idempotent: the only anchors are the row's own type/source/correlation/
 * continuity state and its principal asset's peg-neutrality; no per-transaction-hash or per-address
 * runtime keys. Once reclassified the row is no longer a {@code BRIDGE_IN}, so a re-run is a no-op;
 * a full renormalization re-creates the {@code BRIDGE_IN} and this pass re-applies the marker.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SourcelessBridgeInboundReclassificationService {

    private static final String BRIDGE_CORR_PREFIX = "bridge:";
    private static final String BRIDGE_LEG_NOT_FOUND_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcile(int batchSize) {
        List<NormalizedTransaction> candidates = loadLoneInboundBatch(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction inbound : candidates) {
            if (reclassifyAsUncoveredExternalInbound(inbound, now)) {
                dirty.add(inbound);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "SOURCELESS_BRIDGE_INBOUND_UNCOVERED candidates={} reclassified={}",
                    candidates.size(),
                    dirty.size()
            );
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadLoneInboundBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                Criteria.where("continuityCandidate").ne(true),
                unlinkedBridgeCriteria()
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::isReclassifiableLoneInbound)
                .toList();
    }

    private Criteria unlinkedBridgeCriteria() {
        return new Criteria().orOperator(
                Criteria.where("correlationId").exists(false),
                Criteria.where("correlationId").is(null),
                Criteria.where("correlationId").is(""),
                Criteria.where("correlationId").not().regex("^" + BRIDGE_CORR_PREFIX, "i")
        );
    }

    private boolean isReclassifiableLoneInbound(NormalizedTransaction inbound) {
        return inbound != null
                && inbound.getSource() == NormalizedTransactionSource.ON_CHAIN
                && inbound.getType() == NormalizedTransactionType.BRIDGE_IN
                && !Boolean.TRUE.equals(inbound.getContinuityCandidate())
                && !hasBridgeCorrelation(inbound.getCorrelationId())
                // A bridged-in stablecoin's basis ≈ peg, so a market-priced acquisition is
                // basis-neutral; leave it to the existing peg-neutral fallback untouched.
                && !PegNeutralBridgeAssumptionSupport.isPegNeutralInbound(inbound);
    }

    private boolean reclassifyAsUncoveredExternalInbound(NormalizedTransaction inbound, Instant now) {
        if (!isReclassifiableLoneInbound(inbound)) {
            return false;
        }
        boolean changed = false;
        if (inbound.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            inbound.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
            changed = true;
        }
        List<String> reasons = inbound.getMissingDataReasons();
        if (reasons == null) {
            reasons = new ArrayList<>();
            inbound.setMissingDataReasons(reasons);
        }
        if (!reasons.contains(UncoveredExternalInboundSupport.SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN)) {
            reasons.add(UncoveredExternalInboundSupport.SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN);
            changed = true;
        }
        // The "bridge source leg not found" reason is now superseded by the explicit basis-unknown
        // marker; drop it so the row is not double-flagged for the same missing source.
        if (reasons.remove(BRIDGE_LEG_NOT_FOUND_REASON)) {
            changed = true;
        }
        if (changed) {
            inbound.setUpdatedAt(now);
        }
        return changed;
    }

    private static boolean hasBridgeCorrelation(String correlationId) {
        return correlationId != null
                && correlationId.toLowerCase(Locale.ROOT).startsWith(BRIDGE_CORR_PREFIX);
    }
}
