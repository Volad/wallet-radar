package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified per-network ingestion config. Key = NetworkId name (e.g. ETHEREUM, ARBITRUM).
 */
@ConfigurationProperties(prefix = "walletradar.ingestion")
@NoArgsConstructor
@Getter
@Setter
public class IngestionNetworkProperties {

    /**
     * Per-network entries. Key: NetworkId name.
     */
    private Map<String, NetworkIngestionEntry> network = new HashMap<>();

    public void setNetwork(Map<String, NetworkIngestionEntry> network) {
        if (network == null) {
            this.network = new HashMap<>();
            return;
        }
        Map<String, NetworkIngestionEntry> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, NetworkIngestionEntry> entry : network.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = entry.getKey().trim().toUpperCase(Locale.ROOT);
            normalized.put(key, entry.getValue());
        }
        this.network = normalized;
    }

    /**
     * One network's sync method, chain id, RPC URLs, batch block size, and optional backfill window.
     */
    @NoArgsConstructor
    @Getter
    @Setter
    public static class NetworkIngestionEntry {

        /** Preferred raw sync source for this network backfill. */
        private SyncMethod syncMethod = SyncMethod.ETHERSCAN;
        /** EVM chain id used by explorer integrations (e.g. Etherscan V2 `chainid`). */
        private String chainId;
        private List<String> urls = new ArrayList<>();
        private Integer batchBlockSize;
        /** Optional. Backfill window in blocks for this network; if null, global backfill.window-blocks is used. */
        private Long windowBlocks;
        /** Average block time in seconds for this network. Used as fallback by EstimatingBlockTimestampResolver. */
        private Double avgBlockTimeSeconds;
        /** Explorer source config for this network (etherscan/blockscout). */
        private Explorer explorer = new Explorer();
        /** Optional provider-first acquisition config for advanced RPC APIs. */
        private Provider provider = new Provider();

        public void setUrls(List<String> urls) {
            this.urls = urls != null ? urls : new ArrayList<>();
        }

        public void setExplorer(Explorer explorer) {
            this.explorer = explorer != null ? explorer : new Explorer();
        }

        public void setProvider(Provider provider) {
            this.provider = provider != null ? provider : new Provider();
        }

        @NoArgsConstructor
        @Getter
        @Setter
        public static class Explorer {
            private ExplorerSource etherscan;
            private ExplorerSource blockscout;
        }

        @NoArgsConstructor
        @Getter
        @Setter
        public static class ExplorerSource {
            private String baseUrl;
            private String apiKey;
            private boolean enabled = true;
        }

        @NoArgsConstructor
        @Getter
        @Setter
        public static class Provider {
            private boolean enabled;
            private String baseUrl;
            private Integer pageSize = 100;
        }

        public enum SyncMethod {
            ETHERSCAN,
            BLOCKSCOUT,
            RPC
        }
    }
}
