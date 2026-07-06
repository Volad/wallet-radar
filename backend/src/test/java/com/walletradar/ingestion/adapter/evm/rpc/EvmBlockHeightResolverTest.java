package com.walletradar.ingestion.adapter.evm.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvmBlockHeightResolverTest {

    private final EvmRpcClient rpcClient = mock(EvmRpcClient.class);
    private final ExplorerProvider explorerProvider = mock(ExplorerProvider.class);
    private final RpcEndpointRotator defaultRotator = mock(RpcEndpointRotator.class);

    @Test
    @DisplayName("uses fresher rpc head when explorer head lags on explorer-synced networks")
    void getCurrentBlock_prefersMaxOfExplorerAndRpc() {
        EvmBlockHeightResolver resolver = resolverFor(NetworkId.ARBITRUM);
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getCurrentBlockNumber(NetworkId.ARBITRUM)).thenReturn(451_344_315L);
        when(defaultRotator.getNextEndpoint()).thenReturn("https://rpc.example");
        when(rpcClient.call("https://rpc.example", "eth_blockNumber", java.util.Collections.emptyList()))
                .thenReturn(Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1ae912db\"}"));

        long currentBlock = resolver.getCurrentBlock(NetworkId.ARBITRUM);

        assertThat(currentBlock).isEqualTo(451_482_331L);
    }

    @Test
    @DisplayName("keeps explorer head when rpc fallback fails")
    void getCurrentBlock_fallsBackToExplorerWhenRpcFails() {
        EvmBlockHeightResolver resolver = resolverFor(NetworkId.ARBITRUM);
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getCurrentBlockNumber(NetworkId.ARBITRUM)).thenReturn(451_482_308L);
        when(defaultRotator.getNextEndpoint()).thenReturn("https://rpc.example");
        when(rpcClient.call("https://rpc.example", "eth_blockNumber", java.util.Collections.emptyList()))
                .thenReturn(Mono.error(new RuntimeException("rpc unavailable")));

        long currentBlock = resolver.getCurrentBlock(NetworkId.ARBITRUM);

        assertThat(currentBlock).isEqualTo(451_482_308L);
    }

    private EvmBlockHeightResolver resolverFor(NetworkId networkId) {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        properties.setNetwork(Map.of(networkId.name(), entry));
        return new EvmBlockHeightResolver(
                rpcClient,
                explorerProvider,
                Map.of(),
                defaultRotator,
                properties,
                new ObjectMapper()
        );
    }
}
