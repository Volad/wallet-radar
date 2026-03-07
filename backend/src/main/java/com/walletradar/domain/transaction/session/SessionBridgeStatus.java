package com.walletradar.domain.transaction.session;

/**
 * Session-level bridge lifecycle status.
 */
public enum SessionBridgeStatus {
    BRIDGE_OUT,
    BRIDGE_IN,
    MATCHED,
    REVIEW
}
