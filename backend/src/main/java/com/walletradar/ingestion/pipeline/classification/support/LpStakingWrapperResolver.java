package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RC-5 (ADR-018): canonicalizes a known LP-staking / farming wrapper contract to the underlying
 * NonfungiblePositionManager (NFPM) so that an LP NFT staked into a farming wrapper keeps the single
 * position identity {@code (network, NFPM contract, tokenId)} instead of forming a duplicate
 * wrapper-keyed pool.
 *
 * <p>The {@code wrapperContract → underlyingNFPM} mapping is data-driven: a {@code STAKE_CONTRACT}
 * registry entry declares {@code underlyingPositionManager} (e.g. PancakeSwap MasterChefV3 →
 * PancakeSwap V3 NFPM, per network). The mapping is the durable realization of the reusable detection
 * signal (the wrapper custodies a position-manager NFT and forwards decrease/collect/harvest on the
 * same tokenId the NFPM minted) — no transaction hashes / tokenIds participate.
 */
@Component
public class LpStakingWrapperResolver {

    private static final Logger log = LoggerFactory.getLogger(LpStakingWrapperResolver.class);

    /** One-time "staking wrapper missing underlyingPositionManager mapping" coverage warning. */
    private static final Set<String> WARNED_UNREGISTERED_WRAPPERS = ConcurrentHashMap.newKeySet();

    private final ProtocolRegistryService protocolRegistryService;

    public LpStakingWrapperResolver(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    /**
     * Returns the underlying NFPM contract when {@code resolvedContract} is a registered staking /
     * farming wrapper with an {@code underlyingPositionManager} mapping; otherwise returns
     * {@code resolvedContract} unchanged (lowercased {@code 0x…}).
     *
     * <p>Fail-loud: a registered DEX {@code STAKE_CONTRACT} that is an LP-NFT custody wrapper but has
     * no mapping emits a one-time coverage warning so the registry can be extended; its identity stays
     * the (un-canonicalized) wrapper contract.
     */
    public String canonicalPositionManager(NetworkId networkId, String resolvedContract) {
        String normalized = OnChainRawTransactionView.normalizeAddress(resolvedContract);
        if (networkId == null || normalized == null) {
            return resolvedContract;
        }
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(networkId, normalized);
        if (entry.isEmpty()) {
            return normalized;
        }
        ProtocolRegistryEntry registryEntry = entry.get();
        String underlying = OnChainRawTransactionView.normalizeAddress(registryEntry.underlyingPositionManager());
        if (underlying != null) {
            return underlying;
        }
        if (registryEntry.role() == ProtocolRegistryRole.STAKE_CONTRACT
                && registryEntry.family() == ProtocolRegistryFamily.DEX
                && WARNED_UNREGISTERED_WRAPPERS.add(networkId.name() + ":" + normalized)) {
            log.warn(
                    "LP staking wrapper missing underlyingPositionManager mapping "
                            + "(identity stays wrapper-keyed): network={} wrapper={}",
                    networkId.name(),
                    normalized
            );
        }
        return normalized;
    }
}
