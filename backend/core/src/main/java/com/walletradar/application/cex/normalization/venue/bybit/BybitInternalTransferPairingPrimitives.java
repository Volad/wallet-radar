package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.canonical.correlation.BybitCarryContinuitySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Package-private pairing primitives extracted from {@link BybitInternalTransferPairer}. */
final class BybitInternalTransferPairingPrimitives {
    private BybitInternalTransferPairingPrimitives() {
    }

    static final MathContext MC = MathContext.DECIMAL64;
    static final int QTY_SCALE = 10;
    static final int BROAD_QTY_SCALE = 3;
    static final BigDecimal BROAD_QTY_TOLERANCE_PCT = new BigDecimal("0.0005");
    static final Duration BROAD_PAIR_DRIFT = Duration.ofMinutes(10);
    static final Pattern EVM_HEX_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    static final String ROUNDTRIP_CORRELATION_PREFIX = BybitInternalTransferPairer.ROUNDTRIP_CORRELATION_PREFIX;
    static final String PAIR_CORRELATION_PREFIX = BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX;
    static final String REKEYED_CORRELATION_PREFIX = BybitInternalTransferPairer.REKEYED_CORRELATION_PREFIX;
    static final String BUNDLE_CORRELATION_PREFIX = BybitInternalTransferPairer.BUNDLE_CORRELATION_PREFIX;
    static final String EARN_PRINCIPAL_CORRELATION_PREFIX =
            BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX;

    static boolean isNearZeroBundle(List<NormalizedTransaction> window, BigDecimal residualPct) {
        boolean hasIn = false;
        boolean hasOut = false;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (NormalizedTransaction tx : window) {
            BigDecimal qty = principalQuantity(tx);
            if (qty == null || qty.signum() == 0) {
                return false;
            }
            if (qty.signum() > 0) {
                hasIn = true;
            } else {
                hasOut = true;
            }
            sum = sum.add(qty, MC);
            BigDecimal abs = qty.abs();
            if (abs.compareTo(maxAbs) > 0) {
                maxAbs = abs;
            }
        }
        if (!hasIn || !hasOut || maxAbs.signum() == 0) {
            return false;
        }
        BigDecimal ratio = sum.abs().divide(maxAbs, MC);
        return ratio.compareTo(residualPct) < 0;
    }

    static boolean isOppositeQty(BigDecimal outbound, BigDecimal inbound, BigDecimal tolerancePct) {
        if (outbound.signum() >= 0 || inbound.signum() <= 0) {
            return false;
        }
        BigDecimal sum = outbound.add(inbound, MC);
        BigDecimal denom = outbound.abs();
        if (denom.signum() == 0) {
            return false;
        }
        return sum.abs().divide(denom, MC).compareTo(tolerancePct) < 0;
    }

    static NormalizedTransaction firstInboundLeg(List<NormalizedTransaction> window) {
        for (NormalizedTransaction tx : window) {
            BigDecimal qty = principalQuantity(tx);
            if (qty != null && qty.signum() > 0) {
                return tx;
            }
        }
        return null;
    }

    static void applyPairCorrelation(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String canonicalCorrelation = canonicalPairCorrelationId(left, right);
        left.setCorrelationId(canonicalCorrelation);
        right.setCorrelationId(canonicalCorrelation);
        left.setContinuityCandidate(true);
        right.setContinuityCandidate(true);
        if (left.getMatchedCounterparty() == null) {
            left.setMatchedCounterparty(right.getWalletAddress());
        }
        if (right.getMatchedCounterparty() == null) {
            right.setMatchedCounterparty(left.getWalletAddress());
        }
        BybitCarryContinuitySupport.stamp(left);
        BybitCarryContinuitySupport.stamp(right);
        left.setUpdatedAt(now);
        right.setUpdatedAt(now);
    }

