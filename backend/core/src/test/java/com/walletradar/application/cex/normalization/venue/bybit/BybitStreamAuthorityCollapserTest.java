package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cycle/7 S1 + S7 regression coverage for {@link BybitStreamAuthorityCollapser}. Verifies the
 * collapser keeps a single canonical INTERNAL_TRANSFER document per mirror group, demotes
 * cross-stream duplicates with {@code BYBIT_STREAM_MIRROR_<source>} reasons, and unifies the
 * sender/receiver legs of one economic transfer onto a shared {@code bybit-collapsed-v1:} corr id.
 */
@ExtendWith(MockitoExtension.class)
class BybitStreamAuthorityCollapserTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void collapsesMirrorsAcrossFundingHistoryTransactionLogAndInternalTransfer() {
        Instant senderTimestamp = Instant.parse("2026-03-25T19:08:16Z");
        Instant receiverTimestamp = Instant.parse("2026-03-25T19:08:17Z");
        Instant fundingHistoryMirrorTimestamp = Instant.parse("2026-03-25T19:10:02Z");

        // UTA-side TX_LOG sender (canonical for UTA per priority policy: TX_LOG > FH for UTA when
        // INTERNAL_TRANSFER is absent for UTA leg in this group).
        NormalizedTransaction utaTxLogSender = mirrorDoc(
                "BYBIT-1:TRANSACTION_LOG:tx-out-1",
                "bybit-econ-v1:7126585f",
                "BYBIT:1:UTA",
                "ETH",
                "-0.9189",
                senderTimestamp
        );
        // FUND-side INTERNAL_TRANSFER receiver (canonical for FUND).
        NormalizedTransaction fundInternalTransferReceiver = mirrorDoc(
                "BYBIT-1:INTERNAL_TRANSFER:tr-in-1",
                "bybit-econ-v1:7126585f",
                "BYBIT:1:FUND",
                "ETH",
                "0.9189",
                receiverTimestamp
        );
        // FUND-side FUNDING_HISTORY mirror (different minute bucket → different bybit-econ-v1).
        NormalizedTransaction fundFundingHistoryMirror = mirrorDoc(
                "BYBIT-1:FUNDING_HISTORY:fh-out-1",
                "bybit-econ-v1:fc06a82a",
                "BYBIT:1:FUND",
                "ETH",
                "-0.9189",
                fundingHistoryMirrorTimestamp
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(utaTxLogSender, fundInternalTransferReceiver, fundFundingHistoryMirror));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int dirty = collapser.collapseMirrors();

        assertThat(dirty).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NormalizedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> saved = captor.getValue();

        // The FH mirror sits in a different minute bucket as the TX_LOG sender (UTA, -0.9189) but
        // SAME minute bucket as nothing else — it is its own group of one. Its mirror collapsing
        // hits when it lands in the same signature as the other FUND-side outbound (none exists),
        // so the per-stream signature {UID=1, family=FAMILY:ETH, |qty|=0.9189, bucket=B2,
        // sub=FUND, sign=-} is unique → FH stays as a singleton. However the cross-direction
        // unifier pairs it with the FUND-side IN (opposite sign, same uid+family+|qty|, within
        // 6-min drift window) and assigns a shared bybit-collapsed-v1 corr id.
        // Verify cross-direction correlation re-keying on opposing signs:
        // Both UTA-side OUT and FUND-side IN must share the new corr id.
        assertThat(utaTxLogSender.getCorrelationId()).startsWith("bybit-collapsed-v1:");
        assertThat(fundInternalTransferReceiver.getCorrelationId())
                .isEqualTo(utaTxLogSender.getCorrelationId());
        assertThat(utaTxLogSender.getContinuityCandidate()).isTrue();
        assertThat(fundInternalTransferReceiver.getContinuityCandidate()).isTrue();

