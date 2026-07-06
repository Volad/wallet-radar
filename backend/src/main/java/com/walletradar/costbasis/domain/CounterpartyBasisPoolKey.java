package com.walletradar.costbasis.domain;

import com.walletradar.domain.common.NetworkId;

/**
 * In-memory key for a counterparty basis pool (ADR-015 §D3).
 */
public record CounterpartyBasisPoolKey(
        String universeId,
        String counterpartyLower,
        NetworkId networkId,
        String assetFamily
) {

    public String documentId() {
        return universeId
                + ":"
                + counterpartyLower
                + ":"
                + (networkId == null ? "UNKNOWN" : networkId.name())
                + ":"
                + assetFamily;
    }
}
