package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for phishing/scam transaction filter. Addresses in blocklist (contracts, EOAs)
 * cause transactions involving them to be skipped during ingestion.
 */
@ConfigurationProperties(prefix = "walletradar.scam-filter")
@Getter
@Setter
public class ScamFilterProperties {

    /**
     * When false, no filtering is applied. Default true.
     */
    private boolean enabled = true;

    /**
     * Blocklist of addresses (EVM: 0x..., Solana: base58). Case-insensitive.
     * Add known scam contracts, drainers, phishing addresses.
     */
    private List<String> blocklist = List.of();

    /**
     * Normalized blocklist (lowercase) for fast lookup. Built from blocklist.
     */
    public Set<String> getBlocklistNormalized() {
        if (blocklist == null || blocklist.isEmpty()) {
            return Set.of();
        }
        return blocklist.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.strip().toLowerCase())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
