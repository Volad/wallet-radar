package com.walletradar.domain.transaction.bybit;

import com.walletradar.domain.common.NetworkId;
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
 * Provider-specific Bybit staging row derived from immutable integration raw
 * events. This is the new compatibility staging layer for downstream
 * normalization.
 */
@Document(collection = "bybit_extracted_events")
@CompoundIndexes({
        @CompoundIndex(
                name = "bybit_extracted_status_basis_scope_time_idx",
                def = "{'status': 1, 'basisRelevant': 1, 'outOfScope': 1, 'timeUtc': 1}"
        ),
        @CompoundIndex(
                name = "bybit_extracted_uid_time_idx",
                def = "{'uid': 1, 'timeUtc': 1}"
        ),
        @CompoundIndex(
                name = "bybit_extracted_session_status_idx",
                def = "{'sessionId': 1, 'status': 1}"
        ),
        @CompoundIndex(
                name = "bybit_extracted_tx_hash_idx",
                def = "{'txHash': 1}",
                sparse = true
        ),
        @CompoundIndex(
                name = "bybit_extracted_bridge_corr_status_idx",
                def = "{'sourceFileType': 1, 'onChainCorrelation.status': 1}"
        ),
        @CompoundIndex(
                name = "bybit_extracted_integration_stream_key_idx",
                def = "{'integrationId': 1, 'sourceStream': 1, 'providerEventKey': 1}",
                unique = false
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BybitExtractedEvent {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String integrationRawEventId;
    private String providerEventKey;
    private String sourceStream;
    private String source;
    private String sourceFile;
    private String sourceFileType;
    private String uid;
    private String sessionId;
    private String integrationId;
    private Instant timeUtc;
    private String assetSymbol;
    private BigDecimal quantityRaw;
    private BigDecimal accountBalance;
    private String canonicalType;
    private Boolean basisRelevant;
    private String bybitType;
    private String bybitDescription;
    private String chain;
    private String utaContract;
    private String utaDirection;
    private String utaLegRole;
    private BigDecimal filledPrice;
    private BigDecimal feePaid;
    private BigDecimal cashFlow;
    private BigDecimal change;
    private BigDecimal funding;
    private BigDecimal walletBalance;
    private String txHash;
    private NetworkId networkId;
    private String senderAddress;
    private String receivedAddress;
    private String bybitStatus;
    private String walletRef;
    private Boolean outOfScope;
    private OnChainCorrelation onChainCorrelation = new OnChainCorrelation();
    private BybitExtractedEventStatus status;
    private Instant importedAt;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class OnChainCorrelation {
        private String status;
        private String correlationId;
        private String matchedDocId;
    }
}
