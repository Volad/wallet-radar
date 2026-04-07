package com.walletradar.ingestion.wallet.query;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionDashboardQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    private SessionDashboardQueryService sessionDashboardQueryService;

    @BeforeEach
    void setUp() {
        sessionDashboardQueryService = new SessionDashboardQueryService(
                userSessionRepository,
                mongoOperations,
                accountingUniverseService
        );
    }

    @Test
    void buildsCurrentTokenRowsFromOnChainAndLatestLedgerState() {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setAccountingUniverseId("ACCOUNTING_UNIVERSE:session-1");
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
                "ACCOUNTING_UNIVERSE:session-1",
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
        session.setAccountingUniverseId("ACCOUNTING_UNIVERSE:session-btc");
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
                "ACCOUNTING_UNIVERSE:session-btc",
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
        session.setAccountingUniverseId("ACCOUNTING_UNIVERSE:session-issues");
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
                "ACCOUNTING_UNIVERSE:session-issues",
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
}
