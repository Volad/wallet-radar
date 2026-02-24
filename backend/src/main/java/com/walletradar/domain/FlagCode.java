package com.walletradar.domain;

/**
 * Marker on an economic event indicating it requires human review or has incomplete data.
 */
public enum FlagCode {
    EXTERNAL_INBOUND,
    PRICE_UNKNOWN,
    LP_MANUAL_REQUIRED,
    REWARD_INBOUND,
    UNSUPPORTED_TYPE
}
