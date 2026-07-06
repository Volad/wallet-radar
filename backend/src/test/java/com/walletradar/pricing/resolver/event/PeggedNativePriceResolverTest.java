package com.walletradar.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceResolutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PeggedNativePriceResolverTest {

    private final PeggedNativePriceResolver resolver = new PeggedNativePriceResolver();

    @Test
    void reusesSiblingEthQuoteForCmethWithPeggedNativeSource() {
        NormalizedTransaction tx = new NormalizedTransaction();
        NormalizedTransaction.Flow eth = flow("ETH", "1", NormalizedLegRole.TRANSFER);
        NormalizedTransaction.Flow cmeth = flow("CMETH", "1", NormalizedLegRole.TRANSFER);
        tx.setFlows(java.util.List.of(eth, cmeth));

        PriceQuote ethQuote = new PriceQuote(
                new BigDecimal("2175.66"),
                PriceSource.BINANCE,
                Instant.parse("2025-02-06T17:07:57Z"),
                "ETH",
                "external"
        );
        PriceResolutionContext context = new PriceResolutionContext(tx, cmeth, 1, Map.of(0, ethQuote));

        Optional<PriceQuote> quote = resolver.resolve(context);

        assertThat(quote).isPresent();
        assertThat(quote.orElseThrow().unitPriceUsd()).isEqualByComparingTo("2175.66");
        assertThat(quote.orElseThrow().source()).isEqualTo(PriceSource.PEGGED_NATIVE);
    }

    @Test
    void skipsNonPeggedSymbol() {
        NormalizedTransaction tx = new NormalizedTransaction();
        NormalizedTransaction.Flow usdc = flow("USDC", "100", NormalizedLegRole.TRANSFER);
        tx.setFlows(java.util.List.of(usdc));

        PriceResolutionContext context = new PriceResolutionContext(tx, usdc, 0, Map.of());

        assertThat(resolver.resolve(context)).isEmpty();
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty, NormalizedLegRole role) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
