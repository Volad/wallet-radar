package com.walletradar.ingestion.pipeline.bybit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.integration.bybit.BybitLiveBalance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitEarnSubPoolConservationGuardTest {

    @Mock
    private MongoOperations mongoOperations;

    private ListAppender<ILoggingEvent> appender;
    private Logger guardLogger;

    @BeforeEach
    void setUp() {
        guardLogger = (Logger) LoggerFactory.getLogger(BybitEarnSubPoolConservationGuard.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
        guardLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        guardLogger.detachAppender(appender);
    }

    @Test
    void reportsTrueCombinedLedgerQtyFromInMemoryPoints_andDoesNotFalselyWarnWhenMatched() {
        // Regression: the guard previously re-queried asset_ledger_points (still the prior run's
        // state during the sweep), reporting ledgerQty=0 for every asset. It must instead compute
        // the combined ledger qty from the in-memory points, so a matched asset does not warn.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of(
                liveBalance("BYBIT-33625378", "XRP", null, null, new BigDecimal("4.0533"))
        ));

        guard.evaluate(List.of(
                point("BYBIT:33625378", "XRP", "4.0533", 1L)
        ));

        assertThat(warningsContaining("BYBIT_ASSET_TOTAL_MISMATCH")).isEmpty();
    }

    @Test
    void warnsWithTrueCombinedLedgerQtyForGenuinePhantom() {
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of(
                liveBalance("BYBIT-33625378", "MNT", null, null, new BigDecimal("109.87"))
        ));

        // umbrella 2245.4 + :EARN 104.9 = 2350.3 ledger vs 109.87 live → genuine phantom.
        guard.evaluate(List.of(
                point("BYBIT:33625378", "MNT", "2245.4", 10L),
                point("BYBIT:33625378:EARN", "MNT", "104.9", 11L)
        ));

        List<ILoggingEvent> warns = warningsContaining("BYBIT_ASSET_TOTAL_MISMATCH");
        assertThat(warns).hasSize(1);
        String message = warns.getFirst().getFormattedMessage();
        assertThat(message).contains("ledgerQty=2350.30000000");
        assertThat(message).contains("liveQty=109.87000000");
    }

    @Test
    void fixedEarnAssetsReconcile_doNotWarn() {
        // RC-A controls (ADR-043): LINK / LDO / ONDO earn round-trips conserve basis and quantity,
        // so the combined per-asset ledger total equals the live fund+earn+uta total. None must warn.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of(
                liveBalance("BYBIT-33625378", "LINK", null, new BigDecimal("12.5"), new BigDecimal("2.5")),
                liveBalance("BYBIT-33625378", "LDO", null, new BigDecimal("40"), null),
                liveBalance("BYBIT-33625378", "ONDO", null, null, new BigDecimal("500"))
        ));

        guard.evaluate(List.of(
                point("BYBIT:33625378", "LINK", "2.5", 20L),
                point("BYBIT:33625378:EARN", "LINK", "12.5", 21L),
                point("BYBIT:33625378:EARN", "LDO", "40", 22L),
                point("BYBIT:33625378", "ONDO", "500", 23L)
        ));

        assertThat(warningsContaining("BYBIT_ASSET_TOTAL_MISMATCH")).isEmpty();
        assertThat(warningsContaining("BYBIT_SUBPOOL_BASIS_ORPHAN")).isEmpty();
    }

    @Test
    void ltcInboundLegMaterialized_reconcilesAcrossEarnAndUmbrella() {
        // RC-B control (ADR-043): LTC total 1.26107 = :EARN 0.75 + umbrella 0.511(07). With the
        // inbound EARN leg materialized (exclusion symmetry), the combined total matches live and the
        // basis lands on the EARN principal (qty>0), not the qty=0 ghost that produced the $103.8 AVCO.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of(
                liveBalance("BYBIT-33625378", "LTC", null, new BigDecimal("0.75"), new BigDecimal("0.51107"))
        ));

        guard.evaluate(List.of(
                point("BYBIT:33625378", "LTC", "0.51107", 30L),
                pointWithBasis("BYBIT:33625378:EARN", "LTC", "0.75", "37.0", 31L)
        ));

        assertThat(warningsContaining("BYBIT_ASSET_TOTAL_MISMATCH")).isEmpty();
        assertThat(warningsContaining("BYBIT_SUBPOOL_BASIS_ORPHAN")).isEmpty();
    }

    @Test
    void subPoolBasisOrphanGhostRaisesWarn() {
        // The pre-fix LTC ghost: basis ($41.54) parked on :EARN with qty=0 — the
        // quantityDelta=0 / costBasisDelta>0 signature the combined-total check cannot see.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of(
                liveBalance("BYBIT-33625378", "LTC", null, new BigDecimal("0"), new BigDecimal("0.51107"))
        ));

        guard.evaluate(List.of(
                point("BYBIT:33625378", "LTC", "0.51107", 40L),
                pointWithBasis("BYBIT:33625378:EARN", "LTC", "0", "41.54", 41L)
        ));

        List<ILoggingEvent> orphans = warningsContaining("BYBIT_SUBPOOL_BASIS_ORPHAN");
        assertThat(orphans).hasSize(1);
        assertThat(orphans.getFirst().getFormattedMessage()).contains("subPool=EARN");
        assertThat(orphans.getFirst().getFormattedMessage()).contains("ledgerBasisUsd=41.54");
    }

    @Test
    void conservedInternalLegsDoNotWarnCorridorQtyImbalance() {
        // FIX C (ADR-043): an OPEN subscribe (FUND-out + EARN-in, both EARN_FLEXIBLE_SAVING) nets to
        // zero internal Δqty, so the per-family quantity invariant must NOT warn.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of());

        guard.evaluate(List.of(
                internalPoint("BYBIT:33625378", "LINK", "EARN_FLEXIBLE_SAVING", "-17.1006", 50L),
                internalPoint("BYBIT:33625378:EARN", "LINK", "EARN_FLEXIBLE_SAVING", "17.1006", 51L)
        ));

        assertThat(warningsContaining("CORRIDOR_QTY_IMBALANCE")).isEmpty();
    }

    @Test
    void droppedInboundLegRaisesCorridorQtyImbalance() {
        // FIX C (ADR-043): the exact regression — the subscribe FUND-out is recorded but its paired
        // EARN-in leg was dropped (queue-key divergence). The signed internal Δqty no longer nets to
        // zero, so the invariant surfaces CORRIDOR_QTY_IMBALANCE instead of silently vanishing.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of());

        guard.evaluate(List.of(
                internalPoint("BYBIT:33625378", "LINK", "EARN_FLEXIBLE_SAVING", "-17.1006", 60L)
        ));

        List<ILoggingEvent> breaches = warningsContaining("CORRIDOR_QTY_IMBALANCE");
        assertThat(breaches).hasSize(1);
        String message = breaches.getFirst().getFormattedMessage();
        assertThat(message).contains("key=33625378|LINK");
        assertThat(message).contains("internalQtyDelta=-17.10060000");
    }

    @Test
    void externalTransferLegsAreExcludedFromInternalQtyInvariant() {
        // Cross-venue deposits/withdrawals have their counterpart outside the family, so an
        // EXTERNAL_TRANSFER_IN with no offsetting internal leg must NOT trip the invariant.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of());

        guard.evaluate(List.of(
                internalPoint("BYBIT:33625378", "DOGE", "EXTERNAL_TRANSFER_IN", "150.591", 70L)
        ));

        assertThat(warningsContaining("CORRIDOR_QTY_IMBALANCE")).isEmpty();
    }

    @Test
    void crossSubInternalReallocationNetsToZeroUnderSessionKey() {
        // Issue 4 (ADR-043): an internal reallocation legitimately spans two sub-UIDs of the SAME
        // master (BYBIT:33625378 −150.591 DOGE ↔ BYBIT:421325298 +150.591 DOGE). Keyed by sub-UID
        // each side looked one-sided (false CORRIDOR_QTY_IMBALANCE); keyed by the shared
        // accountingUniverseId they net to zero → no warn.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of());

        guard.evaluate(List.of(
                internalPointInUniverse("BYBIT:33625378", "DOGE", "INTERNAL_TRANSFER", "-150.591", "master-1", 80L),
                internalPointInUniverse("BYBIT:421325298", "DOGE", "INTERNAL_TRANSFER", "150.591", "master-1", 81L)
        ));

        assertThat(warningsContaining("CORRIDOR_QTY_IMBALANCE")).isEmpty();
    }

    @Test
    void genuineOneSidedLossWithinMasterStillTripsUnderSessionKey() {
        // Issue 4 (ADR-043): rescoping to the session/master must NOT mask a genuine one-sided loss.
        // A single internal LTC −1.26 with no offsetting leg anywhere in the master still trips.
        BybitEarnSubPoolConservationGuard guard = new BybitEarnSubPoolConservationGuard(mongoOperations);
        when(mongoOperations.findAll(BybitLiveBalance.class)).thenReturn(List.of());

        guard.evaluate(List.of(
                internalPointInUniverse("BYBIT:33625378", "LTC", "INTERNAL_TRANSFER", "-1.26", "master-1", 90L)
        ));

        List<ILoggingEvent> breaches = warningsContaining("CORRIDOR_QTY_IMBALANCE");
        assertThat(breaches).hasSize(1);
        String message = breaches.getFirst().getFormattedMessage();
        assertThat(message).contains("key=master-1|LTC");
        assertThat(message).contains("internalQtyDelta=-1.26000000");
    }

    private static AssetLedgerPoint internalPoint(
            String wallet, String symbol, String normalizedType, String quantityDelta, long replaySequence
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setWalletAddress(wallet);
        point.setAssetSymbol(symbol);
        point.setNormalizedType(normalizedType);
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setReplaySequence(replaySequence);
        return point;
    }

    private static AssetLedgerPoint internalPointInUniverse(
            String wallet, String symbol, String normalizedType, String quantityDelta,
            String accountingUniverseId, long replaySequence
    ) {
        AssetLedgerPoint point = internalPoint(wallet, symbol, normalizedType, quantityDelta, replaySequence);
        point.setAccountingUniverseId(accountingUniverseId);
        return point;
    }

    private List<ILoggingEvent> warningsContaining(String token) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(token))
                .toList();
    }

    private static AssetLedgerPoint point(String wallet, String symbol, String quantityAfter, long replaySequence) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setWalletAddress(wallet);
        point.setAssetSymbol(symbol);
        point.setQuantityAfter(new BigDecimal(quantityAfter));
        point.setTotalCostBasisAfterUsd(BigDecimal.ZERO);
        point.setReplaySequence(replaySequence);
        return point;
    }

    private static AssetLedgerPoint pointWithBasis(
            String wallet, String symbol, String quantityAfter, String totalCostBasisAfterUsd, long replaySequence
    ) {
        AssetLedgerPoint point = point(wallet, symbol, quantityAfter, replaySequence);
        point.setTotalCostBasisAfterUsd(new BigDecimal(totalCostBasisAfterUsd));
        return point;
    }

    private static BybitLiveBalance liveBalance(
            String integrationId, String symbol, BigDecimal fundQty, BigDecimal earnQty, BigDecimal utaQty
    ) {
        BybitLiveBalance balance = new BybitLiveBalance();
        balance.setIntegrationId(integrationId);
        balance.setAssetSymbol(symbol);
        balance.setFundQty(fundQty);
        balance.setEarnQty(earnQty);
        balance.setUtaQty(utaQty);
        return balance;
    }
}
