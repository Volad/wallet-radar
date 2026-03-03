package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.job.classification.ConfidenceScorer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceScorerTest {

    private final ConfidenceScorer scorer = new ConfidenceScorer();

    @Test
    void returnsLowScoreForEmptyEvents() {
        assertThat(scorer.score(List.of())).isEqualByComparingTo(new BigDecimal("0.35"));
    }

    @Test
    void returnsHighScoreForFullSwapPair() {
        RawClassifiedEvent sell = event(EconomicEventType.SWAP_SELL);
        RawClassifiedEvent buy = event(EconomicEventType.SWAP_BUY);
        assertThat(scorer.score(List.of(sell, buy))).isEqualByComparingTo(new BigDecimal("0.95"));
    }

    @Test
    void returnsMediumLowForPartialSwap() {
        RawClassifiedEvent sell = event(EconomicEventType.SWAP_SELL);
        assertThat(scorer.score(List.of(sell))).isEqualByComparingTo(new BigDecimal("0.55"));
    }

    @Test
    void returnsHighForLendDeposit() {
        RawClassifiedEvent out = event(EconomicEventType.LEND_DEPOSIT);
        out.setQuantityDelta(new BigDecimal("-1"));
        RawClassifiedEvent in = event(EconomicEventType.LEND_DEPOSIT);
        in.setQuantityDelta(new BigDecimal("0.9"));
        assertThat(scorer.score(List.of(out, in))).isEqualByComparingTo(new BigDecimal("0.90"));
    }

    @Test
    void returnsHighForLendWithdrawal() {
        RawClassifiedEvent out = event(EconomicEventType.LEND_WITHDRAWAL);
        out.setQuantityDelta(new BigDecimal("-1"));
        RawClassifiedEvent in = event(EconomicEventType.LEND_WITHDRAWAL);
        in.setQuantityDelta(new BigDecimal("1.1"));
        assertThat(scorer.score(List.of(out, in))).isEqualByComparingTo(new BigDecimal("0.90"));
    }

    private static RawClassifiedEvent event(EconomicEventType type) {
        RawClassifiedEvent e = new RawClassifiedEvent();
        e.setEventType(type);
        return e;
    }
}
