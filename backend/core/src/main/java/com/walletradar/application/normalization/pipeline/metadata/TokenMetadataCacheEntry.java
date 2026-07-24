package com.walletradar.application.normalization.pipeline.metadata;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable, write-through cache of resolved token identity (WS-7). Persisting the live-resolved
 * {symbol, decimals} (with first-seen provenance) makes background renormalization deterministic and
 * RPC-free on replay: after the first population, a 2-year renorm reads identity from Mongo instead
 * of re-hitting Jupiter/toncenter (which could otherwise return drifting symbols across runs).
 * Mirrors the persist-then-replay pattern of {@code historical_prices} / {@code v4_pool_state_cache}.
 *
 * <p>The document {@code id} is the deterministic {@code networkId|contract} key (contract kept in
 * its network-native casing: EVM lowercased by the caller, Solana base58 / TON master as emitted),
 * so writes are idempotent upserts.</p>
 */
@Document(collection = "token_metadata_cache")
@CompoundIndex(name = "token_metadata_cache_network_contract_idx", def = "{'networkId': 1, 'contract': 1}", unique = true)
@NoArgsConstructor
@Getter
@Setter
@Accessors(chain = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TokenMetadataCacheEntry {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String networkId;
    private String contract;
    private String symbol;
    private Integer decimals;
    private String source;
    private Instant firstSeenAt;
    private Instant updatedAt;
}
