package com.walletradar.api.validation;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.descriptor.NetworkRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates wallet address and network list for POST /wallets.
 */
@Component
public class AddressValidator {

    private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    /** Solana Base58: 32-44 chars. */
    private static final Pattern SOLANA_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private final Set<NetworkId> supportedNetworks;
    private final Set<NetworkId> supportedEvmNetworks;

    public AddressValidator(NetworkRegistry networkRegistry) {
        this.supportedNetworks = networkRegistry.walletSupportedNetworks();
        this.supportedEvmNetworks = networkRegistry.evmWalletSupportedNetworks();
    }

    public boolean isValidAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String normalized = address.trim();
        return EVM_ADDRESS.matcher(normalized).matches() || SOLANA_ADDRESS.matcher(normalized).matches();
    }

    public boolean isValidEvmAddress(String address) {
        if (address == null || address.isBlank()) return false;
        return EVM_ADDRESS.matcher(address.trim()).matches();
    }

    /**
     * Empty or null = valid (means "all networks" in add-wallet flow).
     * Non-empty = all elements must be supported.
     */
    public boolean areValidNetworks(List<NetworkId> networks) {
        if (networks == null || networks.isEmpty()) return true;
        return networks.stream().allMatch(supportedNetworks::contains);
    }

    /**
     * Non-empty list of EVM-only networks.
     */
    public boolean areValidEvmNetworks(List<NetworkId> networks) {
        if (networks == null || networks.isEmpty()) return false;
        return networks.stream().allMatch(supportedEvmNetworks::contains);
    }
}
