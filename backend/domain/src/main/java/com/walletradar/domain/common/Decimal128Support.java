package com.walletradar.domain.common;

import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Normalizes computed decimal values so they can be persisted as Mongo Decimal128.
 */
public final class Decimal128Support {

    private Decimal128Support() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return BigDecimal.ZERO;
        }
        try {
            return new Decimal128(normalized).bigDecimalValue();
        } catch (NumberFormatException exception) {
            BigDecimal rounded = normalized.round(MathContext.DECIMAL128).stripTrailingZeros();
            if (rounded.scale() < 0) {
                rounded = rounded.setScale(0);
            }
            return new Decimal128(rounded).bigDecimalValue();
        }
    }
}
