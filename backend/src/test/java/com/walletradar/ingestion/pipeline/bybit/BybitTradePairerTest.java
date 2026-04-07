package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitTradePairerTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void utaPairerAcceptsWindowBoundaryAndIgnoresLaterCandidate() {
        ExternalLedgerRaw buyLeg = trade("row-buy", "BUY", Instant.parse("2026-03-25T10:00:00Z"));
        buyLeg.setAssetSymbol("ETH");
        buyLeg.setQuantityRaw(java.math.BigDecimal.ONE);
        buyLeg.setFilledPrice(new java.math.BigDecimal("2500"));
        ExternalLedgerRaw quoteAtBoundary = trade("row-quote-5", "BUY", Instant.parse("2026-03-25T10:00:05Z"));
        quoteAtBoundary.setAssetSymbol("USDT");
        quoteAtBoundary.setQuantityRaw(new java.math.BigDecimal("-2500"));
        quoteAtBoundary.setFilledPrice(new java.math.BigDecimal("2500"));
        ExternalLedgerRaw quoteOutsideBoundary = trade("row-quote-6", "BUY", Instant.parse("2026-03-25T10:00:06Z"));
        quoteOutsideBoundary.setAssetSymbol("USDT");
        quoteOutsideBoundary.setQuantityRaw(new java.math.BigDecimal("-2500"));
        quoteOutsideBoundary.setFilledPrice(new java.math.BigDecimal("2500"));
        when(mongoOperations.find(any(org.springframework.data.mongodb.core.query.Query.class), org.mockito.ArgumentMatchers.eq(ExternalLedgerRaw.class)))
                .thenReturn(List.of(quoteAtBoundary, quoteOutsideBoundary));

        Optional<ExternalLedgerRaw> pair = new BybitTradePairer(mongoOperations).findOppositeLeg(buyLeg);

        assertThat(pair).isPresent();
        assertThat(pair.orElseThrow().getId()).isEqualTo("row-quote-5");
    }

    @Test
    void utaPairerSupportsNonStablecoinContractPairs() {
        ExternalLedgerRaw sellLeg = trade("row-sol", "SELL", Instant.parse("2026-03-25T10:00:00Z"));
        sellLeg.setUtaContract("BBSOLSOL");
        sellLeg.setAssetSymbol("SOL");
        sellLeg.setQuantityRaw(new java.math.BigDecimal("2.043456"));
        sellLeg.setFilledPrice(new java.math.BigDecimal("1.101"));
        ExternalLedgerRaw counterpart = trade("row-bbsol", "SELL", Instant.parse("2026-03-25T10:00:00Z"));
        counterpart.setUtaContract("BBSOLSOL");
        counterpart.setAssetSymbol("BBSOL");
        counterpart.setQuantityRaw(new java.math.BigDecimal("-1.856"));
        counterpart.setFilledPrice(new java.math.BigDecimal("1.101"));
        when(mongoOperations.find(any(org.springframework.data.mongodb.core.query.Query.class), org.mockito.ArgumentMatchers.eq(ExternalLedgerRaw.class)))
                .thenReturn(List.of(counterpart));

        Optional<ExternalLedgerRaw> pair = new BybitTradePairer(mongoOperations).findOppositeLeg(sellLeg);

        assertThat(pair).isPresent();
        assertThat(pair.orElseThrow().getAssetSymbol()).isEqualTo("BBSOL");
    }

    private ExternalLedgerRaw trade(String id, String direction, Instant timeUtc) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setSourceFileType("uta_derivatives");
        row.setUid("uid-1");
        row.setUtaContract("ETHUSDT");
        row.setUtaDirection(direction);
        row.setTimeUtc(timeUtc);
        row.setAssetSymbol("ETH");
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setQuantityRaw(java.math.BigDecimal.ONE);
        return row;
    }
}