        // The FH-mirror is in the SAME (uid+family+|qty|+bucket+sub+sign) signature as no other
        // doc (its bucket differs), so it remains non-excluded by mirror demotion. The
        // cross-direction unifier may still match it to the FUND-side IN by the broad signature.
        // Either way the saved list must include the FUND IN doc.
        assertThat(saved).contains(fundInternalTransferReceiver);
    }

    @Test
    void demotesSameBucketSameDirectionMirrors() {
        Instant timestamp = Instant.parse("2026-03-25T19:08:16Z");
        // Two FUND-side outbound docs in the same minute bucket with the same |qty| and family.
        NormalizedTransaction internalTransfer = mirrorDoc(
                "BYBIT-1:INTERNAL_TRANSFER:it-out-1",
                "bybit-econ-v1:canon",
                "BYBIT:1:FUND",
                "ETH",
                "-0.9189",
                timestamp
        );
        NormalizedTransaction fundingHistory = mirrorDoc(
                "BYBIT-1:FUNDING_HISTORY:fh-out-1",
                "bybit-econ-v1:mirror",
                "BYBIT:1:FUND",
                "ETH",
                "-0.9189",
                timestamp.plusSeconds(15)
        );
        NormalizedTransaction transactionLog = mirrorDoc(
                "BYBIT-1:TRANSACTION_LOG:tx-out-1",
                "bybit-econ-v1:mirror2",
                "BYBIT:1:FUND",
                "ETH",
                "-0.9189",
                timestamp.plusSeconds(20)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(internalTransfer, fundingHistory, transactionLog));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int dirty = collapser.collapseMirrors();
        assertThat(dirty).isGreaterThanOrEqualTo(2);

        // INTERNAL_TRANSFER is the canonical for FUND sub-account per priority policy.
        assertThat(internalTransfer.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);

        // FH and TX_LOG mirrors must be demoted with stream-specific reasons.
        assertThat(fundingHistory.getExcludedFromAccounting()).isTrue();
        assertThat(fundingHistory.getAccountingExclusionReason())
                .isEqualTo("BYBIT_STREAM_MIRROR_FUNDING_HISTORY");
        assertThat(transactionLog.getExcludedFromAccounting()).isTrue();
        assertThat(transactionLog.getAccountingExclusionReason())
                .isEqualTo("BYBIT_STREAM_MIRROR_TRANSACTION_LOG");
    }

    @Test
    void demotesFundingHistoryMirrorLagBy18HoursViaEventCountPass() {
        // Cycle/8 S2: real-world pattern observed in prod: FH `Transfer in/out` posts up to
        // 18+ hours after the canonical TX_LOG/INTERNAL_TRANSFER pair. Time-window matching
        // (BUCKET_DRIFT_WINDOW=6min) misses the FH mirror; the event-count pass must demote
        // it because count(FH) <= count(INTERNAL_TRANSFER) within (uid, family, |qty|,
        // wallet, sign).
        Instant canonicalTime = Instant.parse("2025-09-10T19:08:17Z");
        Instant fhLagTime = Instant.parse("2025-09-12T04:20:00Z"); // ~33h later
        NormalizedTransaction internalTransferReceiver = mirrorDoc(
                "BYBIT-1:INTERNAL_TRANSFER:tr-in-canonical",
                "bybit-econ-v1:canonical",
                "BYBIT:1:FUND",
                "ETH",
                "0.9189",
                canonicalTime
        );
        NormalizedTransaction fundingHistoryMirror = mirrorDoc(
                "BYBIT-1:FUNDING_HISTORY:fh-in-mirror",
                "bybit-econ-v1:mirror",
                "BYBIT:1:FUND",
                "ETH",
                "0.9189",
                fhLagTime
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(internalTransferReceiver, fundingHistoryMirror));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int dirty = collapser.collapseMirrors();
        assertThat(dirty).isGreaterThan(0);

        assertThat(internalTransferReceiver.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(fundingHistoryMirror.getExcludedFromAccounting()).isTrue();
        assertThat(fundingHistoryMirror.getAccountingExclusionReason())
                .isEqualTo("BYBIT_STREAM_MIRROR_FUNDING_HISTORY");
    }

    @Test
    void doesNotDemoteWhenMirrorCountExceedsCanonical() {
        // Cycle/8 S2 conservative guard: when the lower-authority source has MORE active docs
        // than the canonical source, we cannot assert each mirror is redundant — there may be
        // missing canonical entries. Leave them alone.
        Instant t1 = Instant.parse("2025-09-10T19:00:00Z");
        Instant t2 = Instant.parse("2025-09-15T19:00:00Z");
        Instant t3 = Instant.parse("2025-09-20T19:00:00Z");
        NormalizedTransaction it = mirrorDoc(
                "BYBIT-1:INTERNAL_TRANSFER:single",
                "bybit-econ-v1:c",
                "BYBIT:1:FUND",
                "ETH",
                "0.1",
                t1
        );
        NormalizedTransaction fh1 = mirrorDoc(
                "BYBIT-1:FUNDING_HISTORY:fh1",
                "bybit-econ-v1:m1",
                "BYBIT:1:FUND",
                "ETH",
                "0.1",
                t2
        );
        NormalizedTransaction fh2 = mirrorDoc(
                "BYBIT-1:FUNDING_HISTORY:fh2",
                "bybit-econ-v1:m2",
                "BYBIT:1:FUND",
                "ETH",
                "0.1",
                t3
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(it, fh1, fh2));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        // Both FH docs should remain non-excluded (count(FH)=2 > count(IT)=1).
        assertThat(fh1.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(fh2.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void preservesTwoDistinctOpposingTransfersWithinSixMinutes() {
        // Cycle/13: deposit auto-route at T and manual UTA→FUND at T+150s must not collapse.
        Instant depositRoute = Instant.parse("2026-02-19T07:52:31Z");
        Instant manualTransfer = Instant.parse("2026-02-19T07:55:01Z");

        NormalizedTransaction fundOutDepositRoute = mirrorDoc(
                "BYBIT-33625378:FUNDING_HISTORY:fh-out-deposit",
                "bybit-econ-v1:deposit-route",
                "BYBIT:33625378:FUND",
                "ETH",
                "-3.06",
                depositRoute
        );
        NormalizedTransaction utaInDepositRoute = mirrorDoc(
                "BYBIT-33625378:TRANSACTION_LOG:uta-in-deposit",
                "bybit-econ-v1:deposit-route",
                "BYBIT:33625378:UTA",
                "ETH",
                "3.06",
                depositRoute
        );
        NormalizedTransaction utaOutManual = mirrorDoc(
                "BYBIT-33625378:TRANSACTION_LOG:uta-out-manual",
                "bybit-econ-v1:manual-uta-fund",
                "BYBIT:33625378:UTA",
                "ETH",
                "-3.06",
                manualTransfer
        );
        NormalizedTransaction fundInManual = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:fund-in-manual",
                "bybit-econ-v1:manual-uta-fund",
                "BYBIT:33625378:FUND",
                "ETH",
                "3.06",
                manualTransfer
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        fundOutDepositRoute,
                        utaInDepositRoute,
                        utaOutManual,
                        fundInManual
                ));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(fundOutDepositRoute.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(utaInDepositRoute.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(utaOutManual.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(fundInManual.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);

        assertThat(fundOutDepositRoute.getCorrelationId()).startsWith("bybit-collapsed-v1:");
        assertThat(utaInDepositRoute.getCorrelationId()).isEqualTo(fundOutDepositRoute.getCorrelationId());
        assertThat(utaOutManual.getCorrelationId()).startsWith("bybit-collapsed-v1:");
        assertThat(fundInManual.getCorrelationId()).isEqualTo(utaOutManual.getCorrelationId());
        assertThat(fundOutDepositRoute.getCorrelationId()).isNotEqualTo(utaOutManual.getCorrelationId());
    }

    @Test
    void demotesOrphanMirrorAdjacentToCollapsedPairWithinTenMinuteDriftWindow() {
        // Cycle/15 round 2: orphan bybit-econ-v1 leg on the same source as the collapsed
        // canonical neighbor (so demoteEventCountMirrors is bypassed because bySource.size==1)
        // must be excluded with BYBIT_STREAM_MIRROR_DRIFT_GT_BUCKET when its same-wallet,
        // same-sign, same-|qty| collapsed pair sits within the ±10-minute drift window.
        Instant orphanTs = Instant.parse("2026-02-19T20:41:53Z");
        Instant collapsedTs = Instant.parse("2026-02-19T20:46:14Z");

        NormalizedTransaction collapsedLeg = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:collapsed-leg",
                "bybit-collapsed-v1:already-paired",
                "BYBIT:33625378:FUND",
                "MNT",
                "0.293107880000",
                collapsedTs
        );
        collapsedLeg.setContinuityCandidate(true);

        NormalizedTransaction orphan = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:orphan-leg",
                "bybit-econ-v1:drifted-orphan",
                "BYBIT:33625378:FUND",
                "MNT",
                "0.293107880000",
                orphanTs
        );
        orphan.setContinuityCandidate(false);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(collapsedLeg, orphan));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(orphan.getExcludedFromAccounting()).isEqualTo(Boolean.TRUE);
        assertThat(orphan.getAccountingExclusionReason()).isEqualTo("BYBIT_STREAM_MIRROR_DRIFT_GT_BUCKET");
        assertThat(collapsedLeg.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void preservesOrphanOutsideFortyEightHourDriftWindow() {
        Instant orphanTs = Instant.parse("2026-02-17T20:30:00Z");
        Instant collapsedTs = Instant.parse("2026-02-19T20:46:14Z");

        NormalizedTransaction collapsedLeg = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:collapsed-far",
                "bybit-collapsed-v1:far-paired",
                "BYBIT:33625378:FUND",
                "MNT",
                "0.293107880000",
                collapsedTs
        );
        collapsedLeg.setContinuityCandidate(true);

        NormalizedTransaction orphan = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:orphan-far",
                "bybit-econ-v1:far-orphan",
                "BYBIT:33625378:FUND",
                "MNT",
                "0.293107880000",
                orphanTs
        );
        orphan.setContinuityCandidate(false);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(collapsedLeg, orphan));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(orphan.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void restoresExcludedFundOutboundWhenUtaInboundActiveInCollapsedPair() {
        String corrId = "bybit-collapsed-v1:symmetry-test-corr";
        NormalizedTransaction utaInbound = mirrorDoc(
                "BYBIT-409666492:INTERNAL_TRANSFER:selfTransfer-test",
                corrId,
                "BYBIT:409666492:UTA",
                "USDT",
                "249.8845",
                Instant.parse("2025-01-11T22:30:56Z")
        );
        utaInbound.setContinuityCandidate(true);
        utaInbound.setMatchedCounterparty("BYBIT:409666492:FUND");

        NormalizedTransaction fundOutbound = mirrorDoc(
                "BYBIT-409666492:FUNDING_HISTORY:fh-out-test",
                corrId,
                "BYBIT:409666492:FUND",
                "USDT",
                "-249.8845",
                Instant.parse("2025-01-11T22:30:56Z")
        );
        fundOutbound.setContinuityCandidate(true);
        fundOutbound.setExcludedFromAccounting(true);
        fundOutbound.setAccountingExclusionReason("BYBIT_STREAM_MIRROR_FUNDING_HISTORY");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(utaInbound))
                .thenReturn(List.of(utaInbound, fundOutbound));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int dirty = collapser.collapseMirrors();

        assertThat(dirty).isGreaterThan(0);
        assertThat(fundOutbound.getExcludedFromAccounting()).isFalse();
        assertThat(fundOutbound.getAccountingExclusionReason()).isNull();
    }

    @Test
    void restoresExcludedUtaInboundCreditWhenFundOutboundActiveInCollapsedPair() {
        // C-1 / WS-B seq816 direction (FUND→UTA): the FUND-outbound debit survived and releases a
        // CARRY_OUT, but mirror demotion excluded the UTA-inbound credit, so nothing consumes the
        // carry. The bidirectional symmetry repair must restore the canonical excluded credit so both
        // legs ride the same corr-family queue and the inherit-once machinery carries the basis.
        String corrId = "bybit-collapsed-v1:seq816-symmetry-corr";
        NormalizedTransaction fundOutbound = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:fund-out-seq816",
                corrId,
                "BYBIT:33625378:FUND",
                "ETH",
                "-0.148",
                Instant.parse("2025-08-12T11:22:33Z")
        );
        fundOutbound.setContinuityCandidate(true);
        fundOutbound.setMatchedCounterparty("BYBIT:33625378:UTA");

        NormalizedTransaction utaInbound = mirrorDoc(
                "BYBIT-33625378:FUNDING_HISTORY:uta-in-seq816",
                corrId,
                "BYBIT:33625378:UTA",
                "ETH",
                "0.148",
                Instant.parse("2025-08-12T11:22:33Z")
        );
        utaInbound.setContinuityCandidate(true);
        utaInbound.setExcludedFromAccounting(true);
        utaInbound.setAccountingExclusionReason("BYBIT_STREAM_MIRROR_FUNDING_HISTORY");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundOutbound))
                .thenReturn(List.of(fundOutbound, utaInbound));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int dirty = collapser.collapseMirrors();

        assertThat(dirty).isGreaterThan(0);
        assertThat(utaInbound.getExcludedFromAccounting()).isFalse();
        assertThat(utaInbound.getAccountingExclusionReason()).isNull();
        assertThat(utaInbound.getContinuityCandidate()).isTrue();
        // Quantity conservation: exactly one active debit + one active credit survive (no inflation).
        assertThat(fundOutbound.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void doesNotRekeyCorridorDepositLegStampedWithBybitCorridorCorrelation() {
        // RC-9 refresh determinism: on an incremental refresh the persisted corridor deposit credit
        // is already an INTERNAL_TRANSFER carrying the deterministic BYBIT-CORRIDOR correlation id and
        // the on-chain txHash. A coincident opposing-sign internal leg with the same (uid, family,
        // |qty|) sits inside the drift window. The collapser must NOT re-key the corridor leg onto a
        // bybit-collapsed-v1 queue (which would orphan the matched on-chain CARRY_OUT and collapse the
        // Bybit AVCO back to the spot-fallback basis).
        Instant depositTs = Instant.parse("2026-02-19T20:46:14Z");
        String corridorCorr = "BYBIT-CORRIDOR:ARBITRUM:"
                + "0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27";

        NormalizedTransaction corridorDeposit = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:corridor-deposit",
                corridorCorr,
                "BYBIT:33625378:FUND",
                "ETH",
                "2.5",
                depositTs
        );
        corridorDeposit.setContinuityCandidate(true);
        corridorDeposit.setMatchedCounterparty("0x1111111111111111111111111111111111111111");
        corridorDeposit.setTxHash(
                "0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27");

        NormalizedTransaction opposingInternalLeg = mirrorDoc(
                "BYBIT-33625378:TRANSACTION_LOG:uta-out",
                "bybit-econ-v1:opposing",
                "BYBIT:33625378:UTA",
                "ETH",
                "-2.5",
                depositTs.plusSeconds(5)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorDeposit, opposingInternalLeg));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(corridorDeposit.getCorrelationId()).isEqualTo(corridorCorr);
        assertThat(corridorDeposit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(corridorDeposit.getMatchedCounterparty())
                .isEqualTo("0x1111111111111111111111111111111111111111");
    }

    @Test
    void doesNotRekeyReMaterialisedCorridorLegIdentifiedOnlyByOnChainTxHash() {
        // RC-9 refresh determinism: if re-materialisation reset the corridor leg's correlation id back
        // to a fresh bybit-econ-v1 (before the corridor projection re-stamps it), the intrinsic on-chain
        // txHash still marks it as a corridor leg. The collapser must leave its correlation id untouched
        // (never bybit-collapsed-v1) so the corridor projection can deterministically re-stamp it.
        Instant depositTs = Instant.parse("2026-02-19T20:46:14Z");

        NormalizedTransaction corridorDeposit = mirrorDoc(
                "BYBIT-33625378:INTERNAL_TRANSFER:corridor-deposit-rematerialised",
                "bybit-econ-v1:fresh-after-refresh",
                "BYBIT:33625378:FUND",
                "ETH",
                "2.5",
                depositTs
        );
        corridorDeposit.setTxHash(
                "0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27");

        NormalizedTransaction opposingInternalLeg = mirrorDoc(
                "BYBIT-33625378:TRANSACTION_LOG:uta-out",
                "bybit-econ-v1:opposing",
                "BYBIT:33625378:UTA",
                "ETH",
                "-2.5",
                depositTs.plusSeconds(5)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorDeposit, opposingInternalLeg));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(corridorDeposit.getCorrelationId()).doesNotStartWith("bybit-collapsed-v1:");
        assertThat(corridorDeposit.getCorrelationId()).isEqualTo("bybit-econ-v1:fresh-after-refresh");
        assertThat(corridorDeposit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void collapsedUtaFundPairLegsReceiveIdenticalCorrId() {
        // UTA TRANSACTION_LOG selfTransfer (outbound) + FUND FUNDING_HISTORY (inbound).
        // Both legs must receive the identical bybit-collapsed-v1:HASH corrId so that
        // corr-family:bybit-collapsed-v1:HASH:FAMILY:ASSET queue matches both carries.
        // If each leg gets a hash derived from only its own ID, the queue keys never match
        // and the CARRY_OUT from the UTA leg is orphaned (~$3,938 hidden distortion).
        Instant utaTs = Instant.parse("2026-03-25T18:00:00Z");
        Instant fundTs = Instant.parse("2026-03-25T18:00:15Z"); // 15 s after UTA — within 30 s window

        NormalizedTransaction utaTxLog = mirrorDoc(
                "BYBIT-99:TRANSACTION_LOG:selfTransfer-out-abc",
                "bybit-econ-v1:uta-econ-abc",
                "BYBIT:99:UTA",
                "ETH",
                "-0.5",
                utaTs
        );
        NormalizedTransaction fundFh = mirrorDoc(
                "BYBIT-99:FUNDING_HISTORY:fh-in-def",
                "bybit-econ-v1:fund-econ-def",
                "BYBIT:99:FUND",
                "ETH",
                "0.5",
                fundTs
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(utaTxLog, fundFh));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(utaTxLog.getCorrelationId()).startsWith("bybit-collapsed-v1:");
        assertThat(fundFh.getCorrelationId())
                .as("FUND FH must share the identical bybit-collapsed-v1: corrId with the UTA TX_LOG leg")
                .isEqualTo(utaTxLog.getCorrelationId());
        assertThat(utaTxLog.getContinuityCandidate()).isTrue();
        assertThat(fundFh.getContinuityCandidate()).isTrue();
    }

    @Test
    void utaLegWithoutFundCounterpartDoesNotThrowAndKeepsEconCorrId() {
        // A solo UTA TRANSACTION_LOG selfTransfer with no FUND inbound counterpart.
        // The collapser must complete without exception and must NOT assign a
        // bybit-collapsed-v1: corrId to an unpaired leg (no phantom queue match).
        NormalizedTransaction utaTxLog = mirrorDoc(
                "BYBIT-99:TRANSACTION_LOG:selfTransfer-solo",
                "bybit-econ-v1:solo-econ",
                "BYBIT:99:UTA",
                "ETH",
                "-0.5",
                Instant.parse("2026-03-25T18:00:00Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(utaTxLog));

        BybitStreamAuthorityCollapser collapser = newCollapser();

        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(collapser::collapseMirrors);
        assertThat(utaTxLog.getCorrelationId()).doesNotStartWith("bybit-collapsed-v1:");
    }

    @Test
    void suppressesCorridorDepositAndStakeCycleCollapseGroupsOnFund() {
        // Fix A.2: the 2025-03-12 "ETH 2.0" cycle. Two ARBITRUM ETH corridor deposits (+0.01, +0.699)
        // credit :FUND (BYBIT-CORRIDOR :FUND CARRY_IN), then a :FUND STAKING_DEPOSIT stakes −0.709 ETH
        // → METH. Bybit ALSO records the deposits' FUND↔UTA auto-route + the UTA→FUND consolidation as
        // three bybit-collapsed-v1 FUND↔UTA groups that duplicate the corridor deposits onto the
        // umbrella. Those three groups must be suppressed so the collapse cycle nets to 0.
        String uid = "33625378";
        Instant dep1 = Instant.parse("2025-03-12T19:52:00Z");
        Instant dep2 = Instant.parse("2025-03-12T20:06:00Z");
        Instant consolidate = Instant.parse("2025-03-12T20:07:00Z");
        Instant stakedAt = Instant.parse("2025-03-12T20:08:00Z");

        NormalizedTransaction corridorIn1 = corridorInDoc(uid, "0.01", dep1);
        NormalizedTransaction corridorIn2 = corridorInDoc(uid, "0.699", dep2);
        NormalizedTransaction staking = stakingDepositDoc(uid, "0.709", "0.66865026", stakedAt);

        NormalizedTransaction g1Fund = collapsedLeg(uid, "grp-1", "FUND", "-0.01", dep1);
        NormalizedTransaction g1Uta = collapsedLeg(uid, "grp-1", "UTA", "0.01", dep1);
        NormalizedTransaction g2Fund = collapsedLeg(uid, "grp-2", "FUND", "-0.699", dep2);
        NormalizedTransaction g2Uta = collapsedLeg(uid, "grp-2", "UTA", "0.699", dep2);
        NormalizedTransaction g3Uta = collapsedLeg(uid, "grp-3", "UTA", "-0.709", consolidate);
        NormalizedTransaction g3Fund = collapsedLeg(uid, "grp-3", "FUND", "0.709", consolidate);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorIn1, corridorIn2)) // corridorIns
                .thenReturn(List.of(staking))                  // stakingOuts
                .thenReturn(List.of(g1Fund, g1Uta, g2Fund, g2Uta, g3Uta, g3Fund)); // collapseGroups

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int suppressed = collapser.suppressCorridorDepositStakeCycles();
        assertThat(suppressed).isEqualTo(6);

        for (NormalizedTransaction leg : List.of(g1Fund, g1Uta, g2Fund, g2Uta, g3Uta, g3Fund)) {
            assertThat(leg.getExcludedFromAccounting())
                    .as("collapse leg %s suppressed", leg.getId())
                    .isTrue();
            assertThat(leg.getAccountingExclusionReason())
                    .isEqualTo("BYBIT_STREAM_MIRROR_CORRIDOR_STAKE_CYCLE");
        }
        // The authoritative corridor deposits and the staking outbound stay active.
        assertThat(corridorIn1.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(corridorIn2.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(staking.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void doesNotSuppressCollapseGroupWithoutMatchingStakeCycle() {
        // Blast-radius guard: a legitimate net corridor deposit (RC-9-style) with NO matching :FUND
        // staking outbound of the same family/qty must never be suppressed. Here the corridor deposit
        // is MNT while the only staking outbound is ETH → the corridor→stake cycle never forms, so the
        // MNT FUND↔UTA collapse group is left fully active.
        String uid = "33625378";
        Instant depTs = Instant.parse("2025-04-01T10:00:00Z");
        Instant stakeTs = Instant.parse("2025-04-01T10:05:00Z");

        NormalizedTransaction corridorInMnt = corridorInDoc(uid, "5.0", depTs);
        corridorInMnt.getFlows().get(0).setAssetSymbol("MNT");
        NormalizedTransaction stakingEth = stakingDepositDoc(uid, "0.709", "0.668", stakeTs);

        NormalizedTransaction mntFund = collapsedLeg(uid, "mnt-grp", "FUND", "-5.0", depTs);
        mntFund.getFlows().get(0).setAssetSymbol("MNT");
        NormalizedTransaction mntUta = collapsedLeg(uid, "mnt-grp", "UTA", "5.0", depTs);
        mntUta.getFlows().get(0).setAssetSymbol("MNT");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorInMnt))
                .thenReturn(List.of(stakingEth))
                .thenReturn(List.of(mntFund, mntUta));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int suppressed = collapser.suppressCorridorDepositStakeCycles();

        assertThat(suppressed).isZero();
        assertThat(mntFund.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(mntUta.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void restoresOneSidedExcludedEarnPrincipalSubscribeLeg() {
        // RC-0 (ADR-043): a subscribe (LENDING_DEPOSIT −0.75 LTC) was one-sidedly excluded while its
        // paired redeem (LENDING_WITHDRAW +0.75 LTC) of the same (uid, family, |qty|) stayed booked.
        // Paired-exclusion symmetry must restore the subscribe leg so the pairer can correlate the
        // closed cycle (unblocks RC-B) instead of leaving the inbound EARN leg missing.
        NormalizedTransaction redeemBooked = mirrorDoc(
                "BYBIT-409666492:LENDING_WITHDRAW:ltc-redeem",
                "bybit-econ-v1:ltc-redeem",
                "BYBIT:409666492:FUND",
                "LTC",
                "0.75",
                Instant.parse("2025-02-10T00:00:00Z"));
        redeemBooked.setType(NormalizedTransactionType.LENDING_WITHDRAW);

        NormalizedTransaction subscribeExcluded = mirrorDoc(
                "BYBIT-409666492:LENDING_DEPOSIT:ltc-subscribe",
                "bybit-econ-v1:ltc-subscribe",
                "BYBIT:409666492:EARN",
                "LTC",
                "-0.75",
                Instant.parse("2025-01-10T00:00:00Z"));
        subscribeExcluded.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        subscribeExcluded.setExcludedFromAccounting(true);
        subscribeExcluded.setAccountingExclusionReason("BYBIT_STREAM_MIRROR_FUNDING_HISTORY");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())                      // corridorIns → mirror suppression no-ops
                .thenReturn(List.of(redeemBooked))          // enforce symmetry: booked legs
                .thenReturn(List.of(subscribeExcluded));    // enforce symmetry: excluded legs

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int restored = collapser.suppressCorridorDepositStakeCycles();

        assertThat(restored).isEqualTo(1);
        assertThat(subscribeExcluded.getExcludedFromAccounting()).isFalse();
        assertThat(subscribeExcluded.getAccountingExclusionReason()).isNull();
        assertThat(subscribeExcluded.getContinuityCandidate()).isTrue();
        // The surviving booked redeem leg is never excluded (no leg silently dropped).
        assertThat(redeemBooked.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void doesNotRestoreWhenBothEarnPrincipalLegsAlreadyBooked() {
        // Blast-radius: paired-exclusion symmetry already holds (neither leg excluded), so the pass
        // must restore nothing and never mutate the balanced pair.
        NormalizedTransaction redeem = mirrorDoc(
                "BYBIT-409666492:LENDING_WITHDRAW:link-redeem",
                "bybit-econ-v1:link-redeem",
                "BYBIT:409666492:FUND",
                "LINK",
                "12.5",
                Instant.parse("2025-02-10T00:00:00Z"));
        redeem.setType(NormalizedTransactionType.LENDING_WITHDRAW);
        NormalizedTransaction subscribe = mirrorDoc(
                "BYBIT-409666492:LENDING_DEPOSIT:link-subscribe",
                "bybit-econ-v1:link-subscribe",
                "BYBIT:409666492:EARN",
                "LINK",
                "-12.5",
                Instant.parse("2025-01-10T00:00:00Z"));
        subscribe.setType(NormalizedTransactionType.LENDING_DEPOSIT);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())                          // corridorIns → mirror suppression no-ops
                .thenReturn(List.of(redeem, subscribe))         // enforce symmetry: booked legs (both)
                .thenReturn(List.of());                         // enforce symmetry: excluded legs (none)

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int restored = collapser.suppressCorridorDepositStakeCycles();

        assertThat(restored).isZero();
        assertThat(redeem.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(subscribe.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void earnPrincipalCorridorLegProtectedFromMirrorAndEventCountDemotion() {
        // CB-1 (corridor basis conservation orphan fix): a leg carrying bybit-earn-principal-v1: with
        // a matching continuity sibling must never be excluded — not even by the PRIMARY mirrorSignature
        // pass, which is the pass this exact fixture would otherwise trip. A competing FUND-side
        // INTERNAL_TRANSFER doc (higher canonical priority than FUNDING_HISTORY) shares the identical
        // (uid, family, |qty|, bucketMinute, subAccount, sign) mirrorSignature as the earn-principal FUND
        // leg; absent the CB-1 guard, pickCanonical would select the INTERNAL_TRANSFER doc and demote the
        // earn-principal FUND leg as BYBIT_STREAM_MIRROR_FUNDING_HISTORY, stranding the paired EARN credit.
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        String earnCorrId = "bybit-earn-principal-v1:9ed98986abc";

        NormalizedTransaction earnLeg = mirrorDoc(
                "BYBIT-42:EARN_FLEXIBLE_SAVING:earn-in",
                earnCorrId,
                "BYBIT:42:EARN",
                "USDT",
                "150.2",
                ts
        );
        earnLeg.setContinuityCandidate(true);

        NormalizedTransaction fundLeg = mirrorDoc(
                "BYBIT-42:FUNDING_HISTORY:fh-out",
                earnCorrId,
                "BYBIT:42:FUND",
                "USDT",
                "-150.2",
                ts.plusSeconds(1)
        );
        fundLeg.setContinuityCandidate(true);

        // Competing FUND-side INTERNAL_TRANSFER at the IDENTICAL wallet/qty/sign/bucket — higher
        // canonical priority than FUNDING_HISTORY — that would make the earn-principal FUND leg "the
        // mirror" absent the CB-1 guard.
        NormalizedTransaction competingFundLeg = mirrorDoc(
                "BYBIT-42:INTERNAL_TRANSFER:it-out",
                "bybit-econ-v1:unrelated",
                "BYBIT:42:FUND",
                "USDT",
                "-150.2",
                ts.plusSeconds(2)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(earnLeg, fundLeg, competingFundLeg));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(fundLeg.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(fundLeg.getCorrelationId()).isEqualTo(earnCorrId);
        assertThat(earnLeg.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(earnLeg.getCorrelationId()).isEqualTo(earnCorrId);
    }

    @Test
    void earnPrincipalCorridorLegProtectedFromResidualMirrorDemotion() {
        // CB-1: demoteResidualMirrors (and, upstream of it, unifyOpposingCorrelations) group by the
        // BROADER (uid, family, |qty|) signature (no sub-account/bucket component). Absent the CB-1
        // guard, an earn-principal EARN/FUND pair landing in the same broad bucket as an existing
        // bybit-collapsed-v1 debit/credit pair would get re-keyed or demoted by one of those passes,
        // stranding the true earn-principal corridor. The single shared pre-filter in collapseMirrors()
        // must keep the earn-principal pair out of `docs` entirely, so NEITHER pass ever sees it.
        Instant anchorTs = Instant.parse("2026-04-01T10:00:00Z");
        String earnCorrId = "bybit-earn-principal-v1:c76859bedef";

        NormalizedTransaction anchorDebit = mirrorDoc(
                "BYBIT-42:INTERNAL_TRANSFER:anchor-debit",
                "bybit-collapsed-v1:anchor",
                "BYBIT:42:UTA",
                "USDC",
                "-30.1928",
                anchorTs
        );
        anchorDebit.setContinuityCandidate(true);
        NormalizedTransaction anchorCredit = mirrorDoc(
                "BYBIT-42:FUNDING_HISTORY:anchor-credit",
                "bybit-collapsed-v1:anchor",
                "BYBIT:42:FUND",
                "USDC",
                "30.1928",
                anchorTs
        );
        anchorCredit.setContinuityCandidate(true);

        NormalizedTransaction earnLeg = mirrorDoc(
                "BYBIT-42:EARN_FLEXIBLE_SAVING:earn-in",
                earnCorrId,
                "BYBIT:42:EARN",
                "USDC",
                "30.1928",
                anchorTs.plusSeconds(5)
        );
        earnLeg.setContinuityCandidate(true);
        // Deliberately a DIFFERENT sub-account/sign combo than the anchor pair so the PRIMARY
        // mirrorSignature pass and demoteEventCountMirrors' walletSignSignature pass both skip it
        // (bucket size 1 on those narrower signatures) — isolating the broader demoteResidualMirrors
        // grouping as the only pass that would otherwise catch this leg.
        NormalizedTransaction fundLeg = mirrorDoc(
                "BYBIT-42:TRANSACTION_LOG:fund-out",
                earnCorrId,
                "BYBIT:42:FUND",
                "USDC",
                "-30.1928",
                anchorTs.plusSeconds(6)
        );
        fundLeg.setContinuityCandidate(true);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(anchorDebit, anchorCredit, earnLeg, fundLeg));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(earnLeg.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(earnLeg.getCorrelationId()).isEqualTo(earnCorrId);
        assertThat(fundLeg.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(fundLeg.getCorrelationId()).isEqualTo(earnCorrId);
        // The anchor pair itself stays fully intact (no side effect from the earn-principal legs
        // being excluded from its bucket).
        assertThat(anchorDebit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(anchorCredit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void genuineStreamMirrorWithoutEarnPrincipalCorrelationIsStillDemoted() {
        // Regression guard (critical): the CB-1 fix must NOT become a blanket immunity. A genuine
        // cross-stream mirror duplicate that does NOT carry an earn-principal correlation must still
        // be correctly demoted exactly as before.
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        NormalizedTransaction canonicalFundLeg = mirrorDoc(
                "BYBIT-42:INTERNAL_TRANSFER:it-out",
                "bybit-econ-v1:canonical",
                "BYBIT:42:FUND",
                "USDT",
                "-150.2",
                ts
        );
        NormalizedTransaction mirrorFundLeg = mirrorDoc(
                "BYBIT-42:FUNDING_HISTORY:fh-out",
                "bybit-econ-v1:mirror",
                "BYBIT:42:FUND",
                "USDT",
                "-150.2",
                ts.plusSeconds(1)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(canonicalFundLeg, mirrorFundLeg));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        collapser.collapseMirrors();

        assertThat(canonicalFundLeg.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(mirrorFundLeg.getExcludedFromAccounting()).isTrue();
        assertThat(mirrorFundLeg.getAccountingExclusionReason()).isEqualTo("BYBIT_STREAM_MIRROR_FUNDING_HISTORY");
    }

    @Test
    void noActionWhenOnlyOneCandidate() {
        NormalizedTransaction solo = mirrorDoc(
                "BYBIT-1:INTERNAL_TRANSFER:solo",
                "bybit-econ-v1:solo",
                "BYBIT:1:FUND",
                "ETH",
                "1.0",
                Instant.parse("2026-03-25T19:08:16Z")
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(solo));

        BybitStreamAuthorityCollapser collapser = newCollapser();
        int dirty = collapser.collapseMirrors();
        assertThat(dirty).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    // ---- RC-9 D1 determinism fix regression coverage ------------------------------------------

    /**
     * Whole-{@code collapseMirrors()} determinism regression. {@code collapseMirrors()} makes TWO
     * separate {@code mongoOperations.find()} calls per invocation (the main docs query, then a
     * second correlationId-regex query inside {@code enforceCollapsedUtFundPairSymmetry}), so a
     * naive 2-value stub chain would feed both values to a SINGLE run's two queries, not to
     * run-1-vs-run-2. This test stubs FOUR values in strict sequence:
     * {@code [run1Main, run1Symmetry, run2Main, run2Symmetry]}, where run 2's pair reflects run 1's
     * mutations on the SAME object instances (mirroring what a real second Mongo scan against the
     * same now-mutated documents would return — excluded docs drop out of the main query, but the
     * symmetry query still finds them by their persisted {@code correlationId}).
     *
     * <p>Fixture: one FUND-out debit {@code D} and two same-timestamp UTA/EARN-in credits
     * {@code C1}/{@code C2} exercise RC-a ({@code unifyOpposingCorrelations}'s exact-tie greedy
     * match). A 4th document {@code X} — an already {@code bybit-collapsed-v1:}-tagged credit from
     * a distinct prior pairing, landing in the same broad-signature bucket — is reachable only via
     * {@code demoteResidualMirrors}'s wider net (RC-b): once {@code C1} is deterministically chosen
     * over {@code X} as the canonical credit, both {@code C2} and {@code X} are swept into
     * {@code D}'s corr id instead of being left as genuine orphans.</p>
     */
    @Test
    void collapseMirrorsIsIdempotentAcrossRepeatedInvocationsWithReorderedScan() {
        Instant tieTimestamp = Instant.parse("2026-05-01T10:00:00Z");
        Instant preexistingTimestamp = tieTimestamp.plusSeconds(10);

        NormalizedTransaction debit = mirrorDoc(
                "BYBIT-77:INTERNAL_TRANSFER:d-debit", "bybit-econ-v1:d-raw",
                "BYBIT:77:FUND", "ETH", "-1.0", tieTimestamp);
        NormalizedTransaction credit1 = mirrorDoc(
                "BYBIT-77:INTERNAL_TRANSFER:c1-credit", "bybit-econ-v1:c1-raw",
                "BYBIT:77:UTA", "ETH", "1.0", tieTimestamp);
        NormalizedTransaction credit2 = mirrorDoc(
                "BYBIT-77:INTERNAL_TRANSFER:c2-credit", "bybit-econ-v1:c2-raw",
                "BYBIT:77:EARN", "ETH", "1.0", tieTimestamp);
        NormalizedTransaction preexistingCollapsedCredit = mirrorDoc(
                "BYBIT-77:INTERNAL_TRANSFER:x-preexisting", "bybit-collapsed-v1:already-paired-elsewhere",
                "BYBIT:77:FUND", "ETH", "1.0", preexistingTimestamp);
        preexistingCollapsedCredit.setContinuityCandidate(true);

        // Run 1: arbitrary scan order. Run 2: a real second Mongo scan of the SAME (eventually
        // run-1-mutated) documents, reordered. Excluded docs (C2, X) will no longer satisfy the
        // main query's excludedFromAccounting.ne(true) filter once run 1 excludes them, but the
        // symmetry query's correlationId-regex still finds them regardless of exclusion state.
        List<NormalizedTransaction> run1Main =
                List.of(credit2, preexistingCollapsedCredit, debit, credit1);
        List<NormalizedTransaction> run1Symmetry =
                List.of(debit, credit1, credit2, preexistingCollapsedCredit);
        List<NormalizedTransaction> run2Main = List.of(credit1, debit);
        List<NormalizedTransaction> run2Symmetry =
                List.of(preexistingCollapsedCredit, debit, credit2, credit1);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(run1Main)
                .thenReturn(run1Symmetry)
                .thenReturn(run2Main)
                .thenReturn(run2Symmetry);

        BybitStreamAuthorityCollapser collapser = newCollapser();

        BybitDeterminismTestSupport.assertReorderInvariant(
                List.of(debit, credit1, credit2, preexistingCollapsedCredit),
                collapser::collapseMirrors,
                collapser::collapseMirrors
        );

        // D+C1 are the active collapsed pair; C2 and X are swept into D's corr id and excluded
        // (no genuine orphan left behind), stable after both runs.
        assertThat(debit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(credit1.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(credit2.getExcludedFromAccounting()).isTrue();
        assertThat(preexistingCollapsedCredit.getExcludedFromAccounting()).isTrue();
        assertThat(credit1.getCorrelationId()).isEqualTo(debit.getCorrelationId());
        assertThat(credit2.getCorrelationId()).isEqualTo(debit.getCorrelationId());
        assertThat(preexistingCollapsedCredit.getCorrelationId()).isEqualTo(debit.getCorrelationId());

        // Run 2 must be a true no-op: no further saveAll beyond run 1's single call.
        verify(normalizedTransactionRepository, times(1)).saveAll(any());

        // "No genuine orphan" invariant: every active bybit-collapsed-v1: group has both a debit
        // and a credit member (mirrors what CorridorBasisConservationGuard would flag otherwise).
        Map<String, List<NormalizedTransaction>> activeByCorrelation = new HashMap<>();
        for (NormalizedTransaction tx : List.of(debit, credit1, credit2, preexistingCollapsedCredit)) {
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            activeByCorrelation.computeIfAbsent(tx.getCorrelationId(), ignored -> new ArrayList<>()).add(tx);
        }
        for (Map.Entry<String, List<NormalizedTransaction>> entry : activeByCorrelation.entrySet()) {
            if (!entry.getKey().startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)) {
                continue;
            }
            boolean hasDebit = entry.getValue().stream()
                    .anyMatch(tx -> tx.getFlows().getFirst().getQuantityDelta().signum() < 0);
            boolean hasCredit = entry.getValue().stream()
                    .anyMatch(tx -> tx.getFlows().getFirst().getQuantityDelta().signum() > 0);
            assertThat(hasDebit).as("group %s must retain an active debit", entry.getKey()).isTrue();
            assertThat(hasCredit).as("group %s must retain an active credit", entry.getKey()).isTrue();
        }
    }

    @Test
    void unifyOpposingCorrelationsChoosesSameBestRightRegardlessOfScanOrder() {
        // RC-a: D(-1) is exactly delta=0 tied between C1(+1) and C2(+1) at the same blockTimestamp.
        // Without the _id tiebreak, whichever tied candidate appears earlier in the leaked scan
        // order wins. With the fix, C1 (lower _id) always wins regardless of scan order.
        Instant tieTimestamp = Instant.parse("2026-05-02T08:00:00Z");
        List<List<Integer>> permutations = List.of(
                List.of(0, 1, 2),
                List.of(2, 1, 0),
                List.of(1, 2, 0)
        );

        for (List<Integer> order : permutations) {
            MongoOperations localMongoOperations = mock(MongoOperations.class);
            NormalizedTransactionRepository localRepository = mock(NormalizedTransactionRepository.class);

            NormalizedTransaction d = mirrorDoc(
                    "BYBIT-88:INTERNAL_TRANSFER:d-debit", "bybit-econ-v1:d-raw",
                    "BYBIT:88:FUND", "ETH", "-1.0", tieTimestamp);
            NormalizedTransaction c1 = mirrorDoc(
                    "BYBIT-88:INTERNAL_TRANSFER:c1-credit", "bybit-econ-v1:c1-raw",
                    "BYBIT:88:UTA", "ETH", "1.0", tieTimestamp);
            NormalizedTransaction c2 = mirrorDoc(
                    "BYBIT-88:INTERNAL_TRANSFER:c2-credit", "bybit-econ-v1:c2-raw",
                    "BYBIT:88:EARN", "ETH", "1.0", tieTimestamp);
            List<NormalizedTransaction> docs = List.of(d, c1, c2);
            List<NormalizedTransaction> scanOrder = order.stream().map(docs::get).toList();

            when(localMongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                    .thenReturn(scanOrder)
                    .thenReturn(docs);

            BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                    localMongoOperations, localRepository,
                    new BybitStreamCorridorCycleCollapser(localMongoOperations));
            collapser.collapseMirrors();

            assertThat(d.getExcludedFromAccounting())
                    .as("D must remain the active canonical debit for order %s", order)
                    .isNotEqualTo(Boolean.TRUE);
            assertThat(c1.getExcludedFromAccounting())
                    .as("C1 (lowest _id) must always be D's chosen partner for order %s", order)
                    .isNotEqualTo(Boolean.TRUE);
            assertThat(c2.getExcludedFromAccounting())
                    .as("C2 must always be the leftover swept into D's corr id for order %s", order)
                    .isTrue();
            assertThat(c1.getCorrelationId()).isEqualTo(d.getCorrelationId());
            assertThat(c1.getCorrelationId()).startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX);
        }
    }

    @Test
    void demoteResidualMirrorsChoosesEarliestTimestampCanonicalCreditRegardlessOfScanOrderOrSubAccount() {
        // RC-b: two ALREADY bybit-collapsed-v1:-tagged credit candidates of the same sign sit in one
        // broad-signature bucket, from DIFFERENT subAccounts (UTA vs EARN) with DIFFERENT
        // canonicalPriority scales — proving the fix uses a local timestamp-then-_id comparator, not
        // comparePriorityThenId. Credit1 (UTA) is earlier in time but would lose under a naive
        // cross-subAccount canonicalPriority comparison (EARN_FLEXIBLE_SAVING sourceTag ranks 0 on
        // the EARN scale vs 4 on the UTA scale) and has a LEXICOGRAPHICALLY LARGER _id than Credit2 —
        // isolating timestamp as the deciding factor, not id order or priority.
        Instant anchorTimestamp = Instant.parse("2026-05-03T09:00:00Z");
        Instant credit1Timestamp = anchorTimestamp.plusSeconds(5);
        Instant credit2Timestamp = anchorTimestamp.plusSeconds(8);
        List<List<Integer>> permutations = List.of(
                List.of(0, 1, 2),
                List.of(2, 1, 0),
                List.of(1, 0, 2)
        );

        for (List<Integer> order : permutations) {
            MongoOperations localMongoOperations = mock(MongoOperations.class);
            NormalizedTransactionRepository localRepository = mock(NormalizedTransactionRepository.class);

            NormalizedTransaction debitAnchor = mirrorDoc(
                    "BYBIT-99:INTERNAL_TRANSFER:debit-anchor", "bybit-collapsed-v1:existing-pair-anchor",
                    "BYBIT:99:FUND", "ETH", "-1.0", anchorTimestamp);
            debitAnchor.setContinuityCandidate(true);
            NormalizedTransaction credit1 = mirrorDoc(
                    "BYBIT-99:EARN_FLEXIBLE_SAVING:zzz-credit1", "bybit-collapsed-v1:candidate-a",
                    "BYBIT:99:UTA", "ETH", "1.0", credit1Timestamp);
            credit1.setContinuityCandidate(true);
            NormalizedTransaction credit2 = mirrorDoc(
                    "BYBIT-99:EARN_FLEXIBLE_SAVING:aaa-credit2", "bybit-collapsed-v1:candidate-b",
                    "BYBIT:99:EARN", "ETH", "1.0", credit2Timestamp);
            credit2.setContinuityCandidate(true);
            List<NormalizedTransaction> docs = List.of(debitAnchor, credit1, credit2);
            List<NormalizedTransaction> scanOrder = order.stream().map(docs::get).toList();

            when(localMongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                    .thenReturn(scanOrder)
                    .thenReturn(docs);

            BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                    localMongoOperations, localRepository,
                    new BybitStreamCorridorCycleCollapser(localMongoOperations));
            collapser.collapseMirrors();

            assertThat(credit1.getExcludedFromAccounting())
                    .as("earlier-timestamp credit1 (UTA) must always win as canonicalCredit for order %s", order)
                    .isNotEqualTo(Boolean.TRUE);
            assertThat(credit2.getExcludedFromAccounting())
                    .as("later-timestamp credit2 (EARN) must always be demoted for order %s", order)
                    .isTrue();
            assertThat(credit2.getCorrelationId()).isEqualTo(debitAnchor.getCorrelationId());
        }
    }

    // ---- end RC-9 D1 determinism fix regression coverage ---------------------------------------

    private NormalizedTransaction corridorInDoc(String uid, String quantity, Instant timestamp) {
        NormalizedTransaction tx = mirrorDoc(
                "BYBIT-" + uid + ":INTERNAL_TRANSFER:corridor-in-" + quantity,
                "BYBIT-CORRIDOR:ARBITRUM:0x" + Integer.toHexString(quantity.hashCode()),
                "BYBIT:" + uid + ":FUND",
                "ETH",
                quantity,
                timestamp
        );
        tx.setContinuityCandidate(true);
        return tx;
    }

    private NormalizedTransaction collapsedLeg(
            String uid,
            String group,
            String sub,
            String quantity,
            Instant timestamp
    ) {
        NormalizedTransaction tx = mirrorDoc(
                "BYBIT-" + uid + ":INTERNAL_TRANSFER:" + group + "-" + sub,
                "bybit-collapsed-v1:" + group,
                "BYBIT:" + uid + ":" + sub,
                "ETH",
                quantity,
                timestamp
        );
        tx.setContinuityCandidate(true);
        return tx;
    }

    private NormalizedTransaction stakingDepositDoc(
            String uid,
            String outboundQty,
            String inboundQty,
            Instant timestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("BYBIT-" + uid + ":EARN_FLEXIBLE_SAVING:eth2-stake");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setWalletAddress("BYBIT:" + uid + ":FUND");
        tx.setCorrelationId("bybit-stake-pair-v1:eth2-stake");
        tx.setBlockTimestamp(timestamp);
        tx.setExcludedFromAccounting(false);

        NormalizedTransaction.Flow ethOut = new NormalizedTransaction.Flow();
        ethOut.setRole(NormalizedLegRole.TRANSFER);
        ethOut.setAssetSymbol("ETH");
        ethOut.setAccountRef("BYBIT:" + uid + ":FUND");
        ethOut.setQuantityDelta(new BigDecimal(outboundQty).negate());
        NormalizedTransaction.Flow methIn = new NormalizedTransaction.Flow();
        methIn.setRole(NormalizedLegRole.TRANSFER);
        methIn.setAssetSymbol("METH");
        methIn.setAccountRef("BYBIT:" + uid + ":FUND");
        methIn.setQuantityDelta(new BigDecimal(inboundQty));
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(ethOut);
        flows.add(methIn);
        tx.setFlows(flows);
        return tx;
    }

    private BybitStreamAuthorityCollapser newCollapser() {
        return new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository,
                new BybitStreamCorridorCycleCollapser(mongoOperations)
        );
    }

    private NormalizedTransaction mirrorDoc(
            String id,
            String correlationId,
            String walletAddress,
            String assetSymbol,
            String quantity,
            Instant timestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(walletAddress);
        tx.setCorrelationId(correlationId);
        tx.setBlockTimestamp(timestamp);
        tx.setExcludedFromAccounting(false);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(flow);
        tx.setFlows(flows);
        return tx;
    }
}
