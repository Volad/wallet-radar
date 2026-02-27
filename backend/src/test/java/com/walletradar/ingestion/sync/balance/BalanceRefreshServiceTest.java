package com.walletradar.ingestion.sync.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.OnChainBalance;
import com.walletradar.domain.OnChainBalanceRepository;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.evm.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.adapter.solana.SolanaRpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceRefreshServiceTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private OnChainBalanceRepository onChainBalanceRepository;
    @Mock
    private EconomicEventRepository economicEventRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private EvmTokenDecimalsResolver evmTokenDecimalsResolver;
    @Mock
    private EvmRpcClient evmRpcClient;
    @Mock
    private SolanaRpcClient solanaRpcClient;

    private BalanceRefreshService service;

    @BeforeEach
    void setUp() {
        RpcEndpointRotator evmRotator = new RpcEndpointRotator(List.of("https://arb1.arbitrum.io/rpc"), null);
        RpcEndpointRotator solRotator = new RpcEndpointRotator(List.of("https://api.mainnet-beta.solana.com"), null);
        service = new BalanceRefreshService(
                syncStatusRepository,
                onChainBalanceRepository,
                economicEventRepository,
                normalizedTransactionRepository,
                evmTokenDecimalsResolver,
                evmRpcClient,
                solanaRpcClient,
                new ObjectMapper(),
                Map.of(NetworkId.ARBITRUM.name(), evmRotator),
                evmRotator,
                Map.of(NetworkId.SOLANA.name(), solRotator),
                solRotator
        );
    }

    @Test
    @DisplayName("refreshWallets stores native and known ERC20 token balances for EVM network")
    void refreshWallets_evmNativeAndKnownToken_upsertsBalances() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String token = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        when(economicEventRepository.findDistinctAssetContractsByWalletAddressAndNetworkId(wallet, NetworkId.ARBITRUM))
                .thenReturn(List.of(token));
        when(normalizedTransactionRepository.findDistinctAssetContractsByWalletAddressAndNetworkIdAndStatus(
                wallet, NetworkId.ARBITRUM, com.walletradar.domain.NormalizedTransactionStatus.CONFIRMED))
                .thenReturn(List.of());
        when(onChainBalanceRepository.findByWalletAddressAndNetworkId(wallet, NetworkId.ARBITRUM.name()))
                .thenReturn(List.of());
        when(onChainBalanceRepository.findByWalletAddressAndNetworkIdAndAssetContract(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(evmTokenDecimalsResolver.getDecimals(NetworkId.ARBITRUM.name(), token)).thenReturn(6);

        when(evmRpcClient.call(anyString(), eq("eth_getBalance"), anyList()))
                .thenReturn(Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xde0b6b3a7640000\"}")); // 1 ETH
        when(evmRpcClient.call(anyString(), eq("eth_call"), anyList()))
                .thenReturn(Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0000000000000000000000000000000000000000000000000000000065ec8780\"}")); // 1710 USDC

        service.refreshWallets(List.of(wallet), List.of(NetworkId.ARBITRUM));

        ArgumentCaptor<OnChainBalance> captor = ArgumentCaptor.forClass(OnChainBalance.class);
        org.mockito.Mockito.verify(onChainBalanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<OnChainBalance> saved = captor.getAllValues();
        assertThat(saved).extracting(OnChainBalance::getAssetContract)
                .contains("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", token);
        assertThat(saved).filteredOn(b -> b.getAssetContract().equals("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))
                .first()
                .extracting(OnChainBalance::getQuantity)
                .isEqualTo(new java.math.BigDecimal("1.000000000000000000"));
        assertThat(saved).filteredOn(b -> b.getAssetContract().equals(token))
                .first()
                .extracting(OnChainBalance::getQuantity)
                .isEqualTo(new java.math.BigDecimal("1710.000000000000000000"));
    }
}
