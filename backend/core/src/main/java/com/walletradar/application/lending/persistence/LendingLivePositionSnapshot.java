package com.walletradar.application.lending.persistence;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Single-authority live snapshot of a receipt-less lending position (WS-3). Persisted by the
 * background lending refresh so GET/read paths (dashboard balance contribution, lending cycle
 * builder, borrow-liability true-up) can consume the protocol-authoritative collateral/debt/HF
 * without performing network I/O (snapshot-first; ADR-071).
 */
@Document(collection = "lending_live_position_snapshots")
@CompoundIndexes({
        @CompoundIndex(
                name = "lending_live_pos_session_group_latest_idx",
                def = "{'sessionId': 1, 'protocolKey': 1, 'networkId': 1, 'walletAddress': 1, 'capturedAt': -1}"
        ),
        @CompoundIndex(
                name = "lending_live_pos_wallet_latest_idx",
                def = "{'walletAddress': 1, 'networkId': 1, 'capturedAt': -1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@Accessors(chain = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LendingLivePositionSnapshot {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String sessionId;
    private String protocolKey;
    private String networkId;
    private String walletAddress;
    private BigDecimal healthFactor;
    private BigDecimal liquidationThreshold;
    private BigDecimal loanToValue;
    private String source;
    private Instant capturedAt;
    private String rawRef;

    private List<Leg> collateral;
    private List<Leg> debt;

    @NoArgsConstructor
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Leg {
        private String assetSymbol;
        private String assetContract;
        private Integer decimals;
        private BigDecimal quantity;
        private BigDecimal marketValueUsd;
    }
}
