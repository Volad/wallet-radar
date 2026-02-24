package com.walletradar.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Value object for asset quantity. Encapsulates non-null BigDecimal.
 * Sign convention: positive = received/held, negative = sent (e.g. quantityDelta).
 * For position quantities (derived balance), quantity must be non-negative (INV-06, domain invariants).
 */
public final class Quantity {

    private final BigDecimal value;

    private Quantity(BigDecimal value) {
        this.value = Objects.requireNonNull(value, "quantity value must not be null");
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    /**
     * Quantity for a position (derived balance). Must be non-negative.
     */
    public static Quantity positionQuantity(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Position quantity must be non-negative, got: " + value);
        }
        return new Quantity(value);
    }

    public BigDecimal getValue() {
        return value;
    }

    public boolean isNegative() {
        return value.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quantity quantity = (Quantity) o;
        return value.compareTo(quantity.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros());
    }
}
