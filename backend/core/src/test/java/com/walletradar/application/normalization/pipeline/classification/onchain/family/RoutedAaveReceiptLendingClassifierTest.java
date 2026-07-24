package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.session.application.TrackedWalletLookupService;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
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
 * Network-agnostic Aave V3 routed supply/withdraw classification (plan §R6b).
 *
 * <p>Regression anchors (verify after renormalization): zkSync Aave ERC-20 withdraw
 * {@code 0x4d1a74bd0fe494fad72aa1af837b005adba1c00537561aa288df504ae0b30514} (was {@code LP_EXIT})
 * and the paired supply {@code 0x7e5aac2a7c9558a811f4f998b9cbc3f60e7980dfe40b9fc251470126856908be}
 * (was {@code REWARD_CLAIM}).</p>
 */
class RoutedAaveReceiptLendingClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0xb44958ff91b4b67d8d4db010e38ccffe52551ecb";
    private static final String RELAYER = "0xe117ed7ee6b3ee8b0e9c1e7f0d1c9a1b2c3d4e5f";
    private static final String OTHER_TRACKED_WALLET = "0x2222222222222222222222222222222222222222";
    private static final String BASE_AAVE_POOL = "0xa238dd80c259a72e81d7e4664a9801593f98d1c5";
    private static final String AZKS_ZK_CONTRACT = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ZK_CONTRACT = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String USDC_CONTRACT = "0xcccccccccccccccccccccccccccccccccccccccc";

    @Test
    @DisplayName("routed zkSync Aave withdraw (aZksZK out + ZK in, rebasing dust) → LENDING_WITHDRAW with interest BUY")
    void routedZkSyncWithdrawClassifiesAsLendingWithdraw() {
        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContext(
                NetworkId.ZKSYNC,
                ROUTER,
                List.of(
                        rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("-160")),
                        rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("0.001")),
                        rawLeg("ZK", ZK_CONTRACT, new BigDecimal("160.501"))
                )
        ));

        assertThat(decision).isPresent();
        assertThat(decision.get().type()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(decision.get().protocolName()).isEqualTo("Aave");
        assertThat(decision.get().protocolVersion()).isEqualTo("V3");
        // Accrued interest (destination ZK exceeds redeemed aToken principal) surfaces as a BUY leg
        // (zero-cost income), matching the existing lending-withdraw accounting convention.
        assertThat(decision.get().flows())
                .anySatisfy(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY);
                    assertThat(flow.getAssetSymbol()).isEqualTo("ZK");
                    assertThat(flow.getQuantityDelta().signum()).isPositive();
                });
    }

    @Test
    @DisplayName("routed Aave supply (ZK out + aZksZK in) → LENDING_DEPOSIT carrying principal as TRANSFER")
    void routedSupplyClassifiesAsLendingDeposit() {
        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContext(
                NetworkId.ZKSYNC,
                ROUTER,
                List.of(
                        rawLeg("ZK", ZK_CONTRACT, new BigDecimal("-1000")),
                        rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("1000"))
                )
        ));

        assertThat(decision).isPresent();
        assertThat(decision.get().type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(decision.get().flows())
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
    }

    @Test
    @DisplayName("lone inbound aZksZK from an untracked relayer → LENDING_DEPOSIT (not REWARD_CLAIM)")
    void loneInboundReceiptFromRelayerClassifiesAsLendingDeposit() {
        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContextWithTransfers(
                NetworkId.ZKSYNC,
                RELAYER,
                List.of(rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("160"))),
                List.of(receiptTransfer(RELAYER, WALLET, "aZksZK"))
        ));

        assertThat(decision).isPresent();
        assertThat(decision.get().type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(decision.get().confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    @DisplayName("lone inbound aZksZK from another tracked wallet is left to EXTERNAL_TRANSFER carry")
    void loneInboundReceiptFromTrackedWalletIsSkipped() {
        TrackedWalletLookupService tracked = mock(TrackedWalletLookupService.class);
        when(tracked.contains(any())).thenReturn(false);
        when(tracked.contains(OTHER_TRACKED_WALLET)).thenReturn(true);

        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), tracked);

        Optional<ClassificationDecision> decision = classifier.classify(buildContextWithTransfers(
                NetworkId.ZKSYNC,
                OTHER_TRACKED_WALLET,
                List.of(rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("160"))),
                List.of(receiptTransfer(OTHER_TRACKED_WALLET, WALLET, "aZksZK"))
        ));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("direct-to-pool Aave withdraw (registered pool, e.g. BASE) is skipped — unchanged behavior")
    void directToRegisteredPoolIsSkipped() {
        ProtocolRegistryService registry = mock(ProtocolRegistryService.class);
        when(registry.lookup(any(), any())).thenReturn(Optional.empty());
        when(registry.lookup(NetworkId.BASE, BASE_AAVE_POOL)).thenReturn(Optional.of(aaveLendingPool()));

        RoutedAaveReceiptLendingClassifier classifier = classifier(registry, noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContext(
                NetworkId.BASE,
                BASE_AAVE_POOL,
                List.of(
                        rawLeg("aBasZK", AZKS_ZK_CONTRACT, new BigDecimal("-160")),
                        rawLeg("ZK", ZK_CONTRACT, new BigDecimal("160.5"))
                )
        ));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("presence of an Aave debt marker (borrow/repay shape) is not claimed as supply/withdraw")
    void debtMarkerShapeIsSkipped() {
        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContext(
                NetworkId.ZKSYNC,
                ROUTER,
                List.of(
                        rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("-160")),
                        rawLeg("ZK", ZK_CONTRACT, new BigDecimal("160.5")),
                        rawLeg("variableDebtZksZK", "0xdddddddddddddddddddddddddddddddddddddddd", new BigDecimal("-50"))
                )
        ));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("aToken sold to a different-family asset (aZksZK out + USDC in) is not a lending withdraw")
    void aTokenSoldToDifferentFamilyIsSkipped() {
        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContext(
                NetworkId.ZKSYNC,
                ROUTER,
                List.of(
                        rawLeg("aZksZK", AZKS_ZK_CONTRACT, new BigDecimal("-160")),
                        rawLeg("USDC", USDC_CONTRACT, new BigDecimal("500"))
                )
        ));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("non-Aave lending receipt (Compound cWETH) is out of scope and skipped")
    void nonAaveReceiptIsSkipped() {
        RoutedAaveReceiptLendingClassifier classifier = classifier(emptyRegistry(), noTrackedWallets());

        Optional<ClassificationDecision> decision = classifier.classify(buildContext(
                NetworkId.BASE,
                ROUTER,
                List.of(
                        rawLeg("WETH", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", new BigDecimal("-1")),
                        rawLeg("cWETH", "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", new BigDecimal("1"))
                )
        ));

        assertThat(decision).isEmpty();
    }

    private static RoutedAaveReceiptLendingClassifier classifier(
            ProtocolRegistryService registry,
            TrackedWalletLookupService tracked
    ) {
        return new RoutedAaveReceiptLendingClassifier(registry, tracked);
    }

    private static ProtocolRegistryService emptyRegistry() {
        ProtocolRegistryService registry = mock(ProtocolRegistryService.class);
        when(registry.lookup(any(), any())).thenReturn(Optional.empty());
        return registry;
    }

    private static TrackedWalletLookupService noTrackedWallets() {
        TrackedWalletLookupService tracked = mock(TrackedWalletLookupService.class);
        when(tracked.contains(any())).thenReturn(false);
        return tracked;
    }

    private static ProtocolRegistryEntry aaveLendingPool() {
        return new ProtocolRegistryEntry(
                BASE_AAVE_POOL,
                Set.of(NetworkId.BASE),
                ProtocolRegistryFamily.LENDING,
                ProtocolRegistryRole.POOL,
                ProtocolRegistryEventType.LENDING_DEPOSIT,
                ConfidenceLevel.HIGH,
                "Aave",
                "V3",
                false,
                null
        );
    }

    private static Document receiptTransfer(String from, String to, String symbol) {
        return new Document()
                .append("from", from)
                .append("to", to)
                .append("tokenSymbol", symbol)
                .append("contractAddress", AZKS_ZK_CONTRACT)
                .append("value", "160000000000000000000");
    }

    private static RawLeg rawLeg(String symbol, String contract, BigDecimal quantity) {
        return RawLeg.asset(contract, symbol, quantity);
    }

    private static OnChainClassificationContext buildContext(
            NetworkId networkId,
            String toAddress,
            List<RawLeg> legs
    ) {
        return buildContextWithTransfers(networkId, toAddress, legs, List.of());
    }

    private static OnChainClassificationContext buildContextWithTransfers(
            NetworkId networkId,
            String toAddress,
            List<RawLeg> legs,
            List<Document> tokenTransfers
    ) {
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xtest" + Integer.toHexString(toAddress.hashCode()))
                .setNetworkId(networkId.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", toAddress)
                        .append("from", WALLET)
                        .append("methodId", "0x73fc4457")
                        .append("explorer", new Document("tokenTransfers", tokenTransfers)
                                .append("internalTransfers", List.of())));

        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                legs
        );
    }
}
