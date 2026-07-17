package com.walletradar.domain.common;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Runtime bridge so the read-time conservation gate can resolve the network-agnostic behavioral
 * counterparty-hint sets (bridge payouts, relay sources, LP pools) without importing the
 * normalization-pipeline config plane (ADR-059, Wave W2).
 *
 * <p>Mirrors {@link NetworkNativeAssets} / {@link NetworkStablecoinContracts}: the
 * {@code CounterpartyHintService} binds the membership predicates at startup from the
 * authoritative {@code counterparty-hints.json}, keeping module boundaries intact (the portfolio
 * read model must not depend on {@code ..application.normalization.pipeline..}).</p>
 */
public final class ConservationCounterpartyHints {

    private static volatile Predicate<String> bridgePayoutMembership = address -> false;
    private static volatile Predicate<String> relaySourceMembership = address -> false;
    private static volatile Predicate<String> lpPoolMembership = address -> false;

    private ConservationCounterpartyHints() {
    }

    public static void bind(
            Predicate<String> isBridgePayout,
            Predicate<String> isRelaySource,
            Predicate<String> isLpPool
    ) {
        bridgePayoutMembership = isBridgePayout == null ? address -> false : isBridgePayout;
        relaySourceMembership = isRelaySource == null ? address -> false : isRelaySource;
        lpPoolMembership = isLpPool == null ? address -> false : isLpPool;
    }

    public static boolean isBridgePayout(String address) {
        return contains(bridgePayoutMembership, address);
    }

    public static boolean isRelaySource(String address) {
        return contains(relaySourceMembership, address);
    }

    public static boolean isLpPool(String address) {
        return contains(lpPoolMembership, address);
    }

    private static boolean contains(Predicate<String> membership, String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        return membership.test(address.trim().toLowerCase(Locale.ROOT));
    }
}
