package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.cex.acquisition.venue.bybit.BybitIntegrationStream;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.wallet.WalletRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves deterministic cost basis for non-stablecoin assets returned from Bybit Trading Bots
 * (ADR-058, NEW-12 Phase 2).
 *
 * <p>Bot {@code FUNDING_HISTORY} events are re-typed to {@code EXTERNAL_TRANSFER_IN/OUT} and tagged
 * {@code BOT_TRANSFER} by {@link BybitCanonicalFlowCounterpartySupport#reclassifyBotTransfer}. They
 * stay on the non-guarded standalone path ({@code correlationId=null},
 * {@code continuityCandidate=false}) so {@code CorridorBasisConservationGuard} can never fire on
 * them (the explicit anti-Phase-1 invariant, ADR-058 D5). Stablecoin bot legs are priced at the $1
 * peg; non-stablecoin returns are left at {@code PENDING_PRICE} with {@code BOT_TRANSFER_PENDING_COST}.</p>
 *
 * <p>This service groups every bot leg into a per-uid compartment (ADR-058 A8: the uid-scoped
 * {@code [firstToBot, lastFromBot]} window is the bot session — bot campaigns routinely span more
 * than the legacy 14-day gap, so grouping is uid-scoped rather than gap-split) and resolves basis
 * <b>before replay</b> (ADR-058 A1/D):</p>
 * <ul>
 *   <li><b>Total basis (cap)</b> = net stablecoin consumed ({@code Σ to-bot stable − Σ from-bot stable
 *       dust}) — the exact USD value that left spendable stable inventory (ADR-058 D2). It is the
 *       upper bound on the allocatable basis, never a target that must be fully consumed.</li>
 *   <li><b>Per-asset basis</b> = {@code returnedQty · avgExecPrice} where
 *       {@code avgExecPrice = Σ(execQty·execPrice) / Σ execQty} over that asset's in-window
 *       {@code EXECUTION_SPOT} BUY fills. Each asset is valued at <i>its own</i> execution cost, never a
 *       sibling's (ADR-058 D3, revised for NEW-12-R). The priced assets are only ever scaled
 *       <b>down</b> (conservation cap when {@code Σ assetBasis &gt; netConsumed}); there is <b>no</b>
 *       upward redistribution, so a shortfall stays as unallocated compartment residual.</li>
 *   <li>A single returned asset <b>with</b> execution coverage collapses to the legacy
 *       {@code netConsumed / qty} rule (bit-identical to the prior {@code BOT_LEDGER} behaviour).</li>
 *   <li>A returned crypto with <b>no</b> usable execution unit price is flagged bounded
 *       {@code EVIDENCE_MISSING} — undefined AVCO per ADR-031, never FMV and never funded from a
 *       sibling's netConsumed share (ADR-058 D4). A session with <b>zero</b> execution coverage
 *       produces no fabricated per-unit basis: every returned asset is {@code EVIDENCE_MISSING}.</li>
 * </ul>
 *
 * <p>Invariant (NEW-12-R): no returned asset's per-unit basis may exceed its own {@code avgExecPrice}.
 * Resolution is deterministic (execId / execPrice keyed), idempotent (only touches rows still
 * carrying {@code BOT_TRANSFER_PENDING_COST}), and never fabricates market value.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitBotTransferCostBasisService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String BOT_TRANSFER_MARKER = "BOT_TRANSFER";
    private static final String BOT_TRANSFER_PENDING_COST = "BOT_TRANSFER_PENDING_COST";
    /** ADR-058 A6: bounded EVIDENCE_MISSING marker for an unpriced return in a multi-asset compartment. */
    private static final String BOT_TRANSFER_EVIDENCE_MISSING = "BOT_TRANSFER_EVIDENCE_MISSING";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int computeBotCostBasis() {
        List<NormalizedTransaction> allBotDocs = loadAllBotDocs();
        if (allBotDocs.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> compartments = groupByUid(allBotDocs);

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int resolved = 0;
        for (Map.Entry<String, List<NormalizedTransaction>> entry : compartments.entrySet()) {
            resolved += resolveCompartment(entry.getKey(), entry.getValue(), now, dirty);
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
        }
        if (resolved > 0) {
            log.info("BYBIT_BOT_COST_BASIS compartments={} resolved={} total_bot_docs={}",
                    compartments.size(), resolved, allBotDocs.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadAllBotDocs() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("missingDataReasons").is(BOT_TRANSFER_MARKER)
        ));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    /**
     * ADR-058 A8: one bot compartment per uid. Grouping is keyed by the umbrella uid so two distinct
     * uids can never merge (deterministic; also fixes the latent cross-uid merge in the legacy global
     * gap-split). Ordering within a compartment is by block timestamp for stable window bounds.
     */
    private Map<String, List<NormalizedTransaction>> groupByUid(List<NormalizedTransaction> botDocs) {
        Map<String, List<NormalizedTransaction>> byUid = new TreeMap<>();
        for (NormalizedTransaction tx : botDocs) {
            String uid = uidOf(tx);
            if (uid == null || uid.isBlank()) {
                continue;
            }
            byUid.computeIfAbsent(uid, key -> new ArrayList<>()).add(tx);
        }
        for (List<NormalizedTransaction> compartment : byUid.values()) {
            compartment.sort(Comparator.comparing(
                    NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));
        }
        return byUid;
    }

    private int resolveCompartment(
            String uid,
            List<NormalizedTransaction> compartment,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        // 1. Totals from the transfer legs (authoritative consideration; ADR-058 A3/D2).
        BigDecimal stablecoinOut = BigDecimal.ZERO;
        BigDecimal stablecoinIn = BigDecimal.ZERO;
        Map<String, BigDecimal> returnedQty = new LinkedHashMap<>();
        Instant firstTs = null;
        Instant lastTs = null;

        for (NormalizedTransaction tx : compartment) {
            Instant ts = tx.getBlockTimestamp();
            if (ts != null) {
                if (firstTs == null || ts.isBefore(firstTs)) {
                    firstTs = ts;
                }
                if (lastTs == null || ts.isAfter(lastTs)) {
                    lastTs = ts;
                }
            }
            NormalizedTransaction.Flow flow = principalFlow(tx);
            if (flow == null || flow.getQuantityDelta() == null || flow.getAssetSymbol() == null) {
                continue;
            }
            String asset = flow.getAssetSymbol().toUpperCase(Locale.ROOT);
            BigDecimal qty = flow.getQuantityDelta();
            if (isStablecoin(asset)) {
                if (qty.signum() < 0) {
                    stablecoinOut = stablecoinOut.add(qty.abs());
                } else if (qty.signum() > 0) {
                    stablecoinIn = stablecoinIn.add(qty);
                }
            } else if (qty.signum() > 0) {
                returnedQty.merge(asset, qty, BigDecimal::add);
            }
        }

        if (returnedQty.isEmpty()) {
            return 0;
        }
        BigDecimal netConsumed = stablecoinOut.subtract(stablecoinIn);
        if (netConsumed.signum() <= 0) {
            return 0;
        }

        // 2. Per-asset unit price from ingested executions (deterministic, execution-anchored split;
        //    ADR-058 D3). An asset with a usable in-window unit price is "priced"; otherwise it has no
        //    execution coverage and can never be funded from a sibling's netConsumed share (D4).
        Map<String, BigDecimal> avgExecPrice = loadExecutionUnitPrices(uid, firstTs, lastTs, returnedQty.keySet());
        Set<String> priced = new LinkedHashSet<>();
        Set<String> unpriced = new LinkedHashSet<>();
        for (String asset : returnedQty.keySet()) {
            BigDecimal price = avgExecPrice.get(asset);
            if (price != null && price.signum() > 0) {
                priced.add(asset);
            } else {
                unpriced.add(asset);
            }
        }

        String sessionId = botSessionId(uid);
        int resolved = 0;

        // 3. Single-asset degenerate case (ADR-058 D3). Exactly one returned crypto asset carries no
        //    cross-asset dumping risk, so the whole netConsumed legitimately maps to it:
        //      - with execution coverage  -> legacy netConsumed / qty (bit-identical to BOT_LEDGER);
        //      - without execution coverage -> D4 bounded EVIDENCE_MISSING (no fabricated per-unit basis).
        if (returnedQty.size() == 1) {
            String asset = returnedQty.keySet().iterator().next();
            if (priced.contains(asset)) {
                BigDecimal unitPrice = safeUnitPrice(netConsumed, returnedQty.get(asset));
                if (unitPrice == null) {
                    return 0;
                }
                return applyBasis(compartment, asset, unitPrice, sessionId, now, dirty);
            }
            return markEvidenceMissing(compartment, asset, sessionId, now, dirty);
        }

        // 4. Multi-asset compartment (ADR-058 D4). Every unpriced return is bounded EVIDENCE_MISSING and
        //    its share of netConsumed stays UNALLOCATED — it is never pushed onto a priced sibling. A
        //    session with zero execution coverage therefore produces no fabricated basis at all (this is
        //    the NEW-12-R fix: removing an asset to EVIDENCE_MISSING must reduce the allocatable base,
        //    not concentrate it onto the last priced asset standing).
        for (String asset : unpriced) {
            resolved += markEvidenceMissing(compartment, asset, sessionId, now, dirty);
        }
        if (priced.isEmpty()) {
            return resolved;
        }

        // 5. Priced assets (ADR-058 D3). Each asset is valued at ITS OWN execution cost
        //    (assetBasis = returnedQty * avgExecPrice), then a conservation cap is applied DOWNWARD only:
        //      - if Σ assetBasis > netConsumed -> scale every priced asset down by netConsumed / Σ assetBasis;
        //      - if Σ assetBasis <= netConsumed -> scale = 1 (no upward redistribution); the shortfall is
        //        retained as unallocated compartment residual.
        //    Invariant (NEW-12-R): no returned asset's per-unit basis may exceed its own avgExecPrice.
        BigDecimal rawTotal = BigDecimal.ZERO;
        for (String asset : priced) {
            rawTotal = rawTotal.add(returnedQty.get(asset).multiply(avgExecPrice.get(asset), MC));
        }
        if (rawTotal.signum() <= 0) {
            return resolved;
        }
        BigDecimal scale = rawTotal.compareTo(netConsumed) > 0
                ? netConsumed.divide(rawTotal, MC)
                : BigDecimal.ONE;
        for (String asset : priced) {
            BigDecimal unitPrice = Decimal128Support.normalize(avgExecPrice.get(asset).multiply(scale, MC));
            if (unitPrice == null || unitPrice.signum() <= 0) {
                continue;
            }
            resolved += applyBasis(compartment, asset, unitPrice, sessionId, now, dirty);
        }
        return resolved;
    }

    /**
     * ADR-058 A4/D3: {@code avgExecPrice(asset) = Σ(execQty·execPrice) / Σ execQty} over in-window
     * {@code EXECUTION_SPOT} BUY fills for the uid. Executions are used only for the relative split,
     * so quantity gaps in the fills cannot corrupt the total (which comes from the transfer legs).
     */
    private Map<String, BigDecimal> loadExecutionUnitPrices(
            String uid,
            Instant firstTs,
            Instant lastTs,
            Set<String> assets
    ) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (uid == null || firstTs == null || lastTs == null || assets.isEmpty()) {
            return result;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("uid").is(uid),
                Criteria.where("sourceStream").is(BybitIntegrationStream.EXECUTION_SPOT.name()),
                Criteria.where("utaDirection").is("BUY"),
                Criteria.where("assetSymbol").in(assets),
                Criteria.where("timeUtc").gte(firstTs).lte(lastTs)
        ));
        List<BybitExtractedEvent> fills = mongoOperations.find(query, BybitExtractedEvent.class);

        Map<String, BigDecimal> valueSum = new LinkedHashMap<>();
        Map<String, BigDecimal> qtySum = new LinkedHashMap<>();
        for (BybitExtractedEvent fill : fills) {
            String asset = fill.getAssetSymbol() == null ? null : fill.getAssetSymbol().toUpperCase(Locale.ROOT);
            BigDecimal qty = fill.getQuantityRaw();
            BigDecimal price = fill.getFilledPrice();
            if (asset == null || qty == null || price == null || qty.signum() <= 0 || price.signum() <= 0) {
                continue;
            }
            valueSum.merge(asset, qty.multiply(price, MC), BigDecimal::add);
            qtySum.merge(asset, qty, BigDecimal::add);
        }
        for (String asset : assets) {
            BigDecimal value = valueSum.get(asset);
            BigDecimal qty = qtySum.get(asset);
            if (value != null && qty != null && qty.signum() > 0) {
                result.put(asset, Decimal128Support.normalize(value.divide(qty, MC)));
            }
        }
        return result;
    }

    private int applyBasis(
            List<NormalizedTransaction> compartment,
            String asset,
            BigDecimal unitPrice,
            String sessionId,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        int resolved = 0;
        for (NormalizedTransaction tx : compartment) {
            if (!hasPendingBotCost(tx)) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(tx);
            if (!isReturnLegForAsset(flow, asset)) {
                continue;
            }
            flow.setUnitPriceUsd(unitPrice);
            flow.setValueUsd(Decimal128Support.normalize(flow.getQuantityDelta().abs().multiply(unitPrice, MC)));
            flow.setPriceSource(PriceSource.BOT_LEDGER);
            confirmResolved(tx, sessionId, now);
            dirty.add(tx);
            resolved++;
        }
        return resolved;
    }

    /**
     * ADR-058 A6 / ADR-031: an unpriced return in a multi-asset compartment acquires bounded
     * undefined AVCO — {@code PRICING_SKIPPED} keeps {@code PriceableFlowPolicy.requiresMarketPrice}
     * false so no FMV is ever stamped, and no fabricated basis leaks into replay.
     */
    private int markEvidenceMissing(
            List<NormalizedTransaction> compartment,
            String asset,
            String sessionId,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        int resolved = 0;
        for (NormalizedTransaction tx : compartment) {
            if (!hasPendingBotCost(tx)) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(tx);
            if (!isReturnLegForAsset(flow, asset)) {
                continue;
            }
            flow.setUnitPriceUsd(null);
            flow.setValueUsd(null);
            flow.setPriceSource(PriceSource.PRICING_SKIPPED);
            confirmResolved(tx, sessionId, now);
            if (!tx.getMissingDataReasons().contains(BOT_TRANSFER_EVIDENCE_MISSING)) {
                tx.getMissingDataReasons().add(BOT_TRANSFER_EVIDENCE_MISSING);
            }
            dirty.add(tx);
            resolved++;
        }
        return resolved;
    }

    private void confirmResolved(NormalizedTransaction tx, String sessionId, Instant now) {
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setConfirmedAt(now);
        tx.getMissingDataReasons().remove(BOT_TRANSFER_PENDING_COST);
        tx.setUpdatedAt(now);
        stampBotSessionId(tx, sessionId);
    }

    /** ADR-058 O2: persist the deterministic bot session id on the resolved legs for traceability. */
    private void stampBotSessionId(NormalizedTransaction tx, String sessionId) {
        if (sessionId == null) {
            return;
        }
        org.bson.Document metadata = tx.getMetadata();
        if (metadata == null) {
            metadata = new org.bson.Document();
            tx.setMetadata(metadata);
        }
        metadata.put("botSessionId", sessionId);
    }

    private static String botSessionId(String uid) {
        return "BYBIT:" + uid + ":BOT";
    }

    private static boolean isReturnLegForAsset(NormalizedTransaction.Flow flow, String asset) {
        return flow != null
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0
                && flow.getAssetSymbol() != null
                && flow.getAssetSymbol().toUpperCase(Locale.ROOT).equals(asset);
    }

    private BigDecimal safeUnitPrice(BigDecimal basis, BigDecimal quantity) {
        if (basis == null || quantity == null || quantity.signum() <= 0) {
            return null;
        }
        BigDecimal unitPrice = Decimal128Support.normalize(basis.divide(quantity, MC));
        return (unitPrice == null || unitPrice.signum() <= 0) ? null : unitPrice;
    }

    private static boolean hasPendingBotCost(NormalizedTransaction tx) {
        return tx != null
                && tx.getMissingDataReasons() != null
                && tx.getMissingDataReasons().contains(BOT_TRANSFER_PENDING_COST);
    }

    private static NormalizedTransaction.Flow principalFlow(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
                return flow;
            }
        }
        return null;
    }

    private static String uidOf(NormalizedTransaction tx) {
        if (tx == null) {
            return null;
        }
        if (tx.getBybitUid() != null && !tx.getBybitUid().isBlank()) {
            return tx.getBybitUid().trim();
        }
        WalletRef ref = WalletRef.parse(tx.getWalletAddress());
        String uid = ref.uid();
        return uid == null || uid.isBlank() ? null : uid;
    }

    private static boolean isStablecoin(String symbol) {
        return BybitStablecoinPegSymbols.isPegged(symbol);
    }
}
