package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitOnChainEarnOrphanRepairServiceTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private BybitOnChainEarnOrphanRepairService service() {
        return new BybitOnChainEarnOrphanRepairService(mongoOperations, normalizedTransactionRepository);
    }

    // ------------------------------------------------------------------
    // Happy path: FUND-only orphan → synthetic EARN counterpart created
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void repairOrphans_fundOnlySubscription_createsSyntheticEarnCounterpart() {
        Instant ts = Instant.parse("2025-04-18T07:39:44Z");
        NormalizedTransaction fund = fundTransfer("BYBIT-33625378:FH:eth-sub-1", "BYBIT:33625378:FUND", "ETH", "-0.693", ts);

        // First find() call returns FUND orphans; second returns EARN internal transfers (empty).
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund))
                .thenReturn(List.of());
        // Synthetic does not exist yet.
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(false);

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(1);

        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> saved = (List<NormalizedTransaction>) captor.getValue();
        assertThat(saved).hasSize(2);

        // FUND event updated
        NormalizedTransaction savedFund = saved.stream()
                .filter(t -> "BYBIT:33625378:FUND".equals(t.getWalletAddress()))
                .findFirst().orElseThrow();
        assertThat(savedFund.getCorrelationId()).startsWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_CORR_PREFIX);
        assertThat(savedFund.getContinuityCandidate()).isTrue();
        assertThat(savedFund.getMatchedCounterparty()).isEqualTo("BYBIT:33625378:EARN");

        // Synthetic EARN event created
        NormalizedTransaction synthetic = saved.stream()
                .filter(t -> "BYBIT:33625378:EARN".equals(t.getWalletAddress()))
                .findFirst().orElseThrow();
        assertThat(synthetic.getId()).startsWith(BybitOnChainEarnOrphanRepairService.SYNTHETIC_ID_PREFIX);
        assertThat(synthetic.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(synthetic.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(synthetic.getSource()).isEqualTo(NormalizedTransactionSource.BYBIT);
        assertThat(synthetic.getContinuityCandidate()).isTrue();
        assertThat(synthetic.getMatchedCounterparty()).isEqualTo("BYBIT:33625378:FUND");
        assertThat(synthetic.getCorrelationId()).isEqualTo(savedFund.getCorrelationId());
        assertThat(synthetic.getBlockTimestamp()).isEqualTo(ts);

        NormalizedTransaction.Flow earnFlow = synthetic.getFlows().get(0);
        assertThat(earnFlow.getAssetSymbol()).isEqualTo("ETH");
        assertThat(earnFlow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("0.693"));
        assertThat(earnFlow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    // ------------------------------------------------------------------
    // Already paired: no change
    // ------------------------------------------------------------------

    @Test
    void repairOrphans_fundAlreadyHasCorrId_noChange() {
        NormalizedTransaction fund = fundTransfer("fund-paired", "BYBIT:33625378:FUND", "ETH", "-0.693", null);
        fund.setCorrelationId("bybit-collapsed-v1:existing");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())   // FUND orphans = empty (corrId≠blank filtered in DB query)
                .thenReturn(List.of());

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // EARN counterpart already exists in DB: skips synthesis
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void repairOrphans_earnCounterpartAlreadyExists_skipsSynthesis() {
        Instant ts = Instant.parse("2025-03-12T22:42:40Z");
        NormalizedTransaction fund = fundTransfer("BYBIT-33625378:FH:meth-sub", "BYBIT:33625378:FUND", "METH", "-0.669", ts);
        NormalizedTransaction earn = earnTransfer("BYBIT-33625378:FH:meth-earn", "BYBIT:33625378:EARN", "METH", "0.669", ts);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund))
                .thenReturn(List.of(earn));

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // Idempotent: synthetic already persisted from prior run
    // ------------------------------------------------------------------

    @Test
    void repairOrphans_syntheticAlreadyPersisted_noChange() {
        NormalizedTransaction fund = fundTransfer("BYBIT-33625378:FH:eth-sub-2", "BYBIT:33625378:FUND", "ETH", "-0.693", null);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund))
                .thenReturn(List.of());
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(true);

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // Different uid: no cross-uid pairing
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void repairOrphans_differentUid_noFalsePairing() {
        Instant ts = Instant.parse("2025-04-18T07:39:44Z");
        NormalizedTransaction fund1 = fundTransfer("BYBIT-111:FH:eth-sub-a", "BYBIT:111:FUND", "ETH", "-0.693", ts);
        NormalizedTransaction fund2 = fundTransfer("BYBIT-222:FH:eth-sub-b", "BYBIT:222:FUND", "ETH", "-0.693", ts);

        // Suppose an EARN event from uid=111 exists — must not pair with fund2 (uid=222)
        NormalizedTransaction earn111 = earnTransfer("BYBIT-111:FH:eth-earn", "BYBIT:111:EARN", "ETH", "0.693", ts);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund1, fund2))
                .thenReturn(List.of(earn111));
        // fund1 synthetic does not exist; fund2 synthetic does not exist
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(false);

        int repaired = service().repairOrphans();

        // fund1 should be skipped (earn111 is a valid counterpart for it)
        // fund2 should be repaired (no counterpart for uid=222)
        assertThat(repaired).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> saved = (List<NormalizedTransaction>) captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.stream().allMatch(t -> t.getWalletAddress().contains("222")
                || t.getId().startsWith(BybitOnChainEarnOrphanRepairService.SYNTHETIC_ID_PREFIX))).isTrue();
    }

    // ------------------------------------------------------------------
    // Excluded FUND event: skipped
    // ------------------------------------------------------------------

    @Test
    void repairOrphans_excludedFundEvent_skips() {
        NormalizedTransaction fund = fundTransfer("BYBIT-33625378:FH:eth-excluded", "BYBIT:33625378:FUND", "ETH", "-0.693", null);
        fund.setExcludedFromAccounting(true);

        // DB query with excludedFromAccounting≠true returns nothing (filter applied in DB).
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // Corridor-funded FUND subscription: uses EARN_ONCHAIN_FUND_CORR_PREFIX
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void repairOrphans_corridorFundedSubscription_usesFundCorrIdPrefix() {
        Instant ts = Instant.parse("2025-05-10T12:00:00Z");
        NormalizedTransaction fund = fundTransfer("BYBIT-421325298:FH:arb-sub", "BYBIT:421325298:FUND", "ARB", "-100.0", ts);

        // Simulate: corridor deposit at BYBIT:421325298:FUND, same qty, within 6h
        NormalizedTransaction corridorDeposit = fundTransfer("corridor-arb-deposit",
                "BYBIT:421325298:FUND", "ARB", "100.0", ts.minusSeconds(300));
        corridorDeposit.setCorrelationId("BYBIT-CORRIDOR:arbitrum:some-hash");

        // find() calls: (1) loadFundOrphans, (2) loadEarnInternalTransfers, (3) hasRecentCorridorDeposit
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund))
                .thenReturn(List.of())
                .thenReturn(List.of(corridorDeposit));
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(false);

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(1);

        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> saved = (List<NormalizedTransaction>) captor.getValue();
        assertThat(saved).hasSize(2);

        NormalizedTransaction savedFund = saved.stream()
                .filter(t -> "BYBIT:421325298:FUND".equals(t.getWalletAddress()))
                .findFirst().orElseThrow();
        // Corridor-funded: must use the FUND prefix so :FUND wallet is preserved in replay
        assertThat(savedFund.getCorrelationId())
                .startsWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_FUND_CORR_PREFIX);
        assertThat(savedFund.getCorrelationId())
                .doesNotStartWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_CORR_PREFIX);

        NormalizedTransaction synthetic = saved.stream()
                .filter(t -> "BYBIT:421325298:EARN".equals(t.getWalletAddress()))
                .findFirst().orElseThrow();
        assertThat(synthetic.getCorrelationId()).isEqualTo(savedFund.getCorrelationId());
    }

    // ------------------------------------------------------------------
    // Non-corridor FUND subscription: uses EARN_ONCHAIN_CORR_PREFIX (spot-funded, e.g. METH)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void repairOrphans_spotFundedSubscription_usesDefaultCorrIdPrefix() {
        Instant ts = Instant.parse("2025-06-01T10:00:00Z");
        NormalizedTransaction fund = fundTransfer("BYBIT-516601508:FH:meth-sub", "BYBIT:516601508:FUND", "METH", "-0.669", ts);

        // No corridor deposit found (empty list)
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund))
                .thenReturn(List.of())
                .thenReturn(List.of());  // no corridor deposit
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(false);

        int repaired = service().repairOrphans();

        assertThat(repaired).isEqualTo(1);

        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> saved = (List<NormalizedTransaction>) captor.getValue();
        NormalizedTransaction savedFund = saved.stream()
                .filter(t -> "BYBIT:516601508:FUND".equals(t.getWalletAddress()))
                .findFirst().orElseThrow();
        // Spot-funded: must use the generic (non-fund) prefix — :FUND position will be stripped
        assertThat(savedFund.getCorrelationId())
                .startsWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_CORR_PREFIX);
        assertThat(savedFund.getCorrelationId())
                .doesNotStartWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_FUND_CORR_PREFIX);
    }

    // ------------------------------------------------------------------
    // hasRecentCorridorDeposit: qty within 1% tolerance accepted
    // ------------------------------------------------------------------

    @Test
    void hasRecentCorridorDeposit_qtWithinOnePctTolerance_returnsTrue() {
        Instant ts = Instant.parse("2025-05-10T12:00:00Z");

        // Corridor deposit qty is 99.5 (0.5% less than 100 — within 1% tolerance).
        // ARB → continuityIdentity = "FAMILY:ARB", which is what repairOrphans() would pass.
        NormalizedTransaction corridorDeposit = fundTransfer("corridor-arb",
                "BYBIT:777:FUND", "ARB", "99.5", ts.minusSeconds(60));
        corridorDeposit.setCorrelationId("BYBIT-CORRIDOR:arb:hash");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorDeposit));

        boolean result = service().hasRecentCorridorDeposit("777", "FAMILY:ARB", new BigDecimal("100"), ts);

        assertThat(result).isTrue();
    }

    // ------------------------------------------------------------------
    // hasRecentCorridorDeposit: qty outside 1% tolerance rejected
    // ------------------------------------------------------------------

    @Test
    void hasRecentCorridorDeposit_qtOutsideTolerance_returnsFalse() {
        Instant ts = Instant.parse("2025-05-10T12:00:00Z");

        // Corridor deposit qty is 90 (10% less — outside 1% tolerance)
        NormalizedTransaction corridorDeposit = fundTransfer("corridor-arb",
                "BYBIT:777:FUND", "ARB", "90.0", ts.minusSeconds(60));
        corridorDeposit.setCorrelationId("BYBIT-CORRIDOR:arb:hash");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorDeposit));

        boolean result = service().hasRecentCorridorDeposit("777", "FAMILY:ARB", new BigDecimal("100"), ts);

        assertThat(result).isFalse();
    }

    // ------------------------------------------------------------------
    // hasRecentCorridorDeposit: different asset family rejected
    // ------------------------------------------------------------------

    @Test
    void hasRecentCorridorDeposit_differentAssetFamily_returnsFalse() {
        Instant ts = Instant.parse("2025-05-10T12:00:00Z");

        // Corridor deposit is ETH (FAMILY:ETH), but we're looking for ARB (FAMILY:ARB)
        NormalizedTransaction corridorDeposit = fundTransfer("corridor-eth",
                "BYBIT:777:FUND", "ETH", "100.0", ts.minusSeconds(60));
        corridorDeposit.setCorrelationId("BYBIT-CORRIDOR:arb:hash");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(corridorDeposit));

        boolean result = service().hasRecentCorridorDeposit("777", "FAMILY:ARB", new BigDecimal("100"), ts);

        assertThat(result).isFalse();
    }

    // ------------------------------------------------------------------
    // hasRecentCorridorDeposit: empty DB returns false
    // ------------------------------------------------------------------

    @Test
    void hasRecentCorridorDeposit_noDeposits_returnsFalse() {
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        boolean result = service().hasRecentCorridorDeposit("999", "FAMILY:ETH", new BigDecimal("1.0"),
                Instant.parse("2025-05-10T12:00:00Z"));

        assertThat(result).isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static NormalizedTransaction fundTransfer(String id, String wallet, String asset, String qty, Instant ts) {
        return internalTransfer(id, wallet, asset, qty, ts);
    }

    private static NormalizedTransaction earnTransfer(String id, String wallet, String asset, String qty, Instant ts) {
        return internalTransfer(id, wallet, asset, qty, ts);
    }

    private static NormalizedTransaction internalTransfer(String id, String wallet, String asset, String qty, Instant ts) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setAccountRef(wallet);

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setWalletAddress(wallet);
        tx.setBlockTimestamp(ts);
        tx.setFlows(new java.util.ArrayList<>(List.of(flow)));
        return tx;
    }
}
