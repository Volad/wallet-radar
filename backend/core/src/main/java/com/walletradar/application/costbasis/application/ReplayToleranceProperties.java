package com.walletradar.application.costbasis.application;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "walletradar.replay.tolerance")
@NoArgsConstructor
@Getter
@Setter
public class ReplayToleranceProperties {

    private BigDecimal carrySourceCoverageRatio = new BigDecimal("0.999");
    private BigDecimal absoluteFloorUsd = new BigDecimal("50");
    private BigDecimal relativeMtmFraction = new BigDecimal("0.01");
    private BigDecimal bridgePairAmountTolerance = new BigDecimal("0.015");
}
