package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Immutable on-chain transaction data as fetched from RPC. Never mutated after ingestion (INV-02).
 * Schema varies per network; rawData holds the native payload (BSON Document).
 */
@Document(collection = "raw_transactions")
@CompoundIndex(name = "txHash_networkId", def = "{'txHash': 1, 'networkId': 1}", unique = true)
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RawTransaction {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private String txHash;
    private String networkId;
    private org.bson.Document rawData;
}
