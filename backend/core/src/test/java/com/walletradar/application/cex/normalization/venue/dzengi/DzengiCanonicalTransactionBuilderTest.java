package com.walletradar.application.cex.normalization.venue.dzengi;

import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DzengiCanonicalTransactionBuilderTest {

    // no-op FiatExitRule: no withdrawal is classified as FIAT_EXIT in these unit tests
    private final DzengiCanonicalTransactionBuilder builder =
            new DzengiCanonicalTransactionBuilder((stream, payment, symbol) -> false);
    private final Instant now = Instant.parse("2026-07-08T00:00:00Z");

    @Test
    void externalTransferInStampsUnknownEoaCounterparty() {
        DzengiExtractedEvent row = baseRow("EXTERNAL_TRANSFER_IN");
        row.setAssetSymbol("BTC");
        row.setQuantityRaw(new BigDecimal("0.5"));
        row.setTxHash("0xabc");

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(tx.getCounterpartyType()).isEqualTo("UNKNOWN_EOA");
        assertThat(tx.getCounterpartyAddress()).isEqualTo("DZENGI:EXTERNAL:CHAIN");
        assertThat(tx.getFlows()).allSatisfy(flow -> {
            assertThat(flow.getCounterpartyAddress()).isNotBlank();
            assertThat(flow.getCounterpartyType()).isNotBlank();
        });
    }

    @Test
    void externalTransferOutStampsCounterparty() {
        DzengiExtractedEvent row = baseRow("EXTERNAL_TRANSFER_OUT");
        row.setAssetSymbol("USDT");
        row.setQuantityRaw(new BigDecimal("-100"));

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        assertThat(tx.getCounterpartyType()).isEqualTo("UNKNOWN_EOA");
        assertThat(tx.getCounterpartyAddress()).isEqualTo("DZENGI:EXTERNAL:CHAIN");
    }

    @Test
    void derivativeSettlementStampsCexCounterparty() {
        DzengiExtractedEvent row = baseRow("CEX_DERIVATIVE_SETTLEMENT");
        row.setAssetSymbol("USD");
        row.setRealizedPnl(new BigDecimal("12.34"));
        row.setFeePaid(new BigDecimal("-0.5"));

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.CEX_DERIVATIVE_SETTLEMENT);
        assertThat(tx.getCounterpartyType()).isEqualTo("CEX");
        assertThat(tx.getCounterpartyAddress()).isEqualTo("DZENGI:VENUE:1023141508");
    }

    @Test
    void spotTradeStampsCexCounterpartyOnNonFeeLegs() {
        DzengiExtractedEvent row = baseRow("BUY");
        row.setAssetSymbol("DOGE");
        row.setQuoteAsset("USD");
        row.setQuantityRaw(new BigDecimal("100"));
        row.setPrice(new BigDecimal("0.1"));
        row.setCommission(new BigDecimal("0.01"));
        row.setCommissionAsset("USD");

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getCounterpartyType()).isEqualTo("CEX");
        assertThat(tx.getFlows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .allSatisfy(flow -> assertThat(flow.getCounterpartyAddress()).isEqualTo("DZENGI:VENUE:1023141508"));
    }

    // ─── ADR-051: acquisitionFeeUsd on BUY trades ────────────────────────────────

    @Test
    void buyTradeWithUsdCommission_setsAcquisitionFeeUsd_onBuyLeg() {
        DzengiExtractedEvent row = baseRow("BUY");
        row.setAssetSymbol("TSLA");
        row.setQuoteAsset("USD");
        row.setQuantityRaw(new BigDecimal("1.0"));
        row.setPrice(new BigDecimal("371.528"));
        row.setCommission(new BigDecimal("0.186"));
        row.setCommissionAsset("USD");
        row.setIsBuyer(true);

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        NormalizedTransaction.Flow buyLeg = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY)
                .findFirst().orElseThrow();
        assertThat(buyLeg.getAssetSymbol()).isEqualTo("TSLA");
        assertThat(buyLeg.getAcquisitionFeeUsd())
                .as("acquisitionFeeUsd must equal the USD commission on BUY")
                .isEqualByComparingTo("0.186");
        // FEE leg still present for USD outflow bookkeeping
        assertThat(tx.getFlows()).anyMatch(f -> f.getRole() == NormalizedLegRole.FEE);
    }

    @Test
    void sellTradeWithUsdCommission_doesNotSetAcquisitionFeeUsd() {
        DzengiExtractedEvent row = baseRow("SELL");
        row.setAssetSymbol("TSLA");
        row.setQuoteAsset("USD");
        row.setQuantityRaw(new BigDecimal("-1.0"));
        row.setPrice(new BigDecimal("460.0"));
        row.setCommission(new BigDecimal("0.23"));
        row.setCommissionAsset("USD");
        row.setIsBuyer(false);

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        NormalizedTransaction.Flow sellLeg = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL)
                .findFirst().orElseThrow();
        assertThat(sellLeg.getAcquisitionFeeUsd())
                .as("SELL leg must not have acquisitionFeeUsd")
                .isNull();
    }

    @Test
    void buyTradeWithNoCommission_acquisitionFeeUsdIsNull() {
        DzengiExtractedEvent row = baseRow("BUY");
        row.setAssetSymbol("AAPL");
        row.setQuoteAsset("USD");
        row.setQuantityRaw(new BigDecimal("0.5"));
        row.setPrice(new BigDecimal("200.0"));
        row.setIsBuyer(true);

        NormalizedTransaction tx = builder.build(row, now);

        assertThat(tx).isNotNull();
        tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY)
                .findFirst().ifPresent(f ->
                        assertThat(f.getAcquisitionFeeUsd()).isNull());
    }

    private DzengiExtractedEvent baseRow(String canonicalType) {
        DzengiExtractedEvent row = new DzengiExtractedEvent();
        row.setId("DZENGI-1023141508:DEPOSITS:1");
        row.setUserId("1023141508");
        row.setWalletRef("DZENGI:1023141508");
        row.setCanonicalType(canonicalType);
        row.setTimeUtc(now);
        return row;
    }
}
