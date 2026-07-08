package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;

/**
 * Post-processes an already-produced {@link LpPositionSnapshot} to augment it with
 * additional on-chain data that the primary {@link LpPositionReader} does not cover.
 *
 * Examples: pending farming rewards from a staking contract (MasterChef),
 * external reward tokens, etc.
 *
 * Enrichers run after a primary reader succeeds. They must never throw —
 * any failure should be swallowed and logged so the base snapshot is still saved.
 */
public interface LpSnapshotEnricher {

    /** Returns true if this enricher can augment the snapshot for the given context. */
    boolean supports(LpPositionContext context);

    /** Mutates {@code snapshot} in-place to add extra data. */
    void enrich(LpPositionContext context, LpPositionSnapshot snapshot);
}
