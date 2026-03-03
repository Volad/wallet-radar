package com.walletradar.domain.transaction.raw;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Immutable on-chain transaction data as fetched from chain source. Never mutated after ingestion (INV-02).
 * Schema varies per network; rawData holds the full native payload (BSON Document).
 * ADR-026: EVM stores transaction details at fetch time; receipt may be appended later for selective clarification.
 * Solana stores full tx+sigInfo payload.
 */
@Document(collection = "raw_transactions")
@CompoundIndex(name = "txHash_networkId_wallet", def = "{'txHash': 1, 'networkId': 1, 'walletAddress': 1}", unique = true)
@CompoundIndex(name = "wallet_network_block", def = "{'walletAddress': 1, 'networkId': 1, 'blockNumber': 1}")
@CompoundIndex(name = "wallet_network_slot", def = "{'walletAddress': 1, 'networkId': 1, 'slot': 1}")
@CompoundIndex(name = "wallet_network_status", def = "{'walletAddress': 1, 'networkId': 1, 'normalizationStatus': 1}")
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
    /** Ingestion source method for this raw tx: ETHERSCAN | BLOCKSCOUT | RPC. */
    private RawSyncMethod syncMethod;
    /** Required; we fetch per wallet. */
    private String walletAddress;
    /** EVM: from receipt; used for range queries. */
    private Long blockNumber;
    /** Solana: from sigInfo; used for range queries. */
    private Long slot;
    /** PENDING | COMPLETE — processor selects PENDING. */
    private NormalizationStatus normalizationStatus;
    /** Retry metadata for transient normalization failures. */
    private Integer retryCount;
    private String lastError;
    private Instant nextRetryAt;
    /** Debugging and retry ordering. */
    private Instant createdAt;
    /** Full source payload: EVM = tx details (+ optional receipt enrichment), Solana = full tx + sigInfo. */
    private org.bson.Document rawData;
}
