package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.application.normalization.pipeline.metadata.NetworkTokenOverrides;
import com.walletradar.domain.common.NetworkId;

import java.util.Optional;

/**
 * TON jetton master → {symbol, decimals} descriptor-seed lookups (WS-7). Backed by the per-network
 * {@code token-overrides} in {@code network-descriptors.yml} (via {@link NetworkTokenOverrides}) —
 * the load-bearing decimals (USDT-TON=6, AMZNx/MSTRx=8, XAUT0=6). The static
 * {@code token-metadata.json} store it used to load was deleted; live resolution for unseeded
 * jettons (RWA/xStock symbols) and the durable write-through cache are owned by
 * {@code com.walletradar.application.normalization.pipeline.metadata.TokenMetadataResolutionService}
 * (applied at the {@code CanonicalMetadataEnricher} seam), keeping this lookup deterministic and
 * network-free so the builder stays pure.
 *
 * <p>TON jetton master addresses are case-sensitive and exist in multiple canonical forms;
 * {@link NetworkTokenOverrides} expands every configured master through {@code TonAddressCanonicalizer}
 * so any equivalent form resolves the same entry. Returns {@code null} when the master is not seeded
 * so a known jetton is never booked at the wrong native-precision default (9).</p>
 */
public final class TonJettonMetadataRegistry {

    private TonJettonMetadataRegistry() {
    }

    /** @return seeded decimals for the jetton master (any canonical form), or {@code null}. */
    public static Integer decimals(String jettonMaster) {
        return find(jettonMaster)
                .map(NetworkTokenOverrides.Override::effectiveDecimals)
                .orElse(null);
    }

    /** @return seeded symbol for the jetton master (any canonical form), or {@code null}. */
    public static String symbol(String jettonMaster) {
        return find(jettonMaster)
                .map(NetworkTokenOverrides.Override::symbol)
                .orElse(null);
    }

    private static Optional<NetworkTokenOverrides.Override> find(String jettonMaster) {
        if (jettonMaster == null || jettonMaster.isBlank()) {
            return Optional.empty();
        }
        return NetworkTokenOverrides.find(NetworkId.TON, jettonMaster);
    }
}
