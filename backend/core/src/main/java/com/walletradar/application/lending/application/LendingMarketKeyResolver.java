package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class LendingMarketKeyResolver {

    private static final String EULER_EVC_SINGLETON = "0xddcbe30a761edd2e19bba930a977475265f36fa1";

    private final LendingReceiptIdentityService receiptIdentityService;

    public String marketAssetFromTransaction(NormalizedTransaction transaction, String protocol) {
        String normalizedProtocol = normalizeProtocol(protocol);
        if (normalizedProtocol.startsWith("AAVE")) {
            return "account-pool";
        }
        if (normalizedProtocol.startsWith("COMPOUND")) {
            return "comet-base-market";
        }
        Optional<String> receiptMarketAsset = resolveReceiptContractFromTransaction(
                        transaction,
                        preferredReceiptSide(transaction)
                )
                .or(() -> fallbackVaultContract(transaction, normalizedProtocol))
                .map(contract -> encodeContractMarketAsset(normalizedProtocol, contract));
        if (receiptMarketAsset.isPresent()) {
            return receiptMarketAsset.get();
        }
        if (transaction.getType() != null && transaction.getType().name().startsWith("LENDING_LOOP")) {
            return normalizedProtocol.startsWith("EULER") ? "evk-loop-account" : "loop-account";
        }
        return defaultMarketAsset(normalizedProtocol);
    }

    public String marketAssetFromBalance(String protocol, NetworkId networkId, OnChainBalance balance) {
        String normalizedProtocol = normalizeProtocol(protocol);
        if (normalizedProtocol.startsWith("AAVE")) {
            return "account-pool";
        }
        if (normalizedProtocol.startsWith("COMPOUND")) {
            return "comet-base-market";
        }
        if (balance == null) {
            return defaultMarketAsset(normalizedProtocol);
        }
        String contract = OnChainRawTransactionView.normalizeAddress(balance.getAssetContract());
        String symbol = balance.getAssetSymbol();
        if (contract != null
                && receiptIdentityService.isLendingPositionSymbol(networkId, contract, symbol)
                && !isBorrowReceipt(networkId, contract, symbol)) {
            return encodeContractMarketAsset(normalizedProtocol, contract);
        }
        if (contract != null
                && !contract.isBlank()
                && requiresContractMarketAsset(normalizedProtocol)
                && !LendingAssetSymbolSupport.isBorrowSymbol(symbol)) {
            return encodeContractMarketAsset(normalizedProtocol, contract);
        }
        return defaultMarketAsset(normalizedProtocol);
    }

    private Optional<String> resolveReceiptContractFromTransaction(
            NormalizedTransaction transaction,
            String preferredSide
    ) {
        if (transaction == null || transaction.getNetworkId() == null) {
            return Optional.empty();
        }
        NetworkId networkId = transaction.getNetworkId();
        List<NormalizedTransaction.Flow> flows = safeFlows(transaction);
        return flows.stream()
                .filter(flow -> flow != null && flow.getAssetSymbol() != null && !flow.getAssetSymbol().isBlank())
                .filter(flow -> matchesReceiptSide(networkId, flow, preferredSide))
                .sorted(receiptFlowOrder())
                .map(flow -> OnChainRawTransactionView.normalizeAddress(flow.getAssetContract()))
                .filter(contract -> contract != null && !contract.isBlank())
                .findFirst();
    }

    private Optional<String> fallbackVaultContract(NormalizedTransaction transaction, String normalizedProtocol) {
        if (transaction == null || !requiresContractMarketAsset(normalizedProtocol)) {
            return Optional.empty();
        }
        Set<String> underlyingContracts = underlyingFlowContracts(transaction);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(OnChainRawTransactionView.normalizeAddress(transaction.getMatchedCounterparty()));
        candidates.add(OnChainRawTransactionView.normalizeAddress(transaction.getCounterpartyAddress()));
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (isExcludedRouterAddress(candidate) || underlyingContracts.contains(candidate)) {
                continue;
            }
            if (receiptIdentityService.resolve(transaction.getNetworkId(), candidate, "").isPresent()) {
                return Optional.of(candidate);
            }
            if (isPlausibleVaultContract(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Set<String> underlyingFlowContracts(NormalizedTransaction transaction) {
        LinkedHashSet<String> contracts = new LinkedHashSet<>();
        if (transaction == null || transaction.getNetworkId() == null) {
            return contracts;
        }
        NetworkId networkId = transaction.getNetworkId();
        for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
            if (flow == null || flow.getAssetSymbol() == null) {
                continue;
            }
            String contract = OnChainRawTransactionView.normalizeAddress(flow.getAssetContract());
            if (contract == null || contract.isBlank()) {
                continue;
            }
            if (!receiptIdentityService.isLendingPositionSymbol(networkId, contract, flow.getAssetSymbol())) {
                contracts.add(contract);
            }
        }
        return contracts;
    }

    private boolean matchesReceiptSide(NetworkId networkId, NormalizedTransaction.Flow flow, String preferredSide) {
        String contract = OnChainRawTransactionView.normalizeAddress(flow.getAssetContract());
        String symbol = flow.getAssetSymbol();
        if (!receiptIdentityService.isLendingPositionSymbol(networkId, contract, symbol)) {
            return false;
        }
        return receiptIdentityService.resolve(networkId, contract, symbol)
                .map(identity -> preferredSide.equals(identity.side()))
                .orElseGet(() -> {
                    boolean borrow = LendingAssetSymbolSupport.isBorrowSymbol(symbol);
                    return "BORROW".equals(preferredSide) == borrow;
                });
    }

    private boolean isBorrowReceipt(NetworkId networkId, String contract, String symbol) {
        if (LendingAssetSymbolSupport.isBorrowSymbol(symbol)) {
            return true;
        }
        return receiptIdentityService.resolve(networkId, contract, symbol)
                .map(identity -> "BORROW".equals(identity.side()))
                .orElse(false);
    }

    private static Comparator<NormalizedTransaction.Flow> receiptFlowOrder() {
        return Comparator
                .comparing(
                        (NormalizedTransaction.Flow flow) -> flow.getQuantityDelta() == null
                                ? BigDecimal.ZERO
                                : flow.getQuantityDelta().abs(),
                        Comparator.reverseOrder()
                )
                .thenComparing(
                        flow -> OnChainRawTransactionView.normalizeAddress(flow.getAssetContract()),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                );
    }

    private static String preferredReceiptSide(NormalizedTransaction transaction) {
        NormalizedTransactionType type = transaction == null ? null : transaction.getType();
        if (type == null) {
            return "SUPPLY";
        }
        return switch (type) {
            case BORROW, REPAY, LENDING_LOOP_OPEN -> "BORROW";
            default -> "SUPPLY";
        };
    }

    private static String encodeContractMarketAsset(String normalizedProtocol, String contract) {
        String normalizedContract = OnChainRawTransactionView.normalizeAddress(contract);
        if (normalizedContract == null || normalizedContract.length() < 10) {
            return defaultMarketAsset(normalizedProtocol);
        }
        String prefix = normalizedContract.substring(2, 10);
        if (normalizedProtocol.startsWith("EULER")) {
            return "evk-vault-" + prefix;
        }
        if (normalizedProtocol.startsWith("FLUID") || normalizedProtocol.startsWith("MORPHO")) {
            return "vault-" + prefix;
        }
        return prefix;
    }

    private static String defaultMarketAsset(String normalizedProtocol) {
        if (normalizedProtocol.startsWith("EULER")) {
            return "evk-account";
        }
        if (normalizedProtocol.startsWith("FLUID")) {
            return "vault-account";
        }
        return "account-pool";
    }

    private static boolean requiresContractMarketAsset(String normalizedProtocol) {
        return normalizedProtocol.startsWith("EULER")
                || normalizedProtocol.startsWith("FLUID")
                || normalizedProtocol.startsWith("MORPHO");
    }

    private static boolean isExcludedRouterAddress(String address) {
        return EULER_EVC_SINGLETON.equalsIgnoreCase(address);
    }

    private static boolean isPlausibleVaultContract(String address) {
        return address != null
                && address.matches("^0x[a-f0-9]{40}$")
                && !address.matches("^0x[e]{40}$")
                && !address.matches("^0x0{40}$");
    }

    private static String normalizeProtocol(String protocol) {
        return protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
    }

    private static List<NormalizedTransaction.Flow> safeFlows(NormalizedTransaction transaction) {
        return transaction == null || transaction.getFlows() == null ? List.of() : transaction.getFlows();
    }
}
