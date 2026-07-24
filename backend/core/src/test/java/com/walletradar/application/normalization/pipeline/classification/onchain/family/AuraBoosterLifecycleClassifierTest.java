package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.application.normalization.pipeline.classification.support.LpStakingWrapperResolver;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R6a: Aura Finance Booster deposit/withdraw of a Balancer V3 BPT must classify as
 * {@code LP_POSITION_STAKE}/{@code LP_POSITION_UNSTAKE} (basis-neutral), keying the pool via the
 * Aura deposit-vault entry's {@code underlyingPositionManager} so the stake shares the JOIN/BURN
 * basis pool. Evidence anchors (AVALANCHE): deposit {@code 0xcbfe…30c8} via BoosterLite
 * {@code 0x98ef32…} (selector {@code 0x43a0d066}); the deposit-vault receipt is {@code 0x703735…}
 * whose {@code underlyingPositionManager} is the Balancer V3 boosted-stable BPT {@code 0xfcec3c8d…}.
 */
class AuraBoosterLifecycleClassifierTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String BOOSTER = "0x98ef32edd24e2c92525e59afc4475c1242a30184";
    private static final String DEPOSIT_VAULT = "0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc";
    private static final String BPT = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String DEPOSIT_SELECTOR = "0x43a0d066";
    private static final String EXPECTED_CORRELATION = "lp-position:avalanche:balancerv3:" + BPT;

    @Test
    @DisplayName("Aura Booster deposit (BPT out, deposit-vault in) → LP_POSITION_STAKE, balancerv3 correlation")
    void auraBoosterDepositIsStake() {
        LpRegistryClassifier classifier = classifier();

        // deposit: BPT leaves the wallet, the Aura deposit-vault receipt is minted to the wallet.
        List<RawLeg> legs = List.of(
                RawLeg.asset(BPT, "Aave GHO/USDT/USDC", new BigDecimal("-42.898493206184378445")),
                RawLeg.asset(DEPOSIT_VAULT, "auraAave GHO/USDT/USDC-vault", new BigDecimal("42.898493206184378445"))
        );

        Optional<ClassificationDecision> decision = classifier.classify(boosterContext(legs));

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LP_POSITION_STAKE);
        assertThat(decision.orElseThrow().correlationId()).isEqualTo(EXPECTED_CORRELATION);
        // The raw BPT out-leg is canonicalized to an LP-RECEIPT outbound leg (basis continuity).
        assertThat(decision.orElseThrow().flows())
                .anySatisfy(flow -> {
                    assertThat(flow.getAssetSymbol()).isNotNull();
                    assertThat(flow.getAssetSymbol().toUpperCase()).contains("LP-RECEIPT");
                });
    }

    @Test
    @DisplayName("Aura Booster withdraw (BPT in, deposit-vault out) → LP_POSITION_UNSTAKE")
    void auraBoosterWithdrawIsUnstake() {
        LpRegistryClassifier classifier = classifier();

        List<RawLeg> legs = List.of(
                RawLeg.asset(BPT, "Aave GHO/USDT/USDC", new BigDecimal("42.898493206184378445")),
                RawLeg.asset(DEPOSIT_VAULT, "auraAave GHO/USDT/USDC-vault", new BigDecimal("-42.898493206184378445"))
        );

        Optional<ClassificationDecision> decision = classifier.classify(boosterContext(legs));

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        assertThat(decision.orElseThrow().correlationId()).isEqualTo(EXPECTED_CORRELATION);
    }

    @Test
    @DisplayName("Booster call with no BPT movement (pure getReward) is not classified as LP stake/unstake")
    void auraBoosterWithoutBptDefers() {
        LpRegistryClassifier classifier = classifier();

        // Only a reward token moves — no BPT/deposit-vault pair.
        List<RawLeg> legs = List.of(
                RawLeg.asset("0xe15bcb9e0ea69e6ab9fa080c4c4a5632896298c3", "BAL", new BigDecimal("0.1"))
        );

        Optional<ClassificationDecision> decision = classifier.classify(boosterContext(legs));

        assertThat(decision).isEmpty();
    }

    private LpRegistryClassifier classifier() {
        ProtocolRegistryService registry = mock(ProtocolRegistryService.class);
        when(registry.lookup(any(), any())).thenReturn(Optional.empty());
        when(registry.lookup(NetworkId.AVALANCHE, BOOSTER)).thenReturn(Optional.of(boosterEntry()));
        when(registry.lookup(NetworkId.AVALANCHE, DEPOSIT_VAULT)).thenReturn(Optional.of(depositVaultEntry()));
        when(registry.lookup(NetworkId.AVALANCHE, BPT)).thenReturn(Optional.of(bptEntry()));

        LpStakingWrapperResolver wrapperResolver = mock(LpStakingWrapperResolver.class);
        when(wrapperResolver.canonicalPositionManager(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        NativeAssetSymbolResolver nativeAssetSymbolResolver = mock(NativeAssetSymbolResolver.class);

        return new LpRegistryClassifier(registry, nativeAssetSymbolResolver, wrapperResolver, null, null);
    }

    private static ProtocolRegistryEntry boosterEntry() {
        return new ProtocolRegistryEntry(
                BOOSTER, Set.of(NetworkId.AVALANCHE),
                ProtocolRegistryFamily.YIELD, ProtocolRegistryRole.REWARD_ROUTER,
                null, ConfidenceLevel.HIGH, "Aura", "Sidechain", false,
                ProtocolRegistrySpecialHandlerType.AURA_BOOSTER, null);
    }

    private static ProtocolRegistryEntry depositVaultEntry() {
        return new ProtocolRegistryEntry(
                DEPOSIT_VAULT, Set.of(NetworkId.AVALANCHE),
                ProtocolRegistryFamily.DEX, ProtocolRegistryRole.STAKE_CONTRACT,
                null, ConfidenceLevel.HIGH, "Aura", "Vault", false,
                null, BPT);
    }

    private static ProtocolRegistryEntry bptEntry() {
        return new ProtocolRegistryEntry(
                BPT, Set.of(NetworkId.AVALANCHE),
                ProtocolRegistryFamily.DEX, ProtocolRegistryRole.POOL,
                null, ConfidenceLevel.HIGH, "Balancer", "V3", false,
                null, null);
    }

    private static OnChainClassificationContext boosterContext(List<RawLeg> legs) {
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xcbfe3e467be3ca4154adc01dbdbf878592c5f65026f1d32464d28185976730c8")
                .setNetworkId(NetworkId.AVALANCHE.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", BOOSTER)
                        .append("from", WALLET)
                        .append("methodId", DEPOSIT_SELECTOR)
                        .append("functionName", "deposit(uint256 _pid, uint256 _amount, bool _stake) returns (bool)")
                        .append("explorer", new Document("tokenTransfers", List.<Document>of())
                                .append("internalTransfers", List.<Document>of())));

        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                legs
        );
    }
}
