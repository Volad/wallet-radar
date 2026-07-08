package com.walletradar.application.liquiditypools.application;

public enum LpPositionScope {
    ACTIVE,
    CLOSED,
    ALL;

    public static LpPositionScope fromQuery(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        return switch (value.trim().toLowerCase()) {
            case "closed" -> CLOSED;
            case "all" -> ALL;
            default -> ACTIVE;
        };
    }
}
