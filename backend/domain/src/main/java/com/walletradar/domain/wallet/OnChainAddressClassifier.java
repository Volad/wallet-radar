package com.walletradar.domain.wallet;

import com.walletradar.domain.common.ton.TonAddressCanonicalizer;

import java.util.Locale;

/**
 * Pure grammar-level classifier for wallet addresses.
 *
 * <p>Absorbs the 0x/TON/Solana heuristics previously scattered across
 * {@link com.walletradar.domain.common.NetworkAddressFormat} and
 * {@code AccountingUniverseService#normalizeWalletAddress}.
 * No Spring context, no port dependencies.</p>
 *
 * <p>CEX addresses follow the grammar {@code PROVIDER:uid[:SUBACCOUNT]} (case-insensitive prefix,
 * at least one colon).</p>
 */
public final class OnChainAddressClassifier {

    private OnChainAddressClassifier() {
    }

    /**
     * Classifies a raw wallet address or CEX wallet-ref string into a {@link WalletDomainKind}.
     *
     * <ul>
     *   <li>EVM — starts with {@code 0x} / {@code 0X}</li>
     *   <li>TON — recognised by {@link TonAddressCanonicalizer#looksLikeTon}</li>
     *   <li>CEX — contains {@code :} and the prefix before the first colon is non-empty
     *             (i.e. {@code PROVIDER:uid} grammar)</li>
     *   <li>SOLANA — everything else (base-58 public key)</li>
     * </ul>
     */
    public static WalletDomainKind classifyDomain(String address) {
        if (address == null || address.isBlank()) {
            return WalletDomainKind.SOLANA;
        }
        String trimmed = address.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return WalletDomainKind.EVM;
        }
        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return WalletDomainKind.TON;
        }
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            return WalletDomainKind.CEX;
        }
        return WalletDomainKind.SOLANA;
    }

    /**
     * Normalises a raw address to a canonical form consistent with how it would be stored as a
     * universe member ref. CEX refs are returned as-is (provider prefix upper-cased, uid trimmed).
     */
    public static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String trimmed = address.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
        }
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon).toUpperCase(Locale.ROOT);
            String rest = trimmed.substring(colon + 1);
            return prefix + ":" + rest.trim();
        }
        return trimmed;
    }
}
