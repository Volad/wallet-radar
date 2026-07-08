package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Links GMX LP_EXIT_SETTLEMENT rows (raw hex correlationIds) to their paired
 * LP_EXIT_REQUEST using the GM/GLV market token contract address as the join key.
 *
 * <p>After classification, LP_EXIT_REQUEST stores the outgoing GM/GLV token in
 * {@code flows[].assetContract}. The LP_EXIT_SETTLEMENT receives WETH/USDC from
 * the <em>same</em> GM/GLV market contract, visible as {@code flows[].counterpartyAddress}.
 * Matching on wallet + market contract produces a deterministic one-to-one link.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmxExitSettlementLinkService {

    private static final String GMX_LP_PREFIX = "gmx-lp:";
    private static final String UNKNOWN_CP_PREFIX = "UNKNOWN:";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int linkOutstandingSettlements(int batchSize) {
        List<NormalizedTransaction> settlements = loadUnlinkedSettlements(batchSize);
        if (settlements.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (NormalizedTransaction settlement : settlements) {
            String wallet = settlement.getWalletAddress();
            if (wallet == null || wallet.isBlank()) {
                continue;
            }

            // Extract the GM/GLV market contract addresses from the settlement's inbound flows.
            // These are the addresses that sent WETH/USDC back to the user — they are the
            // GM or GLV market token contracts.
            Set<String> marketContracts = extractMarketContracts(settlement);
            if (marketContracts.isEmpty()) {
                continue;
            }

            // Find the LP_EXIT_REQUEST for the same wallet whose outgoing GM/GLV flow has
            // one of the extracted market contract addresses.
            NormalizedTransaction request = findMatchingRequest(wallet, marketContracts);
            if (request == null || request.getCorrelationId() == null) {
                continue;
            }

            settlement.setCorrelationId(request.getCorrelationId());
            settlement.setUpdatedAt(now);
            dirty.add(settlement);
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("GMX_EXIT_SETTLEMENT_LINK linked={}", dirty.size());
        }
        return dirty.size();
    }

    /**
     * Extracts the unique non-composite counterparty addresses from inbound (positive delta)
     * flows of the settlement. These correspond to the GM/GLV market token contracts that
     * released the underlying assets (WETH/USDC) back to the user.
     */
    private static Set<String> extractMarketContracts(NormalizedTransaction settlement) {
        Set<String> contracts = new LinkedHashSet<>();
        if (settlement.getFlows() == null) {
            return contracts;
        }
        for (NormalizedTransaction.Flow flow : settlement.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            String cp = flow.getCounterpartyAddress();
            if (cp == null || cp.isBlank() || cp.startsWith(UNKNOWN_CP_PREFIX)) {
                continue;
            }
            contracts.add(cp.toLowerCase(Locale.ROOT));
        }
        return contracts;
    }

    private List<NormalizedTransaction> loadUnlinkedSettlements(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.LP_EXIT_SETTLEMENT),
                Criteria.where("protocolName").regex("^gmx", "i"),
                Criteria.where("correlationId").exists(true).ne(null).ne(""),
                Criteria.where("correlationId").not().regex("^" + GMX_LP_PREFIX)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private NormalizedTransaction findMatchingRequest(String walletAddress, Set<String> marketContracts) {
        // Load LP_EXIT_REQUESTs for this wallet that have a resolved gmx-lp: correlationId.
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.LP_EXIT_REQUEST),
                Criteria.where("correlationId").regex("^" + GMX_LP_PREFIX),
                Criteria.where("walletAddress").is(walletAddress)
        ));
        List<NormalizedTransaction> requests = mongoOperations.find(query, NormalizedTransaction.class);

        for (NormalizedTransaction request : requests) {
            if (request.getFlows() == null) {
                continue;
            }
            for (NormalizedTransaction.Flow flow : request.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
                    continue;  // only outgoing legs
                }
                String contract = flow.getAssetContract();
                if (contract != null && marketContracts.contains(contract.toLowerCase(Locale.ROOT))) {
                    return request;
                }
            }
        }
        return null;
    }
}
