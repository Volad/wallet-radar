package com.walletradar.application.pipeline.job.support;

import org.slf4j.Logger;

/**
 * @deprecated Use {@link com.walletradar.platform.common.job.StageExecutionLogSupport}.
 */
@Deprecated
public final class StageExecutionLogSupport {

    private StageExecutionLogSupport() {
    }

    public static long logStart(Logger log, String stageName, String trigger) {
        return com.walletradar.platform.common.job.StageExecutionLogSupport.logStart(log, stageName, trigger);
    }

    public static void logFinish(
            Logger log,
            String stageName,
            String trigger,
            int processed,
            long startedAtNanos
    ) {
        com.walletradar.platform.common.job.StageExecutionLogSupport.logFinish(log, stageName, trigger, processed, startedAtNanos);
    }
}
