package com.walletradar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.Arrays;

/**
 * MongoDB configuration: Decimal128 codec for all monetary/quantity fields (INV-06, ADR-002).
 * Indexes are created from @CompoundIndex / @Indexed on domain documents at startup.
 */
@Configuration
public class MongoConfig {

    @Bean
    public MongoCustomConversions customConversions() {
        return new MongoCustomConversions(Arrays.asList(
                new BigDecimalToDecimal128Converter(),
                new Decimal128ToBigDecimalConverter()
        ));
    }
}
