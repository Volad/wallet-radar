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
 * R6a: {@link RewardRouteClassifier} must DEFER (return empty) when an inbound principal leg is a
 * registered LP {@code POOL} token — i.e. a Balancer BPT returned by an Aura BaseRewardPool
 * {@code withdrawAndUnwrap(uint256, bool claim)}. The literal {@code claim} parameter name makes
 * {@code hasExplicitClaimSignal} true, and the router (Aura BoosterLite / BaseRewardPool) is a
 * {@code REWARD_ROUTER}, so without the guard the tx would be forced to {@code REWARD_CLAIM},
 * severing BPT basis continuity. Deferring lets {@link LpRegistryClassifier} classify it as
 * {@code LP_POSITION_UNSTAKE}. Evidence anchor (AVALANCHE): {@code 0x2447…} withdrawAndUnwrap on
 * the Aura deposit-vault {@code 0x703735…} returning BPT {@code 0xfcec3c8d…}.
 */
class RewardRouteClassifierLpUnstakeDeferralTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String REWARD_ROUTER = "0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc";
    private static final String BPT = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String BAL = "0xe15bcb9e0ea69e6ab9fa080c4c4a5632896298c3";
    private static final String WITHDRAW_SELECTOR = "0xc32e7202";
    private static final String CLAIM_FN = "withdrawAndUnwrap(uint256 amount, bool claim) returns (bool)";

    @Test
    @DisplayName("Claim-signalled withdraw returning a registered LP pool BPT → defers (empty)")
    void defersWhenInboundLegIsRegisteredLpPool() {
        RewardRouteClassifier classifier = classifier(true);

        // BPT flows back in (principal) + a BAL reward token — an LP position unstake, not a harvest.
        List<RawLeg> legs = List.of(
                RawLeg.asset(BPT, "Aave GHO/USDT/USDC", new BigDecimal("42.898493206184378445")),
                RawLeg.asset(BAL, "BAL", new BigDecimal("0.12"))
        );

        Optional<ClassificationDecision> decision = classifier.classify(context(legs));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("Claim-signalled tx with only reward tokens (no LP pool principal) → REWARD_CLAIM")
    void classifiesRewardClaimWhenNoLpPoolPrincipal() {
        RewardRouteClassifier classifier = classifier(false);

        // Only a BAL reward token flows in — a genuine harvest.
        List<RawLeg> legs = List.of(
                RawLeg.asset(BAL, "BAL", new BigDecimal("0.12"))
        );

        Optional<ClassificationDecision> decision = classifier.classify(context(legs));

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
    }

    private RewardRouteClassifier classifier(boolean registerBptAsPool) {
        ProtocolRegistryService registry = mock(ProtocolRegistryService.class);
        when(registry.lookup(any(), any())).thenReturn(Optional.empty());
        when(registry.lookup(NetworkId.AVALANCHE, REWARD_ROUTER)).thenReturn(Optional.of(rewardRouterEntry()));
        if (registerBptAsPool) {
            when(registry.lookup(NetworkId.AVALANCHE, BPT)).thenReturn(Optional.of(bptPoolEntry()));
        }
        return new RewardRouteClassifier(registry);
    }

    private static ProtocolRegistryEntry rewardRouterEntry() {
        return new ProtocolRegistryEntry(
                REWARD_ROUTER, Set.of(NetworkId.AVALANCHE),
                ProtocolRegistryFamily.YIELD, ProtocolRegistryRole.REWARD_ROUTER,
                null, ConfidenceLevel.HIGH, "Aura", "Sidechain", false, null);
    }

    private static ProtocolRegistryEntry bptPoolEntry() {
        return new ProtocolRegistryEntry(
                BPT, Set.of(NetworkId.AVALANCHE),
                ProtocolRegistryFamily.DEX, ProtocolRegistryRole.POOL,
                null, ConfidenceLevel.HIGH, "Balancer", "V3", false, null);
    }

    private static OnChainClassificationContext context(List<RawLeg> legs) {
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0x2447000000000000000000000000000000000000000000000000000000000000")
                .setNetworkId(NetworkId.AVALANCHE.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", REWARD_ROUTER)
                        .append("from", WALLET)
                        .append("methodId", WITHDRAW_SELECTOR)
                        .append("functionName", CLAIM_FN)
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
