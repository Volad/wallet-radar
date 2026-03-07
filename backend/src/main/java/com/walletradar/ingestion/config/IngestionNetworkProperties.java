package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * One network's sync method, chain id, RPC URLs, eth_getLogs batch block size (ADR-011), and optional backfill window.
     * When windowBlocks is set, backfill for this network uses it instead of the global backfill.window-blocks
     * (so L2s with fast blocks can cover ~1 year: e.g. Arbitrum ~4 blocks/s → 126M blocks/year).
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
        /**
         * Explorer/RPC synthetic native token contracts that can appear as pseudo-ERC20 transfer legs and
         * should be ignored by protocol-specific disambiguation heuristics (e.g. zkSync 0x...800a).
         */
        private List<String> syntheticNativeContracts = new ArrayList<>();
        /**
         * Allowlisted one-leg lend selectors for protocols that do not emit receipt-token mint logs.
         * Rule is scoped by target contract and selectors.
         */
        private List<OneLegLendRule> oneLegLendRules = new ArrayList<>();
        private Integer batchBlockSize;
        /** Optional. Backfill window in blocks for this network; if null, global backfill.window-blocks is used. */
        private Long windowBlocks;
        /** Average block time in seconds for this network. Used as fallback by EstimatingBlockTimestampResolver. */
        private Double avgBlockTimeSeconds;
        /** Explorer source config for this network (etherscan/blockscout). */
        private Explorer explorer = new Explorer();

        public void setUrls(List<String> urls) {
            this.urls = urls != null ? urls : new ArrayList<>();
        }

        public void setSyntheticNativeContracts(List<String> syntheticNativeContracts) {
            if (syntheticNativeContracts == null) {
                this.syntheticNativeContracts = new ArrayList<>();
                return;
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String candidate : syntheticNativeContracts) {
                String normalized = normalizeSyntheticNativeContract(candidate);
                if (normalized != null && !normalized.isBlank()) {
                    unique.add(normalized);
                }
            }
            this.syntheticNativeContracts = new ArrayList<>(unique);
        }

        public void setOneLegLendRules(List<OneLegLendRule> oneLegLendRules) {
            if (oneLegLendRules == null || oneLegLendRules.isEmpty()) {
                this.oneLegLendRules = new ArrayList<>();
                return;
            }
            List<OneLegLendRule> normalized = new ArrayList<>(oneLegLendRules.size());
            for (OneLegLendRule rule : oneLegLendRules) {
                if (rule == null) {
                    continue;
                }
                OneLegLendRule normalizedRule = new OneLegLendRule();
                normalizedRule.setContract(normalizeSyntheticNativeContract(rule.getContract()));
                normalizedRule.setSelectors(rule.getSelectors());
                if (normalizedRule.getContract() == null || normalizedRule.getSelectors().isEmpty()) {
                    continue;
                }
                normalized.add(normalizedRule);
            }
            this.oneLegLendRules = normalized;
        }

        @NoArgsConstructor
        @Getter
        @Setter
        public static class OneLegLendRule {
            private String contract;
            private List<String> selectors = new ArrayList<>();

            public void setContract(String contract) {
                this.contract = normalizeSyntheticNativeContract(contract);
            }

            public void setSelectors(List<String> selectors) {
                if (selectors == null || selectors.isEmpty()) {
                    this.selectors = new ArrayList<>();
                    return;
                }
                LinkedHashSet<String> normalized = new LinkedHashSet<>();
                for (String selector : selectors) {
                    String canonical = normalizeSelector(selector);
                    if (canonical != null) {
                        normalized.add(canonical);
                    }
                }
                this.selectors = new ArrayList<>(normalized);
            }
        }

        private static String normalizeSyntheticNativeContract(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("0x")) {
                String hex = normalized.substring(2);
                if (hex.isBlank() || !isHex(hex)) {
                    return normalized;
                }
                if (hex.length() > 40) {
                    hex = hex.substring(hex.length() - 40);
                }
                return "0x" + "0".repeat(40 - hex.length()) + hex;
            }
            if (normalized.matches("[0-9]+")) {
                try {
                    BigInteger decimal = new BigInteger(normalized, 10);
                    if (decimal.signum() < 0 || decimal.bitLength() > 160) {
                        return normalized;
                    }
                    String hex = decimal.toString(16);
                    return "0x" + "0".repeat(40 - hex.length()) + hex;
                } catch (NumberFormatException ignored) {
                    return normalized;
                }
            }
            return normalized;
        }

        private static boolean isHex(String value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean digit = c >= '0' && c <= '9';
                boolean lowerHex = c >= 'a' && c <= 'f';
                if (!digit && !lowerHex) {
                    return false;
                }
            }
            return true;
        }

        private static String normalizeSelector(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("0x")) {
                normalized = "0x" + normalized;
            }
            if (normalized.length() != 10) {
                return null;
            }
            String hex = normalized.substring(2);
            return isHex(hex) ? normalized : null;
        }

        public void setExplorer(Explorer explorer) {
            this.explorer = explorer != null ? explorer : new Explorer();
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

        public enum SyncMethod {
            ETHERSCAN,
            BLOCKSCOUT,
            RPC
        }
    }
}
