package com.walletradar.application.linking.pipeline.clarification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Config-seeded, global custodial-operator registry for TON (ADR-079), loaded once from
 * {@code classpath:ton-custodial-operators.json}. Mirrors {@link
 * com.walletradar.application.normalization.pipeline.ton.TonProtocolRegistry} and the ADR-059
 * counterparty-hints config plane, keyed by {@link TonAddressCanonicalizer}-canonical operator
 * address.
 *
 * <p>Distinct from ADR-072's per-session, user-designated {@code externalCustodyDestinations}
 * ({@link ExternalCustodyDestinationRegistry}): this is a maintained <b>global default</b> for
 * shared, well-known operator pools a user cannot be expected to configure (Telegram Wallet). The two
 * are consulted together by the TON resolver, this registry acting as a global fallback. On a match,
 * the peer is relabeled {@link com.walletradar.domain.counterparty.CounterpartyType#EXTERNAL_CUSTODY}
 * with the configured label and the {@code custodialOffChain} flag is stamped — reusing ADR-072's
 * accounting model exactly (the row stays {@code EXTERNAL_TRANSFER_IN/OUT}, never
 * {@code INTERNAL_TRANSFER}).</p>
 *
 * <p><b>Deterministic source of truth.</b> tonapi's account {@code name}/{@code interfaces} and the
 * {@code wallet_highload_v3r1} interface are <em>offline discovery aids only</em> used to populate/
 * extend this file. There is no runtime tonapi lookup (the stored TonCenter payload carries neither
 * peer {@code interfaces} nor {@code name}); the highload interface alone is never sufficient for a
 * custodial label. An operator not listed here stays {@code UNKNOWN_EOA} (never guessed as
 * "Telegram"), and existing deterministic classifiers (Bybit {@code EXCHANGE_ACCOUNT}; TON DEX
 * routers) keep priority. Fail-fast on a missing/malformed resource.</p>
 */
final class TonCustodialOperatorRegistry {

    private static final String RESOURCE = "ton-custodial-operators.json";

    private static final Map<String, ExternalCustodyDestinationRegistry.CustodyMatch> INDEX = load();

    private TonCustodialOperatorRegistry() {
    }

    /**
     * @return the matched custodial operator (label/provider + canonical address) when {@code peer}
     * is a registered global custodial-operator address in any canonical TON form, else empty.
     */
    static Optional<ExternalCustodyDestinationRegistry.CustodyMatch> match(String peer) {
        if (peer == null || peer.isBlank() || INDEX.isEmpty()) {
            return Optional.empty();
        }
        for (String key : TonAddressCanonicalizer.lookupKeys(peer.trim())) {
            ExternalCustodyDestinationRegistry.CustodyMatch match = INDEX.get(key);
            if (match != null) {
                return Optional.of(match);
            }
        }
        return Optional.empty();
    }

    private static Map<String, ExternalCustodyDestinationRegistry.CustodyMatch> load() {
        try (InputStream inputStream =
                     TonCustodialOperatorRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            Definition definition = new ObjectMapper().readValue(inputStream, Definition.class);
            if (definition == null) {
                throw new IllegalStateException("Malformed " + RESOURCE);
            }
            return buildIndex(definition.operators());
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    private static Map<String, ExternalCustodyDestinationRegistry.CustodyMatch> buildIndex(Map<String, Entry> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, ExternalCustodyDestinationRegistry.CustodyMatch> index = new LinkedHashMap<>();
        configured.forEach((address, entry) -> {
            if (address == null || address.isBlank() || entry == null) {
                return;
            }
            var keys = TonAddressCanonicalizer.lookupKeys(address.trim());
            if (keys.isEmpty()) {
                return;
            }
            String canonical = address.trim();
            String label = entry.label() != null && !entry.label().isBlank()
                    ? entry.label().trim()
                    : (entry.provider() == null ? canonical : entry.provider().trim());
            ExternalCustodyDestinationRegistry.CustodyMatch value =
                    new ExternalCustodyDestinationRegistry.CustodyMatch(canonical, label, entry.provider());
            for (String key : keys) {
                index.putIfAbsent(key, value);
            }
        });
        return Map.copyOf(index);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(Map<String, Entry> operators) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(String provider, String label, String note) {
    }
}
