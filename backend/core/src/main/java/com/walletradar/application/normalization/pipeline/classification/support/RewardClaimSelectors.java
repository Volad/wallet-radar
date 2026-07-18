package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Reward-claim method selectors shared between reward-claim classification
 * ({@link InboundSignalSupport}) and scam-filter legitimacy checks ({@code ScamFilter}).
 *
 * <p>Extracted (Wave W4+) to give the copied 8-selector core a single source. The two consumers'
 * full sets are <em>not</em> identical and must not be merged: {@code ScamFilter} additionally
 * treats the bridge {@code redeemWithFee} selector ({@code 0xe2de2a03}) as legit, while
 * {@code InboundSignalSupport} additionally treats a generic {@code claim(uint256,address,…)}
 * selector ({@code 0x5d4df3bf}) as claim-like. Each composes this shared core with
 * {@link #withExtra(String...)} so membership stays behavior-identical.</p>
 */
public final class RewardClaimSelectors {

    /** The 8 reward-claim selectors common to both consumers. */
    public static final Set<String> SHARED_CLAIM_SELECTORS = Set.of(
            "0x9fb67b58", // claimWithRecipient
            "0x71ee95c0", // Merkl / Angle claim
            "0xb7034f7e", // Compound claim
            "0xbe5013dc", // FLUID claim
            "0x5eac6239", // Pendle claim
            "0x8b681820", // BSC claim-by-proof
            "0x379607f5", // stream claim
            "0x2f52ebb7"  // merkle claim
    );

    private RewardClaimSelectors() {
    }

    /** Immutable union of {@link #SHARED_CLAIM_SELECTORS} with consumer-specific extra selectors. */
    public static Set<String> withExtra(String... extra) {
        Set<String> set = new HashSet<>(SHARED_CLAIM_SELECTORS);
        set.addAll(Arrays.asList(extra));
        return Set.copyOf(set);
    }
}
