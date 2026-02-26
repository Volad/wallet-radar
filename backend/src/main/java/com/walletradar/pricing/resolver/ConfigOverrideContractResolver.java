package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.config.PricingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Contract-to-CoinGecko-ID resolver that uses config overrides only.
 * Wraps PricingProperties.contractToCoinGeckoId (contract-only, network-agnostic).
 * First in the chain — manual overrides always win (ADR-022).
 */
@Component
@RequiredArgsConstructor
public class ConfigOverrideContractResolver implements ContractToCoinGeckoIdResolver {

    private final PricingProperties pricingProperties;

    @Override
    public Optional<String> resolve(String contractAddress, NetworkId networkId) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return Optional.empty();
        }
        String key = contractAddress.toLowerCase().strip();
        String coinId = pricingProperties.getContractToCoinGeckoId().get(key);
        return coinId != null && !coinId.isBlank() ? Optional.of(coinId) : Optional.empty();
    }
}
