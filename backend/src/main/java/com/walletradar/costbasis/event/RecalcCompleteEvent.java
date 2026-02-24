package com.walletradar.costbasis.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a RecalcJob completes (COMPLETE or FAILED).
 * Used for audit and optional UI refresh; no mandatory consumer in MVP.
 */
@Getter
public class RecalcCompleteEvent extends ApplicationEvent {

    private final String jobId;
    private final String status;

    public RecalcCompleteEvent(Object source, String jobId, String status) {
        super(source);
        this.jobId = jobId;
        this.status = status;
    }
}
