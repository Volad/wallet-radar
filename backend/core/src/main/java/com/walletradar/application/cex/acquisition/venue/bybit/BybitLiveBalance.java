package com.walletradar.application.cex.acquisition.venue.bybit;

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
 * Cycle/5 N15: persisted per-(integration, symbol) snapshot of the authoritative Bybit live balance,
 * sourced from {@code /v5/account/wallet-balance}, {@code /v5/asset/transfer/query-account-coins-balance},
 * and {@code /v5/earn/position}. Used by {@code SessionDashboardQueryService} to clamp Bybit umbrella
 * inventories so phantom positions left by API-gap defects (e.g. Earn-product withdrawals that none of the
 * ingested streams expose) cannot inflate the dashboard above the user's real holdings.
 *
 * <p>The umbrella sum is what the dashboard consumes; the per-sub-account fields exist for audit and
 * reconciliation tooling.</p>
 */
@Document(collection = "bybit_live_balances")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class BybitLiveBalance {

    @Id
    private String id;

    @Indexed
    private String integrationId;

    private String assetSymbol;
    private BigDecimal utaQty;
    private BigDecimal fundQty;
    private BigDecimal earnQty;
    private BigDecimal umbrellaQty;
    private Instant fetchedAt;

    public static String key(String integrationId, String assetSymbol) {
        return integrationId + ":" + assetSymbol;
    }

    /** Tombstone row written when a successful live fetch returns zero balances across UTA+FUND+EARN. */
    public static final String EMPTY_UMBRELLA_SYMBOL = "__EMPTY_UMBRELLA__";
}
