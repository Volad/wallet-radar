package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Known GMX V2 handler and vault contracts (Arbitrum + Avalanche, current + deprecated v2.1 set)
 * that return unused execution fees as tiny native transfers to the order creator.
 *
 * <p>Wave W3: the address set now lives in the {@code protocols/gmx-v2.json} config plane under
 * {@code handlerContracts}. This class is a thin bind-backed adapter: {@code GmxHandlerRegistryBinder}
 * binds the membership predicate at startup from {@code ProtocolResourceLoader}, and the public
 * static methods keep their signatures so all existing call sites are unchanged. The
 * {@link #normalize(String)} guard is retained so blank / non-{@code 0x} inputs behave exactly as
 * before.</p>
 *
 * <p>Sources: docs.gmx.io/docs/api/contracts/addresses/ (current + deprecated v2.1 set),
 * Arbiscan / Snowscan verified labels.</p>
 */
public final class GmxV2HandlerRegistry {

    private static volatile Predicate<String> handlerMembership = address -> false;

    private GmxV2HandlerRegistry() {
    }

    /**
     * Binds the network-agnostic handler-membership predicate (called at startup by
     * {@code GmxHandlerRegistryBinder}). The predicate receives an already-normalized (lowercase,
     * {@code 0x}-prefixed) address.
     */
    public static void bind(Predicate<String> isKnownHandler) {
        handlerMembership = isKnownHandler == null ? address -> false : isKnownHandler;
    }

    public static boolean isKnownGmxV2Handler(String address) {
        String normalized = normalize(address);
        return normalized != null && handlerMembership.test(normalized);
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
