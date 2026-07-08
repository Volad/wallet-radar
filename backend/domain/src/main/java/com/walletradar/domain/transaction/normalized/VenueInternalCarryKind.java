package com.walletradar.domain.transaction.normalized;

/**
 * How an intra-venue Bybit transfer participates in basis carry during AVCO replay.
 */
public enum VenueInternalCarryKind {
    /** Matched via {@code corr-family:<correlationId>:<asset>} queue. */
    CORR_FAMILY,
    /** Matched via {@code bybit-earn-carry:<uid>:<asset>} FIFO queue. */
    EARN_CARRY_FIFO,
    /** UTA↔UTA (or equivalent) self-transfer — skipped as a replay no-op. */
    SELF_TRANSFER_NOOP,
    /** Not an intra-venue carry path (external / corridor on-chain leg / etc.). */
    NOT_APPLICABLE
}
