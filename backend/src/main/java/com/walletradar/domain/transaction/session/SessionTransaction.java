package com.walletradar.domain.transaction.session;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-scoped transaction projection sourced from normalized transactions and user session adjustments.
 */
@Document(collection = "session_transactions")
@CompoundIndexes({
    @CompoundIndex(name = "session_source_uniq", def = "{'sessionId': 1, 'sourceType': 1, 'sourceId': 1}", unique = true),
    @CompoundIndex(name = "session_timeline_desc", def = "{'sessionId': 1, 'blockTimestamp': -1, 'sortKey': -1}"),
    @CompoundIndex(name = "session_bridge_status", def = "{'sessionId': 1, 'bridgeStatus': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SessionTransaction {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    @Indexed
    private String sessionId;
    private SessionTransactionSourceType sourceType;
    private String sourceId;

    private String txHash;
    private NetworkId networkId;
    private String walletAddress;
    private Instant blockTimestamp;
    private NormalizedTransactionType type;

    /**
     * Deterministic ordering contract for replay and timeline queries.
     */
    private String sortKey;
    private SessionBridgeStatus bridgeStatus;
    private String bridgePairKey;

    @Setter(AccessLevel.NONE)
    private List<Flow> flows = new ArrayList<>();

    /**
     * Session-level realised PnL across all sell-like legs of this transaction.
     */
    private BigDecimal realisedPnlUsdTotal;

    /**
     * Replay snapshot/version id that produced current totals.
     */
    private Long avcoSnapshotVersion;

    private Instant createdAt;
    private Instant updatedAt;

    public void setFlows(List<Flow> flows) {
        List<Flow> canonical = flows != null ? new ArrayList<>(flows) : new ArrayList<>();
        this.flows = canonical;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Flow {
        private NormalizedLegRole role;
        private String assetContract;
        private String assetSymbol;
        private BigDecimal quantityDelta;
        private BigDecimal unitPriceUsd;
        private BigDecimal valueUsd;
        private PriceSource priceSource;
        private boolean isInferred;
        private String inferenceReason;
        private ConfidenceLevel confidence;
        private Integer logIndex;
    }
}
