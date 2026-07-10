package com.walletradar.application.cex.acquisition.venue.dzengi;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted per-(integration, symbol) snapshot of authoritative Dzengi live balances.
 */
@Document(collection = "dzengi_live_balances")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class DzengiLiveBalance {

    @Id
    private String id;

    @Indexed
    private String integrationId;

    private String assetSymbol;
    private BigDecimal umbrellaQty;
    private Instant fetchedAt;

    public static String key(String integrationId, String assetSymbol) {
        return integrationId + ":" + assetSymbol;
    }

    public static final String EMPTY_UMBRELLA_SYMBOL = "__EMPTY_UMBRELLA__";
}
