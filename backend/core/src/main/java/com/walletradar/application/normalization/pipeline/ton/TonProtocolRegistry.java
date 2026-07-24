package com.walletradar.application.normalization.pipeline.ton;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Config-seeded TON protocol registry (WS-2), loaded once from
 * {@code classpath:ton-protocol-registry.json}. Mirrors the {@code protocol-registry.json} intent
 * for TON while keeping TON-specific, case-sensitive address canonicalization isolated from the
 * EVM/Solana {@code AddressNormalizer} (which cannot key TON addresses).
 *
 * <p>Two concerns:</p>
 * <ul>
 *   <li><b>Proxy-TON (pTON) masters</b> — Ston.fi wraps native TON as a proxy jetton for routing.
 *       A pTON leg must be <em>netted to native TON</em> (never booked as a held pTON jetton),
 *       killing the phantom pTON inventory and its bogus avco (B2). {@link #isProxyTon(String)}.</li>
 *   <li><b>DeFi vault/router addresses</b> — a {@code STAKING} vault drives
 *       stake/unstake classification of wallet↔vault jetton moves; a {@code DEX} router labels swap
 *       counterparties. {@link #family(String)} / {@link #protocol(String)}.</li>
 * </ul>
 *
 * <p>Every configured key is expanded through {@link TonAddressCanonicalizer#lookupKeys(String)} so
 * a lookup by any equivalent form (raw {@code 0:hex} that toncenter emits, or a friendly
 * {@code EQ…}/{@code UQ…}) resolves the same entry. Fail-fast on a missing/malformed resource.</p>
 */
public final class TonProtocolRegistry {

    private static final String RESOURCE = "ton-protocol-registry.json";

    private static final Loaded INDEX = load();

    private TonProtocolRegistry() {
    }

    /** @return {@code true} when the jetton master is a registered proxy-TON (pTON) that nets to native TON. */
    public static boolean isProxyTon(String jettonMaster) {
        return lookup(INDEX.proxyTonMasters, jettonMaster) != null;
    }

    /** @return protocol family (e.g. {@code STAKING}, {@code DEX}) for a vault/router address, or {@code null}. */
    public static String family(String address) {
        Entry entry = lookup(INDEX.protocols, address);
        return entry == null || entry.family() == null ? null : entry.family().trim().toUpperCase(Locale.ROOT);
    }

    /** @return protocol display name for a proxy-TON master or vault/router address, or {@code null}. */
    public static String protocol(String address) {
        Entry proxy = lookup(INDEX.proxyTonMasters, address);
        if (proxy != null && proxy.protocol() != null) {
            return proxy.protocol();
        }
        Entry entry = lookup(INDEX.protocols, address);
        return entry == null ? null : entry.protocol();
    }

    private static Entry lookup(Map<String, Entry> index, String address) {
        if (address == null || address.isBlank() || index.isEmpty()) {
            return null;
        }
        for (String key : TonAddressCanonicalizer.lookupKeys(address.trim())) {
            Entry entry = index.get(key);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static Loaded load() {
        try (InputStream inputStream =
                     TonProtocolRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            Definition definition = new ObjectMapper().readValue(inputStream, Definition.class);
            if (definition == null) {
                throw new IllegalStateException("Malformed " + RESOURCE);
            }
            return new Loaded(
                    buildIndex(definition.proxyTonMasters()),
                    buildIndex(definition.protocols()));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    private static Map<String, Entry> buildIndex(Map<String, Entry> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, Entry> index = new LinkedHashMap<>();
        configured.forEach((address, entry) -> {
            // Skip JSON "_comment" and other non-address annotation keys (no canonical TON form).
            if (address == null || address.isBlank() || entry == null) {
                return;
            }
            var keys = TonAddressCanonicalizer.lookupKeys(address.trim());
            if (keys.isEmpty()) {
                return;
            }
            for (String key : keys) {
                index.put(key, entry);
            }
        });
        return Map.copyOf(index);
    }

    private record Loaded(Map<String, Entry> proxyTonMasters, Map<String, Entry> protocols) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(Map<String, Entry> proxyTonMasters, Map<String, Entry> protocols) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(String protocol, String family, String role, String note) {
    }
}
