package com.walletradar.ingestion.pipeline.classification.support;

import java.math.BigDecimal;

public record RawLeg(
        String assetContract,
        String assetSymbol,
        BigDecimal quantityDelta,
        boolean fee
) {
    public static RawLeg asset(String assetContract, String assetSymbol, BigDecimal quantityDelta) {
        return new RawLeg(assetContract, assetSymbol, quantityDelta, false);
    }

    public static RawLeg nativeAsset(String assetSymbol, BigDecimal quantityDelta) {
        return new RawLeg(null, assetSymbol, quantityDelta, false);
    }

    public static RawLeg fee(String assetSymbol, BigDecimal quantityDelta) {
        return new RawLeg(null, assetSymbol, quantityDelta, true);
    }
}
