package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;
import java.util.Set;

/**
 * Shared accounting-family identity contract for continuity-preserving replay.
 */
public final class AccountingAssetFamilySupport {

    private static final Set<String> ETH_FAMILY_SYMBOLS = Set.of(
            "ETH",
            "WETH",
            "AETHWETH",
            "AARBWETH",
            "ALINWETH",
            "AMANWETH",
            "AZKSWETH",
            "VBETH"
    );

    private AccountingAssetFamilySupport() {
    }

    public static String continuityIdentity(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        String symbol = normalizeSymbol(flow.getAssetSymbol());
        if (ETH_FAMILY_SYMBOLS.contains(symbol)) {
            return "FAMILY:ETH";
        }
        if ("USDC".equals(symbol) || "VBUSDC".equals(symbol)) {
            return "FAMILY:USDC";
        }
        String contract = normalizeContract(flow.getAssetContract());
        if (contract != null) {
            return contract;
        }
        return symbol.isBlank() ? null : "SYMBOL:" + symbol;
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
