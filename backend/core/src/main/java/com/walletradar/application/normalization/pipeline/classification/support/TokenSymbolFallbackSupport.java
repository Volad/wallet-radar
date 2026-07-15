package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves display symbols and decimals for ERC-20 legs when explorer/RPC metadata is missing or blank.
 *
 * <p>Two decimal maps are maintained:
 * <ul>
 *   <li>{@link #KNOWN_CONTRACT_DECIMALS} — fallback-only, applied when the explorer provides no
 *       {@code tokenDecimal} field (i.e. the field is null or absent).</li>
 *   <li>{@link #CONTRACT_DECIMAL_OVERRIDES} — authoritative overrides that ALWAYS take precedence
 *       over the explorer-provided decimal. Use when the explorer (Etherscan / BlockScout) is known
 *       to report a wrong decimal for a specific contract.</li>
 * </ul>
 */
public final class TokenSymbolFallbackSupport {

    private static final Map<String, String> KNOWN_CONTRACT_SYMBOLS = Map.ofEntries(
            Map.entry(
                    "0x39de0f00189306062d79edec6dca5bb6bfd108f9",
                    "eUSDC-2"
            ),
            Map.entry("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC"),  // USDC on BASE
            Map.entry("0x4200000000000000000000000000000000000006", "WETH"),   // WETH on BASE
            Map.entry("0x2514a2ce842705ead703d02fabfd8250bfcfb8bd", "soUSDC") // Silo Optima soUSDC on ARBITRUM
    );

    private static final Map<String, Integer> KNOWN_CONTRACT_DECIMALS = Map.ofEntries(
            Map.entry("0x39de0f00189306062d79edec6dca5bb6bfd108f9", 6),   // eUSDC-2 on BASE
            Map.entry("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", 6),   // USDC on BASE
            Map.entry("0x4200000000000000000000000000000000000006", 18)    // WETH on BASE
    );

    /**
     * Authoritative decimal overrides for contracts where the explorer API (Etherscan/BlockScout)
     * reports an incorrect {@code tokenDecimal}. Entries in this map ALWAYS replace the
     * explorer-provided decimal — even if the field is present.
     *
     * <p>soUSDC "Silo Optima" ({@code 0x2514a2ce...}, Arbitrum): Etherscan incorrectly reports
     * {@code tokenDecimal=6}. Using decimal=6 for raw value {@code 648487021674218} yields
     * 648,487,021.674 (clearly wrong for a ~$651 USDC withdrawal). The correct on-chain decimal
     * for this vault share token is 12: 648487021674218 / 10^12 = 648.487 soUSDC shares, which
     * correctly maps to 651.25 USDC at the vault's accumulated yield exchange rate. Override is
     * mandatory because the explorer actively sends the wrong value — it is not absent.</p>
     */
    private static final Map<String, Integer> CONTRACT_DECIMAL_OVERRIDES = Map.ofEntries(
            Map.entry("0x2514a2ce842705ead703d02fabfd8250bfcfb8bd", 12)   // soUSDC "Silo Optima" on ARBITRUM
    );

    private TokenSymbolFallbackSupport() {
    }

    public static String resolve(String assetContract, String assetSymbol) {
        if (assetSymbol != null && !assetSymbol.isBlank()) {
            return assetSymbol.trim();
        }
        if (assetContract == null || assetContract.isBlank()) {
            return assetSymbol;
        }
        String normalizedContract = assetContract.trim().toLowerCase(Locale.ROOT);
        String known = KNOWN_CONTRACT_SYMBOLS.get(normalizedContract);
        if (known != null) {
            return known;
        }
        String suffix = normalizedContract.length() >= 6
                ? normalizedContract.substring(normalizedContract.length() - 6)
                : normalizedContract;
        return "ERC20:" + suffix;
    }

    /**
     * Returns the known symbol for a contract address, or {@code null} if not in the static registry.
     */
    public static String resolveSymbolByContract(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return KNOWN_CONTRACT_SYMBOLS.get(contractAddress.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the known decimal precision for a contract address, or {@code null} if not in the static registry.
     * This is a <em>fallback</em> used only when the explorer provides no {@code tokenDecimal} field.
     */
    public static Integer resolveDecimalsByContract(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return KNOWN_CONTRACT_DECIMALS.get(contractAddress.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the authoritative decimal override for a contract address, or {@code null} if no
     * override is registered. When non-null, this value ALWAYS replaces the explorer-provided
     * {@code tokenDecimal} — even if the explorer field is present — because the explorer is
     * known to report a wrong decimal for this contract.
     *
     * @see #CONTRACT_DECIMAL_OVERRIDES
     */
    public static Integer resolveDecimalOverride(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return CONTRACT_DECIMAL_OVERRIDES.get(contractAddress.trim().toLowerCase(Locale.ROOT));
    }
}
