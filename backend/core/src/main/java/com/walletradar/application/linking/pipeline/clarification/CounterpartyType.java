package com.walletradar.application.linking.pipeline.clarification;

/**
 * @deprecated Use {@link com.walletradar.domain.counterparty.CounterpartyType}.
 */
@Deprecated
public final class CounterpartyType {

    public static final String CEX = com.walletradar.domain.counterparty.CounterpartyType.CEX;
    public static final String PERSONAL_WALLET =
            com.walletradar.domain.counterparty.CounterpartyType.PERSONAL_WALLET;
    public static final String PROTOCOL = com.walletradar.domain.counterparty.CounterpartyType.PROTOCOL;
    public static final String BRIDGE = com.walletradar.domain.counterparty.CounterpartyType.BRIDGE;
    public static final String UNKNOWN_EOA = com.walletradar.domain.counterparty.CounterpartyType.UNKNOWN_EOA;
    public static final String UNKNOWN_CONTRACT =
            com.walletradar.domain.counterparty.CounterpartyType.UNKNOWN_CONTRACT;
    public static final String GENUINE_MISSING_SOURCE =
            com.walletradar.domain.counterparty.CounterpartyType.GENUINE_MISSING_SOURCE;

    private CounterpartyType() {
    }
}
