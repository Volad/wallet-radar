package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified per-network ingestion config (ADR-012). Single source of truth for RPC URLs and batch block size per NetworkId.
 * Key = NetworkId name (e.g. ETHEREUM, ARBITRUM). See docs/adr/ADR-012-unified-ingestion-network-config.md.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion")
@NoArgsConstructor
@Getter
@Setter
public class IngestionNetworkProperties {

    /**
     * Per-network entries. Key: NetworkId name. Missing network → resolver uses default batch size 2000;
     * rotator uses fallback default URL list when adapter is used for that network.
     */
    private Map<String, NetworkIngestionEntry> network = new HashMap<>();

    public void setNetwork(Map<String, NetworkIngestionEntry> network) {
        this.network = network != null ? network : new HashMap<>();
    }

    /**
     * One network's RPC URLs and eth_getLogs batch block size (ADR-011 semantics).
     */
    @NoArgsConstructor
    @Getter
    @Setter
    public static class NetworkIngestionEntry {

        private List<String> urls = new ArrayList<>();
        private Integer batchBlockSize;

        public void setUrls(List<String> urls) {
            this.urls = urls != null ? urls : new ArrayList<>();
        }
    }
}
