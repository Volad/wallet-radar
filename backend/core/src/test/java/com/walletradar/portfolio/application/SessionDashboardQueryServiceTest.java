package com.walletradar.portfolio.application;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionDashboardQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private CexLiveBalancePort cexLiveBalancePort;
    @Mock
    private PortfolioConservationGate portfolioConservationGate;

    private SessionDashboardQueryService sessionDashboardQueryService;

    @BeforeEach
    void setUp() {
        sessionDashboardQueryService = new SessionDashboardQueryService(
                userSessionRepository,
                mongoOperations,
                accountingUniverseService,
                cexLiveBalancePort,
                portfolioConservationGate
        );
        lenient().when(portfolioConservationGate.evaluate(any())).thenReturn(
                new PortfolioConservationGate.ConservationResult(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("50"),
                        false
                )
        );
        lenient().when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of());
        lenient().when(cexLiveBalancePort.getSnapshotView(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void buildsCurrentTokenRowsFromOnChainAndLatestLedgerState() {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buyPoint = new AssetLedgerPoint();
        buyPoint.setWalletAddress(wallet.getAddress());
        buyPoint.setNetworkId(NetworkId.BASE);
        buyPoint.setAccountingAssetIdentity("NATIVE:BASE");
        buyPoint.setAccountingFamilyIdentity("FAMILY:ETH");
        buyPoint.setFamilyDisplaySymbol("ETH");
        buyPoint.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        buyPoint.setAvcoAfterUsd(new BigDecimal("2000"));
        buyPoint.setBasisBackedQuantityAfter(new BigDecimal("1.5"));
        buyPoint.setHasIncompleteHistoryAfter(false);
        buyPoint.setHasUnresolvedFlagsAfter(false);
        buyPoint.setReplaySequence(1L);

        AssetLedgerPoint realisedPoint = new AssetLedgerPoint();
        realisedPoint.setWalletAddress(wallet.getAddress());
        realisedPoint.setNetworkId(NetworkId.BASE);
        realisedPoint.setAccountingAssetIdentity("NATIVE:BASE");
        realisedPoint.setAccountingFamilyIdentity("FAMILY:ETH");
        realisedPoint.setFamilyDisplaySymbol("ETH");
        realisedPoint.setRealisedPnlDeltaUsd(new BigDecimal("125.50"));
        realisedPoint.setAvcoAfterUsd(new BigDecimal("2000"));
        realisedPoint.setBasisBackedQuantityAfter(new BigDecimal("1.5"));
        realisedPoint.setHasIncompleteHistoryAfter(false);
        realisedPoint.setHasUnresolvedFlagsAfter(false);
        realisedPoint.setReplaySequence(2L);

        OnChainBalance onChainBalance = new OnChainBalance();
        onChainBalance.setWalletAddress(wallet.getAddress());
        onChainBalance.setNetworkId(NetworkId.BASE);
        onChainBalance.setAssetSymbol("ETH");
        onChainBalance.setAssetContract(null);
        onChainBalance.setQuantity(new BigDecimal("2.0"));
        onChainBalance.setCapturedAt(Instant.parse("2026-04-05T10:00:00Z"));

        HistoricalPriceDocument price = new HistoricalPriceDocument();
        price.setSymbol("ETH");
        price.setPriceUsd(new BigDecimal("3000"));
        price.setBucketStart(Instant.parse("2026-04-05T10:00:00Z"));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-1",
                List.of(wallet.getAddress(), "BYBIT:33625378"),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(buyPoint, realisedPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(onChainBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(price));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-1")
                .orElseThrow();

        assertThat(result.wallets()).hasSize(1);
        assertThat(result.tokenPositions()).hasSize(1);
        SessionDashboardQueryService.TokenPositionView token = result.tokenPositions().getFirst();
        assertThat(token.familyIdentity()).isEqualTo("FAMILY:ETH");
        assertThat(token.symbol()).isEqualTo("ETH");
        assertThat(token.quantity()).isEqualByComparingTo("2.0");
        assertThat(token.priceUsd()).isEqualByComparingTo("3000");
        assertThat(token.avcoUsd()).isEqualByComparingTo("2000");
        assertThat(token.unrealizedPnlUsd()).isEqualByComparingTo("1500.0");
        assertThat(token.realizedPnlUsd()).isEqualByComparingTo("125.50");
        assertThat(token.issue()).isEqualTo("coverage_gap");

        assertThat(result.summary().portfolioValueUsd()).isEqualByComparingTo("6000.0");
        assertThat(result.summary().totalRealizedPnlUsd()).isEqualByComparingTo("125.50");
    }

    @Test
    void groupsWbtcAndAaveWbtcIntoSingleBtcFamilyRow() {
        UserSession session = new UserSession();
        session.setId("session-btc");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint wbtcPoint = new AssetLedgerPoint();
        wbtcPoint.setWalletAddress(wallet.getAddress());
        wbtcPoint.setNetworkId(NetworkId.ARBITRUM);
        wbtcPoint.setAccountingAssetIdentity("0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f");
        wbtcPoint.setAccountingFamilyIdentity("FAMILY:BTC");
        wbtcPoint.setFamilyDisplaySymbol("BTC");
        wbtcPoint.setAssetSymbol("WBTC");
        wbtcPoint.setAvcoAfterUsd(new BigDecimal("90000"));
        wbtcPoint.setBasisBackedQuantityAfter(new BigDecimal("0.00030593"));
        wbtcPoint.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        wbtcPoint.setHasIncompleteHistoryAfter(false);
        wbtcPoint.setHasUnresolvedFlagsAfter(false);
        wbtcPoint.setReplaySequence(1L);

        AssetLedgerPoint aaveWbtcPoint = new AssetLedgerPoint();
        aaveWbtcPoint.setWalletAddress(wallet.getAddress());
        aaveWbtcPoint.setNetworkId(NetworkId.ARBITRUM);
        aaveWbtcPoint.setAccountingAssetIdentity("0x078f358208685046a11c85e8ad32895ded33a249");
        aaveWbtcPoint.setAccountingFamilyIdentity("FAMILY:BTC");
        aaveWbtcPoint.setFamilyDisplaySymbol("BTC");
        aaveWbtcPoint.setAssetSymbol("AARBWBTC");
        aaveWbtcPoint.setAvcoAfterUsd(new BigDecimal("91000"));
        aaveWbtcPoint.setBasisBackedQuantityAfter(new BigDecimal("0.00426808"));
        aaveWbtcPoint.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        aaveWbtcPoint.setHasIncompleteHistoryAfter(false);
        aaveWbtcPoint.setHasUnresolvedFlagsAfter(false);
        aaveWbtcPoint.setReplaySequence(2L);

        OnChainBalance wbtcBalance = new OnChainBalance();
        wbtcBalance.setWalletAddress(wallet.getAddress());
        wbtcBalance.setNetworkId(NetworkId.ARBITRUM);
        wbtcBalance.setAssetSymbol("WBTC");
        wbtcBalance.setAssetContract("0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f");
        wbtcBalance.setQuantity(new BigDecimal("0.00030593"));
        wbtcBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        OnChainBalance aaveWbtcBalance = new OnChainBalance();
        aaveWbtcBalance.setWalletAddress(wallet.getAddress());
        aaveWbtcBalance.setNetworkId(NetworkId.ARBITRUM);
        aaveWbtcBalance.setAssetSymbol("AARBWBTC");
        aaveWbtcBalance.setAssetContract("0x078f358208685046a11c85e8ad32895ded33a249");
        aaveWbtcBalance.setQuantity(new BigDecimal("0.00426808"));
        aaveWbtcBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument price = new HistoricalPriceDocument();
        price.setSymbol("WBTC");
        price.setPriceUsd(new BigDecimal("90000"));
        price.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-btc")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-btc",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(wbtcPoint, aaveWbtcPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(wbtcBalance, aaveWbtcBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(price));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-btc")
                .orElseThrow();

        assertThat(result.tokenPositions()).hasSize(1);
        SessionDashboardQueryService.TokenPositionView token = result.tokenPositions().getFirst();
        assertThat(token.familyIdentity()).isEqualTo("FAMILY:BTC");
        assertThat(token.symbol()).isEqualTo("BTC");
        assertThat(token.name()).isEqualTo("Bitcoin");
        assertThat(token.quantity()).isEqualByComparingTo("0.00457401");
        assertThat(token.coveredQuantity()).isEqualByComparingTo("0.00457401");
        assertThat(token.priceUsd()).isEqualByComparingTo("90000");
    }

    @Test
    void classifiesDashboardIssuesByCurrentCoverageAndHistoryState() {
        UserSession session = new UserSession();
        session.setId("session-issues");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint yieldPoint = new AssetLedgerPoint();
        yieldPoint.setWalletAddress(wallet.getAddress());
        yieldPoint.setNetworkId(NetworkId.MANTLE);
        yieldPoint.setAccountingAssetIdentity("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c");
        yieldPoint.setAccountingFamilyIdentity("FAMILY:ETH");
        yieldPoint.setFamilyDisplaySymbol("ETH");
        yieldPoint.setAssetSymbol("AMANWETH");
        yieldPoint.setBasisBackedQuantityAfter(new BigDecimal("3.06"));
        yieldPoint.setAvcoAfterUsd(new BigDecimal("2350"));
        yieldPoint.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        yieldPoint.setLifecycleKind(AssetLedgerPoint.LifecycleKind.LENDING);
        yieldPoint.setReplaySequence(10L);
        yieldPoint.setHasIncompleteHistoryAfter(false);
        yieldPoint.setHasUnresolvedFlagsAfter(false);

        AssetLedgerPoint coverageGapPoint = new AssetLedgerPoint();
        coverageGapPoint.setWalletAddress(wallet.getAddress());
        coverageGapPoint.setNetworkId(NetworkId.MANTLE);
        coverageGapPoint.setAccountingAssetIdentity("NATIVE:MANTLE");
        coverageGapPoint.setAccountingFamilyIdentity("FAMILY:MNT");
        coverageGapPoint.setFamilyDisplaySymbol("MNT");
        coverageGapPoint.setAssetSymbol("MNT");
        coverageGapPoint.setBasisBackedQuantityAfter(new BigDecimal("1"));
        coverageGapPoint.setAvcoAfterUsd(new BigDecimal("1"));
        coverageGapPoint.setBasisEffect(AssetLedgerPoint.BasisEffect.ACQUIRE);
        coverageGapPoint.setLifecycleKind(AssetLedgerPoint.LifecycleKind.SPOT);
        coverageGapPoint.setReplaySequence(11L);
        coverageGapPoint.setHasIncompleteHistoryAfter(false);
        coverageGapPoint.setHasUnresolvedFlagsAfter(false);

        AssetLedgerPoint historyFlagsPoint = new AssetLedgerPoint();
        historyFlagsPoint.setWalletAddress(wallet.getAddress());
        historyFlagsPoint.setNetworkId(NetworkId.MANTLE);
        historyFlagsPoint.setAccountingAssetIdentity("0x09bc4e0d864854c6afb6eb9a9cdf58ac190d0df9");
        historyFlagsPoint.setAccountingFamilyIdentity("FAMILY:USDC");
        historyFlagsPoint.setFamilyDisplaySymbol("USDC");
        historyFlagsPoint.setAssetSymbol("USDC");
        historyFlagsPoint.setBasisBackedQuantityAfter(new BigDecimal("20"));
        historyFlagsPoint.setAvcoAfterUsd(BigDecimal.ONE);
        historyFlagsPoint.setReplaySequence(12L);
        historyFlagsPoint.setHasIncompleteHistoryAfter(true);
        historyFlagsPoint.setHasUnresolvedFlagsAfter(true);

        OnChainBalance yieldBalance = new OnChainBalance();
        yieldBalance.setWalletAddress(wallet.getAddress());
        yieldBalance.setNetworkId(NetworkId.MANTLE);
        yieldBalance.setAssetSymbol("AMANWETH");
        yieldBalance.setAssetContract("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c");
        yieldBalance.setQuantity(new BigDecimal("3.065"));
        yieldBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        OnChainBalance coverageGapBalance = new OnChainBalance();
        coverageGapBalance.setWalletAddress(wallet.getAddress());
        coverageGapBalance.setNetworkId(NetworkId.MANTLE);
        coverageGapBalance.setAssetSymbol("MNT");
        coverageGapBalance.setAssetContract("NATIVE:MANTLE");
        coverageGapBalance.setQuantity(new BigDecimal("5"));
        coverageGapBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        OnChainBalance historyFlagsBalance = new OnChainBalance();
        historyFlagsBalance.setWalletAddress(wallet.getAddress());
        historyFlagsBalance.setNetworkId(NetworkId.MANTLE);
        historyFlagsBalance.setAssetSymbol("USDC");
        historyFlagsBalance.setAssetContract("0x09bc4e0d864854c6afb6eb9a9cdf58ac190d0df9");
        historyFlagsBalance.setQuantity(new BigDecimal("20"));
        historyFlagsBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        OnChainBalance missingReplayPointBalance = new OnChainBalance();
        missingReplayPointBalance.setWalletAddress(wallet.getAddress());
        missingReplayPointBalance.setNetworkId(NetworkId.MANTLE);
        missingReplayPointBalance.setAssetSymbol("PENDLE");
        missingReplayPointBalance.setAssetContract("0xd27b18915e7acc8fd6ac75db6766a80f8d2f5729");
        missingReplayPointBalance.setQuantity(new BigDecimal("1"));
        missingReplayPointBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument ethPrice = new HistoricalPriceDocument();
        ethPrice.setSymbol("ETH");
        ethPrice.setPriceUsd(new BigDecimal("2000"));
        ethPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));
        HistoricalPriceDocument mntPrice = new HistoricalPriceDocument();
        mntPrice.setSymbol("MNT");
        mntPrice.setPriceUsd(BigDecimal.ONE);
        mntPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));
        HistoricalPriceDocument usdcPrice = new HistoricalPriceDocument();
        usdcPrice.setSymbol("USDC");
        usdcPrice.setPriceUsd(BigDecimal.ONE);
        usdcPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-issues")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-issues",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class)))
                .thenReturn(List.of(yieldPoint, coverageGapPoint, historyFlagsPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class)))
                .thenReturn(List.of(yieldBalance, coverageGapBalance, historyFlagsBalance, missingReplayPointBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class)))
                .thenReturn(List.of(ethPrice, mntPrice, usdcPrice));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-issues")
                .orElseThrow();

        assertThat(result.tokenPositions())
                .extracting(SessionDashboardQueryService.TokenPositionView::issue)
                .containsExactlyInAnyOrder("yield_accrual", "coverage_gap", "history_flags", "missing_replay_point");
    }

    @Test
    void excludesAaveDebtReceiptFromPositionsAndValuesHeldAaveReceipt() {
        UserSession session = new UserSession();
        session.setId("session-aave");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        OnChainBalance receiptBalance = new OnChainBalance();
        receiptBalance.setWalletAddress(wallet.getAddress());
        receiptBalance.setNetworkId(NetworkId.MANTLE);
        receiptBalance.setAssetSymbol("AMANUSDC");
        receiptBalance.setAssetContract("0xreceipt");
        receiptBalance.setQuantity(new BigDecimal("10"));
        receiptBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        OnChainBalance debtBalance = new OnChainBalance();
        debtBalance.setWalletAddress(wallet.getAddress());
        debtBalance.setNetworkId(NetworkId.MANTLE);
        debtBalance.setAssetSymbol("VARIABLEDEBTMANUSDE");
        debtBalance.setAssetContract("0xdebt");
        debtBalance.setQuantity(new BigDecimal("4"));
        debtBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-aave")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-aave",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(receiptBalance, debtBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-aave")
                .orElseThrow();

        // F-4: the held aToken receipt is valued, but the variableDebt* liability marker is
        // excluded from positions entirely (tracked via borrow_liabilities, never as a held asset).
        assertThat(result.tokenPositions()).hasSize(1);
        assertThat(result.tokenPositions())
                .filteredOn(position -> "USDC".equals(position.symbol())
                        && "AAVE_INDEX_ACCRUING".equals(position.valuationModel()))
                .singleElement()
                .satisfies(position -> {
                    assertThat(position.priceUsd()).isEqualByComparingTo("1");
                    assertThat(position.marketValueUsd()).isEqualByComparingTo("10");
                    assertThat(position.valuationModel()).isEqualTo("AAVE_INDEX_ACCRUING");
                    assertThat(position.valuationUnderlyingSymbol()).isEqualTo("USDC");
                });
        assertThat(result.tokenPositions())
                .noneMatch(position -> position.symbol().startsWith("VARIABLEDEBT"));
        // No fabricated unrealized PnL from the debt token, and it no longer drags portfolioValue.
        assertThat(result.summary().portfolioValueUsd()).isEqualByComparingTo("10");
    }

    @Test
    void valuesGmxMarketTokenFromProtocolSnapshotQuote() {
        UserSession session = new UserSession();
        session.setId("session-gmx");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));

        OnChainBalance balance = new OnChainBalance();
        balance.setWalletAddress(wallet.getAddress());
        balance.setNetworkId(NetworkId.ARBITRUM);
        balance.setAssetSymbol("GM: ETH/USD [WETH-USDC]");
        balance.setAssetContract("0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        balance.setQuantity(new BigDecimal("10"));
        balance.setCapturedAt(Instant.parse("2026-04-26T10:00:00Z"));

        CurrentPriceQuoteDocument quote = new CurrentPriceQuoteDocument();
        quote.setId("GM: ETH/USD [WETH-USDC]:PROTOCOL_SNAPSHOT");
        quote.setSymbol("GM: ETH/USD [WETH-USDC]");
        quote.setSource(PriceSource.PROTOCOL_SNAPSHOT);
        quote.setPriceUsd(new BigDecimal("1.82"));
        quote.setQuoteSymbol("USD");
        quote.setPricedAt(Instant.now());
        quote.setFetchedAt(Instant.now());

        when(userSessionRepository.findById("session-gmx")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-gmx",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(balance));
        when(mongoOperations.find(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(List.of(quote));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-gmx")
                .orElseThrow();

        assertThat(result.tokenPositions()).singleElement().satisfies(position -> {
            assertThat(position.priceUsd()).isEqualByComparingTo("1.82");
            assertThat(position.marketValueUsd()).isEqualByComparingTo("18.2");
            assertThat(position.priceSource()).isEqualTo("PROTOCOL_SNAPSHOT");
            assertThat(position.valuationModel()).isEqualTo("GMX_MARKET_TOKEN_SNAPSHOT");
            assertThat(position.unsupportedValuationReason()).isNull();
            assertThat(position.priceIssue()).isNull();
        });
        assertThat(result.summary().portfolioValueUsd()).isEqualByComparingTo("18.2");
    }

    @Test
    void inflatesBybitQuantityToLiveWhenLedgerIsBelowLive() {
        UserSession session = new UserSession();
        session.setId("session-bybit-inflate");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setDisplayName("Bybit");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        session.setIntegrations(List.of(integration));

        AssetLedgerPoint dogePoint = new AssetLedgerPoint();
        dogePoint.setAccountingUniverseId("session-bybit-inflate");
        dogePoint.setWalletAddress("BYBIT:33625378:UTA");
        dogePoint.setNetworkId(null);
        dogePoint.setAccountingAssetIdentity("BYBIT:DOGE");
        dogePoint.setAccountingFamilyIdentity("SYMBOL:DOGE");
        dogePoint.setFamilyDisplaySymbol("DOGE");
        dogePoint.setAssetSymbol("DOGE");
        dogePoint.setAssetContract("BYBIT:DOGE");
        dogePoint.setQuantityAfter(new BigDecimal("249.82"));
        dogePoint.setQuantityShortfallAfter(BigDecimal.ZERO);
        dogePoint.setBasisBackedQuantityAfter(new BigDecimal("200"));
        dogePoint.setAvcoAfterUsd(new BigDecimal("0.18"));
        dogePoint.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        dogePoint.setHasIncompleteHistoryAfter(false);
        dogePoint.setHasUnresolvedFlagsAfter(false);
        dogePoint.setReplaySequence(1L);

        when(userSessionRepository.findById("session-bybit-inflate")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-bybit-inflate",
                List.of(wallet.getAddress(), "BYBIT:33625378:UTA", "BYBIT:33625378:FUND", "BYBIT:33625378:EARN"),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(dogePoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
        when(cexLiveBalancePort.getSnapshotView("BYBIT-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        java.util.Map.of("DOGE", new BigDecimal("661.17")),
                        java.time.Instant.parse("2025-06-01T00:00:00Z")
                )));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-bybit-inflate")
                .orElseThrow();

        assertThat(result.tokenPositions()).singleElement().satisfies(token -> {
            assertThat(token.quantity()).isEqualByComparingTo("661.17");
            assertThat(token.coveredQuantity()).isEqualByComparingTo("200");
            assertThat(token.avcoUsd()).isEqualByComparingTo("0.18");
            assertThat(token.issue()).isEqualTo("coverage_gap");
        });
    }

    @Test
    void createsLiveOnlyRowForBybitAssetMissingFromLedger() {
        UserSession session = new UserSession();
        session.setId("session-bybit-live-only");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setDisplayName("Bybit");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        session.setIntegrations(List.of(integration));

        when(userSessionRepository.findById("session-bybit-live-only")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-bybit-live-only",
                List.of(wallet.getAddress(), "BYBIT:33625378:UTA"),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
        when(cexLiveBalancePort.getSnapshotView("BYBIT-33625378"))
                .thenReturn(Optional.of(new CexLiveBalancePort.SnapshotView(
                        CexLiveBalancePort.Availability.KNOWN_NON_EMPTY,
                        java.util.Map.of("XRP", new BigDecimal("4.0533")),
                        Instant.now()
                )));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-bybit-live-only")
                .orElseThrow();

        assertThat(result.tokenPositions()).singleElement().satisfies(token -> {
            assertThat(token.symbol()).isEqualTo("XRP");
            assertThat(token.quantity()).isEqualByComparingTo("4.0533");
            assertThat(token.coveredQuantity()).isEqualByComparingTo("0");
            assertThat(token.avcoUsd()).isEqualByComparingTo("0");
            assertThat(token.issue()).isEqualTo("missing_replay_point");
        });
    }

    @Test
    void exposesBybitCustodyPositionsWithBybitNetworkLabelWhenLedgerNetworkIsNull() {
        UserSession session = new UserSession();
        session.setId("session-bybit-dash");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setDisplayName("Bybit");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        session.setIntegrations(List.of(integration));

        AssetLedgerPoint bybitPoint = new AssetLedgerPoint();
        bybitPoint.setAccountingUniverseId("session-bybit-dash");
        bybitPoint.setWalletAddress("BYBIT:33625378:UTA");
        bybitPoint.setNetworkId(null);
        bybitPoint.setAccountingAssetIdentity("BYBIT:USDT");
        bybitPoint.setAccountingFamilyIdentity("SYMBOL:USDT");
        bybitPoint.setFamilyDisplaySymbol("USDT");
        bybitPoint.setAssetSymbol("USDT");
        bybitPoint.setAssetContract("BYBIT:USDT");
        bybitPoint.setQuantityAfter(new BigDecimal("1000"));
        bybitPoint.setQuantityShortfallAfter(BigDecimal.ZERO);
        bybitPoint.setBasisBackedQuantityAfter(new BigDecimal("1000"));
        bybitPoint.setAvcoAfterUsd(BigDecimal.ONE);
        bybitPoint.setRealisedPnlDeltaUsd(new BigDecimal("12.5"));
        bybitPoint.setHasIncompleteHistoryAfter(false);
        bybitPoint.setHasUnresolvedFlagsAfter(false);
        bybitPoint.setReplaySequence(1L);

        HistoricalPriceDocument usdtPrice = new HistoricalPriceDocument();
        usdtPrice.setSymbol("USDT");
        usdtPrice.setPriceUsd(BigDecimal.ONE);
        usdtPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-bybit-dash")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-bybit-dash",
                List.of(wallet.getAddress(), "BYBIT:33625378:UTA", "BYBIT:33625378:FUND", "BYBIT:33625378:EARN"),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(bybitPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(usdtPrice));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-bybit-dash")
                .orElseThrow();

        assertThat(result.wallets().stream().map(SessionDashboardQueryService.WalletView::address))
                .contains("bybit:33625378");
        assertThat(result.tokenPositions()).singleElement().satisfies(token -> {
            assertThat(token.networkId()).isEqualTo("BYBIT");
            assertThat(token.walletAddress()).isEqualTo("bybit:33625378");
            assertThat(token.quantity()).isEqualByComparingTo("1000");
            assertThat(token.realizedPnlUsd()).isEqualByComparingTo("12.5");
        });
    }
}
