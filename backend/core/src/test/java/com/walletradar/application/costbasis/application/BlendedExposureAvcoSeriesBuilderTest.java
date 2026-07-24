package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RC-E3 / B-ETH-00 (ADR-061) — blended total-exposure AVCO series unit tests.
 *
 * <p>Covers the architect §F acceptance cases 1–9, 11, 12 at the builder level (read-model only):
 * definitional invariant, terminal reconciliation on closed pools, blended-covers-more on open
 * pools, C2 exclusion, zero-LP byte-identity, blended-defined-while-liquid-breaks, blended breaks
 * only at total zero, multi-wallet corridor aggregation, unmatched REALLOCATE_IN clamp ≥ 0, and the
 * B-ETH-04 zero-cost-basis interaction.</p>
 */
class BlendedExposureAvcoSeriesBuilderTest {

    private static final String ETH = "FAMILY:ETH";
    private static final MathContext MC = MathContext.DECIMAL128;

    private final BlendedExposureAvcoSeriesBuilder builder = new BlendedExposureAvcoSeriesBuilder();

    @Nested
    @DisplayName("§F.1 definitional invariant")
    class DefinitionalInvariant {

        @Test
        @DisplayName("blendedCovered == spotCovered + parkedCovered; blendedBasis == spotBasis + parkedBasis")
        void definitionalInvariantHoldsToTheCent() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateOut("lp:1", "tx1", 1L, "1", "0", "2000", "1950")
            ));

            session.applyEvent(List.of("tx1"));
            BigDecimal spotCovered = new BigDecimal("2");
            BigDecimal spotMarketBasis = new BigDecimal("7000");
            BigDecimal spotNetBasis = new BigDecimal("7800");
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(spotCovered, spotMarketBasis, spotNetBasis);

            // parked = (covered 1, market 2000, net 1950); totals divide by 3 exactly.
            assertThat(blended.coveredQuantity()).isEqualByComparingTo("3");
            // blendedBasis == spotBasis + parkedBasis  → market: 9000, net: 9750
            assertThat(blended.marketAvco().multiply(blended.coveredQuantity(), MC))
                    .isEqualByComparingTo("9000");
            assertThat(blended.netAvco().multiply(blended.coveredQuantity(), MC))
                    .isEqualByComparingTo("9750");
            assertThat(blended.avcoKind()).isEqualTo("PRIMARY_FLOW");
        }
    }

    @Nested
    @DisplayName("§F.2 terminal == card on closed pools")
    class TerminalReconciliation {

        @Test
        @DisplayName("fully-restored corridor → parked pool empty → blended == spot Method-B terminal")
        void closedPoolBlendedEqualsSpotTerminal() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateOut("lp:1", "tx1", 1L, "1", "0", "2000", "1950"),
                    reallocateIn("lp:1", "tx2", 2L, "1", "0", "2000", "1950")
            ));

            session.applyEvent(List.of("tx1"));
            session.applyEvent(List.of("tx2"));

            // Terminal spot Method-B (~$2,677 Tax / $2,676.99 Net) — pool closed, so blended == spot.
            BigDecimal spotCovered = new BigDecimal("1");
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended = session.blend(
                    spotCovered, new BigDecimal("2677.30"), new BigDecimal("2676.99"));

            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2677.30");
            assertThat(blended.netAvco()).isEqualByComparingTo("2676.99");
        }
    }

    @Nested
    @DisplayName("§F.3 blended covers more on open pool")
    class BlendedCoversMore {

        @Test
        @DisplayName("open corridor → blendedCovered > spotCovered (covers ≥ spot at every event)")
        void openPoolBlendedCoversMoreThanSpot() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateOut("lp:1", "tx1", 1L, "1", "0", "2500", "2400")
            ));

            session.applyEvent(List.of("tx1"));
            BigDecimal spotCovered = new BigDecimal("0.1");
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(spotCovered, new BigDecimal("450"), new BigDecimal("450"));

            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1.1");
            assertThat(blended.coveredQuantity()).isGreaterThan(spotCovered);
        }
    }

    @Nested
    @DisplayName("§F.4 C2 exclusion regression")
    class C2Exclusion {

        @Test
        @DisplayName("wstETH/weETH/cmETH DISPOSE+ACQUIRE contribute $0 to FAMILY:ETH blended")
        void c2DerivativesNeverEnterBlendedEthPool() {
            // Only REALLOCATE participates → DISPOSE/ACQUIRE never park; additionally a REALLOCATE
            // stamped with a C2 symbol is dropped by the C2 guard. A genuine ETH reallocation parks.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    disposeOut("c2:wsteth", "wtx1", 1L, "WSTETH", "1", "3000"),
                    acquireIn("c2:weeth", "wtx2", 2L, "WEETH", "1", "3100"),
                    disposeOut("c2:cmeth", "wtx3", 3L, "CMETH", "1", "3200"),
                    // A C2-symboled REALLOCATE must also be excluded by the C2 guard.
                    reallocateOutSymbol("c2:wsteth-realloc", "wtx4", 4L, "WSTETH", "1", "0", "3000", "3000"),
                    // A genuine C1 ETH reallocation DOES park.
                    reallocateOut("lp:eth", "wtx5", 5L, "1", "0", "2000", "2000")
            ));

            session.applyEvent(List.of("wtx1", "wtx2", "wtx3", "wtx4", "wtx5"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2200"), new BigDecimal("2200"));

            // Parked = only the ETH reallocation (covered 1, basis 2000). C2 legs contributed $0.
            assertThat(blended.coveredQuantity()).isEqualByComparingTo("2");
            assertThat(blended.marketAvco().multiply(blended.coveredQuantity(), MC))
                    .isEqualByComparingTo("4200"); // 2200 spot + 2000 parked ETH only
        }
    }

    @Nested
    @DisplayName("§F.5 zero-LP session ⇒ blended == liquid")
    class ZeroLpByteIdentical {

        @Test
        @DisplayName("no REALLOCATE with correlationId → parked empty → blended byte-identical to spot")
        void zeroLpBlendedEqualsLiquid() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    // A REALLOCATE with a blank correlationId does NOT park (e.g. legacy LP rows).
                    reallocateOut(null, "tx1", 1L, "1", "0", "2000", "2000")
            ));

            session.applyEvent(List.of("tx1"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("1950"));

            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2000");
            assertThat(blended.netAvco()).isEqualByComparingTo("1950");
        }
    }

    @Nested
    @DisplayName("§F.6/7 break semantics")
    class BreakSemantics {

        @Test
        @DisplayName("§F.6 blended defined while liquid breaks at pool≈0 (open parked pool)")
        void blendedStaysDefinedWhenLiquidDrainsButParkedOpen() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateOut("lp:1", "tx1", 1L, "1", "0", "2000", "2000")
            ));

            session.applyEvent(List.of("tx1"));
            // Liquid pool drained: spotCovered == 0 (spot line would break, ADR-031).
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            assertThat(blended.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2000");
            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("§F.7 blended breaks only when total ETH-origin covered quantity is zero")
        void blendedBreaksOnlyAtTotalZero() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of());

            session.applyEvent(List.of("tx1"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            assertThat(blended.avcoKind()).isEqualTo("UNAVAILABLE");
            assertThat(blended.marketAvco()).isNull();
            assertThat(blended.netAvco()).isNull();
        }
    }

    @Nested
    @DisplayName("§F.8 multi-wallet / network-agnostic corridor aggregation")
    class MultiWalletAggregation {

        @Test
        @DisplayName("LP opened on wallet-a and closed on wallet-b nets across the shared correlationId")
        void corridorNetsAcrossWalletsAndNetworks() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateOut("lp:shared", "tx-a", 1L, "1", "0", "2000", "2000", "wallet-a"),
                    reallocateIn("lp:shared", "tx-b", 2L, "1", "0", "2000", "2000", "wallet-b")
            ));

            session.applyEvent(List.of("tx-a"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint whileOpen =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("1000"));
            assertThat(whileOpen.coveredQuantity()).isEqualByComparingTo("1.5");

            session.applyEvent(List.of("tx-b"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterClose =
                    session.blend(new BigDecimal("1.5"), new BigDecimal("3000"), new BigDecimal("3000"));
            // Corridor closed across wallets → parked empty → blended == spot.
            assertThat(afterClose.coveredQuantity()).isEqualByComparingTo("1.5");
            assertThat(afterClose.marketAvco()).isEqualByComparingTo("2000");
        }
    }

    @Nested
    @DisplayName("§F.11 unmatched REALLOCATE_IN clamp ≥ 0")
    class UnmatchedReallocateIn {

        @Test
        @DisplayName("REALLOCATE_IN with no matching parked slice (yield accrual) does not touch the pool")
        void unmatchedReallocateInIsNoOp() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateIn("orphan:yield", "tx1", 1L, "0.3", "0", "0", "0")
            ));

            session.applyEvent(List.of("tx1"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));

            // Parked pool untouched (not driven negative) → blended == spot.
            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("over-withdrawal clamps the parked slice at 0, never negative (no double-count)")
        void overWithdrawalClampsAtZero() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateOut("lp:1", "tx1", 1L, "1", "0", "2000", "2000"),
                    reallocateIn("lp:1", "tx2", 2L, "2", "0", "4000", "4000")
            ));

            session.applyEvent(List.of("tx1"));
            session.applyEvent(List.of("tx2"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));

            // Parked clamped to 0 → blended == spot (no negative parked basis/quantity).
            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2000");
        }
    }

    @Nested
    @DisplayName("§F.12 B-ETH-04 zero-cost-basis interaction")
    class BEth04ZeroCbd {

        @Test
        @DisplayName("zero-quantity zero-cbd REALLOCATE parks nothing → no spurious blended movement")
        void zeroCbdDustDoesNotMoveBlended() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    // LP_EXIT_FINAL dust: costBasisDelta == 0 and no covered quantity to park.
                    reallocateOut("lp:dust", "tx1", 1L, "0", "0", "0", "0")
            ));

            session.applyEvent(List.of("tx1"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));

            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("zero-cbd REALLOCATE_IN with no matching parked slice → no spurious blended movement")
        void zeroCbdRestorationWithoutParkedSliceIsNoOp() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    reallocateIn("lp:final", "tx1", 1L, "0.000196308863730581", "0", "0", "0")
            ));

            session.applyEvent(List.of("tx1"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));

            assertThat(blended.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(blended.marketAvco()).isEqualByComparingTo("2000");
        }
    }

    @Nested
    @DisplayName("§4 B-ETH-05 cross-asset LP-exit slice closure")
    class CrossAssetLpExitClosure {

        private static final java.time.Instant T1 = java.time.Instant.parse("2026-04-05T10:01:00Z");
        private static final java.time.Instant T2 = java.time.Instant.parse("2026-04-05T10:02:00Z");
        private static final java.time.Instant T3 = java.time.Instant.parse("2026-04-05T10:03:00Z");

        @Test
        @DisplayName("§4.1 cross-asset final exit (ETH→USDC) closes the parked slice; parked(T)==pool(=0)")
        void crossAssetFinalExitClosesSliceToZeroPool() {
            // ETH parked into an LP; the receipt is burned fully but the return lands on USDC — no
            // FAMILY:ETH REALLOCATE_IN ever arrives, so only the receipt-burn + terminal clamps close it.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("lp:x", "tx1", 1L, "1", "0", "2000", "1950")),
                    List.of(receiptBurn("lp:x", "burn1", 2L, "1", "0", T2)),
                    java.util.Map.of() // pool fully closed → no ETH-origin holding
            );

            session.applyEvent(List.of("tx1"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            session.applyTerminalClamp();

            // Some liquid remained; parked ETH is fully closed → blended covers only the liquid.
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(terminal.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.5"); // NOT 1.5 (no over-park)
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("§4.2 partial cross-asset exit is pro-rata: receipt burned 40% → slice 60%, then final → 0")
        void partialCrossAssetExitIsProRata() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("lp:y", "tx1", 1L, "1", "0", "3000", "2900")),
                    List.of(
                            receiptBurn("lp:y", "burn1", 2L, "1", "0.6", T2),   // 40% burned
                            receiptBurn("lp:y", "burn2", 3L, "0.6", "0", T3)     // final burn
                    ),
                    java.util.Map.of()
            );

            session.applyEvent(List.of("tx1"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPark =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(afterPark.coveredQuantity()).isEqualByComparingTo("1");

            // After the 40% burn the slice is clamped down to 60% (AVCO preserved via pro-rata basis).
            session.flushReceiptBurnsUpTo(key(T2, 2L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPartial =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(afterPartial.coveredQuantity()).isEqualByComparingTo("0.6");
            assertThat(afterPartial.marketAvco()).isEqualByComparingTo("3000"); // AVCO preserved
            assertThat(afterPartial.netAvco()).isEqualByComparingTo("2900");

            // Final burn closes the slice entirely.
            session.flushReceiptBurnsUpTo(key(T3, 3L));
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(terminal.avcoKind()).isEqualTo("UNAVAILABLE");
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("§4.3 same-asset exit is byte-identical: receipt-burn clamp is a no-op (no double reduction)")
        void sameAssetExitUnchangedByClamp() {
            List<AssetLedgerPoint> familyPoints = List.of(
                    reallocateOut("lp:z", "tx1", 1L, "1", "0", "2000", "1950"),
                    reallocateIn("lp:z", "tx2", 2L, "1", "0", "2000", "1950")
            );

            // Baseline: pure REALLOCATE reconstruction (no receipt-burn stream).
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession baseline = builder.newSession(ETH, familyPoints);
            baseline.applyEvent(List.of("tx1"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint baseAfterPark =
                    baseline.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            baseline.applyEvent(List.of("tx2"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint baseAfterExit =
                    baseline.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("1950"));

            // With the receipt-burn stream (same-asset exit also burns the receipt in the SAME tx).
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession withBurn = builder.newSession(
                    ETH,
                    familyPoints,
                    List.of(receiptBurn("lp:z", "tx2", 2L, "1", "0", T2)),
                    java.util.Map.of()
            );
            withBurn.applyEvent(List.of("tx1"));
            withBurn.flushReceiptBurnsUpTo(key(T1, 1L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint burnAfterPark =
                    withBurn.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            withBurn.applyEvent(List.of("tx2"));
            withBurn.flushReceiptBurnsUpTo(key(T2, 2L));
            withBurn.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint burnAfterExit =
                    withBurn.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("1950"));

            // Byte-identical to the baseline: the clamp never double-reduces the already-restored slice.
            assertThat(burnAfterPark.coveredQuantity()).isEqualByComparingTo(baseAfterPark.coveredQuantity());
            assertThat(burnAfterExit.coveredQuantity()).isEqualByComparingTo(baseAfterExit.coveredQuantity());
            assertThat(burnAfterExit.marketAvco()).isEqualByComparingTo(baseAfterExit.marketAvco());
            assertThat(burnAfterExit.netAvco()).isEqualByComparingTo(baseAfterExit.netAvco());
        }

        @Test
        @DisplayName("§4.4 multi-position mix: parked terminal tracks only genuinely-open ETH-origin holdings")
        void multiPositionMixTracksOnlyOpen() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(
                            reallocateOut("lp:open-same", "tx1", 1L, "1", "0", "2000", "1950"),
                            reallocateOut("lp:closed-cross", "tx2", 2L, "1", "0", "2000", "1950"),
                            reallocateOut("lp:open-cross", "tx3", 3L, "2", "0", "4000", "3900")
                    ),
                    List.of(
                            receiptBurn("lp:closed-cross", "burnB", 4L, "1", "0", T2),      // fully exited cross-asset
                            receiptBurn("lp:open-cross", "burnC", 5L, "1", "0.5", T3)        // 50% exited cross-asset
                    ),
                    java.util.Map.of(
                            "lp:open-same", holding("0.9", "1800", "1750"),  // still open in LP
                            "lp:open-cross", holding("1", "2000", "1950")     // half remains
                    )
            );

            session.applyEvent(List.of("tx1", "tx2", "tx3"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            // Terminal parked == open-same (0.9) + open-cross (1) ; closed-cross contributes 0.
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("1.9");
            assertThat(terminal.marketAvco().multiply(terminal.coveredQuantity(), MC))
                    .isCloseTo(new BigDecimal("3800"), within(new BigDecimal("0.0000001"))); // 1800 + 2000
            assertThat(terminal.netAvco().multiply(terminal.coveredQuantity(), MC))
                    .isCloseTo(new BigDecimal("3700"), within(new BigDecimal("0.0000001"))); // 1750 + 1950
        }

        @Test
        @DisplayName("§4.5 lending-loop residual is untouched (no FAMILY:LP_RECEIPT burn, no pool row)")
        void lendingLoopResidualUntouched() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(
                            reallocateOut("lending-loop:0xabc", "tx1", 1L, "0.5", "0", "1500", "1450"),
                            reallocateOut("lp:cross", "tx2", 2L, "1", "0", "2000", "1950")
                    ),
                    List.of(receiptBurn("lp:cross", "burn", 3L, "1", "0", T2)), // LP corridor fully closed
                    java.util.Map.of() // no ETH-origin pool rows
            );

            session.applyEvent(List.of("tx1", "tx2"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            // LP corr closed to 0; lending-loop residual (0.5 ETH / $1500) survives untouched.
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.5");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("3000"); // 1500 / 0.5
            assertThat(terminal.netAvco()).isEqualByComparingTo("2900");    // 1450 / 0.5
        }

        @Test
        @DisplayName("§4.6 NFT partial exit: bounded residual until final burn, corrected at terminal clamp")
        void nftPartialExitBoundedThenCorrected() {
            // NFT LP receipt: a partial exit does NOT burn the NFT (qtyBefore == qtyAfter == 1), so the
            // per-event clamp is a no-op and the parked slice stays at full — a bounded, documented
            // approximation until the terminal clamp corrects it to the still-open pool holding.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("lp:nft", "tx1", 1L, "1", "0", "2000", "1950")),
                    List.of(receiptBurn("lp:nft", "burnPartial", 2L, "1", "1", T2)), // NFT not burned on partial
                    java.util.Map.of("lp:nft", holding("0.6", "1200", "1170")) // pool reflects the true remainder
            );

            session.applyEvent(List.of("tx1"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));

            // Before terminal: partial NFT burn is a no-op → bounded residual stays at the full 1 ETH.
            session.flushReceiptBurnsUpTo(key(T2, 2L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint boundedResidual =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(boundedResidual.coveredQuantity()).isEqualByComparingTo("1");

            // Terminal clamp corrects the residual to the authoritative pool holding (0.6 ETH).
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.6");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000"); // 1200 / 0.6
        }

        @Test
        @DisplayName("§4.7 convergence: cross-asset closes drive blended monotonically toward liquid, stays PRIMARY_FLOW")
        void convergesMonotonicallyTowardLiquid() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("lp:c", "tx1", 1L, "2", "0", "6000", "5800")),
                    List.of(
                            receiptBurn("lp:c", "b1", 2L, "1", "0.75", T2),  // 25% out
                            receiptBurn("lp:c", "b2", 3L, "0.75", "0.25", T3) // more out
                    ),
                    java.util.Map.of()
            );

            // Liquid pool: 1 ETH @ $2000 (the "true" liquid AVCO the blended line should converge to).
            BigDecimal liquidCovered = new BigDecimal("1");
            BigDecimal liquidMkt = new BigDecimal("2000");
            BigDecimal liquidNet = new BigDecimal("1950");

            session.applyEvent(List.of("tx1"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPark = session.blend(liquidCovered, liquidMkt, liquidNet);

            session.flushReceiptBurnsUpTo(key(T2, 2L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterFirst = session.blend(liquidCovered, liquidMkt, liquidNet);

            session.flushReceiptBurnsUpTo(key(T3, 3L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterSecond = session.blend(liquidCovered, liquidMkt, liquidNet);

            // Covered quantity converges monotonically down toward the liquid quantity (1 ETH).
            assertThat(afterPark.coveredQuantity()).isEqualByComparingTo("3");
            assertThat(afterFirst.coveredQuantity()).isGreaterThan(afterSecond.coveredQuantity());
            assertThat(afterSecond.coveredQuantity()).isGreaterThanOrEqualTo(liquidCovered);
            // No spike / $0 / lane-hop: blended AVCO stays defined and PRIMARY_FLOW while covered > 0.
            assertThat(afterPark.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(afterFirst.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(afterSecond.avcoKind()).isEqualTo("PRIMARY_FLOW");
            // Parked AVCO ($3000) blends toward the liquid AVCO ($2000) as the corridor drains.
            assertThat(afterFirst.marketAvco()).isBetween(liquidMkt, new BigDecimal("3000"));
            assertThat(afterSecond.marketAvco()).isLessThan(afterFirst.marketAvco());
        }
    }

    @Nested
    @DisplayName("§5 B-ETH-06 generalized cross-family closure")
    class GeneralizedCrossFamilyClosure {

        private static final java.time.Instant T1 = java.time.Instant.parse("2026-04-05T10:01:00Z");
        private static final java.time.Instant T2 = java.time.Instant.parse("2026-04-05T10:02:00Z");

        @Test
        @DisplayName("§5.1 non-LP cross-family settlement (ETH→wstETH DEX) closes the slice at the settlement event")
        void crossFamilySettlementClosesSliceAtEvent() {
            // ETH REALLOCATE_OUT parked, settled as FAMILY:WSTETH ACQUIRE (same correlationId, higher
            // replaySequence in the same tx). No LP_RECEIPT burn, no FAMILY:ETH return, no pool row.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("dex:eth-wsteth", "tx1", 1L, "0.027639", "0", "99.93", "99.93")),
                    List.of(),
                    List.of(crossFamilyAcquire("dex:eth-wsteth", "tx1", 3L, "FAMILY:WSTETH", "WSTETH", T1)),
                    java.util.Map.of()
            );

            session.applyEvent(List.of("tx1"));
            // Right after the park event (eventKey == park seq) the settlement (higher seq) has not
            // been reached yet → parked slice is still open.
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPark =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(afterPark.coveredQuantity()).isEqualByComparingTo(new BigDecimal("0.527639"));

            // A later plotted ETH event at/after the settlement key closes the slice (per-event smoothness).
            session.flushReceiptBurnsUpTo(key(T2, 5L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterSettlement =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(afterSettlement.coveredQuantity()).isEqualByComparingTo("0.5"); // parked closed

            // Terminal: parked for the DEX corr is 0 (no open ETH-origin pool row).
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.5");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("§5.2 bridge OUT-only corr (no return, no settlement, no pool) → terminal clamp closes to 0")
        void bridgeOutOnlyCorrClosesAtTerminal() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("bridge:lifi:0x1", "tx1", 1L, "0.001", "0", "3.27", "3.27")),
                    List.of(),
                    List.of(), // OUT-only: no cross-family ACQUIRE settled in the same tx
                    java.util.Map.of()
            );

            session.applyEvent(List.of("tx1"));
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPark =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));
            assertThat(afterPark.coveredQuantity()).isEqualByComparingTo(new BigDecimal("1.001"));

            // No pool row → generalized terminal clamp closes the bridge dust corr to 0.
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("§5.3 all-corr terminal clamp: mix of open LP + closed LP + DEX-converted + bridge dust → parked == Σ open LP")
        void allCorrTerminalClampKeepsOnlyOpenLp() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(
                            reallocateOut("lp:open", "tx1", 1L, "1", "0", "2000", "1950"),
                            reallocateOut("lp:closed", "tx2", 2L, "1", "0", "2000", "1950"),
                            reallocateOut("dex:conv", "tx3", 3L, "0.5", "0", "1000", "1000"),
                            reallocateOut("bridge:dust", "tx4", 4L, "0.001", "0", "3", "3")
                    ),
                    List.of(receiptBurn("lp:closed", "burn", 5L, "1", "0", T2)), // closed LP fully burned
                    List.of(crossFamilyAcquire("dex:conv", "tx3", 6L, "FAMILY:WSTETH", "WSTETH", T1)),
                    java.util.Map.of("lp:open", holding("1", "2000", "1950")) // only open LP has a pool row
            );

            session.applyEvent(List.of("tx1", "tx2", "tx3", "tx4"));
            session.flushReceiptBurnsUpTo(key(T2, 6L));
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            // Terminal parked == open LP only (1 ETH / $2000). All non-LP corridors closed to 0.
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000");
            assertThat(terminal.netAvco()).isEqualByComparingTo("1950");
        }

        @Test
        @DisplayName("§5.4 mutual exclusion: same-family REALLOCATE_IN corr is not cross-family closed (return owns it)")
        void sameFamilyReturnNotDoubleClosedByCrossFamily() {
            // A corr that both returns to FAMILY:ETH (same-asset) AND has a spurious cross-family point
            // must be handled ONLY by the same-family return path (no double-close).
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(
                            reallocateOut("lp:same", "tx1", 1L, "1", "0", "2000", "1950"),
                            reallocateIn("lp:same", "tx2", 2L, "0.4", "0", "800", "780")
                    ),
                    List.of(),
                    List.of(crossFamilyAcquire("lp:same", "tx1", 3L, "FAMILY:WSTETH", "WSTETH", T1)),
                    java.util.Map.of("lp:same", holding("0.6", "1200", "1170")) // still-open remainder
            );

            session.applyEvent(List.of("tx1"));
            session.applyEvent(List.of("tx2"));
            // The same-family return reduced the slice to 0.6; cross-family close is excluded for this
            // corr, so flushing past its settlement key is a no-op.
            session.flushReceiptBurnsUpTo(key(T2, 9L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterReturn =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(afterReturn.coveredQuantity()).isEqualByComparingTo("0.6");

            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.6"); // reconciled to open pool
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000"); // 1200 / 0.6
        }

        @Test
        @DisplayName("§5.6 separate-tx settlement (DEX escrow park + wstETH settle in a DIFFERENT tx, same corr) closes at the settlement event")
        void separateTxCrossFamilySettlementClosesSliceAtEvent() {
            // Escrow/park leg is tx "park" (seq 1); the wstETH settlement is a SEPARATE tx "settle"
            // (seq 3) sharing the same correlationId — the correlationId-scoped superset still finds it.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("dex:escrow", "park", 1L, "0.027639", "0", "99.93", "99.93")),
                    List.of(),
                    List.of(crossFamilyAcquire("dex:escrow", "settle", 3L, "FAMILY:WSTETH", "WSTETH", T1)),
                    java.util.Map.of()
            );

            session.applyEvent(List.of("park"));
            // At the park event the settlement (higher seq, separate tx) has not been reached yet.
            session.flushReceiptBurnsUpTo(key(T1, 1L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPark =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(afterPark.coveredQuantity()).isEqualByComparingTo(new BigDecimal("0.527639"));

            // A later plotted ETH event at/after the settlement key closes the slice — BEFORE terminal.
            session.flushReceiptBurnsUpTo(key(T2, 5L));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterSettlement =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(afterSettlement.coveredQuantity()).isEqualByComparingTo("0.5"); // parked closed at settlement

            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("975"));
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.5");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("§5.5 lending-loop corr is untouched by both the cross-family close and the terminal clamp")
        void lendingLoopUntouchedByGeneralizedClosure() {
            // Even with a cross-family settlement point present for a lending-loop corr, the prefix guard
            // keeps its basis-conserving residual intact (B-ETH-02).
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(reallocateOut("lending-loop:0xabc", "tx1", 1L, "0.5", "0", "1500", "1450")),
                    List.of(),
                    List.of(crossFamilyAcquire("lending-loop:0xabc", "tx1", 3L, "FAMILY:WSTETH", "WSTETH", T1)),
                    java.util.Map.of() // no pool row for the lending-loop corr
            );

            session.applyEvent(List.of("tx1"));
            session.flushReceiptBurnsUpTo(key(T2, 9L));
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            // Lending-loop residual survives untouched (0.5 ETH / $1500).
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("0.5");
            assertThat(terminal.marketAvco()).isEqualByComparingTo("3000");
            assertThat(terminal.netAvco()).isEqualByComparingTo("2900");
        }
    }

    @Nested
    @DisplayName("§6 RM-1 same-family CARRY corridor fold")
    class CarryCorridorFold {

        private static final java.time.Instant T1 = java.time.Instant.parse("2026-04-05T10:01:00Z");

        @Test
        @DisplayName("§6.1 same-family CARRY_OUT→CARRY_IN keeps the blended denominator whole (no $0 drop between legs)")
        void carryCorridorKeepsDenominatorWholeBetweenLegs() {
            // ETH parked out via a cross-wallet/cross-chain internal transfer (or bridge-out): the liquid
            // pool drains to 0 between the OUT and the IN legs. Before RM-1 the blended line dropped to a
            // false $0/UNAVAILABLE in that window; now the CARRY_OUT is folded so the denominator holds.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    carryOut("bridge:custody-roundtrip:0xabc", "txOut", 1L, "1", "0", "2851", "2851"),
                    carryIn("bridge:custody-roundtrip:0xabc", "txIn", 2L, "1", "0", "2851", "2851")
            ));

            session.applyEvent(List.of("txOut"));
            // In-flight: liquid drained to 0 (spot line would break, ADR-031) but blended holds.
            BlendedExposureAvcoSeriesBuilder.BlendedPoint inFlight =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(inFlight.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(inFlight.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(inFlight.marketAvco()).isEqualByComparingTo("2851");

            // Return leg closes the corridor → parked empty → blended == spot (no double-count).
            session.applyEvent(List.of("txIn"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterReturn =
                    session.blend(new BigDecimal("1"), new BigDecimal("2851"), new BigDecimal("2851"));
            assertThat(afterReturn.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(afterReturn.marketAvco()).isEqualByComparingTo("2851");
        }

        @Test
        @DisplayName("§6.2 internal transfer with no correlationId folds on the shared lifecycleChainId")
        void internalTransferFoldsOnLifecycleChainId() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    carryOutChain("chain:internal:1", "txOut", 1L, "1", "0", "2000", "2000"),
                    carryInChain("chain:internal:1", "txIn", 2L, "1", "0", "2000", "2000")
            ));

            session.applyEvent(List.of("txOut"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint inFlight =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(inFlight.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(inFlight.coveredQuantity()).isEqualByComparingTo("1");
            assertThat(inFlight.marketAvco()).isEqualByComparingTo("2000");

            session.applyEvent(List.of("txIn"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterReturn =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));
            assertThat(afterReturn.coveredQuantity()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("§6.3 lending-loop collateral CARRY_OUT stays folded until LENDING_LOOP_CLOSE CARRY_IN")
        void lendingLoopCollateralStaysFoldedUntilClose() {
            // 0xcb8483-style loop: 0.919 ETH collateral CARRY_OUT on LENDING_LOOP_OPEN, closed on the
            // LENDING_LOOP_CLOSE CARRY_IN. No lending-loop exclusion in the fold path.
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    carryOut("lending-loop:0xcb8483", "open", 1L, "0.919", "0", "3987", "3987"),
                    carryIn("lending-loop:0xcb8483", "close", 2L, "0.919", "0", "3987", "3987")
            ));

            session.applyEvent(List.of("open"));
            // Collateral parked: liquid drained but the collateral is folded back in.
            BlendedExposureAvcoSeriesBuilder.BlendedPoint whileParked =
                    session.blend(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(whileParked.avcoKind()).isEqualTo("PRIMARY_FLOW");
            assertThat(whileParked.coveredQuantity()).isEqualByComparingTo("0.919");

            session.applyEvent(List.of("close"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterClose =
                    session.blend(new BigDecimal("0.919"), new BigDecimal("3987"), new BigDecimal("3987"));
            assertThat(afterClose.coveredQuantity()).isEqualByComparingTo("0.919"); // parked closed via CARRY_IN
        }

        @Test
        @DisplayName("§6.4 C2 (weETH) CARRY is excluded from the blended FAMILY:ETH pool")
        void c2CarryExcludedFromBlendedEthPool() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(ETH, List.of(
                    // A C2 (weETH) CARRY must NOT enter the blended FAMILY:ETH pool.
                    carryOutSymbol("carry:weeth", "wtx1", 1L, "WEETH", "1", "0", "3100", "3100"),
                    // A genuine C1 ETH CARRY DOES fold.
                    carryOut("carry:eth", "wtx2", 2L, "1", "0", "2000", "2000")
            ));

            session.applyEvent(List.of("wtx1", "wtx2"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                    session.blend(new BigDecimal("1"), new BigDecimal("2200"), new BigDecimal("2200"));

            // Parked = only the ETH CARRY (covered 1, basis 2000). weETH contributed $0.
            assertThat(blended.coveredQuantity()).isEqualByComparingTo("2");
            assertThat(blended.marketAvco().multiply(blended.coveredQuantity(), MC))
                    .isEqualByComparingTo("4200"); // 2200 spot + 2000 parked ETH only
        }

        @Test
        @DisplayName("§6.5 terminal CARRY corridor with no matching return closes to zero (bridge leak)")
        void terminalCarryWithNoReturnClosesToZero() {
            BlendedExposureAvcoSeriesBuilder.BlendedSeriesSession session = builder.newSession(
                    ETH,
                    List.of(carryOut("bridge:lifi:0x585aefbf", "txOut", 1L, "0.01371", "0", "9.07", "9.07")),
                    List.of(),
                    List.of(),
                    java.util.Map.of() // no open family-origin pool row for the leaked corridor
            );

            session.applyEvent(List.of("txOut"));
            BlendedExposureAvcoSeriesBuilder.BlendedPoint afterPark =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));
            assertThat(afterPark.coveredQuantity()).isEqualByComparingTo("1.01371");

            // No return, no pool row → generalized terminal clamp closes the bridge-leak corridor to 0.
            session.applyTerminalClamp();
            BlendedExposureAvcoSeriesBuilder.BlendedPoint terminal =
                    session.blend(new BigDecimal("1"), new BigDecimal("2000"), new BigDecimal("2000"));
            assertThat(terminal.coveredQuantity()).isEqualByComparingTo("1"); // leak closed, not lingering
            assertThat(terminal.marketAvco()).isEqualByComparingTo("2000");
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static AssetLedgerPoint carryOut(
            String correlationId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, "WETH", "wallet-a");
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        point.setQuantityDelta(new BigDecimal("-" + quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal("-" + netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint carryIn(
            String correlationId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, "WETH", "wallet-b");
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_IN);
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal(netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint carryOutSymbol(
            String correlationId, String txId, long replaySequence, String symbol,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, symbol, "wallet-a");
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        point.setQuantityDelta(new BigDecimal("-" + quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal("-" + netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint carryOutChain(
            String lifecycleChainId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(null, txId, replaySequence, "WETH", "wallet-a");
        point.setLifecycleChainId(lifecycleChainId);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        point.setQuantityDelta(new BigDecimal("-" + quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal("-" + netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint carryInChain(
            String lifecycleChainId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(null, txId, replaySequence, "WETH", "wallet-b");
        point.setLifecycleChainId(lifecycleChainId);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_IN);
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal(netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint crossFamilyAcquire(
            String correlationId, String txId, long replaySequence,
            String familyIdentity, String symbol, java.time.Instant blockTimestamp
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingFamilyIdentity(familyIdentity);
        point.setAssetSymbol(symbol);
        point.setCorrelationId(correlationId);
        point.setNormalizedTransactionId(txId);
        point.setReplaySequence(replaySequence);
        point.setTransactionIndex(0);
        point.setBlockTimestamp(blockTimestamp);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.ACQUIRE);
        return point;
    }

    private static AssetLedgerPoint receiptBurn(
            String correlationId, String txId, long replaySequence,
            String quantityBefore, String quantityAfter, java.time.Instant blockTimestamp
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingFamilyIdentity("FAMILY:LP_RECEIPT");
        point.setAssetSymbol("WETH-USDC-LP");
        point.setCorrelationId(correlationId);
        point.setNormalizedTransactionId(txId);
        point.setReplaySequence(replaySequence);
        point.setTransactionIndex(0);
        point.setBlockTimestamp(blockTimestamp);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        point.setQuantityBefore(new BigDecimal(quantityBefore));
        point.setQuantityAfter(new BigDecimal(quantityAfter));
        point.setQuantityDelta(new BigDecimal(quantityAfter).subtract(new BigDecimal(quantityBefore), MC));
        return point;
    }

    private static BlendedExposureAvcoSeriesBuilder.OrderingKey key(java.time.Instant blockTimestamp, long replaySequence) {
        return new BlendedExposureAvcoSeriesBuilder.OrderingKey(blockTimestamp, 0, replaySequence);
    }

    private static BlendedExposureAvcoSeriesBuilder.EthOriginHolding holding(
            String coveredQuantity, String marketBasisUsd, String netBasisUsd
    ) {
        return new BlendedExposureAvcoSeriesBuilder.EthOriginHolding(
                new BigDecimal(coveredQuantity),
                new BigDecimal(marketBasisUsd),
                new BigDecimal(netBasisUsd)
        );
    }

    private static AssetLedgerPoint reallocateOut(
            String correlationId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        return reallocateOut(correlationId, txId, replaySequence, quantityDelta, uncoveredQuantityDelta,
                costBasisDeltaUsd, netCostBasisDeltaUsd, "wallet-a");
    }

    private static AssetLedgerPoint reallocateOut(
            String correlationId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd,
            String walletAddress
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, "WETH", walletAddress);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        point.setQuantityDelta(new BigDecimal("-" + quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal("-" + netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint reallocateOutSymbol(
            String correlationId, String txId, long replaySequence, String symbol,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, symbol, "wallet-a");
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        point.setQuantityDelta(new BigDecimal("-" + quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal("-" + netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint reallocateIn(
            String correlationId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd
    ) {
        return reallocateIn(correlationId, txId, replaySequence, quantityDelta, uncoveredQuantityDelta,
                costBasisDeltaUsd, netCostBasisDeltaUsd, "wallet-a");
    }

    private static AssetLedgerPoint reallocateIn(
            String correlationId, String txId, long replaySequence,
            String quantityDelta, String uncoveredQuantityDelta, String costBasisDeltaUsd, String netCostBasisDeltaUsd,
            String walletAddress
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, "WETH", walletAddress);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setUncoveredQuantityDelta(new BigDecimal(uncoveredQuantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal(netCostBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint disposeOut(
            String correlationId, String txId, long replaySequence, String symbol,
            String quantityDelta, String costBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, symbol, "wallet-a");
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.DISPOSE);
        point.setQuantityDelta(new BigDecimal("-" + quantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal("-" + costBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint acquireIn(
            String correlationId, String txId, long replaySequence, String symbol,
            String quantityDelta, String costBasisDeltaUsd
    ) {
        AssetLedgerPoint point = base(correlationId, txId, replaySequence, symbol, "wallet-a");
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.ACQUIRE);
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        point.setNetCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        return point;
    }

    private static AssetLedgerPoint base(
            String correlationId, String txId, long replaySequence, String symbol, String walletAddress
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingFamilyIdentity(ETH);
        point.setAssetSymbol(symbol);
        point.setWalletAddress(walletAddress);
        point.setCorrelationId(correlationId);
        point.setNormalizedTransactionId(txId);
        point.setReplaySequence(replaySequence);
        return point;
    }
}
