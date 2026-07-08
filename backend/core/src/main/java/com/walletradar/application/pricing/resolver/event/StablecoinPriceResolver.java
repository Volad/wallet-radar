package com.walletradar.application.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceResolutionContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Assigns $1 parity to explicitly trusted USD stablecoins.
 */
@Component
@Order(0)
public class StablecoinPriceResolver implements EventLocalPriceResolver {

    private static final BigDecimal PARITY = BigDecimal.ONE;

    @Override
    public Optional<PriceQuote> resolve(PriceResolutionContext context) {
        if (!CanonicalAssetCatalog.isUsdStablecoin(
                context.transaction().getNetworkId(),
                context.flow().getAssetContract(),
                context.flow().getAssetSymbol(),
                context.transaction().getSource()
        )) {
            return Optional.empty();
        }
        return Optional.of(new PriceQuote(
                PARITY,
                PriceSource.STABLECOIN,
                context.transaction().getBlockTimestamp(),
                "USD",
                "stablecoin-parity"
        ));
    }
}
