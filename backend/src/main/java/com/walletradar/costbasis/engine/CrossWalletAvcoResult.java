package com.walletradar.costbasis.engine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * On-request cross-wallet AVCO result (never persisted, INV-04).
 */
@Getter
@RequiredArgsConstructor
public class CrossWalletAvcoResult {

    private final BigDecimal crossWalletAvco;
    private final BigDecimal quantity;

    public static CrossWalletAvcoResult of(BigDecimal crossWalletAvco, BigDecimal quantity) {
        return new CrossWalletAvcoResult(
                crossWalletAvco != null ? crossWalletAvco : BigDecimal.ZERO,
                quantity != null ? quantity : BigDecimal.ZERO);
    }
}
