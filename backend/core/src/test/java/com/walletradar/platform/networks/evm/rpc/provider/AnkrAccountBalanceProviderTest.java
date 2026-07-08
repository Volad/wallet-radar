package com.walletradar.platform.networks.evm.rpc.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnkrAccountBalanceProviderTest {

    @Mock
    private EvmRpcClient rpcClient;

    @Test
    void fetchesBalancesAcrossMultipleSupportedNetworksInSingleProviderCall() {
        IngestionNetworkProperties properties = properties();
        when(rpcClient.call(eq("https://rpc.ankr.com/multichain/test"), eq("ankr_getAccountBalance"), any()))
                .thenReturn(Mono.just("""
                        {
                          "jsonrpc":"2.0",
                          "id":1,
                          "result":{
                            "assets":[
                              {
                                "blockchain":"base",
                                "tokenSymbol":"ETH",
                                "balanceRawInteger":"1000000000000000000",
                                "tokenDecimals":18
                              },
                              {
                                "blockchain":"arbitrum",
                                "tokenSymbol":"USDC",
                                "contractAddress":"0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                                "balanceRawInteger":"2500000",
                                "tokenDecimals":6
                              }
                            ]
                          }
                        }
                        """));

        AnkrAccountBalanceProvider provider = new AnkrAccountBalanceProvider(
                rpcClient,
                new ObjectMapper(),
                properties
        );

        List<AnkrAccountBalanceProvider.AccountBalanceAsset> balances = provider.fetchBalances(
                "0x1111111111111111111111111111111111111111",
                Set.of(NetworkId.BASE, NetworkId.ARBITRUM)
        );

        assertThat(balances).hasSize(2);
        assertThat(balances).containsExactlyInAnyOrder(
                new AnkrAccountBalanceProvider.AccountBalanceAsset(
                        NetworkId.BASE,
                        "ETH",
                        null,
                        new java.math.BigDecimal("1")
                ),
                new AnkrAccountBalanceProvider.AccountBalanceAsset(
                        NetworkId.ARBITRUM,
                        "USDC",
                        "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                        new java.math.BigDecimal("2.5")
                )
        );
    }

    private IngestionNetworkProperties properties() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        LinkedHashMap<String, IngestionNetworkProperties.NetworkIngestionEntry> network = new LinkedHashMap<>();
        network.put("BASE", providerEntry());
        network.put("ARBITRUM", providerEntry());
        properties.setNetwork(network);
        return properties;
    }

    private IngestionNetworkProperties.NetworkIngestionEntry providerEntry() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        IngestionNetworkProperties.NetworkIngestionEntry.Provider provider =
                new IngestionNetworkProperties.NetworkIngestionEntry.Provider();
        provider.setEnabled(true);
        provider.setBaseUrl("https://rpc.ankr.com/multichain/test");
        entry.setProvider(provider);
        return entry;
    }
}
