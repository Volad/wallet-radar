package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.application.normalization.pipeline.metadata.LiveTokenMetadataResolver;
import com.walletradar.application.normalization.pipeline.metadata.ResolvedTokenMetadata;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.ton.metadata.TonMetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * TON {@link LiveTokenMetadataResolver} backed directly by the free-tier {@link TonMetadataClient}
 * (TON Center jetton-masters API, WS-7). It mirrors {@code JupiterSplTokenMetadataResolver} for the
 * TON family: it calls the transport directly (behind a platform port with a ~1 rps throttle) and
 * never consults the descriptor-backed {@code TonJettonMetadataRegistry} or the resolution bridge, so
 * there is no recursion. Durable caching, descriptor precedence and write-through are owned by
 * {@code TokenMetadataResolutionService}. Resolves RWA/xStock jettons (AMZNx/MSTRx/XAUt/STON) live.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TonJettonMetadataResolver implements LiveTokenMetadataResolver {

    private final TonMetadataClient tonMetadataClient;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.TON;
    }

    @Override
    public Optional<ResolvedTokenMetadata> resolve(NetworkId networkId, String jettonMaster) {
        if (!supports(networkId) || jettonMaster == null || jettonMaster.isBlank()) {
            return Optional.empty();
        }
        try {
            return tonMetadataClient.fetchJettonMetadata(jettonMaster.trim())
                    .map(metadata -> new ResolvedTokenMetadata(
                            upper(metadata.symbol()),
                            metadata.decimals(),
                            ResolvedTokenMetadata.Source.LIVE_RESOLVER))
                    .filter(ResolvedTokenMetadata::isResolved);
        } catch (Exception ex) {
            log.debug("TON live jetton metadata resolution failed for master {}: {}", jettonMaster, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
