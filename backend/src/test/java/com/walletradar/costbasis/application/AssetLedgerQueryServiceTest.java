package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.read.TimelineAvcoAuthority;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.integration.bybit.BybitLiveBalanceService;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetLedgerQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AssetLedgerPointRepository assetLedgerPointRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private BybitLiveBalanceService bybitLiveBalanceService;

    private AssetLedgerQueryService service() {
        return new AssetLedgerQueryService(
                userSessionRepository,
                assetLedgerPointRepository,
                normalizedTransactionRepository,
                accountingUniverseService,
                mongoOperations,
                bybitLiveBalanceService
        );
    }

    @Test
    void sessionFamilyLedgerAggregatesWalletLevelPointsIntoOneTimeline() {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionWallet walletA = new UserSession.SessionWallet();
        walletA.setAddress("wallet-a");
        walletA.setNetworks(List.of(NetworkId.BASE));
        UserSession.SessionWallet walletB = new UserSession.SessionWallet();
        walletB.setAddress("wallet-b");
        walletB.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(walletA, walletB));

        AssetLedgerPoint buy = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "100",
                "0",
                "0",
                "1",
                "100"
        );
        AssetLedgerPoint bridgeOut = point(
                "2",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_OUT,
                AssetLedgerPoint.LifecycleKind.BRIDGE,
                AssetLedgerPoint.LifecycleStage.SOURCE,
                "-1",
                "-100",
                "0",
                "0",
                "0",
                "0"
        );
        AssetLedgerPoint bridgeIn = point(
                "2",
                "wallet-b",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.BRIDGE,
                AssetLedgerPoint.LifecycleStage.DESTINATION,
                "1",
                "100",
                "0",
                "0",
                "1",
                "100"
        );
        AssetLedgerPoint bybitBuy = point(
                "0",
                "BYBIT:33625378",
                null,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1.5",
                "3000",
                "0",
                "0",
                "1.5",
                "3000"
        );
        bybitBuy.setTxHash(null);
        bybitBuy.setProtocolName("Bybit");
        bybitBuy.setTransactionIndex(0);
        buy.setBlockTimestamp(Instant.parse("2026-04-05T10:10:00Z"));
        bridgeOut.setBlockTimestamp(Instant.parse("2026-04-05T10:20:00Z"));
        bridgeIn.setBlockTimestamp(Instant.parse("2026-04-05T10:20:01Z"));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("wallet-a", "wallet-b", "BYBIT:33625378"),
                List.of("wallet-a", "wallet-b")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-1",
                "FAMILY:ETH"
        )).thenReturn(List.of(bybitBuy, buy, bridgeOut, bridgeIn));
        when(normalizedTransactionRepository.findAllById(List.of("0", "1", "2")))
                .thenReturn(List.of(
                        normalized("0", "Bybit", "ETH", "BUY", "1.5", "2000"),
                        normalized("1", "Coinbase", "ETH", "BUY", "1", "100"),
                        normalized("2", "Across", "ETH", "TRANSFER", "-1", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0"),
                balance("wallet-b", NetworkId.ARBITRUM, "ETH", "1")
        ));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-1", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(3);
        assertThat(view.timeline().get(0).protocolName()).isEqualTo("Bybit");
        assertThat(view.timeline().get(0).quantityAfter()).isEqualByComparingTo("1.5");
        assertThat(view.timeline().get(0).coveredQuantityAfter()).isEqualByComparingTo("1.5");
        assertThat(view.timeline().get(0).uncoveredQuantityAfter()).isZero();
        assertThat(view.timeline().get(2).quantityAfter()).isEqualByComparingTo("2.5");
        assertThat(view.timeline().get(2).coveredQuantityAfter()).isEqualByComparingTo("2.5");
        assertThat(view.timeline().get(2).uncoveredQuantityAfter()).isZero();
        assertThat(view.timeline().get(2).totalCostBasisAfterUsd()).isEqualByComparingTo("3100");
        assertThat(view.current().quantity()).isEqualByComparingTo("1");
        assertThat(view.current().coveredQuantity()).isEqualByComparingTo("1");
        assertThat(view.current().uncoveredQuantity()).isZero();
        assertThat(view.current().totalCostBasisUsd()).isEqualByComparingTo("100");
        assertThat(view.current().avcoUsd()).isEqualByComparingTo("100");
        assertThat(view.current().uncoveredBuckets()).isEmpty();
        assertThat(view.current().shortfallSources()).isEmpty();
        assertThat(view.events()).hasSize(3);
        assertThat(view.events().get(0).protocolName()).isEqualTo("Bybit");
        assertThat(view.ledgerPoints()).hasSize(4);
    }

    @Test
    void sessionFamilyLedgerFallsBackToCurrentSessionWalletsWhenUniverseIsAbsent() {
        UserSession session = new UserSession();
        session.setId("session-2");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buy = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "100",
                "0",
                "0",
                "1",
                "100"
        );

        when(userSessionRepository.findById("session-2")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-2",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-2",
                "FAMILY:ETH"
        )).thenReturn(List.of(buy));
        when(normalizedTransactionRepository.findAllById(List.of("1")))
                .thenReturn(List.of(normalized("1", "Coinbase", "ETH", "BUY", "1", "100")));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-2", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(1);
        assertThat(view.current().quantity()).isEqualByComparingTo("1");
        assertThat(view.current().uncoveredBuckets()).isEmpty();
        assertThat(view.current().shortfallSources()).isEmpty();
    }

    @Test
    void sessionFamilyLedgerExposesUncoveredBucketDiagnosticsForCurrentState() {
        UserSession session = new UserSession();
        session.setId("session-3");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint wrapPoint = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.WRAP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "0",
                "0",
                "0",
                "1",
                "0"
        );
        wrapPoint.setAssetSymbol("WETH");
        wrapPoint.setAssetContract("0x4200000000000000000000000000000000000006");
        wrapPoint.setAccountingAssetIdentity("0x4200000000000000000000000000000000000006");
        wrapPoint.setBasisBackedQuantityAfter(BigDecimal.ZERO);
        wrapPoint.setUncoveredQuantityAfter(new BigDecimal("1"));
        wrapPoint.setHasIncompleteHistoryAfter(true);
        wrapPoint.setHasUnresolvedFlagsAfter(true);
        wrapPoint.setUnresolvedFlagCountAfter(2);

        when(userSessionRepository.findById("session-3")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-3",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-3",
                "FAMILY:ETH"
        )).thenReturn(List.of(wrapPoint));
        when(normalizedTransactionRepository.findAllById(List.of("1"))).thenReturn(List.of());
        OnChainBalance wethBalance = balance("wallet-a", NetworkId.BASE, "WETH", "1");
        wethBalance.setAssetContract("0x4200000000000000000000000000000000000006");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(wethBalance));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-3", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().quantity()).isEqualByComparingTo("1");
        assertThat(view.current().coveredQuantity()).isZero();
        assertThat(view.current().uncoveredQuantity()).isEqualByComparingTo("1");
        assertThat(view.current().uncoveredBuckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.assetSymbol()).isEqualTo("WETH");
            assertThat(bucket.uncoveredReason()).isEqualTo("coverage_gap");
            assertThat(bucket.latestNormalizedType()).isEqualTo("BRIDGE");
            assertThat(bucket.latestBasisEffect()).isEqualTo("CARRY_IN");
            assertThat(bucket.hasIncompleteHistory()).isTrue();
            assertThat(bucket.hasUnresolvedFlags()).isTrue();
            assertThat(bucket.unresolvedFlagCount()).isEqualTo(2);
        });
        assertThat(view.current().shortfallSources()).isEmpty();
    }

    @Test
    void sessionFamilyLedgerLabelsInterestBearingCurrentDriftAsYieldAccrual() {
        UserSession session = new UserSession();
        session.setId("session-4");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint lendingPoint = point(
                "1",
                "wallet-a",
                NetworkId.MANTLE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.LifecycleKind.LENDING,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "3.06",
                "6058.10",
                "0",
                "0",
                "3.06",
                "6058.10"
        );
        lendingPoint.setAssetSymbol("aManWETH");
        lendingPoint.setAssetContract("0xea00000000000000000000000000000000000000");
        lendingPoint.setAccountingAssetIdentity("0xea00000000000000000000000000000000000000");
        lendingPoint.setNormalizedType("LENDING_DEPOSIT");
        lendingPoint.setProtocolName("Aave");
        lendingPoint.setBasisBackedQuantityAfter(new BigDecimal("3.06"));
        lendingPoint.setUncoveredQuantityAfter(BigDecimal.ZERO);
        lendingPoint.setHasIncompleteHistoryAfter(false);
        lendingPoint.setHasUnresolvedFlagsAfter(false);
        lendingPoint.setUnresolvedFlagCountAfter(0);

        when(userSessionRepository.findById("session-4")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-4",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-4",
                "FAMILY:ETH"
        )).thenReturn(List.of(lendingPoint));
        when(normalizedTransactionRepository.findAllById(List.of("1"))).thenReturn(List.of());
        OnChainBalance balance = balance("wallet-a", NetworkId.MANTLE, "aManWETH", "3.066185599746344040");
        balance.setAssetContract("0xea00000000000000000000000000000000000000");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(balance));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-4", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().uncoveredQuantity()).isEqualByComparingTo("0.006185599746344040");
        assertThat(view.current().uncoveredBuckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.assetSymbol()).isEqualTo("aManWETH");
            assertThat(bucket.uncoveredReason()).isEqualTo("yield_accrual");
            assertThat(bucket.latestNormalizedType()).isEqualTo("LENDING_DEPOSIT");
            assertThat(bucket.latestBasisEffect()).isEqualTo("REALLOCATE_IN");
            assertThat(bucket.hasIncompleteHistory()).isFalse();
            assertThat(bucket.hasUnresolvedFlags()).isFalse();
        });
        assertThat(view.current().shortfallSources()).isEmpty();
    }

    @Test
    void sessionFamilyLedgerExposesFamilyShortfallSourcesForCurrentState() {
        UserSession session = new UserSession();
        session.setId("session-7");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint shortfallSource = point(
                "11",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.0002",
                "0",
                "0",
                "0",
                "0",
                "0"
        );
        shortfallSource.setNormalizedType("LP_ENTRY");
        shortfallSource.setProtocolName("PancakeSwap");
        shortfallSource.setTxHash("0xshortfall");
        shortfallSource.setBlockTimestamp(Instant.parse("2026-04-05T09:55:00Z"));
        shortfallSource.setQuantityShortfallAfter(new BigDecimal("0.125"));
        shortfallSource.setUncoveredQuantityAfter(BigDecimal.ZERO);
        shortfallSource.setQuantityShortfallDelta(new BigDecimal("0.125"));

        AssetLedgerPoint currentHold = point(
                "12",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.TRANSFER,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0.5",
                "1000",
                "0",
                "0",
                "0.5",
                "1000"
        );
        currentHold.setNormalizedType("EXTERNAL_TRANSFER_IN");
        currentHold.setTxHash("0xcurrent");
        currentHold.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        currentHold.setBasisBackedQuantityAfter(new BigDecimal("0.25"));
        currentHold.setUncoveredQuantityAfter(new BigDecimal("0.25"));
        currentHold.setHasIncompleteHistoryAfter(true);
        currentHold.setHasUnresolvedFlagsAfter(true);
        currentHold.setUnresolvedFlagCountAfter(1);

        when(userSessionRepository.findById("session-7")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-7",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-7",
                "FAMILY:ETH"
        )).thenReturn(List.of(shortfallSource, currentHold));
        when(normalizedTransactionRepository.findAllById(List.of("11", "12"))).thenReturn(List.of());
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0.5")
        ));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-7", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().uncoveredBuckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.latestTxHash()).isEqualTo("0xcurrent");
            assertThat(bucket.uncoveredQuantity()).isEqualByComparingTo("0.25");
        });
        assertThat(view.current().shortfallSources()).singleElement().satisfies(source -> {
            assertThat(source.txHash()).isEqualTo("0xshortfall");
            assertThat(source.networkId()).isEqualTo("BASE");
            assertThat(source.normalizedType()).isEqualTo("LP_ENTRY");
            assertThat(source.protocolName()).isEqualTo("PancakeSwap");
            assertThat(source.quantityShortfall()).isEqualByComparingTo("0.125");
        });
    }

    @Test
    void sessionFamilyLedgerCollapsesUniverseInternalTransferIntoSingleDisplayEvent() {
        UserSession session = new UserSession();
        session.setId("session-5");
        UserSession.SessionWallet walletA = new UserSession.SessionWallet();
        walletA.setAddress("wallet-a");
        walletA.setNetworks(List.of(NetworkId.ETHEREUM));
        UserSession.SessionWallet walletB = new UserSession.SessionWallet();
        walletB.setAddress("wallet-b");
        walletB.setNetworks(List.of(NetworkId.ETHEREUM));
        session.setWallets(List.of(walletA, walletB));

        AssetLedgerPoint transferOut = point(
                "50",
                "wallet-a",
                NetworkId.ETHEREUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_OUT,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.0024",
                "-6.59",
                "0",
                "0",
                "0",
                "0"
        );
        transferOut.setNormalizedType("EXTERNAL_TRANSFER_OUT");
        transferOut.setTxHash("0xinternal");
        transferOut.setMatchedCounterparty("wallet-b");

        AssetLedgerPoint gasOnly = point(
                "50",
                "wallet-a",
                NetworkId.ETHEREUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.GAS_ONLY,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.000031",
                "0",
                "0",
                "-0.07",
                "0",
                "0"
        );
        gasOnly.setNormalizedType("EXTERNAL_TRANSFER_OUT");
        gasOnly.setTxHash("0xinternal");
        gasOnly.setMatchedCounterparty("wallet-b");

        AssetLedgerPoint transferIn = point(
                "51",
                "wallet-b",
                NetworkId.ETHEREUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0.0024",
                "6.59",
                "0",
                "0",
                "1",
                "100"
        );
        transferIn.setNormalizedType("EXTERNAL_TRANSFER_IN");
        transferIn.setTxHash("0xinternal");
        transferIn.setMatchedCounterparty("wallet-a");

        NormalizedTransaction outTx = new NormalizedTransaction();
        outTx.setId("50");
        outTx.setTxHash("0xinternal");
        outTx.setNetworkId(NetworkId.ETHEREUM);
        outTx.setWalletAddress("wallet-a");
        outTx.setMatchedCounterparty("wallet-b");
        outTx.setFlows(List.of(flow("TRANSFER", "ETH", "-0.0024", null)));

        NormalizedTransaction inTx = new NormalizedTransaction();
        inTx.setId("51");
        inTx.setTxHash("0xinternal");
        inTx.setNetworkId(NetworkId.ETHEREUM);
        inTx.setWalletAddress("wallet-b");
        inTx.setMatchedCounterparty("wallet-a");
        inTx.setFlows(List.of(flow("TRANSFER", "ETH", "0.0024", null)));

        when(userSessionRepository.findById("session-5")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-5",
                List.of("wallet-a", "wallet-b"),
                List.of("wallet-a", "wallet-b")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-5",
                "FAMILY:ETH"
        )).thenReturn(List.of(transferOut, gasOnly, transferIn));
        when(normalizedTransactionRepository.findAllById(List.of("50", "51")))
                .thenReturn(List.of(outTx, inTx));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.ETHEREUM, "ETH", "0"),
                balance("wallet-b", NetworkId.ETHEREUM, "ETH", "1")
        ));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-5", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).singleElement().satisfies(entry -> {
            assertThat(entry.eventGroupId()).contains(":internal:");
            assertThat(entry.normalizedType()).isEqualTo("INTERNAL_TRANSFER");
            assertThat(entry.fromAddress()).isEqualTo("wallet-a");
            assertThat(entry.toAddress()).isEqualTo("wallet-b");
            assertThat(entry.memberNormalizedTransactionIds()).containsExactly("50", "51");
            assertThat(entry.quantityDelta()).isEqualByComparingTo("-0.000031");
            assertThat(entry.gasDeltaUsd()).isEqualByComparingTo("-0.07");
        });
        assertThat(view.events()).singleElement().satisfies(event -> {
            assertThat(event.normalizedType()).isEqualTo("INTERNAL_TRANSFER");
            assertThat(event.memberNormalizedTransactionIds()).containsExactly("50", "51");
            assertThat(event.fromAddress()).isEqualTo("wallet-a");
            assertThat(event.toAddress()).isEqualTo("wallet-b");
        });
        assertThat(view.ledgerPoints()).hasSize(3);
    }

    @Test
    void sessionFamilyLedgerCollapsesIntegrationToWalletTransferIntoSingleDisplayEvent() {
        UserSession session = new UserSession();
        session.setId("session-6");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint transferOut = point(
                "60",
                "BYBIT:33625378",
                null,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_OUT,
                AssetLedgerPoint.LifecycleKind.TRANSFER,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.0039528",
                "-10.80",
                "0",
                "0",
                "0.00154",
                "4.20"
        );
        transferOut.setNormalizedType("EXTERNAL_TRANSFER_OUT");
        transferOut.setTxHash("0xintegration");
        transferOut.setMatchedCounterparty("wallet-a");

        AssetLedgerPoint transferIn = point(
                "61",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.TRANSFER,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0.0039528",
                "10.75",
                "0",
                "0",
                "0.0039528",
                "10.75"
        );
        transferIn.setNormalizedType("EXTERNAL_TRANSFER_IN");
        transferIn.setTxHash("0xintegration");
        transferIn.setMatchedCounterparty(null);

        AssetLedgerPoint unrelated = point(
                "62",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.GAS_ONLY,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.00001",
                "0",
                "0",
                "-0.02",
                "0.00394",
                "10.75"
        );
        unrelated.setNormalizedType("SWAP");
        unrelated.setTxHash("0xother");
        unrelated.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));
        transferOut.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        transferIn.setBlockTimestamp(Instant.parse("2026-04-05T10:02:00Z"));

        NormalizedTransaction outTx = new NormalizedTransaction();
        outTx.setId("60");
        outTx.setTxHash("0xintegration");
        outTx.setWalletAddress("BYBIT:33625378");
        outTx.setMatchedCounterparty("wallet-a");
        outTx.setFlows(List.of(flow("TRANSFER", "ETH", "-0.0039528", null)));

        NormalizedTransaction inTx = new NormalizedTransaction();
        inTx.setId("61");
        inTx.setTxHash("0xintegration");
        inTx.setNetworkId(NetworkId.ARBITRUM);
        inTx.setWalletAddress("wallet-a");
        inTx.setFlows(List.of(flow("BUY", "ETH", "0.0039528", null)));

        when(userSessionRepository.findById("session-6")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-6",
                List.of("wallet-a", "BYBIT:33625378"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-6",
                "FAMILY:ETH"
        )).thenReturn(List.of(transferOut, unrelated, transferIn));
        when(normalizedTransactionRepository.findAllById(List.of("60", "62", "61")))
                .thenReturn(List.of(outTx, inTx, normalized("62", "1inch", "ETH", "SELL", "-0.00001", null)));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.ARBITRUM, "ETH", "0.0039528")
        ));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-6", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        assertThat(view.timeline()).anySatisfy(entry -> {
            assertThat(entry.normalizedType()).isEqualTo("INTERNAL_TRANSFER");
            assertThat(entry.fromAddress()).isEqualTo("BYBIT:33625378");
            assertThat(entry.toAddress()).isEqualTo("wallet-a");
            assertThat(entry.memberNormalizedTransactionIds()).containsExactly("60", "61");
        });
        assertThat(view.events()).anySatisfy(event -> {
            assertThat(event.normalizedType()).isEqualTo("INTERNAL_TRANSFER");
            assertThat(event.fromAddress()).isEqualTo("BYBIT:33625378");
            assertThat(event.toAddress()).isEqualTo("wallet-a");
        });
    }

    @Test
    void sessionFamilyLedgerUsesRowLocalCounterpartyForProtocolEventEndpoints() {
        UserSession session = new UserSession();
        session.setId("session-8");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint bridgeOut = point(
                "70",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_OUT,
                AssetLedgerPoint.LifecycleKind.BRIDGE,
                AssetLedgerPoint.LifecycleStage.SOURCE,
                "-1",
                "-100",
                "0",
                "0",
                "0",
                "0"
        );
        bridgeOut.setNormalizedType("BRIDGE_OUT");
        bridgeOut.setTxHash("0xbridgeout");

        AssetLedgerPoint bridgeIn = point(
                "71",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.BRIDGE,
                AssetLedgerPoint.LifecycleStage.DESTINATION,
                "0.99",
                "100",
                "0",
                "0",
                "0.99",
                "100"
        );
        bridgeIn.setNormalizedType("BRIDGE_IN");
        bridgeIn.setTxHash("0xbridgein");
        bridgeIn.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        NormalizedTransaction bridgeOutTx = normalized("70", "Across", "ETH", "TRANSFER", "-1", null);
        bridgeOutTx.setWalletAddress("wallet-a");
        bridgeOutTx.setCounterpartyAddress("0x1111111111111111111111111111111111111111");
        NormalizedTransaction bridgeInTx = normalized("71", "Across", "ETH", "TRANSFER", "0.99", null);
        bridgeInTx.setWalletAddress("wallet-a");
        bridgeInTx.setCounterpartyAddress("0x2222222222222222222222222222222222222222");

        when(userSessionRepository.findById("session-8")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-8",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-8",
                "FAMILY:ETH"
        )).thenReturn(List.of(bridgeOut, bridgeIn));
        when(normalizedTransactionRepository.findAllById(List.of("70", "71")))
                .thenReturn(List.of(bridgeOutTx, bridgeInTx));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0.99")
        ));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-8", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        assertThat(view.timeline().get(0)).satisfies(entry -> {
            assertThat(entry.normalizedType()).isEqualTo("BRIDGE_OUT");
            assertThat(entry.fromAddress()).isEqualTo("wallet-a");
            assertThat(entry.toAddress()).isEqualTo("0x1111111111111111111111111111111111111111");
        });
        assertThat(view.timeline().get(1)).satisfies(entry -> {
            assertThat(entry.normalizedType()).isEqualTo("BRIDGE_IN");
            assertThat(entry.fromAddress()).isEqualTo("0x2222222222222222222222222222222222222222");
            assertThat(entry.toAddress()).isEqualTo("wallet-a");
        });
        assertThat(view.events().get(0).toAddress()).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(view.events().get(1).fromAddress()).isEqualTo("0x2222222222222222222222222222222222222222");
    }

    @Test
    void sessionFamilyLedgerTimelineExcludesLpReceiptSharesFromEthAvcoAggregation() {
        UserSession session = new UserSession();
        session.setId("session-lp-exclude");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint ethBuy = point(
                "10",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "2000",
                "0",
                "0",
                "1",
                "2000"
        );
        ethBuy.setNormalizedType("BUY");
        ethBuy.setAvcoAfterUsd(new BigDecimal("2000"));

        AssetLedgerPoint lpReceiptIn = point(
                "11",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "513.47",
                "1182",
                "0",
                "0",
                "513.47",
                "1182"
        );
        lpReceiptIn.setAssetSymbol("LP-RECEIPT:arbitrum:pancakeswap:196975");
        lpReceiptIn.setNormalizedTransactionId("11");
        lpReceiptIn.setNormalizedType("LP_ENTRY");
        lpReceiptIn.setAvcoAfterUsd(new BigDecimal("2.30"));
        lpReceiptIn.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        when(userSessionRepository.findById("session-lp-exclude")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-lp-exclude",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-lp-exclude",
                "FAMILY:ETH"
        )).thenReturn(List.of(ethBuy, lpReceiptIn));
        when(normalizedTransactionRepository.findAllById(List.of("10", "11")))
                .thenReturn(List.of(
                        normalized("10", "Coinbase", "ETH", "BUY", "1", "2000"),
                        normalized("11", "PancakeSwap", "LP-RECEIPT:arbitrum:pancakeswap:196975", "TRANSFER", "513.47", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.ARBITRUM, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-lp-exclude", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(1);
        assertThat(view.timeline().getFirst().avcoAfterUsd()).isEqualByComparingTo("2000");
        assertThat(view.timeline().getFirst().avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_PRIMARY_FLOW);
        assertThat(view.ledgerPoints()).hasSize(2);
    }

    @Test
    void sessionFamilyLedgerTimelineKeepsWethAvcoOnLpExitTransaction() {
        UserSession session = new UserSession();
        session.setId("session-lp-weth");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint wethOut = point(
                "20",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.5",
                "-1361",
                "0",
                "0",
                "0.5",
                "1361"
        );
        wethOut.setAssetSymbol("WETH");
        wethOut.setAccountingAssetIdentity("WETH:0x82af4942262814fb3571b8c24217807");
        wethOut.setNormalizedType("LP_EXIT");
        wethOut.setAvcoAfterUsd(new BigDecimal("2722"));
        wethOut.setNormalizedTransactionId("20");

        AssetLedgerPoint lpReceiptIn = point(
                "20",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "513.47",
                "1182",
                "0",
                "0",
                "513.47",
                "1182"
        );
        lpReceiptIn.setAssetSymbol("LP-RECEIPT:arbitrum:pancakeswap:196975");
        lpReceiptIn.setNormalizedTransactionId("20");
        lpReceiptIn.setNormalizedType("LP_EXIT");
        lpReceiptIn.setAvcoAfterUsd(new BigDecimal("2.30"));

        when(userSessionRepository.findById("session-lp-weth")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-lp-weth",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-lp-weth",
                "FAMILY:ETH"
        )).thenReturn(List.of(wethOut, lpReceiptIn));
        when(normalizedTransactionRepository.findAllById(List.of("20")))
                .thenReturn(List.of(normalized("20", "PancakeSwap", "WETH", "TRANSFER", "-0.5", null)));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.ARBITRUM, "WETH", "0.5")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-lp-weth", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(1);
        assertThat(view.timeline().getFirst().avcoAfterUsd()).isEqualByComparingTo("2722");
        assertThat(view.timeline().getFirst().avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_PRIMARY_FLOW);
    }

    @Test
    void sessionFamilyLedgerTimelineDoesNotCollapseToFamilyRollupAfterLargeLpHistory() {
        UserSession session = new UserSession();
        session.setId("session-lp-tail");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint legacyLpReceipt = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "58674",
                "103000",
                "0",
                "0",
                "58674",
                "103000"
        );
        legacyLpReceipt.setAssetSymbol("LP-RECEIPT:base:pancakeswap:477096");
        legacyLpReceipt.setAvcoAfterUsd(new BigDecimal("1.75"));
        legacyLpReceipt.setBlockTimestamp(Instant.parse("2026-04-01T10:00:00Z"));

        AssetLedgerPoint ethAcquire = point(
                "2",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "2062",
                "0",
                "0",
                "1",
                "2062"
        );
        ethAcquire.setNormalizedType("BUY");
        ethAcquire.setAvcoAfterUsd(new BigDecimal("2062"));
        ethAcquire.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));

        when(userSessionRepository.findById("session-lp-tail")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-lp-tail",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-lp-tail",
                "FAMILY:ETH"
        )).thenReturn(List.of(legacyLpReceipt, ethAcquire));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "PancakeSwap", "LP-RECEIPT:base:pancakeswap:477096", "TRANSFER", "58674", null),
                        normalized("2", "Coinbase", "ETH", "BUY", "1", "2062")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-lp-tail", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(1);
        assertThat(view.timeline().getFirst().avcoAfterUsd()).isEqualByComparingTo("2062");
        assertThat(view.timeline().getFirst().avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_PRIMARY_FLOW);
    }

    @Test
    void currentStateAggregatesBybitUmbrellaForBybitOnlyFamily() {
        UserSession session = bybitSession("session-bybit-ldo", "33625378");
        AssetLedgerPoint uta = bybitVenuePoint("10", "BYBIT:33625378:UTA", "SYMBOL:LDO", "LDO", "100", "60", "2");
        AssetLedgerPoint fund = bybitVenuePoint("11", "BYBIT:33625378:FUND", "SYMBOL:LDO", "LDO", "200", "100", "2");
        AssetLedgerPoint earn = bybitVenuePoint("12", "BYBIT:33625378:EARN", "SYMBOL:LDO", "LDO", "37.732748", "23.560728", "2");

        when(userSessionRepository.findById("session-bybit-ldo")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-bybit-ldo",
                List.of("BYBIT:33625378", "BYBIT:33625378:UTA", "BYBIT:33625378:FUND", "BYBIT:33625378:EARN"),
                List.of()
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-bybit-ldo",
                "SYMBOL:LDO"
        )).thenReturn(List.of(uta, fund, earn));
        when(normalizedTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of());
        when(bybitLiveBalanceService.getUmbrellaBalances("bybit-int-33625378"))
                .thenReturn(Map.of("LDO", new BigDecimal("500")));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-bybit-ldo", "SYMBOL:LDO")
                .orElseThrow();

        assertThat(view.current().quantity()).isEqualByComparingTo("500");
        assertThat(view.current().coveredQuantity()).isEqualByComparingTo("183.560728");
        assertThat(view.current().uncoveredQuantity()).isEqualByComparingTo("316.439272");
        assertThat(view.current().uncoveredBuckets()).hasSize(3);
        assertThat(view.current().uncoveredBuckets())
                .extracting(AssetLedgerQueryService.UncoveredBucketView::walletAddress)
                .containsExactlyInAnyOrder("BYBIT:33625378:UTA", "BYBIT:33625378:FUND", "BYBIT:33625378:EARN");
    }

    @Test
    void currentStateClampsBybitUmbrellaToLive() {
        UserSession session = bybitSession("session-bybit-clamp", "33625378");
        AssetLedgerPoint uta = bybitVenuePoint("20", "BYBIT:33625378:UTA", "SYMBOL:LDO", "LDO", "100", "80", "2");

        when(userSessionRepository.findById("session-bybit-clamp")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-bybit-clamp",
                List.of("BYBIT:33625378:UTA"),
                List.of()
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-bybit-clamp",
                "SYMBOL:LDO"
        )).thenReturn(List.of(uta));
        when(normalizedTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of());
        when(bybitLiveBalanceService.getUmbrellaBalances("bybit-int-33625378"))
                .thenReturn(Map.of("LDO", new BigDecimal("40")));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-bybit-clamp", "SYMBOL:LDO")
                .orElseThrow();

        assertThat(view.current().quantity()).isEqualByComparingTo("40");
        assertThat(view.current().coveredQuantity()).isEqualByComparingTo("32");
        assertThat(view.current().uncoveredQuantity()).isEqualByComparingTo("8");
    }

    @Test
    void currentStateDropsBybitUmbrellaWhenLiveZero() {
        UserSession session = bybitSession("session-bybit-zero", "33625378");
        AssetLedgerPoint uta = bybitVenuePoint("30", "BYBIT:33625378:UTA", "SYMBOL:LDO", "LDO", "100", "80", "2");

        when(userSessionRepository.findById("session-bybit-zero")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-bybit-zero",
                List.of("BYBIT:33625378:UTA"),
                List.of()
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-bybit-zero",
                "SYMBOL:LDO"
        )).thenReturn(List.of(uta));
        when(normalizedTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of());
        lenient().when(bybitLiveBalanceService.getUmbrellaBalances("bybit-int-33625378"))
                .thenReturn(Map.of("LDO", BigDecimal.ZERO));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-bybit-zero", "SYMBOL:LDO")
                .orElseThrow();

        assertThat(view.current().quantity()).isZero();
        assertThat(view.current().coveredQuantity()).isZero();
        assertThat(view.current().uncoveredBuckets()).isEmpty();
    }

    @Test
    void currentStateMergesOnChainAndBybitForFamily() {
        UserSession session = bybitSession("session-bybit-mnt", "33625378");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint onChainPoint = point(
                "40",
                "wallet-a",
                NetworkId.MANTLE,
                "FAMILY:MNT",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "10",
                "5",
                "0",
                "0",
                "10",
                "5"
        );
        onChainPoint.setAssetSymbol("MNT");
        onChainPoint.setFamilyDisplaySymbol("MNT");
        onChainPoint.setAccountingAssetIdentity("NATIVE:MANTLE");
        onChainPoint.setBasisBackedQuantityAfter(new BigDecimal("8"));

        AssetLedgerPoint bybitUta = bybitVenuePoint("41", "BYBIT:33625378:UTA", "FAMILY:MNT", "MNT", "100", "90", "0.05");

        when(userSessionRepository.findById("session-bybit-mnt")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-bybit-mnt",
                List.of("wallet-a", "BYBIT:33625378:UTA"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-bybit-mnt",
                "FAMILY:MNT"
        )).thenReturn(List.of(onChainPoint, bybitUta));
        when(normalizedTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.MANTLE, "MNT", "10")
        ));
        when(bybitLiveBalanceService.getUmbrellaBalances("bybit-int-33625378"))
                .thenReturn(Map.of("MNT", new BigDecimal("200")));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-bybit-mnt", "FAMILY:MNT")
                .orElseThrow();

        assertThat(view.current().quantity()).isEqualByComparingTo("210");
        assertThat(view.current().coveredQuantity()).isEqualByComparingTo("98");
    }

    @Test
    void currentStateIgnoresBybitVenuesForOtherFamilies() {
        UserSession session = bybitSession("session-bybit-isolate", "33625378");
        AssetLedgerPoint ldoPoint = bybitVenuePoint("50", "BYBIT:33625378:UTA", "SYMBOL:LDO", "LDO", "100", "80", "2");

        when(userSessionRepository.findById("session-bybit-isolate")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-bybit-isolate",
                List.of("BYBIT:33625378:UTA"),
                List.of()
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-bybit-isolate",
                "FAMILY:USDT"
        )).thenReturn(List.of());
        when(normalizedTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of());
        lenient().when(bybitLiveBalanceService.getUmbrellaBalances("bybit-int-33625378"))
                .thenReturn(Map.of("LDO", new BigDecimal("500")));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-bybit-isolate", "FAMILY:USDT")
                .orElseThrow();

        assertThat(view.current().quantity()).isZero();
        assertThat(view.current().coveredQuantity()).isZero();
        assertThat(view.current().uncoveredBuckets()).isEmpty();
    }

    @Test
    void fullSessionCurrentIncludesBybitLedgerPointsEvenWithoutLiveIntegration() {
        // Session has NO Bybit integration — current() will only show on-chain balance.
        // fullSessionCurrent() must still include the Bybit replay points.
        UserSession session = new UserSession();
        session.setId("session-fsc");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));
        // no integrations

        AssetLedgerPoint onChainEth = point(
                "100",
                "wallet-a",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "3.06",
                "5244.66",
                "0",
                "0",
                "3.06",
                "5244.66"
        );
        onChainEth.setAssetSymbol("AMANWETH");
        onChainEth.setAccountingAssetIdentity("0xamanweth");
        onChainEth.setAccountingFamilyIdentity("FAMILY:ETH");
        onChainEth.setBasisBackedQuantityAfter(new BigDecimal("3.06"));
        onChainEth.setAvcoAfterUsd(new BigDecimal("1714"));

        // Bybit venue ledger point (no live balance integration in this session)
        AssetLedgerPoint bybitEth = new AssetLedgerPoint();
        bybitEth.setId("101:BYBIT:33625378:UTA");
        bybitEth.setWalletAddress("BYBIT:33625378:UTA");
        bybitEth.setNetworkId(null);
        bybitEth.setAccountingAssetIdentity("SYMBOL:ETH");
        bybitEth.setAccountingFamilyIdentity("FAMILY:ETH");
        bybitEth.setFamilyDisplaySymbol("ETH");
        bybitEth.setAssetSymbol("ETH");
        bybitEth.setNormalizedTransactionId("101");
        bybitEth.setTxHash("0x101");
        bybitEth.setBlockTimestamp(java.time.Instant.parse("2026-04-05T10:05:00Z"));
        bybitEth.setReplaySequence(101L);
        bybitEth.setTransactionIndex(0);
        bybitEth.setNormalizedType("INTERNAL_TRANSFER");
        bybitEth.setLifecycleKind(AssetLedgerPoint.LifecycleKind.SPOT);
        bybitEth.setLifecycleStage(AssetLedgerPoint.LifecycleStage.SINGLE);
        bybitEth.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_IN);
        bybitEth.setQuantityAfter(new BigDecimal("0.42"));
        bybitEth.setBasisBackedQuantityAfter(new BigDecimal("0.42"));
        bybitEth.setAvcoAfterUsd(new BigDecimal("2274"));
        bybitEth.setTotalCostBasisAfterUsd(new BigDecimal("955.08"));
        bybitEth.setQuantityDelta(new BigDecimal("0.42"));
        bybitEth.setCostBasisDeltaUsd(BigDecimal.ZERO);
        bybitEth.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        bybitEth.setGasDeltaUsd(BigDecimal.ZERO);
        bybitEth.setUncoveredQuantityDelta(BigDecimal.ZERO);
        bybitEth.setQuantityShortfallAfter(BigDecimal.ZERO);
        bybitEth.setUncoveredQuantityAfter(BigDecimal.ZERO);

        when(userSessionRepository.findById("session-fsc")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-fsc",
                List.of("wallet-a", "BYBIT:33625378:UTA"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-fsc",
                "FAMILY:ETH"
        )).thenReturn(List.of(onChainEth, bybitEth));
        when(normalizedTransactionRepository.findAllById(any())).thenReturn(List.of());
        OnChainBalance amanwethBalance = balance("wallet-a", NetworkId.ARBITRUM, "AMANWETH", "3.06");
        amanwethBalance.setAssetContract("0xamanweth");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(amanwethBalance));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-fsc", "FAMILY:ETH")
                .orElseThrow();

        // current() = on-chain only (no Bybit integration → Bybit section skipped)
        assertThat(view.current().quantity()).isEqualByComparingTo("3.06");

        // fullSessionCurrent() = on-chain + Bybit ledger state
        assertThat(view.fullSessionCurrent().quantity()).isGreaterThan(view.current().quantity());
        assertThat(view.fullSessionCurrent().quantity()).isEqualByComparingTo("3.48");
        assertThat(view.fullSessionCurrent().coveredQuantity()).isEqualByComparingTo("3.48");

        // AVCO blended: (3.06 * 1714 + 0.42 * 2274) / 3.48 ≈ 1781–1783
        assertThat(view.fullSessionCurrent().avcoUsd())
                .isGreaterThan(new BigDecimal("1780"))
                .isLessThan(new BigDecimal("1790"));
    }

    @Test
    void sessionFamilyLedgerTimelineCarriesForwardAvcoOnEarnWithdraw() {
        UserSession session = bybitSession("session-earn-carry", "33625378");

        AssetLedgerPoint priorBuy = bybitVenuePoint("10", "BYBIT:33625378", "FAMILY:ETH", "ETH", "1.0", "1.0", "2873");
        priorBuy.setBasisEffect(AssetLedgerPoint.BasisEffect.ACQUIRE);
        priorBuy.setNormalizedType("BUY");
        priorBuy.setCostBasisDeltaUsd(new BigDecimal("2873"));
        priorBuy.setQuantityDelta(new BigDecimal("1.0"));
        priorBuy.setTotalCostBasisAfterUsd(new BigDecimal("2873"));

        AssetLedgerPoint earnOut = bybitVenuePoint("11", "BYBIT:33625378:EARN", "FAMILY:ETH", "ETH", "0", "0", "2873");
        earnOut.setNormalizedTransactionId("11");
        earnOut.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        earnOut.setNormalizedType("LENDING_WITHDRAW");
        earnOut.setQuantityDelta(new BigDecimal("-0.151"));
        earnOut.setQuantityAfter(BigDecimal.ZERO);
        earnOut.setBasisBackedQuantityAfter(BigDecimal.ZERO);
        earnOut.setTotalCostBasisAfterUsd(BigDecimal.ZERO);
        earnOut.setAvcoAfterUsd(null);
        earnOut.setCostBasisDeltaUsd(new BigDecimal("-434"));
        earnOut.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        AssetLedgerPoint fundIn = bybitVenuePoint("11", "BYBIT:33625378:FUND", "FAMILY:ETH", "ETH", "0.151", "0.151", "2873");
        fundIn.setNormalizedTransactionId("11");
        fundIn.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        fundIn.setNormalizedType("LENDING_WITHDRAW");
        fundIn.setQuantityDelta(new BigDecimal("0.151"));
        fundIn.setTotalCostBasisAfterUsd(new BigDecimal("434"));
        fundIn.setAvcoAfterUsd(null);
        fundIn.setCostBasisDeltaUsd(new BigDecimal("434"));
        fundIn.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        when(userSessionRepository.findById("session-earn-carry")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-earn-carry",
                List.of("BYBIT:33625378", "BYBIT:33625378:FUND", "BYBIT:33625378:EARN"),
                List.of("BYBIT:33625378", "BYBIT:33625378:FUND", "BYBIT:33625378:EARN")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-earn-carry",
                "FAMILY:ETH"
        )).thenReturn(List.of(priorBuy, earnOut, fundIn));
        when(normalizedTransactionRepository.findAllById(List.of("10", "11")))
                .thenReturn(List.of(
                        normalized("10", "Bybit", "ETH", "BUY", "1.0", "2873"),
                        normalized("11", "Bybit", "ETH", "TRANSFER", "-0.151", null)
                ));
        lenient().when(bybitLiveBalanceService.getUmbrellaBalances("bybit-int-33625378"))
                .thenReturn(Map.of("ETH", new BigDecimal("1.0")));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-earn-carry", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        assertThat(view.timeline().get(1).avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_CARRIED_FORWARD);
        assertThat(view.timeline().get(1).avcoAfterUsd()).isEqualByComparingTo("2873");
    }


    private UserSession bybitSession(String sessionId, String uid) {
        UserSession session = new UserSession();
        session.setId(sessionId);
        session.setWallets(List.of());
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setStatus(UserSession.IntegrationStatus.READY);
        integration.setAccountRef("BYBIT:" + uid);
        integration.setIntegrationId("bybit-int-" + uid);
        session.setIntegrations(List.of(integration));
        return session;
    }

    private AssetLedgerPoint bybitVenuePoint(
            String normalizedTransactionId,
            String venueWallet,
            String familyIdentity,
            String symbol,
            String quantityAfter,
            String basisBackedAfter,
            String avcoUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(normalizedTransactionId + ":" + venueWallet);
        point.setWalletAddress(venueWallet);
        point.setNetworkId(null);
        point.setAccountingAssetIdentity("SYMBOL:" + symbol);
        point.setAccountingFamilyIdentity(familyIdentity);
        point.setFamilyDisplaySymbol(symbol);
        point.setAssetSymbol(symbol);
        point.setAssetContract(null);
        point.setNormalizedTransactionId(normalizedTransactionId);
        point.setTxHash("0x" + normalizedTransactionId);
        point.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        point.setReplaySequence(Long.parseLong(normalizedTransactionId));
        point.setTransactionIndex(0);
        point.setNormalizedType("INTERNAL_TRANSFER");
        point.setLifecycleKind(AssetLedgerPoint.LifecycleKind.SPOT);
        point.setLifecycleStage(AssetLedgerPoint.LifecycleStage.SINGLE);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_IN);
        point.setProtocolName("Bybit");
        point.setQuantityDelta(new BigDecimal(quantityAfter));
        point.setQuantityAfter(new BigDecimal(quantityAfter));
        point.setBasisBackedQuantityAfter(new BigDecimal(basisBackedAfter));
        point.setTotalCostBasisAfterUsd(new BigDecimal(basisBackedAfter).multiply(new BigDecimal(avcoUsd)));
        point.setAvcoAfterUsd(new BigDecimal(avcoUsd));
        point.setCostBasisDeltaUsd(BigDecimal.ZERO);
        point.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        point.setGasDeltaUsd(BigDecimal.ZERO);
        point.setUncoveredQuantityDelta(BigDecimal.ZERO);
        point.setQuantityShortfallAfter(BigDecimal.ZERO);
        point.setUncoveredQuantityAfter(
                new BigDecimal(quantityAfter).subtract(new BigDecimal(basisBackedAfter)).max(BigDecimal.ZERO)
        );
        return point;
    }

    private NormalizedTransaction normalized(
            String id,
            String protocolName,
            String symbol,
            String role,
            String quantityDelta,
            String unitPriceUsd
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setProtocolName(protocolName);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.valueOf(role));
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            String role,
            String symbol,
            String quantityDelta,
            String unitPriceUsd
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.valueOf(role));
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        return flow;
    }

    private AssetLedgerPoint point(
            String normalizedTransactionId,
            String walletAddress,
            NetworkId networkId,
            String familyIdentity,
            AssetLedgerPoint.BasisEffect basisEffect,
            AssetLedgerPoint.LifecycleKind lifecycleKind,
            AssetLedgerPoint.LifecycleStage lifecycleStage,
            String quantityDelta,
            String costBasisDeltaUsd,
            String realisedPnlDeltaUsd,
            String gasDeltaUsd,
            String quantityAfter,
            String totalCostBasisAfterUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(normalizedTransactionId + ":" + walletAddress + ":" + Objects.toString(networkId, "BYBIT"));
        point.setWalletAddress(walletAddress);
        point.setNetworkId(networkId);
        point.setAccountingAssetIdentity(networkId == null ? "BYBIT:ETH" : "NATIVE:" + networkId.name());
        point.setAccountingFamilyIdentity(familyIdentity);
        point.setFamilyDisplaySymbol("ETH");
        point.setAssetSymbol("ETH");
        point.setAssetContract(networkId == null ? "BYBIT:ETH" : "NATIVE:" + networkId.name());
        point.setNormalizedTransactionId(normalizedTransactionId);
        point.setTxHash("0x" + normalizedTransactionId);
        point.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        point.setReplaySequence(Long.parseLong(normalizedTransactionId));
        point.setTransactionIndex(0);
        point.setNormalizedType("BRIDGE");
        point.setLifecycleKind(lifecycleKind);
        point.setLifecycleStage(lifecycleStage);
        point.setBasisEffect(basisEffect);
        point.setProtocolName("Coinbase");
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        point.setRealisedPnlDeltaUsd(new BigDecimal(realisedPnlDeltaUsd));
        point.setGasDeltaUsd(new BigDecimal(gasDeltaUsd));
        point.setQuantityAfter(new BigDecimal(quantityAfter));
        point.setTotalCostBasisAfterUsd(new BigDecimal(totalCostBasisAfterUsd));
        point.setBasisBackedQuantityAfter(new BigDecimal(quantityAfter));
        if (new BigDecimal(quantityAfter).signum() > 0) {
            point.setAvcoAfterUsd(new BigDecimal(totalCostBasisAfterUsd).divide(new BigDecimal(quantityAfter), java.math.MathContext.DECIMAL128));
        }
        point.setUncoveredQuantityDelta(BigDecimal.ZERO);
        point.setQuantityShortfallAfter(BigDecimal.ZERO);
        point.setUncoveredQuantityAfter(BigDecimal.ZERO);
        return point;
    }

    private OnChainBalance balance(
            String walletAddress,
            NetworkId networkId,
            String assetSymbol,
            String quantity
    ) {
        OnChainBalance balance = new OnChainBalance();
        balance.setId(walletAddress + ":" + networkId + ":" + assetSymbol);
        balance.setWalletAddress(walletAddress);
        balance.setNetworkId(networkId);
        balance.setAssetSymbol(assetSymbol);
        balance.setAssetContract("NATIVE:" + networkId.name());
        balance.setQuantity(new BigDecimal(quantity));
        balance.setCapturedAt(Instant.parse("2026-04-05T10:30:00Z"));
        return balance;
    }
}
