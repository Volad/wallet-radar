package com.walletradar.canonical.correlation;

/**
 * Shared Bybit correlation prefix constants and wallet sub-account suffixes.
 * Single source of truth for normalization, linking, and cost-basis replay.
 */
public final class CorrelationContract {

    public static final String BYBIT_COLLAPSED_V1_PREFIX = "bybit-collapsed-v1:";
    public static final String BYBIT_CROSS_UID_V1_PREFIX = "bybit-cross-uid-v1:";
    public static final String BYBIT_REKEYED_V1_PREFIX = "bybit-rekeyed-v1:";
    public static final String BYBIT_IT_BUNDLE_V1_PREFIX = "bybit-it-bundle-v1:";
    public static final String BYBIT_EARN_PRINCIPAL_V1_PREFIX = "bybit-earn-principal-v1:";
    public static final String BYBIT_EARN_ONCHAIN_V1_PREFIX = "bybit-earn-onchain-v1:";
    public static final String BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX = "bybit-earn-onchain-fund-v1:";
    /**
     * ADR-056: Bybit On-chain Earn FUND self round-trip — same-asset subscribe-out and redeem-in
     * both on {@code :FUND} (no {@code :EARN} counterpart). Distinct from
     * {@link #BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX} which is for corridor-funded FUND→EARN repairs.
     */
    public static final String BYBIT_EARN_SELF_RT_V1_PREFIX = "bybit-earn-self-rt-v1:";
    public static final String BYBIT_ECON_V1_PREFIX = "bybit-econ-v1:";
    public static final String BYBIT_CORRIDOR_PREFIX = "BYBIT-CORRIDOR:";
    public static final String CORR_FAMILY_PREFIX = "corr-family:";
    public static final String BYBIT_EARN_CARRY_PREFIX = "bybit-earn-carry:";

    /**
     * B-ETH-02: shared correlation prefix stamped on a {@code LENDING_LOOP_OPEN} and each of its
     * subsequent {@code LENDING_LOOP_DECREASE} / {@code LENDING_LOOP_CLOSE} legs by
     * {@code LendingLoopOpenClosePairLinkService}. The full correlation id is
     * {@code lending-loop:{openTxHash}} — unique per OPEN instance so a position reopened after a
     * full close receives a fresh correlation (basis conservation must not bleed across loop
     * instances). Replay anchors a network-agnostic continuity bucket on this correlation so the
     * collateral principal parked on open is restored on close/decrease regardless of the network
     * on which the loop is unwound.
     */
    public static final String LENDING_LOOP_PREFIX = "lending-loop:";

    public static final String WALLET_SUFFIX_FUND = ":FUND";
    public static final String WALLET_SUFFIX_UTA = ":UTA";
    public static final String WALLET_SUFFIX_EARN = ":EARN";

    public static final String VENUE_BYBIT = "BYBIT";

    private CorrelationContract() {
    }
}
