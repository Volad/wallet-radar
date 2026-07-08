package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cycle/6 A3: Reclassifies Bybit-sourced {@code INTERNAL_TRANSFER} rows whose flow counterparty is
 * neither a Bybit sub-account nor a member of the active accounting universe.
 *
 * <p>Such rows arrive when (a) a counterparty TON / EVM address was not yet added to the universe
 * at extraction time, or (b) legacy normalization mis-typed an external corridor as an internal
 * transfer. They must be treated as external (deposit/withdraw) for the conservation gate and for
 * AVCO basis acquisition; treating them as INTERNAL_TRANSFER leaves both legs orphan and silently
 * destroys covered basis on the receiving side.</p>
 *
 * <p>Conversion rules:
 * <ul>
 *     <li>Principal flow sign &gt; 0 → {@code EXTERNAL_TRANSFER_IN} with role={@code BUY}.</li>
 *     <li>Principal flow sign &lt; 0 → {@code EXTERNAL_TRANSFER_OUT} with role={@code SELL}.</li>
 *     <li>{@code continuityCandidate=false}, {@code correlationId=null}, {@code matchedCounterparty=null}.</li>
 * </ul>
 * The original counterparty address is preserved so downstream FA-001 promotion can later re-link
 * it once the user adds the missing wallet to the universe.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitInternalTransferExternalCpReclassifier {

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;

    public int reclassify(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("counterpartyAddress").exists(true).ne("")
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (!isExternalCounterparty(accountingUniverseId, tx)) {
                continue;
            }
            if (reclassifyTx(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_INTERNAL_TRANSFER_EXT_CP_RECLASSIFIER candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        // Cycle/7 S4: in addition to externally-keyed INTERNAL_TRANSFERs, reclassify orphan
        // UNIVERSAL_TRANSFER (`uni_trans_*`) singletons that the stream-authority collapser could
        // not pair to a sibling. Bybit's universal transfers can land on a sub-account from an
        // unrelated master/sub-account that is not part of the user's accounting universe — for
        // those rows the only honest accounting representation is an external deposit/withdrawal.
        int universalReclassified = reclassifyOrphanUniversalTransfers(now);
        int sameUidReclassified = reclassifySameUidExternalToInternal(now);
        return dirty.size() + universalReclassified + sameUidReclassified;
    }

    /**
     * Cycle/18 R9: same-uid Bybit sub-account transfers (FUND↔UTA, FUND↔EARN, etc.) must be
     * {@code INTERNAL_TRANSFER}, not {@code EXTERNAL_TRANSFER_IN/OUT} with BUY/SELL semantics.
     */
    public int reclassifySameUidExternalToInternal(Instant now) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                Criteria.where("counterpartyAddress").regex("^BYBIT:")
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant timestamp = now == null ? Instant.now() : now;
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (!isSameUidSubAccountCounterparty(tx)) {
                continue;
            }
            if (tx.getMissingDataReasons() != null
                    && tx.getMissingDataReasons().contains("BOT_TRANSFER")) {
                continue;
            }
            if (demoteExternalToInternalTransfer(tx, timestamp)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_SAME_UID_EXT_TO_INTERNAL candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    private boolean isSameUidSubAccountCounterparty(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null || tx.getCounterpartyAddress() == null) {
            return false;
        }
        String walletUid = extractUid(tx.getWalletAddress());
        String counterpartyUid = extractUid(tx.getCounterpartyAddress());
        return walletUid != null && walletUid.equals(counterpartyUid);
    }

    private boolean demoteExternalToInternalTransfer(NormalizedTransaction tx, Instant now) {
        if (tx.getType() == NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        boolean changed = false;
        if (tx.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            changed = true;
        }
        if (tx.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
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
        }
        String correlationId = tx.getCorrelationId();
        // Corridor corrIds (BYBIT-CORRIDOR:) and earn-principal corrIds (bybit-earn-principal-v1:)
        // are final pairing keys (ADR-029 D1): never null them or this same-uid demotion would turn a
        // paired earn-principal FUND leg back into a blank-corr orphan, which BybitOnChainEarnOrphanRepairService
        // would re-link on the next pass — preventing the linking convergence loop from reaching a fixed point.
        if (correlationId == null
                || (!correlationId.startsWith("BYBIT-CORRIDOR:")
                        && !correlationId.startsWith(
                                BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX))) {
            if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
                tx.setContinuityCandidate(false);
                changed = true;
            }
            if (tx.getCorrelationId() != null) {
                tx.setCorrelationId(null);
                changed = true;
            }
        }
        if (tx.getStatus() == NormalizedTransactionStatus.PENDING_PRICE) {
            tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
            tx.setConfirmedAt(now);
            changed = true;
        }
        if (changed) {
            tx.setUpdatedAt(now);
        }
        return changed;
    }

    private int reclassifyOrphanUniversalTransfers(Instant now) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true),
                Criteria.where("_id").regex(":UNIVERSAL_TRANSFER:")
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }

        // Build a sibling index over ALL active BYBIT INTERNAL_TRANSFER docs so we can decide which
        // UNIVERSAL_TRANSFER rows lack a partner. A partner is any opposite-sign row in the same
        // uid+family+|qty| bucket (rounded to 10dp) — exactly the signature used by the collapser.
        Query siblingQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> universe = mongoOperations.find(siblingQuery, NormalizedTransaction.class);
        Map<String, Integer> oppositeCountByKey = indexBroadSignatures(universe);

        Instant timestamp = now;
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            // Defensive: only act on documents whose id contains the UNIVERSAL_TRANSFER segment.
            // The Mongo query already filters by id regex but in tests with broad mocks it can
            // return unrelated INTERNAL_TRANSFER rows.
            if (tx.getId() == null || !tx.getId().contains(":UNIVERSAL_TRANSFER:")) {
                continue;
            }
            if (hasInternalPartner(tx, oppositeCountByKey)) {
                continue;
            }
            if (reclassifyTx(tx, timestamp)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_UNIVERSAL_TRANSFER_ORPHAN_RECLASSIFIER candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    /**
     * Index opposite-sign INTERNAL_TRANSFER docs by {@code (uid, family-or-symbol, |qty| rounded)}.
     * The signature is intentionally broader than {@link BybitStreamAuthorityCollapser}'s minute
     * bucket because we only care about "does a sibling EXIST anywhere in this UID/asset/qty
     * universe" — not where the bucket lands.
     */
    private Map<String, Integer> indexBroadSignatures(List<NormalizedTransaction> docs) {
        Map<String, Integer> counts = new HashMap<>();
        for (NormalizedTransaction tx : docs) {
            String key = broadOppositeSignatureKey(tx);
            if (key == null) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private boolean hasInternalPartner(NormalizedTransaction tx, Map<String, Integer> oppositeCountByKey) {
        String signature = broadOppositeSignatureKey(tx);
        if (signature == null) {
            return false;
        }
        // Look up the partner key (opposite sign).
        String partnerKey = flipSign(signature);
        Integer count = oppositeCountByKey.get(partnerKey);
        return count != null && count > 0;
    }

    private String broadOppositeSignatureKey(NormalizedTransaction tx) {
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (tx == null || tx.getWalletAddress() == null || principal == null
                || principal.getQuantityDelta() == null) {
            return null;
        }
        String uid = extractUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        String family = com.walletradar.accounting.support.AccountingAssetFamilySupport
                .continuityIdentity(principal);
        if (family == null) {
            String sym = principal.getAssetSymbol();
            family = sym == null ? "?" : sym.trim().toUpperCase(Locale.ROOT);
        }
        BigDecimal absQty = principal.getQuantityDelta().abs()
                .setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
        int sign = principal.getQuantityDelta().signum();
        if (sign == 0) {
            return null;
        }
        return uid + "|" + family + "|" + absQty.toPlainString() + "|" + sign;
    }

    private static String flipSign(String signature) {
        if (signature == null || signature.isEmpty()) {
            return signature;
        }
        if (signature.endsWith("|1")) {
            return signature.substring(0, signature.length() - 1) + "-1";
        }
        if (signature.endsWith("|-1")) {
            return signature.substring(0, signature.length() - 2) + "1";
        }
        return signature;
    }

    private static String extractUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
    }

    private boolean isExternalCounterparty(String accountingUniverseId, NormalizedTransaction tx) {
        String cp = tx == null ? null : tx.getCounterpartyAddress();
        if (cp == null || cp.isBlank() || "multi".equalsIgnoreCase(cp.trim())) {
            return false;
        }
        String trimmed = cp.trim();
        if (trimmed.regionMatches(true, 0, "BYBIT:", 0, "BYBIT:".length())) {
            return false;
        }
        String normalized = normalizeRef(trimmed);
        AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(
                accountingUniverseId,
                normalized,
                tx.getNetworkId()
        );
        return !membership.isMember();
    }

    private boolean reclassifyTx(NormalizedTransaction tx, Instant now) {
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return false;
        }
        BigDecimal qty = principal.getQuantityDelta();
        int sign = qty.signum();
        if (sign == 0) {
            return false;
        }
        NormalizedTransactionType newType = sign > 0
                ? NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                : NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
        NormalizedLegRole newRole = sign > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
        boolean changed = false;
        if (tx.getType() != newType) {
            tx.setType(newType);
            changed = true;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != newRole) {
                flow.setRole(newRole);
                changed = true;
            }
        }
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            tx.setContinuityCandidate(false);
            changed = true;
        }
        if (tx.getCorrelationId() != null) {
            tx.setCorrelationId(null);
            changed = true;
        }
        if (tx.getMatchedCounterparty() != null) {
            tx.setMatchedCounterparty(null);
            changed = true;
        }
        if (changed) {
            // Cycle/8 S1: the original INTERNAL_TRANSFER had role=TRANSFER and was placed in
            // CONFIRMED status by the canonical builder (it bypasses pricing). After we promote
            // it to EXTERNAL_TRANSFER_IN/OUT with role=BUY/SELL the doc must re-enter the
            // pricing pipeline so a historical USD quote can be attached — otherwise basis
            // remains $0 and the AVCO replay produces uncovered quantity on the receiving side.
            if (tx.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
                tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
                tx.setConfirmedAt(null);
            }
            tx.setUpdatedAt(now);
        }
        return changed;
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

    private static String normalizeRef(String ref) {
        if (ref == null) {
            return null;
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
        }
        return trimmed;
    }
}
