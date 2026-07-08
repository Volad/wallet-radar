package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @Test
    void liquidStakingPairerUsesExtendedWindowAndDescriptionForMethToCmeth() {
        ExternalLedgerRaw meth = liquidStaking("meth-leg", "METH", "-0.66865026", Instant.parse("2025-03-12T22:42:40Z"));
        ExternalLedgerRaw cmeth = liquidStaking("cmeth-leg", "CMETH", "0.66865026", Instant.parse("2025-03-13T02:38:23Z"));
        when(mongoOperations.find(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(List.of(cmeth));

        Optional<ExternalLedgerRaw> pair = new BybitTradePairer(mongoOperations).findLiquidStakingCounterLeg(meth);

        assertThat(pair).isPresent();
        assertThat(pair.orElseThrow().getId()).isEqualTo("cmeth-leg");

        org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captor.capture(), eq(ExternalLedgerRaw.class));
        String queryText = String.valueOf(captor.getValue().getQueryObject());
        assertThat(queryText).contains("bybitDescription");
        assertThat(queryText).contains("On-chain Earn subscription");
        assertThat(queryText).contains("timeUtc");
        assertThat(queryText).contains("2025-03-12T16:42:40Z");
        assertThat(queryText).contains("2025-03-13T04:42:40Z");
    }

    @Test
    void eth20StakeMintPairerAllowsAsymmetricDescriptionsWithinWindow() {
        ExternalLedgerRaw stake = liquidStaking("stake-leg", "ETH", "-0.709", Instant.parse("2025-03-12T20:08:36Z"));
        stake.setBybitType("ETH 2.0");
        stake.setBybitDescription("Stake");

        ExternalLedgerRaw mint = liquidStaking("mint-leg", "METH", "0.66865026", Instant.parse("2025-03-12T20:37:05Z"));
        mint.setBybitType("ETH 2.0");
        mint.setBybitDescription("Mint");

        when(mongoOperations.find(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(List.of(mint));

        Optional<ExternalLedgerRaw> pair = new BybitTradePairer(mongoOperations).findLiquidStakingCounterLeg(stake);

        assertThat(pair).isPresent();
        assertThat(pair.orElseThrow().getId()).isEqualTo("mint-leg");

        org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captor.capture(), eq(ExternalLedgerRaw.class));
        String queryText = String.valueOf(captor.getValue().getQueryObject());
        assertThat(queryText).contains("ETH 2.0");
        assertThat(queryText).doesNotContain("bybitDescription");
    }

    @Test
    void convertClusterQueryAcceptsCurrencyBuyCurrencySellAndConvertAcrossCase() {
        ExternalLedgerRaw sell = new ExternalLedgerRaw();
        sell.setId("convert-sell");
        sell.setStatus(ExternalLedgerRawStatus.RAW);
        sell.setSourceFileType("fund_asset_changes");
        sell.setUid("33625378");
        sell.setBybitType("Convert");
        sell.setTimeUtc(Instant.parse("2025-04-17T12:08:56Z"));

        when(mongoOperations.find(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(List.of(sell));

        new BybitTradePairer(mongoOperations).loadConvertCluster(sell);

        org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captor.capture(), eq(ExternalLedgerRaw.class));
        String queryText = String.valueOf(captor.getValue().getQueryObject()).toLowerCase();
        assertThat(queryText).contains("currency_buy");
        assertThat(queryText).contains("currency_sell");
        assertThat(queryText).contains("convert");
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

    private ExternalLedgerRaw liquidStaking(String id, String assetSymbol, String quantityRaw, Instant timeUtc) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setSourceFileType("fund_asset_changes");
        row.setUid("33625378");
        row.setBybitType("Earn");
        row.setBybitDescription("On-chain Earn subscription");
        row.setCanonicalType("STAKING_DEPOSIT");
        row.setTimeUtc(timeUtc);
        row.setAssetSymbol(assetSymbol);
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        return row;
    }
}
