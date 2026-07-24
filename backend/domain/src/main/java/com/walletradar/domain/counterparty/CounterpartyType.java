package com.walletradar.domain.counterparty;

/**
 * Row-local counterparty classes accepted by the cycle 83 metadata contract.
 */
public final class CounterpartyType {

    public static final String CEX = "CEX";
    public static final String PERSONAL_WALLET = "PERSONAL_WALLET";
    public static final String PROTOCOL = "PROTOCOL";
    public static final String BRIDGE = "BRIDGE";
    /**
     * Known external custody destination (an exchange/vault the user designates but that we cannot
     * read into, e.g. Telegram Wallet "Доход"/Earn). Treated with standard external-transfer AVCO
     * semantics — never promoted to {@code INTERNAL_TRANSFER}, never a universe member (ADR-072).
     */
    public static final String EXTERNAL_CUSTODY = "EXTERNAL_CUSTODY";
    public static final String UNKNOWN_EOA = "UNKNOWN_EOA";
    public static final String UNKNOWN_CONTRACT = "UNKNOWN_CONTRACT";
    public static final String GENUINE_MISSING_SOURCE = "GENUINE_MISSING_SOURCE";

    private CounterpartyType() {
    }
}
