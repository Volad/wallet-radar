package com.walletradar.costbasis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.common.RetryPolicy;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.rpc.provider.AnkrAccountBalanceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OnChainBalanceRefreshServiceTest {

    private static final String ENDPOINT = "https://base.rpc";

    @Mock
    private OnChainBalanceRefreshQueryService queryService;
    @Mock
    private OnChainBalanceRepository onChainBalanceRepository;
    @Mock
    private EvmRpcClient rpcClient;
    @Mock
    private AnkrAccountBalanceProvider ankrAccountBalanceProvider;
    @Mock
    private EtherscanV2ExplorerProvider etherscanExplorerProvider;
    @Mock
    private BlockScoutExplorerProvider blockScoutExplorerProvider;

    @Test
    void refreshesNativeAndErc20BalancesViaProviderWhenSupported() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.BASE,
                        "ETH",
                        null
                ),
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.BASE,
                        "USDC",
                        "0x2222222222222222222222222222222222222222"
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(ankrAccountBalanceProvider.fetchBalances(
                eq("0x1111111111111111111111111111111111111111"),
                eq(new java.util.LinkedHashSet<>(List.of(NetworkId.BASE)))
        )).thenReturn(List.of(
                new AnkrAccountBalanceProvider.AccountBalanceAsset(
                        NetworkId.BASE,
                        "ETH",
                        "0x0000000000000000000000000000000000000000",
                        new BigDecimal("1")
                ),
                new AnkrAccountBalanceProvider.AccountBalanceAsset(
                        NetworkId.BASE,
                        "USDC",
                        "0x2222222222222222222222222222222222222222",
                        new BigDecimal("1")
                )
        ));

        OnChainBalanceRefreshService service = service();

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(2);
        verify(onChainBalanceRepository).deleteAll();
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);

        Map<String, OnChainBalance> byContract = captor.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(OnChainBalance::getAssetContract, balance -> balance));

        assertThat(byContract.get("NATIVE:BASE").getQuantity()).isEqualByComparingTo("1");
        assertThat(byContract.get("NATIVE:BASE").getAssetSymbol()).isEqualTo("ETH");
        assertThat(byContract.get("0x2222222222222222222222222222222222222222").getQuantity())
                .isEqualByComparingTo("1");
        assertThat(byContract.get("0x2222222222222222222222222222222222222222").getAssetSymbol()).isEqualTo("USDC");
        verify(rpcClient, never()).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void persistsZeroBalanceEvidenceInsteadOfDroppingIt() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.MANTLE,
                        "AMANWETH",
                        "0x3333333333333333333333333333333333333333"
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.MANTLE)).thenReturn(false);
        when(etherscanExplorerProvider.supports(NetworkId.MANTLE)).thenReturn(false);
        when(rpcClient.batchCall(eq(ENDPOINT), anyList()))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0x12\"}]"))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0x0\"}]"));

        OnChainBalanceRefreshService service = new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.MANTLE.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider
        );

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        assertThat(captor.getValue().getFirst().getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void skipsContractlessNonNativeSymbolOnlyCandidates() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.BASE,
                        "DOGE",
                        null
                )
        ));

        OnChainBalanceRefreshService service = service();

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isZero();
        verify(onChainBalanceRepository).deleteAll();
        verify(onChainBalanceRepository, never()).saveAll(anyList());
        verify(rpcClient, never()).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void fallsBackToRpcWhenProviderFails() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.BASE,
                        "ETH",
                        null
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(etherscanExplorerProvider.supports(NetworkId.BASE)).thenReturn(false);
        when(ankrAccountBalanceProvider.fetchBalances(
                eq("0x1111111111111111111111111111111111111111"),
                eq(new java.util.LinkedHashSet<>(List.of(NetworkId.BASE)))
        )).thenThrow(new RuntimeException("provider down"));
        when(rpcClient.batchCall(eq(ENDPOINT), anyList()))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0xde0b6b3a7640000\"}]"));

        OnChainBalanceRefreshService service = service();

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        verify(rpcClient).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void usesExplorerBeforeRpcWhenProviderIsUnavailable() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.MANTLE,
                        "AMANWETH",
                        "0x3333333333333333333333333333333333333333"
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.MANTLE)).thenReturn(false);
        when(etherscanExplorerProvider.supports(NetworkId.MANTLE)).thenReturn(true);
        when(etherscanExplorerProvider.getTokenBalance(
                "0x1111111111111111111111111111111111111111",
                "0x3333333333333333333333333333333333333333",
                NetworkId.MANTLE
        )).thenReturn(new BigDecimal("1").movePointRight(18).toBigIntegerExact());
        when(etherscanExplorerProvider.getTokenDecimals(
                "0x1111111111111111111111111111111111111111",
                "0x3333333333333333333333333333333333333333",
                NetworkId.MANTLE
        )).thenReturn(18);

        OnChainBalanceRefreshService service = new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.MANTLE.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider
        );

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        verify(rpcClient, never()).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void fallsBackToRpcWhenExplorerBalanceExistsButDecimalsAreMissing() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.MANTLE,
                        "AMANWETH",
                        "0x3333333333333333333333333333333333333333"
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.MANTLE)).thenReturn(false);
        when(etherscanExplorerProvider.supports(NetworkId.MANTLE)).thenReturn(true);
        when(etherscanExplorerProvider.getTokenBalance(
                "0x1111111111111111111111111111111111111111",
                "0x3333333333333333333333333333333333333333",
                NetworkId.MANTLE
        )).thenReturn(new BigDecimal("1").movePointRight(18).toBigIntegerExact());
        when(etherscanExplorerProvider.getTokenDecimals(
                "0x1111111111111111111111111111111111111111",
                "0x3333333333333333333333333333333333333333",
                NetworkId.MANTLE
        )).thenReturn(null);
        when(rpcClient.batchCall(eq(ENDPOINT), anyList()))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0x12\"}]"))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0xde0b6b3a7640000\"}]"));

        OnChainBalanceRefreshService service = new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.MANTLE.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider
        );

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        verify(rpcClient, times(2)).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void usesBlockScoutBeforeRpcWhenProviderAndEtherscanAreUnavailable() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.ZKSYNC,
                        "ETH",
                        null
                ),
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.ZKSYNC,
                        "USDC",
                        "0x3333333333333333333333333333333333333333"
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.ZKSYNC)).thenReturn(false);
        when(blockScoutExplorerProvider.supports(NetworkId.ZKSYNC)).thenReturn(true);
        when(blockScoutExplorerProvider.getNativeBalance(
                "0x1111111111111111111111111111111111111111",
                NetworkId.ZKSYNC
        )).thenReturn(new BigDecimal("2").movePointRight(18).toBigIntegerExact());
        when(blockScoutExplorerProvider.getTokenBalances(
                "0x1111111111111111111111111111111111111111",
                NetworkId.ZKSYNC
        )).thenReturn(Map.of(
                "0x3333333333333333333333333333333333333333",
                new BlockScoutExplorerProvider.TokenBalanceSnapshot(
                        new BigDecimal("5").movePointRight(6).toBigIntegerExact(),
                        6
                )
        ));

        OnChainBalanceRefreshService service = new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.ZKSYNC.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider
        );

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(2);
        verify(rpcClient, never()).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void synthesizesZeroBalanceFromBlockScoutTokenMetadataWithoutRpcFallback() {
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.ZKSYNC,
                        "AZKSWETH",
                        "0xb7b93bcf82519bb757fd18b23a389245dbd8ca64"
                )
        ));
        when(ankrAccountBalanceProvider.supports(NetworkId.ZKSYNC)).thenReturn(false);
        when(blockScoutExplorerProvider.supports(NetworkId.ZKSYNC)).thenReturn(true);
        when(blockScoutExplorerProvider.getNativeBalance(
                "0x1111111111111111111111111111111111111111",
                NetworkId.ZKSYNC
        )).thenReturn(new BigDecimal("0.1").movePointRight(18).toBigIntegerExact());
        when(blockScoutExplorerProvider.getTokenBalances(
                "0x1111111111111111111111111111111111111111",
                NetworkId.ZKSYNC
        )).thenReturn(Map.of());
        when(blockScoutExplorerProvider.getTokenDecimals(
                "0xb7b93bcf82519bb757fd18b23a389245dbd8ca64",
                NetworkId.ZKSYNC
        )).thenReturn(18);

        OnChainBalanceRefreshService service = new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.ZKSYNC.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider
        );

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(balance -> {
            assertThat(balance.getAssetContract()).isEqualTo("0xb7b93bcf82519bb757fd18b23a389245dbd8ca64");
            assertThat(balance.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        verify(rpcClient, never()).batchCall(eq(ENDPOINT), anyList());
    }

    private OnChainBalanceRefreshService service() {
        return new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.BASE.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider
        );
    }
}
