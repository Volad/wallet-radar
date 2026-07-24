package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.breakeven.BreakEvenAttributionLoader;
import com.walletradar.application.costbasis.breakeven.BreakEvenAttributionService;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator;
import com.walletradar.application.costbasis.breakeven.OffsetLane;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolRepository;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.application.session.application.AccountingUniverseService;
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
    private CexLiveBalancePort cexLiveBalancePort;
    @Mock
    private LpReceiptBasisPoolRepository lpReceiptBasisPoolRepository;

    private AssetLedgerQueryService service() {
        return service(new BreakEvenAttributionService(
                new BreakEvenAttributionLoader(new com.fasterxml.jackson.databind.ObjectMapper())));
    }

    private AssetLedgerQueryService service(BreakEvenAttributionService attributionService) {
        return new AssetLedgerQueryService(
                userSessionRepository,
                assetLedgerPointRepository,
                normalizedTransactionRepository,
                accountingUniverseService,
                new AssetLedgerChartService(new BlendedExposureAvcoSeriesBuilder(), attributionService),
                new AssetLedgerReconciliationService(mongoOperations, cexLiveBalancePort),
                new LpReceiptBasisPoolService(lpReceiptBasisPoolRepository),
                new BreakEvenCalculator(attributionService),
                attributionService
        );
    }

    /**
     * ADR-062 (2026-07-21 amendment): an attribution service whose offset lane is forced (default
     * classpath lane is NET), so tests can exercise both lanes deterministically. Mirrors the
     * classpath cluster→family mappings.
     */
    private static BreakEvenAttributionService attributionServiceWithLane(OffsetLane offsetLane) {
        Map<String, String> sourceToTarget = new LinkedHashMap<>();
        sourceToTarget.put("CLUSTER:ETH_STAKING", "FAMILY:ETH");
        sourceToTarget.put("CLUSTER:SOL_STAKING", "FAMILY:SOL");
        sourceToTarget.put("CLUSTER:AVAX_STAKING", "FAMILY:AVAX");
        BreakEvenAttributionLoader.LoadedBreakEvenAttribution loaded =
                new BreakEvenAttributionLoader.LoadedBreakEvenAttribution(sourceToTarget, java.util.Set.of(), offsetLane);
        return new BreakEvenAttributionService(new BreakEvenAttributionLoader(new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public LoadedBreakEvenAttribution loadFromClasspath() {
                return loaded;
            }
        });
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
        // The wrap ledger point and the WETH on-chain balance must reconcile on the SAME position
        // bucket. Reconciliation keys the balance via AccountingAssetIdentitySupport.positionAssetIdentity
        // and the point via its stored accountingAssetIdentity. In production the wrapped-native contract
        // folds to NATIVE:BASE via NetworkNativeAssets, but that binding is process-global static state:
        // whether it is active in a unit run depends on test ORDER. Deriving the point identity from the
        // SAME resolver as the balance makes the bucket keys match regardless of binding state, so this
        // reconciles deterministically as coverage_gap (uncovered-with-a-known-point), never
        // missing_replay_point.
        wrapPoint.setAccountingAssetIdentity(AccountingAssetIdentitySupport.positionAssetIdentity(
                NetworkId.BASE, "WETH", "0x4200000000000000000000000000000000000006"));
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
    void sessionFamilyLedgerCreditsReceiptlessLockedCollateralBasisIntoCoverage() {
        // Receipt-less lending (e.g. Jupiter Lend on Solana): the LENDING_DEPOSIT REALLOCATE_OUTs the
        // underlying's basis (no receipt token to re-cover it) while a live-balance provider merges the
        // locked amount back into the native balance. Without the credit the merged balance reads as
        // held-but-uncovered; the parked net REALLOCATE_OUT basis must be credited back, capped by the
        // on-chain gap, so covered == held and AVCO blends liquid + locked.
        UserSession session = new UserSession();
        session.setId("session-recl");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        // After deposit: 0.5 liquid remains basis-backed at $60/ETH; 5.0 was reallocated out at $160/ETH.
        AssetLedgerPoint lendingOut = point(
                "1",
                "wallet-a",
                NetworkId.MANTLE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                AssetLedgerPoint.LifecycleKind.LENDING,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-5",
                "-800",
                "0",
                "0",
                "0.5",
                "30"
        );
        lendingOut.setNormalizedType("LENDING_DEPOSIT");
        lendingOut.setProtocolName("Jupiter Lend");

        when(userSessionRepository.findById("session-recl")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-recl",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-recl",
                "FAMILY:ETH"
        )).thenReturn(List.of(lendingOut));
        when(normalizedTransactionRepository.findAllById(List.of("1"))).thenReturn(List.of());
        // Merged native balance: 0.5 liquid + 5.0 still locked in the receipt-less lending position.
        OnChainBalance balance = balance("wallet-a", NetworkId.MANTLE, "ETH", "5.5");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(balance));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.CurrentStateView current =
                service.findSessionFamilyLedger("session-recl", "FAMILY:ETH").orElseThrow().current();

        assertThat(current.quantity()).isEqualByComparingTo("5.5");
        assertThat(current.coveredQuantity()).isEqualByComparingTo("5.5");
        assertThat(current.uncoveredQuantity()).isZero();
        // Blended AVCO = (0.5*60 + 800) / 5.5 = 830 / 5.5.
        assertThat(current.avcoUsd()).isEqualByComparingTo(new BigDecimal("830").divide(new BigDecimal("5.5"), java.math.MathContext.DECIMAL128));
        assertThat(current.uncoveredBuckets()).isEmpty();
    }

    @Test
    void sessionFamilyLedgerDoesNotCreditReceiptBearingLendingTwice() {
        // Receipt-bearing lending (Aave aToken) nets to ~0 within the family (basis lands on the aToken
        // bucket, reconciled via its own on_chain_balance), so the receipt-less credit must NOT fire.
        UserSession session = new UserSession();
        session.setId("session-recb");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint lendingIn = point(
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
        lendingIn.setAssetSymbol("aManWETH");
        lendingIn.setAssetContract("0xea00000000000000000000000000000000000000");
        lendingIn.setAccountingAssetIdentity("0xea00000000000000000000000000000000000000");
        lendingIn.setNormalizedType("LENDING_DEPOSIT");
        lendingIn.setProtocolName("Aave");

        when(userSessionRepository.findById("session-recb")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-recb",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-recb",
                "FAMILY:ETH"
        )).thenReturn(List.of(lendingIn));
        when(normalizedTransactionRepository.findAllById(List.of("1"))).thenReturn(List.of());
        OnChainBalance balance = balance("wallet-a", NetworkId.MANTLE, "aManWETH", "3.06");
        balance.setAssetContract("0xea00000000000000000000000000000000000000");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(balance));

        AssetLedgerQueryService service = service();
        AssetLedgerQueryService.CurrentStateView current =
                service.findSessionFamilyLedger("session-recb", "FAMILY:ETH").orElseThrow().current();

        // Covered fully via the aToken bucket itself; the receipt-less credit adds nothing (net IN).
        assertThat(current.coveredQuantity()).isEqualByComparingTo("3.06");
        assertThat(current.quantity()).isEqualByComparingTo("3.06");
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
        assertThat(view.timeline().getFirst().avcoKind()).isEqualTo("PRIMARY_FLOW");
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
        assertThat(view.timeline().getFirst().avcoKind()).isEqualTo("PRIMARY_FLOW");
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
        assertThat(view.timeline().getFirst().avcoKind()).isEqualTo("PRIMARY_FLOW");
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
        when(cexLiveBalancePort.getSnapshotView("bybit-int-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        Map.of("LDO", new BigDecimal("500")),
                        Instant.parse("2025-06-01T00:00:00Z"))));

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
        when(cexLiveBalancePort.getSnapshotView("bybit-int-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        Map.of("LDO", new BigDecimal("40")),
                        Instant.parse("2025-06-01T00:00:00Z"))));

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
        lenient().when(cexLiveBalancePort.getSnapshotView("bybit-int-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        Map.of("LDO", BigDecimal.ZERO),
                        Instant.parse("2025-06-01T00:00:00Z"))));

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
        when(cexLiveBalancePort.getSnapshotView("bybit-int-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        Map.of("MNT", new BigDecimal("200")),
                        Instant.parse("2025-06-01T00:00:00Z"))));

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
        lenient().when(cexLiveBalancePort.getSnapshotView("bybit-int-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        Map.of("LDO", new BigDecimal("500")),
                        Instant.parse("2025-06-01T00:00:00Z"))));

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
    void sessionFamilyLedgerTimelineHoldsFamilyAvcoAcrossEarnWithdraw() {
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
        fundIn.setTotalCostBasisAfterUsd(new BigDecimal("433.823"));
        fundIn.setAvcoAfterUsd(new BigDecimal("2873"));
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
        lenient().when(cexLiveBalancePort.getSnapshotView("bybit-int-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        Map.of("ETH", new BigDecimal("1.0")),
                        Instant.parse("2025-06-01T00:00:00Z"))));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-earn-carry", "FAMILY:ETH")
                .orElseThrow();

        // ADR-045 Method B: the covered-weighted family AVCO holds at ~$2873 across the earn
        // withdraw (EARN drains to 0 covered, FUND receives the same basis-backed ETH), and the
        // series self-chains (entry-1 before == entry-0 after). No CARRIED_FORWARD kind exists.
        assertThat(view.timeline()).hasSize(2);
        assertThat(view.timeline().get(0).avcoAfterUsd()).isEqualByComparingTo("2873");
        assertThat(view.timeline().get(1).avcoKind()).isEqualTo("PRIMARY_FLOW");
        assertThat(view.timeline().get(1).avcoAfterUsd()).isEqualByComparingTo("2873");
        assertThat(view.timeline().get(1).avcoBeforeUsd())
                .isEqualByComparingTo(view.timeline().get(0).avcoAfterUsd());
    }


    @Test
    void sessionFamilyLedgerTimelinePlotsCoveredWeightedFamilyAvcoAndSelfChains() {
        // ADR-045 Method B: two live buckets — a low-basis on-chain WETH lane ($1849) and a
        // high-basis Bybit ETH lane ($3715). The plotted series is Σ coveredᵢ·avcoᵢ / Σ coveredᵢ,
        // and avcoBeforeUsd chains to the previous entry's avcoAfterUsd (AC-1b).
        UserSession session = new UserSession();
        session.setId("session-methodb");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint baseWeth = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "1849", "0", "0", "1", "1849"
        );
        baseWeth.setNormalizedType("BUY");

        AssetLedgerPoint bybitEth = point(
                "2", "BYBIT:33625378", null, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "3715", "0", "0", "1", "3715"
        );
        bybitEth.setNormalizedType("BUY");
        bybitEth.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        when(userSessionRepository.findById("session-methodb")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-methodb",
                List.of("wallet-a", "BYBIT:33625378"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-methodb",
                "FAMILY:ETH"
        )).thenReturn(List.of(baseWeth, bybitEth));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "Aerodrome", "ETH", "BUY", "1", "1849"),
                        normalized("2", "Bybit", "ETH", "BUY", "1", "3715")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-methodb", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        AssetLedgerQueryService.TimelineEntryView first = view.timeline().get(0);
        AssetLedgerQueryService.TimelineEntryView second = view.timeline().get(1);

        assertThat(first.avcoBeforeUsd()).isNull();
        assertThat(first.avcoAfterUsd()).isEqualByComparingTo("1849");
        assertThat(first.avcoKind()).isEqualTo("PRIMARY_FLOW");

        // (1·1849 + 1·3715) / 2 = 2782
        assertThat(second.avcoAfterUsd()).isEqualByComparingTo("2782");
        assertThat(second.netAvcoAfterUsd()).isEqualByComparingTo("2782");
        assertThat(second.avcoKind()).isEqualTo("PRIMARY_FLOW");
        // AC-1b self-chaining
        assertThat(second.avcoBeforeUsd()).isEqualByComparingTo(first.avcoAfterUsd());
        assertThat(second.netAvcoBeforeUsd()).isEqualByComparingTo(first.netAvcoAfterUsd());

        assertThat(view.timeline())
                .extracting(AssetLedgerQueryService.TimelineEntryView::avcoKind)
                .doesNotContain("FAMILY_ROLLUP");
    }

    @Test
    void sessionFamilyLedgerBlendedSeriesEqualsLiquidWhenNoReallocationCorridor() {
        // RC-E3 / ADR-061 §F.5: with no basis-conserving REALLOCATE corridor, the blended
        // total-exposure line is byte-identical to the spot (liquid-pool) line at every event.
        UserSession session = new UserSession();
        session.setId("session-blended-zerolp");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buy1 = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "1849", "0", "0", "1", "1849"
        );
        buy1.setNormalizedType("BUY");
        AssetLedgerPoint buy2 = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "3715", "0", "0", "2", "5564"
        );
        buy2.setNormalizedType("BUY");
        buy2.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        when(userSessionRepository.findById("session-blended-zerolp")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-blended-zerolp",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-blended-zerolp",
                "FAMILY:ETH"
        )).thenReturn(List.of(buy1, buy2));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "Aerodrome", "ETH", "BUY", "1", "1849"),
                        normalized("2", "Aerodrome", "ETH", "BUY", "1", "3715")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "2")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-blended-zerolp", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        for (AssetLedgerQueryService.TimelineEntryView entry : view.timeline()) {
            assertThat(entry.blendedAvcoAfterUsd()).isEqualByComparingTo(entry.avcoAfterUsd());
            assertThat(entry.blendedNetAvcoAfterUsd()).isEqualByComparingTo(entry.netAvcoAfterUsd());
            assertThat(entry.blendedCoveredQuantityAfter()).isEqualByComparingTo(entry.coveredQuantityAfter());
            assertThat(entry.liquidQuantityAfter()).isEqualByComparingTo(entry.quantityAfter());
            assertThat(entry.blendedAvcoKind()).isEqualTo("PRIMARY_FLOW");
        }
        // Spot line byte-identical to ADR-045 Method B: (1·1849 + 1·3715) / 2 = 2782.
        assertThat(view.timeline().get(1).avcoAfterUsd()).isEqualByComparingTo("2782");
        assertThat(view.timeline().get(1).blendedAvcoAfterUsd()).isEqualByComparingTo("2782");
        // Backend-owned blended before/after chaining (ADR-061 rule 4).
        assertThat(view.timeline().get(1).blendedAvcoBeforeUsd())
                .isEqualByComparingTo(view.timeline().get(0).blendedAvcoAfterUsd());
    }

    @Test
    void sessionFamilyLedgerBlendedSeriesReincludesParkedEthBasisWhenLiquidDrains() {
        // RC-E3 / ADR-061 §F.6: ETH bought, then reallocated into an LP receipt corridor
        // (correlationId set) draining the liquid pool to 0. The spot (liquid) line breaks
        // (UNAVAILABLE, ADR-031) but the blended line stays defined, re-including the parked basis.
        UserSession session = new UserSession();
        session.setId("session-blended-parked");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        buy.setNormalizedType("BUY");

        AssetLedgerPoint lpEntryPark = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT, AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-1", "-2000", "0", "0", "0", "0"
        );
        lpEntryPark.setAssetSymbol("WETH");
        lpEntryPark.setNormalizedType("LP_ENTRY");
        lpEntryPark.setCorrelationId("lp-position:base:pancakeswap:196975");
        lpEntryPark.setNetCostBasisDeltaUsd(new BigDecimal("-2000"));
        lpEntryPark.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        // B-ETH-06: the LP is still OPEN, so it has an open ETH-origin lp_receipt_basis_pools row
        // (1 ETH / $2000). The generalized terminal clamp reconciles the parked slice to this holding.
        LpReceiptBasisPool openEthPool = new LpReceiptBasisPool();
        openEthPool.setId("ACCOUNTING_UNIVERSE:session-blended-parked:lp-position:base:pancakeswap:196975:NATIVE:BASE");
        openEthPool.setUniverseId("ACCOUNTING_UNIVERSE:session-blended-parked");
        openEthPool.setLpCorrelationId("lp-position:base:pancakeswap:196975");
        openEthPool.setWalletAddress("wallet-a");
        openEthPool.setNetworkId(NetworkId.BASE);
        openEthPool.setAssetIdentity("NATIVE:BASE");
        openEthPool.setAssetSymbol("WETH");
        openEthPool.setAssetContract("NATIVE:BASE");
        openEthPool.setQtyHeld(new BigDecimal("1"));
        openEthPool.setUncoveredQtyHeld(BigDecimal.ZERO);
        openEthPool.setBasisHeldUsd(new BigDecimal("2000"));
        openEthPool.setNetBasisHeldUsd(new BigDecimal("2000"));

        when(userSessionRepository.findById("session-blended-parked")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-blended-parked",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(lpReceiptBasisPoolRepository.findByUniverseId("ACCOUNTING_UNIVERSE:session-blended-parked"))
                .thenReturn(List.of(openEthPool));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-blended-parked",
                "FAMILY:ETH"
        )).thenReturn(List.of(buy, lpEntryPark));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "Coinbase", "ETH", "BUY", "1", "2000"),
                        normalized("2", "PancakeSwap", "WETH", "TRANSFER", "-1", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-blended-parked", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        AssetLedgerQueryService.TimelineEntryView parked = view.timeline().get(1);

        // Spot (liquid) line breaks — byte-identical to pre-change ADR-045 behavior.
        assertThat(parked.avcoAfterUsd()).isNull();
        assertThat(parked.netAvcoAfterUsd()).isNull();
        assertThat(parked.avcoKind()).isEqualTo("UNAVAILABLE");
        assertThat(parked.liquidQuantityAfter()).isEqualByComparingTo("0");

        // Blended line stays defined, re-including the ETH-origin parked basis.
        assertThat(parked.blendedAvcoKind()).isEqualTo("PRIMARY_FLOW");
        assertThat(parked.blendedAvcoAfterUsd()).isEqualByComparingTo("2000");
        assertThat(parked.blendedNetAvcoAfterUsd()).isEqualByComparingTo("2000");
        assertThat(parked.blendedCoveredQuantityAfter()).isEqualByComparingTo("1");
        // Blended covers more than the (drained) liquid quantity.
        assertThat(parked.blendedCoveredQuantityAfter()).isGreaterThan(parked.liquidQuantityAfter());
        assertThat(parked.blendedAvcoBeforeUsd())
                .isEqualByComparingTo(view.timeline().get(0).blendedAvcoAfterUsd());
    }

    @Test
    void sessionFamilyLedgerBlendedSeriesHoldsWhenEthParkedViaLendingLoopCarry() {
        // RM-1 (2026-07-24): ETH bought, then parked out as lending-loop collateral via a same-family
        // CARRY_OUT (LENDING_LOOP_OPEN) draining the liquid pool to 0. Before RM-1 the blended /
        // effective-cost line dropped to a false $0/UNAVAILABLE across the parked window because CARRY
        // corridors were excluded from the fold; now the CARRY corridor is folded so the blended line
        // stays defined and covers the parked collateral (series holds instead of flooring to $0).
        UserSession session = new UserSession();
        session.setId("session-carry-fold");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        buy.setNormalizedType("BUY");

        AssetLedgerPoint collateralOut = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_OUT, AssetLedgerPoint.LifecycleKind.LOOP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-1", "-2000", "0", "0", "0", "0"
        );
        collateralOut.setAssetSymbol("WETH");
        collateralOut.setNormalizedType("LENDING_LOOP_OPEN");
        collateralOut.setCorrelationId("lending-loop:0xcb8483");
        collateralOut.setNetCostBasisDeltaUsd(new BigDecimal("-2000"));
        collateralOut.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        when(userSessionRepository.findById("session-carry-fold")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-carry-fold",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-carry-fold",
                "FAMILY:ETH"
        )).thenReturn(List.of(buy, collateralOut));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "Coinbase", "ETH", "BUY", "1", "2000"),
                        normalized("2", "Aave", "WETH", "TRANSFER", "-1", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-carry-fold", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        AssetLedgerQueryService.TimelineEntryView parked = view.timeline().get(1);

        // Spot (liquid) line breaks — byte-identical to pre-change behavior.
        assertThat(parked.avcoKind()).isEqualTo("UNAVAILABLE");
        assertThat(parked.liquidQuantityAfter()).isEqualByComparingTo("0");

        // RM-1: the CARRY-parked collateral keeps the blended line defined (no false $0 drop).
        assertThat(parked.blendedAvcoKind()).isEqualTo("PRIMARY_FLOW");
        assertThat(parked.blendedAvcoAfterUsd()).isEqualByComparingTo("2000");
        assertThat(parked.blendedCoveredQuantityAfter()).isEqualByComparingTo("1");
        assertThat(parked.blendedCoveredQuantityAfter()).isGreaterThan(parked.liquidQuantityAfter());
        // Effective-cost series stays available (offset 0 → equals AVCO), not floored to $0 or "—".
        assertThat(parked.effectiveCostAfterUsd()).isEqualByComparingTo("2000");
    }

    @Test
    void sessionFamilyLedgerBlendedParkedTerminalReconcilesToLpReceiptBasisPoolsOnCrossAssetExit() {
        // RC-E3 / B-ETH-05 (ADR-061 amendment): a cross-asset LP exit (ETH→USDC) returns nothing to
        // FAMILY:ETH — the receipt burn is a REALLOCATE_OUT on FAMILY:LP_RECEIPT, so the parked slice
        // would never close (over-park) under the pre-fix REALLOCATE-only reconstruction. The receipt-
        // burn clamp + terminal exactness clamp reconcile the parked terminal to the still-open
        // lp_receipt_basis_pools ETH-origin holding (0.4 ETH / $800 mkt / $780 net), NOT the over-park
        // 1 ETH / $2000.
        UserSession session = new UserSession();
        session.setId("session-blended-crossexit");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        buy.setNormalizedType("BUY");

        AssetLedgerPoint lpEntryPark = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT, AssetLedgerPoint.LifecycleKind.LP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-1", "-2000", "0", "0", "0", "0"
        );
        lpEntryPark.setAssetSymbol("WETH");
        lpEntryPark.setNormalizedType("LP_ENTRY");
        lpEntryPark.setCorrelationId("lp-position:base:uniswap:cross");
        lpEntryPark.setNetCostBasisDeltaUsd(new BigDecimal("-2000"));
        lpEntryPark.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        // Cross-asset receipt burn: 60% of the LP receipt burned (return landed on USDC, not ETH).
        // This transaction never appears on the FAMILY:ETH timeline.
        AssetLedgerPoint receiptBurn = new AssetLedgerPoint();
        receiptBurn.setId("3:wallet-a:BASE");
        receiptBurn.setWalletAddress("wallet-a");
        receiptBurn.setNetworkId(NetworkId.BASE);
        receiptBurn.setAccountingFamilyIdentity("FAMILY:LP_RECEIPT");
        receiptBurn.setAccountingAssetIdentity("SYMBOL:WETH-USDC-LP");
        receiptBurn.setAssetSymbol("WETH-USDC-LP");
        receiptBurn.setAssetContract("0xlp");
        receiptBurn.setNormalizedTransactionId("3");
        receiptBurn.setTxHash("0x3");
        receiptBurn.setCorrelationId("lp-position:base:uniswap:cross");
        receiptBurn.setBlockTimestamp(Instant.parse("2026-04-05T10:02:00Z"));
        receiptBurn.setTransactionIndex(0);
        receiptBurn.setReplaySequence(3L);
        receiptBurn.setNormalizedType("LP_EXIT_PARTIAL");
        receiptBurn.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        receiptBurn.setQuantityBefore(new BigDecimal("1"));
        receiptBurn.setQuantityAfter(new BigDecimal("0.4"));
        receiptBurn.setQuantityDelta(new BigDecimal("-0.6"));

        LpReceiptBasisPool ethOriginPool = new LpReceiptBasisPool();
        ethOriginPool.setId("ACCOUNTING_UNIVERSE:session-blended-crossexit:lp-position:base:uniswap:cross:NATIVE:BASE");
        ethOriginPool.setUniverseId("ACCOUNTING_UNIVERSE:session-blended-crossexit");
        ethOriginPool.setLpCorrelationId("lp-position:base:uniswap:cross");
        ethOriginPool.setWalletAddress("wallet-a");
        ethOriginPool.setNetworkId(NetworkId.BASE);
        ethOriginPool.setAssetIdentity("NATIVE:BASE");
        ethOriginPool.setAssetSymbol("WETH");
        ethOriginPool.setAssetContract("NATIVE:BASE");
        ethOriginPool.setQtyHeld(new BigDecimal("0.4"));
        ethOriginPool.setUncoveredQtyHeld(BigDecimal.ZERO);
        ethOriginPool.setBasisHeldUsd(new BigDecimal("800"));
        ethOriginPool.setNetBasisHeldUsd(new BigDecimal("780"));

        when(userSessionRepository.findById("session-blended-crossexit")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-blended-crossexit",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-blended-crossexit",
                "FAMILY:ETH"
        )).thenReturn(List.of(buy, lpEntryPark));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-blended-crossexit",
                "FAMILY:LP_RECEIPT"
        )).thenReturn(List.of(receiptBurn));
        when(lpReceiptBasisPoolRepository.findByUniverseId("ACCOUNTING_UNIVERSE:session-blended-crossexit"))
                .thenReturn(List.of(ethOriginPool));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "Coinbase", "ETH", "BUY", "1", "2000"),
                        normalized("2", "Uniswap", "WETH", "TRANSFER", "-1", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-blended-crossexit", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        AssetLedgerQueryService.TimelineEntryView terminal = view.timeline().get(1);

        // Liquid line broke (pool drained). Blended re-includes ONLY the still-open ETH-origin pool
        // holding — reconciled to lp_receipt_basis_pools, not the over-parked full 1 ETH / $2000.
        assertThat(terminal.avcoKind()).isEqualTo("UNAVAILABLE");
        assertThat(terminal.blendedAvcoKind()).isEqualTo("PRIMARY_FLOW");
        assertThat(terminal.blendedCoveredQuantityAfter()).isEqualByComparingTo("0.4");
        assertThat(terminal.blendedAvcoAfterUsd()).isEqualByComparingTo("2000");
        assertThat(terminal.blendedNetAvcoAfterUsd()).isEqualByComparingTo("1950");
    }

    @Test
    void sessionFamilyLedgerTimelineDustGasOnlyEventDoesNotMoveSeries() {
        // AC-1a: a GAS_ONLY dust event (|qtyΔ| < 0.001) with unchanged bucket AVCO leaves the
        // covered-weighted family series essentially flat.
        UserSession session = new UserSession();
        session.setId("session-dust");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint acquire = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        acquire.setNormalizedType("BUY");

        AssetLedgerPoint dust = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.GAS_ONLY, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.0001", "0", "0", "-0.05", "0.9999", "1999.8"
        );
        dust.setNormalizedType("SPONSORED_GAS_IN");
        dust.setAvcoAfterUsd(new BigDecimal("2000"));
        dust.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        when(userSessionRepository.findById("session-dust")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-dust",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-dust",
                "FAMILY:ETH"
        )).thenReturn(List.of(acquire, dust));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2")))
                .thenReturn(List.of(
                        normalized("1", "Aerodrome", "ETH", "BUY", "1", "2000"),
                        normalized("2", "Relay", "ETH", "SELL", "-0.0001", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0.9999")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-dust", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(2);
        AssetLedgerQueryService.TimelineEntryView dustEntry = view.timeline().get(1);
        assertThat(dustEntry.avcoBeforeUsd()).isEqualByComparingTo("2000");
        assertThat(dustEntry.avcoAfterUsd()).isEqualByComparingTo("2000");
        assertThat(dustEntry.avcoAfterUsd().subtract(dustEntry.avcoBeforeUsd()).abs())
                .as("dust event must not move the plotted AVCO by more than $1")
                .isLessThan(BigDecimal.ONE);
    }

    @Test
    void sessionFamilyLedgerTimelineBreaksSeriesWhenFamilyDrainedThenReacquires() {
        // AC-2 / ADR-031: a fully drained family emits null AVCO (line breaks, not $0); a following
        // re-acquisition starts a fresh non-null segment (avcoBeforeUsd == prior null entry's after).
        UserSession session = new UserSession();
        session.setId("session-drain");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint acquire = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        acquire.setNormalizedType("BUY");

        AssetLedgerPoint disposeAll = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-1", "-2000", "0", "0", "0", "0"
        );
        disposeAll.setNormalizedType("SELL");
        disposeAll.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        AssetLedgerPoint reacquire = point(
                "3", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0.5", "1500", "0", "0", "0.5", "1500"
        );
        reacquire.setNormalizedType("BUY");
        reacquire.setBlockTimestamp(Instant.parse("2026-04-05T10:02:00Z"));

        when(userSessionRepository.findById("session-drain")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-drain",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-drain",
                "FAMILY:ETH"
        )).thenReturn(List.of(acquire, disposeAll, reacquire));
        when(normalizedTransactionRepository.findAllById(List.of("1", "2", "3")))
                .thenReturn(List.of(
                        normalized("1", "Aerodrome", "ETH", "BUY", "1", "2000"),
                        normalized("2", "Aerodrome", "ETH", "SELL", "-1", "2000"),
                        normalized("3", "Aerodrome", "ETH", "BUY", "0.5", "3000")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0.5")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-drain", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(3);
        AssetLedgerQueryService.TimelineEntryView drained = view.timeline().get(1);
        AssetLedgerQueryService.TimelineEntryView reacquired = view.timeline().get(2);

        assertThat(drained.avcoAfterUsd()).isNull();
        assertThat(drained.netAvcoAfterUsd()).isNull();
        assertThat(drained.avcoKind()).isEqualTo("UNAVAILABLE");
        assertThat(drained.avcoBeforeUsd()).isEqualByComparingTo("2000");

        assertThat(reacquired.avcoBeforeUsd()).as("re-acquire chains to prior null after").isNull();
        assertThat(reacquired.avcoAfterUsd()).isEqualByComparingTo("3000");
        assertThat(reacquired.avcoKind()).isEqualTo("PRIMARY_FLOW");

        assertThat(view.timeline())
                .extracting(AssetLedgerQueryService.TimelineEntryView::avcoKind)
                .doesNotContain("FAMILY_ROLLUP");
    }

    @Test
    void sessionFamilyLedgerEffectiveCostSeriesTerminalReconcilesWithScalarBreakEven() {
        // ADR-062 §3: FAMILY:ETH parent (1 ETH @ $2000 basis, no realized P&L of its own) with an
        // attributed cmETH child (FAMILY:METH) that banked +$500 Market-lane realized profit. The
        // scalar header credits the child's $500 to ETH → effective basis $1500 → break-even $1500/ETH.
        // The chart series weaves the child's realized P&L chronologically, so its terminal effective
        // cost must equal the scalar header break-even exactly.
        UserSession session = new UserSession();
        session.setId("session-be");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint ethBuy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        ethBuy.setNormalizedType("BUY");
        ethBuy.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));

        AssetLedgerPoint methSell = point(
                "5", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-2", "-5000", "500", "0", "0", "0"
        );
        methSell.setAssetSymbol("CMETH");
        methSell.setFamilyDisplaySymbol("CMETH");
        methSell.setNormalizedType("SELL");
        methSell.setNetRealisedPnlDeltaUsd(new BigDecimal("500"));
        methSell.setBlockTimestamp(Instant.parse("2026-04-05T10:05:00Z"));

        when(userSessionRepository.findById("session-be")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-be",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-be", "FAMILY:ETH"
        )).thenReturn(List.of(ethBuy));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-be", "FAMILY:METH"
        )).thenReturn(List.of(methSell));
        when(normalizedTransactionRepository.findAllById(List.of("1")))
                .thenReturn(List.of(normalized("1", "Coinbase", "ETH", "BUY", "1", "2000")));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-be", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().breakEvenUsd()).isEqualByComparingTo("1500");
        assertThat(view.timeline()).isNotEmpty();
        AssetLedgerQueryService.TimelineEntryView terminal = view.timeline().get(view.timeline().size() - 1);
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo("1500");
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(view.current().breakEvenUsd());
    }

    @Test
    void sessionFamilyLedgerEffectiveCostReconcilesUnderBothOffsetLanes() {
        // ADR-062 (2026-07-21 amendment): FAMILY:ETH parent (1 ETH @ $2000 basis, no self P&L) with an
        // attributed cmETH child (FAMILY:METH) that banked +$200 Market-lane trading profit AND +$500
        // realized income (net realized = $700, income = net − market = $500).
        //   NET lane   : offset = $700 → effective basis $1300 → break-even $1300/ETH.
        //   MARKET lane: offset = $200 → effective basis $1800 → break-even $1800/ETH.
        // Terminal chart effective cost must equal the header break-even under the SAME lane, and the
        // NET terminal (income credited) must be ≤ the MARKET terminal (income ≥ 0).
        UserSession session = new UserSession();
        session.setId("session-lane");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint ethBuy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        ethBuy.setNormalizedType("BUY");
        ethBuy.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));

        AssetLedgerPoint methSell = point(
                "5", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-2", "-5000", "200", "0", "0", "0"
        );
        methSell.setAssetSymbol("CMETH");
        methSell.setFamilyDisplaySymbol("CMETH");
        methSell.setNormalizedType("SELL");
        methSell.setNetRealisedPnlDeltaUsd(new BigDecimal("700"));
        methSell.setBlockTimestamp(Instant.parse("2026-04-05T10:05:00Z"));

        when(userSessionRepository.findById("session-lane")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-lane",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-lane", "FAMILY:ETH"
        )).thenReturn(List.of(ethBuy));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-lane", "FAMILY:METH"
        )).thenReturn(List.of(methSell));
        when(normalizedTransactionRepository.findAllById(List.of("1")))
                .thenReturn(List.of(normalized("1", "Coinbase", "ETH", "BUY", "1", "2000")));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView netView = service(attributionServiceWithLane(OffsetLane.NET))
                .findSessionFamilyLedger("session-lane", "FAMILY:ETH")
                .orElseThrow();
        AssetLedgerQueryService.SessionAssetLedgerView marketView = service(attributionServiceWithLane(OffsetLane.MARKET))
                .findSessionFamilyLedger("session-lane", "FAMILY:ETH")
                .orElseThrow();

        BigDecimal netTerminal = netView.timeline().get(netView.timeline().size() - 1).effectiveCostAfterUsd();
        BigDecimal marketTerminal = marketView.timeline().get(marketView.timeline().size() - 1).effectiveCostAfterUsd();

        assertThat(netView.current().breakEvenUsd()).isEqualByComparingTo("1300");
        assertThat(netTerminal).isEqualByComparingTo("1300");
        assertThat(netTerminal).isEqualByComparingTo(netView.current().breakEvenUsd());

        assertThat(marketView.current().breakEvenUsd()).isEqualByComparingTo("1800");
        assertThat(marketTerminal).isEqualByComparingTo("1800");
        assertThat(marketTerminal).isEqualByComparingTo(marketView.current().breakEvenUsd());

        assertThat(netTerminal).isLessThanOrEqualTo(marketTerminal);
    }

    @Test
    void sessionFamilyLedgerEffectiveCostNetLaneHeldIncomeReconcilesAtLowerNetValue() {
        // ADR-062 (2026-07-24): a FAMILY:ETH position of 2 held units carrying $4000 MARKET basis
        // (avco $2000/ETH) but only $2000 NET basis (avco $1000/ETH — half arrived as free held reward
        // income, never sold ⇒ zero realized). Under NET the header break-even numerator is the NET
        // held basis and the series numerator is the NET blended AVCO, so BOTH reconcile at the LOWER
        // net value ($1000/ETH), below the market average cost ($2000/ETH). Proves the held zero-cost
        // income is credited free in header AND series (no double-count: zero realized offset).
        UserSession session = new UserSession();
        session.setId("session-held-income");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint ethReward = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "2", "4000", "0", "0", "2", "4000"
        );
        ethReward.setNormalizedType("BUY");
        ethReward.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        // Net (real-cash) lane: only $2000 of real cash cost across the 2 held units (avco $1000).
        ethReward.setNetAvcoAfterUsd(new BigDecimal("1000"));
        ethReward.setNetTotalCostBasisAfterUsd(new BigDecimal("2000"));
        ethReward.setNetCostBasisDeltaUsd(new BigDecimal("2000"));

        when(userSessionRepository.findById("session-held-income")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-held-income",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-held-income", "FAMILY:ETH"
        )).thenReturn(List.of(ethReward));
        when(normalizedTransactionRepository.findAllById(List.of("1")))
                .thenReturn(List.of(normalized("1", "Coinbase", "ETH", "BUY", "2", "4000")));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "2")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-held-income", "FAMILY:ETH")
                .orElseThrow();

        // Header break-even on the NET lane: $2000 net basis / 2 ETH = $1000/ETH (NOT $2000 market).
        assertThat(view.current().breakEvenUsd()).isEqualByComparingTo("1000");
        // Market average cost stays $2000/ETH (Market-lane diagnostic, unchanged).
        assertThat(view.current().avcoUsd()).isEqualByComparingTo("2000");
        assertThat(view.current().breakEvenUsd()).isLessThan(view.current().avcoUsd());
        // Series terminal reconciles with the header scalar at the SAME lower net value.
        AssetLedgerQueryService.TimelineEntryView terminal = view.timeline().get(view.timeline().size() - 1);
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo("1000");
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(view.current().breakEvenUsd());
    }

    @Test
    void sessionFamilyLedgerEffectiveCostSeriesDustDenominatorIsGuardedNotExploded() {
        // ADR-062 Wave 3 (AC-7 / AC-10 parity) — T3 series-explosion regression. During an LP-deployment
        // window the ETH-equivalent covered quantity collapses to a dust sliver while an attributed
        // cmETH cluster LOSS (unfloored per AC-8) is already woven as a NEGATIVE offset. Dividing that
        // (correct, deterministic) offset by the sliver denominator would otherwise render an
        // economically meaningless per-unit spike ($0.40 basis − (−$100) offset ÷ 0.0002 ETH ≈
        // $502,000). The fail-closed dust guard renders the dust point UNAVAILABLE ("—"/null) exactly
        // where the blended AVCO is dust-guarded — while the HEALTHY terminal still reconciles with the
        // scalar header break-even. No ledger/replay/realized-PnL value changes.
        UserSession session = new UserSession();
        session.setId("session-dust");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint ethBuy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        ethBuy.setNormalizedType("BUY");
        ethBuy.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));

        // Attributed cmETH cluster LOSS (unfloored NEGATIVE offset), woven before the dust event.
        AssetLedgerPoint methLoss = point(
                "5", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-1", "-2000", "-100", "0", "0", "0"
        );
        methLoss.setAssetSymbol("CMETH");
        methLoss.setFamilyDisplaySymbol("CMETH");
        methLoss.setNormalizedType("SELL");
        methLoss.setNetRealisedPnlDeltaUsd(new BigDecimal("-100"));
        methLoss.setBlockTimestamp(Instant.parse("2026-04-05T10:02:00Z"));

        // ETH liquid pool drains to a sub-$1 dust residual (0.0002 ETH @ $0.40 → avco $2000).
        AssetLedgerPoint ethDrain = point(
                "3", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.9998", "-1999.60", "0", "0", "0.0002", "0.40"
        );
        ethDrain.setNormalizedType("SELL");
        ethDrain.setBlockTimestamp(Instant.parse("2026-04-05T10:05:00Z"));

        // ETH re-acquired back to a HEALTHY terminal (1 ETH @ $2000).
        AssetLedgerPoint ethReacquire = point(
                "4", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0.9998", "1999.60", "0", "0", "1", "2000"
        );
        ethReacquire.setNormalizedType("BUY");
        ethReacquire.setBlockTimestamp(Instant.parse("2026-04-05T10:10:00Z"));

        when(userSessionRepository.findById("session-dust")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-dust",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-dust", "FAMILY:ETH"
        )).thenReturn(List.of(ethBuy, ethDrain, ethReacquire));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-dust", "FAMILY:METH"
        )).thenReturn(List.of(methLoss));
        when(normalizedTransactionRepository.findAllById(List.of("1", "3", "4")))
                .thenReturn(List.of(
                        normalized("1", "Coinbase", "ETH", "BUY", "1", "2000"),
                        normalized("3", "Uniswap", "ETH", "SELL", "-0.9998", null),
                        normalized("4", "Coinbase", "ETH", "BUY", "0.9998", "2000")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-dust", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(3);
        AssetLedgerQueryService.TimelineEntryView dust = view.timeline().get(1);
        AssetLedgerQueryService.TimelineEntryView terminal = view.timeline().get(2);

        // Dust point: the sub-$1 covered basis fails the AC-10 guard, so BOTH the blended AVCO and the
        // effective-cost series render UNAVAILABLE — never the exploded per-unit value.
        assertThat(dust.blendedAvcoAfterUsd()).as("blended AVCO dust-guarded to null").isNull();
        assertThat(dust.effectiveCostAfterUsd()).as("effective-cost series dust-guarded, not exploded").isNull();

        // Healthy terminal still reconciles with the scalar header break-even (offset −(−$100) raises
        // it above avg cost per R2/AC-8): ($2000 + $100) / 1 ETH = $2100.
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo("2100");
        assertThat(view.current().breakEvenUsd()).isEqualByComparingTo("2100");
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(view.current().breakEvenUsd());
    }

    @Test
    void sessionFamilyLedgerEffectiveCostSeriesOverSliverSpikeIsSuppressedNotExploded() {
        // ADR-062 Wave 3 (T3, 2026-07-23 re-audit) — over-sliver artifact regression. The $1 dust guard
        // only catches sub-dollar covered basis; an LP/lending-parked window whose covered basis is a
        // small-but-above-$1 sliver ($100 here, > the $1 guard) slips through, and the large woven
        // NEGATIVE cluster offset divided by the sliver denominator renders a spurious ~$40k per-unit
        // spike. The additive over-sliver guard suppresses that point (BOTH sliver-denominated:
        // 0.05 ETH < 5% of the 3.85-ETH peak, AND over-blended-AVCO: $40k > $2000 × 1.1) to null, while
        // (a) a normal in-range point ($2000 at AVCO), (b) a legitimate $0 offset-recoup floor on a
        // HEALTHY denominator, and (c) the healthy terminal (reconciling with the scalar header) all
        // stay visible. No ledger/replay/AVCO/terminal value changes.
        UserSession session = new UserSession();
        session.setId("session-sliver");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        // Point 0: buy 3.85 ETH @ $2000 (basis $7700). Establishes the family ETH-equivalent peak and
        // is itself a NORMAL in-range point (no offset woven yet → effective cost == AVCO $2000).
        AssetLedgerPoint ethBuy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "3.85", "7700", "0", "0", "3.85", "7700"
        );
        ethBuy.setNormalizedType("BUY");
        ethBuy.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));

        // Attributed cmETH cluster RECOUP (+$8000 net realized), woven before the hold point below.
        AssetLedgerPoint methRecoup = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-4", "-10000", "8000", "0", "0", "0"
        );
        methRecoup.setAssetSymbol("CMETH");
        methRecoup.setFamilyDisplaySymbol("CMETH");
        methRecoup.setNormalizedType("SELL");
        methRecoup.setNetRealisedPnlDeltaUsd(new BigDecimal("8000"));
        methRecoup.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        // Point 1: hold 3.85 ETH (no quantity change). Cumulative offset is now +$8000, which fully
        // recoups the $7700 basis → $0 offset-recoup floor on a HEALTHY denominator (must stay visible).
        AssetLedgerPoint ethHold = point(
                "3", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0", "0", "0", "0", "3.85", "7700"
        );
        ethHold.setNormalizedType("BUY");
        ethHold.setBlockTimestamp(Instant.parse("2026-04-05T10:02:00Z"));

        // Attributed cmETH cluster LOSS (-$9900 net realized), woven before the parked-sliver point so
        // the cumulative offset flips to -$1900 (recoup $8000 - loss $9900).
        AssetLedgerPoint methLoss = point(
                "4", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-4", "-8000", "-9900", "0", "0", "0"
        );
        methLoss.setAssetSymbol("CMETH");
        methLoss.setFamilyDisplaySymbol("CMETH");
        methLoss.setNormalizedType("SELL");
        methLoss.setNetRealisedPnlDeltaUsd(new BigDecimal("-9900"));
        methLoss.setBlockTimestamp(Instant.parse("2026-04-05T10:04:00Z"));

        // Point 2: ETH deployed into LP so the liquid pool drains to a 0.05-ETH sliver (covered basis
        // $100, > the $1 dust guard, AVCO still $2000). effectiveCost = (100 - (-1900)) / 0.05 = $40k
        // → over-sliver artifact (0.05 < 5% × 3.85 peak AND $40k > $2000 × 1.1) → suppressed to null.
        AssetLedgerPoint ethSliver = point(
                "5", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-3.80", "-7600", "0", "0", "0.05", "100"
        );
        ethSliver.setNormalizedType("SELL");
        ethSliver.setBlockTimestamp(Instant.parse("2026-04-05T10:05:00Z"));

        // Point 3: ETH re-acquired back to a HEALTHY 3.85-ETH terminal (basis $7700). Not sliver-
        // denominated → the -$1900 offset elevates it to (7700 + 1900) / 3.85 = $2493.51 and it stays
        // visible, reconciling with the scalar header break-even.
        AssetLedgerPoint ethReacquire = point(
                "6", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "3.80", "7600", "0", "0", "3.85", "7700"
        );
        ethReacquire.setNormalizedType("BUY");
        ethReacquire.setBlockTimestamp(Instant.parse("2026-04-05T10:10:00Z"));

        when(userSessionRepository.findById("session-sliver")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-sliver",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-sliver", "FAMILY:ETH"
        )).thenReturn(List.of(ethBuy, ethHold, ethSliver, ethReacquire));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-sliver", "FAMILY:METH"
        )).thenReturn(List.of(methRecoup, methLoss));
        when(normalizedTransactionRepository.findAllById(List.of("1", "3", "5", "6")))
                .thenReturn(List.of(
                        normalized("1", "Coinbase", "ETH", "BUY", "3.85", "2000"),
                        normalized("3", "Coinbase", "ETH", "BUY", "0", "2000"),
                        normalized("5", "Uniswap", "ETH", "SELL", "-3.80", null),
                        normalized("6", "Coinbase", "ETH", "BUY", "3.80", "2000")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "3.85")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-sliver", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(4);
        AssetLedgerQueryService.TimelineEntryView normalInRange = view.timeline().get(0);
        AssetLedgerQueryService.TimelineEntryView zeroFloor = view.timeline().get(1);
        AssetLedgerQueryService.TimelineEntryView sliver = view.timeline().get(2);
        AssetLedgerQueryService.TimelineEntryView terminal = view.timeline().get(3);

        // Control (a): a normal in-range point (no offset woven yet) stays visible at AVCO.
        assertThat(normalInRange.effectiveCostAfterUsd())
                .as("normal in-range point stays visible").isEqualByComparingTo("2000");

        // Control (b): a legitimate $0 offset-recoup floor on a HEALTHY denominator stays visible.
        assertThat(zeroFloor.effectiveCostAfterUsd())
                .as("legitimate $0 offset-recoup floor stays visible").isEqualByComparingTo("0");
        assertThat(zeroFloor.blendedAvcoAfterUsd()).as("healthy denominator blended AVCO present").isNotNull();

        // Target: the over-sliver artifact ($100 covered basis > $1 guard, ~$40k per-unit spike) is
        // suppressed to null — the $1 dust guard alone would NOT have caught it (covered basis > $1).
        assertThat(sliver.blendedAvcoAfterUsd())
                .as("sliver point covered basis $100 is above the $1 dust guard").isNotNull();
        assertThat(sliver.effectiveCostAfterUsd())
                .as("over-sliver effective-cost spike suppressed, not exploded").isNull();

        // Control (c): the healthy terminal stays visible, is elevated above AVCO by the -$1900 offset
        // ((7700 + 1900) / 3.85 ≈ $2493.51), and reconciles with the scalar header break-even.
        BigDecimal expectedTerminal = new BigDecimal("9600")
                .divide(new BigDecimal("3.85"), java.math.MathContext.DECIMAL128);
        assertThat(terminal.effectiveCostAfterUsd()).as("healthy terminal stays visible").isNotNull();
        assertThat(terminal.effectiveCostAfterUsd()).isGreaterThan(new BigDecimal("2000"));
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(expectedTerminal);
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(view.current().breakEvenUsd());
    }

    @Test
    void sessionFamilyLedgerEffectiveCostSeriesOverSliverGuardUsesGlobalPeakNotRunningPeak() {
        // ADR-062 Wave 3 (T3, 2026-07-23 retune) — the RUNNING-vs-GLOBAL peak trap. The family's liquid
        // ETH-equivalent exposure ramps up over time: it holds only 0.6 ETH early, then a mid-timeline
        // artifact window drains it to a 0.04-ETH sliver, and only LATER does it ramp to a 4.0-ETH
        // terminal peak. A guard anchored on the RUNNING peak would measure the sliver against 0.6 ETH
        // (0.04 / 0.6 ≈ 6.67% > 5% → NOT sliver-denominated → the ~$7k spike would SLIP THROUGH). The
        // retuned guard anchors on the GLOBAL/terminal peak (4.0 ETH): 0.04 / 4.0 = 1% < 5% AND
        // effectiveCost $7000 > blended AVCO $2000 × 1.1 → suppressed to null. Controls: a normal
        // in-range point and a legitimate $0 offset-recoup floor stay visible, and the healthy terminal
        // stays visible and reconciles with the scalar header break-even. No ledger/replay/AVCO value
        // changes.
        UserSession session = new UserSession();
        session.setId("session-global-peak");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        // Point 0: buy 0.6 ETH @ $2000 (basis $1200). Establishes the SMALL running peak (0.6 ETH) and
        // is a NORMAL in-range point (no offset woven yet → effective cost == AVCO $2000).
        AssetLedgerPoint ethSmallBuy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0.6", "1200", "0", "0", "0.6", "1200"
        );
        ethSmallBuy.setNormalizedType("BUY");
        ethSmallBuy.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));

        // Attributed cmETH cluster RECOUP (+$1200 net realized), woven before the hold point below so
        // the cumulative offset (+$1200) fully recoups the $1200 basis → $0 floor on a HEALTHY 0.6 ETH.
        AssetLedgerPoint methRecoup = point(
                "2", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-4", "-10000", "1200", "0", "0", "0"
        );
        methRecoup.setAssetSymbol("CMETH");
        methRecoup.setFamilyDisplaySymbol("CMETH");
        methRecoup.setNormalizedType("SELL");
        methRecoup.setNetRealisedPnlDeltaUsd(new BigDecimal("1200"));
        methRecoup.setBlockTimestamp(Instant.parse("2026-04-05T10:01:00Z"));

        // Point 1: hold 0.6 ETH (no quantity change). Cumulative offset +$1200 fully recoups the $1200
        // basis → $0 offset-recoup floor on a HEALTHY denominator (0.6 / 4.0 = 15% > 5%, and $0 is not
        // over-AVCO) → must stay visible.
        AssetLedgerPoint ethHold = point(
                "3", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "0", "0", "0", "0", "0.6", "1200"
        );
        ethHold.setNormalizedType("BUY");
        ethHold.setBlockTimestamp(Instant.parse("2026-04-05T10:02:00Z"));

        // Attributed cmETH cluster LOSS (-$1400 net realized), woven before the parked-sliver point so
        // the cumulative offset flips to -$200 (recoup $1200 - loss $1400).
        AssetLedgerPoint methLoss = point(
                "4", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-4", "-8000", "-1400", "0", "0", "0"
        );
        methLoss.setAssetSymbol("CMETH");
        methLoss.setFamilyDisplaySymbol("CMETH");
        methLoss.setNormalizedType("SELL");
        methLoss.setNetRealisedPnlDeltaUsd(new BigDecimal("-1400"));
        methLoss.setBlockTimestamp(Instant.parse("2026-04-05T10:04:00Z"));

        // Point 2: ETH deployed into LP so the liquid pool drains to a 0.04-ETH sliver (covered basis
        // $80, > the $1 dust guard, AVCO still $2000). effectiveCost = (80 - (-200)) / 0.04 = $7000.
        // RUNNING peak here is only 0.6 ETH (0.04 / 0.6 ≈ 6.67% > 5% → a running-peak guard would KEEP
        // it — the bug); GLOBAL peak is 4.0 ETH (0.04 / 4.0 = 1% < 5% AND $7000 > $2000 × 1.1) →
        // suppressed to null.
        AssetLedgerPoint ethSliver = point(
                "5", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-0.56", "-1120", "0", "0", "0.04", "80"
        );
        ethSliver.setNormalizedType("SELL");
        ethSliver.setBlockTimestamp(Instant.parse("2026-04-05T10:05:00Z"));

        // Point 3: ETH ramps to a HEALTHY 4.0-ETH terminal (basis $8000) — the GLOBAL peak, reached only
        // now. Not sliver-denominated → the -$200 offset elevates it to (8000 + 200) / 4.0 = $2050
        // (avcoMult 1.025 < 1.1, so kept) and it stays visible, reconciling with the scalar header.
        AssetLedgerPoint ethRamp = point(
                "6", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "3.96", "7920", "0", "0", "4.0", "8000"
        );
        ethRamp.setNormalizedType("BUY");
        ethRamp.setBlockTimestamp(Instant.parse("2026-04-05T10:10:00Z"));

        when(userSessionRepository.findById("session-global-peak")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-global-peak",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-global-peak", "FAMILY:ETH"
        )).thenReturn(List.of(ethSmallBuy, ethHold, ethSliver, ethRamp));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-global-peak", "FAMILY:METH"
        )).thenReturn(List.of(methRecoup, methLoss));
        when(normalizedTransactionRepository.findAllById(List.of("1", "3", "5", "6")))
                .thenReturn(List.of(
                        normalized("1", "Coinbase", "ETH", "BUY", "0.6", "2000"),
                        normalized("3", "Coinbase", "ETH", "BUY", "0", "2000"),
                        normalized("5", "Uniswap", "ETH", "SELL", "-0.56", null),
                        normalized("6", "Coinbase", "ETH", "BUY", "3.96", "2000")
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "4.0")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-global-peak", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(4);
        AssetLedgerQueryService.TimelineEntryView normalInRange = view.timeline().get(0);
        AssetLedgerQueryService.TimelineEntryView zeroFloor = view.timeline().get(1);
        AssetLedgerQueryService.TimelineEntryView sliver = view.timeline().get(2);
        AssetLedgerQueryService.TimelineEntryView terminal = view.timeline().get(3);

        // The running-vs-global trap made explicit: the sliver covered (0.04 ETH) is ABOVE 5% of the
        // running peak (0.6 ETH) but BELOW 5% of the global/terminal peak (4.0 ETH).
        BigDecimal runningPeak = normalInRange.blendedCoveredQuantityAfter(); // 0.6 ETH
        BigDecimal globalPeak = terminal.blendedCoveredQuantityAfter();       // 4.0 ETH
        assertThat(sliver.blendedCoveredQuantityAfter()).isEqualByComparingTo("0.04");
        assertThat(runningPeak).isEqualByComparingTo("0.6");
        assertThat(globalPeak).isEqualByComparingTo("4.0");
        assertThat(sliver.blendedCoveredQuantityAfter())
                .as("sliver is ABOVE 5% of the running peak (a running-peak guard would miss it)")
                .isGreaterThan(runningPeak.multiply(new BigDecimal("0.05")));
        assertThat(sliver.blendedCoveredQuantityAfter())
                .as("sliver is BELOW 5% of the global/terminal peak (the retuned guard catches it)")
                .isLessThan(globalPeak.multiply(new BigDecimal("0.05")));

        // Control (a): a normal in-range point (no offset woven yet) stays visible at AVCO.
        assertThat(normalInRange.effectiveCostAfterUsd())
                .as("normal in-range point stays visible").isEqualByComparingTo("2000");

        // Control (b): a legitimate $0 offset-recoup floor on a HEALTHY denominator stays visible.
        assertThat(zeroFloor.effectiveCostAfterUsd())
                .as("legitimate $0 offset-recoup floor stays visible").isEqualByComparingTo("0");
        assertThat(zeroFloor.blendedAvcoAfterUsd()).as("healthy denominator blended AVCO present").isNotNull();

        // Target: the mid-timeline over-sliver artifact is suppressed to null ONLY because the guard now
        // uses the global peak; its covered basis ($80) is above the $1 dust guard.
        assertThat(sliver.blendedAvcoAfterUsd())
                .as("sliver point covered basis $80 is above the $1 dust guard").isNotNull();
        assertThat(sliver.effectiveCostAfterUsd())
                .as("over-sliver spike suppressed via global peak, not exploded").isNull();

        // Control (c): the healthy terminal stays visible, is elevated above AVCO by the -$200 offset
        // ((8000 + 200) / 4.0 = $2050), and reconciles with the scalar header break-even.
        BigDecimal expectedTerminal = new BigDecimal("8200")
                .divide(new BigDecimal("4.0"), java.math.MathContext.DECIMAL128);
        assertThat(terminal.effectiveCostAfterUsd()).as("healthy terminal stays visible").isNotNull();
        assertThat(terminal.effectiveCostAfterUsd()).isGreaterThan(new BigDecimal("2000"));
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(expectedTerminal);
        assertThat(terminal.effectiveCostAfterUsd()).isEqualByComparingTo(view.current().breakEvenUsd());
    }

    @Test
    void sessionFamilyLedgerFamilyMemberSymbolsIncludeViewedFamilyAndAttributedChildren() {
        // ADR-062 §3 header hint: distinct member symbols present in the ledger for FAMILY:ETH and its
        // attributed children (viewed family's own symbols first, then children's).
        UserSession session = new UserSession();
        session.setId("session-members");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint ethBuy = point(
                "1", "wallet-a", NetworkId.BASE, "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1", "2000", "0", "0", "1", "2000"
        );
        ethBuy.setAssetSymbol("WETH");
        ethBuy.setFamilyDisplaySymbol("ETH");
        ethBuy.setNormalizedType("BUY");

        AssetLedgerPoint methPoint = point(
                "5", "wallet-a", NetworkId.BASE, "FAMILY:METH",
                AssetLedgerPoint.BasisEffect.DISPOSE, AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "-1", "-2500", "300", "0", "0", "0"
        );
        methPoint.setAssetSymbol("CMETH");
        methPoint.setFamilyDisplaySymbol("CMETH");
        methPoint.setNetRealisedPnlDeltaUsd(new BigDecimal("300"));

        when(userSessionRepository.findById("session-members")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-members",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-members", "FAMILY:ETH"
        )).thenReturn(List.of(ethBuy));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-members", "FAMILY:METH"
        )).thenReturn(List.of(methPoint));
        when(normalizedTransactionRepository.findAllById(List.of("1")))
                .thenReturn(List.of(normalized("1", "Coinbase", "WETH", "BUY", "1", "2000")));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService.SessionAssetLedgerView view = service()
                .findSessionFamilyLedger("session-members", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().familyMemberSymbols()).containsExactly("WETH", "CMETH");
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
