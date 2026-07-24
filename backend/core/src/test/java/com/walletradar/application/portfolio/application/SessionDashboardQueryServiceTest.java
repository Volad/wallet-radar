package com.walletradar.application.portfolio.application;

import com.walletradar.application.costbasis.breakeven.BreakEvenAttributionLoader;
import com.walletradar.application.costbasis.breakeven.BreakEvenAttributionService;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.application.pricing.latest.CurrentPriceReadService;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.Mockito.verify;
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
    private CurrentPriceReadService currentPriceReadService;
    @Mock
    private PortfolioConservationGate portfolioConservationGate;

    private SessionDashboardQueryService sessionDashboardQueryService;

    @BeforeAll
    static void bindNetworkNativeAssets() {
        // Building the test registry binds NetworkNativeAssets so native-alias asset identities
        // (e.g. NATIVE:BASE) collapse on-chain balance + ledger buckets. Without an explicit bind
        // this class depended on another test in the same JVM constructing a NetworkRegistry first.
        NetworkTestFixtures.registry();
    }

    @BeforeEach
    void setUp() {
        sessionDashboardQueryService = new SessionDashboardQueryService(
                userSessionRepository,
                mongoOperations,
                accountingUniverseService,
                cexLiveBalancePort,
                currentPriceReadService,
                portfolioConservationGate,
                new BreakEvenCalculator(new BreakEvenAttributionService(
                        new BreakEvenAttributionLoader(new com.fasterxml.jackson.databind.ObjectMapper())))
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
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
        lenient().when(currentPriceReadService.resolveLatest(any())).thenReturn(java.util.Map.of());
        lenient().when(cexLiveBalancePort.getSnapshotView(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void rendersSolanaAndTonPositionsWithCaseSensitiveAddressesAndScopesConservation() {
        // Case-sensitive base58 Solana address (mixed case) + friendly TON address. Blind
        // lowercasing on the read path previously dropped these; family-aware normalization keeps them.
        String solanaWallet = "So1anaWa11etAbcDefGhiJkLmNoPqRsTuVwXyz12";
        String tonWallet = "UQAbcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRST";

        UserSession session = new UserSession();
        session.setId("session-nonevm");
        UserSession.SessionWallet sol = new UserSession.SessionWallet();
        sol.setAddress(solanaWallet);
        sol.setLabel("Solana");
        sol.setNetworks(List.of(NetworkId.SOLANA));
        UserSession.SessionWallet ton = new UserSession.SessionWallet();
        ton.setAddress(tonWallet);
        ton.setLabel("TON");
        ton.setNetworks(List.of(NetworkId.TON));
        session.setWallets(List.of(sol, ton));

        AssetLedgerPoint solPoint = new AssetLedgerPoint();
        solPoint.setWalletAddress(solanaWallet);
        solPoint.setNetworkId(NetworkId.SOLANA);
        solPoint.setAccountingAssetIdentity("NATIVE:SOLANA");
        solPoint.setAccountingFamilyIdentity("FAMILY:SOL");
        solPoint.setFamilyDisplaySymbol("SOL");
        solPoint.setAssetSymbol("SOL");
        solPoint.setAvcoAfterUsd(new BigDecimal("80"));
        solPoint.setBasisBackedQuantityAfter(new BigDecimal("1"));
        solPoint.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        solPoint.setHasIncompleteHistoryAfter(false);
        solPoint.setHasUnresolvedFlagsAfter(false);
        solPoint.setReplaySequence(1L);

        AssetLedgerPoint tonPoint = new AssetLedgerPoint();
        tonPoint.setWalletAddress(tonWallet);
        tonPoint.setNetworkId(NetworkId.TON);
        tonPoint.setAccountingAssetIdentity("toncoin");
        tonPoint.setAccountingFamilyIdentity("FAMILY:TON");
        tonPoint.setFamilyDisplaySymbol("TON");
        tonPoint.setAssetSymbol("TON");
        tonPoint.setAvcoAfterUsd(new BigDecimal("4"));
        tonPoint.setBasisBackedQuantityAfter(new BigDecimal("1"));
        tonPoint.setRealisedPnlDeltaUsd(BigDecimal.ZERO);
        tonPoint.setHasIncompleteHistoryAfter(false);
        tonPoint.setHasUnresolvedFlagsAfter(false);
        tonPoint.setReplaySequence(1L);

        OnChainBalance solBalance = new OnChainBalance();
        solBalance.setWalletAddress(solanaWallet);
        solBalance.setNetworkId(NetworkId.SOLANA);
        solBalance.setAssetSymbol("SOL");
        solBalance.setAssetContract("NATIVE:SOLANA");
        solBalance.setQuantity(new BigDecimal("1"));
        solBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        OnChainBalance tonBalance = new OnChainBalance();
        tonBalance.setWalletAddress(tonWallet);
        tonBalance.setNetworkId(NetworkId.TON);
        tonBalance.setAssetSymbol("TON");
        tonBalance.setAssetContract("TONCOIN");
        tonBalance.setQuantity(new BigDecimal("1"));
        tonBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-nonevm")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-nonevm",
                List.of(solanaWallet, tonWallet),
                List.of(solanaWallet, tonWallet)
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(solPoint, tonPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(solBalance, tonBalance));
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
        lenient().when(currentPriceReadService.resolveLatest(any())).thenReturn(java.util.Map.of(
                "SOL", new com.walletradar.application.pricing.latest.ResolvedPrice(
                        new BigDecimal("100"), PriceSource.COINGECKO, Instant.now(), false),
                "TON", new com.walletradar.application.pricing.latest.ResolvedPrice(
                        new BigDecimal("50"), PriceSource.COINGECKO, Instant.now(), false)
        ));

        org.mockito.ArgumentCaptor<PortfolioConservationGate.ConservationInputs> captor =
                org.mockito.ArgumentCaptor.forClass(PortfolioConservationGate.ConservationInputs.class);

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-nonevm")
                .orElseThrow();

        // Both non-EVM positions render (base58/friendly case preserved end-to-end).
        assertThat(result.tokenPositions())
                .extracting(SessionDashboardQueryService.TokenPositionView::symbol)
                .containsExactlyInAnyOrder("SOL", "TON");
        assertThat(result.tokenPositions())
                .filteredOn(position -> "SOL".equals(position.symbol()))
                .singleElement()
                .satisfies(position -> {
                    assertThat(position.walletAddress()).isEqualTo(solanaWallet);
                    assertThat(position.marketValueUsd()).isEqualByComparingTo("100");
                });
        // Displayed portfolio value INCLUDES SOL + TON (100 + 50).
        assertThat(result.summary().portfolioValueUsd()).isEqualByComparingTo("150");

        // ADR-067: conservation MtM + PnL are scoped to exclude OOS SOL/TON so the identity stays
        // balanced (no NEC boundary support for these families yet).
        verify(portfolioConservationGate).evaluate(captor.capture());
        assertThat(captor.getValue().dashboardMarkToMarketUsd()).isEqualByComparingTo("0");
        assertThat(captor.getValue().totalUnrealisedPnlUsd()).isEqualByComparingTo("0");
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

    /**
     * ADR-078 A4 coverage guard (missing-vs-zero, MISSING case). A forced-live receipt bucket
     * (aaveWBTC) still carries covered basis in the ledger but has <b>no</b> {@code on_chain_balances}
     * row at all (a transient capture miss — all balance providers failed). The guard must weight the
     * headline covered-qty-weighted AVCO off the ledger-covered quantity (so the lot is not dropped and
     * the AVCO does not swing to the surviving cheap lot) and surface a {@code balance_capture_fallback}
     * coverage flag — never overstating holdings beyond the covered floor.
     */
    @Test
    void coverageGuardKeepsLedgerCoveredLotAndFlagsWhenBalanceRowIsMissing() {
        UserSession session = new UserSession();
        session.setId("session-guard-missing");
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

        // Forced-live lending receipt bucket that still carries covered basis in the ledger.
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

        // Only the spot WBTC bucket has a captured balance row. The aaveWBTC row is MISSING entirely.
        OnChainBalance wbtcBalance = new OnChainBalance();
        wbtcBalance.setWalletAddress(wallet.getAddress());
        wbtcBalance.setNetworkId(NetworkId.ARBITRUM);
        wbtcBalance.setAssetSymbol("WBTC");
        wbtcBalance.setAssetContract("0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f");
        wbtcBalance.setQuantity(new BigDecimal("0.00030593"));
        wbtcBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument price = new HistoricalPriceDocument();
        price.setSymbol("WBTC");
        price.setPriceUsd(new BigDecimal("90000"));
        price.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-guard-missing")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-guard-missing",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(wbtcPoint, aaveWbtcPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(wbtcBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(price));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-guard-missing")
                .orElseThrow();

        assertThat(result.tokenPositions()).hasSize(1);
        SessionDashboardQueryService.TokenPositionView token = result.tokenPositions().getFirst();
        assertThat(token.familyIdentity()).isEqualTo("FAMILY:BTC");
        // The missing aaveWBTC lot is NOT dropped: it is weighted off its ledger-covered quantity.
        assertThat(token.coveredQuantity()).isEqualByComparingTo("0.00457401");
        assertThat(token.quantity()).isEqualByComparingTo("0.00457401");
        // Covered-qty-weighted AVCO blends both lots (never collapses to the cheap surviving lot).
        assertThat(token.avcoUsd()).isEqualByComparingTo(
                new BigDecimal("90000").multiply(new BigDecimal("0.00030593"))
                        .add(new BigDecimal("91000").multiply(new BigDecimal("0.00426808")))
                        .divide(new BigDecimal("0.00457401"), java.math.MathContext.DECIMAL128));
        // ADR-078: coverage/health flag raised for the fallback-weighted bucket.
        assertThat(token.issue()).isEqualTo("balance_capture_fallback");
    }

    /**
     * ADR-078 A4 coverage guard (missing-vs-zero, AUTHORITATIVE ZERO case). Symmetric to the missing
     * case: when the same aaveWBTC receipt was genuinely redeemed on-chain, the refresh writes an
     * explicit zero balance row. That row is <em>present</em> (so the guard skips it) and dropped by the
     * {@code signum() <= 0} filter — the sold lot must fall out of holdings and AVCO weighting with no
     * ghost lot and no false coverage flag, even though the ledger point still carries residual basis.
     */
    @Test
    void authoritativeZeroBalanceDropsSoldLotWithoutGhostOrFalseFlag() {
        UserSession session = new UserSession();
        session.setId("session-guard-zero");
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

        // Authoritative on-chain ZERO: the aaveWBTC receipt was redeemed, so an explicit zero row exists.
        OnChainBalance aaveWbtcZero = new OnChainBalance();
        aaveWbtcZero.setWalletAddress(wallet.getAddress());
        aaveWbtcZero.setNetworkId(NetworkId.ARBITRUM);
        aaveWbtcZero.setAssetSymbol("AARBWBTC");
        aaveWbtcZero.setAssetContract("0x078f358208685046a11c85e8ad32895ded33a249");
        aaveWbtcZero.setQuantity(BigDecimal.ZERO);
        aaveWbtcZero.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument price = new HistoricalPriceDocument();
        price.setSymbol("WBTC");
        price.setPriceUsd(new BigDecimal("90000"));
        price.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-guard-zero")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-guard-zero",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(wbtcPoint, aaveWbtcPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(wbtcBalance, aaveWbtcZero));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(price));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-guard-zero")
                .orElseThrow();

        assertThat(result.tokenPositions()).hasSize(1);
        SessionDashboardQueryService.TokenPositionView token = result.tokenPositions().getFirst();
        assertThat(token.familyIdentity()).isEqualTo("FAMILY:BTC");
        // Sold lot drops out: only the surviving spot WBTC lot remains, no ghost aaveWBTC quantity.
        assertThat(token.quantity()).isEqualByComparingTo("0.00030593");
        assertThat(token.coveredQuantity()).isEqualByComparingTo("0.00030593");
        assertThat(token.avcoUsd()).isEqualByComparingTo("90000");
        // No false coverage flag: an authoritative zero is a real disposal, not a capture miss.
        assertThat(token.issue()).isNotEqualTo("balance_capture_fallback");
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
    void dashboardCreditsReceiptlessLockedLendingCollateralIntoFamilyCoverage() {
        // Part 1 (receipt-less lending continuity): a Jupiter Lend SOL deposit REALLOCATE_OUTs the
        // underlying basis with no in-family receipt token to re-cover it, while the live provider folds
        // the still-locked SOL back into the NATIVE:SOLANA balance. Without the read-time credit the
        // dashboard shows the locked SOL as uncovered with no effective cost; with it, FAMILY:SOL covers
        // spot + lending and exposes a realistic AVCO — symmetric with an EVM aToken staying in family.
        UserSession session = new UserSession();
        session.setId("session-sol-lend");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("So1anaWa11etAbcDefGhiJkLmNoPqRsTuVwXyz12");
        wallet.setNetworks(List.of(NetworkId.SOLANA));
        session.setWallets(List.of(wallet));

        // Latest NATIVE:SOLANA point is the deposit's REALLOCATE_OUT: 0.5 SOL spot remains covered,
        // and 5.0 SOL of basis (750 USD @ 150 AVCO) was parked out to receipt-less lending collateral.
        AssetLedgerPoint lendingOut = new AssetLedgerPoint();
        lendingOut.setWalletAddress(wallet.getAddress());
        lendingOut.setNetworkId(NetworkId.SOLANA);
        lendingOut.setAccountingAssetIdentity("NATIVE:SOLANA");
        lendingOut.setAccountingFamilyIdentity("FAMILY:SOL");
        lendingOut.setFamilyDisplaySymbol("SOL");
        lendingOut.setAssetSymbol("SOL");
        lendingOut.setProtocolName("Jupiter Lend");
        lendingOut.setLifecycleKind(AssetLedgerPoint.LifecycleKind.LENDING);
        lendingOut.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        lendingOut.setQuantityDelta(new BigDecimal("-5.0"));
        lendingOut.setCostBasisDeltaUsd(new BigDecimal("-750"));
        lendingOut.setNetCostBasisDeltaUsd(new BigDecimal("-750"));
        lendingOut.setQuantityAfter(new BigDecimal("0.5"));
        lendingOut.setBasisBackedQuantityAfter(new BigDecimal("0.5"));
        lendingOut.setAvcoAfterUsd(new BigDecimal("150"));
        lendingOut.setNetAvcoAfterUsd(new BigDecimal("150"));
        lendingOut.setReplaySequence(20L);
        lendingOut.setHasIncompleteHistoryAfter(false);
        lendingOut.setHasUnresolvedFlagsAfter(false);

        // Live balance = 0.5 liquid + 5.0 still-locked (folded in by the live provider) = 5.5 SOL.
        OnChainBalance solBalance = new OnChainBalance();
        solBalance.setWalletAddress(wallet.getAddress());
        solBalance.setNetworkId(NetworkId.SOLANA);
        solBalance.setAssetSymbol("SOL");
        solBalance.setAssetContract("NATIVE:SOLANA");
        solBalance.setQuantity(new BigDecimal("5.5"));
        solBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument solPrice = new HistoricalPriceDocument();
        solPrice.setSymbol("SOL");
        solPrice.setPriceUsd(new BigDecimal("200"));
        solPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-sol-lend")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-sol-lend",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class)))
                .thenReturn(List.of(lendingOut));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class)))
                .thenReturn(List.of(solBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class)))
                .thenReturn(List.of(solPrice));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-sol-lend")
                .orElseThrow();

        SessionDashboardQueryService.TokenPositionView sol = result.tokenPositions().stream()
                .filter(position -> "FAMILY:SOL".equals(position.familyIdentity()))
                .findFirst()
                .orElseThrow();

        // Spot(0.5) + lending(5.0) SOL is fully covered; AVCO stays the true 150 (825 basis / 5.5).
        assertThat(sol.quantity()).isEqualByComparingTo("5.5");
        assertThat(sol.coveredQuantity()).isEqualByComparingTo("5.5");
        assertThat(sol.avcoUsd()).isEqualByComparingTo("150");
        assertThat(sol.coveredRatio()).isEqualByComparingTo("1");
        // Fully covered → the stale coverage_gap issue is cleared and break-even is not suppressed.
        assertThat(sol.issue()).isNull();
        assertThat(sol.breakEvenSuppressed()).isFalse();
        assertThat(sol.breakEvenUsd()).isEqualByComparingTo("150");
    }

    @Test
    void dashboardRewardFamilyBreakEvenAndAverageCostUseNetLane() {
        // ADR-062 (2026-07-24): a FAMILY:SOL position of 4 held SOL carrying $200/SOL MARKET average
        // cost but only $50/SOL NET average cost (three quarters arrived as free held staking reward,
        // never sold ⇒ zero realized). Under offsetLane=NET both the header break-even AND the
        // companion average cost use the NET lane ($50), while the Market AVCO diagnostic stays $200.
        UserSession session = new UserSession();
        session.setId("session-sol-reward");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("So1anaWa11etAbcDefGhiJkLmNoPqRsTuVwXyz12");
        wallet.setNetworks(List.of(NetworkId.SOLANA));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint solPoint = new AssetLedgerPoint();
        solPoint.setWalletAddress(wallet.getAddress());
        solPoint.setNetworkId(NetworkId.SOLANA);
        solPoint.setAccountingAssetIdentity("NATIVE:SOLANA");
        solPoint.setAccountingFamilyIdentity("FAMILY:SOL");
        solPoint.setFamilyDisplaySymbol("SOL");
        solPoint.setAssetSymbol("SOL");
        solPoint.setLifecycleKind(AssetLedgerPoint.LifecycleKind.SPOT);
        solPoint.setBasisEffect(AssetLedgerPoint.BasisEffect.ACQUIRE);
        solPoint.setQuantityDelta(new BigDecimal("4"));
        solPoint.setCostBasisDeltaUsd(new BigDecimal("800"));
        solPoint.setNetCostBasisDeltaUsd(new BigDecimal("200"));
        solPoint.setQuantityAfter(new BigDecimal("4"));
        solPoint.setBasisBackedQuantityAfter(new BigDecimal("4"));
        solPoint.setAvcoAfterUsd(new BigDecimal("200"));
        solPoint.setNetAvcoAfterUsd(new BigDecimal("50"));
        solPoint.setReplaySequence(10L);
        solPoint.setHasIncompleteHistoryAfter(false);
        solPoint.setHasUnresolvedFlagsAfter(false);

        OnChainBalance solBalance = new OnChainBalance();
        solBalance.setWalletAddress(wallet.getAddress());
        solBalance.setNetworkId(NetworkId.SOLANA);
        solBalance.setAssetSymbol("SOL");
        solBalance.setAssetContract("NATIVE:SOLANA");
        solBalance.setQuantity(new BigDecimal("4"));
        solBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument solPrice = new HistoricalPriceDocument();
        solPrice.setSymbol("SOL");
        solPrice.setPriceUsd(new BigDecimal("300"));
        solPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-sol-reward")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-sol-reward",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class)))
                .thenReturn(List.of(solPoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class)))
                .thenReturn(List.of(solBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class)))
                .thenReturn(List.of(solPrice));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-sol-reward")
                .orElseThrow();

        SessionDashboardQueryService.TokenPositionView sol = result.tokenPositions().stream()
                .filter(position -> "FAMILY:SOL".equals(position.familyIdentity()))
                .findFirst()
                .orElseThrow();

        // Market AVCO diagnostic stays $200/SOL; break-even + average cost move to the NET lane ($50).
        assertThat(sol.avcoUsd()).isEqualByComparingTo("200");
        assertThat(sol.netAvcoUsd()).isEqualByComparingTo("50");
        assertThat(sol.breakEvenUsd()).isEqualByComparingTo("50");
        assertThat(sol.averageCostUsd()).isEqualByComparingTo("50");
        assertThat(sol.breakEvenSuppressed()).isFalse();
    }

    @Test
    void dashboardSuppressesZeroBreakEvenWhenCoverageIsBelowThreshold() {
        // Part 2 (ADR-062 deviation guard): a family whose realized profit already exceeds its tiny
        // remaining provable basis floors break-even to $0. When coverage is also low, that $0 is a
        // coverage artifact, not a real effective cost, so the read model flags it for annotation and
        // surfaces the covered fraction instead of rendering a misleading bare $0.
        UserSession session = new UserSession();
        session.setId("session-lowcov");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("So1anaWa11etAbcDefGhiJkLmNoPqRsTuVwXyz12");
        wallet.setNetworks(List.of(NetworkId.SOLANA));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint memePoint = new AssetLedgerPoint();
        memePoint.setWalletAddress(wallet.getAddress());
        memePoint.setNetworkId(NetworkId.SOLANA);
        memePoint.setAccountingAssetIdentity(com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport
                .positionAssetIdentity(NetworkId.SOLANA, "BONK", "BONKMINT"));
        memePoint.setAccountingFamilyIdentity("FAMILY:BONK");
        memePoint.setFamilyDisplaySymbol("BONK");
        memePoint.setAssetSymbol("BONK");
        memePoint.setLifecycleKind(AssetLedgerPoint.LifecycleKind.SPOT);
        memePoint.setBasisEffect(AssetLedgerPoint.BasisEffect.DISPOSE);
        memePoint.setQuantityAfter(new BigDecimal("1"));
        memePoint.setBasisBackedQuantityAfter(new BigDecimal("1"));
        memePoint.setAvcoAfterUsd(BigDecimal.ONE);
        memePoint.setNetAvcoAfterUsd(BigDecimal.ONE);
        memePoint.setRealisedPnlDeltaUsd(new BigDecimal("1000"));
        memePoint.setNetRealisedPnlDeltaUsd(new BigDecimal("1000"));
        memePoint.setReplaySequence(5L);
        memePoint.setHasIncompleteHistoryAfter(false);
        memePoint.setHasUnresolvedFlagsAfter(false);

        OnChainBalance memeBalance = new OnChainBalance();
        memeBalance.setWalletAddress(wallet.getAddress());
        memeBalance.setNetworkId(NetworkId.SOLANA);
        memeBalance.setAssetSymbol("BONK");
        memeBalance.setAssetContract("BONKMINT");
        memeBalance.setQuantity(new BigDecimal("100"));
        memeBalance.setCapturedAt(Instant.parse("2026-04-06T10:00:00Z"));

        HistoricalPriceDocument bonkPrice = new HistoricalPriceDocument();
        bonkPrice.setSymbol("BONK");
        bonkPrice.setPriceUsd(BigDecimal.ONE);
        bonkPrice.setBucketStart(Instant.parse("2026-04-06T10:00:00Z"));

        when(userSessionRepository.findById("session-lowcov")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-lowcov",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class)))
                .thenReturn(List.of(memePoint));
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class)))
                .thenReturn(List.of(memeBalance));
        when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class)))
                .thenReturn(List.of(bonkPrice));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-lowcov")
                .orElseThrow();

        SessionDashboardQueryService.TokenPositionView bonk = result.tokenPositions().stream()
                .filter(position -> "FAMILY:BONK".equals(position.familyIdentity()))
                .findFirst()
                .orElseThrow();

        assertThat(bonk.breakEvenUsd()).isEqualByComparingTo("0");
        assertThat(bonk.coveredRatio()).isEqualByComparingTo("0.01");
        assertThat(bonk.breakEvenSuppressed()).isTrue();
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
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());

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

        when(userSessionRepository.findById("session-gmx")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-gmx",
                List.of(wallet.getAddress()),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(OnChainBalance.class))).thenReturn(List.of(balance));
        lenient().when(currentPriceReadService.resolveLatest(any())).thenReturn(java.util.Map.of(
                "GM: ETH/USD [WETH-USDC]", new com.walletradar.application.pricing.latest.ResolvedPrice(
                        new BigDecimal("1.82"), PriceSource.PROTOCOL_SNAPSHOT, Instant.now(), false)
        ));
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());

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
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
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
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
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
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of(usdtPrice));

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

    @Test
    void buildsDzengiUmbrellaRowsFromLedgerWithVenueNativePricing() {
        UserSession session = new UserSession();
        session.setId("session-dzengi-dash");
        session.setWallets(List.of());

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("DZENGI-1023141508");
        integration.setAccountRef("DZENGI:1023141508");
        integration.setDisplayName("Dzengi");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        session.setIntegrations(List.of(integration));

        AssetLedgerPoint tslaPoint = new AssetLedgerPoint();
        tslaPoint.setAccountingUniverseId("session-dzengi-dash");
        tslaPoint.setWalletAddress("DZENGI:1023141508");
        tslaPoint.setNetworkId(null);
        tslaPoint.setAccountingAssetIdentity("SYMBOL:TSLA");
        tslaPoint.setAccountingFamilyIdentity("symbol:tsla");
        tslaPoint.setFamilyDisplaySymbol("TSLA");
        tslaPoint.setAssetSymbol("TSLA");
        tslaPoint.setAssetContract("SYMBOL:TSLA");
        tslaPoint.setQuantityAfter(new BigDecimal("0.2"));
        tslaPoint.setQuantityShortfallAfter(BigDecimal.ZERO);
        tslaPoint.setBasisBackedQuantityAfter(new BigDecimal("0.2"));
        tslaPoint.setAvcoAfterUsd(new BigDecimal("371.53"));
        tslaPoint.setRealisedPnlDeltaUsd(new BigDecimal("12.05"));
        tslaPoint.setHasIncompleteHistoryAfter(false);
        tslaPoint.setHasUnresolvedFlagsAfter(false);
        tslaPoint.setReplaySequence(1L);

        when(userSessionRepository.findById("session-dzengi-dash")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "session-dzengi-dash",
                List.of("DZENGI:1023141508"),
                List.of()
        ));
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(List.of(tslaPoint));
        lenient().when(mongoOperations.find(any(Query.class), eq(HistoricalPriceDocument.class))).thenReturn(List.of());
        lenient().when(currentPriceReadService.resolveLatest(any())).thenReturn(java.util.Map.of(
                "TSLA", new com.walletradar.application.pricing.latest.ResolvedPrice(
                        new BigDecimal("392.35"), PriceSource.DZENGI, Instant.parse("2026-07-08T00:00:00Z"), false)
        ));

        SessionDashboardQueryService.SessionDashboardView result = sessionDashboardQueryService
                .findSessionDashboard("session-dzengi-dash")
                .orElseThrow();

        assertThat(result.wallets().stream().map(SessionDashboardQueryService.WalletView::address))
                .contains("dzengi:1023141508");
        assertThat(result.tokenPositions()).singleElement().satisfies(token -> {
            assertThat(token.networkId()).isEqualTo("DZENGI");
            assertThat(token.walletAddress()).isEqualTo("dzengi:1023141508");
            assertThat(token.symbol()).isEqualTo("TSLA");
            assertThat(token.quantity()).isEqualByComparingTo("0.2");
            assertThat(token.avcoUsd()).isEqualByComparingTo("371.53");
            assertThat(token.marketValueUsd()).isEqualByComparingTo("78.47");
            assertThat(token.realizedPnlUsd()).isEqualByComparingTo("12.05");
            assertThat(token.priceIssue()).isNull();
        });
    }
}
