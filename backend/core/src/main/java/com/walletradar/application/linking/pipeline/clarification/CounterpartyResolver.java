package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Per-{@link NetworkId}-family counterparty resolution SPI (ADR-066).
 *
 * <p>Counterparty resolution reads chain-specific raw evidence (EVM receipt shapes vs. Helius
 * Solana payloads vs. TON messages), so the family-specific logic lives behind this interface
 * instead of being scattered as {@code switch(NetworkId)} inside the shared enrichment services.
 * {@link CounterpartyEnrichmentService#enrichInPlace} selects the first resolver whose
 * {@link #supports(NetworkId)} matches the transaction's network.</p>
 *
 * <p>Implementations must set both the transaction-level counterparty metadata
 * ({@code counterpartyAddress}, {@code counterpartyType}, resolution state/evidence) and the
 * flow-level counterparty on every non-fee flow, so the row clears both
 * {@code STAT_COUNTERPARTY_TYPE_MISSING} and {@code FLOW_COUNTERPARTY_MISSING}.</p>
 */
public interface CounterpartyResolver {

    /**
     * @return {@code true} when this resolver handles the given network. A {@code null} network is
     * treated as EVM to preserve legacy behaviour for rows that predate the network field.
     */
    boolean supports(@Nullable NetworkId networkId);

    /**
     * Enriches transaction-level and flow-level counterparty metadata in place.
     *
     * @return {@code true} when any field changed (so the caller can decide whether to persist).
     */
    boolean enrichInPlace(NormalizedTransaction normalizedTransaction, @Nullable RawTransaction rawTransaction, Instant now);
}
