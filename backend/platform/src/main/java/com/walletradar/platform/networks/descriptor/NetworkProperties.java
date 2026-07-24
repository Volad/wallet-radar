package com.walletradar.platform.networks.descriptor;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-network descriptor entries keyed by {@link com.walletradar.domain.common.NetworkId} name.
 */
@ConfigurationProperties(prefix = "walletradar.networks")
@NoArgsConstructor
@Getter
@Setter
public class NetworkProperties {

    private Map<String, NetworkEntry> entries = new LinkedHashMap<>();

    public void setEntries(Map<String, NetworkEntry> entries) {
        if (entries == null) {
            this.entries = new LinkedHashMap<>();
            return;
        }
        Map<String, NetworkEntry> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, NetworkEntry> entry : entries.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            normalized.put(entry.getKey().trim().toUpperCase(Locale.ROOT), entry.getValue());
        }
        this.entries = normalized;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class NetworkEntry {

        private String addressFormat = "EVM";
        private String nativeSymbol;
        /** Accounting identity sentinel for native flows (e.g. {@code NATIVE:SOLANA}, {@code TONCOIN}). Absent for EVM. */
        private String nativeIdentity;
        /** Precision of the native token in decimal places (e.g. 9 for SOL/TON, 18 for EVM). */
        private Integer nativeDecimals;
        private WrappedNative wrappedNative = new WrappedNative();
        private List<String> nativeAliasContracts = new ArrayList<>();
        private Boolean walletSupported = true;
        private Boolean evmWalletSupported;
        private List<String> usdStableContracts = new ArrayList<>();
        private List<String> ethFamilyContracts = new ArrayList<>();

        public void setNativeAliasContracts(List<String> nativeAliasContracts) {
            this.nativeAliasContracts = nativeAliasContracts != null ? nativeAliasContracts : new ArrayList<>();
        }

        public void setUsdStableContracts(List<String> usdStableContracts) {
            this.usdStableContracts = usdStableContracts != null ? usdStableContracts : new ArrayList<>();
        }

        public void setEthFamilyContracts(List<String> ethFamilyContracts) {
            this.ethFamilyContracts = ethFamilyContracts != null ? ethFamilyContracts : new ArrayList<>();
        }
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class WrappedNative {

        private String contract;
        private String symbol;
    }
}
