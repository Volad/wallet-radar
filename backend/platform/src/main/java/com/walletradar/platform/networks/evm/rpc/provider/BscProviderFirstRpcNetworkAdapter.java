package com.walletradar.platform.networks.evm.rpc.provider;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.NetworkAdapter;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.rpc.nativerpc.NativeRpcTransactionRepairGateway;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class BscProviderFirstRpcNetworkAdapter implements NetworkAdapter {

    private final AnkrTransactionsByAddressProvider provider;
    private final ProviderBackedRawTransactionMapper rawTransactionMapper;
    private final NativeRpcTransactionRepairGateway nativeRepairGateway;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;

    @Override
    public boolean supports(NetworkId networkId) {
        if (networkId != NetworkId.BSC) {
            return false;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = networkEntry();
        return entry != null
                && entry.getProvider() != null
                && entry.getProvider().isEnabled()
                && entry.getProvider().getBaseUrl() != null
                && !entry.getProvider().getBaseUrl().isBlank();
    }

    @Override
    public List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock) {
        if (!supports(networkId) || fromBlock > toBlock) {
            return List.of();
        }
        String normalizedWallet = walletAddress.toLowerCase(Locale.ROOT);
        String nativeEndpoint = nativeEndpoint();
        Map<String, RawTransaction> byHash = new LinkedHashMap<>();
        for (Document providerTransaction : provider.fetchTransactionsByAddress(walletAddress, networkId, fromBlock, toBlock)) {
            RawTransaction rawTransaction = rawTransactionMapper.toRawTransaction(
                    normalizedWallet,
                    networkId.name(),
                    nativeEndpoint,
                    providerTransaction
            );
            if (rawTransaction == null || rawTransaction.getRawData() == null) {
                continue;
            }
            nativeRepairGateway.repair(nativeEndpoint, rawTransaction.getTxHash(), rawTransaction.getRawData());
            rawTransactionMapper.refreshDerivedEvidence(rawTransaction, nativeEndpoint);
            byHash.put(rawTransaction.getTxHash(), rawTransaction);
        }
        return List.copyOf(byHash.values());
    }

    @Override
    public int getMaxBlockBatchSize() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = networkEntry();
        Integer configured = entry != null ? entry.getBatchBlockSize() : null;
        return configured != null && configured > 0 ? configured : 250_000;
    }

    @Override
    public boolean supportsBlockCheckpointing() {
        return false;
    }

    private IngestionNetworkProperties.NetworkIngestionEntry networkEntry() {
        return ingestionNetworkProperties.getNetwork() != null
                ? ingestionNetworkProperties.getNetwork().get(NetworkId.BSC.name())
                : null;
    }

    private String nativeEndpoint() {
        RpcEndpointRotator rotator = rotatorsByNetwork.get(NetworkId.BSC.name());
        if (rotator == null) {
            return null;
        }
        return rotator.getNextEndpoint();
    }
}
