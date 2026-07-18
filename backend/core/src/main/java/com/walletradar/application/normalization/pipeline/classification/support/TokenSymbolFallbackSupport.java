package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Locale;

/**
 * Resolves display symbols and decimals for ERC-20 legs when explorer/RPC metadata is missing or blank.
 *
 * <p>The underlying data lives in {@code classpath:token-metadata.json} (group {@code fallbackTokens},
 * exposed via {@link TokenMetadataRegistry}) — a single source of truth shared with the rest of the
 * normalization layer. Two decimal semantics are maintained:
 * <ul>
 *   <li>fallback decimals ({@link TokenMetadataRegistry#fallbackDecimals}) — applied when the explorer
 *       provides no {@code tokenDecimal} field (i.e. the field is null or absent).</li>
 *   <li>authoritative decimal overrides ({@link TokenMetadataRegistry#decimalOverride}) — ALWAYS take
 *       precedence over the explorer-provided decimal. Use when the explorer (Etherscan / BlockScout)
 *       is known to report a wrong decimal for a specific contract.
 *       <p>Example — soUSDC "Silo Optima" ({@code 0x2514a2ce...}, Arbitrum): Etherscan incorrectly
 *       reports {@code tokenDecimal=6}. Using decimal=6 for raw value {@code 648487021674218} yields
 *       648,487,021.674 (clearly wrong for a ~$651 USDC withdrawal). The correct on-chain decimal for
 *       this vault share token is 12: 648487021674218 / 10^12 = 648.487 soUSDC shares, which correctly
 *       maps to 651.25 USDC at the vault's accumulated yield exchange rate. Override is mandatory
 *       because the explorer actively sends the wrong value — it is not absent.</p></li>
 * </ul>
 */
public final class TokenSymbolFallbackSupport {

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
        String known = TokenMetadataRegistry.fallbackSymbol(normalizedContract);
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
        return TokenMetadataRegistry.fallbackSymbol(contractAddress);
    }

    /**
     * Returns the known decimal precision for a contract address, or {@code null} if not in the static registry.
     * This is a <em>fallback</em> used only when the explorer provides no {@code tokenDecimal} field.
     */
    public static Integer resolveDecimalsByContract(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return TokenMetadataRegistry.fallbackDecimals(contractAddress);
    }

    /**
     * Returns the authoritative decimal override for a contract address, or {@code null} if no
     * override is registered. When non-null, this value ALWAYS replaces the explorer-provided
     * {@code tokenDecimal} — even if the explorer field is present — because the explorer is
     * known to report a wrong decimal for this contract.
     *
     * @see TokenMetadataRegistry#decimalOverride(String)
     */
    public static Integer resolveDecimalOverride(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return TokenMetadataRegistry.decimalOverride(contractAddress);
    }
}
