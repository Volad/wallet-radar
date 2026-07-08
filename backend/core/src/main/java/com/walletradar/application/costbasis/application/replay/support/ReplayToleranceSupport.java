package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.ReplayToleranceProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Shared replay tolerances used by transfer handlers/support.
 */
@Component
public class ReplayToleranceSupport {

    private static ReplayToleranceProperties properties;

    public ReplayToleranceSupport(ReplayToleranceProperties properties) {
        ReplayToleranceSupport.properties = properties;
    }

    /**
     * Coverage ratio that treats tiny residual dust as non-covering inventory.
     */
    public static BigDecimal carrySourceCoverageRatio() {
        if (properties == null) {
            return new BigDecimal("0.999");
        }
        return properties.getCarrySourceCoverageRatio();
    }

    /**
     * Relative tolerance for same-day VAULT_WITHDRAW round-trip quantity drift.
     */
    public static final BigDecimal VAULT_WITHDRAW_ROUND_TRIP_TOLERANCE = new BigDecimal("0.0001");

    /**
     * Generic quantity dust threshold used for partial-match slicing.
     */
    public static final BigDecimal QUANTITY_DUST_THRESHOLD = new BigDecimal("0.00000001");
}
