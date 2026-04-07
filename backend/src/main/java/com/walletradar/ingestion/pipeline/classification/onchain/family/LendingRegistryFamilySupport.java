package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.support.DirectMethodIdSupport;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

final class LendingRegistryFamilySupport {

    private LendingRegistryFamilySupport() {
    }

    static NormalizedTransactionType resolveLendingPoolType(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            ProtocolResourceCatalog protocolResourceCatalog
    ) {
        NormalizedTransactionType resourceType = protocolResourceCatalog
                .find(entry.protocolName(), entry.protocolVersion())
                .map(resource -> resolveLendingPoolTypeFromResource(view, resource))
                .orElse(null);
        if (resourceType != null) {
            return resourceType;
        }

        NormalizedTransactionType selectorType = DirectMethodIdSupport.resolveType(view.methodId());
        if (selectorType == NormalizedTransactionType.LENDING_DEPOSIT
                || selectorType == NormalizedTransactionType.LENDING_WITHDRAW
                || selectorType == NormalizedTransactionType.BORROW
                || selectorType == NormalizedTransactionType.REPAY) {
            return selectorType;
        }

        String functionName = view.functionName();
        if (containsAny(functionName, "withdraw", "redeem")) {
            return NormalizedTransactionType.LENDING_WITHDRAW;
        }
        if (containsAny(functionName, "deposit", "supply")) {
            return NormalizedTransactionType.LENDING_DEPOSIT;
        }
        if (functionName != null && functionName.contains("borrow")) {
            return NormalizedTransactionType.BORROW;
        }
        if (functionName != null && functionName.contains("repay")) {
            return NormalizedTransactionType.REPAY;
        }
        return null;
    }

    private static NormalizedTransactionType resolveLendingPoolTypeFromResource(
            OnChainRawTransactionView view,
            ProtocolResourceDefinition resource
    ) {
        if (resource.matchesMethodSelector("lendingDeposit", view.methodId())
                || resource.matchesFunctionMarker("lendingDeposit", view.functionName())) {
            return NormalizedTransactionType.LENDING_DEPOSIT;
        }
        if (resource.matchesMethodSelector("lendingWithdraw", view.methodId())
                || resource.matchesFunctionMarker("lendingWithdraw", view.functionName())) {
            return NormalizedTransactionType.LENDING_WITHDRAW;
        }
        if (resource.matchesMethodSelector("borrow", view.methodId())
                || resource.matchesFunctionMarker("borrow", view.functionName())) {
            return NormalizedTransactionType.BORROW;
        }
        if (resource.matchesMethodSelector("repay", view.methodId())
                || resource.matchesFunctionMarker("repay", view.functionName())) {
            return NormalizedTransactionType.REPAY;
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
