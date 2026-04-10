package com.walletradar.integration.bybit;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitExtractedTradePairerTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void liquidStakingPairerUsesExtendedWindowAndDescriptionForMethToCmeth() {
        BybitExtractedEvent meth = liquidStakingRow(
                "meth-leg",
                "METH",
                "-0.66865026",
                Instant.parse("2025-03-12T22:42:40Z")
        );
        BybitExtractedEvent cmeth = liquidStakingRow(
                "cmeth-leg",
                "CMETH",
                "0.66865026",
                Instant.parse("2025-03-13T02:38:23Z")
        );

        when(mongoOperations.find(org.mockito.ArgumentMatchers.any(Query.class), eq(BybitExtractedEvent.class)))
                .thenReturn(List.of(cmeth));

        BybitExtractedTradePairer pairer = new BybitExtractedTradePairer(mongoOperations);
        Optional<BybitExtractedEvent> pair = pairer.findLiquidStakingCounterLeg(meth);

        assertThat(pair).isPresent();
        assertThat(pair.orElseThrow().getId()).isEqualTo("cmeth-leg");

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captor.capture(), eq(BybitExtractedEvent.class));
        String queryText = String.valueOf(captor.getValue().getQueryObject());
        assertThat(queryText).contains("bybitDescription");
        assertThat(queryText).contains("On-chain Earn subscription");
        assertThat(queryText).contains("timeUtc");
        assertThat(queryText).contains("2025-03-12T16:42:40Z");
        assertThat(queryText).contains("2025-03-13T04:42:40Z");
    }

    @Test
    void eth20StakeMintPairerAllowsAsymmetricDescriptionsWithinWindow() {
        BybitExtractedEvent stake = liquidStakingRow(
                "stake-leg",
                "ETH",
                "-0.709",
                Instant.parse("2025-03-12T20:08:36Z")
        );
        stake.setBybitType("ETH 2.0");
        stake.setBybitDescription("Stake");

        BybitExtractedEvent mint = liquidStakingRow(
                "mint-leg",
                "METH",
                "0.66865026",
                Instant.parse("2025-03-12T20:37:05Z")
        );
        mint.setBybitType("ETH 2.0");
        mint.setBybitDescription("Mint");

        when(mongoOperations.find(org.mockito.ArgumentMatchers.any(Query.class), eq(BybitExtractedEvent.class)))
                .thenReturn(List.of(mint));

        BybitExtractedTradePairer pairer = new BybitExtractedTradePairer(mongoOperations);
        Optional<BybitExtractedEvent> pair = pairer.findLiquidStakingCounterLeg(stake);

        assertThat(pair).isPresent();
        assertThat(pair.orElseThrow().getId()).isEqualTo("mint-leg");

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captor.capture(), eq(BybitExtractedEvent.class));
        String queryText = String.valueOf(captor.getValue().getQueryObject());
        assertThat(queryText).contains("bybitType");
        assertThat(queryText).contains("ETH 2.0");
        assertThat(queryText).doesNotContain("bybitDescription");
    }

    @Test
    void convertClusterQueryAcceptsCurrencyBuyCurrencySellAndConvertAcrossCase() {
        BybitExtractedEvent sell = new BybitExtractedEvent();
        sell.setId("convert-sell");
        sell.setStatus(BybitExtractedEventStatus.RAW);
        sell.setSourceFileType("fund_asset_changes");
        sell.setUid("33625378");
        sell.setBybitType("Convert");
        sell.setTimeUtc(Instant.parse("2025-04-17T12:08:56Z"));

        when(mongoOperations.find(org.mockito.ArgumentMatchers.any(Query.class), eq(BybitExtractedEvent.class)))
                .thenReturn(List.of(sell));

        BybitExtractedTradePairer pairer = new BybitExtractedTradePairer(mongoOperations);
        pairer.loadConvertCluster(sell);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captor.capture(), eq(BybitExtractedEvent.class));
        String queryText = String.valueOf(captor.getValue().getQueryObject()).toLowerCase();
        assertThat(queryText).contains("currency_buy");
        assertThat(queryText).contains("currency_sell");
        assertThat(queryText).contains("convert");
    }

    private BybitExtractedEvent liquidStakingRow(String id, String assetSymbol, String quantityRaw, Instant timeUtc) {
        BybitExtractedEvent row = new BybitExtractedEvent();
        row.setId(id);
        row.setStatus(BybitExtractedEventStatus.RAW);
        row.setSourceFileType("fund_asset_changes");
        row.setUid("33625378");
        row.setBybitType("Earn");
        row.setBybitDescription("On-chain Earn subscription");
        row.setCanonicalType("STAKING_DEPOSIT");
        row.setAssetSymbol(assetSymbol);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        row.setTimeUtc(timeUtc);
        return row;
    }
}
