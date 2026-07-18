package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.canonical.correlation.CorrelationContract;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-056 coverage for {@link BybitOnChainEarnFundPairer}: same-asset FUND self-round-trip pairing
 * for non-ETH-family On-chain Earn products (e.g. TON).
 *
 * <p>Mockito call ordering: the pairer issues two {@code mongoOperations.find} calls in sequence —
 * first for subscribe-pending rows, second for redemption candidates on the same wallets. Tests
 * use chained {@code thenReturn(...).thenReturn(...)} to supply different answers per call.
 */
@ExtendWith(MockitoExtension.class)
class BybitOnChainEarnFundPairerTest {

    private static final String FUND_WALLET = "BYBIT:33625378:FUND";
    private static final String TON = "TON";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private BybitOnChainEarnFundPairer pairer;

    @BeforeEach
    void setUp() {
        pairer = new BybitOnChainEarnFundPairer(mongoOperations, normalizedTransactionRepository);
    }

    // -------------------------------------------------------------------------
    // 1. Happy path — single pair
    // -------------------------------------------------------------------------

    @Test
    void onChainEarnSubscribeAndRedeemSameQtySameAssetArePaired() {
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393",
                Instant.parse("2025-01-10T12:00:00Z"));
        NormalizedTransaction redeem = redeemIn("red-1", FUND_WALLET, TON, "32.393",
                Instant.parse("2025-01-20T12:00:00Z"));

