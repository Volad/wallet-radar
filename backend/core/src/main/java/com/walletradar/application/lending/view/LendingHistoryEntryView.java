package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record LendingHistoryEntryView(
        String id,
        String txHash,
        String marketKey,
        String cycleId,
        String networkId,
        String walletAddress,
        Instant blockTimestamp,
        String type,
        String eventSubtype,
        String displayType,
        String assetSymbol,
        BigDecimal quantity,
        BigDecimal valueUsd,
        BigDecimal feeUsd,
        Map<String, BigDecimal> feeQuantityByAsset,
        String loopId,
        Map<String, BigDecimal> withdrawYieldByAsset,
        /**
         * WS-8 venue/network-neutral capability carried from the normalized row: {@code true} when
         * the collateral for this lending event is represented by a fungible on-chain receipt token
         * (EVM), {@code false} for receipt-less networks (Solana/TON). Lets the cycle builder decide
         * whether to synthesize outstanding collateral / promote a group to OPEN without re-deriving
         * {@code NetworkAddressFormat.isEvm(networkId)}. {@code null} when the source row predates the
         * WS-8 stamp (renormalization back-fills it).
         */
        Boolean receiptBearingCollateral
) {
}
