package com.walletradar.pricing.resolver;

import com.walletradar.common.StablecoinRegistry;
import com.walletradar.domain.PriceSource;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resolves stablecoins (USDC, USDT, DAI, GHO, USDe, FRAX) to $1.00 per 01-domain.
 */
@Component
@RequiredArgsConstructor
public class StablecoinResolver {

    private static final BigDecimal ONE_USD = BigDecimal.ONE.setScale(18, RoundingMode.HALF_UP);

    private final StablecoinRegistry stablecoinRegistry;

    /**
     * Returns STABLECOIN $1.00 if asset is a known stablecoin, otherwise null (next resolver).
     */
    public PriceResolutionResult resolve(HistoricalPriceRequest request) {
        if (request == null || request.getAssetContract() == null) {
            return PriceResolutionResult.unknown();
        }
        if (stablecoinRegistry.isStablecoin(request.getAssetContract())) {
            return PriceResolutionResult.known(ONE_USD, PriceSource.STABLECOIN);
        }
        return PriceResolutionResult.unknown();
    }
}
