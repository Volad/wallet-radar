package com.walletradar.pricing;

import com.walletradar.domain.NetworkId;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Request for historical USD price resolution. Optional swap leg for SwapDerivedResolver.
 */
@NoArgsConstructor
@Getter
@Setter
public class HistoricalPriceRequest {

    private String assetContract;
    private NetworkId networkId;
    private Instant blockTimestamp;
    /** Optional: other token in swap (contract), its amount, and our token amount for ratio-derived price. */
    private String counterpartContract;
    private BigDecimal counterpartAmount;
    private BigDecimal ourAmount;

    public LocalDate getDate() {
        return blockTimestamp == null ? null : blockTimestamp.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public Optional<SwapLeg> getSwapLeg() {
        if (counterpartContract == null || counterpartAmount == null || ourAmount == null
                || counterpartAmount.compareTo(BigDecimal.ZERO) == 0
                || ourAmount.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.empty();
        }
        return Optional.of(new SwapLeg(counterpartContract, counterpartAmount, ourAmount));
    }

    public record SwapLeg(String counterpartContract, BigDecimal counterpartAmount, BigDecimal ourAmount) {}
}
