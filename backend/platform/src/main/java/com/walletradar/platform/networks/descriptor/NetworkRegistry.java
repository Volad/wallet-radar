package com.walletradar.platform.networks.descriptor;

import com.walletradar.domain.common.NetworkAddressFormatKind;
import com.walletradar.domain.common.NetworkDescriptor;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Config-driven network metadata registry (Track A / A5).
 */
@Component
@Slf4j
public class NetworkRegistry {

    private final Map<NetworkId, NetworkDescriptor> descriptorsById;
    private final Set<NetworkId> walletSupportedNetworks;
    private final Set<NetworkId> evmWalletSupportedNetworks;

    public NetworkRegistry(NetworkProperties properties) {
        Map<NetworkId, NetworkDescriptor> built = new EnumMap<>(NetworkId.class);
        Set<NetworkId> walletSupported = new HashSet<>();
        Set<NetworkId> evmWalletSupported = new HashSet<>();

        for (Map.Entry<String, NetworkProperties.NetworkEntry> entry : properties.getEntries().entrySet()) {
            NetworkId networkId = parseNetworkId(entry.getKey());
            NetworkProperties.NetworkEntry config = entry.getValue();
            if (config == null) {
                continue;
            }
            NetworkAddressFormatKind addressFormat = parseAddressFormat(config.getAddressFormat(), networkId);
            boolean walletSupportedFlag = config.getWalletSupported() == null || config.getWalletSupported();
            boolean evmWalletSupportedFlag = config.getEvmWalletSupported() != null
                    ? config.getEvmWalletSupported()
                    : addressFormat == NetworkAddressFormatKind.EVM;

            NetworkDescriptor descriptor = new NetworkDescriptor(
                    networkId,
                    addressFormat,
                    config.getNativeSymbol(),
                    normalizeContract(config.getWrappedNative() == null ? null : config.getWrappedNative().getContract()),
                    config.getWrappedNative() == null ? null : config.getWrappedNative().getSymbol(),
                    normalizeContracts(config.getNativeAliasContracts()),
                    walletSupportedFlag,
                    evmWalletSupportedFlag,
                    normalizeContracts(config.getUsdStableContracts())
            );
            built.put(networkId, descriptor);
            if (walletSupportedFlag) {
                walletSupported.add(networkId);
            }
            if (evmWalletSupportedFlag) {
                evmWalletSupported.add(networkId);
            }
        }

        this.descriptorsById = Collections.unmodifiableMap(built);
        this.walletSupportedNetworks = Collections.unmodifiableSet(walletSupported);
        this.evmWalletSupportedNetworks = Collections.unmodifiableSet(evmWalletSupported);
        NetworkStablecoinContracts.bind(this::usdStableContracts);
        log.info("Loaded network registry: {} descriptors ({} wallet-supported, {} EVM-wallet-supported)",
                descriptorsById.size(),
                walletSupportedNetworks.size(),
                evmWalletSupportedNetworks.size());
    }

    public Optional<NetworkDescriptor> find(NetworkId networkId) {
        return Optional.ofNullable(descriptorsById.get(networkId));
    }

    public NetworkDescriptor require(NetworkId networkId) {
        return find(networkId)
                .orElseThrow(() -> new IllegalStateException("Missing network descriptor for " + networkId));
    }

    public boolean isEvm(NetworkId networkId) {
        return find(networkId).map(NetworkDescriptor::isEvm).orElse(networkId != NetworkId.SOLANA && networkId != NetworkId.TON);
    }

    public String nativeSymbol(NetworkId networkId) {
        return find(networkId).map(NetworkDescriptor::nativeSymbol).orElse("UNKNOWN_NATIVE");
    }

    public String wrappedNativeContract(NetworkId networkId) {
        return find(networkId).map(NetworkDescriptor::wrappedNativeContract).orElse(null);
    }

    public String wrappedNativeSymbol(NetworkId networkId) {
        return find(networkId).map(NetworkDescriptor::wrappedNativeSymbol).orElse(null);
    }

    public boolean isWrappedNative(NetworkId networkId, String contractAddress) {
        String configured = wrappedNativeContract(networkId);
        if (configured == null || contractAddress == null) {
            return false;
        }
        return configured.equals(contractAddress.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isNativeAliasContract(NetworkId networkId, String contractAddress) {
        if (networkId == null || contractAddress == null) {
            return false;
        }
        String normalized = contractAddress.trim().toLowerCase(Locale.ROOT);
        return find(networkId)
                .map(NetworkDescriptor::nativeAliasContracts)
                .orElse(Set.of())
                .contains(normalized);
    }

    public Set<String> usdStableContracts(NetworkId networkId) {
        return find(networkId).map(NetworkDescriptor::usdStableContracts).orElse(Set.of());
    }

    public Set<NetworkId> walletSupportedNetworks() {
        return walletSupportedNetworks;
    }

    public Set<NetworkId> evmWalletSupportedNetworks() {
        return evmWalletSupportedNetworks;
    }

    public String canonicalAddress(NetworkId networkId, String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        NetworkAddressFormatKind format = find(networkId)
                .map(NetworkDescriptor::addressFormat)
                .orElse(NetworkAddressFormatKind.EVM);
        if (format == NetworkAddressFormatKind.SOLANA) {
            return trimmed;
        }
        if (format == NetworkAddressFormatKind.TON || TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed;
    }

    public String canonicalTxHash(NetworkId networkId, String txHash) {
        if (txHash == null) {
            return null;
        }
        String trimmed = txHash.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        NetworkAddressFormatKind format = find(networkId)
                .map(NetworkDescriptor::addressFormat)
                .orElse(NetworkAddressFormatKind.EVM);
        if (format == NetworkAddressFormatKind.SOLANA) {
            return trimmed;
        }
        if (format == NetworkAddressFormatKind.TON) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed;
    }

    public boolean txHashesEqual(NetworkId networkId, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String l = left.trim();
        String r = right.trim();
        if (l.isEmpty() || r.isEmpty()) {
            return false;
        }
        NetworkAddressFormatKind format = find(networkId)
                .map(NetworkDescriptor::addressFormat)
                .orElse(NetworkAddressFormatKind.EVM);
        if (format == NetworkAddressFormatKind.SOLANA) {
            return l.equals(r);
        }
        return l.equalsIgnoreCase(r);
    }

    private static NetworkId parseNetworkId(String key) {
        try {
            return NetworkId.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported network id in walletradar.networks: " + key, ex);
        }
    }

    private static NetworkAddressFormatKind parseAddressFormat(String value, NetworkId networkId) {
        if (value == null || value.isBlank()) {
            return defaultAddressFormat(networkId);
        }
        try {
            return NetworkAddressFormatKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported address format for " + networkId + ": " + value, ex);
        }
    }

    private static NetworkAddressFormatKind defaultAddressFormat(NetworkId networkId) {
        if (networkId == NetworkId.SOLANA) {
            return NetworkAddressFormatKind.SOLANA;
        }
        if (networkId == NetworkId.TON) {
            return NetworkAddressFormatKind.TON;
        }
        return NetworkAddressFormatKind.EVM;
    }

    private static Set<String> normalizeContracts(List<String> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return Set.of();
        }
        return contracts.stream()
                .map(NetworkRegistry::normalizeContract)
                .filter(contract -> contract != null && !contract.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
    }
}
