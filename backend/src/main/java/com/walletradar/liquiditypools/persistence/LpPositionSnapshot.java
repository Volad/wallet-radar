package com.walletradar.liquiditypools.persistence;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "lp_position_snapshots")
@CompoundIndexes({
        @CompoundIndex(name = "lp_snap_universe_status_idx", def = "{'universeId': 1, 'status': 1}"),
        @CompoundIndex(name = "lp_snap_network_status_idx", def = "{'networkId': 1, 'status': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LpPositionSnapshot {

    @Id
    @EqualsAndHashCode.Include
    private String correlationId;

    private String universeId;
    private String networkId;
    private String walletAddress;
    private String protocol;
    private String family;
    private String status;
    private Boolean staked;

    private TokenSide token0;
    private TokenSide token1;

    private BigDecimal feeTierPct;
    private BigDecimal priceLow;
    private BigDecimal priceHigh;
    private BigDecimal priceCurrent;
    private Integer tickLower;
    private Integer tickUpper;
    private Integer currentTick;

    private BigDecimal tvlUsd;
    private BigDecimal unclaimedFeesUsd;
    private Map<String, BigDecimal> unclaimedFeesByToken = new LinkedHashMap<>();

    private BigDecimal ilPct;
    private BigDecimal ilUsd;
    private BigDecimal aprNow;
    /** Protocol-provided fee APR (e.g. from GMX /markets/info). Null for protocols that track fees via earning points. */
    private BigDecimal feeAprPct;

    /**
     * GMX-specific: {@code cumulativeFeeUsdPerPoolValue} at the time of the first LP entry,
     * in 10^30 scale (same as GMX price format). Populated once on first snapshot and preserved
     * across subsequent refreshes. Combined with {@link #currentFeePerPoolValue} to compute
     * total earned fees: {@code (current - entry) / 10^30 × depositedUsd}.
     */
    private BigDecimal entryFeePerPoolValue;

    /**
     * GMX-specific: latest {@code cumulativeFeeUsdPerPoolValue} from the Subsquid GraphQL,
     * in 10^30 scale. Updated on every refresh.
     */
    private BigDecimal currentFeePerPoolValue;

    private Instant snapshotAt;
    private Boolean snapshotStale;
    private String unavailableReason;

    private List<LiquidityBin> liquidityBins;
    private Instant liquidityBinsAt;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class TokenSide {
        private String sym;
        private String contract;
        private BigDecimal qty;
        private BigDecimal usd;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class LiquidityBin {
        private int tickLower;
        private int tickUpper;
        private BigDecimal priceLower;
        private BigDecimal priceUpper;
        private double liquidityShare;
    }
}
