package com.walletradar.application.pricing.persistence;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceBucketResolution;
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

/**
 * Deterministic historical price cache row.
 */
@Document(collection = "historical_prices")
@CompoundIndexes({
        @CompoundIndex(
                name = "historical_price_asset_bucket_source_idx",
                def = "{'assetKey': 1, 'bucketStart': 1, 'source': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "historical_price_symbol_bucket_source_idx",
                def = "{'symbol': 1, 'bucketStart': 1, 'source': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HistoricalPriceDocument {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String assetKey;
    private NetworkId networkId;
    private String symbol;
    private Instant bucketStart;
    private PriceBucketResolution bucketResolution;
    private PriceSource source;
    private BigDecimal priceUsd;
    private String quoteSymbol;
    private Instant fetchedAt;

    public static String composeId(String assetKey, Instant bucketStart, PriceSource source) {
        return assetKey + ":" + bucketStart.toEpochMilli() + ":" + source.name();
    }
}
