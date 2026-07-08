package com.walletradar.application.normalization.pipeline.classification.lp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PendleLpCorrelationSupportTest {

    @Test
    void eqbPendleLptMapsToSameMktIdAsPendleLpt() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("eqbPENDLE-LPT"))
                .isEqualTo(PendleLpCorrelationSupport.marketIdFromSymbol("PENDLE-LPT"));
    }

    @Test
    void eqbPendleLptMapsToExpectedSlug() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("eqbPENDLE-LPT")).isEqualTo("pendle-lpt");
    }

    @Test
    void regularPendleLptUnchanged() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("PENDLE-LPT")).isEqualTo("pendle-lpt");
    }

    @Test
    void nonPendleTokenReturnsNull() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("USDC")).isNull();
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("cmETH")).isNull();
    }

    @Test
    void eqbWithoutPendleOrLptReturnsNull() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("eqbUSDC")).isNull();
    }
}
