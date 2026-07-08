package com.walletradar.domain.common;

import com.walletradar.domain.common.ton.TonAddressCanonicalizer;

import java.util.Locale;

/**
 * Per-network address / txHash canonicalisation facade.
 *
 * <p>Delegates to {@link com.walletradar.platform.networks.descriptor.NetworkRegistry} when bound
 * at startup; falls back to legacy defaults for unit tests without Spring context.</p>
 */
public final class NetworkAddressFormat {

    private static volatile NetworkAddressFormatDelegate delegate = new LegacyNetworkAddressFormatDelegate();

    private NetworkAddressFormat() {
    }

    public static void bind(NetworkAddressFormatDelegate networkDelegate) {
        delegate = networkDelegate == null ? new LegacyNetworkAddressFormatDelegate() : networkDelegate;
    }

    public static boolean isEvm(NetworkId networkId) {
        return delegate.isEvm(networkId);
    }

    public static String canonicalAddress(NetworkId networkId, String address) {
        return delegate.canonicalAddress(networkId, address);
    }

    public static String canonicalTxHash(NetworkId networkId, String txHash) {
        return delegate.canonicalTxHash(networkId, txHash);
    }

    public static boolean txHashesEqual(NetworkId networkId, String left, String right) {
        return delegate.txHashesEqual(networkId, left, right);
    }

    public interface NetworkAddressFormatDelegate {
        boolean isEvm(NetworkId networkId);

        String canonicalAddress(NetworkId networkId, String address);

        String canonicalTxHash(NetworkId networkId, String txHash);

        boolean txHashesEqual(NetworkId networkId, String left, String right);
    }

    private static final class LegacyNetworkAddressFormatDelegate implements NetworkAddressFormatDelegate {
        @Override
        public boolean isEvm(NetworkId networkId) {
            return networkId != null
                    && networkId != NetworkId.SOLANA
                    && networkId != NetworkId.TON;
        }

        @Override
        public String canonicalAddress(NetworkId networkId, String address) {
            if (address == null) {
                return null;
            }
            String trimmed = address.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (networkId == NetworkId.SOLANA) {
                return trimmed;
            }
            if (networkId == NetworkId.TON || TonAddressCanonicalizer.looksLikeTon(trimmed)) {
                return TonAddressCanonicalizer.preferredMemberRef(trimmed);
            }
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            return trimmed;
        }

        @Override
        public String canonicalTxHash(NetworkId networkId, String txHash) {
            if (txHash == null) {
                return null;
            }
            String trimmed = txHash.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (networkId == NetworkId.SOLANA) {
                return trimmed;
            }
            if (networkId == NetworkId.TON) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            return trimmed;
        }

        @Override
        public boolean txHashesEqual(NetworkId networkId, String left, String right) {
            if (left == null || right == null) {
                return false;
            }
            String l = left.trim();
            String r = right.trim();
            if (l.isEmpty() || r.isEmpty()) {
                return false;
            }
            if (networkId == NetworkId.SOLANA) {
                return l.equals(r);
            }
            return l.equalsIgnoreCase(r);
        }
    }
}
