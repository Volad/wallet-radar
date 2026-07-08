package com.walletradar.domain.transaction.normalized;

/**
 * Canonical {@code missingDataReasons} tokens stored on {@link NormalizedTransaction}.
 */
public final class MissingDataReasons {

    public static final String PRICE_UNRESOLVABLE = "PRICE_UNRESOLVABLE";
    public static final String PRICING_EXECUTION_FAILED = "PRICING_EXECUTION_FAILED";

    private MissingDataReasons() {
    }
}
