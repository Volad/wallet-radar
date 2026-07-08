package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.PriceSource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Snapshot quote for current dashboard valuation. Historical AVCO quotes stay in historical_prices.
 */
@Document(collection = "current_price_quotes")
@CompoundIndexes({
        @CompoundIndex(
                name = "current_price_quote_symbol_source_idx",
                def = "{'symbol': 1, 'source': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "current_price_quote_symbol_priced_idx",
                def = "{'symbol': 1, 'pricedAt': -1, 'fetchedAt': -1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CurrentPriceQuoteDocument {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String symbol;
    private PriceSource source;
    private BigDecimal priceUsd;
    private String quoteSymbol;
    private Instant pricedAt;
    private Instant fetchedAt;
    private String sourceReference;

    public static String composeId(String symbol, PriceSource source) {
        return normalizeSymbol(symbol) + ":" + source.name();
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
