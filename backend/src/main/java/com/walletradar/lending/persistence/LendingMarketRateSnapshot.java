package com.walletradar.lending.persistence;

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

@Document(collection = "lending_market_rate_snapshots")
@CompoundIndexes({
        @CompoundIndex(
                name = "lending_rate_session_market_latest_idx",
                def = "{'sessionId': 1, 'protocol': 1, 'networkId': 1, 'marketKey': 1, 'underlyingSymbol': 1, 'side': 1, 'capturedAt': -1}"
        ),
        @CompoundIndex(
                name = "lending_rate_market_latest_idx",
                def = "{'protocol': 1, 'networkId': 1, 'marketKey': 1, 'underlyingSymbol': 1, 'capturedAt': -1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LendingMarketRateSnapshot {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String sessionId;
    private String protocol;
    private String networkId;
    private String marketKey;
    private String walletAddress;
    private String assetSymbol;
    private String underlyingSymbol;
    private String side;
    private BigDecimal supplyAprPct;
    private BigDecimal supplyApyPct;
    private BigDecimal borrowAprPct;
    private BigDecimal borrowApyPct;
    private BigDecimal rewardAprPct;
    private String rewardAprStatus;
    private String rewardAprUnavailableReason;
    private BigDecimal netSupplyApyPct;
    private BigDecimal netBorrowApyPct;
    private BigDecimal utilizationPct;
    private String rateSource;
    private String rateStatus;
    private String apyConvention;
    private Instant capturedAt;
    private Long blockNumber;
    private Instant sourceTimestamp;
    private String unavailableReason;
    private String rawSnapshotRef;
}
