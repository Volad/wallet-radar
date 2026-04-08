package com.walletradar.domain.transaction.bybit;

/**
 * Processing lifecycle of extracted Bybit provider events before canonical
 * normalization.
 */
public enum BybitExtractedEventStatus {
    RAW,
    CONFIRMED
}
