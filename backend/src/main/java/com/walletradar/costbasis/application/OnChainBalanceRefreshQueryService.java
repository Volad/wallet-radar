package com.walletradar.costbasis.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds a bounded live-balance refresh universe from confirmed on-chain canonical rows.
 */
@Service
@RequiredArgsConstructor
public class OnChainBalanceRefreshQueryService {

    private final MongoOperations mongoOperations;
    private final TrackedWalletRepository trackedWalletRepository;

    public List<BalanceRefreshCandidate> loadCandidates() {
        List<String> trackedWallets = trackedWalletRepository.findAllByOrderByAddressAsc().stream()
                .map(TrackedWallet::getAddress)
                .filter(Objects::nonNull)
                .toList();
        if (trackedWallets.isEmpty()) {
            return List.of();
        }

        Criteria scopeCriteria = new Criteria().andOperator(
                Criteria.where("walletAddress").in(trackedWallets),
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").is(NormalizedTransactionStatus.CONFIRMED),
                Criteria.where("networkId").exists(true).ne(null),
                new Criteria().orOperator(
                        Criteria.where("excludedFromAccounting").exists(false),
                        Criteria.where("excludedFromAccounting").ne(true)
                )
        );
        Criteria assetCriteria = new Criteria().orOperator(
                Criteria.where("flows.assetContract").exists(true).ne(null),
                Criteria.where("flows.assetSymbol").exists(true).ne(null)
        );

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(scopeCriteria),
                Aggregation.unwind("flows"),
                Aggregation.match(assetCriteria),
                Aggregation.group("walletAddress", "networkId", "flows.assetSymbol", "flows.assetContract")
                        .first("walletAddress").as("walletAddress")
                        .first("networkId").as("networkId")
                        .first("flows.assetSymbol").as("assetSymbol")
                        .first("flows.assetContract").as("assetContract"),
                Aggregation.project("walletAddress", "networkId", "assetSymbol", "assetContract"),
                Aggregation.sort(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("assetSymbol"),
                        Sort.Order.asc("assetContract")
                ))
        );

        return mongoOperations.aggregate(aggregation, NormalizedTransaction.class, Document.class)
                .getMappedResults()
                .stream()
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .toList();
    }

    private BalanceRefreshCandidate toCandidate(Document document) {
        if (document == null) {
            return null;
        }
        String walletAddress = normalizedString(document.get("walletAddress"));
        String networkRaw = normalizedString(document.get("networkId"));
        NetworkId networkId = parseNetworkId(networkRaw);
        if (walletAddress == null || networkId == null || networkId == NetworkId.SOLANA) {
            return null;
        }
        return new BalanceRefreshCandidate(
                walletAddress,
                networkId,
                normalizedSymbol(document.get("assetSymbol")),
                normalizedString(document.get("assetContract"))
        );
    }

    private NetworkId parseNetworkId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NetworkId.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizedString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizedSymbol(Object value) {
        String normalized = normalizedString(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    public record BalanceRefreshCandidate(
            String walletAddress,
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
    }
}
