package com.walletradar.domain.common;

import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class Decimal128SupportTest {

    @Test
    void normalizesHighPrecisionComputedDecimalToMongoSafeValue() {
        BigDecimal normalized = Decimal128Support.normalize(
                new BigDecimal("1108.6364289999999999999999999999999325896945")
        );

        assertThat(normalized).isEqualByComparingTo(new Decimal128(normalized).bigDecimalValue());
        assertThat(normalized.precision()).isLessThanOrEqualTo(34);
    }

    @Test
    void normalizesScientificNotationDecimalToMongoSafeValue() {
        BigDecimal normalized = Decimal128Support.normalize(
                new BigDecimal("3.51614194974000000000000000000000031427469220E-7")
        );

        assertThat(normalized).isEqualByComparingTo(new Decimal128(normalized).bigDecimalValue());
        assertThat(normalized.precision()).isLessThanOrEqualTo(34);
    }
}
