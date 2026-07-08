package com.walletradar.platform.common.job;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Shared start/finish logging for staged pipeline jobs.
 */
public final class StageExecutionLogSupport {

    private StageExecutionLogSupport() {
    }

    public static long logStart(Logger log, String stageName, String trigger) {
        long startedAtNanos = System.nanoTime();
        log.info("{} stage started, trigger={}", stageName, trigger);
        return startedAtNanos;
    }

    public static void logFinish(
            Logger log,
            String stageName,
            String trigger,
            int processed,
            long startedAtNanos
    ) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        log.info("{} stage finished, trigger={}, processed={}, durationMs={}",
                stageName,
                trigger,
                processed,
                durationMs);
    }
}
