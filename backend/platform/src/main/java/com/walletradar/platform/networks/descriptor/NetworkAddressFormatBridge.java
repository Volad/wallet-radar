package com.walletradar.platform.networks.descriptor;

import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import org.springframework.stereotype.Component;

/**
 * Binds {@link NetworkRegistry} into static {@link NetworkAddressFormat} helpers at startup.
 */
@Component
public class NetworkAddressFormatBridge {

    public NetworkAddressFormatBridge(NetworkRegistry networkRegistry) {
        NetworkAddressFormat.bind(new NetworkAddressFormat.NetworkAddressFormatDelegate() {
            @Override
            public boolean isEvm(NetworkId networkId) {
                return networkRegistry.isEvm(networkId);
            }

            @Override
            public String canonicalAddress(NetworkId networkId, String address) {
                return networkRegistry.canonicalAddress(networkId, address);
            }

            @Override
            public String canonicalTxHash(NetworkId networkId, String txHash) {
                return networkRegistry.canonicalTxHash(networkId, txHash);
            }

            @Override
            public boolean txHashesEqual(NetworkId networkId, String left, String right) {
                return networkRegistry.txHashesEqual(networkId, left, right);
            }
        });
    }
}
