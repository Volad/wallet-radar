package com.walletradar.ingestion.pipeline.bybit;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
        int dirty = collapser.collapseMirrors();

        assertThat(dirty).isGreaterThan(0);
        assertThat(fundOutbound.getExcludedFromAccounting()).isFalse();
        assertThat(fundOutbound.getAccountingExclusionReason()).isNull();
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

        BybitStreamAuthorityCollapser collapser = new BybitStreamAuthorityCollapser(
                mongoOperations,
                normalizedTransactionRepository
        );
        int dirty = collapser.collapseMirrors();
        assertThat(dirty).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
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
