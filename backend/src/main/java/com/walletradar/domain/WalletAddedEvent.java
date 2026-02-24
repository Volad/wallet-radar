package com.walletradar.domain;

import java.util.List;

/**
 * Application event: wallet added (e.g. after POST /wallets). Consumed by BackfillJobRunner.
 */
public record WalletAddedEvent(String walletAddress, List<NetworkId> networks) {
}
