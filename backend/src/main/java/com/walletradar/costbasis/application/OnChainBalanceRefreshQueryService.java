package com.walletradar.costbasis.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        return loadCandidates(trackedWallets);
    }

    public List<BalanceRefreshCandidate> loadCandidates(List<String> walletAddresses) {
        List<String> trackedWallets = walletAddresses == null
                ? List.of()
                : walletAddresses.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
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
                        .first("flows.assetContract").as("assetContract")
                        .sum("flows.quantityDelta").as("netQuantity"),
                Aggregation.match(Criteria.where("netQuantity").ne(BigDecimal.ZERO)),
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

    public Map<TokenContractRef, Integer> loadKnownTokenDecimals(
            Collection<TokenContractRef> tokenContracts,
            String sessionId
    ) {
        Map<TokenContractRef, Integer> decimals = new LinkedHashMap<>();
        Set<TokenContractRef> refs = tokenContracts == null
                ? Set.of()
                : tokenContracts.stream()
                .filter(Objects::nonNull)
                .map(ref -> new TokenContractRef(ref.networkId(), normalizeContract(ref.assetContract())))
                .filter(ref -> ref.networkId() != null && ref.assetContract() != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (refs.isEmpty()) {
            return decimals;
        }

        loadKnownTokenDecimalsFromBalances(refs, sessionId, decimals);
        loadKnownTokenDecimalsFromRawEvidence(refs, decimals);
        return decimals;
    }

    private void loadKnownTokenDecimalsFromBalances(
            Set<TokenContractRef> refs,
            String sessionId,
            Map<TokenContractRef, Integer> decimals
    ) {
        List<String> contracts = refs.stream().map(TokenContractRef::assetContract).distinct().toList();
        List<NetworkId> networks = refs.stream().map(TokenContractRef::networkId).distinct().toList();
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("networkId").in(networks),
                Criteria.where("assetContract").in(contracts),
                Criteria.where("tokenDecimals").exists(true).ne(null)
        );
        if (sessionId != null && !sessionId.isBlank()) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("sessionId").is(sessionId));
        }
        for (Document balance : mongoOperations.find(
                org.springframework.data.mongodb.core.query.Query.query(criteria),
                Document.class,
                "on_chain_balances"
        )) {
            NetworkId networkId = parseNetworkId(normalizedString(balance.get("networkId")));
            String contract = normalizeContract(balance.getString("assetContract"));
            Integer tokenDecimals = parseInteger(balance.get("tokenDecimals"));
            if (networkId != null && contract != null && tokenDecimals != null) {
                decimals.putIfAbsent(new TokenContractRef(networkId, contract), tokenDecimals);
            }
        }
    }

    private void loadKnownTokenDecimalsFromRawEvidence(
            Set<TokenContractRef> refs,
            Map<TokenContractRef, Integer> decimals
    ) {
        Set<TokenContractRef> missing = refs.stream()
                .filter(ref -> !decimals.containsKey(ref))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (missing.isEmpty()) {
            return;
        }
        List<String> contracts = missing.stream().map(TokenContractRef::assetContract).distinct().toList();
        List<String> networks = missing.stream().map(ref -> ref.networkId().name()).distinct().toList();
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("networkId").in(networks),
                new Criteria().orOperator(
                        Criteria.where("rawData.explorer.tokenTransfers.contractAddress").in(contracts),
                        Criteria.where("clarificationEvidence.transfers.tokenTransfers.contractAddress").in(contracts)
                )
        );
        org.springframework.data.mongodb.core.query.Query query =
                org.springframework.data.mongodb.core.query.Query.query(criteria);
        query.fields()
                .include("networkId")
                .include("rawData.explorer.tokenTransfers")
                .include("clarificationEvidence.transfers.tokenTransfers");
        query.limit(Math.max(500, missing.size() * 20));
        for (RawTransaction rawTransaction : mongoOperations.find(query, RawTransaction.class)) {
            NetworkId networkId = parseNetworkId(rawTransaction.getNetworkId());
            if (networkId == null) {
                continue;
            }
            collectDecimalsFromTransfers(networkId, transfersAt(rawTransaction.getRawData(), "explorer"), missing, decimals);
            Document clarificationEvidence = rawTransaction.getClarificationEvidence();
            Document transfers = clarificationEvidence == null
                    ? null
                    : clarificationEvidence.get("transfers", Document.class);
            collectDecimalsFromTransfers(networkId, transfers, missing, decimals);
            if (decimals.keySet().containsAll(missing)) {
                return;
            }
        }
    }

    private void collectDecimalsFromTransfers(
            NetworkId networkId,
            Document container,
            Set<TokenContractRef> refs,
            Map<TokenContractRef, Integer> decimals
    ) {
        if (container == null) {
            return;
        }
        Object rawTransfers = container.get("tokenTransfers");
        if (!(rawTransfers instanceof List<?> transfers)) {
            return;
        }
        for (Object transfer : transfers) {
            if (!(transfer instanceof Document document)) {
                continue;
            }
            String contract = normalizeContract(document.getString("contractAddress"));
            Integer tokenDecimals = parseInteger(document.get("tokenDecimal"));
            TokenContractRef ref = new TokenContractRef(networkId, contract);
            if (contract != null && tokenDecimals != null && refs.contains(ref)) {
                decimals.putIfAbsent(ref, tokenDecimals);
            }
        }
    }

    private Document transfersAt(Document rawData, String key) {
        return rawData == null ? null : rawData.get(key, Document.class);
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

    private String normalizeContract(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record BalanceRefreshCandidate(
            String walletAddress,
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
    }

    public record TokenContractRef(
            NetworkId networkId,
            String assetContract
    ) {
    }
}
