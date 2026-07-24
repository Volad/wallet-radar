package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.CorridorBasisConservationGuard;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Finding 2 — same-network custody/parking round-trip continuity as ONE pooled basis envelope.
 *
 * <p>Reproduces the Katana weETH+ETH vault round-trip that {@code SameNetworkCustodyRoundTripLinkService}
 * links into a single {@code bridge:custody-roundtrip:} correlation. Evidence anchors (Katana, prod
 * {@code walletAddress=0x1a87f12a…}): OUT {@code 0x6553d3…} deposited −0.212586 weETH (basis $827.90)
 * + −0.210631 ETH (basis $943.72) into the vault; IN {@code 0xc69ef1…} withdrew +0.144062 weETH
 * + +0.284651 ETH 11 days later — the vault internally rebalanced weETH→ETH. The hashes are used
 * only as evidence anchors, never as runtime keys.
 *
 * <p>The whole correlation is treated as ONE basis envelope: the total carried-out basis
 * ($1,771.62) is pooled across both families and redistributed onto the RETURNED assets by their
 * return-time market-value weights, so {@code Σ carried-in == Σ carried-out} exactly regardless of
 * the returned composition. Per-family independent carry (the pre-fix behaviour) over-restored the
 * ETH family by +$204.58 and inflated weETH avco to $5,746; the pooled envelope removes that
 * coupling. Any returned quantity whose value exceeds the pooled basis stays uncovered (zero-basis
 * surplus); basis is never minted above what was carried out. rPnL stays 0 and the corridor
 * conservation guard stays clean.
 */
class TransferReplayHandlerCustodyRoundTripTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String WEETH_VAULT = "0xba9dd716ba2a4b9fa7818802beb631f10bd28073";
    private static final String ETH_VAULT = "0x223ec22d67716fca620aee72b25ffe4ece436f25";
    private static final String OUT_HASH = "0x6553d3903de1a25d3a4865158b20c8ca5e34256d552154acec420d78c497c58e";
    private static final String CORR = CorrelationContract.BRIDGE_CUSTODY_ROUNDTRIP_PREFIX + OUT_HASH;

    private static final MathContext MC = MathContext.DECIMAL64;

    // Out leg composition (basis released at the wallet's accumulated avco).
    private static final BigDecimal WEETH_OUT_QTY = new BigDecimal("0.212586235193760390");
    private static final BigDecimal WEETH_OUT_BASIS = new BigDecimal("827.90");
    private static final BigDecimal ETH_OUT_QTY = new BigDecimal("0.210631041768032693");
    private static final BigDecimal ETH_OUT_BASIS = new BigDecimal("943.72");

    // In leg composition (rebalanced inside the vault; quantities differ per family).
    private static final BigDecimal WEETH_IN_QTY = new BigDecimal("0.144062214399527156");
    private static final BigDecimal ETH_IN_QTY = new BigDecimal("0.284650978143220617");

    // Return-time market prices (weETH ≈ $3,015, ETH ≈ $2,764.28 on 2025-11-21).
    private static final BigDecimal WEETH_IN_PRICE = new BigDecimal("3015.00");
    private static final BigDecimal ETH_IN_PRICE = new BigDecimal("2764.28");

    private static final BigDecimal TOTAL_OUT_BASIS = WEETH_OUT_BASIS.add(ETH_OUT_BASIS); // 1771.62

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;
    private final CorridorBasisConservationGuard guard = new CorridorBasisConservationGuard();
    private ReplayFlowSupport flowSupport;

    /** Return-time market prices keyed by asset symbol; consulted by the mocked market authority. */
    private final Map<String, BigDecimal> returnPricesBySymbol = new HashMap<>();

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine(null);
        flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        var keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        // The pooled envelope redistributes basis by return-time MARKET VALUE. The market authority
        // answers per-flow from returnPricesBySymbol; symbols with no configured price resolve empty
        // (quantity-weight fallback). Only the inbound legs consult it.
        lenient().when(marketAuthority.resolve(any(), any())).thenAnswer(invocation -> {
            NormalizedTransaction.Flow flow = invocation.getArgument(1);
            BigDecimal price = flow == null ? null : returnPricesBySymbol.get(flow.getAssetSymbol());
            if (price == null) {
                return Optional.empty();
            }
            return Optional.of(new ReplayMarketAuthority.ResolvedMarketPrice(
                    price, PriceSource.COINGECKO, ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE));
        });
        handler = TransferReplayHandlerFixtures.handler(
                flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now()));
    }

    @Test
    @DisplayName("Finding 2: rebalanced Katana round-trip pools basis; Σ conserved (no +$204.58), no weETH avco inflation, rpnl=0, guard clean")
    void custodyRoundTripPoolsBasisAndRedistributesByReturnValue() {
        returnPricesBySymbol.put("weETH", WEETH_IN_PRICE);
        returnPricesBySymbol.put("ETH", ETH_IN_PRICE);

        AssetKey weethKey = new AssetKey(WALLET, NetworkId.KATANA, "FAMILY:WEETH", "weETH", "FAMILY:WEETH");
        AssetKey ethKey = new AssetKey(WALLET, NetworkId.KATANA, "SYMBOL:ETH", "ETH", "FAMILY:ETH");

        PositionState weethPos = seed(weethKey, WEETH_OUT_QTY, WEETH_OUT_BASIS);
        PositionState ethPos = seed(ethKey, ETH_OUT_QTY, ETH_OUT_BASIS);

        // OUT: deposit both assets into the vault — each principal releases its CARRY_OUT into the pool.
        NormalizedTransaction outLeg = roundTripTx(NormalizedTransactionType.BRIDGE_OUT,
                WEETH_OUT_QTY.negate(), ETH_OUT_QTY.negate());
        applyDispatchLike(outLeg, weethPos, ethPos);

        assertThat(weethPos.quantity()).isEqualByComparingTo("0");
        assertThat(ethPos.quantity()).isEqualByComparingTo("0");

        // IN: withdraw the rebalanced composition — pooled basis redistributed by market-value weight.
        NormalizedTransaction inLeg = roundTripTx(NormalizedTransactionType.BRIDGE_IN,
                WEETH_IN_QTY, ETH_IN_QTY);
        applyDispatchLike(inLeg, weethPos, ethPos);

        // (a) Σ carried-in == Σ carried-out exactly — no fabricated +$204.58.
        BigDecimal totalInBasis = weethPos.totalCostBasisUsd().add(ethPos.totalCostBasisUsd());
        assertThat(totalInBasis).isCloseTo(TOTAL_OUT_BASIS, within(new BigDecimal("0.02")));

        // (b) No per-family avco inflation: each family holds only its return-value-weighted share of
        // the pool, so weETH avco is far below the pre-fix $5,746 and near its value-weighted level.
        BigDecimal weethValue = WEETH_IN_QTY.multiply(WEETH_IN_PRICE, MC);
        BigDecimal ethValue = ETH_IN_QTY.multiply(ETH_IN_PRICE, MC);
        BigDecimal totalValue = weethValue.add(ethValue, MC);
        BigDecimal expectedWeethBasis = TOTAL_OUT_BASIS.multiply(weethValue.divide(totalValue, MC), MC);
        assertThat(weethPos.totalCostBasisUsd())
                .isCloseTo(expectedWeethBasis, within(new BigDecimal("0.02")));
        assertThat(weethPos.perWalletAvco())
                .isLessThan(new BigDecimal("5000")); // nowhere near the $5,746 per-family bug.

        // (c) Pooled basis ($1,771.62) exceeds total returned value ($1,221) so both families are
        // fully covered (a carried unrealised loss) — no uncovered surplus here.
        assertThat(weethPos.quantity()).isEqualByComparingTo(WEETH_IN_QTY);
        assertThat(ethPos.quantity()).isEqualByComparingTo(ETH_IN_QTY);
        assertThat(weethPos.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(ethPos.uncoveredQuantity()).isEqualByComparingTo("0");

        // net ≤ tax preserved on both lanes.
        assertThat(weethPos.netTotalCostBasisUsd()).isLessThanOrEqualTo(weethPos.totalCostBasisUsd());
        assertThat(ethPos.netTotalCostBasisUsd()).isLessThanOrEqualTo(ethPos.totalCostBasisUsd());

        // Pure carry: no realized P&L booked on either family.
        assertThat(weethPos.totalRealisedPnlUsd()).isEqualByComparingTo("0");
        assertThat(ethPos.totalRealisedPnlUsd()).isEqualByComparingTo("0");

        // (d) Corridor conservation guard finds no orphaned released carry.
        assertThat(guard.evaluate(replayState).conserved()).isTrue();
    }

    @Test
    @DisplayName("Finding 2: asymmetric rebalance where return value > pooled basis leaves surplus uncovered; Σ conserved, guard clean")
    void custodyRoundTripLeavesSurplusUncoveredWhenReturnValueExceedsPool() {
        // Synthetic vault: deposit 10 A ($100 basis) + 10 B ($100 basis) → pool $200. Return LESS A
        // (5 A) + MORE B (30 B), both @ $10 market → return value $350 > pooled $200. The value above
        // the pool must stay uncovered rather than mint basis.
        returnPricesBySymbol.put("AAA", new BigDecimal("10"));
        returnPricesBySymbol.put("BBB", new BigDecimal("10"));

        AssetKey aKey = new AssetKey(WALLET, NetworkId.KATANA, "SYMBOL:AAA", "AAA", "FAMILY:AAA");
        AssetKey bKey = new AssetKey(WALLET, NetworkId.KATANA, "SYMBOL:BBB", "BBB", "FAMILY:BBB");

        PositionState aPos = seed(aKey, new BigDecimal("10"), new BigDecimal("100"));
        PositionState bPos = seed(bKey, new BigDecimal("10"), new BigDecimal("100"));

        NormalizedTransaction outLeg = twoAssetTx(NormalizedTransactionType.BRIDGE_OUT,
                "AAA", new BigDecimal("-10"), "BBB", new BigDecimal("-10"));
        applyDispatchLike(outLeg, aPos, bPos);

        NormalizedTransaction inLeg = twoAssetTx(NormalizedTransactionType.BRIDGE_IN,
                "AAA", new BigDecimal("5"), "BBB", new BigDecimal("30"));
        applyDispatchLike(inLeg, aPos, bPos);

        // Σ carried-in == Σ carried-out exactly ($200).
        BigDecimal totalInBasis = aPos.totalCostBasisUsd().add(bPos.totalCostBasisUsd());
        assertThat(totalInBasis).isCloseTo(new BigDecimal("200"), within(new BigDecimal("0.01")));

        // Value-weighted split: A gets 50/350·200 = $28.57, B gets 300/350·200 = $171.43.
        assertThat(aPos.totalCostBasisUsd()).isCloseTo(new BigDecimal("28.57"), within(new BigDecimal("0.05")));
        assertThat(bPos.totalCostBasisUsd()).isCloseTo(new BigDecimal("171.43"), within(new BigDecimal("0.05")));

        // Surplus stays uncovered — basis never minted above the pool.
        // A: covered = 28.57/$10 = 2.857 → uncovered ≈ 2.143 of 5.
        // B: covered = 171.43/$10 = 17.143 → uncovered ≈ 12.857 of 30.
        assertThat(aPos.uncoveredQuantity()).isCloseTo(new BigDecimal("2.143"), within(new BigDecimal("0.02")));
        assertThat(bPos.uncoveredQuantity()).isCloseTo(new BigDecimal("12.857"), within(new BigDecimal("0.02")));

        // net ≤ tax on both lanes; rPnL = 0.
        assertThat(aPos.netTotalCostBasisUsd()).isLessThanOrEqualTo(aPos.totalCostBasisUsd());
        assertThat(bPos.netTotalCostBasisUsd()).isLessThanOrEqualTo(bPos.totalCostBasisUsd());
        assertThat(aPos.totalRealisedPnlUsd()).isEqualByComparingTo("0");
        assertThat(bPos.totalRealisedPnlUsd()).isEqualByComparingTo("0");

        // Corridor conservation guard clean.
        assertThat(guard.evaluate(replayState).conserved()).isTrue();
    }

    /**
     * Applies both principal legs the way {@code ReplayDispatcher} does for a continuity transfer:
     * routes through {@link TransferReplayHandler#applyTransfer} with a transfer-shaped flow. The
     * dispatcher's inbound spot fallback is intentionally NOT invoked here — it is suppressed for
     * custody-roundtrip legs so it cannot fabricate surplus basis.
     */
    private void applyDispatchLike(NormalizedTransaction tx, PositionState pos0, PositionState pos1) {
        handler.applyTransfer(tx, flowSupport.asTransferFlow(flow(tx, 0)), 0, pos0, replayState);
        handler.applyTransfer(tx, flowSupport.asTransferFlow(flow(tx, 1)), 1, pos1, replayState);
    }

    private PositionState seed(AssetKey key, BigDecimal quantity, BigDecimal basisUsd) {
        PositionState position = replayState.position(key);
        position.setQuantity(quantity);
        position.setTotalCostBasisUsd(basisUsd);
        position.setNetTotalCostBasisUsd(basisUsd);
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setPerWalletAvco(basisUsd.divide(quantity, MC));
        return position;
    }

    private static NormalizedTransaction.Flow flow(NormalizedTransaction tx, int index) {
        return tx.getFlows().get(index);
    }

    private static NormalizedTransaction roundTripTx(
            NormalizedTransactionType type,
            BigDecimal weethSignedQty,
            BigDecimal ethSignedQty
    ) {
        return twoAssetTx(type, "weETH", weethSignedQty, "ETH", ethSignedQty);
    }

    private static NormalizedTransaction twoAssetTx(
            NormalizedTransactionType type,
            String symbolA,
            BigDecimal signedQtyA,
            String symbolB,
            BigDecimal signedQtyB
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(type + ":KATANA:" + symbolA + signedQtyA + ":" + symbolB + signedQtyB);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.KATANA);
        tx.setCorrelationId(CORR);
        tx.setContinuityCandidate(true);
        tx.setBlockTimestamp(type == NormalizedTransactionType.BRIDGE_OUT
                ? Instant.parse("2025-11-10T08:38:19Z")
                : Instant.parse("2025-11-21T07:02:15Z"));
        // Price-less TRANSFER principals (as retagged by the linker) so replay carries basis.
        tx.setFlows(new ArrayList<>(List.of(
                transferFlow(symbolA, signedQtyA, WEETH_VAULT),
                transferFlow(symbolB, signedQtyB, ETH_VAULT)
        )));
        return tx;
    }

    private static NormalizedTransaction.Flow transferFlow(String symbol, BigDecimal signedQty, String counterparty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(signedQty);
        flow.setCounterpartyAddress(counterparty);
        return flow;
    }
}
