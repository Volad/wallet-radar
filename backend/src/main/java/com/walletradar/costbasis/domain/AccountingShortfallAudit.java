package com.walletradar.costbasis.domain;

import com.walletradar.domain.common.NetworkId;
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
 * DEBUG / DIAGNOSTIC (Cycle 15) — TEMPORARY.
 * Pure write-only diagnostic that captures replay shortfall events to
 * {@code accounting_shortfall_audit}. Not consumed by any production logic.
 * REMOVE after coverage acceptance.
 */
@Document(collection = "accounting_shortfall_audit")
@CompoundIndexes({
        @CompoundIndex(
                name = "asa_universe_ts_idx",
                def = "{'accountingUniverseId': 1, 'blockTimestamp': -1}"
        ),
        @CompoundIndex(
                name = "asa_tx_idx",
                def = "{'normalizedTransactionId': 1, 'flowIndex': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
public class AccountingShortfallAudit {

    @Id
    private String id;

    private String accountingUniverseId;
    private String normalizedTransactionId;
    private String normalizedType;
    private String walletAddress;
    private NetworkId networkId;
    private String assetSymbol;
    private String assetIdentity;
    private String correlationId;
    private int flowIndex;
    private BigDecimal quantityShortfallDelta;
    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;
    private BigDecimal basisBackedBefore;
    private BigDecimal basisBackedAfter;
    private Instant blockTimestamp;
    private Instant createdAt;
}
