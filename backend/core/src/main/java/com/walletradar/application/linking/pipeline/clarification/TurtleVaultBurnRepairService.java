package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Synthesizes the missing vault-token burn flow on Turtle Finance USDC Vault VAULT_WITHDRAW
 * transactions on Avalanche.
 *
 * <p>The Turtle Finance USDC vault uses ERC4626-style {@code redeem(uint256 shares, address receiver,
 * address owner)} (method ID {@code 0xba087652}). Unlike standard ERC4626 implementations it does
 * <em>not</em> emit an ERC20 Transfer burn event for the vault token, so the Etherscan API only
 * returns the USDC inflow — the {@code turtleAvalancheUSDC} debit leg is absent from normalized
 * flows.
 *
 * <p>Without the vault-token OUT leg the AVCO replay cannot read the vault-token position AVCO
 * to transfer cost basis to the returned USDC. This service synthesizes that missing leg by
 * parsing the {@code shares} argument from the raw calldata and inserting a
 * {@code turtleAvalancheUSDC} TRANSFER flow with {@code quantityDelta = -shares} at position 0.
 * The replay engine will then derive {@code REALLOCATE_OUT} for the vault token and
 * {@code REALLOCATE_IN} for USDC using the standard flow-sign logic, restoring correct basis
 * carry without any accounting-layer changes.
 *
 * <p>The service is idempotent: if a {@code turtleAvalancheUSDC} flow is already present it
 * skips the transaction. Parsing failures are logged and skipped gracefully.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TurtleVaultBurnRepairService {

    static final String TURTLE_VAULT_ADDRESS = "0x3048925b3ea5a8c12eecccb8810f5f7544db54af";
    static final String VAULT_TOKEN_SYMBOL = "turtleAvalancheUSDC";
    static final int VAULT_TOKEN_DECIMALS = 18;
    static final String REDEEM_METHOD_ID = "0xba087652";

    private static final String CP_TYPE_PROTOCOL = "PROTOCOL";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    /**
     * Repairs up to {@code batchSize} VAULT_WITHDRAW transactions that are missing the
     * vault-token burn flow. Returns the number of transactions that were updated.
     */
    public int repairMissingVaultTokenBurn(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (repairIfMissingBurn(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("TURTLE_VAULT_BURN_REPAIR repaired={} batch={}", dirty.size(), candidates.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.VAULT_WITHDRAW),
                Criteria.where("flows.counterpartyAddress").is(TURTLE_VAULT_ADDRESS),
                Criteria.where("flows.assetSymbol").not().regex(VAULT_TOKEN_SYMBOL, "i"),
                Criteria.where("flows.quantityDelta").gt(BigDecimal.ZERO)
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean repairIfMissingBurn(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getTxHash() == null || tx.getNetworkId() == null) {
            return false;
        }
        if (alreadyHasVaultTokenFlow(tx)) {
            return false;
        }
        RawTransaction raw = findRaw(tx);
        if (raw == null || raw.getRawData() == null) {
            log.warn("TURTLE_VAULT_BURN_REPAIR no raw transaction found txHash={} networkId={}",
                    tx.getTxHash(), tx.getNetworkId());
            return false;
        }
        BigDecimal shares = parseShares(raw, tx.getTxHash());
        if (shares == null) {
            return false;
        }
        return applyVaultTokenBurnFlow(tx, shares, now);
    }

    boolean alreadyHasVaultTokenFlow(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return false;
        }
        return tx.getFlows().stream()
                .filter(f -> f != null && f.getAssetSymbol() != null)
                .anyMatch(f -> f.getAssetSymbol().equalsIgnoreCase(VAULT_TOKEN_SYMBOL));
    }

    BigDecimal parseShares(RawTransaction raw, String txHashForLog) {
        String input = extractInput(raw);
        if (input == null || input.isBlank()) {
            log.warn("TURTLE_VAULT_BURN_REPAIR missing rawData.input txHash={}", txHashForLog);
            return null;
        }
        String normalized = input.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(REDEEM_METHOD_ID.toLowerCase(Locale.ROOT))) {
            log.warn("TURTLE_VAULT_BURN_REPAIR unexpected method selector input={} txHash={}",
                    normalized.length() > 20 ? normalized.substring(0, 20) : normalized, txHashForLog);
            return null;
        }
        // "0x" (2 chars) + method selector (8 chars) = 10 chars to skip
        // next 64 hex chars = 32 bytes = uint256 shares
        int dataStart = 10;
        int dataEnd = dataStart + 64;
        if (normalized.length() < dataEnd) {
            log.warn("TURTLE_VAULT_BURN_REPAIR input too short length={} txHash={}",
                    normalized.length(), txHashForLog);
            return null;
        }
        String sharesHex = normalized.substring(dataStart, dataEnd);
        try {
            BigInteger sharesBig = new BigInteger(sharesHex, 16);
            return new BigDecimal(sharesBig).movePointLeft(VAULT_TOKEN_DECIMALS);
        } catch (NumberFormatException e) {
            log.warn("TURTLE_VAULT_BURN_REPAIR failed to parse shares hex={} txHash={} error={}",
                    sharesHex, txHashForLog, e.getMessage());
            return null;
        }
    }

    private String extractInput(RawTransaction raw) {
        if (raw.getRawData() == null) {
            return null;
        }
        // rawData.input is the top-level calldata field
        Object inputField = raw.getRawData().get("input");
        if (inputField instanceof String s) {
            return s;
        }
        // some Etherscan payloads nest it under rawData.explorer.input
        org.bson.Document explorer = raw.getRawData().get("explorer", org.bson.Document.class);
        if (explorer != null) {
            Object explorerInput = explorer.get("input");
            if (explorerInput instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private boolean applyVaultTokenBurnFlow(NormalizedTransaction tx, BigDecimal shares, Instant now) {
        NormalizedTransaction.Flow burnFlow = new NormalizedTransaction.Flow();
        burnFlow.setRole(NormalizedLegRole.TRANSFER);
        burnFlow.setAssetSymbol(VAULT_TOKEN_SYMBOL);
        burnFlow.setAssetContract(TURTLE_VAULT_ADDRESS);
        burnFlow.setQuantityDelta(shares.negate());
        burnFlow.setCounterpartyAddress(TURTLE_VAULT_ADDRESS);
        burnFlow.setCounterpartyType(CP_TYPE_PROTOCOL);
        burnFlow.setIsInferred(true);
        burnFlow.setInferenceReason("VAULT_TOKEN_BURN_SYNTHESIZED_FROM_CALLDATA");

        List<NormalizedTransaction.Flow> flows = tx.getFlows();
        if (flows == null) {
            flows = new ArrayList<>();
            tx.setFlows(flows);
        }
        // insert at position 0 so the vault-token OUT precedes the USDC IN
        flows.add(0, burnFlow);
        tx.setUpdatedAt(now);
        return true;
    }

    private RawTransaction findRaw(NormalizedTransaction tx) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("txHash").is(tx.getTxHash()),
                Criteria.where("networkId").is(tx.getNetworkId().name())
        ));
        query.limit(1);
        List<RawTransaction> results = mongoOperations.find(query, RawTransaction.class);
        return results.isEmpty() ? null : results.getFirst();
    }
}