        // First find = subscription candidates; second find = redemption candidates.
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of(redeem));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(1);
        assertThat(subscribe.getCorrelationId())
                .startsWith(CorrelationContract.BYBIT_EARN_SELF_RT_V1_PREFIX);
        assertThat(redeem.getCorrelationId())
                .isEqualTo(subscribe.getCorrelationId());
        assertThat(subscribe.getContinuityCandidate()).isTrue();
        assertThat(redeem.getContinuityCandidate()).isTrue();
        assertThat(subscribe.getMatchedCounterparty()).isEqualTo(FUND_WALLET);
        assertThat(redeem.getMatchedCounterparty()).isEqualTo(FUND_WALLET);
    }

    // -------------------------------------------------------------------------
    // 2. Happy path — two pairs on the same wallet (FIFO)
    // -------------------------------------------------------------------------

    @Test
    void twoPairsOnSameWalletArePairedFifo() {
        NormalizedTransaction sub1 = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393",
                Instant.parse("2025-01-10T12:00:00Z"));
        NormalizedTransaction sub2 = subscribeOut("sub-2", FUND_WALLET, TON, "-32.439",
                Instant.parse("2025-03-01T08:00:00Z"));
        NormalizedTransaction red1 = redeemIn("red-1", FUND_WALLET, TON, "32.393",
                Instant.parse("2025-01-20T12:00:00Z"));
        NormalizedTransaction red2 = redeemIn("red-2", FUND_WALLET, TON, "32.439",
                Instant.parse("2025-03-15T08:00:00Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(sub1, sub2))
                .thenReturn(List.of(red1, red2));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(2);
        // FIFO: sub1 (−32.393) pairs with red1 (+32.393), sub2 (−32.439) pairs with red2 (+32.439).
        assertThat(sub1.getCorrelationId()).isEqualTo(red1.getCorrelationId());
        assertThat(sub2.getCorrelationId()).isEqualTo(red2.getCorrelationId());
        // Each pair has a distinct correlationId.
        assertThat(sub1.getCorrelationId()).isNotEqualTo(sub2.getCorrelationId());
    }

    // -------------------------------------------------------------------------
    // 3. No pairing when quantity differs
    // -------------------------------------------------------------------------

    @Test
    void onChainEarnNotPairedWhenQtyDiffers() {
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393",
                Instant.parse("2025-01-10T12:00:00Z"));
        // Quantity does not match within tolerance.
        NormalizedTransaction redeem = redeemIn("red-1", FUND_WALLET, TON, "31.000",
                Instant.parse("2025-01-20T12:00:00Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of(redeem));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
        // Subscribe keeps the pending marker; redeem is untouched.
        assertThat(subscribe.getCorrelationId()).isEqualTo(BybitOnChainEarnFundPairer.SUBSCRIBE_PENDING_MARKER);
        assertThat(redeem.getCorrelationId()).isNull();
    }

    // -------------------------------------------------------------------------
    // 4. Temporal ordering enforced — redeem before subscribe
    // -------------------------------------------------------------------------

    @Test
    void onChainEarnNotPairedWhenRedeemIsBeforeSubscribe() {
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393",
                Instant.parse("2025-01-20T12:00:00Z"));
        // Redeem timestamp BEFORE subscribe — must be rejected.
        NormalizedTransaction redeem = redeemIn("red-1", FUND_WALLET, TON, "32.393",
                Instant.parse("2025-01-10T12:00:00Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of(redeem));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
    }

    @Test
    void onChainEarnNotPairedWhenRedeemHasSameTimestampAsSubscribe() {
        // Same-timestamp = co-event, NOT a round trip. Reject (redeem must be strictly after).
        Instant ts = Instant.parse("2025-01-10T12:00:00Z");
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393", ts);
        NormalizedTransaction redeem = redeemIn("red-1", FUND_WALLET, TON, "32.393", ts);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of(redeem));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 5. Hold window enforced
    // -------------------------------------------------------------------------

    @Test
    void onChainEarnNotPairedBeyond400Days() {
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393",
                Instant.parse("2025-01-01T00:00:00Z"));
        // 401 days later — exceeds 400-day window.
        NormalizedTransaction redeem = redeemIn("red-1", FUND_WALLET, TON, "32.393",
                Instant.parse("2025-01-01T00:00:00Z").plusSeconds(401L * 86400L));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of(redeem));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
    }

    @Test
    void onChainEarnPairedAtExactly400Days() {
        Instant subscribeTs = Instant.parse("2025-01-01T00:00:00Z");
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-10.0", subscribeTs);
        // Exactly 400 days — within window.
        NormalizedTransaction redeem = redeemIn("red-1", FUND_WALLET, TON, "10.0",
                subscribeTs.plusSeconds(400L * 86400L));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of(redeem));

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(1);
        assertThat(subscribe.getCorrelationId()).isEqualTo(redeem.getCorrelationId());
    }

    // -------------------------------------------------------------------------
    // 6. ETH-family assets not processed by this pairer
    // -------------------------------------------------------------------------

    @Test
    void cmethEthFamilyNotProcessedByOnChainEarnFundPairer() {
        // ETH-family On-chain Earn rows are emitted as STAKING_DEPOSIT or NEEDS_REVIEW, never
        // with the subscribe-pending marker. When the subscription query returns empty, the pairer
        // short-circuits without issuing a redemption query or any DB writes.
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // 7. No subscriptions → no DB write
    // -------------------------------------------------------------------------

    @Test
    void noSubscriptionCandidatesReturnsZeroWithoutDbWrite() {
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // 8. Correlation ID is deterministic across runs
    // -------------------------------------------------------------------------

    @Test
    void correlationIdIsDeterministicForSameInput() {
        Instant ts = Instant.parse("2025-06-01T10:00:00Z");
        BigDecimal qty = new BigDecimal("32.393");
        String corrId1 = BybitOnChainEarnFundPairer.selfRtCorrelationId(FUND_WALLET, TON, qty, ts);
        String corrId2 = BybitOnChainEarnFundPairer.selfRtCorrelationId(FUND_WALLET, TON, qty, ts);

        assertThat(corrId1)
                .startsWith(CorrelationContract.BYBIT_EARN_SELF_RT_V1_PREFIX)
                .isEqualTo(corrId2);
    }

    @Test
    void correlationIdDiffersForDifferentSubscribeTimestamps() {
        BigDecimal qty = new BigDecimal("32.393");
        String corrId1 = BybitOnChainEarnFundPairer.selfRtCorrelationId(
                FUND_WALLET, TON, qty, Instant.parse("2025-06-01T10:00:00Z"));
        String corrId2 = BybitOnChainEarnFundPairer.selfRtCorrelationId(
                FUND_WALLET, TON, qty, Instant.parse("2025-07-01T10:00:00Z"));

        assertThat(corrId1).isNotEqualTo(corrId2);
    }

    // -------------------------------------------------------------------------
    // 9. No redemptions on wallet → no pairs
    // -------------------------------------------------------------------------

    @Test
    void noRedemptionCandidatesOnWalletYieldsZeroPairs() {
        NormalizedTransaction subscribe = subscribeOut("sub-1", FUND_WALLET, TON, "-32.393",
                Instant.parse("2025-01-10T12:00:00Z"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(subscribe))
                .thenReturn(List.of()); // no redemptions found

        int pairs = pairer.pairOnChainEarnFundRoundTrips();

        assertThat(pairs).isEqualTo(0);
        assertThat(subscribe.getCorrelationId()).isEqualTo(BybitOnChainEarnFundPairer.SUBSCRIBE_PENDING_MARKER);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static NormalizedTransaction subscribeOut(
            String id, String wallet, String asset, String qty, Instant ts
    ) {
        NormalizedTransaction tx = internalTransfer(id, wallet, asset, qty, ts);
        tx.setCorrelationId(BybitOnChainEarnFundPairer.SUBSCRIBE_PENDING_MARKER);
        tx.setContinuityCandidate(false);
        return tx;
    }

    private static NormalizedTransaction redeemIn(
            String id, String wallet, String asset, String qty, Instant ts
    ) {
        NormalizedTransaction tx = internalTransfer(id, wallet, asset, qty, ts);
        tx.setCorrelationId(null);
        tx.setContinuityCandidate(false);
        return tx;
    }

    private static NormalizedTransaction internalTransfer(
            String id, String wallet, String asset, String qty, Instant ts
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(wallet);
        tx.setBlockTimestamp(ts);
        tx.setExcludedFromAccounting(false);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
