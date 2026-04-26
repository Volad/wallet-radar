package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves row-local counterpartyAddress from persisted raw evidence without using lifecycle links.
 */
@Service
public class CounterpartyResolutionService {

    private final ProtocolRegistryService protocolRegistryService;

    public CounterpartyResolutionService() {
        this(null);
    }

    @Autowired
    public CounterpartyResolutionService(@Nullable ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    public Optional<String> resolve(NormalizedTransaction normalizedTransaction, RawTransaction rawTransaction) {
        ResolvedCounterparty resolved = resolveMetadata(normalizedTransaction, rawTransaction);
        return resolved == null || !present(resolved.address()) ? Optional.empty() : Optional.of(resolved.address());
    }

    public ResolvedCounterparty resolveMetadata(NormalizedTransaction normalizedTransaction, RawTransaction rawTransaction) {
        if (normalizedTransaction == null || rawTransaction == null) {
            return ResolvedCounterparty.missingRaw();
        }

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        String walletAddress = view.walletAddress();
        NormalizedTransactionType type = normalizedTransaction.getType();
        if (type == null) {
            return terminalMissing("TRANSACTION_TYPE_MISSING");
        }

        Optional<String> resolvedAddress = switch (type) {
            case SWAP,
                    WRAP,
                    UNWRAP,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    BORROW,
                    REPAY,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW,
                    LP_ENTRY,
                    LP_EXIT,
                    LP_ENTRY_REQUEST,
                    LP_EXIT_REQUEST,
                    LP_ENTRY_SETTLEMENT,
                    LP_EXIT_SETTLEMENT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL,
                    LP_FEE_CLAIM,
                    REWARD_CLAIM,
                    STAKING_DEPOSIT,
                    STAKING_WITHDRAW,
                    STAKING_WITHDRAW_REQUEST,
                    PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW -> resolveInteractedContract(view, walletAddress)
                    .or(() -> resolveRegistryBackedContract(view, type, walletAddress));
            case BRIDGE_OUT -> resolveInteractedContract(view, walletAddress)
                    .or(() -> resolveRegistryBackedContract(view, type, walletAddress));
            case BRIDGE_IN -> resolveInteractedContract(view, walletAddress)
                    .or(() -> resolveRegistryBackedContract(view, type, walletAddress))
                    .or(() -> resolveUniqueInboundPeer(view, walletAddress));
            case EXTERNAL_TRANSFER_IN -> resolveUniqueInboundPeer(view, walletAddress)
                    .or(() -> resolveRegistryBackedContract(view, type, walletAddress));
            case EXTERNAL_TRANSFER_OUT -> resolveUniqueOutboundPeer(view, walletAddress)
                    .or(() -> resolveRegistryBackedContract(view, type, walletAddress));
            default -> Optional.empty();
        };
        if (resolvedAddress.isEmpty()) {
            return terminalMissing("NO_UNIQUE_ROW_LOCAL_COUNTERPARTY");
        }
        String address = resolvedAddress.orElseThrow();
        return new ResolvedCounterparty(
                address,
                classifyCounterpartyType(normalizedTransaction, view, address),
                MetadataResolutionState.RESOLVED_EXACT,
                "ROW_LOCAL_RAW_OR_REGISTRY_EVIDENCE"
        );
    }

    private String classifyCounterpartyType(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view,
            String address
    ) {
        String matchedCounterparty = normalizedTransaction.getMatchedCounterparty();
        if (present(matchedCounterparty) && matchedCounterparty.trim().toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return CounterpartyType.CEX;
        }
        NormalizedTransactionType type = normalizedTransaction.getType();
        if (type == NormalizedTransactionType.BRIDGE_IN || type == NormalizedTransactionType.BRIDGE_OUT) {
            return CounterpartyType.BRIDGE;
        }
        ProtocolRegistryEntry entry = protocolRegistryEntry(view, address).orElse(null);
        if (entry != null) {
            return isBridgeRole(entry.role()) ? CounterpartyType.BRIDGE : CounterpartyType.PROTOCOL;
        }
        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return CounterpartyType.UNKNOWN_EOA;
        }
        return CounterpartyType.PROTOCOL;
    }

    private Optional<ProtocolRegistryEntry> protocolRegistryEntry(OnChainRawTransactionView view, String address) {
        if (protocolRegistryService == null || view == null || view.networkId() == null || !present(address)) {
            return Optional.empty();
        }
        return protocolRegistryService.lookup(view.networkId(), address);
    }

    private boolean isBridgeRole(ProtocolRegistryRole role) {
        return role == ProtocolRegistryRole.BRIDGE_ENTRY || role == ProtocolRegistryRole.BRIDGE_EXIT;
    }

    private ResolvedCounterparty terminalMissing(String evidence) {
        return new ResolvedCounterparty(
                null,
                CounterpartyType.GENUINE_MISSING_SOURCE,
                MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING,
                evidence
        );
    }

    private Optional<String> resolveInteractedContract(OnChainRawTransactionView view, String walletAddress) {
        String toAddress = view.interactionToAddress();
        if (present(toAddress) && !sameAddress(toAddress, walletAddress)) {
            return Optional.of(toAddress);
        }
        String contractAddress = view.contractAddress();
        if (present(contractAddress) && !sameAddress(contractAddress, walletAddress)) {
            return Optional.of(contractAddress);
        }
        return Optional.empty();
    }

