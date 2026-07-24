package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.DexGaugePoolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.LpStakingWrapperResolver;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Velodrome/Aerodrome <b>v2 (fungible) gauge</b> STAKE correlation-keying
 * ({@link com.walletradar.application.normalization.pipeline.classification.onchain.family.LpRegistryClassifier}),
 * driven purely by on-chain gauge grammar via {@link DexGaugePoolResolver} (no address hardcoding).
 *
 * <p>Evidence anchor (Optimism): {@code deposit(uint256)} (selector {@code 0xb6b55f25}) to the v2
 * AMM gauge {@code 0xbc6043a5…} whose {@code pool()} = the staked USD₮0/USDT AMM LP token
 * {@code 0x4da46c6a…}. A v2 gauge ({@code nft()} reverts) keys on the staked LP token, carrying the
 * gauge for valuation; a CL/Slipstream gauge ({@code nft()} present → resolver empty) keeps the
 * legacy NFPM {@code :vault} path.
 */
class LpRegistryClassifierTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String GAUGE = "0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad";
    private static final String STAKED_LP_TOKEN = "0x4da46c6afe7322b66efefda1f702605cbe08e0bd";
    private static final String NFPM = "0x416b433906b1b72fa758e166e239c43d68dc6f29";
    private static final String DEPOSIT_SELECTOR = "0xb6b55f25";

    @Test
    @DisplayName("v2 gauge deposit keys correlation on the staked LP token (gauge.pool()), carrying the gauge")
    void v2GaugeDepositKeysOnStakedLpToken() {
        // v2 gauge with no NFPM mapping; the resolver reports the staked AMM LP token.
        DexGaugePoolResolver gaugeResolver = mock(DexGaugePoolResolver.class);
        when(gaugeResolver.resolveFungibleGaugeStakedLpToken(NetworkId.OPTIMISM, GAUGE))
                .thenReturn(Optional.of(STAKED_LP_TOKEN));

        LpRegistryClassifier classifier = classifier(gaugeResolver, stakeGaugeEntry(null));

        Optional<ClassificationDecision> decision = classifier.classify(depositContext());

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().correlationId())
                .isEqualTo("lp-position:optimism:" + STAKED_LP_TOKEN + ":vault:" + GAUGE);
    }

    @Test
    @DisplayName("CL/Slipstream gauge (resolver empty) keeps the legacy NFPM :vault path")
    void clGaugeKeepsNfpmVaultPath() {
        // CL gauge exposes nft() → resolver returns empty; the registry maps it to a Slipstream NFPM.
        DexGaugePoolResolver gaugeResolver = mock(DexGaugePoolResolver.class);
        when(gaugeResolver.resolveFungibleGaugeStakedLpToken(any(), any())).thenReturn(Optional.empty());

        LpRegistryClassifier classifier = classifier(gaugeResolver, stakeGaugeEntry(NFPM));

        Optional<ClassificationDecision> decision = classifier.classify(depositContext());

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().correlationId())
                .isEqualTo("lp-position:optimism:" + NFPM + ":vault");
    }

    private LpRegistryClassifier classifier(DexGaugePoolResolver gaugeResolver, ProtocolRegistryEntry gaugeEntry) {
        ProtocolRegistryService registry = mock(ProtocolRegistryService.class);
        when(registry.lookup(any(), any())).thenReturn(Optional.empty());
        when(registry.lookup(NetworkId.OPTIMISM, GAUGE)).thenReturn(Optional.of(gaugeEntry));

        // A DEX gauge's underlyingPositionManager (the mis-mapped Slipstream NFPM) canonicalizes the
        // gauge address to the NFPM for the legacy vault-fallback path.
        LpStakingWrapperResolver wrapperResolver = mock(LpStakingWrapperResolver.class);
        when(wrapperResolver.canonicalPositionManager(any(), any()))
                .thenAnswer(inv -> {
                    String contract = inv.getArgument(1);
                    return GAUGE.equalsIgnoreCase(contract) && gaugeEntry.underlyingPositionManager() != null
                            ? gaugeEntry.underlyingPositionManager()
                            : contract;
                });

        NativeAssetSymbolResolver nativeAssetSymbolResolver = mock(NativeAssetSymbolResolver.class);

        return new LpRegistryClassifier(registry, nativeAssetSymbolResolver, wrapperResolver, null, gaugeResolver);
    }

    private static ProtocolRegistryEntry stakeGaugeEntry(String underlyingPositionManager) {
        return new ProtocolRegistryEntry(
                GAUGE,
                Set.of(NetworkId.OPTIMISM),
                ProtocolRegistryFamily.DEX,
                ProtocolRegistryRole.STAKE_CONTRACT,
                ProtocolRegistryEventType.LP_MINT,
                ConfidenceLevel.HIGH,
                "Velodrome",
                "V2",
                false,
                null,
                underlyingPositionManager
        );
    }

    private static OnChainClassificationContext depositContext() {
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0x83978f62a0f05b662a87210263e923ad568d616f5dd8c420d0485e1e21828a61")
                .setNetworkId(NetworkId.OPTIMISM.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", GAUGE)
                        .append("from", WALLET)
                        .append("methodId", DEPOSIT_SELECTOR)
                        .append("explorer", new Document("tokenTransfers", List.of())
                                .append("internalTransfers", List.of())));

        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );
    }
}
