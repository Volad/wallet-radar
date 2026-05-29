package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * When an authoritative earn-principal pair exists, demote duplicate {@code INTERNAL_TRANSFER}
 * rows that describe the same economic move (collapsed stream mirrors).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitPrincipalEventExclusivityService {

    public static final String DUPLICATE_PRINCIPAL_REASON = "BYBIT_DUPLICATE_PRINCIPAL";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int QTY_SCALE = 8;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int demoteDuplicatePrincipalEvents() {
        Query earnPairs = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("correlationId").regex("^" + BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> earnLegs = mongoOperations.find(earnPairs, NormalizedTransaction.class);
        if (earnLegs.isEmpty()) {
            return 0;
        }

        Set<String> earnSignatures = new HashSet<>();
        for (NormalizedTransaction tx : earnLegs) {
            String sig = principalEventSignature(tx);
            if (sig != null) {
                earnSignatures.add(sig);
            }
        }
        if (earnSignatures.isEmpty()) {
            return 0;
        }

        Query itQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true),
                new Criteria().orOperator(
                        Criteria.where("correlationId").regex("^bybit-collapsed-v1:"),
                        Criteria.where("correlationId").regex("^bybit-it-pair-v1:"),
                        Criteria.where("correlationId").regex("^bybit-rekeyed-v1:")
                )
        ));
        List<NormalizedTransaction> transfers = mongoOperations.find(itQuery, NormalizedTransaction.class);
        if (transfers.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> earnBySignature = new HashMap<>();
        for (NormalizedTransaction tx : earnLegs) {
            String sig = principalEventSignature(tx);
            if (sig == null) {
                continue;
            }
            earnBySignature.computeIfAbsent(sig, ignored -> new ArrayList<>()).add(tx);
        }

        int demoted = 0;
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction it : transfers) {
            String sig = principalEventSignature(it);
            if (sig == null || !earnSignatures.contains(sig)) {
                continue;
            }
            List<NormalizedTransaction> earnMatches = earnBySignature.get(sig);
            if (earnMatches == null || earnMatches.isEmpty()) {
                continue;
            }
            if (!hasOppositeSignPartner(it, earnMatches)) {
                continue;
            }
            it.setExcludedFromAccounting(true);
            it.setAccountingExclusionReason(DUPLICATE_PRINCIPAL_REASON);
            it.setContinuityCandidate(false);
            it.setUpdatedAt(now);
            dirty.add(it);
            demoted++;
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_PRINCIPAL_EVENT_EXCLUSIVITY demoted={} earnSignatures={}", demoted, earnSignatures.size());
        }
        return demoted;
    }

    private static boolean hasOppositeSignPartner(
            NormalizedTransaction it,
            List<NormalizedTransaction> earnMatches
    ) {
        int itSign = principalSign(it);
        if (itSign == 0) {
            return false;
        }
        for (NormalizedTransaction earn : earnMatches) {
            int earnSign = principalSign(earn);
            if (earnSign != 0 && earnSign != itSign) {
                return true;
            }
        }
        return false;
    }

    private static String principalEventSignature(NormalizedTransaction tx) {
        String uid = extractUid(tx.getWalletAddress());
        String symbol = principalSymbol(tx);
        BigDecimal qty = principalQty(tx);
        if (uid == null || symbol == null || qty == null) {
            return null;
        }
        return uid + "|" + symbol + "|" + qty.setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal principalQty(NormalizedTransaction tx) {
        BigDecimal q = principalQuantity(tx);
        return q == null ? null : q.abs();
    }

    private static int principalSign(NormalizedTransaction tx) {
        BigDecimal q = principalQuantity(tx);
        return q == null ? 0 : q.signum();
    }

    private static BigDecimal principalQuantity(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
                return flow.getQuantityDelta();
            }
        }
        return null;
    }

    private static String principalSymbol(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow != null && flow.getAssetSymbol() != null && !flow.getAssetSymbol().isBlank()) {
                return flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String extractUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = walletAddress.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        return colon > 0 ? without.substring(0, colon) : without;
    }
}
