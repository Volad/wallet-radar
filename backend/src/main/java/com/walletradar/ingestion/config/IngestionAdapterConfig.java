package com.walletradar.ingestion.config;

import com.walletradar.common.RetryPolicy;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.evm.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.WebClientEvmRpcClient;
import com.walletradar.ingestion.adapter.solana.SolanaRpcClient;
import com.walletradar.ingestion.adapter.solana.WebClientSolanaRpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Configures RPC adapters from unified per-network config (ADR-012): per-network rotators + default rotator for unknown networks.
 */
@Configuration
@EnableConfigurationProperties({ IngestionNetworkProperties.class, ProtocolRegistryProperties.class, IngestionRetryProperties.class, BackfillProperties.class })
public class IngestionAdapterConfig {

    /** Fallback URL when a network has no entry or empty urls; used so adapter does not fail for unconfigured EVM networks. */
    private static final List<String> DEFAULT_FALLBACK_URLS = List.of("https://eth.llamarpc.com");

    @Autowired
    private IngestionRetryProperties retryProperties;

    private RetryPolicy retryPolicy() {
        return new RetryPolicy(
                retryProperties.getBaseDelayMs(),
                retryProperties.getJitterFactor(),
                retryProperties.getMaxAttempts());
    }

    /** EVM networks only (exclude SOLANA). */
    @Bean
    public Map<String, RpcEndpointRotator> evmRotatorsByNetwork(IngestionNetworkProperties properties) {
        return properties.getNetwork().entrySet().stream()
                .filter(e -> !"SOLANA".equals(e.getKey()))
                .filter(e -> e.getValue() != null && e.getValue().getUrls() != null && !e.getValue().getUrls().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> new RpcEndpointRotator(e.getValue().getUrls(), retryPolicy())));
    }

    /** Solana only (SOLANA entry from network). */
    @Bean
    public Map<String, RpcEndpointRotator> solanaRotatorsByNetwork(IngestionNetworkProperties properties) {
        return properties.getNetwork().entrySet().stream()
                .filter(e -> "SOLANA".equals(e.getKey()))
                .filter(e -> e.getValue() != null && e.getValue().getUrls() != null && !e.getValue().getUrls().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> new RpcEndpointRotator(e.getValue().getUrls(), retryPolicy())));
    }

    /**
     * Default rotator for EVM networks not present in walletradar.ingestion.network. Single fallback URL to avoid failure.
     */
    @Bean
    public RpcEndpointRotator evmDefaultRpcEndpointRotator() {
        return new RpcEndpointRotator(DEFAULT_FALLBACK_URLS, retryPolicy());
    }

    @Bean
    public EvmRpcClient evmRpcClient(WebClient.Builder webClientBuilder) {
        return new WebClientEvmRpcClient(webClientBuilder);
    }

    /** Default Solana RPC rotator when SOLANA has no urls in walletradar.ingestion.network. */
    @Bean(name = "solanaDefaultRpcEndpointRotator")
    public RpcEndpointRotator solanaDefaultRpcEndpointRotator() {
        return new RpcEndpointRotator(List.of("https://api.mainnet-beta.solana.com"), retryPolicy());
    }

    @Bean
    public SolanaRpcClient solanaRpcClient(WebClient.Builder webClientBuilder) {
        return new WebClientSolanaRpcClient(webClientBuilder);
    }
}
