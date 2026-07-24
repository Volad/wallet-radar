package com.walletradar.application.costbasis.support;

import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.domain.wallet.OnChainAddressClassifier;

import java.util.Locale;

/**
 * Family-aware canonicalization of a wallet address for read-path {@code walletAddress} matching
 * (Mongo {@code $in} queries and in-memory bucket joins) across dashboard and lending views.
 *
 * <p>EVM and CEX refs are lowercased (unchanged legacy behavior). Solana base58 public keys are
 * case-sensitive, so their case is preserved. TON addresses are collapsed to their preferred
 * canonical member ref (friendly {@code UQ…}), matching how balances and universe members are
 * persisted. Blindly lowercasing (the previous behavior) corrupted base58 Solana/TON addresses so
 * their balance/ledger rows never matched the session wallet on the read path.</p>
 */
public final class WalletAddressReadScope {

    private WalletAddressReadScope() {
    }

    /**
     * @return the canonical read-scope form of {@code address}: Solana case-preserved, TON preferred
     *         member ref, EVM/CEX lowercased; empty string for null/blank input.
     */
    public static String normalize(String address) {
        if (address == null) {
            return "";
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return switch (OnChainAddressClassifier.classifyDomain(trimmed)) {
            case SOLANA -> trimmed;
            case TON -> TonAddressCanonicalizer.preferredMemberRef(trimmed);
            case EVM, CEX -> trimmed.toLowerCase(Locale.ROOT);
        };
    }
}
