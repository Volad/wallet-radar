package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;
import java.util.Map;

/**
 * Shared accounting-family identity contract for continuity-preserving replay.
 */
public final class AccountingAssetFamilySupport {

    private static final Map<String, String> SYMBOL_FAMILIES = Map.ofEntries(
            Map.entry("BTC", "FAMILY:BTC"),
            Map.entry("WBTC", "FAMILY:BTC"),
            Map.entry("AARBWBTC", "FAMILY:BTC"),
            Map.entry("AETHWBTC", "FAMILY:BTC"),
            Map.entry("ALINWBTC", "FAMILY:BTC"),
            Map.entry("AMANWBTC", "FAMILY:BTC"),
            Map.entry("AZKSWBTC", "FAMILY:BTC"),
            Map.entry("ETH", "FAMILY:ETH"),
            Map.entry("WETH", "FAMILY:ETH"),
            Map.entry("AETHWETH", "FAMILY:ETH"),
            Map.entry("AARBWETH", "FAMILY:ETH"),
            Map.entry("ALINWETH", "FAMILY:ETH"),
            Map.entry("AMANWETH", "FAMILY:ETH"),
            Map.entry("AZKSWETH", "FAMILY:ETH"),
            Map.entry("VBETH", "FAMILY:ETH"),
            Map.entry("STETH", "FAMILY:ETH"),
            Map.entry("WSTETH", "FAMILY:ETH"),
            Map.entry("RETH", "FAMILY:ETH"),
            Map.entry("CBETH", "FAMILY:ETH"),
            Map.entry("EETH", "FAMILY:ETH"),
            Map.entry("WEETH", "FAMILY:ETH"),
            Map.entry("EZETH", "FAMILY:ETH"),
            Map.entry("RSETH", "FAMILY:ETH"),
            Map.entry("OSETH", "FAMILY:ETH"),
            Map.entry("METH", "FAMILY:ETH"),
            Map.entry("AVAX", "FAMILY:AVAX"),
            Map.entry("WAVAX", "FAMILY:AVAX"),
            Map.entry("SAVAX", "FAMILY:AVAX"),
            Map.entry("AAVAWAVAX", "FAMILY:AVAX"),
            Map.entry("AAVASAVAX", "FAMILY:AVAX"),
            Map.entry("MNT", "FAMILY:MNT"),
            Map.entry("WMNT", "FAMILY:MNT"),
            Map.entry("AMANMNT", "FAMILY:MNT"),
            Map.entry("USDC", "FAMILY:USDC"),
            Map.entry("VBUSDC", "FAMILY:USDC"),
            Map.entry("USDBC", "FAMILY:USDC")
    );

    private AccountingAssetFamilySupport() {
    }

    public static String continuityIdentity(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        return continuityIdentity(flow.getAssetSymbol(), flow.getAssetContract());
    }

    public static String continuityIdentity(String assetSymbol, String assetContract) {
        String symbol = normalizeSymbol(assetSymbol);
        String familyIdentity = SYMBOL_FAMILIES.get(symbol);
        if (familyIdentity != null) {
            return familyIdentity;
        }
        String contract = normalizeContract(assetContract);
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
