package com.walletradar.application.pricing.resolver.external.defillama;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.resolver.external.ExternalPriceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Resolves historical USD prices via the DefiLlama coins API.
 * Supports any EVM token that has a known on-chain contract address.
 * Positioned before CoinGecko in the fallback chain (DEFILLAMA priority = 2.5).
 */
@Component
@RequiredArgsConstructor
public class DefiLlamaPriceSourceAdapter implements ExternalPriceSource {

    private final DefiLlamaClient defiLlamaClient;

    @Override
    public PriceSource source() {
        return PriceSource.DEFILLAMA;
    }

    @Override
    public boolean supports(PriceRequest request) {
        // Only attempt DefiLlama when we have a concrete on-chain contract address
        // and the network is one DefiLlama knows about.
        return request.assetContract() != null
                && !request.assetContract().isBlank()
                && DefiLlamaClient.chainSlug(request.networkId()).isPresent();
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        Optional<BigDecimal> price = defiLlamaClient.historicalPrice(
                request.networkId(),
                request.assetContract(),
                request.occurredAt()
        );
        return price.map(p -> new PriceQuote(
                p,
                PriceSource.DEFILLAMA,
                request.occurredAt(),
                "USD",
                DefiLlamaClient.chainSlug(request.networkId()).orElse("?") + ":" + request.assetContract()
        ));
    }
}
