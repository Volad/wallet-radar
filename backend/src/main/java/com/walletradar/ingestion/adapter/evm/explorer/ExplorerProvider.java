package com.walletradar.ingestion.adapter.evm.explorer;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;

import java.util.List;

/**
 * Chain explorer provider abstraction (ADR-026).
 */
public interface ExplorerProvider {

    boolean supports(NetworkId networkId);

    List<ExplorerTransaction> getTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page);

    List<ExplorerTokenTransfer> getTokenTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page);

    List<ExplorerInternalTransfer> getInternalTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page);

    ExplorerTransaction getTransaction(String txHash, NetworkId networkId);

    ExplorerTransactionDetails getTransactionDetails(String txHash, NetworkId networkId);

    ExplorerReceipt getReceipt(String txHash, NetworkId networkId);
}
