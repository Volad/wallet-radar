package com.walletradar.application.normalization.pipeline.classification.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SameWalletSwapShapeSupportTest {

    @Test
    void detectsInboundPrincipalLeg() {
        List<RawLeg> legs = List.of(
                RawLeg.asset("0xout", "USDe", new BigDecimal("-2500")),
                RawLeg.asset("0xin", "USDC", new BigDecimal("2498.35"))
        );
        assertThat(SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(legs)).isTrue();
    }

    @Test
    void outboundOnlyIsNotSameWalletSwapShape() {
        List<RawLeg> legs = List.of(
                RawLeg.asset("0xout", "USDC", new BigDecimal("-1000000"))
        );
        assertThat(SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(legs)).isFalse();
    }

    @Test
    void feeLegsDoNotCountAsInboundPrincipal() {
        List<RawLeg> legs = List.of(
                RawLeg.asset("0xout", "USDC", new BigDecimal("-1000000")),
                RawLeg.fee("ETH", new BigDecimal("-0.001"))
        );
        assertThat(SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(legs)).isFalse();
    }
}
