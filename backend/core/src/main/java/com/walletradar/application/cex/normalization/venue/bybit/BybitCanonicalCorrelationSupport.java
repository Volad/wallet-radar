package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BybitCanonicalCorrelationSupport {
    private BybitCanonicalCorrelationSupport() {}
    private static final Pattern SELF_TRANSFER_PATTERN = Pattern.compile("selfTransfer_([0-9a-fA-F-]{36})");
    private static final Pattern UNI_TRANS_PATTERN = Pattern.compile("uni_trans_([0-9a-fA-F-]{36})");

    static String bybitSubAccountTransferCorrelationId(ExternalLedgerRaw row) {
        if (row == null) {
            return null;
        }
        String id = row.getId();
        if (id == null || id.isBlank()) {
            return null;
        }
        Matcher matcher = SELF_TRANSFER_PATTERN.matcher(id);
        if (matcher.find()) {
            return "bybit-sub-transfer:" + normalize(row.getUid()) + ":" + matcher.group(1);
        }
        matcher = UNI_TRANS_PATTERN.matcher(id);
        if (matcher.find()) {
            return "bybit-uni-transfer:" + normalize(row.getUid()) + ":" + matcher.group(1);
        }
        return null;
    }

    static String otherSubAccount(String walletRef) {
        if (walletRef.endsWith(":UTA")) {
            return walletRef.substring(0, walletRef.length() - 3) + "FUND";
        }
        if (walletRef.endsWith(":FUND")) {
            return walletRef.substring(0, walletRef.length() - 4) + "UTA";
        }
        if (walletRef.endsWith(":EARN")) {
            return walletRef.substring(0, walletRef.length() - 4) + "FUND";
        }
        return null;
    }

    static boolean shouldAttachBybitEconomyCorrelationId(ExternalLedgerRaw row) {
        if (row == null) {
            return false;
        }
        String sf = row.getSourceFile();
        if (sf == null || sf.isBlank()) {
            return false;
        }
        String u = sf.toUpperCase(Locale.ROOT);
        return u.contains("TRANSACTION_LOG")
                || u.contains("INTERNAL_TRANSFER")
                || u.contains("FUNDING_HISTORY")
                || u.contains("EARN_FLEXIBLE_SAVING");
    }

    static String bybitInternalTransferEconomyCorrelationId(ExternalLedgerRaw row) {
        if (row == null || row.getUid() == null || row.getAssetSymbol() == null || row.getQuantityRaw() == null) {
            return null;
        }
        Instant anchor = row.getTimeUtc() != null ? row.getTimeUtc() : row.getImportedAt();
        if (anchor == null) {
            return null;
        }
        long minuteBucket = anchor.getEpochSecond() / 60;
        String qtyPlain = row.getQuantityRaw().abs().stripTrailingZeros().toPlainString();
        String payload = normalize(row.getUid())
                + "|"
                + row.getAssetSymbol().trim().toUpperCase(Locale.ROOT)
                + "|"
                + qtyPlain
                + "|"
                + minuteBucket;
        return "bybit-econ-v1:" + sha256Hex(payload);
    }

    static String crossSubAccountStakingCorrelationId(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (left == null || right == null) {
            return null;
        }
        String uid = normalize(left.getUid());
        if (uid.isBlank()) {
            uid = normalize(right.getUid());
        }
        if (uid.isBlank()) {
            return null;
        }
        String cluster = AccountingAssetClassificationSupport.liquidStakingNormalizationCluster(
                left.getAssetSymbol(), right.getAssetSymbol());
        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(left.getAssetSymbol(), null);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(right.getAssetSymbol(), null);
        String family = cluster != null ? cluster : (leftFamily != null ? leftFamily : rightFamily);
        if (family == null || family.isBlank()) {
            return null;
        }
        BigDecimal leftQty = left.getQuantityRaw() == null ? null : left.getQuantityRaw().abs().stripTrailingZeros();
        BigDecimal rightQty = right.getQuantityRaw() == null ? null : right.getQuantityRaw().abs().stripTrailingZeros();
        BigDecimal qty = leftQty != null && (rightQty == null || leftQty.compareTo(rightQty) >= 0) ? leftQty : rightQty;
        if (qty == null) {
            return null;
        }
        Instant leftTime = left.getTimeUtc() != null ? left.getTimeUtc() : left.getImportedAt();
        Instant rightTime = right.getTimeUtc() != null ? right.getTimeUtc() : right.getImportedAt();
        Instant anchor;
        if (leftTime != null && rightTime != null) {
            anchor = leftTime.isBefore(rightTime) ? leftTime : rightTime;
        } else {
            anchor = leftTime != null ? leftTime : rightTime;
        }
        if (anchor == null) {
            return null;
        }
        long minuteBucket = anchor.getEpochSecond() / 60;
        String payload = uid
                + "|"
                + family
                + "|"
                + qty.toPlainString()
                + "|"
                + minuteBucket;
        return "bybit-stake-pair-v1:" + sha256Hex(payload);
    }

    static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
