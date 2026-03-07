package com.walletradar.ingestion.adapter.evm.explorer;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Routes explorer requests to the concrete provider based on network sync method/config.
 */
@Component
@Primary
@RequiredArgsConstructor
public class RoutingExplorerProvider implements ExplorerProvider {

    private final EtherscanV2ExplorerProvider etherscanProvider;
    private final BlockScoutExplorerProvider blockScoutProvider;

    @Override
    public boolean supports(NetworkId networkId) {
        return resolve(networkId) != null;
    }

    @Override
    public Long getCurrentBlockNumber(NetworkId networkId) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return null;
        }
        return provider.getCurrentBlockNumber(networkId);
    }

    @Override
    public List<ExplorerTransaction> getTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return List.of();
        }
        return provider.getTransactions(walletAddress, networkId, fromBlock, toBlock, page);
    }

    @Override
    public List<ExplorerTokenTransfer> getTokenTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return List.of();
        }
        return provider.getTokenTransfers(walletAddress, networkId, fromBlock, toBlock, page);
    }

    @Override
    public List<ExplorerInternalTransfer> getInternalTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return List.of();
        }
        return provider.getInternalTransfers(walletAddress, networkId, fromBlock, toBlock, page);
    }

    @Override
    public ExplorerTransaction getTransaction(String txHash, NetworkId networkId) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return null;
        }
        return provider.getTransaction(txHash, networkId);
    }

    @Override
    public ExplorerTransactionDetails getTransactionDetails(String txHash, NetworkId networkId) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return null;
        }
        return provider.getTransactionDetails(txHash, networkId);
    }

    @Override
    public ExplorerReceipt getReceipt(String txHash, NetworkId networkId) {
        ExplorerProvider provider = resolve(networkId);
        if (provider == null) {
            return null;
        }
        return provider.getReceipt(txHash, networkId);
    }

    private ExplorerProvider resolve(NetworkId networkId) {
        if (etherscanProvider.supports(networkId)) {
            return etherscanProvider;
        }
        if (blockScoutProvider.supports(networkId)) {
            return blockScoutProvider;
        }
        return null;
    }
}
