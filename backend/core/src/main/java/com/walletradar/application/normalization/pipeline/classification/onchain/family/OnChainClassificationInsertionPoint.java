package com.walletradar.application.normalization.pipeline.classification.onchain.family;

/**
 * Ordered execution stages for family classifiers inside the on-chain classifier orchestrator.
 */
public enum OnChainClassificationInsertionPoint {
    EARLY_GUARDS,
    PRE_ECONOMIC_REVIEW,
    PRE_PROTOCOL_REVIEW,
    PROTOCOL_LIFECYCLE,
    PRE_SPAM_REVIEW,
    POST_SPAM_REVIEW,
    FINAL_FALLBACK
}
