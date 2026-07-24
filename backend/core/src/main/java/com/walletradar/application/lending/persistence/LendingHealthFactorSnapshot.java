package com.walletradar.application.lending.persistence;

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

@Document(collection = "lending_health_factor_snapshots")
@CompoundIndexes({
        @CompoundIndex(
                name = "lending_hf_session_group_latest_idx",
                def = "{'sessionId': 1, 'protocolKey': 1, 'networkId': 1, 'walletAddress': 1, 'capturedAt': -1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LendingHealthFactorSnapshot {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String sessionId;
    private String protocolKey;
    private String networkId;
    private String walletAddress;
    private BigDecimal healthFactor;
    /** Liquidation threshold as a fraction (e.g. 0.85). Surfaced for LTV/HF context (WS-3). */
    private BigDecimal liquidationThreshold;
    /** Current loan-to-value as a fraction (e.g. 0.564). Surfaced next to the health factor (WS-3). */
    private BigDecimal loanToValue;
    private String source;
    private Instant capturedAt;
    private Long blockNumber;
    private String unavailableReason;
    private String rawSnapshotRef;
}
