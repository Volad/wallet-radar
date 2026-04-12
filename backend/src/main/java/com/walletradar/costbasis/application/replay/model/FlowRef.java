package com.walletradar.costbasis.application.replay.model;

public record FlowRef(String value) {
    public static FlowRef of(String value) {
        return new FlowRef(value);
    }
}