    private Optional<String> resolveUniqueInboundPeer(OnChainRawTransactionView view, String walletAddress) {
        Set<String> peers = new LinkedHashSet<>();
        for (Document transfer : view.explorerTokenTransfers()) {
            if (sameAddress(view.tokenTransferTo(transfer), walletAddress)) {
                addPeer(peers, view.tokenTransferFrom(transfer), walletAddress);
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (sameAddress(view.internalTransferTo(transfer), walletAddress)) {
                addPeer(peers, view.internalTransferFrom(transfer), walletAddress);
            }
        }
        if (peers.size() == 1) {
            return Optional.of(peers.iterator().next());
        }
        String fromAddress = view.fromAddress();
        if (peers.isEmpty() && present(fromAddress) && !sameAddress(fromAddress, walletAddress)) {
            return Optional.of(fromAddress);
        }
        return Optional.empty();
    }

    private Optional<String> resolveUniqueOutboundPeer(OnChainRawTransactionView view, String walletAddress) {
        Set<String> peers = new LinkedHashSet<>();
        for (Document transfer : view.explorerTokenTransfers()) {
            if (sameAddress(view.tokenTransferFrom(transfer), walletAddress)) {
                addPeer(peers, view.tokenTransferTo(transfer), walletAddress);
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (sameAddress(view.internalTransferFrom(transfer), walletAddress)) {
                addPeer(peers, view.internalTransferTo(transfer), walletAddress);
            }
        }
        if (peers.size() == 1) {
            return Optional.of(peers.iterator().next());
        }
        String toAddress = view.toAddress();
        if (peers.isEmpty() && present(toAddress) && !sameAddress(toAddress, walletAddress)) {
            return Optional.of(toAddress);
        }
        return Optional.empty();
    }

    private Optional<String> resolveRegistryBackedContract(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            String walletAddress
    ) {
        if (protocolRegistryService == null || view == null || view.networkId() == null) {
            return Optional.empty();
        }

        Set<String> candidates = new LinkedHashSet<>();
        addRegistryCandidate(candidates, view.interactionToAddress(), view, type, walletAddress);
        addRegistryCandidate(candidates, view.contractAddress(), view, type, walletAddress);
        addRegistryCandidate(candidates, view.fromAddress(), view, type, walletAddress);
        for (Document log : view.persistedLogs()) {
            addRegistryCandidate(candidates, log == null ? null : String.valueOf(log.get("address")), view, type, walletAddress);
        }
        return candidates.size() == 1 ? Optional.of(candidates.iterator().next()) : Optional.empty();
    }

    private void addRegistryCandidate(
            Set<String> candidates,
            String candidate,
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            String walletAddress
    ) {
        String normalized = OnChainRawTransactionView.normalizeAddress(candidate);
        if (!present(normalized) || sameAddress(normalized, walletAddress)) {
            return;
        }
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(view.networkId(), normalized);
        if (entry.isEmpty() || !isRoleRelevant(type, entry.orElseThrow().role())) {
            return;
        }
        candidates.add(normalized);
    }

    private boolean isRoleRelevant(NormalizedTransactionType type, ProtocolRegistryRole role) {
        if (type == null || role == null) {
            return false;
        }
        return switch (type) {
            case SWAP, WRAP, UNWRAP -> role == ProtocolRegistryRole.ROUTER
                    || role == ProtocolRegistryRole.EXCHANGE_ROUTER
                    || role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.POSITION_MANAGER
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT;
            case BRIDGE_OUT, BRIDGE_IN, EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT -> role == ProtocolRegistryRole.BRIDGE_ENTRY
                    || role == ProtocolRegistryRole.BRIDGE_EXIT
                    || role == ProtocolRegistryRole.ROUTER
                    || role == ProtocolRegistryRole.EXCHANGE_ROUTER
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.REWARD_ROUTER;
            case LENDING_DEPOSIT, LENDING_WITHDRAW, BORROW, REPAY -> role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN;
            case VAULT_DEPOSIT, VAULT_WITHDRAW -> role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.FACTORY
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN;
            case LP_ENTRY, LP_EXIT, LP_ENTRY_REQUEST, LP_EXIT_REQUEST, LP_ENTRY_SETTLEMENT, LP_EXIT_SETTLEMENT,
                    LP_EXIT_PARTIAL, LP_EXIT_FINAL, LP_FEE_CLAIM, LP_POSITION_STAKE, LP_POSITION_UNSTAKE -> role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.POSITION_MANAGER
                    || role == ProtocolRegistryRole.FACTORY
                    || role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.REWARD_ROUTER;
            case STAKING_DEPOSIT, STAKING_WITHDRAW, STAKING_WITHDRAW_REQUEST -> role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN;
            case REWARD_CLAIM -> role == ProtocolRegistryRole.REWARD_ROUTER
                    || role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.POOL;
            default -> false;
        };
    }

    private void addPeer(Set<String> peers, String candidate, String walletAddress) {
        if (!present(candidate) || sameAddress(candidate, walletAddress)) {
            return;
        }
        peers.add(candidate);
    }

    private boolean sameAddress(String left, String right) {
        return present(left) && present(right) && left.equalsIgnoreCase(right);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record ResolvedCounterparty(
            String address,
            String counterpartyType,
            String resolutionState,
            String evidence
    ) {
        static ResolvedCounterparty missingRaw() {
            return new ResolvedCounterparty(
                    null,
                    CounterpartyType.GENUINE_MISSING_SOURCE,
                    MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING,
                    "RAW_TRANSACTION_MISSING"
            );
        }
    }
}