    static void applyRekeyedPairCorrelation(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String canonicalCorrelation = rekeyedPairCorrelationId(left, right);
        left.setCorrelationId(canonicalCorrelation);
        right.setCorrelationId(canonicalCorrelation);
        left.setContinuityCandidate(true);
        right.setContinuityCandidate(true);
        if (left.getMatchedCounterparty() == null) {
            left.setMatchedCounterparty(right.getWalletAddress());
        }
        if (right.getMatchedCounterparty() == null) {
            right.setMatchedCounterparty(left.getWalletAddress());
        }
        // R-ORDER: rekeyed-v1 pairs (FUND→UTA internal moves) must process AFTER
        // BYBIT-CORRIDOR events at the same blockTimestamp. The corridor FUND credit
        // carries the on-chain transactionIndex (e.g. 1), while rekeyed legs carry the
        // synthetic default (0 or null). With sort "blockTimestamp → transactionIndex → _id",
        // the rekeyed FUND debit (transactionIndex=0) would otherwise precede the corridor
        // FUND credit (transactionIndex=1), draining an empty FUND position and losing the
        // $3945 basis. Setting MAX_VALUE on rekeyed legs guarantees they always follow
        // all same-timestamp corridor events regardless of their on-chain transactionIndex.
        if (left.getTransactionIndex() == null || left.getTransactionIndex() == 0) {
            left.setTransactionIndex(Integer.MAX_VALUE);
        }
        if (right.getTransactionIndex() == null || right.getTransactionIndex() == 0) {
            right.setTransactionIndex(Integer.MAX_VALUE);
        }
        BybitCarryContinuitySupport.stamp(left);
        BybitCarryContinuitySupport.stamp(right);
        left.setUpdatedAt(now);
        right.setUpdatedAt(now);
    }

