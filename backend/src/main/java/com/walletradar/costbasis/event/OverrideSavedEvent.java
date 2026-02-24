package com.walletradar.costbasis.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published after an override is saved or reverted (cost_basis_overrides upsert/deactivate).
 * RecalcJobService consumes this and runs AvcoEngine.recalculate async on recalc-executor (Data Flow 4).
 */
@Getter
public class OverrideSavedEvent extends ApplicationEvent {

    private final String jobId;

    public OverrideSavedEvent(Object source, String jobId) {
        super(source);
        this.jobId = jobId;
    }
}
