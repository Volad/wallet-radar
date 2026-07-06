package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;

import java.util.List;

public interface WalletBackfillPlanner {

    int planPendingOnChainSources(String walletAddress, List<NetworkId> networks);
}
