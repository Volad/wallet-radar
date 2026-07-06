package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionWalletAdjacencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * BR-1: corrects {@code BRIDGE_OUT}/{@code BRIDGE_IN} legs whose principal-flow counterparty is a
 * different own/member wallet of the same accounting universe. Such transfers are own-wallet moves,
 * not third-party bridge handoffs, so they must be {@code INTERNAL_TRANSFER} (continuity carry),
 * never a monetary bridge disposal.
 *
 * <p>The rule is fully generalized: membership is decided via
 * {@link AccountingUniverseService#shareUniverseMembers(String, String)} and session adjacency, not
 * any hardcoded address. After reclassification the established same-tx / cross-network internal
 * pairing passes link the reciprocal leg.</p>
 *
 * <p>WS-3b extension: also handles {@code EXTERNAL_TRANSFER_OUT}/{@code EXTERNAL_TRANSFER_IN} rows
 * whose {@code counterpartyAddress = MULTI} (caused by FEE leg counted as a counterparty before the
 * ADR-032 fix). When the principal-flow counterparty resolves to another own/member wallet, the row
 * is reclassified to {@code INTERNAL_TRANSFER} and the transaction-level counterparty is stamped.
 * This pass is idempotent: the query matches only rows still carrying {@code counterpartyAddress=MULTI}
 * with the relevant transfer types.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OwnWalletBridgeMistypeCorrectionService {

    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x[a-f0-9]{40}$");
    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final SessionWalletAdjacencyService sessionWalletAdjacencyService;

    public int reclassifyOwnWalletBridgeMistypes(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(
                        NormalizedTransactionType.BRIDGE_OUT,
                        NormalizedTransactionType.BRIDGE_IN
                ),
                Criteria.where("walletAddress").regex("^0x[a-fA-F0-9]{40}$")
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (reclassifyIfOwnWalletCounterparty(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("OWN_WALLET_BRIDGE_MISTYPE_CORRECTION candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    /**
     * WS-3b: repairs legacy rows where {@code counterpartyAddress=MULTI} was caused by a FEE leg
     * being counted as a counterparty (root cause fixed by ADR-032 / WS-3a). Queries for
     * EXTERNAL_TRANSFER_OUT/IN rows with MULTI counterparty and reclassifies those whose principal
     * flow resolves to another own/member wallet.
     */
    public int reclassifyMultiCpOwnWalletTransfers(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                ),
                Criteria.where("counterpartyAddress").is(FlowCounterpartySupport.MULTI_COUNTERPARTY),
                Criteria.where("walletAddress").regex("^0x[a-fA-F0-9]{40}$")
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (reclassifyMultiCpIfOwnWallet(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("MULTI_CP_OWN_WALLET_CORRECTION candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    boolean reclassifyIfOwnWalletCounterparty(NormalizedTransaction transaction, Instant now) {
        if (transaction == null
                || transaction.getType() == null
                || !hasHexAddress(transaction.getWalletAddress())) {
            return false;
        }
        int direction = transaction.getType() == NormalizedTransactionType.BRIDGE_OUT ? -1 : 1;
        Optional<NormalizedTransaction.Flow> principal =
                BridgePairLinkSupport.selectPrimaryPrincipalFlow(transaction, direction);
        if (principal.isEmpty()) {
            return false;
        }
        String counterparty = principal.get().getCounterpartyAddress();
        if (!hasHexAddress(counterparty)) {
            return false;
        }
        if (!bothEndpointsOwnWallets(transaction.getWalletAddress(), counterparty)) {
            return false;
        }
        transaction.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        boolean changed = true;
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
        }
        // Demote principal flows to TRANSFER and strip price so AVCO replay carries basis instead
        // of realizing a spurious disposal/acquisition; reuse the shared bridge-continuity retag.
        BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(transaction, now);
        removeMissingReason(transaction, BRIDGE_MISSING_REASON);
        transaction.setUpdatedAt(now);
        return changed;
    }

    private boolean reclassifyMultiCpIfOwnWallet(NormalizedTransaction transaction, Instant now) {
        if (transaction == null
                || transaction.getType() == null
                || !hasHexAddress(transaction.getWalletAddress())) {
            return false;
        }
        // Resolve unique non-fee, non-synthetic counterparty addresses from principal flows
        String resolvedCp = resolveSingleConcreteCounterparty(transaction);
        if (!hasHexAddress(resolvedCp)) {
            return false;
        }
        if (!bothEndpointsOwnWallets(transaction.getWalletAddress(), resolvedCp)) {
            return false;
        }
        transaction.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        transaction.setCounterpartyAddress(resolvedCp);
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
        }
        BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(transaction, now);
        transaction.setUpdatedAt(now);
        return true;
    }

    /**
     * Finds a single concrete EVM counterparty address from non-FEE principal flows, excluding
     * synthetic {@code UNKNOWN:*} placeholders. Returns {@code null} when zero or multiple distinct
     * concrete addresses are present (genuine multi-counterparty case).
     */
    static String resolveSingleConcreteCounterparty(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        String found = null;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            if (flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            String cp = flow.getCounterpartyAddress();
            if (cp == null || cp.isBlank()) {
                continue;
            }
            String cpNorm = cp.trim();
            if (cpNorm.toUpperCase(Locale.ROOT).startsWith("UNKNOWN:")) {
                continue;
            }
            if (found == null) {
                found = cpNorm.toLowerCase(Locale.ROOT);
            } else if (!found.equalsIgnoreCase(cpNorm)) {
                // Multiple distinct concrete counterparties — genuine multi
                return null;
            }
        }
        return found;
    }

    private boolean bothEndpointsOwnWallets(String walletAddress, String counterpartyAddress) {
        if (walletAddress == null || counterpartyAddress == null) {
            return false;
        }
        if (walletAddress.trim().equalsIgnoreCase(counterpartyAddress.trim())) {
            // Degenerate self-loop: not a counterparty pair; leave classification untouched.
            return false;
        }
        return accountingUniverseService.shareUniverseMembers(walletAddress, counterpartyAddress)
                || sessionWalletAdjacencyService.anySessionListsBothAddresses(walletAddress, counterpartyAddress);
    }

    private static void removeMissingReason(NormalizedTransaction transaction, String reason) {
        List<String> reasons = transaction.getMissingDataReasons();
        if (reasons == null || reasons.isEmpty()) {
            return;
        }
        reasons.removeIf(reason::equals);
    }

    private static boolean hasHexAddress(String value) {
        return value != null
                && !value.isBlank()
                && HEX_ADDRESS.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
