package com.walletradar.pricing.resolver;

import com.walletradar.domain.PriceSource;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.resolver.CounterpartPriceResolver;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Derives price from swap tokenIn/tokenOut ratio when request has swap leg and counterpart price is known.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SwapDerivedResolver {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final CounterpartPriceResolver counterpartPriceResolver;

    /**
     * If request has swap leg, resolves counterpart price (one level) and returns price = counterpartPrice * counterpartAmount / ourAmount.
     * Otherwise returns UNKNOWN.
     */
    public PriceResolutionResult resolve(HistoricalPriceRequest request) {
        if (request == null) {
            return PriceResolutionResult.unknown();
        }
        var swapLeg = request.getSwapLeg();
        if (swapLeg.isEmpty()) {
            return PriceResolutionResult.unknown();
        }
        HistoricalPriceRequest counterpartRequest = new HistoricalPriceRequest();
        counterpartRequest.setAssetContract(request.getCounterpartContract());
        counterpartRequest.setNetworkId(request.getNetworkId());
        counterpartRequest.setBlockTimestamp(request.getBlockTimestamp());
        // No swap leg for counterpart to avoid recursion
        PriceResolutionResult counterpartResult = counterpartPriceResolver.resolve(counterpartRequest);
        if (counterpartResult.isUnknown()) {
            return PriceResolutionResult.unknown();
        }
        BigDecimal cp = counterpartResult.getPriceUsd().orElseThrow();
        BigDecimal otherAmount = swapLeg.get().counterpartAmount();
        BigDecimal ourAmount = swapLeg.get().ourAmount();
        if (ourAmount.compareTo(BigDecimal.ZERO) == 0) {
            return PriceResolutionResult.unknown();
        }
        BigDecimal priceUsd = cp.multiply(otherAmount).divide(ourAmount, SCALE, ROUNDING);
        return PriceResolutionResult.known(priceUsd, PriceSource.SWAP_DERIVED);
    }
}
