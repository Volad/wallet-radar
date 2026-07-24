package com.walletradar.application.lending.spi;

import java.math.BigDecimal;

/**
 * A single collateral or debt leg of a {@link LiveLendingPosition}.
 *
 * @param assetSymbol   accounting/display symbol (e.g. {@code SOL}, {@code USDT})
 * @param assetContract on-chain asset identity (SPL mint / native sentinel) used for balance
 *                      contribution + accounting-identity merging
 * @param decimals      token decimals used to scale the protocol's base-unit amount
 * @param quantity      human-scaled quantity ({@code rawBaseUnits / 10^decimals})
 * @param marketValueUsd market value in USD at read time (carry value; not a minted basis)
 */
public record LiveLendingAssetAmount(
        String assetSymbol,
        String assetContract,
        Integer decimals,
        BigDecimal quantity,
        BigDecimal marketValueUsd
) {
}
