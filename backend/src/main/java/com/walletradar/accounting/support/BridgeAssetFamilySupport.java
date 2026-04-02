package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;

/**
 * Conservative bridge-family identity mapping used by continuity logic.
 */
public final class BridgeAssetFamilySupport {

    private BridgeAssetFamilySupport() {
    }

    public static String continuityIdentity(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        String symbol = normalizeSymbol(flow.getAssetSymbol());
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
