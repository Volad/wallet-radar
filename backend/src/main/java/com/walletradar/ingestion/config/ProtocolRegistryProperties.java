package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Protocol name by contract address (EVM) or program id (Solana). Key = address (lowercase for EVM), value = display name.
 * See application.yml walletradar.ingestion.protocol-registry.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.protocol-registry")
@NoArgsConstructor
@Getter
@Setter
public class ProtocolRegistryProperties {

    /**
     * Map: address or programId (e.g. 0x…) -> protocol display name (e.g. "Uniswap V3").
     */
    private Map<String, String> names = new HashMap<>();
}
