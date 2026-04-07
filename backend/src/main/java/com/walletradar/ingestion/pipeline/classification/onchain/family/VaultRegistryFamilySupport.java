package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

final class VaultRegistryFamilySupport {

    private VaultRegistryFamilySupport() {
    }

    static NormalizedTransactionType resolveVaultType(OnChainRawTransactionView view) {
        String functionName = view.functionName();
        if (containsAny(functionName, "joinpool", "join")) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (containsAny(functionName, "exitpool", "exit")) {
            return NormalizedTransactionType.LP_EXIT;
        }
        if (containsAny(functionName, "deposit", "supply")) {
            return NormalizedTransactionType.VAULT_DEPOSIT;
        }
        if (containsAny(functionName, "withdraw", "redeem")) {
            return NormalizedTransactionType.VAULT_WITHDRAW;
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
