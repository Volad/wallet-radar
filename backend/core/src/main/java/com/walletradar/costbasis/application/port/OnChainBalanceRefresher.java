package com.walletradar.costbasis.application.port;

import java.time.Instant;
import java.util.Collection;

/**
 * Port for refreshing on-chain balance evidence consumed by portfolio snapshots.
 */
public interface OnChainBalanceRefresher {

    int refreshCurrentBalances(Instant capturedAt);

    int refreshCurrentBalances(String sessionId, Collection<String> walletAddresses, Instant capturedAt);

    int refreshCurrentBalances(
            String sessionId,
            Collection<String> walletAddresses,
            Instant capturedAt,
            Runnable heartbeat
    );
}
