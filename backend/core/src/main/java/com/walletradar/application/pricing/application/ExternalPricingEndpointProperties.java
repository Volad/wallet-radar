package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.NetworkId;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "walletradar.pricing.endpoints")
@NoArgsConstructor
@Getter
@Setter
public class ExternalPricingEndpointProperties {

    private String defiLlamaBaseUrl = "https://coins.llama.fi";
    private Duration requestTimeout = Duration.ofSeconds(8);
    private Duration gmxSnapshotTtl = Duration.ofSeconds(60);
    private Duration gmxApyCacheTtl = Duration.ofMinutes(30);
    private Map<NetworkId, GmxChainEndpoints> gmx = defaultGmxEndpoints();
    private Map<NetworkId, String> gmxSquidGraphQl = defaultGmxSquidGraphQl();

    @NoArgsConstructor
    @Getter
    @Setter
    public static class GmxChainEndpoints {
        private List<String> apiBaseUrls;
        private List<String> rpcUrls;
    }

    private static Map<NetworkId, GmxChainEndpoints> defaultGmxEndpoints() {
        Map<NetworkId, GmxChainEndpoints> endpoints = new EnumMap<>(NetworkId.class);
        putGmx(endpoints, NetworkId.ARBITRUM,
                List.of(
                        "https://arbitrum-api.gmxinfra.io",
                        "https://arbitrum-api-fallback.gmxinfra.io",
                        "https://arbitrum-api-fallback.gmxinfra2.io"
                ),
                List.of(
                        "https://arb1.arbitrum.io/rpc",
                        "https://arbitrum-one-rpc.publicnode.com"
                ));
        putGmx(endpoints, NetworkId.AVALANCHE,
                List.of(
                        "https://avalanche-api.gmxinfra.io",
                        "https://avalanche-api-fallback.gmxinfra.io",
                        "https://avalanche-api-fallback.gmxinfra2.io"
                ),
                List.of(
                        "https://api.avax.network/ext/bc/C/rpc",
                        "https://avax.meowrpc.com"
                ));
        return endpoints;
    }

    private static void putGmx(
            Map<NetworkId, GmxChainEndpoints> endpoints,
            NetworkId networkId,
            List<String> apiBaseUrls,
            List<String> rpcUrls
    ) {
        GmxChainEndpoints chain = new GmxChainEndpoints();
        chain.setApiBaseUrls(apiBaseUrls);
        chain.setRpcUrls(rpcUrls);
        endpoints.put(networkId, chain);
    }

    private static Map<NetworkId, String> defaultGmxSquidGraphQl() {
        Map<NetworkId, String> endpoints = new EnumMap<>(NetworkId.class);
        endpoints.put(NetworkId.ARBITRUM,
                "https://gmx.squids.live/gmx-synthetics-arbitrum:prod/api/graphql");
        endpoints.put(NetworkId.AVALANCHE,
                "https://gmx.squids.live/gmx-synthetics-avalanche:prod/api/graphql");
        return endpoints;
    }
}
