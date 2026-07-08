package com.walletradar.application.pricing.domain;

/**
 * Time bucket used for deterministic historical price cache keys.
 */
public enum PriceBucketResolution {
    MINUTE,
    HOUR,
    DAY
}
