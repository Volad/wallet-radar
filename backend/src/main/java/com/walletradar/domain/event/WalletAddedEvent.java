package com.walletradar.domain.event;

import com.walletradar.domain.common.NetworkId;

import java.util.List;

/**
 * Application event: wallet added (e.g. after POST /wallets). Consumed by {@link com.walletradar.ingestion.job.backfill.BackfillJobRunner}.
 */
public record WalletAddedEvent(String walletAddress, List<NetworkId> networks) {
}
