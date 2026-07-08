package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferPairer;
import com.walletradar.pricing.application.PriceableFlowPolicy;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Re-attaches same-universe wallet <-> Bybit transfer continuity after an
 * on-chain-only rerun rebuilt the wallet row without the already known Bybit match.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitTransferContinuityRepairService {

    private static final MathContext MC = MathContext.DECIMAL64;
    /** FA-001 D2: match Bybit extract / shadow rows within 5×10⁻⁴ relative quantity drift. */
    private static final BigDecimal RELATIVE_QTY_TOLERANCE = new BigDecimal("0.0005");
    private static final BigDecimal ABSOLUTE_QTY_TOLERANCE = new BigDecimal("0.000001");
    private static final Pattern EVM_HEX_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final Pattern BYBIT_REF = Pattern.compile("^BYBIT:[^\\s]+$");
    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    private static final String EXTERNAL_CUSTODY_UNTRACKED_VENUE = "EXTERNAL_CUSTODY_UNTRACKED_VENUE";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;

    public int reconcileOutstandingPairs(int batchSize) {
        int changed = 0;
        List<NormalizedTransaction> batch = loadCandidateBatch(batchSize);
        for (NormalizedTransaction candidate : batch) {
            if (repair(candidate)) {
                changed++;
            }
        }
        // Cycle/6 B1: also drive pairing from the Bybit-side. The legacy path only loaded on-chain
        // candidates, so cases where the Bybit deposit row materialised AFTER the on-chain leg was
        // confirmed (e.g. backfill of a CEX integration on an already-tracked wallet) never paired.
        List<NormalizedTransaction> bybitBatch = loadBybitCandidateBatch(batchSize);
        for (NormalizedTransaction bybitCandidate : bybitBatch) {
            if (repairFromBybitSide(bybitCandidate)) {
                changed++;
            }
        }
        if (changed > 0 || !batch.isEmpty() || !bybitBatch.isEmpty()) {
            log.info("BybitTransferContinuityRepair: onchainCandidates={} bybitCandidates={} paired={}",
                    batch.size(), bybitBatch.size(), changed);
        }
        changed += reclassifyPairedCorridorExternals(batchSize);
        return changed;
    }

    boolean repairFromBybitSide(NormalizedTransaction bybitCandidate) {
        if (bybitCandidate == null
                || bybitCandidate.getSource() != NormalizedTransactionSource.BYBIT
                || bybitCandidate.getNetworkId() == null
                || blank(bybitCandidate.getTxHash())
                || (!isRepairableBybitExternalType(bybitCandidate.getType())
                && !isFa001BybitDepositAnchor(bybitCandidate))) {
            return false;
        }
        List<NormalizedTransaction> onChainCandidates = selectOnChainPartners(bybitCandidate);
        if (onChainCandidates.size() != 1) {
            return false;
        }
        return repair(onChainCandidates.getFirst());
    }

    private List<NormalizedTransaction> loadBybitCandidateBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                new Criteria().orOperator(
                        Criteria.where("type").in(
                                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                        ),
                        new Criteria().andOperator(
                                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                                Criteria.where("matchedCounterparty").regex("^0x[a-fA-F0-9]{40}$")
                        )
                ),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("networkId").exists(true),
                new Criteria().orOperator(
                        Criteria.where("continuityCandidate").ne(Boolean.TRUE),
                        Criteria.where("matchedCounterparty").exists(false),
                        Criteria.where("matchedCounterparty").is(null),
                        Criteria.where("matchedCounterparty").is("")
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean repair(NormalizedTransaction onChainCandidate) {
        if (!isOnChainCandidate(onChainCandidate) && !isFa001OnChainPartner(onChainCandidate)) {
            return false;
        }
        List<NormalizedTransaction> compatibleBybitRows = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                        onChainCandidate.getTxHash(),
                        onChainCandidate.getNetworkId(),
                        NormalizedTransactionSource.BYBIT
                ).stream()
                .filter(bybitRow -> isPairable(onChainCandidate, bybitRow))
                .toList();
        // RC-9 D1: drop the size()==1 hard gate. A re-materialised refresh can present a stream
        // mirror alongside the real corridor leg, flipping the count from 1 to 2 and producing a
        // different (or no) correlation than the full rebuild. Instead pick the canonical Bybit leg
        // deterministically (endpoint-matching FUND deposit anchor first, then lowest _id) so the
        // corridor triple is a pure, order-stable function of the raw legs.
        NormalizedTransaction bybitRow = selectCanonicalBybitLeg(onChainCandidate, compatibleBybitRows);
        if (bybitRow == null) {
            return false;
        }
        String correlationId = correlationId(onChainCandidate.getNetworkId(), onChainCandidate.getTxHash());
        Instant now = Instant.now();

        boolean leftChanged = applyContinuityMetadata(
                onChainCandidate,
                correlationId,
                bybitRow.getWalletAddress(),
                now
        );
        boolean rightChanged = applyContinuityMetadata(
                bybitRow,
                correlationId,
                onChainCandidate.getWalletAddress(),
                now
        );
        boolean onChainFlowsRetagged = retagOnChainPrincipalFlowsForContinuity(onChainCandidate, now);
        boolean onChainRewritten = rewriteOnChainLegAsInternalCexTransfer(onChainCandidate, bybitRow, now);
        // Cycle/5 N18: symmetric retag for the Bybit anchor. After N17 the FH/Deposit / FH/Withdraw
        // flow carries role=BUY/SELL with no priced value yet; without retagging here the pricing job
        // would later assign market value and the cost-basis replay would acquire fresh basis at
        // market, producing the very phantom step-up that the continuity link was supposed to
        // eliminate. By demoting the Bybit principal flow to TRANSFER (and clearing any price/value
        // fields, including realised PnL written by an earlier replay pass), the replay engine sees a
        // pure basis carry — on-chain disposes at original AVCO, Bybit acquires at the same AVCO,
        // realised PnL = 0 across the pair (which matches reality: this is the user moving their own
        // crypto between their wallets, not selling on one side and buying on the other).
        boolean bybitFlowsRetagged = retagBybitPrincipalFlowsForContinuity(bybitRow, now);
        boolean bybitRewritten = rewriteBybitLegAsInternalTransfer(bybitRow, now);
        if (!leftChanged && !rightChanged && !onChainFlowsRetagged && !bybitFlowsRetagged && !onChainRewritten && !bybitRewritten) {
            return false;
        }

        normalizedTransactionRepository.saveAll(deduplicateById(List.of(onChainCandidate, bybitRow)));
        return true;
    }

    /**
     * Cycle/18 R8: FA-001 corridor rows that already carry a tracked on-chain counterparty must
     * be typed as {@code INTERNAL_TRANSFER}, not {@code EXTERNAL_TRANSFER_IN/OUT}.
     */
    private int reclassifyPairedCorridorExternals(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                Criteria.where("correlationId").regex("^BYBIT-CORRIDOR:"),
                Criteria.where("matchedCounterparty").exists(true).ne(""),
                Criteria.where("matchedCounterparty").not().regex("^BYBIT:", "i")
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (rewriteBybitLegAsInternalTransfer(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_PAIRED_CORRIDOR_TYPE_RECLASSIFIER candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    private boolean rewriteBybitLegAsInternalTransfer(NormalizedTransaction bybit, Instant now) {
        if (bybit == null
                || bybit.getSource() != NormalizedTransactionSource.BYBIT
                || bybit.getType() == NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        if (bybit.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && bybit.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        String matchedCounterparty = bybit.getMatchedCounterparty();
        if (blank(matchedCounterparty) || matchedCounterparty.trim().regionMatches(true, 0, "BYBIT:", 0, "BYBIT:".length())) {
            return false;
        }
        bybit.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        bybit.setUpdatedAt(now);
        return true;
    }

    /**
     * Cycle/5 N18: strip priced semantics from the Bybit FH-anchor principal flow once the cross-wallet
     * continuity pair is established. Mirrors {@link #retagOnChainPrincipalFlowsForContinuity}.
     */
    private boolean retagBybitPrincipalFlowsForContinuity(NormalizedTransaction bybit, Instant now) {
        if (bybit.getFlows() == null || bybit.getFlows().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (NormalizedTransaction.Flow flow : bybit.getFlows()) {
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
        if (changed && shouldRequeueContinuityInboundPricing(bybit)) {
            bybit.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            bybit.setConfirmedAt(null);
        }
        if (changed) {
            bybit.setUpdatedAt(now);
        }
        return changed;
    }

    /**
     * Cycle/16 R6: FA-001 continuity retagging demotes BUY→TRANSFER and clears prices so basis
     * carries from the on-chain leg. When carry leaves residual uncov, re-queue pricing for any
     * inbound TRANSFER leg so replay can apply inbound shortfall spot fallback.
     */
    private static boolean shouldRequeueContinuityInboundPricing(NormalizedTransaction bybit) {
        if (bybit == null || bybit.getFlows() == null) {
            return false;
        }
        if (bybit.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && bybit.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : bybit.getFlows()) {
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            return flow.getUnitPriceUsd() == null || flow.getUnitPriceUsd().signum() <= 0;
        }
        return false;
    }

    private List<NormalizedTransaction> loadCandidateBatch(int batchSize) {
        // FA-001 P1: drop the EVM-only walletAddress regex so SOL (base58) and TON (UQ/EQ/raw)
        // candidates also enter the FA-001 corridor. The {@code isOnChainCandidate} predicate
        // applies a network-aware shape check downstream.
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").in(
                        NormalizedTransactionStatus.CONFIRMED,
                        NormalizedTransactionStatus.PENDING_PRICE
                ),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                        NormalizedTransactionType.BRIDGE_IN,
                        NormalizedTransactionType.BRIDGE_OUT
                ),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("networkId").exists(true),
                Criteria.where("walletAddress").exists(true).ne(""),
                new Criteria().orOperator(
                        Criteria.where("continuityCandidate").ne(Boolean.TRUE),
                        bybitCorrelationMissingCriteria(),
                        bybitCounterpartyMissingCriteria()
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private Criteria bybitCorrelationMissingCriteria() {
        // Cycle/7 S3: accept both the legacy `BYBIT:` prefix and the new `BYBIT-CORRIDOR:` prefix
        // as "already paired" so we do not re-pair existing rows after the format switch.
        return new Criteria().orOperator(
                Criteria.where("correlationId").exists(false),
                Criteria.where("correlationId").is(null),
                Criteria.where("correlationId").is(""),
                Criteria.where("correlationId").not().regex("^BYBIT[-:]")
        );
    }

    private Criteria bybitCounterpartyMissingCriteria() {
        return new Criteria().orOperator(
                Criteria.where("matchedCounterparty").exists(false),
                Criteria.where("matchedCounterparty").is(null),
                Criteria.where("matchedCounterparty").is(""),
                Criteria.where("matchedCounterparty").not().regex("^BYBIT:")
        );
    }

    private boolean isOnChainCandidate(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getSource() == NormalizedTransactionSource.ON_CHAIN
                && (transaction.getStatus() == NormalizedTransactionStatus.CONFIRMED
                || transaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE)
                && hasOnChainWalletShape(transaction.getWalletAddress(), transaction.getNetworkId())
                && transaction.getNetworkId() != null
                && !blank(transaction.getTxHash())
                && isFa001OnChainType(transaction.getType())
                && principalFlows(transaction).size() == 1;
    }

    private boolean isRepairableExcludedBybitLeg(NormalizedTransaction bybit) {
        if (bybit == null || !Boolean.TRUE.equals(bybit.getExcludedFromAccounting())) {
            return true;
        }
        String reason = bybit.getAccountingExclusionReason();
        if (EXTERNAL_CUSTODY_UNTRACKED_VENUE.equals(reason)) {
            return true;
        }
        if (hasBybitCorridorCorrelation(bybit.getCorrelationId())) {
            return true;
        }
        // B-ZERO-3: same-sign mirror demotion runs after FA-001 pairing in linking; allow re-pair
        // when the Bybit FH withdraw anchor shares the on-chain txHash.
        return BybitInternalTransferPairer.SAME_SIGN_MIRROR_REASON.equals(reason)
                && !blank(bybit.getTxHash());
    }

    private boolean isPairable(
            NormalizedTransaction onChain,
            NormalizedTransaction bybit
    ) {
        if (bybit == null
                || bybit.getSource() != NormalizedTransactionSource.BYBIT
                || !isRepairableBybitStatus(bybit)
                || !hasBybitRef(bybit.getWalletAddress())
                || bybit.getNetworkId() != onChain.getNetworkId()
                || !NetworkAddressFormat.txHashesEqual(onChain.getNetworkId(), bybit.getTxHash(), onChain.getTxHash())
                || !directionCompatible(onChain, bybit)
                || !accountingUniverseService.shareUniverseMembers(onChain.getWalletAddress(), bybit.getWalletAddress())
                || principalFlows(bybit).size() != 1) {
            return false;
        }
        if (Boolean.TRUE.equals(bybit.getExcludedFromAccounting())
                && !isRepairableExcludedBybitLeg(bybit)) {
            return false;
        }
        if (!blank(bybit.getMatchedCounterparty())
                && !sameText(bybit.getMatchedCounterparty(), onChain.getWalletAddress())) {
            return false;
        }

        NormalizedTransaction.Flow onChainPrincipal = principalFlows(onChain).getFirst();
        NormalizedTransaction.Flow bybitPrincipal = principalFlows(bybit).getFirst();
        String onChainFamily = AccountingAssetFamilySupport.continuityIdentity(onChainPrincipal);
        String bybitFamily = AccountingAssetFamilySupport.continuityIdentity(bybitPrincipal);
        if (onChainFamily == null || !Objects.equals(onChainFamily, bybitFamily)) {
            return false;
        }
        if (walletEndpointMatches(onChain, bybit)) {
            return true;
        }
        return quantitiesCompatible(
                onChainPrincipal.getQuantityDelta().abs(),
                bybitPrincipal.getQuantityDelta().abs()
        );
    }

    /**
     * FA-001 R10: when Bybit raw extract already stamped the on-chain wallet endpoint on
     * {@code counterpartyAddress}, pairing must not fail on withdrawal-fee quantity drift.
     */
    private boolean walletEndpointMatches(NormalizedTransaction onChain, NormalizedTransaction bybit) {
        if (onChain == null || bybit == null || blank(bybit.getCounterpartyAddress())) {
            return false;
        }
        String counterparty = bybit.getCounterpartyAddress().trim();
        if (counterparty.regionMatches(true, 0, "BYBIT:", 0, "BYBIT:".length())) {
            return false;
        }
        if (!hasOnChainWalletShape(counterparty, onChain.getNetworkId())) {
            return false;
        }
        return sameText(counterparty, onChain.getWalletAddress());
    }

    /**
     * RC-9 D1: deterministic canonical-leg selector. Among all Bybit rows compatible with the
     * on-chain leg, prefer the wallet-endpoint-matching FUND deposit anchor, then break remaining
     * ties by lowest {@code _id}. Pure and order-stable: the same candidate set always yields the
     * same leg regardless of DB iteration order or row materialisation timing.
     */
    private NormalizedTransaction selectCanonicalBybitLeg(
            NormalizedTransaction onChain,
            List<NormalizedTransaction> compatibleBybitRows
    ) {
        if (compatibleBybitRows.isEmpty()) {
            return null;
        }
        if (compatibleBybitRows.size() == 1) {
            return compatibleBybitRows.getFirst();
        }
        return compatibleBybitRows.stream()
                .min(java.util.Comparator
                        .comparingInt((NormalizedTransaction row) -> walletEndpointMatches(onChain, row) ? 0 : 1)
                        .thenComparing(row -> row.getId() == null ? "" : row.getId()))
                .orElse(null);
    }

    private List<NormalizedTransaction> selectOnChainPartners(NormalizedTransaction bybit) {
        List<NormalizedTransaction> candidates = normalizedTransactionRepository
                .findAllByTxHashAndNetworkIdAndSource(
                        bybit.getTxHash(),
                        bybit.getNetworkId(),
                        NormalizedTransactionSource.ON_CHAIN
                ).stream()
                .filter(this::isFa001OnChainPartner)
                .filter(onChain -> isPairable(onChain, bybit))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() == 1) {
            return candidates;
        }
        List<NormalizedTransaction> endpointMatches = candidates.stream()
                .filter(onChain -> walletEndpointMatches(onChain, bybit))
                .toList();
        if (endpointMatches.size() == 1) {
            return endpointMatches;
        }
        List<NormalizedTransaction> tiebreakPool = endpointMatches.size() > 1 ? endpointMatches : candidates;
        if (principalFlows(bybit).isEmpty()) {
            return List.of();
        }
        BigDecimal bybitQty = principalFlows(bybit).getFirst().getQuantityDelta().abs();
        NormalizedTransaction best = null;
        BigDecimal bestDrift = null;
        int bestCount = 0;
        for (NormalizedTransaction onChain : tiebreakPool) {
            BigDecimal drift = quantityDrift(onChain, bybitQty);
            if (drift == null) {
                continue;
            }
            if (bestDrift == null || drift.compareTo(bestDrift) < 0) {
                best = onChain;
                bestDrift = drift;
                bestCount = 1;
            } else if (drift.compareTo(bestDrift) == 0) {
                bestCount++;
            }
        }
        if (bestCount == 1 && best != null) {
            return List.of(best);
        }
        return List.of();
    }

    private BigDecimal quantityDrift(NormalizedTransaction onChain, BigDecimal bybitQty) {
        if (onChain == null || bybitQty == null || principalFlows(onChain).isEmpty()) {
            return null;
        }
        BigDecimal onChainQty = principalFlows(onChain).getFirst().getQuantityDelta().abs();
        return onChainQty.subtract(bybitQty).abs();
    }

    /**
     * ADR-013 + Cycle/7 S3: after FA-001 pairing, the chain leg is an internal custody move to the
     * user's CEX account. Use the full sub-account ref ({@code BYBIT:<uid>:FUND}) so transfer
     * continuity matching keys on the receiving sub-account, not just the master uid.
     */
    private boolean rewriteOnChainLegAsInternalCexTransfer(
            NormalizedTransaction onChain,
            NormalizedTransaction bybit,
            Instant now
    ) {
        if (onChain == null || bybit == null) {
            return false;
        }
        String bybitSubAccountRef = resolveBybitSubAccountRef(bybit.getWalletAddress());
        if (bybitSubAccountRef == null) {
            return false;
        }
        boolean changed = false;
        if (onChain.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            onChain.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            changed = true;
        }
        if (!sameText(onChain.getCounterpartyAddress(), bybitSubAccountRef)) {
            onChain.setCounterpartyAddress(bybitSubAccountRef);
            changed = true;
        }
        if (onChain.getCounterpartyType() != CounterpartyType.CEX) {
            onChain.setCounterpartyType(CounterpartyType.CEX);
            changed = true;
        }
        if (onChain.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : onChain.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (!sameText(flow.getCounterpartyAddress(), bybitSubAccountRef)) {
                    flow.setCounterpartyAddress(bybitSubAccountRef);
                    changed = true;
                }
                if (flow.getCounterpartyType() != CounterpartyType.CEX) {
                    flow.setCounterpartyType(CounterpartyType.CEX);
                    changed = true;
                }
            }
        }
        if (changed) {
            onChain.setUpdatedAt(now);
        }
        return changed;
    }

    /**
     * Cycle/7 S3: return the full Bybit sub-account ref ({@code BYBIT:<uid>:<SUB>}) when the wallet
     * ref already carries one (which is the case for FUND/UTA/EARN). Falls back to the master
     * {@code BYBIT:<uid>} only when no sub-account suffix is present.
     */
    private String resolveBybitSubAccountRef(String walletRef) {
        return CorridorCorrelationKeyFactory.bybitSubAccountEndpoint(walletRef);
    }

    /**
     * Strip priced external-transfer semantics from the on-chain leg so replay uses continuity transfer
     * (FA-001 D2); Bybit shadow row already uses TRANSFER.
     */
    private boolean retagOnChainPrincipalFlowsForContinuity(NormalizedTransaction onChain, Instant now) {
        if (onChain.getFlows() == null || onChain.getFlows().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (NormalizedTransaction.Flow flow : onChain.getFlows()) {
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
        if (changed) {
            onChain.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean applyContinuityMetadata(
            NormalizedTransaction transaction,
            String correlationId,
            String matchedCounterparty,
            Instant now
    ) {
        boolean changed = false;
        if (!sameText(transaction.getCorrelationId(), correlationId)) {
            transaction.setCorrelationId(correlationId);
            changed = true;
        }
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
            changed = true;
        }
        if (!sameText(transaction.getMatchedCounterparty(), matchedCounterparty)) {
            transaction.setMatchedCounterparty(matchedCounterparty);
            changed = true;
        }
        if (transaction.getStatus() != NormalizedTransactionStatus.CONFIRMED) {
            transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
            changed = true;
        }
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            transaction.setExcludedFromAccounting(false);
            changed = true;
        }
        if (transaction.getAccountingExclusionReason() != null) {
            transaction.setAccountingExclusionReason(null);
            changed = true;
        }
        if (removeMissingReason(transaction, BRIDGE_MISSING_REASON)) {
            changed = true;
        }
        if (removeMissingReason(transaction, EXTERNAL_CUSTODY_UNTRACKED_VENUE)) {
            changed = true;
        }
        if (transaction.getConfirmedAt() == null) {
            transaction.setConfirmedAt(now);
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean isRepairableBybitStatus(NormalizedTransaction transaction) {
        // Cycle/5 N18: after N17 the basis-acquiring Bybit anchor (FH/Deposit / FH/Withdraw) is
        // assigned the BUY/SELL role at the canonical builder, which the PriceableFlowPolicy then
        // pins to PENDING_PRICE until pricing runs. The linking stage executes BEFORE pricing, so
        // every FH-anchor we want to link starts in PENDING_PRICE. The matcher must accept it
        // alongside CONFIRMED/NEEDS_REVIEW; otherwise continuity is never established and the
        // pricing job later crystallises a fresh market-priced acquisition (the exact double-step-up
        // we are trying to eliminate).
        return transaction.getStatus() == NormalizedTransactionStatus.CONFIRMED
                || transaction.getStatus() == NormalizedTransactionStatus.NEEDS_REVIEW
                || transaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE;
    }

    private boolean removeMissingReason(NormalizedTransaction transaction, String reason) {
        if (transaction.getMissingDataReasons() == null || transaction.getMissingDataReasons().isEmpty()) {
            return false;
        }
        return transaction.getMissingDataReasons().removeIf(reason::equals);
    }

    private boolean directionCompatible(
            NormalizedTransaction onChain,
            NormalizedTransaction bybit
    ) {
        if (onChain.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || onChain.getType() == NormalizedTransactionType.BRIDGE_OUT) {
            return bybit.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                    || (bybit.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                    && principalQuantitySign(bybit) > 0);
        }
        if (onChain.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || onChain.getType() == NormalizedTransactionType.BRIDGE_IN) {
            return bybit.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                    || (bybit.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                    && principalQuantitySign(bybit) < 0);
        }
        if (onChain.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                && bybit.getType() == NormalizedTransactionType.INTERNAL_TRANSFER) {
            int onChainSign = principalQuantitySign(onChain);
            int bybitSign = principalQuantitySign(bybit);
            return onChainSign != 0 && bybitSign != 0 && onChainSign != bybitSign;
        }
        return false;
    }

    private static int principalQuantitySign(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return 0;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
                return flow.getQuantityDelta().signum();
            }
        }
        return 0;
    }

    /**
     * Cycle/18 R9b: heuristic bridge classification can mis-tag wallet→CEX deposits as
     * {@code BRIDGE_OUT} when the deposit contract is also a known bridge router. FA-001 pairing
     * must still accept those legs when a same-txHash Bybit corridor exists.
     */
    private static boolean isFa001OnChainType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || type == NormalizedTransactionType.BRIDGE_IN
                || type == NormalizedTransactionType.BRIDGE_OUT;
    }

    private boolean isFa001OnChainPartner(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.ON_CHAIN
                || (transaction.getStatus() != NormalizedTransactionStatus.CONFIRMED
                && transaction.getStatus() != NormalizedTransactionStatus.PENDING_PRICE)
                || !hasOnChainWalletShape(transaction.getWalletAddress(), transaction.getNetworkId())
                || transaction.getNetworkId() == null
                || blank(transaction.getTxHash())
                || principalFlows(transaction).size() != 1) {
            return false;
        }
        if (isFa001OnChainType(transaction.getType())) {
            return true;
        }
        return transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                && Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && hasBybitCorridorCorrelation(transaction.getCorrelationId());
    }

    private static boolean isRepairableBybitExternalType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
    }

    private static boolean isFa001BybitDepositAnchor(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                && hasEvmMatchedCounterparty(transaction.getMatchedCounterparty());
    }

    private static boolean hasEvmMatchedCounterparty(String matchedCounterparty) {
        return matchedCounterparty != null
                && EVM_HEX_ADDRESS.matcher(matchedCounterparty.trim()).matches();
    }

    private static boolean hasBybitCorridorCorrelation(String correlationId) {
        return correlationId != null && correlationId.startsWith("BYBIT-CORRIDOR:");
    }

    private List<NormalizedTransaction.Flow> principalFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0)
                .toList();
    }

    private boolean quantitiesCompatible(BigDecimal left, BigDecimal right) {
        if (left == null || right == null || left.signum() <= 0 || right.signum() <= 0) {
            return false;
        }
        BigDecimal delta = left.subtract(right).abs();
        if (delta.compareTo(ABSOLUTE_QTY_TOLERANCE) <= 0) {
            return true;
        }
        BigDecimal denominator = left.max(right);
        if (denominator.signum() <= 0) {
            return false;
        }
        return delta.divide(denominator, MC).compareTo(RELATIVE_QTY_TOLERANCE) <= 0;
    }

    private List<NormalizedTransaction> deduplicateById(List<NormalizedTransaction> candidates) {
        Map<String, NormalizedTransaction> deduplicated = new LinkedHashMap<>();
        for (NormalizedTransaction candidate : candidates) {
            if (candidate == null || blank(candidate.getId())) {
                continue;
            }
            deduplicated.putIfAbsent(candidate.getId(), candidate);
        }
        return List.copyOf(deduplicated.values());
    }

    private boolean hasOnChainWalletShape(String value, NetworkId networkId) {
        if (blank(value)) {
            return false;
        }
        if (networkId == NetworkId.SOLANA) {
            // Solana base58 addresses are 32–44 chars; reject anything containing whitespace or '0x'.
            String trimmed = value.trim();
            return trimmed.length() >= 32
                    && trimmed.length() <= 64
                    && !trimmed.contains(" ")
                    && !trimmed.startsWith("0x")
                    && !trimmed.startsWith("BYBIT:");
        }
        if (networkId == NetworkId.TON) {
            return com.walletradar.domain.common.ton.TonAddressCanonicalizer.looksLikeTon(value);
        }
        return EVM_HEX_ADDRESS.matcher(value).matches();
    }

    private boolean hasBybitRef(String value) {
        return !blank(value) && BYBIT_REF.matcher(value).matches();
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String correlationId(NetworkId networkId, String txHash) {
        // RC-9 D1: single source of truth. Pure derivation via CorridorCorrelationKeyFactory so the
        // corridor id is bit-identical between full rebuild and incremental refresh (Solana stays
        // case-sensitive base58; EVM/TON are lower-cased).
        return CorridorCorrelationKeyFactory.corridorKey(networkId, txHash);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
