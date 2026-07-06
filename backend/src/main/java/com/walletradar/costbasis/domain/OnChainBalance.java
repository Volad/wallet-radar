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

/**
 * Latest observed current-balance evidence used by post-replay reconciliation.
 */
@Document(collection = "on_chain_balances")
@CompoundIndexes({
        @CompoundIndex(
                name = "on_chain_balance_session_wallet_network_asset_idx",
                def = "{'sessionId': 1, 'walletAddress': 1, 'networkId': 1, 'assetContract': 1, 'capturedAt': -1}"
        ),
        @CompoundIndex(
                name = "on_chain_balance_session_wallet_symbol_idx",
                def = "{'sessionId': 1, 'walletAddress': 1, 'assetSymbol': 1, 'capturedAt': -1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OnChainBalance {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String sessionId;
    private String walletAddress;
    private NetworkId networkId;
    private String assetSymbol;
    private String assetContract;
    private Integer tokenDecimals;
    private BigDecimal quantity;
    private Instant capturedAt;
}
