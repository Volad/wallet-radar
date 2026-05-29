package com.walletradar.ingestion.pipeline.bybit;

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
