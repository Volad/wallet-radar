package com.walletradar.domain.transaction.dzengi;

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
 * Provider-specific Dzengi staging row derived from immutable integration raw events.
 */
@Document(collection = "dzengi_extracted_events")
@CompoundIndexes({
        @CompoundIndex(
                name = "dzengi_extracted_status_basis_scope_time_idx",
                def = "{'status': 1, 'basisRelevant': 1, 'outOfScope': 1, 'timeUtc': 1}"
        ),
        @CompoundIndex(
                name = "dzengi_extracted_session_status_idx",
                def = "{'sessionId': 1, 'status': 1}"
        ),
        @CompoundIndex(
                name = "dzengi_extracted_tx_hash_idx",
                def = "{'txHash': 1}",
                sparse = true
        ),
        @CompoundIndex(
                name = "dzengi_extracted_integration_stream_key_idx",
                def = "{'integrationId': 1, 'sourceStream': 1, 'providerEventKey': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DzengiExtractedEvent {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String integrationRawEventId;
    private String providerEventKey;
    private String sourceStream;
    private String sessionId;
    private String integrationId;
    private String userId;
    private String walletRef;
    private Instant timeUtc;
    private String assetSymbol;
    private String quoteAsset;
    private String tradingSymbol;
    private String marketType;
    private String assetType;
    private BigDecimal quantityRaw;
    private BigDecimal price;
    private BigDecimal commission;
    private String commissionAsset;
    private Boolean isBuyer;
    private String canonicalType;
    private Boolean basisRelevant;
    private String dzengiType;
    private String paymentMethod;
    private String txHash;
    private BigDecimal realizedPnl;
    private BigDecimal feePaid;
    private BigDecimal swapCost;
    private String positionId;
    private Boolean outOfScope;
    private DzengiExtractedEventStatus status;
    private Instant importedAt;
}
