package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferPairer;
import com.walletradar.application.pricing.application.PriceableFlowPolicy;
import com.walletradar.application.session.application.AccountingUniverseService;
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
    private static final Pattern DZENGI_REF = Pattern.compile("^DZENGI:[^\\s]+$", Pattern.CASE_INSENSITIVE);
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
        List<NormalizedTransaction> dzengiBatch = loadDzengiCandidateBatch(batchSize);
        for (NormalizedTransaction dzengiCandidate : dzengiBatch) {
            if (repairFromDzengiSide(dzengiCandidate)) {
                changed++;
            }
        }
        if (changed > 0 || !batch.isEmpty() || !bybitBatch.isEmpty() || !dzengiBatch.isEmpty()) {
            log.info("BybitTransferContinuityRepair: onchainCandidates={} bybitCandidates={} dzengiCandidates={} paired={}",
                    batch.size(), bybitBatch.size(), dzengiBatch.size(), changed);
        }
        changed += reclassifyPairedCorridorExternals(batchSize);
        return changed;
    }

    boolean repairFromDzengiSide(NormalizedTransaction dzengiCandidate) {
        if (dzengiCandidate == null
                || dzengiCandidate.getSource() != NormalizedTransactionSource.DZENGI
                || blank(dzengiCandidate.getTxHash())
                || !isRepairableCexExternalType(dzengiCandidate.getType())) {
            return false;
        }
        List<NormalizedTransaction> onChainCandidates = selectOnChainPartnersForCex(dzengiCandidate);
        if (onChainCandidates.size() != 1) {
            return false;
        }
        return repair(onChainCandidates.getFirst());
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

    private List<NormalizedTransaction> loadDzengiCandidateBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.DZENGI),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                Criteria.where("txHash").exists(true).ne(""),
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
        List<NormalizedTransaction> compatibleCexRows = new ArrayList<>();
        compatibleCexRows.addAll(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                        onChainCandidate.getTxHash(),
                        onChainCandidate.getNetworkId(),
                        NormalizedTransactionSource.BYBIT
                ).stream()
                .filter(cexRow -> isPairable(onChainCandidate, cexRow))
                .toList());
        compatibleCexRows.addAll(normalizedTransactionRepository.findAllByTxHashAndSource(
                        onChainCandidate.getTxHash(),
                        NormalizedTransactionSource.DZENGI
                ).stream()
                .filter(cexRow -> isPairable(onChainCandidate, cexRow))
                .toList());
        NormalizedTransaction cexRow = selectCanonicalCexLeg(onChainCandidate, compatibleCexRows);
        if (cexRow == null) {
            return false;
        }
        String correlationId = correlationId(onChainCandidate.getNetworkId(), onChainCandidate.getTxHash());
        Instant now = Instant.now();

        boolean leftChanged = applyContinuityMetadata(
                onChainCandidate,
                correlationId,
                cexRow.getWalletAddress(),
                now
        );
        boolean rightChanged = applyContinuityMetadata(
                cexRow,
                correlationId,
                onChainCandidate.getWalletAddress(),
                now
        );
        boolean onChainFlowsRetagged = retagOnChainPrincipalFlowsForContinuity(onChainCandidate, now);
        boolean onChainRewritten = rewriteOnChainLegAsInternalCexTransfer(onChainCandidate, cexRow, now);
        boolean cexFlowsRetagged = retagCexPrincipalFlowsForContinuity(cexRow, now);
        boolean cexRewritten = rewriteCexLegAsInternalTransfer(cexRow, now);
        if (!leftChanged && !rightChanged && !onChainFlowsRetagged && !cexFlowsRetagged && !onChainRewritten && !cexRewritten) {
            return false;
        }

        normalizedTransactionRepository.saveAll(deduplicateById(List.of(onChainCandidate, cexRow)));
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
                Criteria.where("correlationId").regex("^" + CorrelationContract.BYBIT_CORRIDOR_PREFIX),
                Criteria.where("matchedCounterparty").exists(true).ne(""),
                Criteria.where("matchedCounterparty").not().regex("^" + CorrelationContract.VENUE_BYBIT + ":", "i")
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

    private boolean rewriteCexLegAsInternalTransfer(NormalizedTransaction cex, Instant now) {
        if (cex == null
                || !isVenueCexSource(cex.getSource())
                || cex.getType() == NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        if (cex.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && cex.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        String matchedCounterparty = cex.getMatchedCounterparty();
        if (blank(matchedCounterparty) || hasCexRef(matchedCounterparty, cex.getSource())) {
            return false;
        }
        cex.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        cex.setUpdatedAt(now);
        return true;
    }

    private boolean rewriteBybitLegAsInternalTransfer(NormalizedTransaction bybit, Instant now) {
        return rewriteCexLegAsInternalTransfer(bybit, now);
    }

    private boolean retagCexPrincipalFlowsForContinuity(NormalizedTransaction cex, Instant now) {
        if (cex.getFlows() == null || cex.getFlows().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (NormalizedTransaction.Flow flow : cex.getFlows()) {
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
        if (changed && shouldRequeueContinuityInboundPricing(cex)) {
            cex.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            cex.setConfirmedAt(null);
        }
        if (changed) {
            cex.setUpdatedAt(now);
        }
        return changed;
    }

    /**
     * Cycle/5 N18: strip priced semantics from the Bybit FH-anchor principal flow once the cross-wallet
     * continuity pair is established. Mirrors {@link #retagOnChainPrincipalFlowsForContinuity}.
     */
    private boolean retagBybitPrincipalFlowsForContinuity(NormalizedTransaction bybit, Instant now) {
        return retagCexPrincipalFlowsForContinuity(bybit, now);
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
                Criteria.where("matchedCounterparty").not().regex("^" + CorrelationContract.VENUE_BYBIT + ":")
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

    private boolean isRepairableExcludedCexLeg(NormalizedTransaction cex) {
        if (cex == null || !Boolean.TRUE.equals(cex.getExcludedFromAccounting())) {
            return true;
        }
        String reason = cex.getAccountingExclusionReason();
        if (EXTERNAL_CUSTODY_UNTRACKED_VENUE.equals(reason)) {
            return true;
        }
        if (hasBybitCorridorCorrelation(cex.getCorrelationId())) {
            return true;
        }
        if (cex.getSource() != NormalizedTransactionSource.BYBIT) {
            return false;
        }
        return BybitInternalTransferPairer.SAME_SIGN_MIRROR_REASON.equals(reason)
                && !blank(cex.getTxHash());
    }

    private boolean isRepairableExcludedBybitLeg(NormalizedTransaction bybit) {
        return isRepairableExcludedCexLeg(bybit);
    }

    private boolean isPairable(
            NormalizedTransaction onChain,
            NormalizedTransaction cex
    ) {
        if (cex == null
                || !isVenueCexSource(cex.getSource())
                || !isRepairableCexStatus(cex)
                || !hasCexRef(cex.getWalletAddress(), cex.getSource())
                || !networkCompatible(onChain, cex)
                || !NetworkAddressFormat.txHashesEqual(onChain.getNetworkId(), cex.getTxHash(), onChain.getTxHash())
                || !directionCompatible(onChain, cex)
                || !accountingUniverseService.shareUniverseMembers(onChain.getWalletAddress(), cex.getWalletAddress())
                || principalFlows(cex).size() != 1) {
            return false;
        }
        if (Boolean.TRUE.equals(cex.getExcludedFromAccounting())
                && !isRepairableExcludedCexLeg(cex)) {
            return false;
        }
        if (!blank(cex.getMatchedCounterparty())
                && !sameText(cex.getMatchedCounterparty(), onChain.getWalletAddress())) {
            return false;
        }

        NormalizedTransaction.Flow onChainPrincipal = principalFlows(onChain).getFirst();
        NormalizedTransaction.Flow cexPrincipal = principalFlows(cex).getFirst();
        String onChainFamily = AccountingAssetFamilySupport.continuityIdentity(onChainPrincipal);
        String cexFamily = AccountingAssetFamilySupport.continuityIdentity(cexPrincipal);
        if (onChainFamily == null || !Objects.equals(onChainFamily, cexFamily)) {
            return false;
        }
        if (walletEndpointMatches(onChain, cex)) {
            return true;
        }
        // Dzengi records the gross withdrawal amount; on-chain receipt is net after the transfer fee
        // (e.g. Dzengi shows 10 USDT sent, chain shows 8.6 USDT received). The txHash is already
        // verified equal above, so when the asset family matches we trust the txHash pairing and
        // skip the tight quantity check that would otherwise reject the ~14% gap.
        if (cex.getSource() == NormalizedTransactionSource.DZENGI) {
            return true;
        }
        return quantitiesCompatible(
                onChainPrincipal.getQuantityDelta().abs(),
                cexPrincipal.getQuantityDelta().abs()
        );
    }

    private static boolean networkCompatible(NormalizedTransaction onChain, NormalizedTransaction cex) {
        if (cex.getSource() == NormalizedTransactionSource.DZENGI) {
            return cex.getNetworkId() == null || cex.getNetworkId() == onChain.getNetworkId();
        }
        return cex.getNetworkId() == onChain.getNetworkId();
    }

    /**
     * FA-001 R10: when Bybit raw extract already stamped the on-chain wallet endpoint on
     * {@code counterpartyAddress}, pairing must not fail on withdrawal-fee quantity drift.
     */
    private boolean walletEndpointMatches(NormalizedTransaction onChain, NormalizedTransaction cex) {
        if (onChain == null || cex == null || blank(cex.getCounterpartyAddress())) {
            return false;
        }
        String counterparty = cex.getCounterpartyAddress().trim();
        if (WalletRef.parse(counterparty).domain() == WalletDomainKind.CEX) {
            return false;
        }
        if (!hasOnChainWalletShape(counterparty, onChain.getNetworkId())) {
            return false;
        }
        return sameText(counterparty, onChain.getWalletAddress());
    }

    private NormalizedTransaction selectCanonicalCexLeg(
            NormalizedTransaction onChain,
            List<NormalizedTransaction> compatibleCexRows
    ) {
        if (compatibleCexRows.isEmpty()) {
            return null;
        }
        if (compatibleCexRows.size() == 1) {
            return compatibleCexRows.getFirst();
        }
        return compatibleCexRows.stream()
                .min(java.util.Comparator
                        .comparingInt((NormalizedTransaction row) -> walletEndpointMatches(onChain, row) ? 0 : 1)
                        .thenComparing(row -> row.getId() == null ? "" : row.getId()))
                .orElse(null);
    }

    private NormalizedTransaction selectCanonicalBybitLeg(
            NormalizedTransaction onChain,
            List<NormalizedTransaction> compatibleBybitRows
    ) {
        return selectCanonicalCexLeg(onChain, compatibleBybitRows);
    }

    private List<NormalizedTransaction> selectOnChainPartnersForCex(NormalizedTransaction cex) {
        List<NormalizedTransaction> candidates;
        if (cex.getNetworkId() != null) {
            candidates = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                    cex.getTxHash(),
                    cex.getNetworkId(),
                    NormalizedTransactionSource.ON_CHAIN
            );
        } else {
            candidates = normalizedTransactionRepository.findAllByTxHashAndSource(
                    cex.getTxHash(),
                    NormalizedTransactionSource.ON_CHAIN
            );
        }
        return selectOnChainPartnersFromCandidates(cex, candidates);
    }

    private List<NormalizedTransaction> selectOnChainPartners(NormalizedTransaction bybit) {
        List<NormalizedTransaction> candidates = normalizedTransactionRepository
                .findAllByTxHashAndNetworkIdAndSource(
                        bybit.getTxHash(),
                        bybit.getNetworkId(),
                        NormalizedTransactionSource.ON_CHAIN
                );
        return selectOnChainPartnersFromCandidates(bybit, candidates);
    }

    private List<NormalizedTransaction> selectOnChainPartnersFromCandidates(
            NormalizedTransaction cex,
            List<NormalizedTransaction> candidates
    ) {
        List<NormalizedTransaction> filtered = candidates.stream()
                .filter(this::isFa001OnChainPartner)
                .filter(onChain -> isPairable(onChain, cex))
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }
        if (filtered.size() == 1) {
            return filtered;
        }
        List<NormalizedTransaction> endpointMatches = filtered.stream()
                .filter(onChain -> walletEndpointMatches(onChain, cex))
                .toList();
        if (endpointMatches.size() == 1) {
            return endpointMatches;
        }
        List<NormalizedTransaction> tiebreakPool = endpointMatches.size() > 1 ? endpointMatches : filtered;
        if (principalFlows(cex).isEmpty()) {
            return List.of();
        }
        BigDecimal cexQty = principalFlows(cex).getFirst().getQuantityDelta().abs();
        NormalizedTransaction best = null;
        BigDecimal bestDrift = null;
        int bestCount = 0;
        for (NormalizedTransaction onChain : tiebreakPool) {
            BigDecimal drift = quantityDrift(onChain, cexQty);
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
            NormalizedTransaction cex,
            Instant now
    ) {
        if (onChain == null || cex == null) {
            return false;
        }
        String cexSubAccountRef = resolveCexSubAccountEndpoint(cex);
        if (cexSubAccountRef == null) {
            return false;
        }
        boolean changed = false;
        if (onChain.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            onChain.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            changed = true;
        }
        if (!sameText(onChain.getCounterpartyAddress(), cexSubAccountRef)) {
            onChain.setCounterpartyAddress(cexSubAccountRef);
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
                if (!sameText(flow.getCounterpartyAddress(), cexSubAccountRef)) {
                    flow.setCounterpartyAddress(cexSubAccountRef);
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

    private String resolveCexSubAccountEndpoint(NormalizedTransaction cex) {
        if (cex == null || cex.getSource() == null) {
            return null;
        }
        return switch (cex.getSource()) {
            case BYBIT -> CorridorCorrelationKeyFactory.bybitSubAccountEndpoint(cex.getWalletAddress());
            case DZENGI -> resolveDzengiEndpoint(cex.getWalletAddress());
            default -> null;
        };
    }

    private static String resolveDzengiEndpoint(String walletRef) {
        if (walletRef == null || walletRef.isBlank()) {
            return null;
        }
        WalletRef ref = WalletRef.parse(walletRef.trim());
        if (ref.domain() != WalletDomainKind.CEX || !"dzengi".equals(ref.venueId())) {
            return null;
        }
        return ref.canonicalRef();
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

    private boolean isRepairableCexStatus(NormalizedTransaction transaction) {
        return transaction.getStatus() == NormalizedTransactionStatus.CONFIRMED
                || transaction.getStatus() == NormalizedTransactionStatus.NEEDS_REVIEW
                || transaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE;
    }

    private boolean isRepairableBybitStatus(NormalizedTransaction transaction) {
        return isRepairableCexStatus(transaction);
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

    private static boolean isRepairableCexExternalType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
    }

    private static boolean isRepairableBybitExternalType(NormalizedTransactionType type) {
        return isRepairableCexExternalType(type);
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
        return correlationId != null && correlationId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX);
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
                    && WalletRef.parse(trimmed).domain() != WalletDomainKind.CEX;
        }
        if (networkId == NetworkId.TON) {
            return com.walletradar.domain.common.ton.TonAddressCanonicalizer.looksLikeTon(value);
        }
        return EVM_HEX_ADDRESS.matcher(value).matches();
    }

    private static boolean isVenueCexSource(NormalizedTransactionSource source) {
        return source == NormalizedTransactionSource.BYBIT || source == NormalizedTransactionSource.DZENGI;
    }

    private boolean hasCexRef(String value, NormalizedTransactionSource source) {
        if (blank(value) || source == null) {
            return false;
        }
        return switch (source) {
            case BYBIT -> BYBIT_REF.matcher(value).matches();
            case DZENGI -> DZENGI_REF.matcher(value).matches();
            default -> false;
        };
    }

    private boolean hasBybitRef(String value) {
        return hasCexRef(value, NormalizedTransactionSource.BYBIT);
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
