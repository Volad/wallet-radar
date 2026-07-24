package com.walletradar.application.portfolio.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.application.costbasis.application.balance.NonEvmOnChainBalanceLoader;
import com.walletradar.application.costbasis.application.balance.OnChainBalanceProvider;
import com.walletradar.application.costbasis.application.OnChainBalanceRefreshQueryService;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.provider.AnkrAccountBalanceProvider;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OnChainBalanceRefreshServiceTest {

    private static final String ENDPOINT = "https://base.rpc";

    @BeforeAll
    static void bindNetworkNativeAssets() {
        // Building the test registry binds NetworkNativeAssets so native-alias asset identities
        // (e.g. NATIVE:BASE) resolve. Without an explicit bind this class depended on another test
        // in the same JVM having constructed a NetworkRegistry first (order-fragile).
        NetworkTestFixtures.registry();
    }

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
        // A3: non-destructive upsert lifecycle — no unconditional deleteAll() before the write.
        verify(onChainBalanceRepository, never()).deleteAll();
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
                        "GRT",
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
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(), List.of())
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
        // A3: nothing resolvable and no existing rows → no destructive wipe and no write.
        verify(onChainBalanceRepository, never()).deleteAll();
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
                        "GRT",
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
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(), List.of())
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
                        "GRT",
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
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(), List.of())
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
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(), List.of())
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
                        "GRT",
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
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(), List.of())
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

    @Test
    void fetchesNonEvmBalancesViaProviderBoundedToAccountingUniverse() {
        String solanaWallet = "So1anaWa11etAbcDefGhiJkLmNoPqRsTuVwXyz12";
        String usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
        String dustMint = "DustMint1111111111111111111111111111111111";
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        solanaWallet, NetworkId.SOLANA, "SOL", null),
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        solanaWallet, NetworkId.SOLANA, "USDC", usdcMint)
        ));

        OnChainBalanceProvider provider =
                new OnChainBalanceProvider() {
                    @Override
                    public NetworkId networkId() {
                        return NetworkId.SOLANA;
                    }

                    @Override
                    public List<ProviderBalance> fetchBalances(String walletAddress) {
                        assertThat(walletAddress).isEqualTo(solanaWallet); // case preserved
                        return List.of(
                                new ProviderBalance("SOL", "NATIVE:SOLANA", 9, new BigDecimal("2.5"), true),
                                new ProviderBalance("USDC", usdcMint, 6, new BigDecimal("1.5"), false),
                                new ProviderBalance("SCAM", dustMint, 0, new BigDecimal("999"), false)
                        );
                    }
                };

        OnChainBalanceRefreshService service = new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.BASE.name(), new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(ENDPOINT), RetryPolicy.defaultPolicy()),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(provider), List.of())
        );

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-04-03T18:00:00Z"));

        assertThat(refreshed).isEqualTo(2); // native SOL + seeded USDC; dust bounded away
        verify(onChainBalanceRepository, never()).deleteAll();
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        Map<String, OnChainBalance> byContract = captor.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(OnChainBalance::getAssetContract, balance -> balance));
        assertThat(byContract.get("NATIVE:SOLANA").getWalletAddress()).isEqualTo(solanaWallet);
        assertThat(byContract.get("NATIVE:SOLANA").getQuantity()).isEqualByComparingTo("2.5");
        assertThat(byContract.get(usdcMint).getQuantity()).isEqualByComparingTo("1.5");
        assertThat(byContract).doesNotContainKey(dustMint);
        verify(rpcClient, never()).batchCall(eq(ENDPOINT), anyList());
    }

    @Test
    void forcesLiveRpcBalanceOfForInterestAccruingLendingTokens() {
        // Aave variable-debt token: indexed providers report the scaled/principal amount (under-reporting
        // accrued interest), so it must be read via live RPC balanceOf. decimals() -> 6, balanceOf() ->
        // 603.203823 * 1e6 (0x23f428ef), the true accrued on-chain debt.
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        "0x1111111111111111111111111111111111111111",
                        NetworkId.BASE,
                        "VARIABLEDEBTBASUSDC",
                        "0x59dca05b6c26dbd64b5381374aaac5cd05644c28"
                )
        ));
        when(rpcClient.batchCall(eq(ENDPOINT), anyList()))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0x6\"}]"))
                .thenReturn(Mono.just("[{\"id\":1,\"result\":\"0x23f428ef\"}]"));

        OnChainBalanceRefreshService service = service();

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-07-20T18:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        // Indexed provider bypassed entirely; live RPC decimals + balanceOf used instead.
        verify(ankrAccountBalanceProvider, never()).fetchBalances(anyString(), any());
        verify(rpcClient, times(2)).batchCall(eq(ENDPOINT), anyList());
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(balance ->
                assertThat(balance.getQuantity()).isEqualByComparingTo("603.203823"));
    }

    @Test
    void retainsLastKnownSnapshotWhenForcedLiveAllSourcesFailInsteadOfDropping() {
        // A1/A2: forced-live Aave debt token whose live RPC (decimals + balanceOf) fails on every
        // endpoint and whose Ankr/BlockScout/Etherscan fallbacks are all unavailable must NOT be
        // dropped. Retain the last-known snapshot, mark captureFallback, keep the prior capturedAt
        // (no silent backfill), and never wipe the read model.
        String wallet = "0x1111111111111111111111111111111111111111";
        String contract = "0x59dca05b6c26dbd64b5381374aaac5cd05644c28";
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                NetworkId.BASE, "VARIABLEDEBTBASUSDC", contract);
        Instant priorCapture = Instant.parse("2026-07-20T18:00:00Z");
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        wallet, NetworkId.BASE, "VARIABLEDEBTBASUSDC", contract)
        ));
        when(rpcClient.batchCall(eq(ENDPOINT), anyList())).thenThrow(new RuntimeException("rpc down"));
        when(onChainBalanceRepository.findBySessionIdIsNull()).thenReturn(List.of(
                snapshot("GLOBAL:" + wallet + ":BASE:" + identity, wallet, NetworkId.BASE,
                        "VARIABLEDEBTBASUSDC", contract, new BigDecimal("603.203823"), priorCapture)
        ));

        OnChainBalanceRefreshService service = service(new RetryPolicy(0L, 0.0, 1));

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-07-22T10:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        verify(onChainBalanceRepository, never()).deleteAll();
        verify(onChainBalanceRepository, never()).deleteAllById(any());
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(balance -> {
            assertThat(balance.getQuantity()).isEqualByComparingTo("603.203823");
            assertThat(balance.getCaptureFallback()).isTrue();
            // Not backfilled to the current capture time — staleness stays measurable.
            assertThat(balance.getCapturedAt()).isEqualTo(priorCapture);
        });
    }

    @Test
    void staleBeyondBoundFallbackFlagsRatherThanBackfilling() {
        // A4 freshness bound: a very stale last-known snapshot is still retained (never dropped) but is
        // marked captureFallback and keeps its old capturedAt — it is never silently backfilled to look
        // like a fresh capture.
        String wallet = "0x1111111111111111111111111111111111111111";
        String contract = "0x59dca05b6c26dbd64b5381374aaac5cd05644c28";
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                NetworkId.BASE, "VARIABLEDEBTBASUSDC", contract);
        Instant staleCapture = Instant.parse("2026-06-01T00:00:00Z"); // ~7 weeks before "now"
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        wallet, NetworkId.BASE, "VARIABLEDEBTBASUSDC", contract)
        ));
        when(rpcClient.batchCall(eq(ENDPOINT), anyList())).thenThrow(new RuntimeException("rpc down"));
        when(onChainBalanceRepository.findBySessionIdIsNull()).thenReturn(List.of(
                snapshot("GLOBAL:" + wallet + ":BASE:" + identity, wallet, NetworkId.BASE,
                        "VARIABLEDEBTBASUSDC", contract, new BigDecimal("100"), staleCapture)
        ));

        OnChainBalanceRefreshService service = service(new RetryPolicy(0L, 0.0, 1));

        int refreshed = service.refreshCurrentBalances(Instant.parse("2026-07-22T10:00:00Z"));

        assertThat(refreshed).isEqualTo(1);
        ArgumentCaptor<List<OnChainBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(onChainBalanceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(balance -> {
            assertThat(balance.getCaptureFallback()).isTrue();
            assertThat(balance.getCapturedAt()).isEqualTo(staleCapture);
        });
    }

    @Test
    void partialFetchDoesNotEraseExistingSurvivorRows() {
        // A3: on a partial/failed fetch (forced-live candidate falls back to snapshot) the destructive
        // stale-row cleanup must be skipped so a survivor row for a bucket that is no longer a candidate
        // is never erased.
        String wallet = "0x1111111111111111111111111111111111111111";
        String contract = "0x59dca05b6c26dbd64b5381374aaac5cd05644c28";
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                NetworkId.BASE, "VARIABLEDEBTBASUSDC", contract);
        when(queryService.loadCandidates()).thenReturn(List.of(
                new OnChainBalanceRefreshQueryService.BalanceRefreshCandidate(
                        wallet, NetworkId.BASE, "VARIABLEDEBTBASUSDC", contract)
        ));
        when(rpcClient.batchCall(eq(ENDPOINT), anyList())).thenThrow(new RuntimeException("rpc down"));
        when(onChainBalanceRepository.findBySessionIdIsNull()).thenReturn(List.of(
                snapshot("GLOBAL:" + wallet + ":BASE:" + identity, wallet, NetworkId.BASE,
                        "VARIABLEDEBTBASUSDC", contract, new BigDecimal("603.20"),
                        Instant.parse("2026-07-20T18:00:00Z")),
                // Survivor bucket that is no longer a candidate — must not be erased on a failed fetch.
                snapshot("GLOBAL:" + wallet + ":BASE:NATIVE:BASE", wallet, NetworkId.BASE,
                        "ETH", "NATIVE:BASE", new BigDecimal("0.75"), Instant.parse("2026-07-20T18:00:00Z"))
        ));

        OnChainBalanceRefreshService service = service(new RetryPolicy(0L, 0.0, 1));

        service.refreshCurrentBalances(Instant.parse("2026-07-22T10:00:00Z"));

        verify(onChainBalanceRepository, never()).deleteAll();
        verify(onChainBalanceRepository, never()).deleteAllById(any());
    }

    private static OnChainBalance snapshot(
            String id,
            String wallet,
            NetworkId networkId,
            String symbol,
            String contract,
            BigDecimal quantity,
            Instant capturedAt
    ) {
        OnChainBalance balance = new OnChainBalance();
        balance.setId(id);
        balance.setSessionId(null);
        balance.setWalletAddress(wallet);
        balance.setNetworkId(networkId);
        balance.setAssetSymbol(symbol);
        balance.setAssetContract(contract);
        balance.setQuantity(quantity);
        balance.setCapturedAt(capturedAt);
        return balance;
    }

    private OnChainBalanceRefreshService service() {
        return service(RetryPolicy.defaultPolicy());
    }

    private OnChainBalanceRefreshService service(RetryPolicy retryPolicy) {
        return new OnChainBalanceRefreshService(
                queryService,
                onChainBalanceRepository,
                rpcClient,
                Map.of(NetworkId.BASE.name(), new RpcEndpointRotator(List.of(ENDPOINT), retryPolicy)),
                new RpcEndpointRotator(List.of(ENDPOINT), retryPolicy),
                new ObjectMapper(),
                ankrAccountBalanceProvider,
                etherscanExplorerProvider,
                blockScoutExplorerProvider,
                new NonEvmOnChainBalanceLoader(List.of(), List.of())
        );
    }
}
