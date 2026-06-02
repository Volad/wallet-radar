package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferPairer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cycle/12: Demotes Bybit {@code INTERNAL_TRANSFER} legs that remain singleton correlation ids
 * after bundle and round-trip pairing so the pricing pipeline can establish market basis.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitInternalTransferOrphanFallbackService {

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcileOrphanInternals() {
        Query candidatesQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("continuityCandidate").is(true)
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(candidatesQuery, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<String, Integer> corrIdCount = new HashMap<>();
        for (NormalizedTransaction tx : candidates) {
            String corr = tx.getCorrelationId();
            if (corr == null || corr.isBlank()) {
                continue;
            }
            corrIdCount.merge(corr, 1, Integer::sum);
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int inboundDemoted = 0;
        int outboundDemoted = 0;
        for (NormalizedTransaction tx : candidates) {
            if (isFa001OnChainCorridorAnchor(tx)) {
                continue;
            }
            if (isProtectedFundEarnPairerCorrelation(tx, corrIdCount)) {
                continue;
            }
            String corr = tx.getCorrelationId();
            if (corr == null || corr.isBlank() || corrIdCount.getOrDefault(corr, 0) != 1) {
                continue;
            }
            // Cross-UID universal transfers have their outbound partner excluded from accounting
            // to prevent double-counting. The inbound leg correctly appears as a singleton here —
            // do not demote it as an orphan.
            if (corr.startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)) {
                continue;
            }
            int sign = principalQuantitySign(tx);
            if (sign > 0) {
                if (demoteInboundOrphan(tx, now)) {
                    dirty.add(tx);
                    inboundDemoted++;
                }
            } else if (sign < 0) {
                if (demoteOutboundOrphan(tx, now)) {
                    dirty.add(tx);
                    outboundDemoted++;
                }
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
        }
        if (!candidates.isEmpty()) {
            log.info(
                    "BYBIT_INTERNAL_TRANSFER_ORPHAN_DEMOTE candidates={} inbound={} outbound={}",
                    candidates.size(),
                    inboundDemoted,
                    outboundDemoted
            );
        }
        return dirty.size();
    }

    private boolean demoteInboundOrphan(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            tx.setContinuityCandidate(false);
            changed = true;
        }
        if (tx.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
            changed = true;
        }
        if (promoteTransferToBuy(tx)) {
            changed = true;
        }
        return finalizeDemote(tx, now, changed);
    }

    private boolean demoteOutboundOrphan(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            tx.setContinuityCandidate(false);
            changed = true;
        }
        if (tx.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
            changed = true;
        }
        if (promoteTransferToSell(tx)) {
            changed = true;
        }
        return finalizeDemote(tx, now, changed);
    }

    private static boolean promoteTransferToBuy(NormalizedTransaction tx) {
        boolean changed = false;
        if (tx.getFlows() == null) {
            return false;
        }
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
        return changed;
    }

    private static boolean promoteTransferToSell(NormalizedTransaction tx) {
        boolean changed = false;
        if (tx.getFlows() == null) {
            return false;
        }
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
        return changed;
    }

    private static boolean finalizeDemote(NormalizedTransaction tx, Instant now, boolean changed) {
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

    private static int principalQuantitySign(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return 0;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
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
     * Only pairer-confirmed FUND↔EARN bundles stay {@code INTERNAL_TRANSFER}. Singleton
     * {@code bybit-econ-v1:*} Earn/Launchpool legs are demoted so pricing can establish basis.
     */
    private static boolean isProtectedFundEarnPairerCorrelation(
            NormalizedTransaction tx,
            Map<String, Integer> corrIdCount
    ) {
        if (!isSameUidFundEarnCounterparty(tx)) {
            return false;
        }
        String corr = tx.getCorrelationId();
        if (corr == null || corr.isBlank() || corrIdCount == null) {
            return false;
        }
        if (!(corr.startsWith("bybit-it-bundle-v1:")
                || corr.startsWith("bybit-it-roundtrip-v1:")
                || corr.startsWith("bybit-collapsed-v1:"))) {
            return false;
        }
        return corrIdCount.getOrDefault(corr, 0) > 1;
    }

    private static boolean isSameUidFundEarnCounterparty(NormalizedTransaction tx) {
        if (tx == null) {
            return false;
        }
        String wallet = tx.getWalletAddress();
        String counterparty = tx.getMatchedCounterparty();
        if (wallet == null || counterparty == null) {
            return false;
        }
        String walletSuffix = subAccountSuffix(wallet);
        String counterpartySuffix = subAccountSuffix(counterparty);
        if (walletSuffix == null || counterpartySuffix == null) {
            return false;
        }
        boolean fundEarnPair = ("FUND".equals(walletSuffix) && "EARN".equals(counterpartySuffix))
                || ("EARN".equals(walletSuffix) && "FUND".equals(counterpartySuffix));
        if (!fundEarnPair) {
            return false;
        }
        String walletUid = extractBybitUid(wallet);
        String counterpartyUid = extractBybitUid(counterparty);
        return walletUid != null && walletUid.equals(counterpartyUid);
    }

    private static String subAccountSuffix(String address) {
        if (address == null) {
            return null;
        }
        int colon = address.lastIndexOf(':');
        if (colon < 0 || colon == address.length() - 1) {
            return null;
        }
        return address.substring(colon + 1).toUpperCase(Locale.ROOT);
    }

    private static String extractBybitUid(String address) {
        if (address == null || !address.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String remainder = address.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
    }

    /**
     * Cycle/18 R9b: FA-001 on-chain↔Bybit deposit anchors look like Bybit-only singletons because
     * the paired ON_CHAIN row is not counted in {@link #reconcileOrphanInternals()}.
     */
    private static boolean isFa001OnChainCorridorAnchor(NormalizedTransaction tx) {
        if (tx == null || tx.getSource() != NormalizedTransactionSource.BYBIT) {
            return false;
        }
        String correlationId = tx.getCorrelationId();
        if (correlationId != null && correlationId.startsWith("BYBIT-CORRIDOR:")) {
            return true;
        }
        String matchedCounterparty = tx.getMatchedCounterparty();
        return matchedCounterparty != null
                && matchedCounterparty.startsWith("0x")
                && matchedCounterparty.length() == 42;
    }
}
