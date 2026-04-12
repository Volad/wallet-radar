package com.walletradar.costbasis.application.replay.model;

public record CorrelationRef(String value) {
    public static CorrelationRef from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new CorrelationRef(raw.trim());
    }
}
