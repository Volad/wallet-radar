package com.walletradar.lending.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@ConfigurationProperties(prefix = "walletradar.lending.market-rates")
@NoArgsConstructor
@Getter
@Setter
public class LendingMarketRateProperties {

    private boolean startupRefreshEnabled = true;
    private long refreshIntervalMs = 86_400_000L;
    private Map<String, AaveV3NetworkConfig> aaveV3 = new HashMap<>();

    public void setAaveV3(Map<String, AaveV3NetworkConfig> aaveV3) {
        if (aaveV3 == null) {
            this.aaveV3 = new HashMap<>();
            return;
        }
        Map<String, AaveV3NetworkConfig> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, AaveV3NetworkConfig> entry : aaveV3.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            normalized.put(entry.getKey().trim().toUpperCase(Locale.ROOT), entry.getValue());
        }
        this.aaveV3 = normalized;
    }

    /** True when on-chain Aave V3 health fetch is configured for the network. */
    public boolean isAaveV3HealthFetchEnabled(String networkId) {
        if (networkId == null || networkId.isBlank()) {
            return false;
        }
        AaveV3NetworkConfig config = aaveV3.get(networkId.trim().toUpperCase(Locale.ROOT));
        return config != null
                && config.isEnabled()
                && config.getPoolAddress() != null
                && !config.getPoolAddress().isBlank();
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class AaveV3NetworkConfig {
        private boolean enabled = true;
        private String poolAddress;
        private String poolAddressesProvider;
        private String protocolDataProvider;
        private String uiPoolDataProvider;
        private String incentivesController;
        private String source = "application.yml";
        private String version = "v3";
    }
}
