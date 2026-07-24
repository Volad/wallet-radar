package com.walletradar.application.liquiditypools.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.application.liquiditypools.persistence.LpEarningPoint;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator;
import com.walletradar.application.session.application.AccountingUniverseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLpQueryServiceTest {

    private static final String SESSION_ID = "session-lp-1";
    private static final String UNIVERSE_ID = "universe-lp-1";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String CORR = "lp-position:ethereum:0xnfpm:841022";

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private LpPositionSnapshotService snapshotService;
    @Mock
    private LpEarningPointService earningPointService;
    @Mock
    private HistoricalPriceCacheService historicalPriceCacheService;
    @Mock
    private PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;
    @Mock
    private com.walletradar.application.pricing.latest.CurrentPriceReadService currentPriceReadService;

    @Test
    void costBasisTiesToBasisPools() {
        SessionLpView view = query(openPositionTxs(), basisPools(new BigDecimal("4991")), ledgerPoints(), snapshot());
        LpPositionView position = view.positions().getFirst();
        assertThat(position.costBasisUsd()).isEqualByComparingTo("4991");
        assertThat(position.costBasisPrecision()).isEqualTo(LpFieldPrecision.EXACT);
    }

    @Test
    void claimedFeesTieToLedgerLpFeeClaim() {
        SessionLpView view = query(openPositionTxs(), basisPools(new BigDecimal("1000")),
                List.of(feeClaimLedger(new BigDecimal("182.40"), Instant.parse("2025-01-10T00:00:00Z"))),
                snapshot());
        assertThat(view.positions().getFirst().fees().claimedUsd()).isEqualByComparingTo("182.40");
        assertThat(view.summary().realizedPnlUsd()).isEqualByComparingTo("182.40");
    }

    @Test
    void closedPositionHasZeroTvlAndNoIl() {
        NormalizedTransaction exitFinal = lpTx(NormalizedTransactionType.LP_EXIT_FINAL, Instant.parse("2024-10-30T00:00:00Z"));
        SessionLpView view = query(List.of(openEntry(), exitFinal), basisPools(BigDecimal.ZERO), ledgerPoints(), null);
        LpPositionView position = view.positions().getFirst();
        assertThat(position.status()).isEqualTo("closed");
        assertThat(position.tvlUsd()).isEqualByComparingTo("0");
        assertThat(position.fees().unclaimedUsd()).isEqualByComparingTo("0");
        assertThat(position.il().precision()).isEqualTo(LpFieldPrecision.NOT_APPLICABLE);
        assertThat(position.priceAppreciationPrecision()).isEqualTo(LpFieldPrecision.NOT_APPLICABLE);
    }

    @Test
    void realizedPnlDoesNotUseWithdrawalsMinusDeposits() {
        NormalizedTransaction entry = openEntry();
        entry.getFlows().getFirst().setValueUsd(new BigDecimal("5000"));
        NormalizedTransaction exitPartial = lpTx(NormalizedTransactionType.LP_EXIT_PARTIAL, Instant.parse("2025-02-01T00:00:00Z"));
        NormalizedTransaction.Flow out = new NormalizedTransaction.Flow();
        out.setRole(NormalizedLegRole.SELL);
        out.setAssetSymbol("USDC");
        out.setQuantityDelta(new BigDecimal("2000"));
        out.setValueUsd(new BigDecimal("2000"));
        exitPartial.setFlows(List.of(out));

        AssetLedgerPoint feeClaim = feeClaimLedger(new BigDecimal("50"), Instant.parse("2025-01-01T00:00:00Z"));
        feeClaim.setRealisedPnlDeltaUsd(new BigDecimal("50"));

        SessionLpView view = query(List.of(entry, exitPartial), basisPools(new BigDecimal("3000")),
                List.of(feeClaim), snapshot());
        LpPositionView position = view.positions().getFirst();
        assertThat(position.withdrawnUsd()).isEqualByComparingTo("2000");
        assertThat(position.fees().claimedUsd()).isEqualByComparingTo("50");
    }

    @Test
    void unclaimedExcludedWhenSnapshotOlderThanLatestClaim() {
        LpPositionSnapshot snap = snapshot();
        snap.setSnapshotAt(Instant.parse("2025-01-01T00:00:00Z"));
        snap.setUnclaimedFeesUsd(new BigDecimal("100"));
        AssetLedgerPoint claim = feeClaimLedger(new BigDecimal("80"), Instant.parse("2025-01-10T00:00:00Z"));
        SessionLpView view = query(openPositionTxs(), basisPools(new BigDecimal("1000")), List.of(claim), snap);
        assertThat(view.positions().getFirst().fees().unclaimedUsd()).isEqualByComparingTo("0");
    }

    @Test
    void missingSnapshotUsesUnknownStatus() {
        SessionLpView view = query(openPositionTxs(), basisPools(new BigDecimal("4991")), ledgerPoints(), null);
        assertThat(view.positions().getFirst().status()).isEqualTo("unknown");
    }

    @Test
    void wethPricedViaEthCanonicalAlias() {
        LpPositionSnapshot snap = snapshot();
        LpPositionSnapshot.TokenSide weth = new LpPositionSnapshot.TokenSide();
        weth.setSym("WETH");
        weth.setQty(new BigDecimal("0.2144"));
        snap.setToken0(weth);
        LpPositionSnapshot.TokenSide usdc = new LpPositionSnapshot.TokenSide();
        usdc.setSym("USDC");
        usdc.setQty(new BigDecimal("224"));
        snap.setToken1(usdc);
        snap.setUnclaimedFeesByToken(Map.of("WETH", new BigDecimal("0.0025"), "USDC", new BigDecimal("3.92")));

        SessionLpView view = query(openPositionTxs(), basisPools(new BigDecimal("598")), ledgerPoints(), snap);
        LpPositionView position = view.positions().getFirst();
        assertThat(position.token0().usd()).isNotNull();
        assertThat(position.fees().unclaimedUsd()).isGreaterThan(new BigDecimal("7"));
    }

    @Test
    void twoLegEntryExposedInTxnView() {
        NormalizedTransaction entry = openEntry();
        NormalizedTransaction.Flow eth = new NormalizedTransaction.Flow();
        eth.setRole(NormalizedLegRole.SELL);
        eth.setAssetSymbol("ETH");
        eth.setQuantityDelta(new BigDecimal("-1.2"));
        eth.setValueUsd(new BigDecimal("3360"));
        NormalizedTransaction.Flow usdc = new NormalizedTransaction.Flow();
        usdc.setRole(NormalizedLegRole.SELL);
        usdc.setAssetSymbol("USDC");
        usdc.setQuantityDelta(new BigDecimal("-3360"));
        usdc.setValueUsd(new BigDecimal("3360"));
        entry.setFlows(List.of(eth, usdc));

        SessionLpView view = query(List.of(entry), basisPools(new BigDecimal("6720")), ledgerPoints(), snapshot());
        LpPositionView position = view.positions().getFirst();
        assertThat(position.entryToken0().sym()).isEqualTo("ETH");
        assertThat(position.entryToken1().sym()).isEqualTo("USDC");
        assertThat(position.depositedMarketUsd()).isEqualByComparingTo("6720");
        assertThat(position.txns().getFirst().assetSymbol1()).isEqualTo("USDC");
    }

    @Test
    void openEntryUsesHistoricalUsdWhenFlowValueMissing() {
        NormalizedTransaction entry = lpTx(NormalizedTransactionType.LP_ENTRY, Instant.parse("2026-06-26T16:12:19Z"));
        entry.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "WETH", "-0.20999999980759044", null),
                flow(NormalizedLegRole.TRANSFER, "USDC", "-233.089686", null)
        ));

        when(priceExternalSourceOrchestrator.prioritizedSources(any()))
                .thenReturn(List.of(PriceSource.BYBIT, PriceSource.COINGECKO));
        when(historicalPriceCacheService.findCanonicalQuote(any(), eq(entry.getBlockTimestamp()), eq(PriceSource.BYBIT)))
                .thenReturn(Optional.of(new PriceQuote(
                        new BigDecimal("1578.96"),
                        PriceSource.BYBIT,
                        entry.getBlockTimestamp(),
                        "USD",
                        "hist:eth"
                )));

        LpPositionSnapshot snap = wethSnapshot();
        SessionLpView view = query(List.of(entry), basisPools(new BigDecimal("596.7520464442760874")), ledgerPoints(), snap);

        LpPositionView position = view.positions().getFirst();
        assertThat(position.depositedMarketUsd()).isEqualByComparingTo("564.6712856961930011424");
        assertThat(position.entryToken0().sym()).isEqualTo("WETH");
        assertThat(position.entryToken0().usd()).isEqualByComparingTo("331.5815996961930011424");
        assertThat(position.entryToken1().sym()).isEqualTo("USDC");
        assertThat(position.entryToken1().usd()).isEqualByComparingTo("233.089686");
        assertThat(position.txns().getFirst().valueUsd()).isEqualByComparingTo("331.5815996961930011424");
        assertThat(position.txns().getFirst().valueUsd1()).isEqualByComparingTo("233.089686");
        assertThat(position.txns().getFirst().totalValueUsd()).isEqualByComparingTo("564.6712856961930011424");
    }

    @Test
    void entryTokenSidesFollowSnapshotOrderInsteadOfAlphabeticalOrder() {
        NormalizedTransaction entry = lpTx(NormalizedTransactionType.LP_ENTRY, Instant.parse("2026-06-26T16:12:19Z"));
        entry.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "WETH", "-0.20999999980759044", new BigDecimal("331.5815996961930011424")),
                flow(NormalizedLegRole.TRANSFER, "USDC", "-233.089686", new BigDecimal("233.089686"))
        ));

        LpPositionView position = query(
                List.of(entry),
                basisPools(new BigDecimal("596.7520464442760874")),
                ledgerPoints(),
                wethSnapshot()
        ).positions().getFirst();

        assertThat(position.entryToken0().sym()).isEqualTo("WETH");
        assertThat(position.entryToken0().qty()).isEqualByComparingTo("0.20999999980759044");
        assertThat(position.entryToken1().sym()).isEqualTo("USDC");
        assertThat(position.entryToken1().qty()).isEqualByComparingTo("233.089686");
    }

    @Test
    void openPositionSurvivesMissingCurrentPrices() {
        SessionLpView view = query(
                openPositionTxs(),
                basisPools(new BigDecimal("4991")),
                ledgerPoints(),
                snapshot(),
                List.of()
        );
        LpPositionView position = view.positions().getFirst();
        assertThat(position.tvlUsd()).isNotNull();
        assertThat(position.depositedMarketUsd()).isEqualByComparingTo("6720");
        assertThat(position.token0().usd()).isNull();
    }

    @Test
    void derivesPendlePairFromFourSegmentKeyDroppingWalletSegment() throws Exception {
        // ADR-081 (C2): the 4-segment key `pendle-lp:{network}:{marketOrSyAddress}:{walletLower}` must
        // derive its label from the market segment only; the trailing wallet segment is a per-wallet
        // disambiguator and must never leak into the display label.
        assertThat(derivePair("pendle-lp:mantle:cmeth-market:0xa0dd"))
                .isEqualTo("CMETH/MARKET");
        assertThat(derivePair("pendle-lp:mantle:0xabc123:0xa0dd"))
                .isEqualTo("0XABC123");
        // Legacy 3-segment key still works unchanged.
        assertThat(derivePair("pendle-lp:mantle:pendle-lpt"))
                .isEqualTo("PENDLE/LPT");
    }

    private static String derivePair(String correlationId) throws Exception {
        var method = SessionLpQueryService.class
                .getDeclaredMethod("derivePairFromCorrelationId", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, correlationId);
    }

    private SessionLpView query(
            List<NormalizedTransaction> txs,
            List<LpReceiptBasisPool> basis,
            List<AssetLedgerPoint> ledger,
            LpPositionSnapshot snapshot
    ) {
        return query(txs, basis, ledger, snapshot, priceQuotes());
    }

    private SessionLpView query(
            List<NormalizedTransaction> txs,
            List<LpReceiptBasisPool> basis,
            List<AssetLedgerPoint> ledger,
            LpPositionSnapshot snapshot,
            List<CurrentPriceQuoteDocument> prices
    ) {
        UserSession session = new UserSession();
        session.setId(SESSION_ID);
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(WALLET);
        session.setWallets(List.of(wallet));

        // Build a resolved-price map from the legacy price-quote list for backward compat
        java.util.Map<String, com.walletradar.application.pricing.latest.ResolvedPrice> resolvedMap = new java.util.LinkedHashMap<>();
        for (CurrentPriceQuoteDocument price : prices) {
            if (price.getSymbol() != null && price.getPriceUsd() != null) {
                resolvedMap.put(price.getSymbol().toUpperCase(java.util.Locale.ROOT),
                        new com.walletradar.application.pricing.latest.ResolvedPrice(
                                price.getPriceUsd(),
                                price.getSource() != null ? price.getSource() : com.walletradar.domain.common.PriceSource.UNKNOWN,
                                java.time.Instant.now(),
                                false
                        ));
            }
        }
        lenient().when(currentPriceReadService.resolveLatest(any())).thenReturn(resolvedMap);

        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(
                new AccountingUniverseService.AccountingUniverseScope(UNIVERSE_ID, List.of(WALLET), List.of(WALLET)));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(txs);
        when(mongoOperations.find(any(Query.class), eq(LpReceiptBasisPool.class))).thenReturn(basis);
        when(mongoOperations.find(any(Query.class), eq(AssetLedgerPoint.class))).thenReturn(ledger);
        when(snapshotService.findByUniverseId(UNIVERSE_ID)).thenReturn(snapshot == null ? List.of() : List.of(snapshot));
        when(earningPointService.findSeriesByCorrelationId(CORR)).thenReturn(List.of());
        lenient().when(priceExternalSourceOrchestrator.prioritizedSources(any()))
                .thenReturn(List.of(PriceSource.BYBIT, PriceSource.COINGECKO));

        LiquidityPoolsProperties properties = new LiquidityPoolsProperties();
        properties.setDustThresholdUsd(BigDecimal.ZERO);
        SessionLpQueryService service = new SessionLpQueryService(
                userSessionRepository,
                accountingUniverseService,
                mongoOperations,
                snapshotService,
                earningPointService,
                historicalPriceCacheService,
                priceExternalSourceOrchestrator,
                properties,
                currentPriceReadService
        );
        return service.findSessionLp(SESSION_ID, LpPositionScope.ALL).orElseThrow();
    }

    private static List<NormalizedTransaction> openPositionTxs() {
        return List.of(openEntry());
    }

    private static NormalizedTransaction openEntry() {
        return lpTx(NormalizedTransactionType.LP_ENTRY, Instant.parse("2024-10-12T00:00:00Z"));
    }

    private static NormalizedTransaction lpTx(NormalizedTransactionType type, Instant ts) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-" + type.name());
        tx.setTxHash("0xabc");
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setWalletAddress(WALLET);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setType(type);
        tx.setCorrelationId(CORR);
        tx.setProtocolName("Uniswap V3");
        tx.setBlockTimestamp(ts);

        NormalizedTransaction.Flow eth = new NormalizedTransaction.Flow();
        eth.setRole(NormalizedLegRole.SELL);
        eth.setAssetSymbol("ETH");
        eth.setQuantityDelta(new BigDecimal("-1.2"));
        eth.setValueUsd(new BigDecimal("3360"));

        NormalizedTransaction.Flow usdc = new NormalizedTransaction.Flow();
        usdc.setRole(NormalizedLegRole.SELL);
        usdc.setAssetSymbol("USDC");
        usdc.setQuantityDelta(new BigDecimal("-3360"));
        usdc.setValueUsd(new BigDecimal("3360"));

        tx.setFlows(List.of(eth, usdc));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String qty,
            BigDecimal valueUsd
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setValueUsd(valueUsd);
        return flow;
    }

    private static List<LpReceiptBasisPool> basisPools(BigDecimal basisUsd) {
        LpReceiptBasisPool pool = new LpReceiptBasisPool();
        pool.setUniverseId(UNIVERSE_ID);
        pool.setLpCorrelationId(CORR);
        pool.setWalletAddress(WALLET);
        pool.setNetworkId(NetworkId.ETHEREUM);
        pool.setAssetSymbol("LP-RECEIPT");
        pool.setBasisHeldUsd(basisUsd);
        pool.setQtyHeld(BigDecimal.ONE);
        return List.of(pool);
    }

    private static List<AssetLedgerPoint> ledgerPoints() {
        return List.of();
    }

    private static AssetLedgerPoint feeClaimLedger(BigDecimal claimedUsd, Instant ts) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingUniverseId(UNIVERSE_ID);
        point.setCorrelationId(CORR);
        point.setLifecycleKind(AssetLedgerPoint.LifecycleKind.LP);
        point.setNormalizedType(NormalizedTransactionType.LP_FEE_CLAIM.name());
        point.setCostBasisDeltaUsd(claimedUsd);
        point.setBlockTimestamp(ts);
        return point;
    }

    private static LpPositionSnapshot snapshot() {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(CORR);
        snapshot.setUniverseId(UNIVERSE_ID);
        snapshot.setStatus("in_range");
        snapshot.setTvlUsd(new BigDecimal("4991"));
        snapshot.setUnclaimedFeesUsd(new BigDecimal("69.4"));
        snapshot.setSnapshotAt(Instant.parse("2025-01-14T00:00:00Z"));
        snapshot.setSnapshotStale(false);
        LpPositionSnapshot.TokenSide eth = new LpPositionSnapshot.TokenSide();
        eth.setSym("ETH");
        eth.setQty(new BigDecimal("0.812"));
        eth.setUsd(new BigDecimal("2581"));
        LpPositionSnapshot.TokenSide usdc = new LpPositionSnapshot.TokenSide();
        usdc.setSym("USDC");
        usdc.setQty(new BigDecimal("2410"));
        usdc.setUsd(new BigDecimal("2410"));
        snapshot.setToken0(eth);
        snapshot.setToken1(usdc);
        return snapshot;
    }

    private static LpPositionSnapshot wethSnapshot() {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(CORR);
        snapshot.setUniverseId(UNIVERSE_ID);
        snapshot.setStatus("in_range");
        snapshot.setTvlUsd(new BigDecimal("584.91"));
        snapshot.setUnclaimedFeesUsd(new BigDecimal("2.14"));
        snapshot.setSnapshotAt(Instant.parse("2026-07-02T13:23:00Z"));
        snapshot.setSnapshotStale(false);
        LpPositionSnapshot.TokenSide weth = new LpPositionSnapshot.TokenSide();
        weth.setSym("WETH");
        weth.setQty(new BigDecimal("0.188633564864138622"));
        weth.setUsd(new BigDecimal("317.08"));
        LpPositionSnapshot.TokenSide usdc = new LpPositionSnapshot.TokenSide();
        usdc.setSym("USDC");
        usdc.setQty(new BigDecimal("267.83"));
        usdc.setUsd(new BigDecimal("267.83"));
        snapshot.setToken0(weth);
        snapshot.setToken1(usdc);
        return snapshot;
    }

    private static List<CurrentPriceQuoteDocument> priceQuotes() {
        CurrentPriceQuoteDocument eth = new CurrentPriceQuoteDocument();
        eth.setSymbol("ETH");
        eth.setPriceUsd(new BigDecimal("3100"));
        CurrentPriceQuoteDocument usdc = new CurrentPriceQuoteDocument();
        usdc.setSymbol("USDC");
        usdc.setPriceUsd(BigDecimal.ONE);
        return List.of(eth, usdc);
    }
}
