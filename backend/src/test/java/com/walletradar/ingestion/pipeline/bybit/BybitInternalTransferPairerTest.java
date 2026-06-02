package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.config.BybitInternalTransferProperties;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cycle/6 A2 + Cycle/12 regression coverage for {@link BybitInternalTransferPairer}.
 */
@ExtendWith(MockitoExtension.class)
class BybitInternalTransferPairerTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private BybitInternalTransferProperties properties;
    private BybitInternalTransferPairer pairer;

    @BeforeEach
    void setUp() {
        properties = new BybitInternalTransferProperties();
        pairer = new BybitInternalTransferPairer(mongoOperations, normalizedTransactionRepository, properties);
    }

    @Test
    void pairsOppositeSignSingletonsWithinTwoHourWindow() {
        NormalizedTransaction senderLeg = singletonInternalTransfer(
                "BYBIT-1:FUNDING_HISTORY:out-1",
                "corr-singleton-out-1",
                "BYBIT:42:UTA",
                "DOGE",
                "-150.50",
                Instant.parse("2026-03-25T12:00:00Z")
        );
        NormalizedTransaction receiverLeg = singletonInternalTransfer(
                "BYBIT-1:FUNDING_HISTORY:in-1",
                "corr-singleton-in-1",
                "BYBIT:42:FUND",
                "DOGE",
                "150.50",
                Instant.parse("2026-03-25T12:00:30Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(senderLeg, receiverLeg));

        int rewrites = pairer.repairSingletonPairs();

        assertThat(rewrites).isEqualTo(2);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(2);
        String canonicalCorrelation = dirty.get(0).getCorrelationId();
        assertThat(canonicalCorrelation).startsWith(BybitInternalTransferPairer.PAIR_CORRELATION_PREFIX);
        for (NormalizedTransaction tx : dirty) {
            assertThat(tx.getCorrelationId()).isEqualTo(canonicalCorrelation);
            assertThat(tx.getContinuityCandidate()).isTrue();
            assertThat(tx.getMatchedCounterparty()).isNotNull();
            assertThat(tx.getMatchedCounterparty()).isNotEqualTo(tx.getWalletAddress());
        }
    }

    @Test
    void pairBundles_threeLegNearZeroFormsBundle() {
        Instant base = Instant.parse("2026-03-25T12:00:00Z");
        NormalizedTransaction uta = singletonInternalTransfer("uta", "c1", "BYBIT:42:UTA", "LDO", "-27.85", base);
        NormalizedTransaction fund = singletonInternalTransfer("fund", "c2", "BYBIT:42:FUND", "LDO", "-0.05",
                base.plusSeconds(10));
        NormalizedTransaction earn = singletonInternalTransfer("earn", "c3", "BYBIT:42:EARN", "LDO", "27.90",
                base.plusSeconds(20));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(uta, fund, earn));

        int rewrites = pairer.pairBundles();

        assertThat(rewrites).isEqualTo(3);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(3);
        String bundleCorr = dirty.get(0).getCorrelationId();
        assertThat(bundleCorr).startsWith(BybitInternalTransferPairer.BUNDLE_CORRELATION_PREFIX);
        assertThat(dirty).allMatch(tx -> tx.getCorrelationId().equals(bundleCorr));
    }

    @Test
    void pairBundles_fourLegBundle() {
        Instant base = Instant.parse("2026-03-25T12:00:00Z");
        List<NormalizedTransaction> legs = List.of(
                singletonInternalTransfer("a", "c1", "BYBIT:42:UTA", "LDO", "-10", base),
                singletonInternalTransfer("b", "c2", "BYBIT:42:FUND", "LDO", "-5", base.plusSeconds(5)),
                singletonInternalTransfer("c", "c3", "BYBIT:42:EARN", "LDO", "14", base.plusSeconds(10)),
                singletonInternalTransfer("d", "c4", "BYBIT:42:FUND", "LDO", "1", base.plusSeconds(15))
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(legs);

        int rewrites = pairer.pairBundles();

        assertThat(rewrites).isEqualTo(4);
    }

    @Test
    void pairBundles_rejectsLargeResidual() {
        Instant base = Instant.parse("2026-03-25T12:00:00Z");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        singletonInternalTransfer("out", "c1", "BYBIT:42:UTA", "LDO", "-10", base),
                        singletonInternalTransfer("in", "c2", "BYBIT:42:EARN", "LDO", "12", base.plusSeconds(5))
                ));

        int rewrites = pairer.pairBundles();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void pairBundles_rejectsOnlyOneSide() {
        Instant base = Instant.parse("2026-03-25T12:00:00Z");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        singletonInternalTransfer("a", "c1", "BYBIT:42:UTA", "LDO", "-10", base),
                        singletonInternalTransfer("b", "c2", "BYBIT:42:UTA", "LDO", "-5", base.plusSeconds(5)),
                        singletonInternalTransfer("c", "c3", "BYBIT:42:UTA", "LDO", "-1", base.plusSeconds(10))
                ));

        int rewrites = pairer.pairBundles();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void pairSameWalletRoundTrips_pairsExactOpposite() {
        Instant outTime = Instant.parse("2026-01-01T12:00:00Z");
        Instant inTime = Instant.parse("2026-01-03T12:00:00Z");
        NormalizedTransaction outLeg = singletonInternalTransfer("out", "c1", "BYBIT:42:FUND", "LDO", "-68.665", outTime);
        NormalizedTransaction inLeg = singletonInternalTransfer("in", "c2", "BYBIT:42:FUND", "LDO", "68.665", inTime);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outLeg, inLeg));

        int rewrites = pairer.pairSameWalletRoundTrips();

        assertThat(rewrites).isEqualTo(2);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty.get(0).getCorrelationId()).startsWith(BybitInternalTransferPairer.ROUNDTRIP_CORRELATION_PREFIX);
        assertThat(dirty.get(0).getCorrelationId()).isEqualTo(dirty.get(1).getCorrelationId());
    }

    @Test
    void pairSameWalletRoundTrips_skipsApproximateQty() {
        Instant base = Instant.parse("2026-01-01T12:00:00Z");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        singletonInternalTransfer("out", "c1", "BYBIT:42:FUND", "LDO", "-68.665", base),
                        singletonInternalTransfer("in", "c2", "BYBIT:42:FUND", "LDO", "68.000", base.plusSeconds(3600))
                ));

        int rewrites = pairer.pairSameWalletRoundTrips();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void pairSameWalletRoundTrips_respectsWindow() {
        Instant outTime = Instant.parse("2026-01-01T12:00:00Z");
        Instant inTime = Instant.parse("2026-01-25T12:00:00Z");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        singletonInternalTransfer("out", "c1", "BYBIT:42:FUND", "LDO", "-10", outTime),
                        singletonInternalTransfer("in", "c2", "BYBIT:42:FUND", "LDO", "10", inTime)
                ));

        int rewrites = pairer.pairSameWalletRoundTrips();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void ignoresSingletonsWithSameSign() {
        NormalizedTransaction one = singletonInternalTransfer(
                "id-1",
                "corr-1",
                "BYBIT:1:UTA",
                "USDT",
                "100",
                Instant.parse("2026-03-25T12:00:00Z")
        );
        NormalizedTransaction two = singletonInternalTransfer(
                "id-2",
                "corr-2",
                "BYBIT:1:FUND",
                "USDT",
                "100",
                Instant.parse("2026-03-25T12:01:00Z")
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(one, two));

        int rewrites = pairer.repairSingletonPairs();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void ignoresSingletonsOutsideTwoHourWindow() {
        NormalizedTransaction one = singletonInternalTransfer(
                "id-1",
                "corr-out-1",
                "BYBIT:1:UTA",
                "USDT",
                "-100",
                Instant.parse("2026-03-25T08:00:00Z")
        );
        NormalizedTransaction two = singletonInternalTransfer(
                "id-2",
                "corr-in-1",
                "BYBIT:1:FUND",
                "USDT",
                "100",
                Instant.parse("2026-03-25T11:00:00Z")
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(one, two));

        int rewrites = pairer.repairSingletonPairs();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void skipsAlreadyPairedDocuments() {
        NormalizedTransaction leftPaired = singletonInternalTransfer(
                "paired-out",
                "shared-corr",
                "BYBIT:1:UTA",
                "USDT",
                "-100",
                Instant.parse("2026-03-25T12:00:00Z")
        );
        NormalizedTransaction rightPaired = singletonInternalTransfer(
                "paired-in",
                "shared-corr",
                "BYBIT:1:FUND",
                "USDT",
                "100",
                Instant.parse("2026-03-25T12:01:00Z")
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(leftPaired, rightPaired));

        int rewrites = pairer.repairSingletonPairs();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void pairsLegsWithQtyDriftAtFourthDecimal() {
        NormalizedTransaction fundOut = singletonInternalTransfer(
                "fh-out",
                "bybit-econ-v1:orphan-out",
                "BYBIT:33625378:FUND",
                "USDT",
                "-207.188384",
                Instant.parse("2025-09-25T06:59:09Z")
        );
        NormalizedTransaction utaIn = singletonInternalTransfer(
                "it-in",
                "bybit-econ-v1:orphan-in",
                "BYBIT:33625378:UTA",
                "USDT",
                "207.1883",
                Instant.parse("2025-09-25T06:59:12Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundOut, utaIn));

        int rewrites = pairer.pairBroadEconomicFingerprint();

        assertThat(rewrites).isEqualTo(2);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty.get(0).getCorrelationId()).startsWith(BybitInternalTransferPairer.REKEYED_CORRELATION_PREFIX);
        assertThat(dirty.get(0).getCorrelationId()).isEqualTo(dirty.get(1).getCorrelationId());
    }

    @Test
    void pairsLegsAcrossMinuteBucketDrift() {
        NormalizedTransaction outLeg = singletonInternalTransfer(
                "out",
                "bybit-econ-v1:a",
                "BYBIT:33625378:UTA",
                "SOL",
                "-0.3",
                Instant.parse("2025-02-04T07:45:32Z")
        );
        NormalizedTransaction inLeg = singletonInternalTransfer(
                "in",
                "bybit-econ-v1:b",
                "BYBIT:33625378:FUND",
                "SOL",
                "0.3",
                Instant.parse("2025-02-04T07:50:32Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outLeg, inLeg));

        int rewrites = pairer.pairBroadEconomicFingerprint();

        assertThat(rewrites).isEqualTo(2);
    }

    @Test
    void dedupSameSignMirrorsOnSameWallet() {
        Instant base = Instant.parse("2026-03-25T12:00:00Z");
        NormalizedTransaction first = singletonInternalTransfer(
                "mirror-1",
                "bybit-econ-v1:m1",
                "BYBIT:42:FUND",
                "USDT",
                "-100",
                base
        );
        NormalizedTransaction second = singletonInternalTransfer(
                "mirror-2",
                "bybit-econ-v1:m2",
                "BYBIT:42:FUND",
                "USDT",
                "-100",
                base.plusSeconds(30)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(first, second));

        int demoted = pairer.dedupSameSignMirrors();

        assertThat(demoted).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(1);
        assertThat(dirty.getFirst().getExcludedFromAccounting()).isTrue();
        assertThat(dirty.getFirst().getAccountingExclusionReason())
                .isEqualTo(BybitInternalTransferPairer.SAME_SIGN_MIRROR_REASON);
    }

    @Test
    void dedupSameSignMirrorsSkipsFa001OnChainWithdrawAnchor() {
        Instant base = Instant.parse("2025-02-07T08:18:11Z");
        NormalizedTransaction keeper = singletonInternalTransfer(
                "mirror-keeper",
                "bybit-econ-v1:keeper",
                "BYBIT:33625378:FUND",
                "ETH",
                "-0.00399",
                base
        );
        NormalizedTransaction corridorAnchor = singletonInternalTransfer(
                "mirror-corridor",
                "BYBIT-CORRIDOR:ARBITRUM:0xabc",
                "BYBIT:33625378:FUND",
                "ETH",
                "-0.00399",
                base.plusSeconds(55)
        );
        corridorAnchor.setTxHash("0xabc");
        corridorAnchor.setNetworkId(NetworkId.ARBITRUM);
        corridorAnchor.setMatchedCounterparty("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(keeper, corridorAnchor));

        int demoted = pairer.dedupSameSignMirrors();

        assertThat(demoted).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    void doesNotPairAcrossDifferentSubAccountsWithSameSign() {
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        singletonInternalTransfer("a", "c1", "BYBIT:42:UTA", "USDT", "100",
                                Instant.parse("2026-03-25T12:00:00Z")),
                        singletonInternalTransfer("b", "c2", "BYBIT:42:FUND", "USDT", "100",
                                Instant.parse("2026-03-25T12:01:00Z"))
                ));

        int rewrites = pairer.pairBroadEconomicFingerprint();

        assertThat(rewrites).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void pairsDemotedEconOrphanExternalTransfers() {
        NormalizedTransaction fundOut = demotedExternalTransfer(
                "dem-out",
                "bybit-econ-v1:dem-out",
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                NormalizedLegRole.SELL,
                "BYBIT:33625378:FUND",
                "USDT",
                "-207.188384",
                Instant.parse("2025-09-25T06:59:09Z")
        );
        NormalizedTransaction utaIn = demotedExternalTransfer(
                "dem-in",
                "bybit-econ-v1:dem-in",
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                NormalizedLegRole.BUY,
                "BYBIT:33625378:UTA",
                "USDT",
                "207.1883",
                Instant.parse("2025-09-25T06:59:12Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundOut, utaIn));

        int rewrites = pairer.pairDemotedEconOrphans();

        assertThat(rewrites).isEqualTo(2);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).allSatisfy(tx -> {
            assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
            assertThat(tx.getContinuityCandidate()).isTrue();
            assertThat(tx.getCorrelationId()).startsWith(BybitInternalTransferPairer.REKEYED_CORRELATION_PREFIX);
        });
        assertThat(dirty.get(0).getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void repairAllRunsAllPasses() {
        AtomicInteger call = new AtomicInteger();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenAnswer(invocation -> {
                    int n = call.incrementAndGet();
                    if (n == 2) {
                        return List.of(
                                singletonInternalTransfer("out", "c1", "BYBIT:1:UTA", "USDT", "-1", Instant.now()),
                                singletonInternalTransfer("in", "c2", "BYBIT:1:FUND", "USDT", "1", Instant.now())
                        );
                    }
                    return List.of();
                });

        int total = pairer.repairAll();

        assertThat(total).isEqualTo(2);
    }

    // ---- B-CROSS-UID tests -------------------------------------------------------

    @Test
    void crossUidFundingHistoryOutboundLinkedWhenUniversalTransferExcluded() {
        String uuid = "c0ffee11-dead-beef-cafe-012345678901";
        Instant ts = Instant.parse("2025-12-01T14:30:00Z");

        // Orphaned inbound on destination UID — positive BTC, no correlationId yet
        NormalizedTransaction inboundLeg = universalTransferLeg(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":in",
                null,
                "BYBIT:33625378:FUND",
                "BTC",
                "0.001",
                ts,
                false
        );

        // Excluded UNIVERSAL_TRANSFER outbound on source UID
        NormalizedTransaction excludedPartner = universalTransferLeg(
                "BYBIT-516601508:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":out",
                null,
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.001",
                ts,
                true
        );

        // FUNDING_HISTORY outbound on source UID — no correlationId, not excluded
        NormalizedTransaction fundingOutbound = fundingHistoryLeg(
                "BYBIT-516601508:FUNDING_HISTORY:fh-out-1",
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.001",
                ts
        );

        // Call 1: main query → inbound only
        // Call 2: findExcludedCrossUidPartner → excluded partner
        // Call 3: loadFundingHistoryCandidates → FUNDING_HISTORY outbound
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inboundLeg))
                .thenReturn(List.of(excludedPartner))
                .thenReturn(List.of(fundingOutbound));

        int rewrites = pairer.pairCrossUidUniversalTransfers();

        assertThat(rewrites).isEqualTo(2);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(2);

        String expectedCorrId = BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX + uuid;
        assertThat(inboundLeg.getCorrelationId()).isEqualTo(expectedCorrId);
        assertThat(inboundLeg.getContinuityCandidate()).isTrue();

        assertThat(fundingOutbound.getCorrelationId()).isEqualTo(expectedCorrId);
        // continuityCandidate=true is required so ReplayPendingTransferKeyFactory uses the
        // "corr-family:" queue key, enabling the inbound CARRY_IN to receive the carry basis.
        assertThat(fundingOutbound.getContinuityCandidate()).isTrue();
        assertThat(fundingOutbound.getMatchedCounterparty()).isEqualTo("BYBIT:33625378:FUND");
        assertThat(fundingOutbound.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    @Test
    void crossUidFundingHistoryLinkedOnRetryWhenLonerAlreadyHasCrossUidCorrId() {
        // Simulates the race condition where pairCrossUidUniversalTransfers() runs during
        // per-wallet normalization of the destination UID before the source UID's FUNDING_HISTORY
        // records are in the DB. The loner gets its corrId but the FUNDING_HISTORY outbound is
        // missed. On the second call (LinkingBatchProcessor) the loner already has the corrId —
        // the pairer must still link the FUNDING_HISTORY outbound using the existing corrId.
        String uuid = "c0ffee22-dead-beef-cafe-012345678902";
        String existingCorrId = BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX + uuid;
        Instant ts = Instant.parse("2025-12-12T10:15:47Z");

        // Inbound already has corrId from the first (incomplete) run
        NormalizedTransaction inboundLeg = universalTransferLeg(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":in",
                existingCorrId,
                "BYBIT:33625378:FUND",
                "BTC",
                "0.0007972",
                ts,
                false
        );

        // Excluded outbound on source UID — already has corrId too
        NormalizedTransaction excludedPartner = universalTransferLeg(
                "BYBIT-516601508:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":out",
                existingCorrId,
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.0007972",
                ts,
                true
        );

        // FUNDING_HISTORY outbound still has no corrId (was not yet in DB on first run)
        NormalizedTransaction fundingOutbound = fundingHistoryLeg(
                "BYBIT-516601508:FUNDING_HISTORY:fh-btc-retry",
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.0007972",
                ts.minusSeconds(1)
        );

        // Call 1: main query → inbound only (excl=false)
        // Call 2: findExcludedCrossUidPartner → excluded partner
        // Call 3: loadFundingHistoryCandidates → FUNDING_HISTORY outbound
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inboundLeg))
                .thenReturn(List.of(excludedPartner))
                .thenReturn(List.of(fundingOutbound));

        int rewrites = pairer.pairCrossUidUniversalTransfers();

        // Only the FUNDING_HISTORY outbound is new — loner must not be modified again
        assertThat(rewrites).isEqualTo(1);
        assertThat(fundingOutbound.getCorrelationId()).isEqualTo(existingCorrId);
        assertThat(fundingOutbound.getContinuityCandidate()).isTrue();
        assertThat(fundingOutbound.getMatchedCounterparty()).isEqualTo("BYBIT:33625378:FUND");
        // Inbound was not re-written
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).containsExactly(fundingOutbound);
    }

    @Test
    void crossUidSelfTransferGuardPreventsFalsePositive() {
        // Verifies that transactions linked by pairCrossUidUniversalTransfers carry the
        // CROSS_UID_CORRELATION_PREFIX prefix. ReplayDispatcher.isBybitSelfTransfer() returns
        // false whenever corrId starts with this prefix, preventing cross-UID CARRY_OUT from
        // being incorrectly skipped as a same-wallet no-op.
        String uuid = "deadbeef-0000-1111-2222-333344445555";
        Instant ts = Instant.parse("2025-12-01T10:00:00Z");

        NormalizedTransaction inboundLeg = universalTransferLeg(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":in",
                null,
                "BYBIT:33625378:FUND",
                "ETH",
                "0.5",
                ts,
                false
        );
        NormalizedTransaction excludedPartner = universalTransferLeg(
                "BYBIT-516601508:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":out",
                null,
                "BYBIT:516601508:FUND",
                "ETH",
                "-0.5",
                ts,
                true
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inboundLeg))
                .thenReturn(List.of(excludedPartner))
                .thenReturn(List.of());

        pairer.pairCrossUidUniversalTransfers();

        // The resulting corrId must start with the prefix that isBybitSelfTransfer guards against
        assertThat(inboundLeg.getCorrelationId())
                .startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX);
        assertThat(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)
                .isEqualTo("bybit-cross-uid-v1:");
    }

    @Test
    void crossUidAssetSymbolFilterPreventsWrongMatch() {
        String uuid = "aaaabbbb-cccc-dddd-eeee-ffffaaaabbbb";
        Instant ts = Instant.parse("2025-11-15T09:00:00Z");

        NormalizedTransaction btcInbound = universalTransferLeg(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":in",
                null,
                "BYBIT:33625378:FUND",
                "BTC",
                "0.05",
                ts,
                false
        );
        NormalizedTransaction excludedPartner = universalTransferLeg(
                "BYBIT-516601508:UNIVERSAL_TRANSFER:uni_trans_" + uuid + ":out",
                null,
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.05",
                ts,
                true
        );

        // Two FUNDING_HISTORY outbounds from the source UID: one BTC (correct), one ETH (wrong)
        NormalizedTransaction btcOutbound = fundingHistoryLeg(
                "BYBIT-516601508:FUNDING_HISTORY:btc-out",
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.05",
                ts
        );
        NormalizedTransaction ethOutbound = fundingHistoryLeg(
                "BYBIT-516601508:FUNDING_HISTORY:eth-out",
                "BYBIT:516601508:FUND",
                "ETH",
                "-0.5",
                ts
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(btcInbound))
                .thenReturn(List.of(excludedPartner))
                .thenReturn(List.of(btcOutbound, ethOutbound));

        int rewrites = pairer.pairCrossUidUniversalTransfers();

        assertThat(rewrites).isEqualTo(2);
        // Only BTC outbound must be linked; ETH outbound must remain untouched
        assertThat(btcOutbound.getCorrelationId())
                .isEqualTo(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX + uuid);
        assertThat(ethOutbound.getCorrelationId()).isNull();
    }

    private NormalizedTransaction fundingHistoryLeg(
            String id,
            String walletRef,
            String asset,
            String qty,
            Instant blockTimestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(null);
        tx.setContinuityCandidate(false);
        tx.setWalletAddress(walletRef);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setExcludedFromAccounting(false);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    // ---- end B-CROSS-UID tests ---------------------------------------------------

    private NormalizedTransaction demotedExternalTransfer(
            String id,
            String correlationId,
            NormalizedTransactionType type,
            NormalizedLegRole role,
            String walletRef,
            String asset,
            String qty,
            Instant blockTimestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(type);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(false);
        tx.setWalletAddress(walletRef);
        tx.setBlockTimestamp(blockTimestamp);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    @Test
    void pairsCrossUidBtcBetween516601508And33625378() {
        NormalizedTransaction outLeg = singletonInternalTransfer(
                "BYBIT-516601508:UNIVERSAL_TRANSFER:uni_trans_b7c3e1a2-f4d5-6789-abcd-ef1234567890:out",
                "bybit-econ-v1:btc-out",
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.05",
                Instant.parse("2025-12-01T14:30:00Z")
        );
        NormalizedTransaction inLeg = singletonInternalTransfer(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_b7c3e1a2-f4d5-6789-abcd-ef1234567890:in",
                "bybit-econ-v1:btc-in",
                "BYBIT:33625378:FUND",
                "BTC",
                "0.05",
                Instant.parse("2025-12-01T14:30:01Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outLeg, inLeg));

        int rewrites = pairer.pairCrossUidUniversalTransfers();

        assertThat(rewrites).isEqualTo(2);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(2);
        // Both sides must be marked continuity candidates so replay produces CARRY_OUT / CARRY_IN
        assertThat(dirty).allMatch(tx -> Boolean.TRUE.equals(tx.getContinuityCandidate()));
        // Both sides share a canonical cross-UID correlation id
        String corrId = dirty.get(0).getCorrelationId();
        assertThat(corrId).startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX);
        assertThat(dirty.get(1).getCorrelationId()).isEqualTo(corrId);
        // matchedCounterparty cross-wired
        NormalizedTransaction out = dirty.stream()
                .filter(tx -> "BYBIT:516601508:FUND".equals(tx.getWalletAddress()))
                .findFirst()
                .orElseThrow();
        NormalizedTransaction in = dirty.stream()
                .filter(tx -> "BYBIT:33625378:FUND".equals(tx.getWalletAddress()))
                .findFirst()
                .orElseThrow();
        assertThat(out.getMatchedCounterparty()).isEqualTo("BYBIT:33625378:FUND");
        assertThat(in.getMatchedCounterparty()).isEqualTo("BYBIT:516601508:FUND");
        // Flows must have been demoted to TRANSFER so replay engine sees CARRY_OUT (negative) / CARRY_IN (positive)
        assertThat(out.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(in.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void pairsCrossUidUniversalTransferLegsByEmbeddedTransferUuid() {
        NormalizedTransaction mainOut = singletonInternalTransfer(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:out",
                "bybit-econ-v1:main-out",
                "BYBIT:33625378:FUND",
                "MNT",
                "-100",
                Instant.parse("2026-03-25T12:00:00Z")
        );
        NormalizedTransaction subIn = singletonInternalTransfer(
                "BYBIT-99999999:UNIVERSAL_TRANSFER:uni_trans_aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:in",
                "bybit-econ-v1:sub-in",
                "BYBIT:99999999:FUND",
                "MNT",
                "100",
                Instant.parse("2026-03-25T12:00:01Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(mainOut, subIn));

        int rewrites = pairer.pairCrossUidUniversalTransfers();

        assertThat(rewrites).isEqualTo(2);
        assertThat(mainOut.getCorrelationId()).startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX);
        assertThat(subIn.getCorrelationId()).isEqualTo(mainOut.getCorrelationId());
    }

    @Test
    void pairsCrossUidInboundWhenOutboundIsExcluded() {
        String uuid = "866903d7-d743-11f0-b806-825d86cb1b52";

        // Inbound leg — not excluded, no correlation yet, positive qty
        NormalizedTransaction inboundLeg = universalTransferLeg(
                "BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_" + uuid + "_516601508",
                null,
                "BYBIT:33625378:FUND",
                "BTC",
                "0.0007972",
                Instant.parse("2025-12-01T14:30:00Z"),
                false
        );

        // Outbound leg — excluded from accounting (to avoid double-counting with FUNDING_HISTORY),
        // negative qty, different Bybit UID
        NormalizedTransaction excludedOutboundLeg = universalTransferLeg(
                "BYBIT-516601508:UNIVERSAL_TRANSFER:uni_trans_" + uuid + "_516601508",
                "bybit-cross-uid-v1:" + uuid,
                "BYBIT:516601508:FUND",
                "BTC",
                "-0.0007972",
                Instant.parse("2025-12-01T14:30:00Z"),
                true
        );

        // Call 1: main query (excludedFromAccounting.ne(true)) returns only the inbound
        // Call 2: findExcludedCrossUidPartner returns the excluded outbound
        // Call 3: loadFundingHistoryCandidates returns empty (no FUNDING_HISTORY records here)
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inboundLeg))
                .thenReturn(List.of(excludedOutboundLeg))
                .thenReturn(List.of());

        int rewrites = pairer.pairCrossUidUniversalTransfers();

        assertThat(rewrites).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(1);

        NormalizedTransaction paired = dirty.getFirst();
        assertThat(paired.getCorrelationId())
                .isEqualTo(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX + uuid);
        assertThat(paired.getContinuityCandidate()).isTrue();
        assertThat(paired.getMatchedCounterparty()).isEqualTo("BYBIT:516601508:FUND");

        // Excluded outbound must remain unchanged
        assertThat(excludedOutboundLeg.getCorrelationId()).isEqualTo("bybit-cross-uid-v1:" + uuid);
    }

    private NormalizedTransaction universalTransferLeg(
            String id,
            String correlationId,
            String walletRef,
            String asset,
            String qty,
            Instant blockTimestamp,
            boolean excludedFromAccounting
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(false);
        tx.setWalletAddress(walletRef);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setExcludedFromAccounting(excludedFromAccounting);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction singletonInternalTransfer(
            String id,
            String correlationId,
            String walletRef,
            String asset,
            String qty,
            Instant blockTimestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setWalletAddress(walletRef);
        tx.setBlockTimestamp(blockTimestamp);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
