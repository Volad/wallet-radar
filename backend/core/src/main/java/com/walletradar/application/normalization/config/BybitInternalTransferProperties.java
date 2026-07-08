package com.walletradar.application.normalization.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cycle/12: tuning for Bybit {@code INTERNAL_TRANSFER} post-normalization pairing passes.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.bybit.internal-transfer")
@NoArgsConstructor
@Getter
@Setter
public class BybitInternalTransferProperties {

    /** Sliding-window size for N-way near-zero bundle detection. */
    private int bundleWindowSeconds = 60;

    /** Max |sum(qty)| / max(abs(qty)) for a bundle to be accepted. */
    private double bundleResidualPct = 0.01;

    /** Minimum legs in a bundle (typically 3: UTA + FUND + EARN). */
    private int bundleMinMembers = 3;

    /** Max calendar span for same-wallet Earn round-trip pairing. */
    private int roundtripWindowDays = 14;

    /** Max relative qty mismatch for round-trip opposite legs. */
    private double roundtripTolerancePct = 0.001;
}
