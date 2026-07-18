package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Cycle/18 R9: Known bridge routers and reward distributors that must not be classified as
 * generic {@code EXTERNAL_TRANSFER_IN/OUT} with BUY/SELL semantics.
 *
 * <p>ADR-059 (Wave W2): the address sets now live in the {@code counterparty-hints.json} config
 * plane. This class is a thin bind-backed adapter: {@code CounterpartyHintService} binds the
 * membership predicates at startup, and the public static methods keep their signatures so all
 * existing call sites are unchanged. The {@link #normalize(String)} guard is retained so blank /
 * non-{@code 0x} inputs behave exactly as before.</p>
 */
public final class KnownBridgeRouterRegistry {

    private static volatile Predicate<String> bridgeRouterMembership = address -> false;
    private static volatile Predicate<String> rewardDistributorMembership = address -> false;

    private KnownBridgeRouterRegistry() {
    }

    /**
     * Binds the network-agnostic membership predicates (called at startup by
     * {@code CounterpartyHintService}). Predicates receive an already-normalized (lowercase,
     * {@code 0x}-prefixed) address.
     */
    public static void bind(Predicate<String> isBridgeRouter, Predicate<String> isRewardDistributor) {
        bridgeRouterMembership = isBridgeRouter == null ? address -> false : isBridgeRouter;
        rewardDistributorMembership = isRewardDistributor == null ? address -> false : isRewardDistributor;
    }

    public static boolean isKnownBridgeRouter(String address) {
        String normalized = normalize(address);
        return normalized != null && bridgeRouterMembership.test(normalized);
    }

    public static boolean isKnownRewardDistributor(String address) {
        String normalized = normalize(address);
        return normalized != null && rewardDistributorMembership.test(normalized);
    }

    public static boolean touchesKnownBridgeRouter(Iterable<String> addresses) {
        if (addresses == null) {
            return false;
        }
        for (String address : addresses) {
            if (isKnownBridgeRouter(address)) {
                return true;
            }
        }
        return false;
    }

    public static boolean touchesKnownRewardDistributor(Iterable<String> addresses) {
        if (addresses == null) {
            return false;
        }
        for (String address : addresses) {
            if (isKnownRewardDistributor(address)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (!trimmed.startsWith("0x") && !trimmed.startsWith("0X")) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
