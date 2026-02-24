package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Current on-chain asset quantity for (wallet, network, asset). Updated by balance poll; used for reconciliation.
 */
@Document(collection = "on_chain_balances")
@CompoundIndex(name = "wallet_network_asset", def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1}", unique = true)
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OnChainBalance {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private String walletAddress;
    private String networkId;
    private String assetContract;
    private BigDecimal quantity;
    private Instant capturedAt;
}
