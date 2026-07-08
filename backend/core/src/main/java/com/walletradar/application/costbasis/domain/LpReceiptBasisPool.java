package com.walletradar.application.costbasis.domain;

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
 * Cycle/15 Z3: per-LP-position basis pool holding cost basis while principal assets sit in LP receipts.
 */
@Document(collection = "lp_receipt_basis_pools")
@CompoundIndexes({
        @CompoundIndex(
                name = "lrp_universe_corr_asset_idx",
                def = "{'universeId': 1, 'lpCorrelationId': 1, 'assetIdentity': 1}",
                unique = true
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LpReceiptBasisPool {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String universeId;
    private String lpCorrelationId;
    private String walletAddress;
    private NetworkId networkId;
    private String assetIdentity;
    private String assetSymbol;
    private String assetContract;

    private BigDecimal qtyHeld;
    private BigDecimal basisHeldUsd;
    private BigDecimal uncoveredQtyHeld;
    private BigDecimal avcoUsd;
    /** ADR-040 Change 2: net cost lane — null on legacy documents (treated as basisHeldUsd). */
    private BigDecimal netBasisHeldUsd;

    private Instant lastTouchedAt;
    private Instant createdAt;
}
