package com.walletradar.costbasis.domain;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Per-counterparty AVCO basis pool (ADR-015 §D1).
 */
@Document(collection = "counterparty_basis_pools")
@CompoundIndexes({
        @CompoundIndex(
                name = "cbp_universe_counterparty_network_family_idx",
                def = "{'universeId': 1, 'counterpartyAddress': 1, 'networkId': 1, 'assetFamily': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "cbp_universe_member_qty_idx",
                def = "{'universeId': 1, 'isMemberAtLastTouch': 1, 'qtyHeld': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CounterpartyBasisPool {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String universeId;
    private String counterpartyAddress;
    private NetworkId networkId;
    private String assetFamily;

    private BigDecimal qtyHeld;
    private BigDecimal basisHeldUsd;
    private BigDecimal avcoUsd;

    private BigDecimal lifetimeOutQty;
    private BigDecimal lifetimeOutBasisUsd;
    private BigDecimal lifetimeInQty;
    private BigDecimal lifetimeInBasisUsd;
    private BigDecimal netCapitalDeltaUsd;

    private String counterpartyTypeAtLastTouch;
    private Boolean isMemberAtLastTouch;
    private List<String> observedAssetSymbols;

    private Instant lastTouchedAt;
    private Instant createdAt;

    public List<String> observedAssetSymbols() {
        if (observedAssetSymbols == null) {
            observedAssetSymbols = new ArrayList<>();
        }
        return observedAssetSymbols;
    }
}