    static void applyRekeyedDemotedPair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        applyRekeyedPairCorrelation(left, right, now);
        promoteDemotedLegToInternalTransfer(left);
        promoteDemotedLegToInternalTransfer(right);
        BybitCarryContinuitySupport.stamp(left);
        BybitCarryContinuitySupport.stamp(right);
    }

    static void promoteDemotedLegToInternalTransfer(NormalizedTransaction tx) {
        if (tx == null) {
            return;
        }
        int sign = principalQuantitySign(tx);
        if (sign > 0) {
            tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            demoteBuySellToTransfer(tx);
        } else if (sign < 0) {
            tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            demoteBuySellToTransfer(tx);
        }
    }

    static void demoteBuySellToTransfer(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL) {
                flow.setRole(NormalizedLegRole.TRANSFER);
            }
        }
    }

    static String exactQtySignature(NormalizedTransaction tx) {
        String family = familySignature(tx);
        if (family == null) {
            return null;
        }
        BigDecimal qty = principalQuantity(tx);
        if (qty == null) {
            return null;
        }
        BigDecimal absQty = qty.abs().setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return family + "|" + absQty.toPlainString();
    }

    static String broadQtySignature(NormalizedTransaction tx) {
        return familySignature(tx);
    }

    static boolean isBroadOppositeQty(NormalizedTransaction left, NormalizedTransaction right) {
        BigDecimal leftQty = principalQuantity(left);
        BigDecimal rightQty = principalQuantity(right);
        if (leftQty == null || rightQty == null) {
            return false;
        }
        return isOppositeQty(leftQty, rightQty, BROAD_QTY_TOLERANCE_PCT);
    }

    /**
     * FA-001 Bybit FUNDING_HISTORY withdraw anchors that share an on-chain txHash are corridor
     * legs, not stream mirrors — never demote via {@link #dedupSameSignMirrors()}.
     */
    static boolean isFa001OnChainWithdrawAnchor(NormalizedTransaction tx) {
        if (tx == null || tx.getTxHash() == null || tx.getTxHash().isBlank() || tx.getNetworkId() == null) {
            return false;
        }
        if (tx.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        if (principalQuantitySign(tx) >= 0) {
            return false;
        }
        if (hasEvmMatchedCounterparty(tx.getMatchedCounterparty())) {
            return true;
        }
        if (tx.getFlows() == null) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            String counterparty = flow.getCounterpartyAddress();
            if (counterparty != null
                    && EVM_HEX_ADDRESS.matcher(counterparty.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasEvmMatchedCounterparty(String matchedCounterparty) {
        return matchedCounterparty != null
                && EVM_HEX_ADDRESS.matcher(matchedCounterparty.trim()).matches();
    }

    static String sameSignMirrorSignature(NormalizedTransaction tx) {
        if (isFa001OnChainWithdrawAnchor(tx)) {
            return null;
        }
        String broad = broadQtySignature(tx);
        if (broad == null || tx.getWalletAddress() == null) {
            return null;
        }
        BigDecimal qty = principalQuantity(tx);
        if (qty == null || qty.signum() == 0) {
            return null;
        }
        int sign = qty.signum();
        BigDecimal absQty = qty.abs().setScale(BROAD_QTY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return broad + "|" + tx.getWalletAddress() + "|" + sign + "|" + absQty.toPlainString();
    }

    static String familySignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null) {
            return null;
        }
        String familyKey = com.walletradar.application.costbasis.support.AccountingAssetFamilySupport
                .continuityIdentity(principal.getAssetSymbol(), principal.getAssetContract());
        if (familyKey == null || familyKey.isBlank()) {
            familyKey = principal.getAssetSymbol() == null
                    ? ""
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        return uid + "|" + familyKey;
    }

    static String sameWalletSignature(NormalizedTransaction tx) {
        String family = familySignature(tx);
        if (family == null || tx.getWalletAddress() == null) {
            return null;
        }
        return family + "|" + tx.getWalletAddress();
    }

    static boolean hasDifferentCorrelationId(NormalizedTransaction a, NormalizedTransaction b) {
        String corrA = a == null ? null : a.getCorrelationId();
        String corrB = b == null ? null : b.getCorrelationId();
        if (corrA == null || corrA.isBlank() || corrB == null || corrB.isBlank()) {
            return true;
        }
        return !corrA.equals(corrB);
    }

    static boolean involvesEarnSubAccount(NormalizedTransaction tx) {
        if (tx == null) {
            return false;
        }
        String wallet = tx.getWalletAddress();
        if (wallet != null && wallet.endsWith(":EARN")) {
            return true;
        }
        String cp = tx.getMatchedCounterparty();
        if (cp == null || cp.isBlank()) {
            cp = tx.getCounterpartyAddress();
        }
        return cp != null && cp.endsWith(":EARN");
    }

    static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
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

    static BigDecimal principalQuantity(NormalizedTransaction tx) {
        NormalizedTransaction.Flow flow = principalFlow(tx);
        return flow == null ? null : flow.getQuantityDelta();
    }

    static int principalQuantitySign(NormalizedTransaction tx) {
        BigDecimal qty = principalQuantity(tx);
        if (qty == null) {
            return 0;
        }
        return qty.signum();
    }

    static String canonicalPairCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String low = canonicalIdOrder(left.getId(), right.getId());
        String high = Objects.equals(low, left.getId()) ? nullSafeId(right.getId()) : nullSafeId(left.getId());
        return PAIR_CORRELATION_PREFIX + low + "|" + high;
    }

    static String rekeyedPairCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String uid = extractBybitUid(left.getWalletAddress());
        if (uid == null) {
            uid = extractBybitUid(right.getWalletAddress());
        }
        NormalizedTransaction.Flow principal = principalFlow(left);
        if (principal == null) {
            principal = principalFlow(right);
        }
        String familyKey = "";
        String qtyPlain = "";
        if (principal != null) {
            familyKey = com.walletradar.application.costbasis.support.AccountingAssetFamilySupport
                    .continuityIdentity(principal.getAssetSymbol(), principal.getAssetContract());
            if (familyKey == null || familyKey.isBlank()) {
                familyKey = principal.getAssetSymbol() == null
                        ? ""
                        : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            }
            if (principal.getQuantityDelta() != null) {
                qtyPlain = principal.getQuantityDelta().abs()
                        .setScale(BROAD_QTY_SCALE, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString();
            }
        }
        Instant ts = left.getBlockTimestamp();
        if (right.getBlockTimestamp() != null
                && (ts == null || right.getBlockTimestamp().isBefore(ts))) {
            ts = right.getBlockTimestamp();
        }
        long epochSecond = ts == null ? 0L : ts.getEpochSecond();
        String payload = (uid == null ? "" : uid)
                + "|" + familyKey
                + "|" + qtyPlain
                + "|" + epochSecond;
        return REKEYED_CORRELATION_PREFIX + sha256Hex(payload);
    }

    static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String canonicalRoundtripCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String low = canonicalIdOrder(left.getId(), right.getId());
        String high = Objects.equals(low, left.getId()) ? nullSafeId(right.getId()) : nullSafeId(left.getId());
        return ROUNDTRIP_CORRELATION_PREFIX + low + "|" + high;
    }

    static String canonicalBundleCorrelationId(List<NormalizedTransaction> members) {
        List<String> ids = members.stream()
                .map(NormalizedTransaction::getId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return BUNDLE_CORRELATION_PREFIX + String.join("|", ids);
    }

    static String canonicalIdOrder(String leftId, String rightId) {
        String left = nullSafeId(leftId);
        String right = nullSafeId(rightId);
        return left.compareTo(right) <= 0 ? left : right;
    }

    static String nullSafeId(String id) {
        return id == null ? "" : id;
    }

    /**
     * Cycle/18 R9b: FA-001 on-chain↔Bybit deposit anchors must not be re-paired with Bybit stream
     * mirrors. The corridor correlation id is shared with an ON_CHAIN row, so it looks like a
     * Bybit-only singleton inside {@link #loadSingletons()}.
     */
    static boolean isFa001OnChainCorridorAnchor(NormalizedTransaction tx) {
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

    /**
     * CB-2 real-data fix (corridor basis conservation orphan, USDC $30.19 / LDO $6.17): a leg
     * already carrying a {@link BybitEarnPrincipalTransferPairer} correlation id must never be
     * re-claimed by {@link BybitInternalTransferPairer}'s repair passes.
     *
     * <p>{@code loadSingletons()} scopes its "already paired" count to {@code type =
     * INTERNAL_TRANSFER} rows only. An Earn co-event pair always has ONE {@code INTERNAL_TRANSFER}
     * leg (the {@code :UTA}/{@code :FUND} side) and ONE {@code EARN_FLEXIBLE_SAVING}/{@code
     * LENDING_*} leg (the {@code :EARN} side) sharing the SAME {@code bybit-earn-principal-v1:}
     * correlation id — so within the INTERNAL_TRANSFER-only population that id's count is always 1,
     * making the already-correctly-paired leg look like an unpaired singleton again. {@code
     * BybitNormalizationService.processNextBatch} runs {@link BybitInternalTransferPairer#repairAll()}
     * BEFORE {@link BybitEarnPrincipalTransferPairer#pairEarnPrincipalTransfers()} on every batch,
     * so on any LATER batch this repair pass would silently steal the leg back into an unrelated
     * {@code bybit-it-pair-v1:}/{@code bybit-it-roundtrip-v1:} correlation, permanently orphaning the
     * true Earn sibling (the real-data cause: replay #CB-2, confirmed on universe
     * {@code df5e69cc-a0c0-4910-8b7d-74488fa266e2}).</p>
     */
    static boolean isEarnPrincipalOwned(NormalizedTransaction tx) {
        if (tx == null) {
            return false;
        }
        String correlationId = tx.getCorrelationId();
        return correlationId != null && correlationId.startsWith(EARN_PRINCIPAL_CORRELATION_PREFIX);
    }
}
