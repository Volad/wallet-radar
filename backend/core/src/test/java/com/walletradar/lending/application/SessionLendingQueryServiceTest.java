package com.walletradar.lending.application;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.lending.persistence.LendingMarketRateSnapshot;
import com.walletradar.lending.persistence.LendingGroupRefreshStateRepository;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.lending.persistence.LendingReceiptIdentityRepository;
import com.walletradar.session.application.AccountingUniverseService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLendingQueryServiceTest {

    private static final String SESSION_ID = "session-1";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String EULER_EUSDC2_VAULT = "0x39de0f00189306062d79edec6dca5bb6bfd108f9";
    private static final String USDC_AVAX = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
    private static final String MORPHO_GT_USDCC_SHARE = "0x7e97fa6893871a2751b5fe961978dccb2c201e65";
    private static final String MORPHO_WSTETH_VAULT = "0xa1b2c3d4e5f6789012345678901234567890abcd";

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private LendingHealthFactorSnapshotService healthFactorSnapshotService;
    @Mock
    private LendingMarketRateSnapshotService marketRateSnapshotService;
    @Mock
    private LendingReceiptIdentityRepository receiptIdentityRepository;
    @Mock
    private ProtocolRegistryService protocolRegistryService;
    @Mock
    private LendingGroupRefreshStateRepository lendingGroupRefreshStateRepository;

    private SessionLendingQueryService newService() {
        return newService(null, null);
    }

    private SessionLendingQueryService newService(
            LendingMarketRateSnapshotService rateService,
            LendingHealthFactorSnapshotService healthService
    ) {
        lenient().when(receiptIdentityRepository.findByNetworkIdAndContractAddress(any(), any()))
                .thenReturn(Optional.empty());
        LendingReceiptIdentityService identityService = new LendingReceiptIdentityService(
                receiptIdentityRepository,
                protocolRegistryService
        );
        return new SessionLendingQueryService(
                userSessionRepository,
                accountingUniverseService,
                mongoOperations,
                new LendingMarketMetricEstimator(),
                rateService,
                healthService,
                lendingGroupRefreshStateRepository,
                new LendingMarketKeyResolver(identityService),
                identityService
        );
    }

    @Test
    void historicalFluidBorrowRowsDoNotCreateCurrentOpenBorrowPosition() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(fluidBorrow()));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        Optional<SessionLendingQueryService.SessionLendingView> result = service.findSessionLending(SESSION_ID);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().groups()).singleElement().satisfies(group -> {
            assertThat(group.protocol()).isEqualTo("Fluid");
            assertThat(group.status()).isEqualTo("CLOSED");
            assertThat(group.positions()).isEmpty();
            assertThat(group.cycles()).singleElement().satisfies(cycle -> {
                assertThat(cycle.status()).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
                assertThat(cycle.events()).singleElement()
                        .extracting(SessionLendingQueryService.LendingHistoryEntryView::type)
                        .isEqualTo("BORROW");
            });
        });
    }

    @Test
    void aaveCurrentReceiptPositionAttachesToAccountPoolLifecycleMarket() {
        UserSession session = session();
        String aAvaUsdc = "0x625e7708f30ca75bfd92586e17077590c60eb4cd";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                aaveDeposit("0xa8", "401.429002", "401.429001", Instant.parse("2026-04-26T19:12:09Z")),
                aaveWithdraw("0x6411", "401.429001", "401.429069", Instant.parse("2026-04-26T19:13:08Z")),
                aaveDeposit("0xd7", "401.429069", "401.429068", Instant.parse("2026-04-26T19:13:49Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(aaveLedgerPoint(aAvaUsdc)));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(aaveBalance(aAvaUsdc)));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "AVALANCHE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        assertThat(group.status()).isEqualTo("OPEN");
        assertThat(group.cycles()).noneMatch(cycle -> cycle.marketKey().equals("Aave:AVALANCHE:AAVAUSDC"));
        assertThat(group.cycles()).filteredOn(cycle -> "CLOSED".equals(cycle.status()))
                .singleElement()
                .satisfies(cycle -> {
                    assertThat(cycle.marketKey()).isEqualTo("Aave:AVALANCHE:ACCOUNT-POOL");
                    assertThat(cycle.startTxHash()).isEqualTo("0xa8");
                    assertThat(cycle.closeTxHash()).isEqualTo("0x6411");
                    assertThat(cycle.events()).extracting(SessionLendingQueryService.LendingHistoryEntryView::txHash)
                            .containsExactly("0x6411", "0xa8");
                });
        assertThat(group.cycles()).filteredOn(cycle -> "OPEN".equals(cycle.status()))
                .singleElement()
                .satisfies(cycle -> {
                    assertThat(cycle.marketKey()).isEqualTo("Aave:AVALANCHE:ACCOUNT-POOL");
                    assertThat(cycle.events()).extracting(SessionLendingQueryService.LendingHistoryEntryView::txHash)
                            .containsExactly("0xd7");
                    assertThat(cycle.positions()).singleElement()
                            .extracting(SessionLendingQueryService.LendingPositionView::marketKey)
                            .isEqualTo("Aave:AVALANCHE:ACCOUNT-POOL");
                    assertThat(cycle.pnlBreakdown().method()).doesNotContain("unresolved principal exit");
                });
    }

    @Test
    void repayDoesNotCloseCycleWhenSupplyRemainsAndRewardAttachesToOpenCycle() {
        UserSession session = session();
        String aManWeth = "0xeac30ed8609f564ae65c809c4bf42db2ff426d2c";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.MANTLE, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "WETH", "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead", "-3.06", Instant.parse("2026-02-19T08:17:12Z")),
                lendingEvent("0xborrow", NetworkId.MANTLE, NormalizedTransactionType.BORROW, "Aave",
                        "USDE", "0xbeefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "2500", Instant.parse("2026-02-19T08:18:12Z")),
                lendingEvent("0xrepay", NetworkId.MANTLE, NormalizedTransactionType.REPAY, "Aave",
                        "USDE", "0xbeefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "-2503.719327739524", Instant.parse("2026-04-26T18:33:16Z")),
                lendingEvent("0xreward", NetworkId.MANTLE, NormalizedTransactionType.REWARD_CLAIM, "Aave",
                        "AMANWMNT", "0xfeedfeedfeedfeedfeedfeedfeedfeedfeedfeed", "29.22129098756597", Instant.parse("2026-04-26T19:07:32Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(aaveMantleLedgerPoint(aManWeth)));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(aaveMantleBalance(aManWeth)));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "MANTLE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        assertThat(group.status()).isEqualTo("OPEN");
        assertThat(group.cycles()).singleElement().satisfies(cycle -> {
            assertThat(cycle.status()).isEqualTo("OPEN");
            assertThat(cycle.startTxHash()).isEqualTo("0xdeposit");
            assertThat(cycle.closeTxHash()).isNull();
            assertThat(cycle.events()).extracting(SessionLendingQueryService.LendingHistoryEntryView::txHash)
                    .containsExactly("0xreward", "0xrepay", "0xborrow", "0xdeposit");
        });
    }

    @Test
    void outstandingBorrowWithoutDebtTokenBalanceSynthesizesBorrowPosition() {
        UserSession session = session();
        String aManWeth = "0xeac30ed8609f564ae65c809c4bf42db2ff426d2c";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.MANTLE, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "WETH", "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead", "-3.06", Instant.parse("2026-02-19T08:17:12Z")),
                lendingEvent("0xborrow", NetworkId.MANTLE, NormalizedTransactionType.BORROW, "Aave",
                        "USDE", "0xbeefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "5000", Instant.parse("2026-02-19T08:18:12Z")),
                lendingEvent("0xrepay", NetworkId.MANTLE, NormalizedTransactionType.REPAY, "Aave",
                        "USDE", "0xbeefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "-2503.72", Instant.parse("2026-04-26T18:33:16Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(aaveMantleLedgerPoint(aManWeth)));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(aaveMantleBalance(aManWeth)));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());
        when(healthFactorSnapshotService.latestFresh(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(marketRateSnapshotService.latestFresh(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String underlying = invocation.getArgument(4);
                    String side = invocation.getArgument(5);
                    if ("USDE".equals(underlying) && "BORROW".equals(side)) {
                        return Optional.of(usdeBorrowRateSnapshot());
                    }
                    return Optional.empty();
                });

        SessionLendingQueryService service = newService(marketRateSnapshotService, healthFactorSnapshotService);

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "MANTLE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        assertThat(group.status()).isEqualTo("OPEN");
        // Outstanding USDE debt (5000 borrowed - 2503.72 repaid) is now reflected in borrowUsd.
        assertThat(group.borrowUsd()).isEqualByComparingTo("2496.28");
        assertThat(group.netExposureUsd()).isEqualByComparingTo("-2496.28");
        // Health factor is reconstructed from accounting, never fabricated as a live snapshot.
        assertThat(group.healthSource()).isEqualTo("ACCOUNTING_ESTIMATE");
        assertThat(group.healthStale()).isTrue();
        assertThat(group.positions())
                .filteredOn(position -> "BORROW".equals(position.side()))
                .singleElement()
                .satisfies(position -> {
                    assertThat(position.id()).endsWith(":synthetic-borrow");
                    assertThat(position.underlyingSymbol()).isEqualTo("USDE");
                    assertThat(position.quantity()).isEqualByComparingTo("2496.28");
                    assertThat(position.valueUsd()).isEqualByComparingTo("2496.28");
                    assertThat(position.metricStatus()).isEqualTo("ACCOUNTING_ESTIMATE");
                    assertThat(position.protocolBorrowApyPct()).isEqualByComparingTo("4.25");
                    assertThat(position.protocolApyStatus()).isEqualTo(LendingMarketRateStatus.PROTOCOL_SNAPSHOT);
                });
        assertThat(group.cycles()).singleElement().satisfies(cycle -> {
                assertThat(cycle.positions())
                        .anyMatch(position -> "BORROW".equals(position.side())
                                && position.id() != null
                                && position.id().endsWith(":synthetic-borrow"));
                assertThat(cycle.factualApy().factualBorrowApyByAsset()).doesNotContainKey("USDE");
                assertThat(cycle.pnlAssetBreakdown().borrowPnlUsdByAsset()).doesNotContainKey("USDE");
        });
    }

    @Test
    void liveDebtTokenBalanceIsNotDoubleCountedBySynthesis() {
        UserSession session = session();
        String aManWeth = "0xeac30ed8609f564ae65c809c4bf42db2ff426d2c";
        String debtUsde = "0xdebt00000000000000000000000000000000usde";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.MANTLE, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "WETH", "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead", "-3.06", Instant.parse("2026-02-19T08:17:12Z")),
                lendingEvent("0xborrow", NetworkId.MANTLE, NormalizedTransactionType.BORROW, "Aave",
                        "USDE", "0xbeefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "5000", Instant.parse("2026-02-19T08:18:12Z")),
                lendingEvent("0xrepay", NetworkId.MANTLE, NormalizedTransactionType.REPAY, "Aave",
                        "USDE", "0xbeefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "-2503.72", Instant.parse("2026-04-26T18:33:16Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(
                aaveMantleLedgerPoint(aManWeth),
                aaveMantleDebtLedgerPoint(debtUsde)
        ));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(
                aaveMantleBalance(aManWeth),
                aaveMantleDebtBalance(debtUsde)
        ));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "MANTLE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        // A live debt-token balance already represents the debt: do NOT also synthesize it.
        assertThat(group.positions()).noneMatch(position -> position.id() != null
                && position.id().endsWith(":synthetic-borrow"));
        assertThat(group.positions())
                .filteredOn(position -> "BORROW".equals(position.side()))
                .singleElement()
                .satisfies(position -> {
                    assertThat(position.id()).doesNotEndWith(":synthetic-borrow");
                    assertThat(position.assetContract()).isEqualTo(debtUsde);
                    assertThat(position.underlyingSymbol()).isEqualTo("USDE");
                });
        // borrowUsd comes solely from the live debt-token balance (2496.28 @ $1), not doubled.
        assertThat(group.borrowUsd()).isEqualByComparingTo("2496.28");
    }

    @Test
    void compoundClosedCycleWithMissingPrincipalExitExposesWarning() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xe31f", NetworkId.UNICHAIN, NormalizedTransactionType.LENDING_DEPOSIT, "Compound",
                        "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", "-62.787753", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0x6244", NetworkId.UNICHAIN, NormalizedTransactionType.REWARD_CLAIM, "Compound",
                        "COMP", "0xc00e94cb662c3520282e6f5717214004a7f26888", "0.22549", Instant.parse("2026-01-02T00:00:00Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Compound".equals(candidate.protocol()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "0xe31f".equals(candidate.startTxHash()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.status()).isEqualTo("CLOSED");
        assertThat(cycle.startTxHash()).isEqualTo("0xe31f");
        assertThat(cycle.statusDetail()).isEqualTo("closed/current-state-zero");
        assertThat(cycle.warningReason()).isEqualTo("unresolved_principal_exit");
        assertThat(cycle.realizedPnl().method()).contains("unresolved_principal_exit");
    }

    @Test
    void compoundCollateralWithdrawWithoutEntryRemainsSeparateReviewEvent() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xusdc-supply", NetworkId.UNICHAIN, NormalizedTransactionType.LENDING_DEPOSIT, "Compound",
                        "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", "-68.747223", Instant.parse("2025-11-01T18:34:00Z")),
                lendingEvent("0xweth-withdraw", NetworkId.UNICHAIN, NormalizedTransactionType.LENDING_WITHDRAW, "Compound",
                        "WETH", "0x4200000000000000000000000000000000000006", "0.000000000430603782", Instant.parse("2026-01-22T09:41:52Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        List<SessionLendingQueryService.LendingCycleView> cycles = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Compound".equals(candidate.protocol()))
                .findFirst()
                .orElseThrow()
                .cycles();

        assertThat(cycles).filteredOn(cycle -> "CLOSED".equals(cycle.status()))
                .singleElement()
                .satisfies(cycle -> {
                    assertThat(cycle.startTxHash()).isEqualTo("0xusdc-supply");
                    assertThat(cycle.events()).extracting(SessionLendingQueryService.LendingHistoryEntryView::txHash)
                            .containsExactly("0xusdc-supply");
                    assertThat(cycle.warningReason()).isEqualTo("unresolved_principal_exit");
                });
        assertThat(cycles).filteredOn(cycle -> "AMBIGUOUS_NEEDS_REVIEW".equals(cycle.status()))
                .singleElement()
                .satisfies(cycle -> {
                    assertThat(cycle.startTxHash()).isEqualTo("0xweth-withdraw");
                    assertThat(cycle.pnlBreakdown().method()).contains("unresolved lifecycle");
                });
    }

    @Test
    void compoundBulkerLifecycleBuildsClosedBorrowCycleWithAssetPnl() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                compoundLoopEvent(
                        "0xcb8483",
                        NormalizedTransactionType.LENDING_LOOP_OPEN,
                        Instant.parse("2025-09-12T08:03:39Z"),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "ETH", null, new BigDecimal("-0.919170497571836978")),
                                flow(NormalizedLegRole.TRANSFER, "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", new BigDecimal("2050"))
                        )
                ),
                lendingEvent("0xe31f", NetworkId.UNICHAIN, NormalizedTransactionType.LENDING_DEPOSIT, "Compound",
                        "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", "-62.787753", Instant.parse("2025-11-01T18:34:00Z")),
                lendingEvent("0x9374", NetworkId.UNICHAIN, NormalizedTransactionType.LENDING_DEPOSIT, "Compound",
                        "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", "-5.959470", Instant.parse("2025-11-05T10:24:05Z")),
                compoundLoopEvent(
                        "0xf289",
                        NormalizedTransactionType.LENDING_LOOP_DECREASE,
                        Instant.parse("2025-11-17T08:27:11Z"),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", new BigDecimal("-1209.373557")),
                                flow(NormalizedLegRole.TRANSFER, "ETH", null, new BigDecimal("0.5"))
                        )
                ),
                compoundLoopEvent(
                        "0x38d",
                        NormalizedTransactionType.LENDING_LOOP_CLOSE,
                        Instant.parse("2025-11-17T08:32:24Z"),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", new BigDecimal("-789.477787")),
                                flow(NormalizedLegRole.TRANSFER, "ETH", null, new BigDecimal("0.419170497141233196"))
                        )
                ),
                lendingEvent("0x56f772", NetworkId.UNICHAIN, NormalizedTransactionType.LENDING_WITHDRAW, "Compound",
                        "WETH", "0x4200000000000000000000000000000000000006", "0.000000000430603782", Instant.parse("2026-01-22T09:41:52Z")),
                lendingEvent("0x6244", NetworkId.UNICHAIN, NormalizedTransactionType.REWARD_CLAIM, "Compound",
                        "COMP", "0xdf78e4f0a8279942ca68046476919a90f2288656", "0.22549", Instant.parse("2026-01-22T09:42:52Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Compound".equals(candidate.protocol()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "CLOSED".equals(candidate.status()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.startTxHash()).isEqualTo("0xcb8483");
        assertThat(cycle.closeTxHash()).isEqualTo("0x56f772");
        assertThat(cycle.warningReason()).isNull();
        assertThat(cycle.assetDeltas().borrowedByAsset()).containsEntry("USDC", new BigDecimal("2050"));
        assertThat(cycle.assetDeltas().repaidByAsset()).containsEntry("USDC", new BigDecimal("2067.598567"));
        assertThat(cycle.pnlAssetBreakdown().borrowCostByAsset()).containsEntry("USDC", new BigDecimal("17.598567"));
        assertThat(cycle.pnlAssetBreakdown().rewardsByAsset()).containsEntry("COMP", new BigDecimal("0.22549"));
        assertThat(cycle.events()).extracting(SessionLendingQueryService.LendingHistoryEntryView::txHash)
                .contains("0x56f772", "0x38d", "0xf289", "0x9374", "0xe31f", "0xcb8483");
    }

    @Test
    void fluidArbitrumLoopOpenEmitsBorrowChildAndBorrowCostUsesRepayMinusBorrow() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                fluidLoopEvent(
                        "0x7ffe",
                        NetworkId.ARBITRUM,
                        NormalizedTransactionType.LENDING_LOOP_OPEN,
                        Instant.parse("2025-10-01T00:00:00Z"),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "wstUSR", "0x2a52b289ba68bbd02676640aa9f605700c9e5699", new BigDecimal("-1959.424764648563")),
                                flow(NormalizedLegRole.TRANSFER, "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", new BigDecimal("1800"))
                        )
                ),
                withFluidChildLegs(
                        withCounterparty(lendingEvent("0x0399", NetworkId.ARBITRUM, NormalizedTransactionType.REPAY, "Fluid",
                                "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "-1808.868212", Instant.parse("2025-10-02T00:00:00Z")),
                                "0x3e11b9aeb9c7dbbda4dd41477223cc2f3f24b9d7"),
                        List.of(
                                fluidChildLeg("repay", "REPAY", "FLUID_WALLET_VISIBLE_REPAY", "USDC", "1808.868212"),
                                fluidChildLeg("repay:0x1d", "REPAY", "FLUID_LOG_OPERATE_REPAY", "USDC", "1808.868212")
                        )
                ),
                withCounterparty(lendingEvent("0xa664", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_WITHDRAW, "Fluid",
                        "wstUSR", "0x2a52b289ba68bbd02676640aa9f605700c9e5699", "1959.424764648562982911", Instant.parse("2025-10-02T00:01:00Z")),
                        "0x3e11b9aeb9c7dbbda4dd41477223cc2f3f24b9d7")
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Fluid".equals(candidate.protocol()) && "ARBITRUM".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "CLOSED".equals(candidate.status()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.assetDeltas().borrowedByAsset()).containsEntry("USDC", new BigDecimal("1800"));
        assertThat(cycle.assetDeltas().repaidByAsset()).containsEntry("USDC", new BigDecimal("1808.868212"));
        assertThat(cycle.pnlAssetBreakdown().borrowCostByAsset()).containsEntry("USDC", new BigDecimal("8.868212"));
        assertThat(cycle.observedFlowsByAsset().get("USDC"))
                .filteredOn(flow -> "0x0399".equals(flow.sourceTxHash()))
                .hasSize(1)
                .extracting(SessionLendingQueryService.LendingObservedFlowView::sourceKind)
                .containsExactly("WALLET_VISIBLE_TRANSFER");
        assertThat(cycle.txGroups().stream()
                .flatMap(group -> group.items().stream())
                .filter(item -> "0x0399".equals(item.txHash()))
                .filter(item -> "REPAY".equals(item.type())))
                .hasSize(1);
    }

    @Test
    void morphoBundlerChildLegsReconcileWstEthCollateralInputsToWithdrawal() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withCounterparty(withFluidChildLegs(lendingEvent("0xe195", NetworkId.ARBITRUM, NormalizedTransactionType.VAULT_DEPOSIT, "Morpho",
                        "WSTETH", "0x5979d7b546e38e414f7e9822514be443a4800529", "-0.010708250924555605", Instant.parse("2025-09-29T13:13:25Z")),
                        List.of(fluidChildLeg("morpho:collateral:0", "LENDING_DEPOSIT", "MORPHO_BUNDLER_COLLATERAL_IN", "WSTETH", "0.010708250924555605"))),
                        MORPHO_WSTETH_VAULT),
                withCounterparty(withFluidChildLegs(lendingEvent("0x7eb876", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_LOOP_OPEN, "Morpho",
                        "WSTETH", "0x5979d7b546e38e414f7e9822514be443a4800529", "-0.022742145033450122", Instant.parse("2025-10-15T11:42:54Z")),
                        List.of(
                                fluidChildLeg("morpho:collateral:0", "LENDING_LOOP_OPEN", "MORPHO_BUNDLER_COLLATERAL_IN", "WSTETH", "0.022742145033450122"),
                                fluidChildLeg("morpho:borrow:1", "BORROW", "MORPHO_BUNDLER_BORROW", "USDC", "50")
                        )),
                        MORPHO_WSTETH_VAULT),
                withCounterparty(withFluidChildLegs(lendingEvent("0xf767", NetworkId.ARBITRUM, NormalizedTransactionType.VAULT_DEPOSIT, "Morpho",
                        "WSTETH", "0x5979d7b546e38e414f7e9822514be443a4800529", "-0.024883544264968890", Instant.parse("2025-11-01T19:18:15Z")),
                        List.of(fluidChildLeg("morpho:collateral:0", "LENDING_DEPOSIT", "MORPHO_BUNDLER_COLLATERAL_IN", "WSTETH", "0.024883544264968890"))),
                        MORPHO_WSTETH_VAULT),
                withCounterparty(lendingEvent("0xedf2", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_WITHDRAW, "Morpho",
                        "WSTETH", "0x5979d7b546e38e414f7e9822514be443a4800529", "0.058333940222974617", Instant.parse("2025-11-21T06:43:01Z")),
                        MORPHO_WSTETH_VAULT)
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Morpho".equals(candidate.protocol()) && "ARBITRUM".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> candidate.marketKey().contains("A1B2C3D4"))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.assetDeltas().principalInByAsset())
                .containsEntry("WSTETH", new BigDecimal("0.058333940222974617"));
        assertThat(cycle.assetDeltas().principalOutByAsset())
                .containsEntry("WSTETH", new BigDecimal("0.058333940222974617"));
        assertThat(cycle.assetDeltas().netCashDeltaByAsset().get("WSTETH"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void morphoGtUsdccCycleLinksAdjacentCollateralOutputsIntoMultiAssetPnl() {
        UserSession session = session();
        Instant first = Instant.parse("2025-11-05T15:05:50Z");
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                morphoGtUsdccDeposit("0x0f2b", "-1816.826013", "1794.511540931197938841", first),
                morphoGtUsdccWithdraw("0x926b", "-1738.882122851812307369", "0.660311", first.plus(Duration.ofDays(7))),
                withFluidChildLegs(lendingEvent("0xcec2", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_WITHDRAW, "Morpho",
                        "wstUSR", "0x66cfbd79257dc5217903a36293120282548e2254", "1959.424764648563", first.plus(Duration.ofDays(7)).plusSeconds(41)),
                        List.of(fluidChildLeg("morpho:withdraw:0", "LENDING_WITHDRAW", "MORPHO_BUNDLER_COLLATERAL_OUT", "wstUSR", "1959.424764648563"))),
                morphoGtUsdccDeposit("0x1813", "-1800.660311", "1776.026778718890452247", first.plus(Duration.ofDays(7)).plusSeconds(267)),
                morphoGtUsdccWithdraw("0xf070", "-130.518133781250607134", "0.085302", first.plus(Duration.ofDays(16))),
                withFluidChildLegs(lendingEvent("0xedf2", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_WITHDRAW, "Morpho",
                        "wstETH", "0x5979d7b546e38e414f7e9822514be443a4800529", "0.058333940222974617", first.plus(Duration.ofDays(16)).plusSeconds(89)),
                        List.of(fluidChildLeg("morpho:withdraw:0", "LENDING_WITHDRAW", "MORPHO_BUNDLER_COLLATERAL_OUT", "wstETH", "0.058333940222974617"))),
                morphoGtUsdccWithdraw("0x2f76", "-196.709445668981240448", "200", first.plus(Duration.ofDays(29))),
                morphoGtUsdccDeposit("0x6bc9", "-300", "295.063912333654056767", first.plus(Duration.ofDays(29)).plusSeconds(604)),
                morphoGtUsdccWithdraw("0xd2c9", "-1799.492529681698292904", "1832.141519", first.plus(Duration.ofDays(41)))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(
                historicalPrice("wstUSR", first.plus(Duration.ofDays(7)).plusSeconds(41), "1.1184495487210164"),
                historicalPrice("wstETH", first.plus(Duration.ofDays(16)).plusSeconds(89), "3424.001475258698")
        ));

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Morpho".equals(candidate.protocol()) && "ARBITRUM".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> candidate.marketKey().contains("7E97FA68"))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.assetDeltas().principalInByAsset()).containsEntry("USDC", new BigDecimal("3917.486324"));
        assertThat(cycle.assetDeltas().principalOutCashByAsset()).containsEntry("USDC", new BigDecimal("2032.887132"));
        assertThat(cycle.assetDeltas().principalOutCashByAsset().get("WSTUSR")).isEqualByComparingTo("1959.424764648563000000");
        assertThat(cycle.assetDeltas().principalOutCashByAsset()).containsEntry("WSTETH", new BigDecimal("0.058333940222974617"));
        assertThat(cycle.pnlAssetBreakdown().supplyIncomeByAsset()).doesNotContainKey("USDC");
        assertThat(cycle.pnlAssetBreakdown().reasonByAsset()).containsEntry("USDC", LendingFactualApyCalculator.NO_YIELD_FLOW_EVIDENCE);
        assertThat(cycle.totalValuation().totalUsdPnl()).isEqualByComparingTo("506.654049155086847240641321901866");
        // Cross-foot: per-asset USD P&L (non-$1 multi-asset) reconciles to the published total.
        assertCrossFoot(cycle.pnlAssetBreakdown().netIncomeUsdByAsset(), cycle.totalValuation().totalUsdPnl());
        assertThat(cycle.pnlAssetBreakdown().netIncomeUsdByAsset()).isNotEmpty();
    }

    @Test
    void fluidPlasmaUnavailablePnlExposesObservedFlowsOutsidePnlMaps() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0x01ca", NetworkId.PLASMA, NormalizedTransactionType.LENDING_LOOP_OPEN, "Fluid",
                        "USDT0", "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb", "-496.366838", Instant.parse("2025-10-02T19:12:38Z")),
                lendingEvent("0x807", NetworkId.PLASMA, NormalizedTransactionType.LENDING_LOOP_DECREASE, "Fluid",
                        "USDT0", "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb", "0.152536", Instant.parse("2025-12-18T14:14:47Z")),
                lendingEvent("0xf1ff", NetworkId.PLASMA, NormalizedTransactionType.LENDING_WITHDRAW, "Fluid",
                        "USDT0", "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb", "499.597618", Instant.parse("2025-12-18T14:16:00Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Fluid".equals(candidate.protocol()) && "PLASMA".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "CLOSED".equals(candidate.status()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.warningReason()).isEqualTo("pnl_unavailable_missing_full_receipt_logs");
        assertThat(cycle.pnlAssetBreakdown().supplyIncomeByAsset()).isEmpty();
        assertThat(cycle.observedFlowsByAsset()).containsKey("USDT");
        assertThat(cycle.observedFlowsByAsset().get("USDT"))
                .extracting(SessionLendingQueryService.LendingObservedFlowView::assetSymbol)
                .containsOnly("USDT0");
        assertThat(cycle.observedFlowsByAsset().get("USDT"))
                .allSatisfy(flow -> assertThat(flow.isAuthoritativeForPnl()).isFalse());
    }

    @Test
    void borrowSupplyWithinTwentyFourHoursIsCollapsedIntoLoopGroup() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "WETH", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "-1.0", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0xborrow", NetworkId.ARBITRUM, NormalizedTransactionType.BORROW, "Aave",
                        "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "1000", Instant.parse("2026-01-01T01:00:00Z")),
                lendingEvent("0xloop-supply", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "WETH", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "-0.25", Instant.parse("2026-01-01T02:00:00Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "ARBITRUM".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "0xdeposit".equals(candidate.startTxHash()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.txGroups()).extracting(SessionLendingQueryService.LendingTxGroupView::type)
                .containsExactly("open", "loop");
        assertThat(cycle.txGroups().get(1).loopSteps()).isEqualTo(1);
        assertThat(cycle.txGroups().get(1).loopAssetIn()).isEqualTo("USDC");
        assertThat(cycle.txGroups().get(1).loopAssetOut()).isEqualTo("ETH");
        assertThat(cycle.txGroups().get(1).items()).extracting(SessionLendingQueryService.LendingTxItemView::txHash)
                .containsExactly("0xborrow", "0xloop-supply");
    }

    @Test
    void stableDebtReceiptSymbolsAreCanonicalizedForCycleDeltasAndPnl() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.AVALANCHE, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "GHO", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", "-100", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0xborrow", NetworkId.AVALANCHE, NormalizedTransactionType.BORROW, "Aave",
                        "GHO", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", "100", Instant.parse("2026-01-01T00:01:00Z")),
                lendingEvent("0xrepay", NetworkId.AVALANCHE, NormalizedTransactionType.REPAY, "Aave",
                        "variableDebtAvaGHO", "0x38d693ce1df5aadf7bc62595a37d667ad57922e5", "-100.5", Instant.parse("2026-01-02T00:00:00Z")),
                withFee(
                        aaveWithdrawWithYield("0xwithdraw", "100", "0.1", Instant.parse("2026-01-02T00:01:00Z")),
                        "ETH",
                        "0.01",
                        "20"
                )
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "AVALANCHE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "0xdeposit".equals(candidate.startTxHash()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.status()).isEqualTo("CLOSED");
        assertThat(cycle.assetDeltas().repaidByAsset()).containsEntry("GHO", new BigDecimal("100.5"));
        assertThat(cycle.assetDeltas().withdrawnByAsset()).containsEntry("GHO", new BigDecimal("100"));
        assertThat(cycle.pnlAssetBreakdown().supplyIncomeByAsset()).containsEntry("GHO", new BigDecimal("0.1"));
        assertThat(cycle.pnlAssetBreakdown().borrowCostByAsset()).containsEntry("GHO", new BigDecimal("0.5"));
        assertThat(cycle.pnlAssetBreakdown().gasByAsset()).containsEntry("ETH", new BigDecimal("0.01"));
        assertThat(cycle.pnlAssetBreakdown().netIncomeByAsset()).containsEntry("GHO", new BigDecimal("-0.4"));
        assertThat(cycle.pnlAssetBreakdown().netIncomeByAsset()).containsEntry("ETH", new BigDecimal("-0.01"));
        assertThat(cycle.pnlAssetBreakdown().precisionByAsset()).containsEntry("GHO", "EXACT");
        assertThat(cycle.pnlBreakdown().method()).isEqualTo("interest-earned-minus-paid-minus-gas");
        assertThat(cycle.totalValuation().totalUsdPnl()).isEqualByComparingTo("-20.5");
        assertThat(cycle.totalValuation().totalUsdPnlPrecision()).isEqualTo("EXACT");
        assertThat(cycle.totalValuation().yieldOnlyPnl()).isEqualByComparingTo("-20.4");
    }

    @Test
    void eurcDoesNotUseUsdParityWhenCachedEcbPriceIsMissing() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.BASE, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "EURC", "0x60a3e35cc302bfa44cb288bc5a4f316fdb1adb42", "-100", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0xwithdraw", NetworkId.BASE, NormalizedTransactionType.LENDING_WITHDRAW, "Aave",
                        "EURC", "0x60a3e35cc302bfa44cb288bc5a4f316fdb1adb42", "101", Instant.parse("2026-01-02T00:00:00Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "BASE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .findFirst()
                .orElseThrow();

        assertThat(cycle.totalValuation().totalUsdPnl()).isNull();
        assertThat(cycle.totalValuation().totalUsdPnlPrecision()).isEqualTo("UNAVAILABLE");
        assertThat(cycle.totalValuation().unavailableReason()).isEqualTo("missing_lending_leg_usd_valuation");
        // Missing valueUsd => per-asset USD is UNAVAILABLE (empty), never emitted as $0.
        assertThat(cycle.pnlAssetBreakdown().netIncomeUsdByAsset()).isEmpty();
        assertThat(cycle.pnlAssetBreakdown().usdPrecisionByAsset()).isEmpty();
    }

    @Test
    void wstUsrTotalValuationCanBeEstimatedWhileYieldOnlyStaysUnavailable() {
        UserSession session = session();
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.PLASMA, NormalizedTransactionType.VAULT_DEPOSIT, "Fluid",
                        "wstUSR", "0x2a52b289ba68bbd02676640aa9f605700c9e5699", "-100", timestamp),
                lendingEvent("0xwithdraw", NetworkId.PLASMA, NormalizedTransactionType.VAULT_WITHDRAW, "Fluid",
                        "wstUSR", "0x2a52b289ba68bbd02676640aa9f605700c9e5699", "101", timestamp.plusSeconds(60))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(
                historicalPrice("WSTUSR", timestamp, "1.10")
        ));

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Fluid".equals(candidate.protocol()) && "PLASMA".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .findFirst()
                .orElseThrow();

        assertThat(cycle.totalValuation().totalUsdPnl()).isEqualByComparingTo("1.10");
        assertThat(cycle.totalValuation().totalUsdPnlPrecision()).isEqualTo("ESTIMATED");
        assertThat(cycle.totalValuation().yieldOnlyPnl()).isNull();
        assertThat(cycle.totalValuation().yieldOnlyPnlPrecision()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void eulerEvkSeparatesCashOutFromInternalShareMovement() {
        UserSession session = session();
        Instant start = Instant.parse("2025-07-31T10:56:42Z");
        Instant close = Instant.parse("2025-08-21T07:31:47Z");
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withFlows(lendingEvent("0xeuler-deposit", NetworkId.AVALANCHE, NormalizedTransactionType.LENDING_DEPOSIT, "Euler",
                                "USDC", USDC_AVAX, "-2595.231191", start),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "USDC", USDC_AVAX, new BigDecimal("-2595.231191")),
                                flow(NormalizedLegRole.TRANSFER, "eUSDC-2", EULER_EUSDC2_VAULT, new BigDecimal("2595.231191"))
                        )),
                withFee(
                        withFlowValue(lendingEvent("0xeuler-close", NetworkId.AVALANCHE, NormalizedTransactionType.LENDING_LOOP_DECREASE, "Euler",
                                        "EUSDC-2", EULER_EUSDC2_VAULT, "2793.036068", close),
                                "2152.278542"),
                        "AVAX",
                        "0.00384177793",
                        "0.08950617794584"
                ),
                swapEvent("0xeuler-exit-swap", NetworkId.AVALANCHE, "USDt", "-2152.278542", "USDC", "2152.278542",
                        close.plusSeconds(90))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Euler".equals(candidate.protocol()) && "AVALANCHE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .findFirst()
                .orElseThrow();

        assertThat(cycle.assetDeltas().principalInByAsset()).containsEntry("USDC", new BigDecimal("2595.231191"));
        assertThat(cycle.assetDeltas().principalOutByAsset()).containsEntry("USDC", new BigDecimal("2793.036068"));
        assertThat(cycle.assetDeltas().principalOutCashByAsset()).containsEntry("USDC", new BigDecimal("2152.278542"));
        assertThat(cycle.assetDeltas().internalReceiptMovementByAsset()).containsEntry("USDC", new BigDecimal("2793.036068"));
        assertThat(cycle.totalValuation().totalUsdPnl()).isEqualByComparingTo("-443.04215517794584");
        assertThat(cycle.factualApy().factualSupplyAprByAsset()).containsKey("USDC");
        assertThat(cycle.factualApy().factualSupplyAprByAsset().get("USDC")).isNegative();
        assertThat(cycle.largePnlReasons()).containsExactly("SHARE_RATE_EFFECT", "GAS_COST");
        assertThat(cycle.primaryLargePnlReason()).isEqualTo("SHARE_RATE_EFFECT");
    }

    @Test
    void aaveAccountPoolSplitsClosedBorrowLoopFromIndependentSupplyOnlyCycles() {
        UserSession session = session();
        String aArbWbtc = "0x078f358208685046a11c85e8ad32895ded33a249";
        String aArbArb = "0x6533afac2e7bccb20dca161449a13a32d391fb00";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xeth-supply", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "ETH", null, "-1.0", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0xusdc-borrow", NetworkId.ARBITRUM, NormalizedTransactionType.BORROW, "Aave",
                        "USDC", null, "1000", Instant.parse("2026-01-01T00:02:00Z")),
                lendingEvent("0xwbtc-supply", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "WBTC", aArbWbtc, "-0.01", Instant.parse("2026-01-10T00:00:00Z")),
                lendingEvent("0xusdc-repay", NetworkId.ARBITRUM, NormalizedTransactionType.REPAY, "Aave",
                        "USDC", null, "-1001", Instant.parse("2026-02-01T00:00:00Z")),
                lendingEvent("0xeth-withdraw", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_WITHDRAW, "Aave",
                        "WETH", null, "1.001", Instant.parse("2026-02-01T00:05:00Z")),
                lendingEvent("0xarb-supply", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "ARB", aArbArb, "-25", Instant.parse("2026-02-01T01:00:00Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(
                aaveLedgerPoint(NetworkId.ARBITRUM, "aArbWBTC", aArbWbtc, "0.01"),
                aaveLedgerPoint(NetworkId.ARBITRUM, "aArbARB", aArbArb, "25")
        ));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(
                aaveBalance(NetworkId.ARBITRUM, "aArbWBTC", aArbWbtc, "0.01"),
                aaveBalance(NetworkId.ARBITRUM, "aArbARB", aArbArb, "25")
        ));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "ARBITRUM".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        assertThat(group.cycles()).filteredOn(cycle -> "CLOSED".equals(cycle.status()))
                .singleElement()
                .satisfies(cycle -> {
                    assertThat(cycle.events())
                            .extracting(SessionLendingQueryService.LendingHistoryEntryView::txHash)
                            .containsExactly("0xeth-withdraw", "0xusdc-repay", "0xusdc-borrow", "0xeth-supply");
                    assertThat(cycle.pnlBreakdown().method()).contains("missing yield-only valuation evidence");
                });
        assertThat(group.cycles()).filteredOn(cycle -> "OPEN".equals(cycle.status()))
                .hasSize(2)
                .extracting(cycle -> cycle.events().get(0).txHash())
                .containsExactlyInAnyOrder("0xarb-supply", "0xwbtc-supply");
    }

    private static UserSession session() {
        UserSession session = new UserSession();
        session.setId(SESSION_ID);
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(WALLET);
        wallet.setNetworks(List.of(NetworkId.PLASMA));
        session.setWallets(List.of(wallet));
        return session;
    }

    private static NormalizedTransaction fluidBorrow() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("0xborrow:PLASMA:" + WALLET);
        transaction.setTxHash("0xborrow");
        transaction.setNetworkId(NetworkId.PLASMA);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(NormalizedTransactionType.BORROW);
        transaction.setProtocolName("Fluid");
        transaction.setBlockTimestamp(Instant.parse("2025-10-22T07:41:01Z"));
        transaction.setTransactionIndex(1);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol("USDT0");
        flow.setAssetContract("0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb");
        flow.setQuantityDelta(new BigDecimal("496.452304"));
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private static NormalizedTransaction aaveDeposit(
            String txHash,
            String usdcQuantity,
            String receiptQuantity,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = baseAaveTransaction(txHash, NormalizedTransactionType.LENDING_DEPOSIT, timestamp);
        transaction.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "USDC", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", new BigDecimal(usdcQuantity).negate()),
                flow(NormalizedLegRole.TRANSFER, "aAvaUSDC", "0x625e7708f30ca75bfd92586e17077590c60eb4cd", new BigDecimal(receiptQuantity))
        ));
        return transaction;
    }

    private static NormalizedTransaction aaveWithdraw(
            String txHash,
            String receiptQuantity,
            String usdcQuantity,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = baseAaveTransaction(txHash, NormalizedTransactionType.LENDING_WITHDRAW, timestamp);
        transaction.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "aAvaUSDC", "0x625e7708f30ca75bfd92586e17077590c60eb4cd", new BigDecimal(receiptQuantity).negate()),
                flow(NormalizedLegRole.TRANSFER, "USDC", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", new BigDecimal(usdcQuantity))
        ));
        return transaction;
    }

    private static NormalizedTransaction aaveWithdrawWithYield(
            String txHash,
            String principalQuantity,
            String yieldQuantity,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = baseAaveTransaction(txHash, NormalizedTransactionType.LENDING_WITHDRAW, timestamp);
        transaction.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "aGhoV3", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", new BigDecimal(principalQuantity).negate()),
                flow(NormalizedLegRole.TRANSFER, "GHO", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", new BigDecimal(principalQuantity)),
                flow(NormalizedLegRole.BUY, "GHO", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", new BigDecimal(yieldQuantity))
        ));
        return transaction;
    }

    private static NormalizedTransaction baseCompoundTransaction(
            String txHash,
            NormalizedTransactionType type,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":BASE:" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(type);
        transaction.setProtocolName("Compound");
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(1);
        return transaction;
    }

    private static NormalizedTransaction baseAaveTransaction(
            String txHash,
            NormalizedTransactionType type,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":AVALANCHE:" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(NetworkId.AVALANCHE);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(type);
        transaction.setProtocolName("Aave");
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(1);
        return transaction;
    }

    private static NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String contract,
            BigDecimal quantity
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(quantity);
        return flow;
    }

    private static HistoricalPriceDocument historicalPrice(String symbol, Instant timestamp, String priceUsd) {
        HistoricalPriceDocument document = new HistoricalPriceDocument();
        document.setSymbol(symbol);
        document.setBucketStart(timestamp);
        document.setPriceUsd(new BigDecimal(priceUsd));
        return document;
    }

    private static AssetLedgerPoint aaveLedgerPoint(String aAvaUsdc) {
        return aaveLedgerPoint(NetworkId.AVALANCHE, "aAvaUSDC", aAvaUsdc, "401.429068");
    }

    private static AssetLedgerPoint aaveLedgerPoint(
            NetworkId networkId,
            String symbol,
            String contract,
            String quantity
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingUniverseId("universe-1");
        point.setWalletAddress(WALLET);
        point.setNetworkId(networkId);
        point.setAccountingAssetIdentity(AccountingAssetIdentitySupport.positionAssetIdentity(
                networkId,
                symbol,
                contract
        ));
        point.setAssetSymbol(symbol);
        point.setAssetContract(contract);
        point.setNormalizedType("LENDING_DEPOSIT");
        point.setLifecycleKind(AssetLedgerPoint.LifecycleKind.LENDING);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        point.setProtocolName("Aave");
        point.setBasisBackedQuantityAfter(new BigDecimal(quantity));
        return point;
    }

    private static OnChainBalance aaveBalance(String aAvaUsdc) {
        return aaveBalance(NetworkId.AVALANCHE, "aAvaUSDC", aAvaUsdc, "401.429068");
    }

    private static OnChainBalance aaveBalance(
            NetworkId networkId,
            String symbol,
            String contract,
            String quantity
    ) {
        OnChainBalance balance = new OnChainBalance();
        balance.setSessionId(SESSION_ID);
        balance.setWalletAddress(WALLET);
        balance.setNetworkId(networkId);
        balance.setAssetSymbol(symbol);
        balance.setAssetContract(contract);
        balance.setQuantity(new BigDecimal(quantity));
        return balance;
    }

    private static NormalizedTransaction lendingEvent(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            String protocolName,
            String symbol,
            String contract,
            String quantity,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":" + networkId.name() + ":" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(networkId);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(type);
        transaction.setProtocolName(protocolName);
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(1);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(new BigDecimal(quantity).signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.BUY);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(quantity));
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private static NormalizedTransaction compoundLoopEvent(
            String txHash,
            NormalizedTransactionType type,
            Instant timestamp,
            List<NormalizedTransaction.Flow> flows
    ) {
        NormalizedTransaction transaction = lendingEvent(
                txHash,
                NetworkId.UNICHAIN,
                type,
                "Compound",
                "USDC",
                "0x078d782b760474a361dda0af3839290b0ef57ad6",
                "0",
                timestamp
        );
        transaction.setFlows(flows);
        transaction.setMatchedCounterparty("0x2c7118c4c88b9841fcf839074c26ae8f035f2921");
        return transaction;
    }

    private static NormalizedTransaction fluidLoopEvent(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            Instant timestamp,
            List<NormalizedTransaction.Flow> flows
    ) {
        NormalizedTransaction transaction = lendingEvent(
                txHash,
                networkId,
                type,
                "Fluid",
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "0",
                timestamp
        );
        transaction.setFlows(flows);
        transaction.setMatchedCounterparty("0x3e11b9aeb9c7dbbda4dd41477223cc2f3f24b9d7");
        return transaction;
    }

    private static NormalizedTransaction withFee(
            NormalizedTransaction transaction,
            String feeSymbol,
            String feeQuantity,
            String feeValueUsd
    ) {
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol(feeSymbol);
        fee.setQuantityDelta(new BigDecimal(feeQuantity).negate());
        fee.setValueUsd(new BigDecimal(feeValueUsd));
        List<NormalizedTransaction.Flow> flows = new java.util.ArrayList<>(transaction.getFlows());
        flows.add(fee);
        transaction.setFlows(flows);
        return transaction;
    }

    private static NormalizedTransaction withFlowValue(NormalizedTransaction transaction, String valueUsd) {
        transaction.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .findFirst()
                .orElseThrow()
                .setValueUsd(new BigDecimal(valueUsd));
        return transaction;
    }

    private static NormalizedTransaction withFlows(
            NormalizedTransaction transaction,
            List<NormalizedTransaction.Flow> flows
    ) {
        transaction.setFlows(flows);
        return transaction;
    }

    private static NormalizedTransaction morphoGtUsdccDeposit(
            String txHash,
            String usdcQuantity,
            String shareQuantity,
            Instant timestamp
    ) {
        return withFluidChildLegs(withFlows(lendingEvent(txHash, NetworkId.ARBITRUM, NormalizedTransactionType.VAULT_DEPOSIT, "Morpho",
                        "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", usdcQuantity, timestamp),
                List.of(
                        flow(NormalizedLegRole.TRANSFER, "USDC", null, new BigDecimal(usdcQuantity)),
                        flow(NormalizedLegRole.TRANSFER, "gtUSDCc", MORPHO_GT_USDCC_SHARE, new BigDecimal(shareQuantity))
                )),
                List.of(fluidChildLeg("morpho:collateral:0", "LENDING_DEPOSIT", "MORPHO_BUNDLER_COLLATERAL_IN", "USDC", new BigDecimal(usdcQuantity).abs().toPlainString())));
    }

    private static NormalizedTransaction morphoGtUsdccWithdraw(
            String txHash,
            String shareQuantity,
            String usdcQuantity,
            Instant timestamp
    ) {
        return withFluidChildLegs(withFlows(lendingEvent(txHash, NetworkId.ARBITRUM, NormalizedTransactionType.VAULT_WITHDRAW, "Morpho",
                        "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", usdcQuantity, timestamp),
                List.of(
                        flow(NormalizedLegRole.TRANSFER, "gtUSDCc", MORPHO_GT_USDCC_SHARE, new BigDecimal(shareQuantity)),
                        flow(NormalizedLegRole.TRANSFER, "USDC", null, new BigDecimal(usdcQuantity))
                )),
                List.of(fluidChildLeg("morpho:withdraw:0", "LENDING_WITHDRAW", "MORPHO_BUNDLER_COLLATERAL_OUT", "USDC", new BigDecimal(usdcQuantity).abs().toPlainString())));
    }

    private static NormalizedTransaction swapEvent(
            String txHash,
            NetworkId networkId,
            String sellSymbol,
            String sellQuantity,
            String buySymbol,
            String buyQuantity,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = lendingEvent(
                txHash,
                networkId,
                NormalizedTransactionType.SWAP,
                "Velora/ParaSwap",
                sellSymbol,
                null,
                sellQuantity,
                timestamp
        );
        NormalizedTransaction.Flow sell = transaction.getFlows().get(0);
        sell.setRole(NormalizedLegRole.SELL);
        sell.setValueUsd(new BigDecimal(sellQuantity).abs());
        NormalizedTransaction.Flow buy = flow(NormalizedLegRole.BUY, buySymbol, null, new BigDecimal(buyQuantity));
        buy.setValueUsd(new BigDecimal(buyQuantity).abs());
        transaction.setFlows(List.of(sell, buy));
        return transaction;
    }

    private static NormalizedTransaction withCounterparty(NormalizedTransaction transaction, String counterparty) {
        transaction.setMatchedCounterparty(counterparty);
        return transaction;
    }

    private static NormalizedTransaction withFluidChildLegs(
            NormalizedTransaction transaction,
            List<Document> childLegs
    ) {
        transaction.setMetadata(new Document("lendingChildLegs", childLegs)
                .append("evidenceCompleteness", "FULL_LOGS_PRESENT"));
        return transaction;
    }

    private static Document fluidChildLeg(
            String id,
            String type,
            String eventSubtype,
            String assetSymbol,
            String quantity
    ) {
        return new Document("id", id)
                .append("type", type)
                .append("eventSubtype", eventSubtype)
                .append("displayType", "Repay")
                .append("assetSymbol", assetSymbol)
                .append("quantity", new BigDecimal(quantity));
    }

    private static LendingMarketRateSnapshot usdeBorrowRateSnapshot() {
        LendingMarketRateSnapshot snapshot = new LendingMarketRateSnapshot();
        snapshot.setSessionId(SESSION_ID);
        snapshot.setProtocol("Aave");
        snapshot.setNetworkId("MANTLE");
        snapshot.setMarketKey("Aave:MANTLE:ACCOUNT-POOL");
        snapshot.setUnderlyingSymbol("USDE");
        snapshot.setSide("BORROW");
        snapshot.setBorrowApyPct(new BigDecimal("4.25"));
        snapshot.setNetBorrowApyPct(new BigDecimal("4.25"));
        snapshot.setRateStatus(LendingMarketRateStatus.PROTOCOL_SNAPSHOT);
        snapshot.setRateSource("AAVE_V3_POOL");
        snapshot.setCapturedAt(Instant.parse("2026-06-24T00:00:00Z"));
        return snapshot;
    }

    private static AssetLedgerPoint aaveMantleLedgerPoint(String aManWeth) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingUniverseId("universe-1");
        point.setWalletAddress(WALLET);
        point.setNetworkId(NetworkId.MANTLE);
        point.setAccountingAssetIdentity(AccountingAssetIdentitySupport.positionAssetIdentity(
                NetworkId.MANTLE,
                "aManWETH",
                aManWeth
        ));
        point.setAssetSymbol("aManWETH");
        point.setAssetContract(aManWeth);
        point.setNormalizedType("LENDING_DEPOSIT");
        point.setLifecycleKind(AssetLedgerPoint.LifecycleKind.LENDING);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        point.setProtocolName("Aave");
        point.setBasisBackedQuantityAfter(new BigDecimal("3.06"));
        return point;
    }

    private static OnChainBalance aaveMantleBalance(String aManWeth) {
        OnChainBalance balance = new OnChainBalance();
        balance.setSessionId(SESSION_ID);
        balance.setWalletAddress(WALLET);
        balance.setNetworkId(NetworkId.MANTLE);
        balance.setAssetSymbol("aManWETH");
        balance.setAssetContract(aManWeth);
        balance.setQuantity(new BigDecimal("3.069749245591617024"));
        return balance;
    }

    private static AssetLedgerPoint aaveMantleDebtLedgerPoint(String debtUsde) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingUniverseId("universe-1");
        point.setWalletAddress(WALLET);
        point.setNetworkId(NetworkId.MANTLE);
        point.setAccountingAssetIdentity(AccountingAssetIdentitySupport.positionAssetIdentity(
                NetworkId.MANTLE,
                "variableDebtMantleUSDE",
                debtUsde
        ));
        point.setAssetSymbol("variableDebtMantleUSDE");
        point.setAssetContract(debtUsde);
        point.setNormalizedType("BORROW");
        point.setLifecycleKind(AssetLedgerPoint.LifecycleKind.LENDING);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        point.setProtocolName("Aave");
        point.setBasisBackedQuantityAfter(new BigDecimal("2496.28"));
        return point;
    }

    private static OnChainBalance aaveMantleDebtBalance(String debtUsde) {
        OnChainBalance balance = new OnChainBalance();
        balance.setSessionId(SESSION_ID);
        balance.setWalletAddress(WALLET);
        balance.setNetworkId(NetworkId.MANTLE);
        balance.setAssetSymbol("variableDebtMantleUSDE");
        balance.setAssetContract(debtUsde);
        balance.setQuantity(new BigDecimal("2496.28"));
        return balance;
    }

    @Test
    void fluidOrphanCyclesDeduplicateByMarketKeyAndStartTxHash() {
        UserSession session = session();
        Instant timestamp = Instant.parse("2025-10-22T07:41:01Z");
        String vault = "0xb4f3bf2d00000000000000000000000000000000";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withCounterparty(
                        lendingEvent("0xduplicate", NetworkId.PLASMA, NormalizedTransactionType.LENDING_WITHDRAW, "Fluid",
                                "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "100", timestamp),
                        vault
                ),
                withCounterparty(
                        lendingEvent("0xduplicate", NetworkId.PLASMA, NormalizedTransactionType.LENDING_WITHDRAW, "Fluid",
                                "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "100", timestamp),
                        vault
                )
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        List<SessionLendingQueryService.LendingCycleView> orphans = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Fluid".equals(candidate.protocol()) && "PLASMA".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(cycle -> "AMBIGUOUS_NEEDS_REVIEW".equals(cycle.status()))
                .toList();

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).marketKey()).isEqualTo("Fluid:PLASMA:VAULT-B4F3BF2D");
        assertThat(orphans.get(0).startTxHash()).isEqualTo("0xduplicate");
    }

    @Test
    void eulerTransactionsUsePerVaultMarketKey() {
        UserSession session = session();
        String vault = "0x1234567890abcdef1234567890abcdef12345678";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withCounterparty(
                        lendingEvent("0xeuler-deposit", NetworkId.AVALANCHE, NormalizedTransactionType.LENDING_DEPOSIT, "Euler",
                                "USDC", "0xusdc", "-100", Instant.parse("2026-01-01T00:00:00Z")),
                        vault
                )
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Euler".equals(candidate.protocol()) && "AVALANCHE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        assertThat(group.history()).singleElement()
                .extracting(SessionLendingQueryService.LendingHistoryEntryView::marketKey)
                .isEqualTo("Euler:AVALANCHE:EVK-VAULT-12345678");
    }

    @Test
    void eulerDepositWithdrawShareTokenResolvesSameMarketKey() {
        UserSession session = session();
        Instant depositAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant withdrawAt = Instant.parse("2026-01-02T00:00:00Z");
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withFlows(lendingEvent("0xdep", NetworkId.BASE, NormalizedTransactionType.LENDING_DEPOSIT, "Euler",
                                "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "-100", depositAt),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", new BigDecimal("-100")),
                                flow(NormalizedLegRole.TRANSFER, "eUSDC", EULER_EUSDC2_VAULT, new BigDecimal("100"))
                        )),
                withFlows(lendingEvent("0xwd", NetworkId.BASE, NormalizedTransactionType.LENDING_WITHDRAW, "Euler",
                                "eUSDC", EULER_EUSDC2_VAULT, "100", withdrawAt),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "eUSDC", EULER_EUSDC2_VAULT, new BigDecimal("-100")),
                                flow(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", new BigDecimal("100"))
                        ))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingGroupView group = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Euler".equals(candidate.protocol()) && "BASE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow();

        assertThat(group.history())
                .extracting(SessionLendingQueryService.LendingHistoryEntryView::marketKey)
                .containsOnly("Euler:BASE:EVK-VAULT-39DE0F00");
        assertThat(group.cycles()).singleElement().satisfies(cycle -> {
            assertThat(cycle.status()).isEqualTo("CLOSED");
            assertThat(cycle.warningReason()).isNull();
        });
    }

    @Test
    void multiVaultSameUnderlyingProducesDistinctCycles() {
        UserSession session = session();
        String vaultA = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String vaultB = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withFlows(lendingEvent("0xdep-a", NetworkId.BASE, NormalizedTransactionType.LENDING_DEPOSIT, "Euler",
                                "USDC", usdc, "-50", Instant.parse("2026-01-01T00:00:00Z")),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "USDC", usdc, new BigDecimal("-50")),
                                flow(NormalizedLegRole.TRANSFER, "eUSDC-A", vaultA, new BigDecimal("50"))
                        )),
                withFlows(lendingEvent("0xdep-b", NetworkId.BASE, NormalizedTransactionType.LENDING_DEPOSIT, "Euler",
                                "USDC", usdc, "-75", Instant.parse("2026-01-02T00:00:00Z")),
                        List.of(
                                flow(NormalizedLegRole.TRANSFER, "USDC", usdc, new BigDecimal("-75")),
                                flow(NormalizedLegRole.TRANSFER, "eUSDC-B", vaultB, new BigDecimal("75"))
                        ))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        List<SessionLendingQueryService.LendingCycleView> cycles = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Euler".equals(candidate.protocol()) && "BASE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles();

        assertThat(cycles).hasSize(2);
        assertThat(cycles)
                .extracting(SessionLendingQueryService.LendingCycleView::marketKey)
                .containsExactlyInAnyOrder("Euler:BASE:EVK-VAULT-AAAAAAAA", "Euler:BASE:EVK-VAULT-BBBBBBBB");
    }

    @Test
    void aaveAndCompoundMarketKeysStayCollapsed() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xaave-eth", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "ETH", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "-1", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0xaave-usdc", NetworkId.ARBITRUM, NormalizedTransactionType.BORROW, "Aave",
                        "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "100", Instant.parse("2026-01-02T00:00:00Z")),
                lendingEvent("0xcompound", NetworkId.BASE, NormalizedTransactionType.LENDING_DEPOSIT, "Compound",
                        "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "-25", Instant.parse("2026-01-03T00:00:00Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.SessionLendingView lending = service.findSessionLending(SESSION_ID).orElseThrow();

        assertThat(lending.groups().stream().filter(g -> "Aave".equals(g.protocol())).findFirst().orElseThrow().history())
                .extracting(SessionLendingQueryService.LendingHistoryEntryView::marketKey)
                .containsOnly("Aave:ARBITRUM:ACCOUNT-POOL");
        assertThat(lending.groups().stream().filter(g -> "Compound".equals(g.protocol())).findFirst().orElseThrow().history())
                .extracting(SessionLendingQueryService.LendingHistoryEntryView::marketKey)
                .containsOnly("Compound:BASE:COMET-BASE-MARKET");
    }

    @Test
    void compoundClosedCycleWithoutBuyFlowsKeepsSupplyIncomeUnavailable() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        NormalizedTransaction compoundWithdraw = baseCompoundTransaction(
                "0xcompound-withdraw",
                NormalizedTransactionType.LENDING_WITHDRAW,
                Instant.parse("2026-01-02T00:00:00Z")
        );
        compoundWithdraw.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", new BigDecimal("100"))
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xcompound-deposit", NetworkId.BASE, NormalizedTransactionType.LENDING_DEPOSIT, "Compound",
                        "USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", "-100", Instant.parse("2026-01-01T00:00:00Z")),
                compoundWithdraw
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Compound".equals(candidate.protocol()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "CLOSED".equals(candidate.status()))
                .findFirst()
                .orElseThrow();

        assertThat(cycle.pnlAssetBreakdown().supplyIncomeByAsset()).doesNotContainKey("USDC");
        assertThat(cycle.pnlAssetBreakdown().reasonByAsset()).containsEntry("USDC", LendingFactualApyCalculator.NO_YIELD_FLOW_EVIDENCE);
        assertThat(cycle.factualApy().apyUnavailableReason()).isEqualTo(LendingFactualApyCalculator.NO_YIELD_FLOW_EVIDENCE);
    }

    @Test
    void closedCycleEmitsPerAssetUsdPnlThatCrossFootsToTotal() {
        UserSession session = session();
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                lendingEvent("0xdeposit", NetworkId.AVALANCHE, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                        "GHO", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", "-100", Instant.parse("2026-01-01T00:00:00Z")),
                lendingEvent("0xborrow", NetworkId.AVALANCHE, NormalizedTransactionType.BORROW, "Aave",
                        "GHO", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73", "100", Instant.parse("2026-01-01T00:01:00Z")),
                lendingEvent("0xrepay", NetworkId.AVALANCHE, NormalizedTransactionType.REPAY, "Aave",
                        "variableDebtAvaGHO", "0x38d693ce1df5aadf7bc62595a37d667ad57922e5", "-100.5", Instant.parse("2026-01-02T00:00:00Z")),
                withFee(
                        aaveWithdrawWithYield("0xwithdraw", "100", "0.1", Instant.parse("2026-01-02T00:01:00Z")),
                        "ETH",
                        "0.01",
                        "20"
                )
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "AVALANCHE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "0xdeposit".equals(candidate.startTxHash()))
                .findFirst()
                .orElseThrow();

        SessionLendingQueryService.LendingPnlAssetBreakdownView breakdown = cycle.pnlAssetBreakdown();
        assertThat(cycle.totalValuation().totalUsdPnl()).isEqualByComparingTo("-20.5");
        // Authoritative cross-foot: Sigma per-asset USD == published total.
        assertCrossFoot(breakdown.netIncomeUsdByAsset(), cycle.totalValuation().totalUsdPnl());
        // Borrow leg is the net interest cost in that asset (negative), not lost principal.
        assertThat(breakdown.borrowPnlUsdByAsset().get("GHO")).isEqualByComparingTo("-0.5");
        // Gas USD is attributed to the native fee asset (not the "USD" sentinel key).
        assertThat(breakdown.gasUsdByAsset().get("ETH")).isEqualByComparingTo("20");
        assertThat(breakdown.netIncomeUsdByAsset().get("ETH")).isEqualByComparingTo("-20");
        assertThat(breakdown.netIncomeUsdByAsset().get("GHO")).isEqualByComparingTo("-0.5");
        // Quantity map remains untouched (USD is purely additive).
        assertThat(breakdown.netIncomeByAsset()).containsEntry("GHO", new BigDecimal("-0.4"));
        assertThat(breakdown.netIncomeByAsset()).containsEntry("ETH", new BigDecimal("-0.01"));
        // Fully-priced closed cycle => USD precision inherits EXACT.
        assertThat(breakdown.usdPrecisionByAsset()).containsEntry("GHO", "EXACT");
        assertThat(breakdown.usdPrecisionByAsset()).containsEntry("ETH", "EXACT");
    }

    @Test
    void closedInferredSupplyYieldIsEstimatedForQuantityWhileUsdCashflowStaysExact() {
        UserSession session = session();
        String usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                withFlows(lendingEvent("0xdep", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_DEPOSIT, "Aave",
                                "USDC", usdc, "-100", Instant.parse("2026-01-01T00:00:00Z")),
                        List.of(flow(NormalizedLegRole.TRANSFER, "USDC", usdc, new BigDecimal("-100")))),
                withFlows(lendingEvent("0xwd", NetworkId.ARBITRUM, NormalizedTransactionType.LENDING_WITHDRAW, "Aave",
                                "USDC", usdc, "100.5", Instant.parse("2026-01-02T00:00:00Z")),
                        List.of(flow(NormalizedLegRole.TRANSFER, "USDC", usdc, new BigDecimal("100.5"))))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "ARBITRUM".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "CLOSED".equals(candidate.status()))
                .findFirst()
                .orElseThrow();

        SessionLendingQueryService.LendingPnlAssetBreakdownView breakdown = cycle.pnlAssetBreakdown();
        // C-2: yield resolved only via inference (no observed withdraw-yield BUY flow) => ESTIMATED.
        assertThat(breakdown.precisionByAsset()).containsEntry("USDC", "ESTIMATED");
        // The USD cashflow leg (cash out - cash in) is directly priced => EXACT and cross-foots.
        assertThat(cycle.totalValuation().totalUsdPnl()).isEqualByComparingTo("0.5");
        assertThat(breakdown.netIncomeUsdByAsset().get("USDC")).isEqualByComparingTo("0.5");
        assertCrossFoot(breakdown.netIncomeUsdByAsset(), cycle.totalValuation().totalUsdPnl());
        assertThat(breakdown.usdPrecisionByAsset()).containsEntry("USDC", "EXACT");
    }

    @Test
    void openCycleUsdPerAssetReconcilesToUnrealizedTotal() {
        UserSession session = session();
        String aAvaUsdc = "0x625e7708f30ca75bfd92586e17077590c60eb4cd";
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "universe-1",
                List.of(WALLET),
                List.of(WALLET)
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                aaveDeposit("0xa8", "100", "100", Instant.parse("2026-04-26T19:12:09Z"))
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(
                aaveLedgerPoint(NetworkId.AVALANCHE, "aAvaUSDC", aAvaUsdc, "101")
        ));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(
                aaveBalance(NetworkId.AVALANCHE, "aAvaUSDC", aAvaUsdc, "101")
        ));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());

        SessionLendingQueryService service = newService();

        SessionLendingQueryService.LendingCycleView cycle = service.findSessionLending(SESSION_ID)
                .orElseThrow()
                .groups()
                .stream()
                .filter(candidate -> "Aave".equals(candidate.protocol()) && "AVALANCHE".equals(candidate.networkId()))
                .findFirst()
                .orElseThrow()
                .cycles()
                .stream()
                .filter(candidate -> "OPEN".equals(candidate.status()))
                .findFirst()
                .orElseThrow();

        SessionLendingQueryService.LendingPnlAssetBreakdownView breakdown = cycle.pnlAssetBreakdown();
        assertThat(cycle.totalValuation().unrealizedTotalUsdPnl()).isEqualByComparingTo("1");
        // OPEN cross-foot: current position USD value participates so Sigma == unrealized total.
        assertCrossFoot(breakdown.netIncomeUsdByAsset(), cycle.totalValuation().unrealizedTotalUsdPnl());
        assertThat(breakdown.netIncomeUsdByAsset().get("USDC")).isEqualByComparingTo("1");
        assertThat(breakdown.usdPrecisionByAsset()).containsEntry("USDC", "ESTIMATED");
    }

    private static void assertCrossFoot(
            java.util.Map<String, BigDecimal> netIncomeUsdByAsset,
            BigDecimal publishedTotal
    ) {
        BigDecimal sum = netIncomeUsdByAsset.values().stream()
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal absolute = new BigDecimal("0.01");
        BigDecimal relative = publishedTotal.abs().multiply(new BigDecimal("0.001"));
        BigDecimal tolerance = absolute.max(relative);
        assertThat(sum.subtract(publishedTotal).abs())
                .as("per-asset USD must cross-foot to the published cycle total")
                .isLessThanOrEqualTo(tolerance);
    }
}
