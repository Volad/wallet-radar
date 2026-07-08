package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.canonical.correlation.BybitCarryContinuitySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.linking.pipeline.clarification.CorridorCorrelationKeyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Cross-UID universal transfer pairing extracted from {@link BybitInternalTransferPairer}. */
@Service
@Slf4j
@RequiredArgsConstructor
class BybitCrossUidUniversalTransferPairer {

    private static final Pattern UNI_TRANS_UUID_PATTERN =
            Pattern.compile("uni_trans_([0-9a-fA-F-]{36})");

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int pairCrossUidUniversalTransfers() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("excludedFromAccounting").ne(true),
                Criteria.where("_id").regex("uni_trans_"),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT)
                )
        ));
        // RC-9 D1: order-independent grouping. Sort the candidate set by stable _id before building
        // the per-transferId groups so the pairing outcome does not depend on Mongo iteration order
        // between a full rebuild and an incremental refresh.
        List<NormalizedTransaction> candidates = new ArrayList<>(
                mongoOperations.find(query, NormalizedTransaction.class));
        if (candidates.isEmpty()) {
            return 0;
        }
        candidates.sort(Comparator.comparing(NormalizedTransaction::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Integer> corrIdCount = new HashMap<>();
        for (NormalizedTransaction tx : candidates) {
            String corr = tx.getCorrelationId();
            if (corr != null && !corr.isBlank()) {
                corrIdCount.merge(corr, 1, Integer::sum);
            }
        }

        Map<String, List<NormalizedTransaction>> groupedByTransferId = new LinkedHashMap<>();
        for (NormalizedTransaction tx : candidates) {
            String corr = tx.getCorrelationId();
            // RC-9 D1: corridor anchors are owned by the deterministic on-chain↔CEX corridor
            // projection; never re-key them via cross-UID pairing.
            if (corr != null && corr.startsWith(CorridorCorrelationKeyFactory.CORRIDOR_PREFIX)) {
                continue;
            }
            boolean alreadyPaired = corr != null && !corr.isBlank()
                    && corrIdCount.getOrDefault(corr, 0) >= 2;
            if (alreadyPaired) {
                continue;
            }
            String uuid = extractTransferUuid(tx.getId());
            if (uuid != null) {
                groupedByTransferId.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(tx);
            }
        }

        int rewrites = 0;
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (List<NormalizedTransaction> group : groupedByTransferId.values()) {
            if (group.size() != 2) {
                if (group.size() >= 3) {
                    rewrites += pairMultiLegCrossUidGroup(group, now, dirty);
                }
                continue;
            }
            NormalizedTransaction a = group.get(0);
            NormalizedTransaction b = group.get(1);
            String uidA = BybitInternalTransferPairingPrimitives.extractBybitUid(a.getWalletAddress());
            String uidB = BybitInternalTransferPairingPrimitives.extractBybitUid(b.getWalletAddress());
            if (uidA == null || uidB == null || uidA.equals(uidB)) {
                continue;
            }
            int signA = BybitInternalTransferPairingPrimitives.principalQuantitySign(a);
            int signB = BybitInternalTransferPairingPrimitives.principalQuantitySign(b);
            if (signA == 0 || signB == 0 || signA == signB) {
                continue;
            }
            applyCrossUidPairCorrelation(a, b, now);
            dirty.add(a);
            dirty.add(b);
            rewrites += 2;
        }

        // Second pass: orphaned inbound where the outbound partner is excluded from accounting.
        // The outbound was excluded to avoid double-counting with FUNDING_HISTORY records, but the
        // inbound still needs continuityCandidate=true and the FUNDING_HISTORY outbound on the
        // source UID needs to be linked so carry propagates correctly.
        //
        // Note: pairCrossUidUniversalTransfers() is called both during per-wallet normalization
        // and at the end of linking. On the first call the FUNDING_HISTORY records for the source
        // UID may not yet be in the DB. The loner gets its corrId, but the FUNDING_HISTORY outbound
        // is missed. On subsequent calls the loner already has a bybit-cross-uid-v1: corrId. We
        // therefore must NOT skip those loners — instead reuse the existing corrId to link any
        // still-unlinked FUNDING_HISTORY outbound on the source UID.
        //
        // Pass 2a: collect all (loner, excludedPartner) pairs so we know source UIDs up front.
        record SecondPassEntry(String uuid, NormalizedTransaction loner, NormalizedTransaction excludedPartner, boolean lonerAlreadyLinked) {}
        List<SecondPassEntry> secondPassEntries = new ArrayList<>();
        for (Map.Entry<String, List<NormalizedTransaction>> entry : groupedByTransferId.entrySet()) {
            List<NormalizedTransaction> group = entry.getValue();
            if (group.size() != 1) {
                continue;
            }
            NormalizedTransaction loner = group.getFirst();
            if (BybitInternalTransferPairingPrimitives.principalQuantitySign(loner) <= 0) {
                continue; // only process inbound (positive qty) orphans
            }
            String existingCorrId = loner.getCorrelationId();
            boolean lonerAlreadyLinked = existingCorrId != null && !existingCorrId.isBlank();
            if (lonerAlreadyLinked && !existingCorrId.startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)) {
                // Paired by a different mechanism (first pass or another service) — skip entirely.
                continue;
            }
            NormalizedTransaction excludedPartner = findExcludedCrossUidPartner(entry.getKey(), loner);
            if (excludedPartner == null) {
                continue;
            }
            secondPassEntries.add(new SecondPassEntry(entry.getKey(), loner, excludedPartner, lonerAlreadyLinked));
        }

        // Pass 2b: bulk pre-load unpaired FUNDING_HISTORY records for all source UIDs.
        Set<String> sourceUids = secondPassEntries.stream()
                .map(e -> BybitInternalTransferPairingPrimitives.extractBybitUid(e.excludedPartner().getWalletAddress()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, List<NormalizedTransaction>> fundingByUid = loadFundingHistoryCandidates(sourceUids);

        // Pass 2c: link each inbound and, if found, the corresponding FUNDING_HISTORY outbound.
        for (SecondPassEntry e : secondPassEntries) {
            NormalizedTransaction loner = e.loner();
            NormalizedTransaction excludedPartner = e.excludedPartner();
            // Reuse an existing cross-UID corrId so the loner and FUNDING_HISTORY share the same key.
            String corrId = e.lonerAlreadyLinked()
                    ? loner.getCorrelationId()
                    : BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX + e.uuid();

            if (!e.lonerAlreadyLinked()) {
                loner.setCorrelationId(corrId);
                loner.setContinuityCandidate(true);
                loner.setMatchedCounterparty(excludedPartner.getWalletAddress());
                loner.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
                BybitInternalTransferPairingPrimitives.demoteBuySellToTransfer(loner);
                loner.setUpdatedAt(now);
                dirty.add(loner);
                rewrites++;
            }

            NormalizedTransaction fundingOutbound =
                    findFundingHistoryOutbound(loner, excludedPartner, fundingByUid);
            if (fundingOutbound != null
                    && (fundingOutbound.getCorrelationId() == null
                        || fundingOutbound.getCorrelationId().isBlank())) {
                // continuityCandidate=true is required on the outbound so that
                // ReplayPendingTransferKeyFactory routes it to the "corr-family:" pending-transfer
                // queue, which is the same queue the inbound CARRY_IN reads from. Without it the
                // outbound would use the "corr:" key and the inbound would find an empty pool.
                // excludedFromAccounting is left untouched (it only gates balance queries, not
                // replay). The isBybitSelfTransfer guard in ReplayDispatcher already returns false
                // for bybit-cross-uid-v1: corrIds so the CARRY_OUT is emitted correctly.
                fundingOutbound.setCorrelationId(corrId);
                fundingOutbound.setContinuityCandidate(true);
                fundingOutbound.setMatchedCounterparty(loner.getWalletAddress());
                fundingOutbound.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
                BybitInternalTransferPairingPrimitives.demoteBuySellToTransfer(fundingOutbound);
                fundingOutbound.setUpdatedAt(now);
                dirty.add(fundingOutbound);
                rewrites++;
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_CROSS_UID_UNIVERSAL_TRANSFER_PAIRER candidates={} paired={}",
                    candidates.size(),
                    rewrites
            );
        }
        return rewrites;
    }

    /**
     * Bulk-loads unpaired non-{@code uni_trans_} records for the given Bybit UIDs so that
     * {@link #findFundingHistoryOutbound} can match without a per-orphan Mongo query.
     */
    private Map<String, List<NormalizedTransaction>> loadFundingHistoryCandidates(Set<String> uids) {
        if (uids.isEmpty()) {
            return Collections.emptyMap();
        }
        Criteria[] uidCriteria = uids.stream()
                .map(uid -> Criteria.where("walletAddress").regex("^BYBIT:" + Pattern.quote(uid)))
                .toArray(Criteria[]::new);
        Criteria walletCriteria = uidCriteria.length == 1
                ? uidCriteria[0]
                : new Criteria().orOperator(uidCriteria);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                walletCriteria,
                Criteria.where("_id").not().regex("uni_trans_"),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is("")
                )
        ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .collect(Collectors.groupingBy(tx -> {
                    String uid = BybitInternalTransferPairingPrimitives.extractBybitUid(tx.getWalletAddress());
                    return uid != null ? uid : "";
                }));
    }

    /**
     * Finds the FUNDING_HISTORY outbound on the source UID that corresponds to the given inbound.
     * Matches on: same source UID as {@code excludedPartner}, same principal asset symbol,
     * quantity within 5%, and timestamp within ±60 s.
     */
    private NormalizedTransaction findFundingHistoryOutbound(
            NormalizedTransaction inbound,
            NormalizedTransaction excludedPartner,
            Map<String, List<NormalizedTransaction>> fundingByUid
    ) {
        String sourceUid = BybitInternalTransferPairingPrimitives.extractBybitUid(excludedPartner.getWalletAddress());
        if (sourceUid == null) {
            return null;
        }
        Instant ts = inbound.getBlockTimestamp();
        BigDecimal inboundQty = BybitInternalTransferPairingPrimitives.principalQuantity(inbound);
        String assetSymbol = principalAssetSymbol(inbound);
        if (ts == null || inboundQty == null || inboundQty.signum() <= 0 || assetSymbol == null) {
            return null;
        }
        List<NormalizedTransaction> candidates =
                fundingByUid.getOrDefault(sourceUid, Collections.emptyList());
        return candidates.stream()
                // RC-9 D1: stable selection so the linked FUNDING_HISTORY outbound is a pure
                // function of the candidate set, not Mongo iteration order.
                .sorted(Comparator.comparing(NormalizedTransaction::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .filter(tx -> BybitInternalTransferPairingPrimitives.principalQuantitySign(tx) < 0)
                .filter(tx -> assetSymbol.equals(principalAssetSymbol(tx)))
                .filter(tx -> {
                    BigDecimal qty = BybitInternalTransferPairingPrimitives.principalQuantity(tx);
                    return qty != null && inboundQty.subtract(qty.abs()).abs()
                            .compareTo(inboundQty.multiply(new BigDecimal("0.05"))) <= 0;
                })
                .filter(tx -> {
                    Instant txTs = tx.getBlockTimestamp();
                    return txTs != null
                            && !txTs.isBefore(ts.minusSeconds(60))
                            && !txTs.isAfter(ts.plusSeconds(60));
                })
                .findFirst()
                .orElse(null);
    }

    private static String principalAssetSymbol(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return null;
        }
        return tx.getFlows().stream()
                .filter(f -> f.getQuantityDelta() != null && f.getQuantityDelta().signum() != 0)
                .max(Comparator.comparing(f -> f.getQuantityDelta().abs()))
                .map(NormalizedTransaction.Flow::getAssetSymbol)
                .orElse(null);
    }

    private NormalizedTransaction findExcludedCrossUidPartner(String uuid, NormalizedTransaction loner) {
        if (uuid == null || loner == null) {
            return null;
        }
        String lonerUid = BybitInternalTransferPairingPrimitives.extractBybitUid(loner.getWalletAddress());
        if (lonerUid == null) {
            return null;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("excludedFromAccounting").is(true),
                Criteria.where("_id").regex("uni_trans_" + Pattern.quote(uuid))
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        return candidates.stream()
                .filter(tx -> {
                    String uid = BybitInternalTransferPairingPrimitives.extractBybitUid(tx.getWalletAddress());
                    return uid != null && !uid.equals(lonerUid);
                })
                .filter(tx -> BybitInternalTransferPairingPrimitives.principalQuantitySign(tx) < 0)
                .findFirst()
                .orElse(null);
    }

    private static String extractTransferUuid(String id) {
        if (id == null) {
            return null;
        }
        Matcher matcher = UNI_TRANS_UUID_PATTERN.matcher(id);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void applyCrossUidPairCorrelation(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String transferUuid = extractTransferUuid(left.getId());
        if (transferUuid == null) {
            transferUuid = extractTransferUuid(right.getId());
        }
        String corrId = BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX + (transferUuid != null ? transferUuid : "unknown");

        left.setCorrelationId(corrId);
        right.setCorrelationId(corrId);
        left.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        right.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        left.setContinuityCandidate(true);
        right.setContinuityCandidate(true);
        left.setMatchedCounterparty(right.getWalletAddress());
        right.setMatchedCounterparty(left.getWalletAddress());
        BybitInternalTransferPairingPrimitives.demoteBuySellToTransfer(left);
        BybitInternalTransferPairingPrimitives.demoteBuySellToTransfer(right);
        left.setUpdatedAt(now);
        right.setUpdatedAt(now);
    }

    /**
     * Pairs sub↔sub (or sub↔master) universal transfers where Bybit emits three or more legs for
     * one {@code uni_trans_<UUID>} (sender sub, receiver sub, optional master routing echo).
     */
    private int pairMultiLegCrossUidGroup(
            List<NormalizedTransaction> group,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        List<NormalizedTransaction> positives = group.stream()
                .filter(tx -> !Boolean.TRUE.equals(tx.getExcludedFromAccounting()))
                .filter(tx -> BybitInternalTransferPairingPrimitives.principalQuantitySign(tx) > 0)
                .toList();
        List<NormalizedTransaction> negatives = group.stream()
                .filter(tx -> !Boolean.TRUE.equals(tx.getExcludedFromAccounting()))
                .filter(tx -> BybitInternalTransferPairingPrimitives.principalQuantitySign(tx) < 0)
                .sorted(Comparator.comparing(
                        (NormalizedTransaction tx) -> BybitInternalTransferPairingPrimitives.extractBybitUid(tx.getWalletAddress()),
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
        if (positives.size() != 1 || negatives.isEmpty()) {
            return 0;
        }
        NormalizedTransaction receiver = positives.getFirst();
        String receiverUid = BybitInternalTransferPairingPrimitives.extractBybitUid(receiver.getWalletAddress());
        BigDecimal receiverQty = principalQuantityAbs(receiver);
        if (receiverUid == null || receiverQty == null) {
            return 0;
        }
        NormalizedTransaction sender = negatives.stream()
                .filter(tx -> {
                    String uid = BybitInternalTransferPairingPrimitives.extractBybitUid(tx.getWalletAddress());
                    return uid != null && !uid.equals(receiverUid);
                })
                .filter(tx -> receiverQty.compareTo(principalQuantityAbs(tx)) == 0)
                .findFirst()
                .orElse(null);
        if (sender == null) {
            return 0;
        }
        String existingCorr = sender.getCorrelationId();
        if (existingCorr != null
                && !existingCorr.isBlank()
                && !existingCorr.startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)) {
            return 0;
        }
        if (existingCorr != null && existingCorr.startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)) {
            return 0;
        }
        applyCrossUidPairCorrelation(sender, receiver, now);
        dirty.add(sender);
        dirty.add(receiver);
        return 2;
    }

    private static BigDecimal principalQuantityAbs(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return null;
        }
        BigDecimal qty = tx.getFlows().getFirst().getQuantityDelta();
        return qty == null ? null : qty.abs();
    }

    /**
     * Cycle/15 R3: pairs {@code bybit-econ-v1} legs demoted to {@code EXTERNAL_TRANSFER_*} after
     * orphan fallback — same broad fingerprint as {@link #pairBroadEconomicFingerprint()} but
     * re-promotes both legs to {@code INTERNAL_TRANSFER} with {@link #REKEYED_CORRELATION_PREFIX}.
     */
}
