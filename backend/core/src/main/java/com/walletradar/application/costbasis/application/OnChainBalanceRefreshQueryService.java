package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.support.WalletAddressReadScope;
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
        // Family-aware canonicalization: EVM/CEX lowercased (legacy behavior), but case-sensitive
        // base58 Solana and friendly TON refs are preserved so the walletAddress $in query matches
        // the canonically-stored normalized rows (blind lowercasing dropped every SOL/TON candidate).
        List<String> trackedWallets = walletAddresses == null
                ? List.of()
                : walletAddresses.stream()
                .filter(Objects::nonNull)
                .map(WalletAddressReadScope::normalize)
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

        List<BalanceRefreshCandidate> flowCandidates = mongoOperations
                .aggregate(aggregation, NormalizedTransaction.class, Document.class)
                .getMappedResults()
                .stream()
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .toList();

        // ADR-080 (C3, Precondition A): also emit a candidate per known LP-receipt identity from
        // `lp_receipt_basis_pools`. A fully-burned/exited fungible receipt (PENDLE-LPT, Meteora MLP,
        // fungible CL receipt) nets to zero across its flows, so the flow-derived universe above drops
        // it (netQuantity == 0 filter) — leaving ABSENCE, which the closure cross-check must not treat
        // as an authoritative zero. Probing these identities on the background refresh writes an
        // explicit on_chain_balances row (0 = authoritative zero, distinct from a missing row), which
        // is what lets the closure resolver tell "burned ⇒ closed" apart from "never observed".
        List<BalanceRefreshCandidate> lpReceiptCandidates = loadLpReceiptBasisPoolCandidates(trackedWallets);
        return mergeDistinct(flowCandidates, lpReceiptCandidates);
    }

    /**
     * ADR-080 (C3): the LP-receipt basis-pool identities scoped to the tracked wallets, as balance
     * refresh candidates. Read-only projection over {@code lp_receipt_basis_pools}; only fungible
     * receipts carrying a real on-chain token identity (non-blank {@code assetContract} or a concrete
     * {@code assetSymbol}) are emitted, so NFT-position pools (resolved via {@code ownerOf}/burn, C4)
     * are not probed as ERC-20 balances here.
     */
    private List<BalanceRefreshCandidate> loadLpReceiptBasisPoolCandidates(List<String> trackedWallets) {
        if (trackedWallets.isEmpty()) {
            return List.of();
        }
        org.springframework.data.mongodb.core.query.Query query =
                org.springframework.data.mongodb.core.query.Query.query(
                        Criteria.where("walletAddress").in(trackedWallets));
        query.fields()
                .include("walletAddress")
                .include("networkId")
                .include("assetSymbol")
                .include("assetContract");
        List<BalanceRefreshCandidate> candidates = new java.util.ArrayList<>();
        for (LpReceiptBasisPool pool : mongoOperations.find(query, LpReceiptBasisPool.class)) {
            if (pool == null || pool.getWalletAddress() == null || pool.getNetworkId() == null) {
                continue;
            }
            String assetContract = normalizedString(pool.getAssetContract());
            String assetSymbol = normalizedSymbol(pool.getAssetSymbol());
            if (assetContract == null && assetSymbol == null) {
                continue;
            }
            candidates.add(new BalanceRefreshCandidate(
                    pool.getWalletAddress().trim(),
                    pool.getNetworkId(),
                    assetSymbol,
                    assetContract
            ));
        }
        return candidates;
    }

    private List<BalanceRefreshCandidate> mergeDistinct(
            List<BalanceRefreshCandidate> primary,
            List<BalanceRefreshCandidate> additional
    ) {
        Map<String, BalanceRefreshCandidate> byKey = new LinkedHashMap<>();
        for (BalanceRefreshCandidate candidate : primary) {
            byKey.putIfAbsent(candidateKey(candidate), candidate);
        }
        for (BalanceRefreshCandidate candidate : additional) {
            byKey.putIfAbsent(candidateKey(candidate), candidate);
        }
        return List.copyOf(byKey.values());
    }

    private String candidateKey(BalanceRefreshCandidate candidate) {
        return candidate.walletAddress()
                + "|" + candidate.networkId()
                + "|" + (candidate.assetSymbol() == null ? "" : candidate.assetSymbol())
                + "|" + (candidate.assetContract() == null ? "" : candidate.assetContract());
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
        if (walletAddress == null || networkId == null) {
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
