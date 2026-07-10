package com.walletradar.domain.wallet;

import com.walletradar.domain.common.ton.TonAddressCanonicalizer;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable value object representing a canonical wallet reference.
 *
 * <p>Parses the uniform grammar used throughout WalletRadar:
 * <ul>
 *   <li><b>On-chain EVM:</b> {@code 0x<hex>} — domain=EVM, uid=lowercase address</li>
 *   <li><b>On-chain TON:</b> friendly {@code UQ…}/{@code EQ…} or raw {@code workchain:hex}
 *       — domain=TON, uid=preferredMemberRef</li>
 *   <li><b>On-chain Solana:</b> base-58 public key — domain=SOLANA, uid=address</li>
 *   <li><b>CEX:</b> {@code PROVIDER:uid} or {@code PROVIDER:uid:SUBACCOUNT}
 *       — domain=CEX, venueId=lowercase provider, uid=uid, subAccount=SUBACCOUNT</li>
 * </ul>
 *
 * <p>Pure parser: no Spring context, no ports, no side effects.</p>
 */
public final class WalletRef {

    private final WalletDomainKind domain;
    /** Venue id for CEX wallets (e.g. {@code bybit}, {@code dzengi}); {@code null} for on-chain. */
    private final String venueId;
    /** The core identifier: lowercased EVM address, Solana pubkey, preferred TON ref, or CEX uid. */
    private final String uid;
    /** Sub-account suffix for CEX (e.g. {@code FUND}, {@code UTA}, {@code EARN}); {@code null} if absent. */
    private final String subAccount;
    /** Canonical serialised form as stored in {@code walletAddress} / member-ref columns. */
    private final String canonicalRef;

    private WalletRef(WalletDomainKind domain, String venueId, String uid, String subAccount, String canonicalRef) {
        this.domain = Objects.requireNonNull(domain);
        this.venueId = venueId;
        this.uid = Objects.requireNonNull(uid);
        this.subAccount = subAccount;
        this.canonicalRef = Objects.requireNonNull(canonicalRef);
    }

    // ---- factory ----

    /**
     * Parses {@code walletAddress} (on-chain address or CEX wallet-ref) into a {@link WalletRef}.
     * Returns a SOLANA-domain ref for blank input (consistent with existing normalisation logic).
     */
    public static WalletRef parse(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return new WalletRef(WalletDomainKind.SOLANA, null, "", null, "");
        }
        String trimmed = walletAddress.trim();

        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            String canonical = trimmed.toLowerCase(Locale.ROOT);
            return new WalletRef(WalletDomainKind.EVM, null, canonical, null, canonical);
        }

        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            String canonical = TonAddressCanonicalizer.preferredMemberRef(trimmed);
            return new WalletRef(WalletDomainKind.TON, null, canonical, null, canonical);
        }

        int firstColon = trimmed.indexOf(':');
        if (firstColon > 0) {
            String providerPrefix = trimmed.substring(0, firstColon).toUpperCase(Locale.ROOT);
            String venueId = providerPrefix.toLowerCase(Locale.ROOT);
            String remainder = trimmed.substring(firstColon + 1);
            int secondColon = remainder.indexOf(':');
            String uid;
            String subAccount;
            if (secondColon >= 0) {
                uid = remainder.substring(0, secondColon).trim();
                subAccount = remainder.substring(secondColon + 1).trim();
                if (subAccount.isEmpty()) {
                    subAccount = null;
                }
            } else {
                uid = remainder.trim();
                subAccount = null;
            }
            String canonical = subAccount != null
                    ? providerPrefix + ":" + uid + ":" + subAccount
                    : providerPrefix + ":" + uid;
            return new WalletRef(WalletDomainKind.CEX, venueId, uid, subAccount, canonical);
        }

        return new WalletRef(WalletDomainKind.SOLANA, null, trimmed, null, trimmed);
    }

    // ---- accessors ----

    public WalletDomainKind domain() {
        return domain;
    }

    /**
     * For CEX wallets: stable lowercase venue slug (e.g. {@code bybit}, {@code dzengi}).
     * {@code null} for on-chain wallets.
     */
    public String venueId() {
        return venueId;
    }

    /**
     * Core wallet identifier: lowercase EVM address, Solana pubkey, preferred TON ref, or CEX uid.
     */
    public String uid() {
        return uid;
    }

    /**
     * CEX sub-account kind (e.g. {@code FUND}, {@code UTA}, {@code EARN}).
     * {@code null} if not applicable.
     */
    public String subAccount() {
        return subAccount;
    }

    /**
     * Umbrella key used for cost-basis grouping.
     * <ul>
     *   <li>CEX: {@code PROVIDER:uid} (without sub-account suffix)</li>
     *   <li>On-chain: the canonical address</li>
     * </ul>
     */
    public String umbrellaKey() {
        if (domain == WalletDomainKind.CEX) {
            return venueId.toUpperCase(Locale.ROOT) + ":" + uid;
        }
        return canonicalRef;
    }

    /**
     * Returns the canonical form suitable for storage in {@code walletAddress} / member-ref columns.
     * Identical to {@link #umbrellaKey()} for on-chain wallets; includes sub-account for CEX.
     */
    public String canonicalRef() {
        return canonicalRef;
    }

    /**
     * Provider prefix in uppercase (e.g. {@code BYBIT}, {@code DZENGI}), or {@code null} for on-chain.
     */
    public String providerPrefix() {
        return venueId != null ? venueId.toUpperCase(Locale.ROOT) : null;
    }

    // ---- equality / string ----

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalletRef other)) return false;
        return domain == other.domain && Objects.equals(canonicalRef, other.canonicalRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domain, canonicalRef);
    }

    @Override
    public String toString() {
        return "WalletRef{domain=" + domain + ", ref='" + canonicalRef + "'}";
    }
}
