package com.walletradar.application.liquiditypools.enrichment;

/**
 * Request-scoped policy for whether {@link LiquidityDepthReader} may hit RPC on cache miss.
 * Manual/bulk refresh skips depth fetch (uses cache or keeps prior bins) to avoid batch RPC storms.
 */
public final class LpDepthFetchPolicy {

    private static final ThreadLocal<Boolean> SKIP_RPC_FETCH = ThreadLocal.withInitial(() -> false);

    private LpDepthFetchPolicy() {
    }

    public static boolean skipRpcFetch() {
        return Boolean.TRUE.equals(SKIP_RPC_FETCH.get());
    }

    public static void runSkippingRpcFetch(Runnable action) {
        SKIP_RPC_FETCH.set(true);
        try {
            action.run();
        } finally {
            SKIP_RPC_FETCH.remove();
        }
    }

    public static <T> T callSkippingRpcFetch(java.util.function.Supplier<T> action) {
        SKIP_RPC_FETCH.set(true);
        try {
            return action.get();
        } finally {
            SKIP_RPC_FETCH.remove();
        }
    }
}
