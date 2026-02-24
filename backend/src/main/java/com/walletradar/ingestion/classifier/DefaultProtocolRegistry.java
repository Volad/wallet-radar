package com.walletradar.ingestion.classifier;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map-based protocol registry. Known EVM/Solana addresses can be configured or added at runtime.
 */
@Component
public class DefaultProtocolRegistry implements ProtocolRegistry {

    private final Map<String, String> nameByAddress = new ConcurrentHashMap<>();

    public DefaultProtocolRegistry() {
        // Known Uniswap V2/V3, Aave, etc. (lowercase for EVM)
        nameByAddress.put("0x7a250d5630b4cf539739df2c5dacb4c659f2488d".toLowerCase(), "Uniswap V2");
        nameByAddress.put("0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45".toLowerCase(), "Uniswap V3");
        nameByAddress.put("0xe592427a0aece92de3edee1f18e0157c05861564".toLowerCase(), "Uniswap V3");
        nameByAddress.put("0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2".toLowerCase(), "Aave V3");
    }

    @Override
    public Optional<String> getProtocolName(String addressOrProgramId) {
        if (addressOrProgramId == null || addressOrProgramId.isBlank()) {
            return Optional.empty();
        }
        String key = addressOrProgramId.strip().toLowerCase();
        return Optional.ofNullable(nameByAddress.get(key));
    }
}
