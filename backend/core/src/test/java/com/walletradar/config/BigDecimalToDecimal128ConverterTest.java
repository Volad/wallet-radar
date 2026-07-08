package com.walletradar.platform.common.config;

import com.walletradar.platform.persistence.config.BigDecimalToDecimal128Converter;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BigDecimalToDecimal128ConverterTest {

    @Test
    void normalizesHighPrecisionValueBeforeWritingDecimal128() {
        BigDecimal source = new BigDecimal("-33557346.46171258651723781542863941213711109100");

        Decimal128 converted = new BigDecimalToDecimal128Converter().convert(source);

        assertThat(converted).isNotNull();
        assertThat(converted.bigDecimalValue().precision()).isLessThanOrEqualTo(34);
    }
}
