package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * B-EARN-DEPOSIT-MISSING: synthesises missing EARN-account counterpart for Bybit "On-chain Earn
 * subscription" FUND outflows where Bybit's API did not emit the matching EARN inflow event.
 *
 * <p>Normal Bybit Flexible Savings flows are recorded on both sides (FUND debit +
 * EARN_FLEXIBLE_SAVING credit), which the {@code BybitStreamAuthorityCollapser} pairs into a
 * {@code bybit-collapsed-v1:} corrId. For certain "On-chain Earn" subscriptions the EARN-side
 * FUNDING_HISTORY event is absent from the ingested data. The FUND outflow then carries basis out
 * of the accounting universe with no matching CARRY_IN at EARN.
 *
 * <p>This service detects FUND {@code INTERNAL_TRANSFER} legs with a blank {@code correlationId}
 * and no existing EARN-side counterpart (same uid, same asset family, same |qty| ±ε, within ±6h),
 * then synthesises a matching EARN {@code INTERNAL_TRANSFER} and assigns a shared deterministic
 * {@code bybit-earn-onchain-v1:} corrId to both. The replay engine then routes both legs to the
 * same {@code corr-family} queue, correctly emitting CARRY_OUT at FUND and CARRY_IN at EARN.
 *
 * <p>Idempotent: skips candidates whose synthetic partner already exists by deterministic ID.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitOnChainEarnOrphanRepairService {

    static final String EARN_ONCHAIN_CORR_PREFIX = "bybit-earn-onchain-v1:";
    static final String SYNTHETIC_ID_PREFIX = "bybit-earn-onchain-synthetic-v1:";
    private static final int QTY_SCALE = 8;
    private static final Duration EARN_COUNTERPART_WINDOW = Duration.ofHours(6);
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.00000001");

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int repairOrphans() {
        List<NormalizedTransaction> candidates = loadFundOrphans();
        if (candidates.isEmpty()) {
            return 0;
        }

        List<NormalizedTransaction> allEarnLegs = loadEarnInternalTransfers();

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;

        for (NormalizedTransaction fund : candidates) {
            NormalizedTransaction.Flow principal = principalFlow(fund);
            if (principal == null || principal.getQuantityDelta() == null
                    || principal.getQuantityDelta().signum() >= 0) {
                continue;
            }

            String uid = extractBybitUid(fund.getWalletAddress());
            if (uid == null) {
                continue;
            }

            String assetSymbol = principal.getAssetSymbol();
            String assetContract = principal.getAssetContract();
            BigDecimal absQty = principal.getQuantityDelta().abs();
            String assetFamily = assetFamily(principal);

            // Skip if an EARN counterpart already exists (either fully paired or just present).
            if (earnCounterpartExists(allEarnLegs, uid, assetFamily, absQty, fund.getBlockTimestamp())) {
                continue;
            }

            String syntheticId = syntheticId(fund.getId());
            // Idempotency: skip if synthetic already persisted from a prior run.
            if (syntheticExists(syntheticId)) {
                continue;
            }

            String corrId = corrId(fund.getId(), assetFamily, absQty);

            NormalizedTransaction synthetic = buildSyntheticEarnTransaction(
                    syntheticId, uid, assetSymbol, assetContract, absQty,
                    fund.getBlockTimestamp(), corrId, fund.getWalletAddress(), now
            );

            fund.setCorrelationId(corrId);
            fund.setMatchedCounterparty(earnWallet(uid));
            fund.setContinuityCandidate(true);
            fund.setUpdatedAt(now);

            dirty.add(fund);
            dirty.add(synthetic);
            repaired++;
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_EARN_ONCHAIN_ORPHAN_REPAIR candidates={} repaired={}", candidates.size(), repaired);
        }
        return repaired;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    private List<NormalizedTransaction> loadFundOrphans() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is("")
                )
        ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(tx -> isFundAccount(tx.getWalletAddress()))
                .toList();
    }

    private List<NormalizedTransaction> loadEarnInternalTransfers() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(tx -> isEarnAccount(tx.getWalletAddress()))
                .toList();
    }

    private boolean syntheticExists(String syntheticId) {
        return mongoOperations.exists(
                Query.query(Criteria.where("_id").is(syntheticId)),
                NormalizedTransaction.class
        );
    }

    // -------------------------------------------------------------------------
    // Counterpart matching
    // -------------------------------------------------------------------------

    private boolean earnCounterpartExists(
            List<NormalizedTransaction> earnLegs,
            String uid,
            String assetFamily,
            BigDecimal absQty,
            Instant fundTimestamp
    ) {
        for (NormalizedTransaction earn : earnLegs) {
            if (!uid.equals(extractBybitUid(earn.getWalletAddress()))) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(earn);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!assetFamily.equals(assetFamily(flow))) {
                continue;
            }
            if (flow.getQuantityDelta().subtract(absQty).abs().compareTo(QTY_TOLERANCE) > 0) {
                continue;
            }
            if (fundTimestamp != null && earn.getBlockTimestamp() != null) {
                Duration drift = Duration.between(fundTimestamp, earn.getBlockTimestamp()).abs();
                if (drift.compareTo(EARN_COUNTERPART_WINDOW) > 0) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Synthetic transaction construction
    // -------------------------------------------------------------------------

    private static NormalizedTransaction buildSyntheticEarnTransaction(
            String id,
            String uid,
            String assetSymbol,
            String assetContract,
            BigDecimal absQty,
            Instant timestamp,
            String corrId,
            String fundWallet,
            Instant now
    ) {
        String earnWallet = earnWallet(uid);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(assetSymbol);
        flow.setAssetContract(assetContract);
        flow.setQuantityDelta(absQty);
        flow.setAccountRef(earnWallet);

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setWalletAddress(earnWallet);
        tx.setBlockTimestamp(timestamp);
        tx.setCorrelationId(corrId);
        tx.setMatchedCounterparty(fundWallet);
        tx.setContinuityCandidate(true);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        return tx;
    }

    // -------------------------------------------------------------------------
    // ID / corrId derivation
    // -------------------------------------------------------------------------

    private static String syntheticId(String fundId) {
        return SYNTHETIC_ID_PREFIX + sha256Hex(fundId == null ? "" : fundId);
    }

    private static String corrId(String fundId, String assetFamily, BigDecimal absQty) {
        String qtyPlain = absQty.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        String payload = (fundId == null ? "" : fundId) + "|" + assetFamily + "|" + qtyPlain;
        return EARN_ONCHAIN_CORR_PREFIX + sha256Hex(payload);
    }

    // -------------------------------------------------------------------------
    // Wallet helpers
    // -------------------------------------------------------------------------

    private static boolean isFundAccount(String walletAddress) {
        return walletAddress != null
                && walletAddress.toUpperCase(Locale.ROOT).endsWith(":FUND");
    }

    private static boolean isEarnAccount(String walletAddress) {
        return walletAddress != null
                && walletAddress.toUpperCase(Locale.ROOT).endsWith(":EARN");
    }

    private static String earnWallet(String uid) {
        return "BYBIT:" + uid + ":EARN";
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
    }

    // -------------------------------------------------------------------------
    // Flow helpers
    // -------------------------------------------------------------------------

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

    private static String assetFamily(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return "?";
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (family != null) {
            return family;
        }
        return flow.getAssetSymbol() == null ? "?" : flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
