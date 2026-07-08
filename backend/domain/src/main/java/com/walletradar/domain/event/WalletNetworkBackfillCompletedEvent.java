package com.walletradar.domain.event;

import com.walletradar.domain.common.NetworkId;

/**
 * Published when raw backfill is fully complete for one wallet×network target.
 */
public record WalletNetworkBackfillCompletedEvent(String walletAddress, NetworkId networkId) {
}
