package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.linking.pipeline.clarification.CorridorCorrelationKeyFactory;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Package-private signature and helper primitives for {@link BybitStreamAuthorityCollapser}. */
final class BybitStreamAuthorityCollapserSupport {

    static final String ECON_CORR_PREFIX = "bybit-econ-v1:";
    static final String EXCLUSION_REASON_PREFIX = "BYBIT_STREAM_MIRROR_";
    static final int QTY_SCALE = 10;
    static final long BUCKET_SECONDS = 60L;

    private BybitStreamAuthorityCollapserSupport() {
    }

    static boolean isCorridorLeg(NormalizedTransaction tx) {
        if (tx == null) {
            return false;
        }
        if (CorridorCorrelationKeyFactory.isCorridorKey(tx.getCorrelationId())) {
            return true;
        }
        if (isEarnPrincipalCorridorLeg(tx)) {
            return true;
        }
        String txHash = tx.getTxHash();
        return txHash != null && !txHash.isBlank();
    }

    /**
     * CB-1 (corridor basis conservation orphan fix): {@link CorridorCorrelationKeyFactory#isCorridorKey}
     * only recognizes the on-chain {@code BYBIT-CORRIDOR:} prefix — it never protected a row whose two
     * legs were already proven to be one economic event by {@link BybitEarnPrincipalTransferPairer}
     * (shared {@code bybit-earn-principal-v1:} correlation, both legs {@code continuityCandidate=true}).
     * Without this guard, {@link #mirrorSignature}'s broader {@code (uid, family, |qty|, bucketMinute,
     * subAccount, sign)} grouping could independently re-exclude one of those already-paired legs as a
     * "stream mirror", stranding the other leg's basis (the corridor never dequeues). This check is
     * applied ONCE, at the single pre-filter every demotion pass in {@code collapseMirrors()} reads
     * from ({@code docs}), so it protects the primary pass, {@code demoteEventCountMirrors}, and
     * {@code demoteResidualMirrors} uniformly instead of patching each pass separately.
     */
    private static boolean isEarnPrincipalCorridorLeg(NormalizedTransaction tx) {
        String correlationId = tx.getCorrelationId();
        return correlationId != null
                && correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)
                && Boolean.TRUE.equals(tx.getContinuityCandidate());
    }

    static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
    }

    static String extractSubAccount(String walletAddress) {
        if (walletAddress == null) {
            return "";
        }
        int colon = walletAddress.lastIndexOf(':');
        if (colon < 0 || colon == walletAddress.length() - 1) {
            return "";
        }
        return walletAddress.substring(colon + 1).toUpperCase(Locale.ROOT);
    }

    static String sourceFileTag(NormalizedTransaction tx) {
        if (tx == null || tx.getId() == null) {
            return "UNKNOWN";
        }
        String id = tx.getId();
        int firstColon = id.indexOf(':');
        if (firstColon < 0) {
            return "UNKNOWN";
        }
        int secondColon = id.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return id.substring(firstColon + 1).toUpperCase(Locale.ROOT);
        }
        return id.substring(firstColon + 1, secondColon).toUpperCase(Locale.ROOT);
    }

    static NormalizedTransaction.Flow principalFlow(NormalizedTransaction tx) {
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

    static int principalSign(NormalizedTransaction tx) {
        NormalizedTransaction.Flow flow = principalFlow(tx);
        return flow == null || flow.getQuantityDelta() == null ? 0 : flow.getQuantityDelta().signum();
    }

    static int principalQuantitySign(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return 0;
        }
        BigDecimal qty = tx.getFlows().getFirst().getQuantityDelta();
        return qty == null ? 0 : qty.signum();
    }

    static String principalFamily(NormalizedTransaction tx) {
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family != null) {
            return family;
        }
        return principal.getAssetSymbol() == null
                ? null
                : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
    }

    static BigDecimal principalAbsQty(NormalizedTransaction tx) {
        NormalizedTransaction.Flow principal = principalFlow(tx);
        return principal == null || principal.getQuantityDelta() == null
                ? null
                : principal.getQuantityDelta().abs();
    }

    static String earnCorridorSignature(NormalizedTransaction tx) {
        String uid = extractBybitUid(tx == null ? null : tx.getWalletAddress());
        String family = principalFamily(tx);
        BigDecimal absQty = principalAbsQty(tx);
        if (uid == null || family == null || absQty == null || absQty.signum() <= 0) {
            return null;
        }
        return uid + "|" + family + "|"
                + absQty.setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static String mirrorSignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null || tx.getBlockTimestamp() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        String subAccount = extractSubAccount(tx.getWalletAddress());
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        BigDecimal qty = principal.getQuantityDelta();
        int sign = qty.signum();
        if (sign == 0) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            family = principal.getAssetSymbol() == null
                    ? "?"
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        String absQty = qty.abs().setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        long bucket = tx.getBlockTimestamp().getEpochSecond() / BUCKET_SECONDS;
        return String.join("|",
                uid,
                family,
                absQty,
                Long.toString(bucket),
                subAccount,
                Integer.toString(sign)
        );
    }

    static String walletSignSignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            family = principal.getAssetSymbol() == null
                    ? "?"
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        BigDecimal absQty = principal.getQuantityDelta().abs()
                .setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        int sign = principal.getQuantityDelta().signum();
        if (sign == 0) {
            return null;
        }
        return String.join("|",
                uid,
                family,
                absQty.toPlainString(),
                tx.getWalletAddress(),
                Integer.toString(sign)
        );
    }

    static String economicUnifySignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            family = principal.getAssetSymbol() == null
                    ? "?"
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        BigDecimal absQty = principal.getQuantityDelta().abs()
                .setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return uid + "|" + family + "|" + absQty.toPlainString();
    }

    static String broadSignature(NormalizedTransaction tx) {
        String base = economicUnifySignature(tx);
        if (base == null) {
            return null;
        }
        long bucket = tx.getBlockTimestamp() != null
                ? tx.getBlockTimestamp().getEpochSecond() / BUCKET_SECONDS
                : 0L;
        return base + "|" + bucket;
    }

    static NormalizedTransaction pickCanonical(List<NormalizedTransaction> group) {
        group.sort(Comparator
                .comparingInt((NormalizedTransaction tx) -> canonicalPriority(tx))
                .thenComparing(NormalizedTransaction::getBlockTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NormalizedTransaction::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return group.get(0);
    }

    static int canonicalPriority(NormalizedTransaction tx) {
        return canonicalPriority(sourceFileTag(tx), extractSubAccount(tx.getWalletAddress()));
    }

    static int canonicalPriority(String source, String subAccount) {
        if ("EARN".equals(subAccount)) {
            return switch (source) {
                case "EARN_FLEXIBLE_SAVING" -> 0;
                case "INTERNAL_TRANSFER" -> 1;
                case "FUNDING_HISTORY" -> 2;
                case "TRANSACTION_LOG" -> 3;
                case "UNIVERSAL_TRANSFER" -> 4;
                default -> 5;
            };
        }
        if ("UTA".equals(subAccount)) {
            return switch (source) {
                case "INTERNAL_TRANSFER" -> 0;
                case "TRANSACTION_LOG" -> 1;
                case "FUNDING_HISTORY" -> 2;
                case "UNIVERSAL_TRANSFER" -> 3;
                case "EARN_FLEXIBLE_SAVING" -> 4;
                default -> 5;
            };
        }
        return switch (source) {
            case "INTERNAL_TRANSFER" -> 0;
            case "FUNDING_HISTORY" -> 1;
            case "TRANSACTION_LOG" -> 2;
            case "UNIVERSAL_TRANSFER" -> 3;
            case "EARN_FLEXIBLE_SAVING" -> 4;
            default -> 5;
        };
    }

    /**
     * RC-9 D1 (determinism fix): shared ascending-{@code _id} tiebreak used across every Bybit
     * normalization pass in this package that selects one document among 2+ tied/near-tied
     * candidates, so the selection is a pure function of the candidate set rather than the leaked
     * Mongo scan order. See {@code docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md} §D1.
     */
    static Comparator<NormalizedTransaction> idTiebreak() {
        return Comparator.comparing(NormalizedTransaction::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    static int comparePriorityThenId(NormalizedTransaction left, NormalizedTransaction right) {
        int byPriority = Integer.compare(canonicalPriority(left), canonicalPriority(right));
        if (byPriority != 0) {
            return byPriority;
        }
        String leftId = left.getId() == null ? "" : left.getId();
        String rightId = right.getId() == null ? "" : right.getId();
        return leftId.compareTo(rightId);
    }

    static boolean isDriftOrphanCandidate(NormalizedTransaction tx) {
        if (tx == null) {
            return false;
        }
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            return false;
        }
        String corr = tx.getCorrelationId();
        return corr != null && corr.startsWith(ECON_CORR_PREFIX);
    }

    static void propagateCorrelationMetadata(
            NormalizedTransaction excluded,
            NormalizedTransaction canonical,
            java.time.Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        if (excluded.getCorrelationId() == null || excluded.getCorrelationId().isBlank()) {
            return;
        }
        if (canonical.getCorrelationId() != null && !canonical.getCorrelationId().isBlank()) {
            return;
        }
        canonical.setCorrelationId(excluded.getCorrelationId());
        canonical.setMatchedCounterparty(excluded.getMatchedCounterparty());
        canonical.setContinuityCandidate(excluded.getContinuityCandidate());
        canonical.setUpdatedAt(now);
        if (!dirtyAccumulator.contains(canonical)) {
            dirtyAccumulator.add(canonical);
        }
    }

    static String collapsedCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String leftId = left.getId() == null ? "" : left.getId();
        String rightId = right.getId() == null ? "" : right.getId();
        String low = leftId.compareTo(rightId) <= 0 ? leftId : rightId;
        String high = low.equals(leftId) ? rightId : leftId;
        return BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX + sha256Hex(low + "|" + high);
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
}
