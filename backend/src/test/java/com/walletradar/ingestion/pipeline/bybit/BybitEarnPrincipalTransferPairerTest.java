package com.walletradar.ingestion.pipeline.bybit;

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
