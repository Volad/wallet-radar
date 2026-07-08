package com.walletradar.lending.persistence;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "lending_receipt_identity")
@CompoundIndexes({
        @CompoundIndex(
                name = "lending_receipt_identity_network_contract_idx",
                def = "{'networkId': 1, 'contractAddress': 1}",
                unique = true
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LendingReceiptIdentityDocument {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String networkId;
    private String contractAddress;
    private String assetSymbol;
    private String protocol;
    private String underlyingSymbol;
    private String side;
    private String source;
    private String firstSeenTxHash;
    private Instant firstSeenAt;
    private Instant updatedAt;
}
