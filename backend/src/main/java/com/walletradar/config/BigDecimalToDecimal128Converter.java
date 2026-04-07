package com.walletradar.config;

import com.walletradar.domain.common.Decimal128Support;
import org.bson.types.Decimal128;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

import java.math.BigDecimal;

/**
 * Writes BigDecimal as Decimal128 in MongoDB.
 */
@WritingConverter
public class BigDecimalToDecimal128Converter implements Converter<BigDecimal, Decimal128> {

    @Override
    public Decimal128 convert(BigDecimal source) {
        return source == null ? null : new Decimal128(Decimal128Support.normalize(source));
    }
}
