package com.walletradar.ingestion.pipeline.classification.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryLoader;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-5 (ADR-018): a known LP-staking / farming wrapper must canonicalize to the underlying
 * NonfungiblePositionManager so a staked position keeps the single {@code (network, NFPM, tokenId)}
 * identity instead of forming a duplicate wrapper-keyed pool. Contracts/tokenIds are regression
 * anchors only — the resolution is purely data-driven (wrapper → underlyingNFPM registry).
 */
class LpStakingWrapperResolverTest {

    private static final String PANCAKE_MASTERCHEF_BASE = "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3";
    private static final String PANCAKE_MASTERCHEF_ARBITRUM = "0x5e09acf80c0296740ec5d6f643005a4ef8daa694";
    private static final String PANCAKE_NFPM = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
    private static final String VELODROME_GAUGE_OPTIMISM = "0x0f5212f63ba8eab0fabd94fc2071d461d9d6ddb2";
    private static final String VELODROME_NFPM = "0x416b433906b1b72fa758e166e239c43d68dc6f29";
    private static final String UNREGISTERED_WRAPPER = "0x1234567890abcdef1234567890abcdef12345678";

    private final ProtocolRegistryService registryService =
            new ProtocolRegistryService(new ProtocolRegistryLoader(new ObjectMapper()));
    private final LpStakingWrapperResolver resolver = new LpStakingWrapperResolver(registryService);

    @Test
    @DisplayName("PancakeSwap MasterChefV3 wrapper canonicalizes to the underlying PancakeSwap V3 NFPM (BASE + ARBITRUM)")
    void masterChefCanonicalizesToNfpm() {
        assertThat(resolver.canonicalPositionManager(NetworkId.BASE, PANCAKE_MASTERCHEF_BASE))
                .isEqualTo(PANCAKE_NFPM);
        assertThat(resolver.canonicalPositionManager(NetworkId.ARBITRUM, PANCAKE_MASTERCHEF_ARBITRUM))
                .isEqualTo(PANCAKE_NFPM);
    }

    @Test
    @DisplayName("Velodrome Slipstream gauge canonicalizes to the Velodrome Slipstream position manager")
    void velodromeGaugeCanonicalizesToNfpm() {
        assertThat(resolver.canonicalPositionManager(NetworkId.OPTIMISM, VELODROME_GAUGE_OPTIMISM))
                .isEqualTo(VELODROME_NFPM);
    }

    @Test
    @DisplayName("the NFPM itself is not a wrapper — returned unchanged (no underlyingPositionManager mapping)")
    void nfpmReturnedUnchanged() {
        assertThat(resolver.canonicalPositionManager(NetworkId.ARBITRUM, PANCAKE_NFPM))
                .isEqualTo(PANCAKE_NFPM);
    }

    @Test
    @DisplayName("an unregistered contract is returned unchanged (identity stays contract-keyed, fail-safe)")
    void unregisteredWrapperReturnedUnchanged() {
        assertThat(resolver.canonicalPositionManager(NetworkId.BASE, UNREGISTERED_WRAPPER))
                .isEqualTo(UNREGISTERED_WRAPPER);
    }

    @Test
    @DisplayName("entry on the NFPM and staked exit on the wrapper resolve to the SAME contract-keyed identity")
    void stakedEntryAndExitShareOnePoolIdentity() {
        // ENTRY: LP NFT minted directly on the NFPM.
        String entryContract = resolver.canonicalPositionManager(NetworkId.BASE, PANCAKE_NFPM);
        // EXIT / FEE_CLAIM: forwarded through the MasterChefV3 wrapper that custodies the NFT.
        String stakedContract = resolver.canonicalPositionManager(NetworkId.BASE, PANCAKE_MASTERCHEF_BASE);

        String entryId = LpPositionCorrelationSupport.contractKeyedCorrelationId(NetworkId.BASE, entryContract, "938761");
        String stakedExitId = LpPositionCorrelationSupport.contractKeyedCorrelationId(NetworkId.BASE, stakedContract, "938761");

        assertThat(entryId)
                .isEqualTo(stakedExitId)
                .isEqualTo("lp-position:base:" + PANCAKE_NFPM + ":938761");
    }

    @Test
    @DisplayName("two genuinely distinct NFPM positions sharing a numeric tokenId stay separate (negative case)")
    void distinctNfpmPositionsSharingTokenIdStaySeparate() {
        String pancakeId = LpPositionCorrelationSupport.contractKeyedCorrelationId(
                NetworkId.BASE, resolver.canonicalPositionManager(NetworkId.BASE, PANCAKE_MASTERCHEF_BASE), "100");
        String velodromeId = LpPositionCorrelationSupport.contractKeyedCorrelationId(
                NetworkId.OPTIMISM, resolver.canonicalPositionManager(NetworkId.OPTIMISM, VELODROME_GAUGE_OPTIMISM), "100");

        assertThat(pancakeId).isNotEqualTo(velodromeId);
    }
}
