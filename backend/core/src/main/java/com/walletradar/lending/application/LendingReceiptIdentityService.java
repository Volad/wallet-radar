package com.walletradar.lending.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.lending.persistence.LendingReceiptIdentityDocument;
import com.walletradar.lending.persistence.LendingReceiptIdentityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LendingReceiptIdentityService {

    private static final EnumSet<NormalizedTransactionType> LENDING_INDEX_TYPES = EnumSet.of(
            NormalizedTransactionType.LENDING_DEPOSIT,
            NormalizedTransactionType.LENDING_WITHDRAW,
            NormalizedTransactionType.LENDING_LOOP_OPEN,
            NormalizedTransactionType.LENDING_LOOP_REBALANCE,
            NormalizedTransactionType.LENDING_LOOP_DECREASE,
            NormalizedTransactionType.LENDING_LOOP_CLOSE,
            NormalizedTransactionType.BORROW,
            NormalizedTransactionType.REPAY,
            NormalizedTransactionType.VAULT_DEPOSIT,
            NormalizedTransactionType.VAULT_WITHDRAW
    );

    private final LendingReceiptIdentityRepository repository;
    private final ProtocolRegistryService protocolRegistryService;

    public Optional<LendingReceiptIdentity> resolve(NetworkId networkId, String contractAddress, String assetSymbol) {
        Optional<LendingReceiptIdentity> derived = resolveDerived(networkId, contractAddress);
        if (derived.isPresent()) {
            return derived;
        }
        Optional<LendingReceiptIdentity> registry = resolveRegistry(networkId, contractAddress, assetSymbol);
        if (registry.isPresent()) {
            return registry;
        }
        return resolveGrammar(assetSymbol);
    }

    public Optional<LendingReceiptIdentity> resolveGrammar(String assetSymbol) {
        if (!LendingAssetSymbolSupport.isLendingPositionSymbol(assetSymbol)) {
            return Optional.empty();
        }
        String protocol = LendingProtocolNameSupport.protocolFromAssetSymbol(assetSymbol).orElse(null);
        if (protocol == null) {
            return Optional.empty();
        }
        String side = LendingAssetSymbolSupport.isBorrowSymbol(assetSymbol) ? "BORROW" : "SUPPLY";
        return Optional.of(new LendingReceiptIdentity(
                protocol,
                LendingAssetSymbolSupport.underlyingSymbol(assetSymbol),
                side,
                "GRAMMAR"
        ));
    }

    public boolean isLendingPositionSymbol(NetworkId networkId, String contractAddress, String assetSymbol) {
        return resolve(networkId, contractAddress, assetSymbol).isPresent()
                || LendingAssetSymbolSupport.isLendingPositionSymbol(assetSymbol);
    }

    public String underlyingSymbol(NetworkId networkId, String contractAddress, String assetSymbol) {
        return resolve(networkId, contractAddress, assetSymbol)
                .map(LendingReceiptIdentity::underlyingSymbol)
                .orElseGet(() -> LendingAssetSymbolSupport.underlyingSymbol(assetSymbol));
    }

    public String lifecycleAsset(NetworkId networkId, String contractAddress, String assetSymbol) {
        return LendingAssetSymbolSupport.lifecycleAsset(
                resolve(networkId, contractAddress, assetSymbol)
                        .map(LendingReceiptIdentity::underlyingSymbol)
                        .orElseGet(() -> LendingAssetSymbolSupport.underlyingSymbol(assetSymbol))
        );
    }

    public Optional<String> protocolHint(NetworkId networkId, String contractAddress, String assetSymbol) {
        return resolve(networkId, contractAddress, assetSymbol).map(LendingReceiptIdentity::protocol);
    }

    public void indexTransaction(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getType() == null
                || !LENDING_INDEX_TYPES.contains(transaction.getType())
                || transaction.getNetworkId() == null) {
            return;
        }
        String protocol = canonicalProtocol(transaction);
        if (protocol == null) {
            return;
        }
        List<NormalizedTransaction.Flow> flows = transaction.getFlows() == null ? List.of() : transaction.getFlows();
        if (flows.isEmpty()) {
            return;
        }
        Instant seenAt = transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
        for (NormalizedTransaction.Flow receiptFlow : flows) {
            if (receiptFlow == null || receiptFlow.getAssetSymbol() == null) {
                continue;
            }
            if (!LendingAssetSymbolSupport.isLendingPositionSymbol(receiptFlow.getAssetSymbol())) {
                continue;
            }
            String receiptUnderlying = LendingAssetSymbolSupport.underlyingSymbol(receiptFlow.getAssetSymbol());
            String underlying = receiptGrammarUnderlying(receiptFlow.getAssetSymbol(), receiptUnderlying)
                    .or(() -> findPairedUnderlying(transaction.getType(), flows, receiptFlow))
                    .orElse(receiptUnderlying);
            String side = LendingAssetSymbolSupport.isBorrowSymbol(receiptFlow.getAssetSymbol()) ? "BORROW" : "SUPPLY";
            upsert(
                    transaction.getNetworkId(),
                    receiptFlow.getAssetContract(),
                    receiptFlow.getAssetSymbol(),
                    protocol,
                    underlying,
                    side,
                    "DERIVED_TX_PAIR",
                    transaction.getTxHash(),
                    seenAt
            );
        }
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null || flow.getAssetSymbol() == null || flow.getAssetContract() == null) {
                continue;
            }
            if (LendingAssetSymbolSupport.isLendingPositionSymbol(flow.getAssetSymbol())) {
                continue;
            }
            if (!isRegistryPoolContract(transaction.getNetworkId(), flow.getAssetContract())) {
                continue;
            }
            String side = switch (transaction.getType()) {
                case BORROW, LENDING_LOOP_OPEN -> "BORROW";
                case REPAY, LENDING_LOOP_CLOSE -> "BORROW";
                default -> "SUPPLY";
            };
            upsert(
                    transaction.getNetworkId(),
                    flow.getAssetContract(),
                    flow.getAssetSymbol(),
                    protocol,
                    LendingAssetSymbolSupport.underlyingSymbol(flow.getAssetSymbol()),
                    side,
                    "REGISTRY_POOL",
                    transaction.getTxHash(),
                    seenAt
            );
        }
    }

    private Optional<LendingReceiptIdentity> resolveDerived(NetworkId networkId, String contractAddress) {
        String normalizedContract = OnChainRawTransactionView.normalizeAddress(contractAddress);
        if (networkId == null || normalizedContract == null) {
            return Optional.empty();
        }
        return repository.findByNetworkIdAndContractAddress(networkId.name(), normalizedContract)
                .map(document -> new LendingReceiptIdentity(
                        document.getProtocol(),
                        document.getUnderlyingSymbol(),
                        document.getSide(),
                        document.getSource()
                ));
    }

    private Optional<LendingReceiptIdentity> resolveRegistry(
            NetworkId networkId,
            String contractAddress,
            String assetSymbol
    ) {
        String normalizedContract = OnChainRawTransactionView.normalizeAddress(contractAddress);
        if (networkId == null || normalizedContract == null) {
            return Optional.empty();
        }
        return protocolRegistryService.lookup(networkId, normalizedContract)
                .filter(entry -> isLendingRegistryEntry(entry))
                .map(entry -> new LendingReceiptIdentity(
                        LendingProtocolNameSupport.displayProtocol(entry.protocolName()),
                        LendingAssetSymbolSupport.underlyingSymbol(
                                assetSymbol == null || assetSymbol.isBlank() ? "UNKNOWN" : assetSymbol
                        ),
                        registrySide(entry),
                        "REGISTRY"
                ));
    }

    private void upsert(
            NetworkId networkId,
            String contractAddress,
            String assetSymbol,
            String protocol,
            String underlyingSymbol,
            String side,
            String source,
            String txHash,
            Instant seenAt
    ) {
        String normalizedContract = OnChainRawTransactionView.normalizeAddress(contractAddress);
        if (networkId == null || normalizedContract == null || protocol == null || underlyingSymbol == null) {
            return;
        }
        LendingReceiptIdentityDocument existing = repository
                .findByNetworkIdAndContractAddress(networkId.name(), normalizedContract)
                .orElse(null);
        Instant now = Instant.now();
        if (existing == null) {
            LendingReceiptIdentityDocument created = new LendingReceiptIdentityDocument();
            created.setId(networkId.name() + ":" + normalizedContract);
            created.setNetworkId(networkId.name());
            created.setContractAddress(normalizedContract);
            created.setAssetSymbol(assetSymbol);
            created.setProtocol(protocol);
            created.setUnderlyingSymbol(underlyingSymbol);
            created.setSide(side);
            created.setSource(source);
            created.setFirstSeenTxHash(txHash);
            created.setFirstSeenAt(seenAt);
            created.setUpdatedAt(now);
            repository.save(created);
            return;
        }
        boolean changed = false;
        if (!Objects.equals(existing.getProtocol(), protocol)) {
            existing.setProtocol(protocol);
            changed = true;
        }
        if (!Objects.equals(existing.getUnderlyingSymbol(), underlyingSymbol)) {
            existing.setUnderlyingSymbol(underlyingSymbol);
            changed = true;
        }
        if (!Objects.equals(existing.getSide(), side)) {
            existing.setSide(side);
            changed = true;
        }
        if (assetSymbol != null && !assetSymbol.isBlank() && !Objects.equals(existing.getAssetSymbol(), assetSymbol)) {
            existing.setAssetSymbol(assetSymbol);
            changed = true;
        }
        if (changed) {
            existing.setUpdatedAt(now);
            repository.save(existing);
        }
    }

    private static Optional<String> receiptGrammarUnderlying(String receiptSymbol, String receiptUnderlying) {
        if (receiptSymbol == null || receiptSymbol.isBlank() || receiptUnderlying == null || receiptUnderlying.isBlank()) {
            return Optional.empty();
        }
        String normalizedReceipt = LendingAssetSymbolSupport.normalizeSymbol(receiptSymbol);
        if (receiptUnderlying.equals(normalizedReceipt)) {
            return Optional.empty();
        }
        return Optional.of(receiptUnderlying);
    }

    private Optional<String> findPairedUnderlying(
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows,
            NormalizedTransaction.Flow receiptFlow
    ) {
        return flows.stream()
                .filter(candidate -> candidate != receiptFlow)
                .filter(candidate -> candidate.getAssetSymbol() != null && !candidate.getAssetSymbol().isBlank())
                .filter(candidate -> !LendingAssetSymbolSupport.isLendingPositionSymbol(candidate.getAssetSymbol()))
                .filter(candidate -> candidate.getQuantityDelta() != null && receiptFlow.getQuantityDelta() != null)
                .filter(candidate -> candidate.getQuantityDelta().signum() != receiptFlow.getQuantityDelta().signum())
                .map(candidate -> LendingAssetSymbolSupport.underlyingSymbol(candidate.getAssetSymbol()))
                .findFirst();
    }

    private String canonicalProtocol(NormalizedTransaction transaction) {
        String protocol = LendingProtocolNameSupport.displayProtocol(transaction.getProtocolName());
        if (LendingProtocolNameSupport.isKnownLendingProtocol(protocol)) {
            return protocol;
        }
        return LendingProtocolNameSupport.protocolFromAssetSymbol(
                transaction.getFlows() == null
                        ? ""
                        : transaction.getFlows().stream()
                        .map(NormalizedTransaction.Flow::getAssetSymbol)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("")
        ).orElse(null);
    }

    private boolean isRegistryPoolContract(NetworkId networkId, String contractAddress) {
        return protocolRegistryService.lookup(networkId, contractAddress)
                .filter(this::isLendingRegistryEntry)
                .isPresent();
    }

    private boolean isLendingRegistryEntry(ProtocolRegistryEntry entry) {
        if (entry == null || entry.protocolName() == null) {
            return false;
        }
        String protocol = entry.protocolName().toUpperCase(Locale.ROOT);
        return protocol.contains("AAVE")
                || protocol.contains("EULER")
                || protocol.contains("MORPHO")
                || protocol.contains("FLUID")
                || protocol.contains("COMPOUND")
                || protocol.contains("SILO");
    }

    private static String registrySide(ProtocolRegistryEntry entry) {
        if (entry.eventType() != null) {
            String eventType = entry.eventType().name();
            if (eventType.contains("BORROW") || eventType.contains("DEBT")) {
                return "BORROW";
            }
        }
        return "SUPPLY";
    }
}
