package com.walletradar.domain.transaction.externalledger;

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
 * Immutable Bybit source row used to materialize canonical accounting docs.
 */
@Document(collection = "external_ledger_raw")
@CompoundIndexes({
        @CompoundIndex(
                name = "external_ledger_status_basis_scope_time_idx",
                def = "{'status': 1, 'basisRelevant': 1, 'outOfScope': 1, 'timeUtc': 1}"
        ),
        @CompoundIndex(
                name = "external_ledger_uid_time_idx",
                def = "{'uid': 1, 'timeUtc': 1}"
        ),
        @CompoundIndex(
                name = "external_ledger_tx_hash_idx",
                def = "{'txHash': 1}",
                sparse = true
        ),
        @CompoundIndex(
                name = "external_ledger_bridge_corr_status_idx",
                def = "{'sourceFileType': 1, 'onChainCorrelation.status': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExternalLedgerRaw {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String source;
    private String sourceFile;
    private String sourceFileType;
    private String uid;
    private String sessionId;
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
    private String receivedAddress;
    private String bybitStatus;
    private String walletRef;
    private Boolean outOfScope;
    private OnChainCorrelation onChainCorrelation = new OnChainCorrelation();
    private ExternalLedgerRawStatus status;
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
