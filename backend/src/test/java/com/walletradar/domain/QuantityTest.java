package com.walletradar.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityTest {

    @Nested
    @DisplayName("of(BigDecimal)")
    class Of {
        @Test
        void acceptsPositive() {
            Quantity q = Quantity.of(new BigDecimal("100.5"));
            assertThat(q.getValue()).isEqualByComparingTo("100.5");
            assertThat(q.isNegative()).isFalse();
            assertThat(q.isZero()).isFalse();
        }

        @Test
        void acceptsNegative() {
            Quantity q = Quantity.of(new BigDecimal("-10"));
            assertThat(q.getValue()).isEqualByComparingTo("-10");
            assertThat(q.isNegative()).isTrue();
        }

        @Test
        void acceptsZero() {
            Quantity q = Quantity.of(BigDecimal.ZERO);
            assertThat(q.isZero()).isTrue();
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> Quantity.of(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("quantity value must not be null");
        }
    }

    @Nested
    @DisplayName("positionQuantity(BigDecimal)")
    class PositionQuantity {
        @Test
        void acceptsPositive() {
            Quantity q = Quantity.positionQuantity(new BigDecimal("50.25"));
            assertThat(q.getValue()).isEqualByComparingTo("50.25");
        }

        @Test
        void acceptsZero() {
            Quantity q = Quantity.positionQuantity(BigDecimal.ZERO);
            assertThat(q.isZero()).isTrue();
        }

        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> Quantity.positionQuantity(new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Position quantity must be non-negative");
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> Quantity.positionQuantity(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-negative");
        }
    }

    @Test
    void equalityUsesNumericValue() {
        assertThat(Quantity.of(new BigDecimal("10.00")))
                .isEqualTo(Quantity.of(new BigDecimal("10.000")));
    }
}
