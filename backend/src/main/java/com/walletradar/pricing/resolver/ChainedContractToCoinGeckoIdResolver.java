package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Chain-of-responsibility: ConfigOverride → CoinsListBulk.
 * OnchainTokenInfoResolver skipped for MVP (onchain-fallback-enabled: false).
 */
@Component
@Primary
@RequiredArgsConstructor
public class ChainedContractToCoinGeckoIdResolver implements ContractToCoinGeckoIdResolver {

    private final ConfigOverrideContractResolver configOverrideResolver;
    private final CoinsListBulkResolver coinsListBulkResolver;

    @Override
    public Optional<String> resolve(String contractAddress, NetworkId networkId) {
        Optional<String> result = configOverrideResolver.resolve(contractAddress, networkId);
        if (result.isPresent()) {
            return result;
        }
        return coinsListBulkResolver.resolve(contractAddress, networkId);
    }
}
