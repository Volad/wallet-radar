package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Point-in-time asset entry within a portfolio snapshot. All monetary fields are BigDecimal (INV-06).
 */
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AssetSnapshot {

    @EqualsAndHashCode.Include
    private String assetSymbol;
    @EqualsAndHashCode.Include
    private BigDecimal quantity;
    @EqualsAndHashCode.Include
    private BigDecimal perWalletAvco;
    private BigDecimal spotPriceUsd;
    private BigDecimal valueUsd;
    private BigDecimal unrealisedPnlUsd;
    private boolean isResolved;
}
