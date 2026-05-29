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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * Pairs Bybit Flexible Savings / Earn principal moves ({@code LENDING_DEPOSIT} /
 * {@code LENDING_WITHDRAW}) across {@code :EARN} and {@code :FUND}/{@code :UTA} with a shared
 * continuity correlation so replay uses correlation keys instead of fragile FIFO-only matching.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitEarnPrincipalTransferPairer {

    public static final String EARN_PRINCIPAL_CORRELATION_PREFIX = "bybit-earn-principal-v1:";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int QTY_SCALE = 8;
    private static final Duration MAX_PAIR_DRIFT = Duration.ofMinutes(30);

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int pairEarnPrincipalTransfers() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").in(
                        NormalizedTransactionType.LENDING_DEPOSIT,
                        NormalizedTransactionType.LENDING_WITHDRAW
                ),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.size() < 2) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : candidates) {
            String key = principalSignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int rewrites = 0;
        Instant now = Instant.now();
        Set<String> rewritten = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (List<NormalizedTransaction> bucket : grouped.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            bucket.sort(Comparator.comparing(
                    NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));
            for (int i = 0; i < bucket.size(); i++) {
                NormalizedTransaction left = bucket.get(i);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int leftSign = principalSign(left);
                if (leftSign == 0) {
                    continue;
                }
                NormalizedTransaction best = null;
                Duration bestDrift = MAX_PAIR_DRIFT.plusSeconds(1);
                for (int j = i + 1; j < bucket.size(); j++) {
                    NormalizedTransaction right = bucket.get(j);
                    if (rewritten.contains(right.getId())) {
                        continue;
                    }
                    if (!isEarnPrincipalOppositePair(left, right, leftSign)) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration drift = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (drift.compareTo(MAX_PAIR_DRIFT) > 0 || drift.compareTo(bestDrift) >= 0) {
                        continue;
                    }
                    bestDrift = drift;
                    best = right;
                }
                if (best == null) {
                    continue;
                }
                applyEarnPrincipalPair(left, best, now);
                rewritten.add(left.getId());
                rewritten.add(best.getId());
                dirty.add(left);
                dirty.add(best);
                rewrites += 2;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_EARN_PRINCIPAL_PAIRER candidates={} rewrites={}", candidates.size(), rewrites);
        }
        return rewrites;
    }

    private static boolean isEarnPrincipalOppositePair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            int leftSign
    ) {
        if (Objects.equals(left.getWalletAddress(), right.getWalletAddress())) {
            return false;
        }
        if (!sameUid(left.getWalletAddress(), right.getWalletAddress())) {
            return false;
        }
        int rightSign = principalSign(right);
        if (rightSign == 0 || rightSign == leftSign) {
            return false;
        }
        boolean leftEarn = isEarnWallet(left.getWalletAddress());
        boolean rightEarn = isEarnWallet(right.getWalletAddress());
        if (leftEarn == rightEarn) {
            return false;
        }
        return oppositeQty(left, right);
    }

    private static void applyEarnPrincipalPair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String correlationId = earnPrincipalCorrelationId(left, right);
        left.setCorrelationId(correlationId);
        right.setCorrelationId(correlationId);
        left.setContinuityCandidate(true);
        right.setContinuityCandidate(true);
        if (left.getMatchedCounterparty() == null || left.getMatchedCounterparty().isBlank()) {
            left.setMatchedCounterparty(right.getWalletAddress());
        }
        if (right.getMatchedCounterparty() == null || right.getMatchedCounterparty().isBlank()) {
            right.setMatchedCounterparty(left.getWalletAddress());
        }
        left.setUpdatedAt(now);
        right.setUpdatedAt(now);
    }

    private static String earnPrincipalCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String uid = extractUid(left.getWalletAddress());
        String family = familySymbol(left);
        BigDecimal qty = principalQty(left).max(principalQty(right));
        String qtyPlain = qty.setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        Instant ts = left.getBlockTimestamp();
        if (right.getBlockTimestamp() != null && (ts == null || right.getBlockTimestamp().isBefore(ts))) {
            ts = right.getBlockTimestamp();
        }
        long epochSecond = ts == null ? 0L : ts.getEpochSecond();
        String payload = (uid == null ? "" : uid) + "|" + family + "|" + qtyPlain + "|" + epochSecond;
        return EARN_PRINCIPAL_CORRELATION_PREFIX + sha256Hex(payload);
    }

    private static String principalSignature(NormalizedTransaction tx) {
        String uid = extractUid(tx.getWalletAddress());
        String family = familySymbol(tx);
        BigDecimal qty = principalQty(tx);
        if (uid == null || family == null || qty == null) {
            return null;
        }
        return uid + "|" + family + "|" + qty.setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static boolean oppositeQty(NormalizedTransaction left, NormalizedTransaction right) {
        BigDecimal l = principalQty(left);
        BigDecimal r = principalQty(right);
        if (l == null || r == null) {
            return false;
        }
        return l.subtract(r, MC).abs().compareTo(new BigDecimal("0.00000001")) <= 0;
    }

    private static BigDecimal principalQty(NormalizedTransaction tx) {
        BigDecimal qty = principalQuantity(tx);
        return qty == null ? null : qty.abs();
    }

    private static int principalSign(NormalizedTransaction tx) {
        BigDecimal qty = principalQuantity(tx);
        return qty == null ? 0 : qty.signum();
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

    private static String familySymbol(NormalizedTransaction tx) {
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

    private static boolean isEarnWallet(String wallet) {
        return wallet != null && wallet.toUpperCase(Locale.ROOT).endsWith(":EARN");
    }

    private static boolean sameUid(String leftWallet, String rightWallet) {
        String left = extractUid(leftWallet);
        String right = extractUid(rightWallet);
        return left != null && left.equals(right);
    }

    private static String extractUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = walletAddress.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        return colon > 0 ? without.substring(0, colon) : without;
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
