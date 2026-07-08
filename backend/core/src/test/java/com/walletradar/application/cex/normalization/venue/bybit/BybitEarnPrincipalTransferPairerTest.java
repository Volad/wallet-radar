package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.normalization.config.BybitInternalTransferProperties;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FIX A (ADR-043) coverage for {@link BybitEarnPrincipalTransferPairer}: co-event sibling pairing
 * followed by equal-principal subscribe→redeem FIFO. The regression these guard against is the
 * greedy cross-event FIFO that gave the two legs of ONE subscribe DIFFERENT correlationIds (the
 * dropped-inbound quantity-conservation defect).
 */
@ExtendWith(MockitoExtension.class)
class BybitEarnPrincipalTransferPairerTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private BybitEarnPrincipalTransferPairer pairer;

    @BeforeEach
    void setUp() {
        pairer = new BybitEarnPrincipalTransferPairer(mongoOperations, normalizedTransactionRepository);
    }

    @Test
    void multiSourceCoEventBundleSharesOneCorrelationId() {
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LTC",
                "-0.01146338", Instant.parse("2025-04-01T10:00:00Z"));
        fundOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        NormalizedTransaction utaOut = earnPrincipal("uta-out", "BYBIT:42:UTA", "LTC",
                "-0.4995", Instant.parse("2025-04-01T10:00:01Z"));
        utaOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LTC",
                "0.51096338", Instant.parse("2025-04-01T10:00:02Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundOut, utaOut, earnIn));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(3);
        assertThat(fundOut.getCorrelationId()).isEqualTo(utaOut.getCorrelationId());
        assertThat(utaOut.getCorrelationId()).isEqualTo(earnIn.getCorrelationId());
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
        assertThat(fundOut.getContinuityCandidate()).isTrue();
        assertThat(utaOut.getContinuityCandidate()).isTrue();
        assertThat(earnIn.getContinuityCandidate()).isTrue();
    }

    @Test
    void coEventSiblingsOfOneSubscribeShareOneCorrelationId() {
        // Both legs of ONE subscribe fire at (nearly) the same blockTimestamp with equal principal.
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LINK",
                "17.1006", Instant.parse("2025-02-08T10:00:00Z"));
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LINK",
                "-17.1006", Instant.parse("2025-02-08T10:00:02Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, fundOut));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(2);
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
        assertThat(fundOut.getCorrelationId()).isEqualTo(earnIn.getCorrelationId());
        assertThat(earnIn.getContinuityCandidate()).isTrue();
        assertThat(fundOut.getContinuityCandidate()).isTrue();
    }

    @Test
    void twoDistinctSubscribesPairOwnSiblingsNotCrossEvents() {
        // Regression (LINK evidence): a Dec-16 subscribe and a Feb-08 OPEN subscribe. The pairer must
        // pair each event's OWN two legs (so both share one corrId), never cross-pair the Dec EARN-in
        // with the Feb FUND-out — the mis-pairing that dropped the open subscribe's :EARN inbound.
        NormalizedTransaction decEarnIn = earnPrincipal("dec-earn-in", "BYBIT:42:EARN", "LINK",
                "12.0986", Instant.parse("2024-12-16T10:00:00Z"));
        NormalizedTransaction decFundOut = earnPrincipal("dec-fund-out", "BYBIT:42:FUND", "LINK",
                "-12.0986", Instant.parse("2024-12-16T10:00:02Z"));
        NormalizedTransaction febEarnIn = earnPrincipal("feb-earn-in", "BYBIT:42:EARN", "LINK",
                "17.1006", Instant.parse("2025-02-08T10:00:00Z"));
        NormalizedTransaction febFundOut = earnPrincipal("feb-fund-out", "BYBIT:42:FUND", "LINK",
                "-17.1006", Instant.parse("2025-02-08T10:00:02Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(decEarnIn, decFundOut, febEarnIn, febFundOut));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(4);
        // Each event's siblings share one corrId.
        assertThat(decEarnIn.getCorrelationId()).isEqualTo(decFundOut.getCorrelationId());
        assertThat(febEarnIn.getCorrelationId()).isEqualTo(febFundOut.getCorrelationId());
        // The two distinct events carry DIFFERENT corrIds (no cross-event mis-pairing).
        assertThat(decEarnIn.getCorrelationId()).isNotEqualTo(febEarnIn.getCorrelationId());
    }

    @Test
    void ambiguousMultiSourceBundleIsLeftUnlinked() {
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LTC",
                "-0.01146338", Instant.parse("2025-04-01T10:00:00Z"));
        fundOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        NormalizedTransaction utaOut = earnPrincipal("uta-out", "BYBIT:42:UTA", "LTC",
                "-0.4994", Instant.parse("2025-04-01T10:00:01Z"));
        utaOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LTC",
                "0.51096338", Instant.parse("2025-04-01T10:00:02Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundOut, utaOut, earnIn));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isZero();
        assertThat(fundOut.getCorrelationId()).isNull();
        assertThat(utaOut.getCorrelationId()).isNull();
        assertThat(earnIn.getCorrelationId()).isNull();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void equalPrincipalMatcherRejectsUnequalCrossEventPair() {
        // The exact mis-pair the greedy 400-day FIFO produced: Dec-16 :EARN +12.0986 and Feb-08
        // :FUND -17.1006 — unequal principal, 54 days apart, from two different economic positions.
        // Equal-principal matching must reject it (no pairing, no write).
        NormalizedTransaction decEarnIn = earnPrincipal("dec-earn-in", "BYBIT:42:EARN", "LINK",
                "12.0986", Instant.parse("2024-12-16T10:00:00Z"));
        NormalizedTransaction febFundOut = earnPrincipal("feb-fund-out", "BYBIT:42:FUND", "LINK",
                "-17.1006", Instant.parse("2025-02-08T10:00:00Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(decEarnIn, febFundOut));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isZero();
        assertThat(decEarnIn.getCorrelationId()).isNull();
        assertThat(febFundOut.getCorrelationId()).isNull();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void equalPrincipalFifoPairsDriftedSiblingsBeyondCoEventWindow() {
        // When the two legs of one subscribe drift beyond the co-event window (e.g. minutes apart),
        // the equal-principal FIFO still pairs them because the principal matches exactly.
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LDO",
                "40.0", Instant.parse("2025-03-01T10:00:00Z"));
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LDO",
                "-40.0", Instant.parse("2025-03-01T10:10:00Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, fundOut));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(2);
        assertThat(earnIn.getCorrelationId()).isEqualTo(fundOut.getCorrelationId());
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
    }

    @Test
    void interestMismatchedSubscribeIsNotSplitIntoSyntheticReward() {
        // Issue 1 (ADR-043, replay #13b): the interest tolerance band is REMOVED because it was
        // net-harmful. A subscribe whose :EARN inbound exceeds the FUND/UTA principal by a small gap
        // (LINK evidence: :EARN +12.0986 vs :FUND -12.0198, diff 0.0788) is a CONSOLIDATION-NETTING
        // artifact, NOT a second interest stream — Bybit pays Flexible-Savings interest as explicit
        // daily REWARD_CLAIM legs. The unequal legs are therefore REJECTED (equal-principal only), no
        // synthetic REWARD_CLAIM is written, and the :EARN leg's quantity is left untouched.
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LINK",
                "12.0986", Instant.parse("2025-12-16T10:00:00Z"));
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LINK",
                "-12.0198", Instant.parse("2025-12-16T10:00:02Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, fundOut));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isZero();
        assertThat(earnIn.getCorrelationId()).isNull();
        assertThat(fundOut.getCorrelationId()).isNull();
        // The :EARN principal leg is NOT reduced — no principal is stolen and priced below-pool.
        assertThat(earnIn.getFlows().getFirst().getQuantityDelta()).isEqualByComparingTo("12.0986");
        // No synthetic REWARD_CLAIM row is ever synthesized (interest comes only from the exchange's
        // real daily reward legs), and with no pairing nothing is persisted.
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void unequalCrossEventPairRejectedWhenEarnSideLarger() {
        // Guard: the equal-principal protection rejects a genuinely-unequal cross-event pair even when
        // the earn side is LARGER — a 41% gap (17.1006 vs 12.0986) is not principal, so it must NOT be
        // paired (no pairing, no reward, no write).
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LINK",
                "17.1006", Instant.parse("2025-12-16T10:00:00Z"));
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LINK",
                "-12.0986", Instant.parse("2025-12-16T10:00:02Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, fundOut));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isZero();
        assertThat(earnIn.getCorrelationId()).isNull();
        assertThat(fundOut.getCorrelationId()).isNull();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    // ---- CB-2 (corridor basis conservation orphan fix) coverage --------------------------------

    @Test
    void coEventSiblingSelectionPrefersUnclaimedTrueSiblingOverAlreadyConsumedDecoy() {
        // CB-2 reproduction: an EARN leg, a same-quantity DECOY non-earn leg already claimed by an
        // unrelated BybitInternalTransferPairer pairing (which always runs first, per
        // BybitNormalizationService.processNextBatch), and the TRUE UTA sibling (still unclaimed) —
        // all inside CO_EVENT_MAX_SKEW. Root cause: pairCoEventSiblings scanned candidates in
        // time-sorted list order and stopped at the FIRST predicate match, so the already-claimed
        // decoy (appearing earlier in the corridor) won purely by scan order, permanently stranding
        // the true, still-unclaimed UTA sibling. The fix must select the unclaimed TRUE UTA row.
        Instant earnTs = Instant.parse("2025-06-01T10:00:00Z");
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "USDC",
                "30.1928", earnTs);

        NormalizedTransaction decoy = earnPrincipal("decoy-fund-out", "BYBIT:42:FUND", "USDC",
                "-30.1928", earnTs.plusSeconds(1));
        decoy.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        decoy.setCorrelationId(BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX + "decoy-already-paired");
        decoy.setContinuityCandidate(true);

        NormalizedTransaction trueUta = earnPrincipal("true-uta-out", "BYBIT:42:UTA", "USDC",
                "-30.1928", earnTs.plusSeconds(3));
        trueUta.setType(NormalizedTransactionType.INTERNAL_TRANSFER);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, decoy, trueUta));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(2);
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
        assertThat(trueUta.getCorrelationId()).isEqualTo(earnIn.getCorrelationId());
        assertThat(trueUta.getContinuityCandidate()).isTrue();
        // The decoy is left completely untouched — still carrying its original, unrelated correlation.
        assertThat(decoy.getCorrelationId())
                .isEqualTo(BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX + "decoy-already-paired");
    }

    @Test
    void reclaimingOnlyCandidateUtaSiblingLeavesFormerItPartnerUntouchedForNextPass() {
        // CB-2 "leg X" unwind check: when the TRUE UTA sibling is the ONLY viable co-event candidate
        // but already carries a stale bybit-it-pair-v1: correlation from a PRIOR
        // BybitInternalTransferPairer pass (originally paired with an unrelated "leg X" on a
        // different asset family), the earn-principal pairer must still reclaim it (the only
        // candidate is always selected — applyEarnPrincipalPair's existing unconditional overwrite
        // already handles this correctly). Leg X — the other half of that now-broken IT pairing — is
        // NOT part of this corridor (different asset family) and must be left completely untouched by
        // this pass, ready to be re-evaluated as a revisitable singleton by
        // BybitInternalTransferPairer on the next normalization batch.
        Instant earnTs = Instant.parse("2025-06-01T10:00:00Z");
        String staleItPairCorrId = BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX + "uta-and-legx";

        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LDO", "6.17", earnTs);

        NormalizedTransaction trueUta = earnPrincipal("true-uta-out", "BYBIT:42:UTA", "LDO", "-6.17",
                earnTs.plusSeconds(2));
        trueUta.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        trueUta.setCorrelationId(staleItPairCorrId);
        trueUta.setContinuityCandidate(true);

        // Leg X: the other half of trueUta's stale IT pairing, on a different asset family (ETH) —
        // never enters THIS corridor's candidate set, so this pairer must not touch it at all.
        NormalizedTransaction legX = earnPrincipal("leg-x", "BYBIT:42:FUND", "ETH", "1.0",
                earnTs.plusSeconds(2));
        legX.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        legX.setCorrelationId(staleItPairCorrId);
        legX.setContinuityCandidate(true);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, trueUta, legX));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(2);
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
        // The stale bybit-it-pair-v1: correlation is correctly overwritten on the reclaimed UTA leg.
        assertThat(trueUta.getCorrelationId()).isEqualTo(earnIn.getCorrelationId());
        // Leg X is left completely untouched — still carrying the now one-sided stale correlation id,
        // not silently mutated or excluded by this pairer.
        assertThat(legX.getCorrelationId()).isEqualTo(staleItPairCorrId);
        assertThat(legX.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void legXFromBrokenItPairingIsSelfHealedByInternalTransferPairerOnNextPass() {
        // Continuation of the "leg X" unwind scenario, simulating the NEXT normalization batch:
        // once the earn-principal pairer has reclaimed the true UTA sibling (previous test), leg X
        // becomes the SOLE holder of the stale bybit-it-pair-v1: id. BybitInternalTransferPairer's
        // loadSingletons() treats any continuityCandidate=true row whose correlationId now occurs
        // EXACTLY ONCE as re-pairable, so leg X is a valid candidate for repairSingletonPairs() on the
        // next pass and is not silently left as a permanent orphan.
        String staleItPairCorrId = BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX + "uta-and-legx";
        NormalizedTransaction legX = earnPrincipal("leg-x", "BYBIT:42:FUND", "ETH", "1.0",
                Instant.parse("2025-06-01T10:00:02Z"));
        legX.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        legX.setCorrelationId(staleItPairCorrId);
        legX.setContinuityCandidate(true);

        NormalizedTransaction freshOpposite = earnPrincipal("fresh-opposite", "BYBIT:42:UTA", "ETH", "-1.0",
                Instant.parse("2025-06-01T10:05:00Z"));
        freshOpposite.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        freshOpposite.setCorrelationId("bybit-econ-v1:fresh-opposite");
        freshOpposite.setContinuityCandidate(true);

        BybitInternalTransferProperties properties = new BybitInternalTransferProperties();
        BybitCrossUidUniversalTransferPairer crossUidPairer =
                new BybitCrossUidUniversalTransferPairer(mongoOperations, normalizedTransactionRepository);
        BybitInternalTransferPairer internalTransferPairer = new BybitInternalTransferPairer(
                mongoOperations, normalizedTransactionRepository, properties, crossUidPairer);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(legX, freshOpposite));

        int rewrites = internalTransferPairer.repairSingletonPairs();

        assertThat(rewrites).isEqualTo(2);
        assertThat(legX.getCorrelationId()).startsWith(BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX);
        assertThat(legX.getCorrelationId()).isNotEqualTo(staleItPairCorrId);
        assertThat(legX.getCorrelationId()).isEqualTo(freshOpposite.getCorrelationId());
    }

    @Test
    void unrelatedAlreadyPairedItRoundtripWithoutEarnSiblingIsLeftUntouched() {
        // CB-2 non-regression (critical): an unrelated, already-correct bybit-it-pair-v1: pair with NO
        // earn-principal sibling anywhere in its corridor must be left completely untouched by this
        // pass — the selection-preference fix must not "steal" or rewrite legs that have no earn leg
        // to pair with in the first place. A genuine, unrelated earn-principal corridor in the SAME
        // batch must still pair normally, proving the fix doesn't regress ordinary pairing either.
        String existingCorrId = BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX + "unrelated-pair";
        Instant ts = Instant.parse("2025-07-01T09:00:00Z");

        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:77:FUND", "LINK", "-5.0", ts);
        fundOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        fundOut.setCorrelationId(existingCorrId);
        fundOut.setContinuityCandidate(true);
        NormalizedTransaction utaIn = earnPrincipal("uta-in", "BYBIT:77:UTA", "LINK", "5.0", ts.plusSeconds(1));
        utaIn.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        utaIn.setCorrelationId(existingCorrId);
        utaIn.setContinuityCandidate(true);

        NormalizedTransaction earnIn = earnPrincipal("dot-earn-in", "BYBIT:77:EARN", "DOT", "40.0", ts);
        NormalizedTransaction fundOutDot = earnPrincipal("dot-fund-out", "BYBIT:77:FUND", "DOT", "-40.0",
                ts.plusSeconds(1));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundOut, utaIn, earnIn, fundOutDot));

        int rewrites = pairer.pairEarnPrincipalTransfers();

        assertThat(rewrites).isEqualTo(2);
        // The unrelated LINK IT pair is completely untouched.
        assertThat(fundOut.getCorrelationId()).isEqualTo(existingCorrId);
        assertThat(utaIn.getCorrelationId()).isEqualTo(existingCorrId);
        // The genuine DOT earn corridor pairs normally.
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
        assertThat(fundOutDot.getCorrelationId()).isEqualTo(earnIn.getCorrelationId());
    }

    // ---- end CB-2 coverage ------------------------------------------------------------------

    // ---- RC-9 D1 determinism fix coverage ----------------------------------------------------

    /**
     * RC-9 D1: {@code pairEarnPrincipalTransfers}'s {@code corridor.sort(...)} now ends in an
     * {@code idTiebreak()} tiebreak. All three bundle members share the IDENTICAL blockTimestamp
     * (a full 3-way sort tie), so their relative order inside {@code corridor} is a pure function
     * of the candidate set rather than the leaked Mongo scan order.
     */
    @Test
    void pairEarnPrincipalTransfersIsIdempotentAcrossReorderedScan() {
        Instant tie = Instant.parse("2026-06-06T10:00:00Z");
        NormalizedTransaction fundOut = earnPrincipal("fund-out", "BYBIT:42:FUND", "LTC", "-0.01146338", tie);
        fundOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        NormalizedTransaction utaOut = earnPrincipal("uta-out", "BYBIT:42:UTA", "LTC", "-0.4995", tie);
        utaOut.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "LTC", "0.51096338", tie);
        List<NormalizedTransaction> docs = List.of(fundOut, utaOut, earnIn);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnIn, fundOut, utaOut))
                .thenReturn(List.of(utaOut, earnIn, fundOut));

        BybitDeterminismTestSupport.assertReorderInvariant(
                docs, pairer::pairEarnPrincipalTransfers, pairer::pairEarnPrincipalTransfers);

        assertThat(fundOut.getCorrelationId()).isEqualTo(utaOut.getCorrelationId());
        assertThat(utaOut.getCorrelationId()).isEqualTo(earnIn.getCorrelationId());
        assertThat(earnIn.getCorrelationId())
                .startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX);
    }

    /**
     * RC-9 D1 (pairCoEventSiblings gap): two candidates ({@code candidateLow}/{@code candidateHigh})
     * are BOTH still unclaimed and equally eligible siblings for {@code earnIn} within the co-event
     * skew window — the corridor-sort fix alone only fixes iteration order, not this method's
     * separate "2+ still-unclaimed candidates" tie. {@code isPreferredSibling}'s fallback must
     * deterministically prefer the lowest {@code _id} regardless of scan order. Uses fresh
     * documents/mocks per permutation (not the shared repeat-invocation idempotency utility) since
     * a real second invocation would legitimately re-evaluate the now-claimed candidateLow under
     * the pre-existing (unrelated, unchanged) "prefer unclaimed over claimed" rule.
     */
    @Test
    void pairCoEventSiblingsPrefersLowestIdWhenBothCandidatesUnclaimedRegardlessOfScanOrder() {
        Instant earnTs = Instant.parse("2026-06-07T10:00:00Z");
        Instant siblingTs = earnTs.plusSeconds(1);
        List<List<Integer>> permutations = List.of(
                List.of(0, 1, 2),
                List.of(2, 1, 0),
                List.of(1, 2, 0)
        );

        for (List<Integer> order : permutations) {
            MongoOperations localMongoOperations = mock(MongoOperations.class);
            NormalizedTransactionRepository localRepository = mock(NormalizedTransactionRepository.class);

            NormalizedTransaction earnIn = earnPrincipal("earn-in", "BYBIT:42:EARN", "USDC", "30.1928", earnTs);
            NormalizedTransaction candidateLow = earnPrincipal(
                    "aaa-candidate-uta", "BYBIT:42:UTA", "USDC", "-30.1928", siblingTs);
            candidateLow.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            NormalizedTransaction candidateHigh = earnPrincipal(
                    "zzz-candidate-fund", "BYBIT:42:FUND", "USDC", "-30.1928", siblingTs);
            candidateHigh.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            List<NormalizedTransaction> docs = List.of(earnIn, candidateLow, candidateHigh);
            List<NormalizedTransaction> scanOrder = order.stream().map(docs::get).toList();

            when(localMongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                    .thenReturn(scanOrder);

            BybitEarnPrincipalTransferPairer localPairer =
                    new BybitEarnPrincipalTransferPairer(localMongoOperations, localRepository);
            localPairer.pairEarnPrincipalTransfers();

            assertThat(candidateLow.getCorrelationId())
                    .as("the lowest-_id unclaimed candidate must always win the sibling slot for order %s", order)
                    .isEqualTo(earnIn.getCorrelationId());
            assertThat(candidateHigh.getCorrelationId())
                    .as("the higher-_id unclaimed candidate must be left unpaired for order %s", order)
                    .isNull();
        }
    }

    // ---- end RC-9 D1 determinism fix coverage ------------------------------------------------

    private static NormalizedTransaction earnPrincipal(
            String id, String wallet, String asset, String qty, Instant blockTimestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        tx.setWalletAddress(wallet);
        tx.setContinuityCandidate(false);
        tx.setExcludedFromAccounting(false);
        tx.setBlockTimestamp(blockTimestamp);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
