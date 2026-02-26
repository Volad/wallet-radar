package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Immutable on-chain transaction data as fetched from RPC. Never mutated after ingestion (INV-02).
 * Schema varies per network; rawData holds the full native payload (BSON Document).
 * ADR-020: full receipt (EVM) or full tx+sigInfo (Solana) stored for classification retry without re-fetch.
 */
@Document(collection = "raw_transactions")
@CompoundIndex(name = "txHash_networkId", def = "{'txHash': 1, 'networkId': 1}", unique = true)
@CompoundIndex(name = "wallet_network_block", def = "{'walletAddress': 1, 'networkId': 1, 'blockNumber': 1}")
@CompoundIndex(name = "wallet_network_slot", def = "{'walletAddress': 1, 'networkId': 1, 'slot': 1}")
@CompoundIndex(name = "wallet_network_status", def = "{'walletAddress': 1, 'networkId': 1, 'classificationStatus': 1}")
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
    /** Required; we fetch per wallet. */
    private String walletAddress;
    /** EVM: from receipt; used for range queries. */
    private Long blockNumber;
    /** Solana: from sigInfo; used for range queries. */
    private Long slot;
    /** PENDING | COMPLETE | FAILED — processor selects PENDING. */
    private ClassificationStatus classificationStatus;
    /** Debugging and retry ordering. */
    private Instant createdAt;
    /** Full RPC payload: EVM = full receipt; Solana = full tx + sigInfo. */
    private org.bson.Document rawData;
}
