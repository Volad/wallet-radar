package com.walletradar.accounting.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LpReceiptSymbolSupportTest {

    @Test
    void canonicalizesMixedCaseReceiptSymbol() {
        assertThat(LpReceiptSymbolSupport.canonicalize("LP-RECEIPT:base:pancakeswap:477096"))
                .isEqualTo("LP-RECEIPT:BASE:PANCAKESWAP:477096");
    }

    @Test
    void buildsCanonicalSymbolFromLpPositionCorrelation() {
        assertThat(LpReceiptSymbolSupport.fromLpPositionCorrelation("lp-position:optimism:velodrome:1702"))
                .isEqualTo("LP-RECEIPT:OPTIMISM:VELODROME:1702");
    }

    @Test
    void detectsLpReceiptSymbolsCaseInsensitively() {
        assertThat(LpReceiptSymbolSupport.isLpReceiptSymbol("lp-receipt:base:pancakeswap:1")).isTrue();
        assertThat(LpReceiptSymbolSupport.isLpReceiptSymbol("ETH")).isFalse();
    }
}
