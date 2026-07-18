package com.walletradar.application.normalization.store;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

/**
 * Extension point called by {@link IdempotentNormalizedTransactionStore} just before a
 * normalized transaction is written to MongoDB.
 *
 * <p>Implementations may stamp additional fields (e.g. boundary-contract markers) on the
 * candidate without needing to modify normalization builder code. The store collects all
 * {@code @Component} implementations via {@code List<NormalizedTransactionPostProcessor>}.</p>
 */
public interface NormalizedTransactionPostProcessor {

    /**
     * Process the candidate normalized transaction in-place before it is persisted.
     * Called on both inserts and updates; implementations must be idempotent.
     *
     * @param candidate the normalized transaction about to be written (mutable)
     */
    void process(NormalizedTransaction candidate);
}
