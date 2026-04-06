package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.classification.support.CowSwapSupport;
import com.walletradar.ingestion.pipeline.classification.support.GmxEventTopicSupport;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String COUNTERPARTY = "0x2222222222222222222222222222222222222222";
    private static final String PROTOCOL = "0xdddddddddddddddddddddddddddddddddddddddd";
    private static final String TOKEN_A = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TOKEN_B = "0xcccccccccccccccccccccccccccccccccccccccc";
    private static final String WRAPPED_NATIVE_PREDEPLOY = "0x4200000000000000000000000000000000000006";

    @Mock
    private ProtocolRegistryService protocolRegistryService;
    @Mock
    private TrackedWalletLookupService trackedWalletLookupService;

    private OnChainClassifier classifier;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());
        classifier = new OnChainClassifier(
                protocolRegistryService,
                trackedWalletLookupService,
                new NativeAssetSymbolResolver()
        );
    }

    @Test
    @DisplayName("protocol registry takes precedence over method id")
    void protocolRegistryTakesPrecedenceOverMethodId() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        ROUTER,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Test Bridge",
                        "v1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.protocolName()).isEqualTo("Test Bridge");
    }

    @Test
    @DisplayName("special handler unsupported method becomes NEEDS_REVIEW and does not fall through")
    void specialHandlerUnsupportedMethodBecomesNeedsReview() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        ROUTER,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.VAULT,
                        null,
                        ConfidenceLevel.HIGH,
                        "Balancer",
                        "V2",
                        true,
                        ProtocolRegistrySpecialHandlerType.BALANCER_VAULT
                )));
        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.missingDataReasons()).contains("HANDLER_UNSUPPORTED_METHOD");
    }

    @Test
    @DisplayName("synthetic logs do not create flows when explorer transfers are absent")
    void syntheticLogsDoNotCreateFlows() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData()
                .append("input", "0x095ea7b3000000000000000000000000")
                .append("methodId", "0x095ea7b3")
                .append("logs", List.of(
                        new Document("address", "0xtoken")
                                .append("topics", List.of("0xddf252ad"))
                                .append("__syntheticTransferLog", true)
                ));
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.empty());

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.APPROVE);
        assertThat(result.flows()).isEmpty();
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("blank methodId falls back to selector recovered from input")
    void blankMethodIdFallsBackToSelectorRecoveredFromInput() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x38ED1739000000000000000000000000");
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.empty());

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("swap extracts separate gas fee leg")
    void swapExtractsGasFeeLeg() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.empty());

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.flows()).hasSize(3);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-0.00105"));
                });
    }

    @Test
    @DisplayName("tracked counterparty yields owner-agnostic external transfer with continuity metadata")
    void trackedCounterpartyYieldsOwnerAgnosticTransfer() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", COUNTERPARTY);
        rawTransaction.getRawData().put("value", "1000000000000000000");
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, COUNTERPARTY))
                .thenReturn(Optional.empty());
        when(trackedWalletLookupService.contains(COUNTERPARTY))
                .thenReturn(true);

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(result.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.continuityCandidate()).isTrue();
        assertThat(result.matchedCounterparty()).isEqualTo(COUNTERPARTY);
        assertThat(result.flows())
                .allSatisfy(flow -> assertThat(flow.getRole()).isIn(NormalizedLegRole.SELL, NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("lending deposit keeps principal and receipt flows as transfer")
    void lendingDepositKeepsContinuityRoles() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("methodId", "0x617ba037");
        rawTransaction.getRawData().put("functionName", "supply(address,uint256,address,uint16)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", PROTOCOL)
                        .append("value", "1000000"),
                new Document("contractAddress", "0xbcca60bb61934080951369a648fb03df4f96263c")
                        .append("tokenSymbol", "aUSDC")
                        .append("tokenDecimal", "6")
                        .append("from", PROTOCOL)
                        .append("to", WALLET)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.empty());

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
    }

    @Test
    @DisplayName("plasma native symbol is XPL including gas leg")
    void plasmaNativeSymbolIsXpl() {
        RawTransaction rawTransaction = baseRaw(NetworkId.PLASMA);
        rawTransaction.getRawData().put("to", COUNTERPARTY);
        rawTransaction.getRawData().put("value", "500000000000000000");
        when(protocolRegistryService.lookup(NetworkId.PLASMA, COUNTERPARTY))
                .thenReturn(Optional.empty());

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.flows())
                .extracting(flow -> flow.getAssetSymbol())
                .contains("XPL");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> assertThat(flow.getAssetSymbol()).isEqualTo("XPL"));
    }

    @Test
    @DisplayName("wrapped-native deposit selector becomes WRAP with synthetic inbound leg")
    void wrappedNativeDepositSelectorBecomesWrapWithSyntheticInboundLeg() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", WRAPPED_NATIVE_PREDEPLOY);
        rawTransaction.getRawData().put("methodId", "0xd0e30db0");
        rawTransaction.getRawData().put("functionName", "deposit()");
        rawTransaction.getRawData().put("value", "1000000000000000000");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.WRAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER)
                .hasSize(2);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("ETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-1")));
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("WETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("1")));
    }

    @Test
    @DisplayName("wrapped-native deposit selector with weak top-level fields still becomes WRAP")
    void wrappedNativeDepositSelectorWithWeakTopLevelFieldsStillBecomesWrap() {
        String wrappedNative = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().remove("from");
        rawTransaction.getRawData().remove("to");
        rawTransaction.getRawData().remove("value");
        rawTransaction.getRawData().put("methodId", "0xd0e30db0");
        rawTransaction.getRawData().put("functionName", "deposit()");
        rawTransaction.getRawData().put("input", "deprecated");
        rawTransaction.getRawData().put("explorer", new Document("tx", new Document()
                .append("methodId", "0xd0e30db0")
                .append("functionName", "deposit()")
                .append("input", "deprecated"))
                .append("tokenTransfers", List.of(
                        new Document("contractAddress", wrappedNative)
                                .append("tokenSymbol", "WETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", WALLET)
                                .append("value", "258000000000000000")
                ))
                .append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.WRAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("ETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-0.258000000000000000")));
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("WETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("0.258000000000000000")));
    }

    @Test
    @DisplayName("wrapped-native withdraw selector becomes UNWRAP with synthetic native inbound leg")
    void wrappedNativeWithdrawSelectorBecomesUnwrapWithSyntheticNativeInboundLeg() {
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("to", WRAPPED_NATIVE_PREDEPLOY);
        rawTransaction.getRawData().put("methodId", "0x2e1a7d4d");
        rawTransaction.getRawData().put("functionName", "withdraw(uint256)");
        rawTransaction.getRawData().put("input", "0x2e1a7d4d"
                + "0000000000000000000000000000000000000000000000000de0b6b3a7640000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", WRAPPED_NATIVE_PREDEPLOY)
                        .append("tokenSymbol", "WETH")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", WRAPPED_NATIVE_PREDEPLOY)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNWRAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER)
                .hasSize(2);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("WETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-1")));
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("ETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("1")));
    }

    @Test
    @DisplayName("wrapped-native withdraw selector with internal continuity and weak top-level to still becomes UNWRAP")
    void wrappedNativeWithdrawSelectorWithInternalContinuityAndWeakTopLevelToStillBecomesUnwrap() {
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().remove("to");
        rawTransaction.getRawData().put("methodId", "0x2e1a7d4d");
        rawTransaction.getRawData().put("functionName", "withdraw(uint256 wad)");
        rawTransaction.getRawData().put("input", "0x2e1a7d4d"
                + "00000000000000000000000000000000000000000000000009b67e0669b0c936");
        rawTransaction.getRawData().put("explorer", new Document("tx", new Document()
                .append("methodId", "0x2e1a7d4d")
                .append("functionName", "withdraw(uint256 wad)")
                .append("input", "0x2e1a7d4d00000000000000000000000000000000000000000000000009b67e0669b0c936"))
                .append("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", WRAPPED_NATIVE_PREDEPLOY)
                                .append("to", WALLET)
                                .append("value", "699885358110787894")
                                .append("isError", "0")
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNWRAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("WETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-0.699885358110787894")));
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER && flow.getAssetSymbol().equals("ETH"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("0.699885358110787894")));
    }

    @Test
    @DisplayName("low-confidence inbound with complete receipt data goes directly to PENDING_PRICE")
    void lowConfidenceInboundWithCompleteReceiptDataGoesDirectlyToPendingPrice() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "2500000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("reward-like inbound without verified distributor stays EXTERNAL_TRANSFER_IN with ambiguity metadata")
    void rewardLikeInboundWithoutVerifiedDistributorStaysExternalInboundWithAmbiguityMetadata() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("functionName", "distributeRewards()");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "AERO Rewards")
                        .append("tokenName", "Aerodrome Reward")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).containsExactly("AMBIGUOUS_INBOUND_VS_REWARD");
    }

    @Test
    @DisplayName("known reward distributor inbound becomes REWARD_CLAIM without clarification")
    void knownRewardDistributorInboundBecomesRewardClaimWithoutClarification() {
        String rewardDistributor = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "AERO")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Reward Distributor",
                        "v1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("claim function on unknown contract does not auto-classify as reward claim")
    void claimFunctionOnUnknownContractDoesNotAutoClassifyAsRewardClaim() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("functionName", "claim(address)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "TOKEN")
                        .append("tokenName", "Token")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.missingDataReasons()).containsExactly("AMBIGUOUS_INBOUND_VS_REWARD");
    }

    @Test
    @DisplayName("promo phishing inbound becomes explicit review and never reward claim")
    void promoPhishingInboundBecomesExplicitReviewAndNeverRewardClaim() {
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("functionName", "Rewards(address,address[],uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "Claim Your Airdrop ( Velodromefi.Store )")
                        .append("tokenName", "Reward")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Plasma promo spam family with suspicious token text stays explicit review even with multiple transfers")
    void plasmaPromoSpamFamilyWithSuspiciousTokenTextStaysExplicitReviewEvenWithMultipleTransfers() {
        RawTransaction rawTransaction = baseRaw(NetworkId.PLASMA);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0x1939c1ff");
        rawTransaction.getRawData().put("functionName", "claimRewards(bytes data)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "www.baseapp.cfd - claim Base airdrop")
                        .append("tokenName", "Spam Token")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "SPAM")
                        .append("tokenName", "Spam Token")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", "0x3333333333333333333333333333333333333333")
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Base multicall token-drop family becomes promo spam even without suspicious token text")
    void baseMulticallTokenDropFamilyBecomesPromoSpamEvenWithoutSuspiciousTokenText() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "MOLT")
                        .append("tokenName", "MOLT")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("repeated selector 0xeec4378e spam family becomes promo spam")
    void repeatedSelectorSpamFamilyBecomesPromoSpam() {
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("methodId", "0xeec4378e");
        rawTransaction.getRawData().put("functionName", "");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USD.uni")
                        .append("tokenName", "USD.uni")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Base batchTransfer promo distribution becomes promo spam")
    void baseBatchTransferPromoDistributionBecomesPromoSpam() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.BASE,
                "e34a5d4d",
                "batchTransfer(address token, address from, address[] recipients, uint256[] amounts)",
                "SURYA",
                "Surya PRO"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Base multiSend promo distribution becomes promo spam")
    void baseMultiSendPromoDistributionBecomesPromoSpam() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.BASE,
                "9ec68f0f",
                "multiSend(address _token, address[] _accounts, uint256[] _amounts)",
                "TLOU",
                "The  Last Of Us"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Base array-transfer promo distribution becomes promo spam")
    void baseArrayTransferPromoDistributionBecomesPromoSpam() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.BASE,
                "a06c1a33",
                "transfer(address[] walletAddress)",
                "GOOFS",
                "Goofs World"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Base blank-method promo drop becomes promo spam")
    void baseBlankMethodPromoDropBecomesPromoSpam() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.BASE,
                "",
                "",
                "DOODLE",
                "Doodle By Virtuals"
        );
        rawTransaction.getRawData().put("input", "0x1786a230000000000000000000000000");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("ACHIVX batchTransfer on Arbitrum becomes promo spam")
    void achivxBatchTransferOnArbitrumBecomesPromoSpam() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.ARBITRUM,
                "0x88d695b2",
                "batchTransfer(address[] recipients_, uint256[] amounts_)",
                "ACHIVX",
                "ACHIVX"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("ACHIVX batchTransfer on Optimism becomes promo spam when selector lacks 0x")
    void achivxBatchTransferOnOptimismBecomesPromoSpamWithout0xSelector() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.OPTIMISM,
                "88d695b2",
                "batchTransfer(address[] _tos, uint256[] _values)",
                "ACHIVX",
                "ACHIVX"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("same selector without audited promo marker remains external inbound")
    void sameSelectorWithoutAuditedPromoMarkerRemainsExternalInbound() {
        RawTransaction rawTransaction = promoDistributionRaw(
                NetworkId.ARBITRUM,
                "0x88d695b2",
                "batchTransfer(address[] recipients_, uint256[] amounts_)",
                "USDC",
                "USD Coin"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("Avalanche homoglyph USDC spoof inbound becomes promo spam")
    void avalancheHomoglyphUsdcSpoofInboundBecomesPromoSpam() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.AVALANCHE,
                "0xa9059cbb",
                "transfer(address recipient, uint256 amount) returns (bool)",
                "0x318c6a3cb85952641cd253b2311b0cee30f44822",
                "UЅDС",
                "UЅDС",
                "0x1a872b33479b10f57d308104004a4d5f57bf693f"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Ethereum self-drop promo token inbound becomes promo spam")
    void ethereumSelfDropPromoTokenInboundBecomesPromoSpam() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.ETHEREUM,
                "0x3b1102c3",
                "",
                "0x83819bf7e906bcf57e9f5b20453a2eff43f3845c",
                "PORT",
                "DePORT",
                "0x83819bf7e906bcf57e9f5b20453a2eff43f3845c"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Base cbXRP batchTransfer promo inbound becomes promo spam")
    void baseCbxrpBatchTransferPromoInboundBecomesPromoSpam() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.BASE,
                "1239ec8c",
                "batchTransfer(address token, address[] recipients, uint256[] amounts)",
                "0x41e357ea17eed8e3ee32451f8e5cba824af58dbf",
                "cbXRP",
                "Coinbase Wrapped XRP",
                "0x6bb7c862d5db0e03388148106ef86dac76cb8598"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Unichain sendBatchTokens promo inbound becomes promo spam")
    void unichainSendBatchTokensPromoInboundBecomesPromoSpam() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.UNICHAIN,
                "0x9f1b6858",
                "sendBatchTokens(address token,uint256 tokenAmount,address[] targets)",
                "0x03c2868c6d7fd27575426f395ee081498b1120dd",
                "GRG",
                "Rigo Token",
                "0xa3c91e040673151e520bb7c63c89ec01f06521bb"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Polygon ZHT distributor inbound becomes promo spam")
    void polygonZhtDistributorInboundBecomesPromoSpam() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.POLYGON,
                "0xa9059cbb",
                "transfer(address dst, uint256 rawAmount)",
                "0x90a9e2772d6b53c92ccbeaba6c31a02c22eac111",
                "ZHT",
                "ZHT Token",
                "0x222884666fec64f6ab5368d9d7250d7103751f7a"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("Arbitrum xAUUSD distributor inbound becomes promo spam")
    void arbitrumXauusdDistributorInboundBecomesPromoSpam() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.ARBITRUM,
                "0x4fdb8d47",
                "",
                "0x51c25acea32ec4237fcde962ed7789d0941169bc",
                "xAUUSD",
                "XAUUSD",
                "0x068a2418d4b1c8fda198b58b5035b8267675e40e"
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
    }

    @Test
    @DisplayName("same Base batchTransfer on different token remains external inbound")
    void sameBaseBatchTransferOnDifferentTokenRemainsExternalInbound() {
        RawTransaction rawTransaction = promoInboundRaw(
                NetworkId.BASE,
                "1239ec8c",
                "batchTransfer(address token, address[] recipients, uint256[] amounts)",
                TOKEN_A,
                "USDC",
                "USD Coin",
                COUNTERPARTY
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("known type with missing receipt-safe fields stays in PENDING_CLARIFICATION")
    void knownTypeWithMissingReceiptSafeFieldsStaysInPendingClarification() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().remove("gasUsed");
        rawTransaction.getRawData().remove("gasPrice");
        rawTransaction.getRawData().remove("txreceipt_status");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).containsExactly(
                "MISSING_EXECUTION_STATUS",
                "MISSING_EFFECTIVE_GAS_PRICE",
                "MISSING_GAS_USED"
        );
    }

    @Test
    @DisplayName("non fee payer clarification row still surfaces missing effective gas price")
    void nonFeePayerClarificationRowStillSurfacesMissingEffectiveGasPrice() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().remove("txreceipt_status");
        rawTransaction.getRawData().remove("gasPrice");
        rawTransaction.getRawData().remove("effectiveGasPrice");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).containsExactly(
                "MISSING_EXECUTION_STATUS",
                "MISSING_EFFECTIVE_GAS_PRICE"
        );
    }

    @Test
    @DisplayName("across depositV3 bridge path does not fall into broad deposit classification")
    void acrossDepositV3BridgePathDoesNotFallIntoBroadDepositClassification() {
        String acrossSpokePool = "0x09aea4b2242abc8bb4bb78d537a67a245a7bec64";
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("to", acrossSpokePool);
        rawTransaction.getRawData().put("methodId", "0x7b939232");
        rawTransaction.getRawData().put("functionName", "depositV3((address,address,address,address,uint256,uint256,uint256,address,uint32,uint32,uint32,bytes))");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", acrossSpokePool)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, acrossSpokePool))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        acrossSpokePool,
                        Set.of(NetworkId.UNICHAIN),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Across",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("across depositV3 uses transfer-backed SpokePool identity when top-level to is weak")
    void acrossDepositV3UsesTransferBackedSpokePoolIdentityWhenTopLevelToIsWeak() {
        String acrossSpokePool = "0xe35e9842fceaca96570b734083f4a58e8f7c5f2a";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().remove("to");
        rawTransaction.getRawData().remove("from");
        rawTransaction.getRawData().put("methodId", "0x7b939232");
        rawTransaction.getRawData().put("functionName", "depositV3(address depositor,address recipient,address inputToken,address outputToken,uint256 inputAmount,uint256 outputAmount,uint256 destinationChainId,address exclusiveRelayer,uint32 quoteTimestamp,uint32 fillDeadline,uint32 exclusivityDeadline,bytes message)");
        rawTransaction.getRawData().put("input", "deprecated");
        rawTransaction.getRawData().put("explorer", new Document("tx", new Document()
                .append("methodId", "0x7b939232")
                .append("functionName", "depositV3(address depositor,address recipient,address inputToken,address outputToken,uint256 inputAmount,uint256 outputAmount,uint256 destinationChainId,address exclusiveRelayer,uint32 quoteTimestamp,uint32 fillDeadline,uint32 exclusivityDeadline,bytes message)")
                .append("input", "deprecated"))
                .append("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", acrossSpokePool)
                                .append("value", "641214425")
                )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, acrossSpokePool))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        acrossSpokePool,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Across",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("non-Across depositV3-like selector does not auto-classify as bridge out")
    void nonAcrossDepositV3LikeSelectorDoesNotAutoClassifyAsBridgeOut() {
        String testBridge = "0x09aea4b2242abc8bb4bb78d537a67a245a7bec65";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", testBridge);
        rawTransaction.getRawData().put("methodId", "0x7b939232");
        rawTransaction.getRawData().put("functionName", "depositV3((address,address,address,address,uint256,uint256,uint256,address,uint32,uint32,uint32,bytes))");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", testBridge)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, testBridge))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        testBridge,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "TestBridge",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("ROUTER_METHOD_OVERLOAD_UNSUPPORTED");
    }

    @Test
    @DisplayName("across fillV3Relay settlement resolves to BRIDGE_IN through method-aware registry dispatch")
    void acrossFillV3RelaySettlementResolvesToBridgeInThroughMethodAwareRegistryDispatch() {
        String acrossSpokePool = "0x09aea4b2242abc8bb4bb78d537a67a245a7bec64";
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("from", ROUTER);
        rawTransaction.getRawData().put("to", acrossSpokePool);
        rawTransaction.getRawData().put("methodId", "0x2e378115");
        rawTransaction.getRawData().put("functionName", "fillV3Relay(tuple relayData,uint256 repaymentChainId)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", acrossSpokePool)
                        .append("to", WALLET)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, acrossSpokePool))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        acrossSpokePool,
                        Set.of(NetworkId.UNICHAIN),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Across",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("across fillRelay settlement resolves to BRIDGE_IN through method-aware registry dispatch")
    void acrossFillRelaySettlementResolvesToBridgeInThroughMethodAwareRegistryDispatch() {
        String acrossSpokePool = "0xe35e9842fceaca96570b734083f4a58e8f7c5f2a";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("from", ROUTER);
        rawTransaction.getRawData().put("to", acrossSpokePool);
        rawTransaction.getRawData().put("methodId", "0xdeff4b24");
        rawTransaction.getRawData().put("functionName", "fillRelay(tuple relayExecution)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", acrossSpokePool)
                        .append("to", WALLET)
                        .append("value", "2500000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, acrossSpokePool))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        acrossSpokePool,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Across",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("redeemWithFee settlement sourced from bridge sender resolves to BRIDGE_IN and not promo spam")
    void redeemWithFeeSettlementFromBridgeSenderResolvesToBridgeInAndNotPromoSpam() {
        String bridgeContract = "0x875d6d37ec55c8cf220b9e5080717549d8aa8eca";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", bridgeContract);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("methodId", "0xe2de2a03");
        rawTransaction.getRawData().put("functionName", "redeemWithFee(bytes cctpMsg, bytes cctpSigs, bytes encodedVm, tuple bridgeParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", bridgeContract)
                        .append("to", WALLET)
                        .append("value", "897975990")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, bridgeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        bridgeContract,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Across",
                        "V2",
                        false,
                        null
                )));
        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("transfer-shaped redeemWithFee raw does not create duplicate native bridge leg")
    void transferShapedRedeemWithFeeRawDoesNotCreateDuplicateNativeBridgeLeg() {
        String bridgeContract = "0x875d6d37ec55c8cf220b9e5080717549d8aa8eca";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", bridgeContract);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("value", "897975990");
        rawTransaction.getRawData().put("contractAddress", TOKEN_A);
        rawTransaction.getRawData().put("tokenSymbol", "USDC");
        rawTransaction.getRawData().put("tokenName", "USD Coin");
        rawTransaction.getRawData().put("tokenDecimal", "6");
        rawTransaction.getRawData().put("methodId", "0xe2de2a03");
        rawTransaction.getRawData().put("functionName", "redeemWithFee(bytes cctpMsg, bytes cctpSigs, bytes encodedVm, tuple bridgeParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", bridgeContract)
                        .append("to", WALLET)
                        .append("value", "897975990")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta())
                .containsExactly("USDC:897.975990");
    }

    @Test
    @DisplayName("transfer-shaped execute302 raw does not duplicate token quantity as native leg")
    void transferShapedExecute302RawDoesNotDuplicateTokenQuantityAsNativeLeg() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", "0x0000000000000000000000000000000000000000");
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("value", "502040958000000000000");
        rawTransaction.getRawData().put("contractAddress", "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34");
        rawTransaction.getRawData().put("tokenSymbol", "USDE");
        rawTransaction.getRawData().put("tokenName", "Ethena USDe");
        rawTransaction.getRawData().put("tokenDecimal", "18");
        rawTransaction.getRawData().put("methodId", "0xcfc32570");
        rawTransaction.getRawData().put("functionName", "execute302((address,(uint32,bytes32,uint64),bytes32,bytes,bytes,uint256) _executionParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34")
                        .append("tokenSymbol", "USDE")
                        .append("tokenName", "Ethena USDe")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "502040958000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta())
                .containsExactly("USDE:502.040958000000000000");
    }

    @Test
    @DisplayName("claimWithRecipient from known reward distributor becomes reward claim and not promo spam")
    void claimWithRecipientFromKnownRewardDistributorBecomesRewardClaimAndNotPromoSpam() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("from", rewardDistributor);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("methodId", "0x9fb67b58");
        rawTransaction.getRawData().put("functionName", "claimWithRecipient(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs,address[] recipients,bytes[] datas)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ARB")
                        .append("tokenName", "Arbitrum")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "7350151119837232735")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, WALLET)).thenReturn(Optional.empty());
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Arbitrum Rewards",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("explicit claim selector with inbound movement becomes reward claim without registry")
    void explicitClaimSelectorWithInboundMovementBecomesRewardClaimWithoutRegistry() {
        RawTransaction rawTransaction = baseRaw(NetworkId.MANTLE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x5d4df3bf000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "MNT")
                        .append("tokenName", "Mantle")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known Mantle merkle distributor without payout becomes claim without movement")
    void knownMantleMerkleDistributorWithoutPayoutBecomesClaimWithoutMovement() {
        String distributor = "0x0045601c3c4c561012c108ea84a81e36eac24296";
        RawTransaction rawTransaction = baseRaw(NetworkId.MANTLE);
        rawTransaction.getRawData().put("to", distributor);
        rawTransaction.getRawData().put("methodId", "0x5d4df3bf");
        rawTransaction.getRawData().put("functionName", "claim(uint256 id,uint256 index,address account,uint256 amount,bytes32[] merkleProof)");

        when(protocolRegistryService.lookup(NetworkId.MANTLE, distributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        distributor,
                        Set.of(NetworkId.MANTLE),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Merkle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.missingDataReasons()).containsExactly("CLAIM_WITHOUT_MOVEMENT");
    }

    @Test
    @DisplayName("zkSync LiFi bridge execute path resolves to BRIDGE_OUT through method-aware registry dispatch")
    void zksyncLifiBridgeExecutePathResolvesToBridgeOutThroughMethodAwareRegistryDispatch() {
        String lifiDiamond = "0x341e94069f53234fe6dabef707ad424830525715";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("to", lifiDiamond);
        rawTransaction.getRawData().put("methodId", "0xae0b91e5");
        rawTransaction.getRawData().put("functionName", "execute(bytes,bytes[],uint256)");
        rawTransaction.getRawData().put("value", "100000000000000000");
        when(protocolRegistryService.lookup(NetworkId.ZKSYNC, lifiDiamond))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        lifiDiamond,
                        Set.of(NetworkId.ZKSYNC),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "LiFi",
                        "Diamond",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("source-side Mayan bridge-start selector resolves to BRIDGE_OUT before generic swap fallback")
    void sourceSideMayanBridgeStartSelectorResolvesToBridgeOutBeforeGenericSwapFallback() {
        String lifiDiamond = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", lifiDiamond);
        rawTransaction.getRawData().put("methodId", "0x30c48952");
        rawTransaction.getRawData().put("functionName",
                "swapAndStartBridgeTokensViaMayan(tuple _bridgeData,tuple[] _swapData,tuple _mayanData)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", lifiDiamond)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).contains(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER)
                .hasSize(1);
    }

    @Test
    @DisplayName("bridge-start selector drops bridge-pair evidence reason after full receipt clarification evidence exists")
    void bridgeStartSelectorDropsBridgePairEvidenceReasonAfterFullReceiptEvidenceExists() {
        String lifiDiamond = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", lifiDiamond);
        rawTransaction.getRawData().put("methodId", "0x30c48952");
        rawTransaction.getRawData().put("functionName",
                "swapAndStartBridgeTokensViaMayan(tuple _bridgeData,tuple[] _swapData,tuple _mayanData)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", lifiDiamond)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("receipt", new Document("logs", List.of(
                        new Document("address", lifiDiamond)
                                .append("topics", List.of("0xbridge"))
                )))
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", lifiDiamond)
                                .append("value", "1000000")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.missingDataReasons()).doesNotContain(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("outbound-only aggregator router call demotes to EXTERNAL_TRANSFER_OUT instead of SWAP")
    void outboundOnlyAggregatorRouterCallDemotesToExternalTransferOutInsteadOfSwap() {
        String oneInchRouter = "0x111111125421ca6dc452d289314280a0f8842a65";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", oneInchRouter);
        rawTransaction.getRawData().put("methodId", "0x07ed2379");
        rawTransaction.getRawData().put("functionName", "");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", oneInchRouter)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, oneInchRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        oneInchRouter,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.AGGREGATOR,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "1inch",
                        "V6",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).contains(ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code());
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL));
    }

    @Test
    @DisplayName("1inch native-output swap recovers wallet native buy leg from wrapped-native unwrap evidence")
    void oneInchNativeOutputSwapRecoversWalletNativeBuyLegFromWrappedNativeUnwrapEvidence() {
        String oneInchRouter = "0x111111125421ca6dc452d289314280a0f8842a65";
        String executor = "0x4444444444444444444444444444444444444444";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", oneInchRouter);
        rawTransaction.getRawData().put("methodId", "0x07ed2379");
        rawTransaction.getRawData().put("input", oneInchSwapInput(
                executor,
                TOKEN_A,
                "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                oneInchRouter,
                WALLET,
                "1000000",
                "1"
        ));
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", oneInchRouter)
                        .append("value", "1000000"),
                new Document("contractAddress", "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c")
                        .append("tokenSymbol", "WBNB")
                        .append("tokenDecimal", "18")
                        .append("from", "0x5555555555555555555555555555555555555555")
                        .append("to", executor)
                        .append("value", "500000000000000000"),
                new Document("contractAddress", "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c")
                        .append("tokenSymbol", "WBNB")
                        .append("tokenDecimal", "18")
                        .append("from", executor)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "500000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, oneInchRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        oneInchRouter,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.AGGREGATOR,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "1inch",
                        "V6",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.SELL)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("USDC");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-1.0"));
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.BUY)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("BNB");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("0.5"));
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("BNB");
                    assertThat(flow.getQuantityDelta()).isLessThan(BigDecimal.ZERO);
                });
    }

    @Test
    @DisplayName("1inch RPC native-output swap recovers wallet native buy leg from wrapped-native withdrawal log")
    void oneInchRpcNativeOutputSwapRecoversWalletNativeBuyLegFromWrappedNativeWithdrawalLog() {
        String oneInchRouter = "0x111111125421ca6dc452d289314280a0f8842a65";
        String intermediary = "0x8c864d0c8e476bf9eb9d620c10e1296fb0e2f940";
        String wrappedBnb = "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", oneInchRouter);
        rawTransaction.getRawData().put("methodId", "0x07ed2379");
        rawTransaction.getRawData().put("input", oneInchSwapInput(
                intermediary,
                TOKEN_A,
                "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                intermediary,
                WALLET,
                "12000000",
                "1"
        ));
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", intermediary)
                        .append("value", "12000000"),
                new Document("contractAddress", wrappedBnb)
                        .append("tokenSymbol", "WBNB")
                        .append("tokenDecimal", "18")
                        .append("from", "0xc4dc171d499b3f5340bffed8433bddcec8d33b04")
                        .append("to", intermediary)
                        .append("value", "1265420489474075")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("receipt", new Document("logs", List.of(
                        new Document("address", wrappedBnb)
                                .append("topics", List.of(
                                        "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65",
                                        "0x0000000000000000000000008c864d0c8e476bf9eb9d620c10e1296fb0e2f940"
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000000047ee4aac4401b")
                )))
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", intermediary)
                                .append("value", "12000000"),
                        new Document("contractAddress", wrappedBnb)
                                .append("tokenSymbol", "WBNB")
                                .append("tokenDecimal", "18")
                                .append("from", "0xc4dc171d499b3f5340bffed8433bddcec8d33b04")
                                .append("to", intermediary)
                                .append("value", "1265420489474075")
                ))));
        when(protocolRegistryService.lookup(NetworkId.BSC, oneInchRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        oneInchRouter,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.AGGREGATOR,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "1inch",
                        "V6",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.SELL)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("USDC");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-12.0"));
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.BUY)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("BNB");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.001265420489474075");
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("BNB");
                    assertThat(flow.getQuantityDelta()).isLessThan(BigDecimal.ZERO);
                });
        assertThat(result.missingDataReasons()).doesNotContain(ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code());
    }

    @Test
    @DisplayName("malformed swap candidate without buy leg leaves active lane before pricing")
    void malformedSwapCandidateWithoutBuyLegLeavesActiveLaneBeforePricing() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("methodId", "0x38ed1739");
        rawTransaction.getRawData().put("functionName", "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).contains("SWAP_SHAPE_INCOMPLETE", "SWAP_MISSING_BUY_LEG");
    }

    @Test
    @DisplayName("zkSync pancake universal router execute path resolves to SWAP through method-aware registry dispatch")
    void zksyncPancakeUniversalRouterExecutePathResolvesToSwapThroughMethodAwareRegistryDispatch() {
        String pancakeUniversalRouter = "0xdaee41e335322c85ff2c5a6745c98e1351806e98";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("to", pancakeUniversalRouter);
        rawTransaction.getRawData().put("methodId", "0x3593564c");
        rawTransaction.getRawData().put("functionName", "execute(bytes,bytes[],uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", pancakeUniversalRouter)
                        .append("value", "1000000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", pancakeUniversalRouter)
                        .append("to", WALLET)
                        .append("value", "250000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ZKSYNC, pancakeUniversalRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        pancakeUniversalRouter,
                        Set.of(NetworkId.ZKSYNC),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("zkSync router execute path recovers selector from calldata and resolves to SWAP")
    void zksyncRouterExecutePathRecoversSelectorFromCalldataAndResolvesToSwap() {
        String pancakeUniversalRouter = "0xdaee41e335322c85ff2c5a6745c98e1351806e98";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("to", pancakeUniversalRouter);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x3593564c000000000000000000000000");
        rawTransaction.getRawData().put("functionName", "execute(bytes,bytes[],uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", pancakeUniversalRouter)
                        .append("value", "1000000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", pancakeUniversalRouter)
                        .append("to", WALLET)
                        .append("value", "250000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ZKSYNC, pancakeUniversalRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        pancakeUniversalRouter,
                        Set.of(NetworkId.ZKSYNC),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("zkSync router execute path with round-trip token legs still resolves to SWAP by net asset movement")
    void zksyncRouterExecutePathWithRoundTripTokenLegsStillResolvesToSwapByNetAssetMovement() {
        String pancakeUniversalRouter = "0xdaee41e335322c85ff2c5a6745c98e1351806e98";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("to", pancakeUniversalRouter);
        rawTransaction.getRawData().put("methodId", "0x3593564c");
        rawTransaction.getRawData().put("functionName", "execute(bytes,bytes[],uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", pancakeUniversalRouter)
                        .append("value", "1000000"),
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", pancakeUniversalRouter)
                        .append("to", WALLET)
                        .append("value", "250000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", pancakeUniversalRouter)
                        .append("to", WALLET)
                        .append("value", "250000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ZKSYNC, pancakeUniversalRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        pancakeUniversalRouter,
                        Set.of(NetworkId.ZKSYNC),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("position manager safeTransfer into known dex stake contract becomes LP_POSITION_STAKE")
    void positionManagerSafeTransferIntoKnownDexStakeContractBecomesLpPositionStake() {
        String positionManager = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
        String stakeContract = "0x5e09acf80c0296740ec5d6f643005a4ef8daa694";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0x42842e0e");
        rawTransaction.getRawData().put("functionName", "safeTransferFrom(address,address,uint256)");
        rawTransaction.getRawData().put("input", "0x42842e0e"
                + "000000000000000000000000f03b52e8686b962e051a6075a06b96cb8a663021"
                + "0000000000000000000000005e09acf80c0296740ec5d6f643005a4ef8daa694"
                + "000000000000000000000000000000000000000000000000000000000003c864");
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_STAKE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.flows())
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("unichain modifyLiquidities outbound principal becomes LP_ENTRY")
    void unichainModifyLiquiditiesOutboundPrincipalBecomesLpEntry() {
        String positionManager = "0x4529a01c7a0410167c5740c487a8de60232617bf";
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xdd46508f");
        rawTransaction.getRawData().put("functionName", "modifyLiquidities(bytes unlockData,uint256 deadline)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USD₮0")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x1f98400000000000000000000000000000000004")
                        .append("value", "120214180")
        )).append("internalTransfers", List.of(
                new Document("from", positionManager)
                        .append("to", WALLET)
                        .append("value", "2366892066930994")
                        .append("isError", "0")
        )));
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.UNICHAIN),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        null,
                        ConfidenceLevel.HIGH,
                        "Uniswap",
                        "V4",
                        true,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
    }

    @Test
    @DisplayName("unichain modifyLiquidities inbound principal becomes LP_EXIT")
    void unichainModifyLiquiditiesInboundPrincipalBecomesLpExit() {
        String positionManager = "0x4529a01c7a0410167c5740c487a8de60232617bf";
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xdd46508f");
        rawTransaction.getRawData().put("functionName", "modifyLiquidities(bytes unlockData,uint256 deadline)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USD₮0")
                        .append("tokenDecimal", "6")
                        .append("from", "0x1f98400000000000000000000000000000000004")
                        .append("to", WALLET)
                        .append("value", "924079939")
        )).append("internalTransfers", List.of(
                new Document("from", "0x1f98400000000000000000000000000000000004")
                        .append("to", WALLET)
                        .append("value", "688511354278937274")
                        .append("isError", "0")
        )));
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.UNICHAIN),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        null,
                        ConfidenceLevel.HIGH,
                        "Uniswap",
                        "V4",
                        true,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
    }

    @Test
    @DisplayName("raw swap-like legs outrank transfer wording")
    void rawSwapLikeLegsOutrankTransferWording() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0x23b872dd");
        rawTransaction.getRawData().put("functionName", "transferFrom(address,address,uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDT")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "40200000")
        )).append("internalTransfers", List.of(
                new Document("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "14210161516051834")
                        .append("isError", "0")
        )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
    }

    @Test
    @DisplayName("uniswap position-manager multicall with mint path becomes LP_ENTRY")
    void uniswapPositionManagerMulticallWithMintPathBecomesLpEntry() {
        String positionManager = "0xc36442b4a4522e871399cd717abdd847ab11fe88";
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "88316456");
        rawTransaction.getRawData().put("value", "130613750057285395");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", positionManager)
                        .append("value", "400088613")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Uniswap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("position-manager multicall with embedded liquidity increase selector becomes LP_ENTRY")
    void positionManagerMulticallWithEmbeddedLiquidityIncreaseSelectorBecomesLpEntry() {
        String positionManager = "0xc36442b4a4522e871399cd717abdd847ab11fe88";
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "219f5d17");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "15557138")
        )).append("internalTransfers", List.of(
                new Document("from", positionManager)
                        .append("to", WALLET)
                        .append("value", "1289862951905")
                        .append("isError", "0")
        )));
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Uniswap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("position-manager multicall with collect-only path becomes LP_FEE_CLAIM")
    void positionManagerMulticallWithCollectOnlyPathBecomesLpFeeClaim() {
        String positionManager = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "fc6f7865");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", positionManager)
                        .append("to", WALLET)
                        .append("value", "2147331")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("position-manager multicall with unsupported inner call stays explicit review")
    void positionManagerMulticallWithUnsupportedInnerCallStaysExplicitReview() {
        String positionManager = "0xc36442b4a4522e871399cd717abdd847ab11fe88";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "deadbeef");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", positionManager)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Uniswap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("ROUTER_METHOD_OVERLOAD_UNSUPPORTED");
    }

    @Test
    @DisplayName("dex stake contract staked-position liquidity increase becomes LP_ENTRY")
    void dexStakeContractStakedPositionLiquidityIncreaseBecomesLpEntry() {
        String stakeContract = "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x219f5d17");
        rawTransaction.getRawData().put("input", "0x219f5d17000000000000000000000000000000000000000000000000000000000006a68d");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", stakeContract)
                        .append("value", "1942810"),
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", stakeContract)
                        .append("to", WALLET)
                        .append("value", "35617")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
    }

    @Test
    @DisplayName("trusted Slipstream position manager recovered increaseLiquidity selector becomes LP_ENTRY")
    void trustedSlipstreamPositionManagerRecoveredIncreaseLiquiditySelectorBecomesLpEntry() {
        String positionManager = "0x416b433906b1b72fa758e166e239c43d68dc6f29";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x219f5d17000000000000000000000000000000000000000000000000000000000006a68d");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "196378"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "USDT0")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "171330")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("trusted Slipstream position manager recovered approve selector remains APPROVE")
    void trustedSlipstreamPositionManagerRecoveredApproveSelectorRemainsApprove() {
        String positionManager = "0x416b433906b1b72fa758e166e239c43d68dc6f29";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("functionName", "");
        rawTransaction.getRawData().put("input", "0x095ea7b3"
                + "000000000000000000000000bc6043a5e50ba0c0213d2f7430a73e4590af97ad"
                + "0000000000000000000000000000000000000000000000000000000000238891");
        rawTransaction.getRawData().put("value", "0");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.APPROVE);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("dex stake contract withdraw without asset movement becomes LP_POSITION_UNSTAKE")
    void dexStakeContractWithdrawWithoutAssetMovementBecomesLpPositionUnstake() {
        String stakeContract = "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x00f714ce");
        rawTransaction.getRawData().put("input", "0x00f714ce"
                + "000000000000000000000000000000000000000000000000000000000006a68d"
                + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        when(protocolRegistryService.lookup(NetworkId.BASE, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.flows())
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("known router multicall path resolves to SWAP through method-aware registry dispatch")
    void knownRouterMulticallPathResolvesToSwapThroughMethodAwareRegistryDispatch() {
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "1000000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "OP")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "250000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, ROUTER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        ROUTER,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "Router",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("unsupported router overload becomes explicit needs review")
    void unsupportedRouterOverloadBecomesExplicitNeedsReview() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.AVALANCHE, ROUTER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        ROUTER,
                        Set.of(NetworkId.AVALANCHE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "Router",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.missingDataReasons()).containsExactly("ROUTER_METHOD_OVERLOAD_UNSUPPORTED");
    }

    @Test
    @DisplayName("blank methodId recovered from calldata reaches router review generation")
    void blankMethodIdRecoveredFromCalldataReachesRouterReviewGeneration() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xc16ae7a4" + "00".repeat(64));
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.AVALANCHE, ROUTER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        ROUTER,
                        Set.of(NetworkId.AVALANCHE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.ROUTER,
                        ProtocolRegistryEventType.SWAP,
                        ConfidenceLevel.HIGH,
                        "Router",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("ROUTER_METHOD_OVERLOAD_UNSUPPORTED");
    }

    @Test
    @DisplayName("known admin selector preempts protocol handler and keeps only fee flow")
    void knownAdminSelectorPreemptsProtocolHandlerAndKeepsOnlyFeeFlow() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", PROTOCOL);
        rawTransaction.getRawData().put("methodId", "0xfa6e671d");
        rawTransaction.getRawData().put("functionName", "setRelayerApproval(address sender, address relayer, bool approved)");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.flows())
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE);
                    assertThat(flow.getAssetSymbol()).isEqualTo("AVAX");
                });
    }

    @Test
    @DisplayName("contract-scoped admin selector becomes ADMIN_CONFIG")
    void contractScopedAdminSelectorBecomesAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", "0x426fa03fb86e510d0dd9f70335cf102a98b10875");
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xE32954EB000000000000000000000000");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
    }

    @Test
    @DisplayName("unknown zero-flow contract call no longer auto-collapses into approve")
    void unknownZeroFlowContractCallNoLongerAutoCollapsesIntoApprove() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("methodId", "0xdeadbeef");
        rawTransaction.getRawData().put("input", "0xdeadbeef000000000000000000000000");
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, ROUTER))
                .thenReturn(Optional.empty());

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).contains("CLASSIFICATION_FAILED");
    }

    @Test
    @DisplayName("known zero-amount family becomes admin config with fee-only flow")
    void knownZeroAmountFamilyBecomesAdminConfigWithFeeOnlyFlow() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", "0x2ea84921448af2a15d4bc442fd7fb09dfdbbac6d");
        rawTransaction.getRawData().put("methodId", "0x0cf79e0a");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).isEmpty();
        assertThat(result.flows())
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("known Base zero-amount transfer family becomes admin config")
    void knownBaseZeroAmountTransferFamilyBecomesAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", "0xd743caa0ad523bbeba05c29b666d66e05f18094d");
        rawTransaction.getRawData().put("methodId", "0x12514bba");
        rawTransaction.getRawData().put("functionName", "transfer(uint256 amount)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("known Avalanche zero-amount ERC20 transfer family becomes admin config")
    void knownAvalancheZeroAmountErc20TransferFamilyBecomesAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", "0xd743caa0ad523bbeba05c29b666d66e05f18094d");
        rawTransaction.getRawData().put("methodId", "0xa9059cbb");
        rawTransaction.getRawData().put("functionName", "transfer(address,uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDt")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("known Ethereum zero-amount ERC20 transfer family becomes admin config")
    void knownEthereumZeroAmountErc20TransferFamilyBecomesAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", "0xc6fd8084fb9b6a0768cf943c341049edd1085b82");
        rawTransaction.getRawData().put("methodId", "0xa9059cbb");
        rawTransaction.getRawData().put("functionName", "transfer(address,uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("recovered selector on known Arbitrum zero-amount family becomes admin config")
    void recoveredSelectorOnKnownArbitrumZeroAmountFamilyBecomesAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", "0xc50005eb632e52e2d86096e5dae7b633609b348c");
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xe94a5b23000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("known Unichain zero-amount batch family becomes admin config")
    void knownUnichainZeroAmountBatchFamilyBecomesAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("to", "0x3333333333333333333333333333333333333333");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("unknown zero-amount token transfer becomes explicit review without buy sell flows")
    void unknownZeroAmountTokenTransferBecomesExplicitReviewWithoutBuySellFlows() {
        RawTransaction rawTransaction = baseRaw(NetworkId.POLYGON);
        rawTransaction.getRawData().put("to", "0xd743caa0ad523bbeba05c29b666d66e05f18094d");
        rawTransaction.getRawData().put("methodId", "0xdeadbeef");
        rawTransaction.getRawData().put("functionName", "mysteryTransfer(uint256)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDt")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "0")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("ZERO_AMOUNT_TOKEN_TRANSFER");
        assertThat(result.flows())
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("failed transaction by txreceipt_status bypasses downstream classification")
    void failedTransactionByReceiptStatusBypassesDownstreamClassification() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("txreceipt_status", "0");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("FAILED_TRANSACTION");
    }

    @Test
    @DisplayName("failed transaction by isError bypasses downstream classification")
    void failedTransactionByIsErrorBypassesDownstreamClassification() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("isError", "1");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("FAILED_TRANSACTION");
    }

    @Test
    @DisplayName("Slipstream position manager mint selector becomes LP_ENTRY")
    void slipstreamPositionManagerMintSelectorBecomesLpEntry() {
        String positionManager = "0x416b433906b1b72fa758e166e239c43d68dc6f29";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xb5007d1f000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x01bff41798a0bcf287b996046ca68b395dbc1071")
                        .append("tokenSymbol", "USDT0")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "209965545"),
                new Document("contractAddress", "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58")
                        .append("tokenSymbol", "USDT")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "409005200")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("Slipstream multicall with decrease and collect becomes LP_EXIT")
    void slipstreamMulticallWithDecreaseAndCollectBecomesLpExit() {
        String positionManager = "0x416b433906b1b72fa758e166e239c43d68dc6f29";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "0c49ccbe" + "00".repeat(64) + "fc6f7865");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x01bff41798a0bcf287b996046ca68b395dbc1071")
                        .append("tokenSymbol", "USDT0")
                        .append("tokenDecimal", "6")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "226952468"),
                new Document("contractAddress", "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58")
                        .append("tokenSymbol", "USDT")
                        .append("tokenDecimal", "6")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "392018375")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ProtocolRegistryEventType.LP_MINT,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
    }

    @Test
    @DisplayName("Base Pancake V3 MasterChef multicall with collect and NFT return becomes LP_EXIT")
    void basePancakeV3MasterChefMulticallWithCollectAndNftReturnBecomesLpExit() {
        String stakeContract = "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3";
        String positionManager = "0x46a15b0b27311cedf172ab29e4f4766fbE7F4364";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "fc6f7865");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", stakeContract)
                        .append("to", WALLET)
                        .append("value", "1110000")
        )).append("internalTransfers", List.of()));
        rawTransaction.getRawData().put("logs", List.of(
                new Document("address", positionManager)
                        .append("topics", List.of(
                                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                "0x000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3",
                                "0x0000000000000000000000001111111111111111111111111111111111111111",
                                "0x0000000000000000000000000000000000000000000000000000000000000001"
                        ))
                        .append("data", "0x")
        ));
        when(protocolRegistryService.lookup(NetworkId.BASE, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("Arbitrum Pancake V3 MasterChef multicall with collect-only flow becomes LP_FEE_CLAIM")
    void arbitrumPancakeV3MasterChefMulticallWithCollectOnlyFlowBecomesLpFeeClaim() {
        String stakeContract = "0x5e09acf80c0296740ec5d6f643005a4ef8daa694";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(64) + "fc6f7865");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", stakeContract)
                        .append("to", WALLET)
                        .append("value", "2220000")
        )).append("internalTransfers", List.of(
                new Document("from", stakeContract)
                        .append("to", WALLET)
                        .append("value", "500000000000000")
                        .append("isError", "0")
        )));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY));
    }

    @Test
    @DisplayName("pure dex stake contract deposit becomes LP_POSITION_STAKE")
    void pureDexStakeContractDepositBecomesLpPositionStake() {
        String stakeContract = "0x0f5212f63ba8eab0fabd94fc2071d461d9d6ddb2";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xb6b55f25000000000000000000000000");
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_STAKE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.flows())
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("pure dex stake contract withdraw becomes LP_POSITION_UNSTAKE")
    void pureDexStakeContractWithdrawBecomesLpPositionUnstake() {
        String stakeContract = "0x0f5212f63ba8eab0fabd94fc2071d461d9d6ddb2";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x2e1a7d4d000000000000000000000000");
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.flows())
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    @Test
    @DisplayName("trusted Slipstream stake contract deposit with incidental inbound remains LP_POSITION_STAKE")
    void trustedSlipstreamStakeContractDepositWithIncidentalInboundRemainsLpPositionStake() {
        String stakeContract = "0xc762d18800b3f78ae56e9e61ad7be98a413d59de";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0xb6b55f25000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "51")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_STAKE);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("trusted Slipstream stake contract withdraw with incidental reward remains LP_POSITION_UNSTAKE")
    void trustedSlipstreamStakeContractWithdrawWithIncidentalRewardRemainsLpPositionUnstake() {
        String stakeContract = "0xc762d18800b3f78ae56e9e61ad7be98a413d59de";
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.getRawData().put("to", stakeContract);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x2e1a7d4d000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "VELO")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "34538768251211583")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.OPTIMISM, stakeContract))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        stakeContract,
                        Set.of(NetworkId.OPTIMISM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "Velodrome",
                        "Slipstream",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("method-aware bridge entry selector on known bridge contract becomes BRIDGE_OUT")
    void methodAwareBridgeEntrySelectorOnKnownBridgeContractBecomesBridgeOut() {
        String bridgeEntry = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", bridgeEntry);
        rawTransaction.getRawData().put("methodId", "0x30c48952");
        rawTransaction.getRawData().put("input", "0x30c48952000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDT")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", bridgeEntry)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, bridgeEntry))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        bridgeEntry,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        null,
                        ConfidenceLevel.HIGH,
                        "LiFi",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known BSC merkle distributor becomes reward claim without function name")
    void knownBscMerkleDistributorBecomesRewardClaimWithoutFunctionName() {
        String distributor = "0xea64df3a17b5172bfaf0e4215660cdec22ee7d57";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", distributor);
        rawTransaction.getRawData().put("methodId", "0x2f52ebb7");
        rawTransaction.getRawData().put("input", "0x2f52ebb7000000000000000000000000");
        rawTransaction.getRawData().remove("functionName");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "CAKE")
                        .append("tokenDecimal", "18")
                        .append("from", distributor)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, distributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        distributor,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Rewards",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known SmartChef deposit remains staking deposit even with reward side effect")
    void knownSmartChefDepositRemainsStakingDepositEvenWithRewardSideEffect() {
        String smartChef = "0x7816f1711828c52eb3ca5a2f075a0c06e0548bd6";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", smartChef);
        rawTransaction.getRawData().put("methodId", "0xb6b55f25");
        rawTransaction.getRawData().put(
                "input",
                "0xb6b55f250000000000000000000000000000000000000000000000000de0b6b3a7640000"
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "CAKE")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", smartChef)
                        .append("value", "1000000000000000000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "AITECH")
                        .append("tokenDecimal", "18")
                        .append("from", smartChef)
                        .append("to", WALLET)
                        .append("value", "500000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, smartChef))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        smartChef,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.STAKING,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        ProtocolRegistryEventType.STAKING_DEPOSIT,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.flows())
                .filteredOn(flow -> "CAKE".equals(flow.getAssetSymbol()))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-1");
                });
        assertThat(result.flows())
                .filteredOn(flow -> "AITECH".equals(flow.getAssetSymbol()))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY);
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.5");
                });
    }

    @Test
    @DisplayName("liquid staking submit keeps principal and derivative as continuity transfers")
    void liquidStakingSubmitKeepsContinuityTransferSemantics() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", COUNTERPARTY);
        rawTransaction.getRawData().put("functionName", "submit()");
        rawTransaction.getRawData().put("value", "2000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "sAVAX")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "2000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole().name())
                .containsExactlyInAnyOrder("AVAX:TRANSFER", "sAVAX:TRANSFER");
    }

    @Test
    @DisplayName("known BSC CL position manager modifyLiquidities becomes LP_ENTRY")
    void knownBscClPositionManagerModifyLiquiditiesBecomesLpEntry() {
        String positionManager = "0x55f4c8aba71a1e923edc303eb4feff14608cc226";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xdd46508f");
        rawTransaction.getRawData().put("input", "0xdd46508f000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDT")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "Infinity",
                        true,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known reward claim contract with inbound movement becomes REWARD_CLAIM")
    void knownRewardClaimContractWithInboundMovementBecomesRewardClaim() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", rewardDistributor);
        rawTransaction.getRawData().put("methodId", "0x71ee95c0");
        rawTransaction.getRawData().put("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ARB")
                        .append("tokenName", "Arbitrum")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "2720809576252872031")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Angle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known Merkl distributor on Plasma becomes reward claim")
    void knownMerklDistributorOnPlasmaBecomesRewardClaim() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        RawTransaction rawTransaction = baseRaw(NetworkId.PLASMA);
        rawTransaction.getRawData().put("to", rewardDistributor);
        rawTransaction.getRawData().put("methodId", "0x71ee95c0");
        rawTransaction.getRawData().put("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "MERKL")
                        .append("tokenName", "Merkl Reward")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "1200000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.PLASMA, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.PLASMA),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Angle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known Merkl distributor on Unichain becomes reward claim")
    void knownMerklDistributorOnUnichainBecomesRewardClaim() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("to", rewardDistributor);
        rawTransaction.getRawData().put("methodId", "0x71ee95c0");
        rawTransaction.getRawData().put("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ANGLE")
                        .append("tokenName", "Angle")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "900000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.UNICHAIN),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Angle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("known claim contract without movement becomes explicit review")
    void knownClaimContractWithoutMovementBecomesExplicitReview() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        RawTransaction rawTransaction = baseRaw(NetworkId.KATANA);
        rawTransaction.getRawData().put("to", rewardDistributor);
        rawTransaction.getRawData().put("methodId", "0x71ee95c0");
        rawTransaction.getRawData().put("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)");

        when(protocolRegistryService.lookup(NetworkId.KATANA, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.KATANA),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Angle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.missingDataReasons()).containsExactly("CLAIM_WITHOUT_MOVEMENT");
    }

    @Test
    @DisplayName("same claim tx can be reward claim for receiver and claim without movement for signer")
    void sameClaimTxCanBeRewardClaimForReceiverAndClaimWithoutMovementForSigner() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        String secondTrackedWallet = "0xf03b52e8686b962e051a6075a06b96cb8a663021";

        RawTransaction receivingWallet = baseRaw(NetworkId.ARBITRUM);
        receivingWallet.setId("0xf13356fe:ARBITRUM:" + WALLET);
        receivingWallet.setTxHash("0xf13356fe");
        receivingWallet.getRawData().put("to", rewardDistributor);
        receivingWallet.getRawData().put("methodId", "0x9fb67b58");
        receivingWallet.getRawData().put("functionName", "claimWithRecipient(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs,address[] recipients,bytes[] datas)");
        receivingWallet.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ARB")
                        .append("tokenName", "Arbitrum")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "7350151119837232735")
        )).append("internalTransfers", List.of()));

        RawTransaction signingWallet = baseRaw(NetworkId.ARBITRUM);
        signingWallet.setId("0xf13356fe:ARBITRUM:" + secondTrackedWallet);
        signingWallet.setTxHash("0xf13356fe");
        signingWallet.setWalletAddress(secondTrackedWallet);
        signingWallet.getRawData().put("from", secondTrackedWallet);
        signingWallet.getRawData().put("to", rewardDistributor);
        signingWallet.getRawData().put("methodId", "0x9fb67b58");
        signingWallet.getRawData().put("functionName", "claimWithRecipient(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs,address[] recipients,bytes[] datas)");
        signingWallet.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "ARB")
                        .append("tokenName", "Arbitrum")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "7350151119837232735")
        )).append("internalTransfers", List.of()));

        ProtocolRegistryEntry entry = new ProtocolRegistryEntry(
                rewardDistributor,
                Set.of(NetworkId.ARBITRUM),
                ProtocolRegistryFamily.YIELD,
                ProtocolRegistryRole.REWARD_ROUTER,
                ProtocolRegistryEventType.REWARD_CLAIM,
                ConfidenceLevel.HIGH,
                "Angle",
                "V1",
                false,
                null
        );
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, rewardDistributor))
                .thenReturn(Optional.of(entry));

        OnChainClassificationResult receiverResult = classifier.classify(receivingWallet);
        OnChainClassificationResult signerResult = classifier.classify(signingWallet);

        assertThat(receiverResult.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(signerResult.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(signerResult.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(signerResult.missingDataReasons()).containsExactly("CLAIM_WITHOUT_MOVEMENT");
    }

    @Test
    @DisplayName("clarification full receipt batch with share inbound and principal outbound becomes lending deposit")
    void clarificationFullReceiptBatchWithShareInboundAndPrincipalOutboundBecomesLendingDeposit() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("receipt", new Document("logs", List.of(
                        new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                                .append("topics", List.of("0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec"))
                )))
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", ROUTER)
                                .append("value", "1000000"),
                        new Document("contractAddress", TOKEN_B)
                                .append("tokenSymbol", "eUSDC")
                                .append("tokenName", "Euler USDC")
                                .append("tokenDecimal", "6")
                                .append("from", ROUTER)
                                .append("to", WALLET)
                                .append("value", "995000")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("Euler batch subaccount movements are attributed back to the tracked wallet")
    void eulerBatchSubaccountMovementsAreAttributedBackToTrackedWallet() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0xevc-subaccount");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", ROUTER)
                                .append("value", "1000000"),
                        new Document("contractAddress", TOKEN_B)
                                .append("tokenSymbol", "eUSDC")
                                .append("tokenName", "Euler USDC")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", "0x1111111111111111111111111111111111111110")
                                .append("value", "995000")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("Arbitrum Euler eUSDC-6 deposit stays explicit review until clarification proves lifecycle")
    void arbitrumEulerSimpleVaultDepositWithoutClarificationStaysExplicitReview() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x8e940d70131f8a52fd6bc1d84cec901f44b2981b065680ae285cc00d4c29d124");
        rawTransaction.getRawData().put("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("value", "2243571465"),
                new Document("contractAddress", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("tokenSymbol", "eUSDC-6")
                        .append("tokenName", "EVK Vault eUSDC-6")
                        .append("tokenDecimal", "6")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "2212415353")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.missingDataReasons()).contains("EULER_BATCH_DECODER_REQUIRED");
    }

    @Test
    @DisplayName("Arbitrum Euler eUSDC-6 deposit resolves to lending deposit when clarification proves lifecycle")
    void arbitrumEulerSimpleVaultDepositResolvesToLendingDeposit() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x8e940d70131f8a52fd6bc1d84cec901f44b2981b065680ae285cc00d4c29d124");
        rawTransaction.getRawData().put("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("value", "2243571465"),
                new Document("contractAddress", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("tokenSymbol", "eUSDC-6")
                        .append("tokenName", "EVK Vault eUSDC-6")
                        .append("tokenDecimal", "6")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "2212415353")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        "USDC:-2243.571465",
                        "eUSDC-6:2212.415353"
                );
    }

    @Test
    @DisplayName("Arbitrum Euler eUSDC-6 partial withdraw stays explicit review until clarification proves lifecycle")
    void arbitrumEulerSimpleVaultPartialWithdrawWithoutClarificationStaysExplicitReview() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x9aad9182c92e4eb4cfb9e560c5695f8d6dc650b3e95cd2ab351fed4cfbf3ed8d");
        rawTransaction.getRawData().put("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("tokenSymbol", "eUSDC-6")
                        .append("tokenName", "EVK Vault eUSDC-6")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "1479515661"),
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("to", WALLET)
                        .append("value", "1501000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.missingDataReasons()).contains("EULER_BATCH_DECODER_REQUIRED");
    }

    @Test
    @DisplayName("Arbitrum Euler eUSDC-6 partial withdraw resolves to lending withdraw when clarification proves lifecycle")
    void arbitrumEulerSimpleVaultPartialWithdrawResolvesToLendingWithdraw() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x9aad9182c92e4eb4cfb9e560c5695f8d6dc650b3e95cd2ab351fed4cfbf3ed8d");
        rawTransaction.getRawData().put("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("tokenSymbol", "eUSDC-6")
                        .append("tokenName", "EVK Vault eUSDC-6")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "1479515661"),
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("to", WALLET)
                        .append("value", "1501000000")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        "eUSDC-6:-1479.515661",
                        "USDC:1501"
                );
    }

    @Test
    @DisplayName("Arbitrum Euler eUSDC-6 final withdraw stays explicit review until clarification proves lifecycle")
    void arbitrumEulerSimpleVaultFinalWithdrawWithoutClarificationStaysExplicitReview() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x248f9dd324adbd9d60172a002d217d712fd6cee501dac05ee3a2460f83eb4bbd");
        rawTransaction.getRawData().put("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("tokenSymbol", "eUSDC-6")
                        .append("tokenName", "EVK Vault eUSDC-6")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "732899692"),
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("to", WALLET)
                        .append("value", "746016993")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.missingDataReasons()).contains("EULER_BATCH_DECODER_REQUIRED");
    }

    @Test
    @DisplayName("Arbitrum Euler eUSDC-6 final withdraw resolves to lending withdraw when clarification proves lifecycle")
    void arbitrumEulerSimpleVaultFinalWithdrawResolvesToLendingWithdraw() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x248f9dd324adbd9d60172a002d217d712fd6cee501dac05ee3a2460f83eb4bbd");
        rawTransaction.getRawData().put("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("tokenSymbol", "eUSDC-6")
                        .append("tokenName", "EVK Vault eUSDC-6")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "732899692"),
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("from", "0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc")
                        .append("to", WALLET)
                        .append("value", "746016993")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        "eUSDC-6:-732.899692",
                        "USDC:746.016993"
                );
    }

    @Test
    @DisplayName("Euler batch with debt mint and collateral share mint stays explicit review when clarification still cannot prove lifecycle")
    void eulerBatchWithDebtMintAndCollateralShareMintStaysExplicitReviewAfterClarification() {
        String subaccount = "0x1111111111111111111111111111111111111110";
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 2)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x1d45674ec811f8a33c97616790bc5a81d4c9afac")
                                .append("tokenSymbol", "eUSDt-2-DEBT")
                                .append("tokenName", "Debt token of EVK Vault eUSDt-2")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", subaccount)
                                .append("value", "1823548898"),
                        new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("tokenSymbol", "eUSDC-2")
                                .append("tokenName", "EVK Vault eUSDC-2")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", subaccount)
                                .append("value", "1774411539")
                )))
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                                .append("topics", List.of(
                                        "0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111",
                                        "0x1111111111111111111111111111111111111100000000000000000000000000",
                                        "0x000000000000000000000000aba9d2d4b6b93c3dc8976d8eb0690cca56431fe4"
                                ))
                                .append("data", "0x00000000000000000000000011111111111111111111111111111111111111104b3fd14800000000000000000000000000000000000000000000000000000000"),
                        new Document("address", "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4")
                                .append("topics", List.of(
                                        "0xcbc04eca7e9da35cb1393a6135a199ca52e450d5e9251cbd99f7847d33a36750"
                                ))
                                .append("data", "0x01")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).contains("EULER_BATCH_DECODER_REQUIRED");
    }

    @Test
    @DisplayName("Euler batch with clarified collateral-open lifecycle resolves to lending loop open")
    void eulerBatchWithClarifiedCollateralOpenLifecycleResolvesToLendingLoopOpen() {
        String subaccount = "0x1111111111111111111111111111111111111110";
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 2)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x1d45674ec811f8a33c97616790bc5a81d4c9afac")
                                .append("tokenSymbol", "eUSDt-2-DEBT")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", subaccount)
                                .append("value", "1823548898"),
                        new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("tokenSymbol", "eUSDC-2")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", subaccount)
                                .append("value", "1774411539"),
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDT")
                                .append("tokenDecimal", "6")
                                .append("from", "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4")
                                .append("to", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("value", "1774411539")
                )))
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                                .append("topics", List.of(
                                        "0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111",
                                        "0x1111111111111111111111111111111111111100000000000000000000000000",
                                        "0x000000000000000000000000aba9d2d4b6b93c3dc8976d8eb0690cca56431fe4"
                                ))
                                .append("data", "0x00000000000000000000000011111111111111111111111111111111111111104b3fd14800000000000000000000000000000000000000000000000000000000"),
                        new Document("address", "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4")
                                .append("topics", List.of(
                                        "0xcbc04eca7e9da35cb1393a6135a199ca52e450d5e9251cbd99f7847d33a36750"
                                ))
                                .append("data", "0x01")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_OPEN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().endsWith("DEBT"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("eUSDC-2"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY);
                    assertThat(flow.getPriceSource()).isEqualTo(com.walletradar.domain.common.PriceSource.SWAP_DERIVED);
                    assertThat(flow.getUnitPriceUsd()).isEqualByComparingTo(
                            new BigDecimal("1774411539").movePointLeft(6)
                                    .divide(new BigDecimal("1774411539").movePointLeft(6), MathContext.DECIMAL128)
                    );
                });
    }

    @Test
    @DisplayName("Euler batch share migration resolves to lending loop rebalance when clarification proves lifecycle")
    void eulerBatchShareMigrationResolvesToLendingLoopRebalance() {
        String subaccount = "0x1111111111111111111111111111111111111110";
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0xa548b35769c68377b33172370d1a414facd1be4f3c8106d21fcc3940e38ee7a5");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0xa45189636c04388adbb4d865100dd155e55682ec")
                        .append("tokenSymbol", "edeUSD-1")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "2011887556269756470"),
                new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                        .append("tokenSymbol", "eUSDC-2")
                        .append("tokenDecimal", "6")
                        .append("from", subaccount)
                        .append("to", WALLET)
                        .append("value", "1444591868")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("eUSDC-2"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-1444.591868");
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("edeUSD-1"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("2.011887556269756470");
                });
    }

    @Test
    @DisplayName("Euler batch share burn plus replacement mint resolves to lending loop rebalance with clarification")
    void eulerBatchShareBurnMintWithDustResolvesToLendingLoopRebalance() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x56ef233104fabcf809fbad26d5956f0450398cfd90a583fadfe6c7613a7bd332");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0xa45189636c04388adbb4d865100dd155e55682ec")
                        .append("tokenSymbol", "edeUSD-1")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "2011887556269756470"),
                new Document("contractAddress", "0xa45189636c04388adbb4d865100dd155e55682ec")
                        .append("tokenSymbol", "edeUSD-1")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "456988285152"),
                new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                        .append("tokenSymbol", "eUSDC-2")
                        .append("tokenDecimal", "6")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "1983988")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("edeUSD-1"))
                .extracting(NormalizedTransaction.Flow::getQuantityDelta)
                .containsExactlyInAnyOrder(
                        new BigDecimal("-2.011887556269756470"),
                        new BigDecimal("0.000000456988285152")
                );
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("eUSDC-2"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo("1.983988"));
    }

    @Test
    @DisplayName("Euler batch partial unwind resolves to lending loop decrease when clarification proves lifecycle")
    void eulerBatchPartialUnwindResolvesToLendingLoopDecrease() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x46177d31314a31e6934fdaca01c8d24d50a5e260de4b66fd1dda74e990d3d69d");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                        .append("tokenSymbol", "eUSDC-2")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x1111111111111111111111111111111111111110")
                        .append("value", "1444356263"),
                new Document("contractAddress", "0xb57b25851fe2311cc3fe511c8f10e868932e0680")
                        .append("tokenSymbol", "deUSD")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "1039254268973979242470")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_DECREASE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("eUSDC-2"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL);
                    assertThat(flow.getUnitPriceUsd()).isEqualByComparingTo(
                            new BigDecimal("1039254268973979242470").movePointLeft(18)
                                    .divide(new BigDecimal("1444356263").movePointLeft(6), MathContext.DECIMAL128)
                    );
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("deUSD"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY);
                    assertThat(flow.getUnitPriceUsd()).isEqualByComparingTo(BigDecimal.ONE);
                    assertThat(flow.getPriceSource()).isEqualTo(com.walletradar.domain.common.PriceSource.STABLECOIN);
                });
    }

    @Test
    @DisplayName("Euler batch share burn with USDC payout resolves to lending loop close when clarification proves lifecycle")
    void eulerBatchShareBurnWithUsdPayoutResolvesToLendingLoopClose() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0xf9db2e5ecd31eb22ed030b64e63c68f4f940d5f6f7a828ced74e0e9d0fd3ba5a");
        rawTransaction.getRawData().put("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                        .append("tokenSymbol", "eUSDC-2")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "2051603084"),
                new Document("contractAddress", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                        .append("to", WALLET)
                        .append("value", "2121363989")
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", tokenTransfers).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of()))
                .append("transfers", new Document("tokenTransfers", tokenTransfers)));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_CLOSE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.protocolName()).isEqualTo("Euler");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("eUSDC-2"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL);
                    assertThat(flow.getUnitPriceUsd()).isEqualByComparingTo(
                            new BigDecimal("2121363989").movePointLeft(6)
                                    .divide(new BigDecimal("2051603084").movePointLeft(6), MathContext.DECIMAL128)
                    );
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("USDC"))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY);
                    assertThat(flow.getUnitPriceUsd()).isEqualByComparingTo(BigDecimal.ONE);
                });
    }

    @Test
    @DisplayName("clarification full receipt burn-only cleanup family narrows to admin config")
    void clarificationFullReceiptBurnOnlyCleanupFamilyNarrowsToAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.setTxHash("0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("input", "0xac9650d80000000000000000000000000000000000000000000000000000000042966c68");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("receipt", new Document("logs", List.of(
                new Document("address", "0xc762d18800b3f78ae56e9e61ad7be98a413d59de")
                        .append("topics", List.of(
                                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                "0x0000000000000000000000001111111111111111111111111111111111111111",
                                "0x0000000000000000000000000000000000000000000000000000000000000000"
                        ))
                        .append("data", "0x01")
        ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("settlement selector with inbound-only movement becomes bridge in even without registry match")
    void settlementSelectorWithInboundOnlyMovementBecomesBridgeInEvenWithoutRegistryMatch() {
        RawTransaction rawTransaction = baseRaw(NetworkId.UNICHAIN);
        rawTransaction.getRawData().put("from", "0xbe75079fd259a82054caab2ce007cd0c20b177a8");
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("methodId", "0xdeff4b24");
        rawTransaction.getRawData().put("functionName", "fillRelay(tuple relayData,uint256 repaymentChainId,bytes32 repaymentAddress)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0xbe75079fd259a82054caab2ce007cd0c20b177a8")
                        .append("to", WALLET)
                        .append("value", "999418")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("aave withdraw selector on lending pool overrides registry deposit default")
    void aaveWithdrawSelectorOnLendingPoolOverridesRegistryDepositDefault() {
        String aavePool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", aavePool);
        rawTransaction.getRawData().put("methodId", "0x69328dec");
        rawTransaction.getRawData().put("functionName", "withdraw(address erc20, uint256 amount, address receiver)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xf611aeb5013fd2c0511c9cd55c7dc5c1140741a6")
                        .append("tokenSymbol", "aAvaGHO")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "529674296781858998679"),
                new Document("contractAddress", "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73")
                        .append("tokenSymbol", "GHO")
                        .append("tokenDecimal", "18")
                        .append("from", "0xf611aeb5013fd2c0511c9cd55c7dc5c1140741a6")
                        .append("to", WALLET)
                        .append("value", "530000000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.AVALANCHE, aavePool))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        aavePool,
                        Set.of(NetworkId.AVALANCHE),
                        ProtocolRegistryFamily.LENDING,
                        ProtocolRegistryRole.POOL,
                        ProtocolRegistryEventType.LENDING_DEPOSIT,
                        ConfidenceLevel.HIGH,
                        "Aave",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("BSC CL position-manager multicall with embedded modify and NFT mint becomes LP_ENTRY")
    void bscClPositionManagerMulticallWithEmbeddedModifyAndNftMintBecomesLpEntry() {
        String positionManager = "0x55f4c8aba71a1e923edc303eb4feff14608cc226";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8" + "00".repeat(128) + "dd46508f");
        rawTransaction.getRawData().put("logs", List.of(
                new Document("address", positionManager)
                        .append("topics", List.of(
                                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                "0x0000000000000000000000000000000000000000000000000000000000000000",
                                "0x0000000000000000000000001111111111111111111111111111111111111111",
                                "0x000000000000000000000000000000000000000000000000000000000009d352"
                        ))
                        .append("data", "0x")
        ));
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x9e9035aafecb30cfd5355a10f93a270e33bc4293")
                        .append("tokenSymbol", "XYZ")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "73999999999999999999981")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "Infinity",
                        true,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("BSC modifyLiquidities with positive log deltas becomes LP_ENTRY")
    void bscModifyLiquiditiesWithPositiveLogDeltasBecomesLpEntry() {
        String positionManager = "0x55f4c8aba71a1e923edc303eb4feff14608cc226";
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.getRawData().put("to", positionManager);
        rawTransaction.getRawData().put("methodId", "0xdd46508f");
        rawTransaction.getRawData().put("functionName", "modifyLiquidities(bytes payload,uint256 deadline)");
        rawTransaction.getRawData().put("logs", List.of(
                new Document("address", "0xa0FfB9c1CE1Fe56963B0321B32E7A0302114058b")
                        .append("topics", List.of(
                                "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec",
                                "0x98e787ffddc9c6cac3b591ee67e9a6481609d76ee1717fe2d67cbc13b11691e4",
                                "0x00000000000000000000000055f4c8aba71a1e923edc303eb4feff14608cc226"
                        ))
                        .append("data", "0x"
                                + "000000000000000000000000000000000000000000000000000000000000cef8"
                                + "00000000000000000000000000000000000000000000000000000000000144f2"
                                + "0000000000000000000000000000000000000000000000000000000000000000"
                                + "000000000000000000000000000000000000000000000000000000000009d352")
        ));
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BSC, positionManager))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        positionManager,
                        Set.of(NetworkId.BSC),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "Infinity",
                        true,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).contains("INSUFFICIENT_MOVEMENT_EVIDENCE");
    }

    @Test
    @DisplayName("BSC zero-effect modifyLiquidities becomes explicit terminal stop condition")
    void bscZeroEffectModifyLiquiditiesBecomesExplicitTerminalStopCondition() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BSC);
        rawTransaction.setTxHash("0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a");
        rawTransaction.getRawData().put("to", "0x55f4c8aba71a1e923edc303eb4feff14608cc226");
        rawTransaction.getRawData().put("methodId", "0xdd46508f");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xa0ffb9c1ce1fe56963b0321b32e7a0302114058b")
                                .append("topics", List.of(
                                        "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec",
                                        "0x98e787ffddc9c6cac3b591ee67e9a6481609d76ee1717fe2d67cbc13b11691e4",
                                        "0x00000000000000000000000055f4c8aba71a1e923edc303eb4feff14608cc226"
                                ))
                                .append("data", "0x"
                                        + "000000000000000000000000000000000000000000000000000000000000cef8"
                                        + "00000000000000000000000000000000000000000000000000000000000144f2"
                                        + "0000000000000000000000000000000000000000000000000000000000000000"
                                        + "000000000000000000000000000000000000000000000000000000000009d352"),
                        new Document("address", "0x55f4c8aba71a1e923edc303eb4feff14608cc226")
                                .append("topics", List.of(
                                        "0x547f338f02d501a923ee4865857cad34ce600348ee714b1968240d63259bb02e",
                                        "0x000000000000000000000000000000000000000000000000000000000009d352"
                                ))
                                .append("data", "0x"
                                        + "0000000000000000000000000000000000000000000000000000000000000000"
                                        + "0000000000000000000000000000000000000000000000000000000000000000")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("STOP_CONDITION_ZERO_EFFECT_MODIFY_LIQUIDITIES");
    }

    @Test
    @DisplayName("ParaSwap exact amount out with same-asset refund leaves review as swap")
    void paraSwapExactAmountOutWithSameAssetRefundLeavesReviewAsSwap() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.setTxHash("0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da");
        rawTransaction.getRawData().put("to", "0x6a000f20005980200259b80c5102003040001068");
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("input", "0x7f457675" + "00".repeat(128));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", ROUTER)
                                .append("value", "853605286"),
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", "0x6a000f20005980200259b80c5102003040001068")
                                .append("to", WALLET)
                                .append("value", "842509")
                )))
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0x4200000000000000000000000000000000000006")
                                .append("topics", List.of(
                                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                        "0x0000000000000000000000000e5891850bb3f03090f03010000806f080040100",
                                        "0x0000000000000000000000006a000f20005980200259b80c5102003040001068"
                                ))
                                .append("data", "0x01")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("routeSingle with clarified NFT mint becomes LP_ENTRY")
    void routeSingleWithClarifiedNftMintBecomesLpEntry() {
        RawTransaction rawTransaction = baseRaw(NetworkId.KATANA);
        rawTransaction.setTxHash("0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499");
        rawTransaction.getRawData().put("to", "0x3067bdba0e6628497d527bef511c22da8b32ca3f");
        rawTransaction.getRawData().put("methodId", "0xb94c3609");
        rawTransaction.getRawData().put("functionName", "routeSingle(tuple tokenIn,bytes data)");
        rawTransaction.getRawData().put("value", "450000000000000000");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "vbUSDC")
                                .append("tokenName", "vbUSDC")
                                .append("tokenDecimal", "6")
                                .append("from", COUNTERPARTY)
                                .append("to", WALLET)
                                .append("value", "2")
                )))
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0x2659c6085d26144117d904c46b48b6d180393d27")
                                .append("topics", List.of(
                                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                        "0x0000000000000000000000000000000000000000000000000000000000000000",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111",
                                        "0x0000000000000000000000000000000000000000000000000000000000008d69"
                                ))
                                .append("data", "0x")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("zero log multicall becomes explicit terminal stop condition")
    void zeroLogMulticallBecomesExplicitTerminalStopCondition() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.setTxHash("0x504695248b7be49796e52895005019fa7ff268297e394078e336ec5a14cbcf54");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("STOP_CONDITION_ZERO_LOGS");
    }

    @Test
    @DisplayName("zero effect collect becomes explicit terminal stop condition")
    void zeroEffectCollectBecomesExplicitTerminalStopCondition() {
        RawTransaction rawTransaction = baseRaw(NetworkId.KATANA);
        rawTransaction.setTxHash("0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775");
        rawTransaction.getRawData().put("to", "0x2659c6085d26144117d904c46b48b6d180393d27");
        rawTransaction.getRawData().put("methodId", "0xfc6f7865");
        rawTransaction.getRawData().put("functionName", "collect(tuple params)");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0x2659c6085d26144117d904c46b48b6d180393d27")
                                .append("topics", List.of(
                                        "0x40d0efd1e97d9562d1ff5f9323fd27ee4d7ecf8a74757e876c6287f9c12ee618"
                                ))
                                .append("data", "0x")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("NON_ECONOMIC_COLLECT");
    }

    @Test
    @DisplayName("unverified getReward without movement becomes terminal stop condition")
    void unverifiedGetRewardWithoutMovementBecomesTerminalStopCondition() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x508ad8c6695151cd84df379876cef4bd5c5370e8bdd660e54141a35ebe1d9d54");
        rawTransaction.getRawData().put("to", "0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc");
        rawTransaction.getRawData().put("methodId", "0x7050ccd9");
        rawTransaction.getRawData().put("functionName", "getReward(address _account, bool _claimExtras) returns (bool)");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).containsExactly("STOP_CONDITION_NON_MOVEMENT");
    }

    @Test
    @DisplayName("zkSync routed Across send with fee refunds resolves to BRIDGE_OUT and keeps only net fee")
    void zkSyncRoutedAcrossSendWithFeeRefundsResolvesToBridgeOut() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966");
        rawTransaction.getRawData().put("to", "0xde167bb9f640a3d6de7b8c16c28920755f5921f2");
        rawTransaction.getRawData().put("methodId", "0x27ad57d5");
        rawTransaction.getRawData().put(
                "input",
                "0x27ad57d5"
                        + WALLET.substring(2)
                        + "b456e051867625d320a7a793897058eb7eb6093b"
                        + "82af49447d8a07e3bd95bd0d56f35241523fbab1"
        );
        rawTransaction.getRawData().put("gasUsed", "1");
        rawTransaction.getRawData().put("gasPrice", "24078882500000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000008001")
                        .append("value", "58528841550000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", WALLET)
                        .append("value", "11512507800000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0xde167bb9f640a3d6de7b8c16c28920755f5921f2")
                        .append("value", "689595000000000000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", WALLET)
                        .append("value", "22937451250000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0xde167bb9f640a3d6de7b8c16c28920755f5921f2")
                        .append("to", "0xb456e051867625d320a7a793897058eb7eb6093b")
                        .append("value", "689595000000000000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0xb456e051867625d320a7a793897058eb7eb6093b")
                        .append("to", "0xe0b015e54d54fc84a6cb9b666099c46ade9335ff")
                        .append("value", "689595000000000000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0xe0b015e54d54fc84a6cb9b666099c46ade9335ff")
                        .append("to", "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91")
                        .append("value", "689595000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.protocolName()).isEqualTo("Across");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetContract()).isEqualTo("0x000000000000000000000000000000000000800a");
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-0.689595");
                });
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-0.0000240788825"));
    }

    @Test
    @DisplayName("zkSync routed Across send resolves from stored boundary-only raw evidence")
    void zkSyncRoutedAcrossSendResolvesFromStoredBoundaryOnlyRawEvidence() {
        String actualWallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966");
        rawTransaction.setWalletAddress(actualWallet);
        rawTransaction.getRawData().put("to", "0xde167bb9f640a3d6de7b8c16c28920755f5921f2");
        rawTransaction.getRawData().put("from", actualWallet);
        rawTransaction.getRawData().put("methodId", "0x27ad57d5");
        rawTransaction.getRawData().put("value", "689595000000000000");
        rawTransaction.getRawData().put("gasUsed", "532130");
        rawTransaction.getRawData().put("gasPrice", "45250000");
        rawTransaction.getRawData().put(
                "input",
                "0x27ad57d50000000000000000000000000000000000000000000000000000000000000020"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
                        + "0000000000000000000000000000000000000000000000000991eeffb5e2b000"
                        + "0000000000000000000000000000000000000000000000000000000000000080"
                        + "0000000000000000000000000000000000000000000000000000000000000046"
                        + "0000000000000000000000000000000000000000000000000000000000000001"
                        + "0000000000000000000000000000000000000000000000000000000000000020"
                        + "0000000000000000000000000000000000000000000000000000000000000001"
                        + "0000000000000000000000000000000000000000000000000000000000000040"
                        + "0000000000000000000000000000000000000000000000000000000000000034"
                        + "0000000000000000000000000000000000000000000000000000000000000002"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
                        + "00000000000000000000000082af49447d8a07e3bd95bd0d56f35241523fbab1"
                        + "0000000000000000000000000000000000000000000000000991eeffb5e2b000"
                        + "000000000000000000000000000000000000000000000000099196da00e145ee"
                        + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                        + "000000000000000000000000b456e051867625d320a7a793897058eb7eb6093b"
                        + "0000000000000000000000000000000000000000000000000000000000000140"
                        + "0000000000000000000000000000000000000000000000000000000000000a4b"
                        + "100000000000000000000000060ac95ec1153aa8199751917ede25d0ca49a36e2"
                        + "00000000000000000000000000000000000000000000000000000000000001e0"
                        + "0000000000000000000000000000000000000000000000000000000000000080"
                        + "000000000000000000000000394311a6aaa0d8e3411d8b62de4578d41322d1bd"
                        + "0000000000000000000000000000000000000000000000000000000000000009"
                        + "00000000000000000000000000000000000000000000000000000000693137f3"
                        + "0000000000000000000000000000000000000000000000000000000069315413"
                        + "0000000000000000000000000000000000000000000000000000000000000120"
                        + "0000000000000000000000000000000000000000000000000000000000000040"
                        + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                        + "0000000000000000000000000000000000000000000000000000000000000001"
                        + "0000000000000000000000000000000000000000000000000000000000000020"
                        + "0000000000000000000000000000000000000000000000000000000000000005"
                        + "0000000000000000000000000000000000000000000000000000000000000040"
                        + "0000000000000000000000000000000000000000000000000000000000000040"
                        + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                        + "000000000000000000000000000000000000000000000000099196da00e145ee"
                        + "0000000000000000000000000000000000000000000000000000000000000120"
                        + "0000000000000000000000000000000000000000000000000000000000000002"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
                        + "00000000000000000000000000000000000000000000000000000000000000e0"
                        + "0000000000000000000000000000000000000000000000000000000000000000"
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", actualWallet)
                        .append("to", "0x0000000000000000000000000000000000008001")
                        .append("value", "58528841550000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", actualWallet)
                        .append("value", "11512507800000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", actualWallet)
                        .append("to", "0xde167bb9f640a3d6de7b8c16c28920755f5921f2")
                        .append("value", "689595000000000000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", actualWallet)
                        .append("value", "22937451250000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.protocolName()).isEqualTo("Across");
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-0.689595"));
    }

    @Test
    @DisplayName("zkSync native alias inbound covered by tx value does not duplicate symbol-only ETH leg")
    void zkSyncNativeAliasInboundCoveredByTxValueDoesNotDuplicateSymbolOnlyEthLeg() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("value", "32893451503674712");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "32893451503674712")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetContract()).isEqualTo("0x000000000000000000000000000000000000800a");
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.032893451503674712");
                });
    }

    @Test
    @DisplayName("zkSync bridge settlement covered by tx value does not duplicate symbol-only ETH leg")
    void zkSyncBridgeSettlementCoveredByTxValueDoesNotDuplicateSymbolOnlyEthLeg() {
        String bridgeContract = "0x875d6d37ec55c8cf220b9e5080717549d8aa8eca";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("from", bridgeContract);
        rawTransaction.getRawData().put("to", WALLET);
        rawTransaction.getRawData().put("value", "32893451503674712");
        rawTransaction.getRawData().put("methodId", "0xcfc32570");
        rawTransaction.getRawData().put("functionName", "execute302((address,(uint32,bytes32,uint64),bytes32,bytes,bytes,uint256) _executionParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", bridgeContract)
                        .append("to", WALLET)
                        .append("value", "32893451503674712")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetContract()).isEqualTo("0x000000000000000000000000000000000000800a");
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.032893451503674712");
                });
    }

    @Test
    @DisplayName("zkSync bridge start covered by tx value does not duplicate symbol-only ETH leg")
    void zkSyncBridgeStartCoveredByTxValueDoesNotDuplicateSymbolOnlyEthLeg() {
        String lifiDiamond = "0x341e94069f53234fe6dabef707ad424830525715";
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("to", lifiDiamond);
        rawTransaction.getRawData().put("methodId", "0xae0b91e5");
        rawTransaction.getRawData().put("functionName", "execute(bytes,bytes[],uint256)");
        rawTransaction.getRawData().put("value", "421105918108654720");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenName", "Ether")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", lifiDiamond)
                        .append("value", "421105918108654720")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ZKSYNC, lifiDiamond))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        lifiDiamond,
                        Set.of(NetworkId.ZKSYNC),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "LiFi",
                        "Diamond",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.flows())
                .filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetContract()).isEqualTo("0x000000000000000000000000000000000000800a");
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-0.421105918108654720");
                });
    }

    @Test
    @DisplayName("setMinterApproval is treated as admin config instead of LP entry")
    void setMinterApprovalIsTreatedAsAdminConfigInsteadOfLpEntry() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("methodId", "0x0de54ba0");
        rawTransaction.getRawData().put("functionName", "setMinterApproval(address,bool)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Trader Joe addLiquidity stays LP entry and not lending deposit")
    void traderJoeAddLiquidityStaysLpEntryAndNotLendingDeposit() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("methodId", "0xe8e33700");
        rawTransaction.getRawData().put("functionName",
                "addLiquidity(address tokenX,address tokenY,uint256 amountX,uint256 amountY,uint256 amountXMin,uint256 amountYMin,address to,uint256 deadline)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "1000000"),
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "AVAX")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", ROUTER)
                        .append("value", "2000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("Plasma wrapped-native withdraw with native continuity becomes UNWRAP")
    void plasmaWrappedNativeWithdrawWithNativeContinuityBecomesUnwrap() {
        RawTransaction rawTransaction = baseRaw(NetworkId.PLASMA);
        rawTransaction.getRawData().put("to", "0x6100e367285b01f48d07953803a2d8dca5d19873");
        rawTransaction.getRawData().put("methodId", "0x2e1a7d4d");
        rawTransaction.getRawData().put("functionName", "withdraw(uint256 wad)");
        rawTransaction.getRawData().put("input",
                "0x2e1a7d4d000000000000000000000000000000000000000000000000043fb3f3f17f1268");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", "0x6100e367285b01f48d07953803a2d8dca5d19873")
                                .append("to", WALLET)
                                .append("value", "306002780400595560")
                                .append("isError", "0")
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNWRAP);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("route-tagged LI.FI diamond call becomes BRIDGE_OUT")
    void routeTaggedLifiDiamondCallBecomesBridgeOut() {
        RawTransaction rawTransaction = baseRaw(NetworkId.MANTLE);
        rawTransaction.getRawData().put("to", "0xbdff0c1c8b0b779581c4ac3ba1f29667c366c56e");
        rawTransaction.getRawData().put("methodId", "0xd7a08473");
        rawTransaction.getRawData().put("functionName",
                "callDiamondWithEIP2612Signature(address tokenAddress,uint256 amount,uint256 deadline,uint8 v,bytes32 r,bytes32 s,bytes diamondCalldata)");
        rawTransaction.getRawData().put("input", "0xd7a08473" + asciiHex("jumper.exchange|symbiosis|lifi"));
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0xbdff0c1c8b0b779581c4ac3ba1f29667c366c56e")
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("blank-method LI.FI route selector recovered from calldata becomes BRIDGE_OUT")
    void blankMethodLifiRouteSelectorRecoveredFromCalldataBecomesBridgeOut() {
        String bridgeEntry = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", bridgeEntry);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("functionName", "");
        rawTransaction.getRawData().put("input", "0xfc5f1003" + asciiHex("jumper.exchange|gasZipBridge|across"));
        rawTransaction.getRawData().put("value", "9000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, bridgeEntry))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        bridgeEntry,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        null,
                        ConfidenceLevel.HIGH,
                        "LiFi",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("route-tagged LI.FI bridge selector outside explicit allowlist still becomes BRIDGE_OUT")
    void routeTaggedLiFiBridgeSelectorOutsideExplicitAllowlistStillBecomesBridgeOut() {
        String bridgeEntry = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", bridgeEntry);
        rawTransaction.getRawData().put("methodId", "0xae328590");
        rawTransaction.getRawData().put("functionName", "");
        rawTransaction.getRawData().put("input", "0xae328590" + asciiHex("relay|jumper.exchange"));
        rawTransaction.getRawData().put("value", "1393882801667435");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, bridgeEntry))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        bridgeEntry,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        null,
                        ConfidenceLevel.HIGH,
                        "LiFi",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("blank-method route-tagged LI.FI bridge selector outside explicit allowlist still becomes BRIDGE_OUT")
    void blankMethodRouteTaggedLiFiBridgeSelectorOutsideExplicitAllowlistStillBecomesBridgeOut() {
        String bridgeEntry = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("to", bridgeEntry);
        rawTransaction.getRawData().put("methodId", "0x");
        rawTransaction.getRawData().put("functionName", "");
        rawTransaction.getRawData().put("input", "0xa1f1ce43" + asciiHex("across|jumper.exchange"));
        rawTransaction.getRawData().put("value", "11139382465917016");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.BASE, bridgeEntry))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        bridgeEntry,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        null,
                        ConfidenceLevel.HIGH,
                        "LiFi",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.HEURISTIC);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("transferRemote with token out and native fee becomes BRIDGE_OUT")
    void transferRemoteWithTokenOutAndNativeFeeBecomesBridgeOut() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", "0xf5f93d26229482adca3e42f84d08d549cf131658");
        rawTransaction.getRawData().put("methodId", "0x81b4e8b4");
        rawTransaction.getRawData().put("functionName",
                "transferRemote(uint32 _destination,bytes32 _recipient,uint256 _amountOrId)");
        rawTransaction.getRawData().put("value", "63765140242285");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0xf5f93d26229482adca3e42f84d08d549cf131658")
                        .append("value", "2000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("CCTP redeem with inbound USDC becomes BRIDGE_IN")
    void cctpRedeemWithInboundUsdcBecomesBridgeIn() {
        String bridgePayout = "0xc1062b7c5dc8e4b1df9f200fe360cdc0ed6e7741";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", "0x0000000000000000000000000000000000000000");
        rawTransaction.getRawData().put("methodId", "0xe5c1bf6e");
        rawTransaction.getRawData().put("functionName", "redeem(bytes cctpMsg,bytes cctpSigs)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", bridgePayout)
                        .append("to", WALLET)
                        .append("value", "427946249")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("GMX createOrder tuple path becomes in-scope derivative order request clarification")
    void gmxCreateOrderTuplePathBecomesInScopeDerivativeOrderRequestClarification() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec");
        rawTransaction.getRawData().put("methodId", "0x322bba21");
        rawTransaction.getRawData().put("functionName", "createOrder(tuple order)");
        rawTransaction.getRawData().put("value", "27638811423349461");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED");
        assertThat(result.excludedFromAccounting()).isFalse();
        assertThat(result.accountingExclusionReason()).isNull();
    }

    @Test
    @DisplayName("Pancake harvest with inbound reward becomes REWARD_CLAIM")
    void pancakeHarvestWithInboundRewardBecomesRewardClaim() {
        String masterChef = "0x5e09acf80c0296740ec5d6f643005a4ef8daa694";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("to", masterChef);
        rawTransaction.getRawData().put("methodId", "0x18fccc76");
        rawTransaction.getRawData().put("functionName", "harvest(uint256 pid, address to)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_B)
                        .append("tokenSymbol", "Cake")
                        .append("tokenDecimal", "18")
                        .append("from", masterChef)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, masterChef))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        masterChef,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.STAKE_CONTRACT,
                        null,
                        ConfidenceLevel.HIGH,
                        "PancakeSwap",
                        "V3",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("release with inbound token becomes REWARD_CLAIM")
    void releaseWithInboundTokenBecomesRewardClaim() {
        RawTransaction rawTransaction = baseRaw(NetworkId.LINEA);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x5828a3c0f07c6b841205d12660e0abb869bf98dc");
        rawTransaction.getRawData().put("methodId", "0x86d1a69f");
        rawTransaction.getRawData().put("functionName", "release()");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "LINEA")
                        .append("tokenDecimal", "18")
                        .append("from", "0x5828a3c0f07c6b841205d12660e0abb869bf98dc")
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("merkle claim with inbound payout becomes REWARD_CLAIM")
    void merkleClaimWithInboundPayoutBecomesRewardClaim() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0xbac23ec6ccab8631f500ecb6c01ea0ee0b72ac69");
        rawTransaction.getRawData().put("methodId", "0xae0b51df");
        rawTransaction.getRawData().put("functionName", "claim(uint256 index, uint256 amount, bytes32[] merkleProof)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "stRESOLV")
                        .append("tokenDecimal", "18")
                        .append("from", "0xbac23ec6ccab8631f500ecb6c01ea0ee0b72ac69")
                        .append("to", WALLET)
                        .append("value", "30000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("claimWithSig with inbound native payout becomes REWARD_CLAIM")
    void claimWithSigWithInboundNativePayoutBecomesRewardClaim() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x2f925542d1689258be55f87e17fcd8b7480d76f0");
        rawTransaction.getRawData().put("methodId", "0x7796e4ce");
        rawTransaction.getRawData().put("functionName",
                "claimWithSig(address account, bytes32 key, uint256 amountMax, uint256 expireTime, bytes signature)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", "0x2f925542d1689258be55f87e17fcd8b7480d76f0")
                                .append("to", WALLET)
                                .append("value", "27229307737197520")
                                .append("isError", "0")
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("LBHooks claim with inbound movement becomes LP_FEE_CLAIM")
    void lbHooksClaimWithInboundMovementBecomesLpFeeClaim() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0xe104964852be626ee27762712e4de521066859c9");
        rawTransaction.getRawData().put("methodId", "0x45718278");
        rawTransaction.getRawData().put("functionName", "claim(address LBHooks, uint256[] ids)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", "0xe104964852be626ee27762712e4de521066859c9")
                                .append("to", WALLET)
                                .append("value", "1000000000000000000")
                                .append("isError", "0")
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("executeOrder without clarified receipt becomes derivative execution clarification")
    void executeOrderWithoutClarifiedReceiptBecomesDerivativeExecutionClarification() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0x7ebc83f7");
        rawTransaction.getRawData().put("functionName", "executeOrder(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "1000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED");
    }

    @Test
    @DisplayName("executeOrder with PositionIncrease receipt becomes in-scope derivative increase")
    void executeOrderWithPositionIncreaseReceiptBecomesInScopeDerivativeIncrease() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0x7ebc83f7");
        rawTransaction.getRawData().put("functionName", "executeOrder(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", COUNTERPARTY)
                                .append("to", WALLET)
                                .append("value", "19472334000000")
                                .append("isError", "0")
                )));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0x01",
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x" + asciiHex("OrderExecuted PositionIncrease"))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106");
        assertThat(result.excludedFromAccounting()).isFalse();
        assertThat(result.accountingExclusionReason()).isNull();
    }

    @Test
    @DisplayName("executeOrder with structured GMX position increase log becomes in-scope derivative increase")
    void executeOrderWithStructuredGmxPositionIncreaseLogBecomesInScopeDerivativeIncrease() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0x7ebc83f7");
        rawTransaction.getRawData().put("functionName", "executeOrder(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", COUNTERPARTY)
                                .append("to", WALLET)
                                .append("value", "19472334000000")
                                .append("isError", "0")
                )));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        GmxEventTopicSupport.topicHash("OrderExecuted"),
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x"),
                        new Document("address", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("topics", List.of(
                                        "0x137a44067c8961cd7e1d876f4754a5a3a75989b4552f1843fc69c3b372def160"
                                ))
                                .append("data", "0x" + asciiHex("PositionIncrease"))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106");
    }

    @Test
    @DisplayName("executeOrder with PositionDecrease and sibling auto cancel becomes in-scope derivative decrease")
    void executeOrderWithPositionDecreaseAndSiblingAutoCancelBecomesInScopeDerivativeDecrease() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0x7ebc83f7");
        rawTransaction.getRawData().put("functionName", "executeOrder(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "9667735")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0x01",
                                        "0x8185a2694ec51cbc7ef47531e08ede8b4eacd55f7f866f6b00b5625f9c047de5",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x" + asciiHex("PositionDecrease OrderExecuted")),
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0x01",
                                        "0x3231f64e29aa6dbdbd9457215cfd7ef9b8c22e2fc71ea669d01df7f1fa4398b9",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x" + asciiHex("OrderCancelled AUTO_CANCEL"))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x8185a2694ec51cbc7ef47531e08ede8b4eacd55f7f866f6b00b5625f9c047de5");
        assertThat(result.excludedFromAccounting()).isFalse();
    }

    @Test
    @DisplayName("executeOrder with structured GMX position decrease log becomes in-scope derivative decrease")
    void executeOrderWithStructuredGmxPositionDecreaseLogBecomesInScopeDerivativeDecrease() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0x7ebc83f7");
        rawTransaction.getRawData().put("functionName", "executeOrder(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "9667735")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        GmxEventTopicSupport.topicHash("OrderExecuted"),
                                        "0x8185a2694ec51cbc7ef47531e08ede8b4eacd55f7f866f6b00b5625f9c047de5",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x"),
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        GmxEventTopicSupport.topicHash("OrderCancelled"),
                                        "0x3231f64e29aa6dbdbd9457215cfd7ef9b8c22e2fc71ea669d01df7f1fa4398b9",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x" + asciiHex("AUTO_CANCEL")),
                        new Document("address", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("topics", List.of(
                                        "0x137a44067c8961cd7e1d876f4754a5a3a75989b4552f1843fc69c3b372def160"
                                ))
                                .append("data", "0x" + asciiHex("PositionDecrease"))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x8185a2694ec51cbc7ef47531e08ede8b4eacd55f7f866f6b00b5625f9c047de5");
    }

    @Test
    @DisplayName("GMX helper multicall deposit request becomes pending clarification when request key is not yet persisted")
    void gmxHelperMulticallDepositRequestBecomesPendingClarification() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28");
        rawTransaction.getRawData().put("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8000000007d39aaf100000000e6d66ac8000000004c82aa41");
        rawTransaction.getRawData().put("value", "104501240797800");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                        .append("value", "1000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED");
    }

    @Test
    @DisplayName("GMX helper multicall order request to OrderVault becomes in-scope derivative request")
    void gmxHelperMulticallOrderRequestToOrderVaultBecomesInScopeDerivativeRequest() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7");
        rawTransaction.getRawData().put("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d800000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000003000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000000000018000000000000000000000000000000000000000000000000000000000000000447d39aaf100000000000000000000000031ef83a530fde1b38ee9a18093a333d8bbbc40d5000000000000000000000000000000000000000000000000000033e67d14a680000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000064e6d66ac8000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000031ef83a530fde1b38ee9a18093a333d8bbbc40d500000000000000000000000000000000000000000000000000000000007a12000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003046996807b000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000001e000000000000000000000000000000000000011449b266f7daf4020a0de846000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006dec442830813000000000000000000000000000000000000000000000000000033e67d14a680000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000111111111111111111111111111111111111111100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000ff0000000000000000000000000000000000000100000000000000000000000070d95587d40a2caf56bd97485ab3eec10bee6336000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
        rawTransaction.getRawData().put("value", "57065034000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x31eF83a530Fde1B38EE9A18093A333D8Bbbc40D5")
                        .append("value", "8000000")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0x01",
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x" + asciiHex("OrderCreated"))
                ))));

        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5"))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5",
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.PERP,
                        ProtocolRegistryRole.ORDER_VAULT,
                        null,
                        ConfidenceLevel.HIGH,
                        "GMX",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106");
        assertThat(result.excludedFromAccounting()).isFalse();
        assertThat(result.accountingExclusionReason()).isNull();
    }

    @Test
    @DisplayName("GMX helper multicall order request without explorer transfer still becomes derivative request clarification")
    void gmxHelperMulticallOrderRequestWithoutExplorerTransferStillBecomesDerivativeRequestClarification() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0xf8d8a2aaa743285f35f88c1477e8de37c4095b44c60964139799f033ada0ba51");
        rawTransaction.getRawData().put("to", "0x5ac4e27341e4cccb3e5fd62f9e62db2adf43dd57");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d800000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000c000000000000000000000000000000000000000000000000000000000000000447d39aaf100000000000000000000000031ef83a530fde1b38ee9a18093a333d8bbbc40d50000000000000000000000000000000000000000000000000000448e56e6a1800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003046996807b000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000001e000000000000000000000000000000000000011449b266f7daf4020a0de846000000000000000000000000000000000000000000000000000000000000076dd070000000000000000000000000000000000000000000000000006d6c6fd3150000000000000000000000000000000000000000000000000000006d186645e7a000000000000000000000000000000000000000000000000000000448e56e6a180000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000500000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000001000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000111111111111111111111111111111111111111100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000ff0000000000000000000000000000000000000100000000000000000000000070d95587d40a2caf56bd97485ab3eec10bee6336000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
        rawTransaction.getRawData().put("value", "75378134000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));

        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5"))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5",
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.PERP,
                        ProtocolRegistryRole.ORDER_VAULT,
                        null,
                        ConfidenceLevel.HIGH,
                        "GMX",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED");
        assertThat(result.excludedFromAccounting()).isFalse();
    }

    @Test
    @DisplayName("GMX helper multicall share burn becomes LP exit request")
    void gmxHelperMulticallShareBurnBecomesLpExitRequest() {
        String gmxRouter = "0x7eadee3f226a0d2be54a333a2588cc94292ffd7f";
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x806ccd26c2f11ab6180c8f1f0448df2fda3917ffcc65dc7d661c1ae8d86426ec");
        rawTransaction.getRawData().put("to", gmxRouter);
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", multicallInput("0x7d39aaf1", "0xe6d66ac8", "0x647c6fa4"));
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x1111222233334444555566667777888899990000")
                        .append("tokenSymbol", "GLV [WETH-USDC]")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", gmxRouter)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, gmxRouter))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        gmxRouter,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.PERP,
                        ProtocolRegistryRole.EXCHANGE_ROUTER,
                        null,
                        ConfidenceLevel.HIGH,
                        "GMX",
                        "V2",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED");
        assertThat(result.protocolName()).isEqualTo("GMX");
    }

    @Test
    @DisplayName("GMX helper multicall share burn with embedded selector fallback becomes LP exit request")
    void gmxHelperMulticallShareBurnWithEmbeddedSelectorFallbackBecomesLpExitRequest() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x806ccd26c2f11ab6180c8f1f0448df2fda3917ffcc65dc7d661c1ae8d86426ec");
        rawTransaction.getRawData().put("to", "0x5ac4e27341e4cccb3e5fd62f9e62db2adf43dd57");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put(
                "input",
                "0xac9650d8"
                        + "000000000000000000000000000000000000000000000000000000007d39aaf1"
                        + "00000000000000000000000000000000000000000000000000000000e6d66ac8"
                        + "00000000000000000000000000000000000000000000000000000000647c6fa4"
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x1111222233334444555566667777888899990000")
                        .append("tokenSymbol", "GLV [WETH-USDC]")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x5ac4e27341e4cccb3e5fd62f9e62db2adf43dd57")
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED");
        assertThat(result.protocolName()).isEqualTo("GMX");
    }

    @Test
    @DisplayName("GMX executeWithdrawal becomes LP exit settlement")
    void gmxExecuteWithdrawalBecomesLpExitSettlement() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x977474f616af6a4227237ec7680f8c2023b7c626652ffda2349ba71f76cfb00e");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0xc96fea9f");
        rawTransaction.getRawData().put("functionName", "executeWithdrawal(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1")
                        .append("tokenSymbol", "WETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "10000000000000000"),
                new Document("contractAddress", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1")
                        .append("tokenSymbol", "WETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "20000000000000000")
        )).append("internalTransfers", List.of(
                new Document("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1500000000000000")
                        .append("isError", "0")
        )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() == NormalizedLegRole.TRANSFER).hasSize(3);
    }

    @Test
    @DisplayName("CoW Eth Flow request selector does not collide into GMX derivative request")
    void cowEthFlowRequestDoesNotCollideIntoGmxDerivativeRequest() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105");
        rawTransaction.getRawData().put("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec");
        rawTransaction.getRawData().put("methodId", "0x322bba21");
        rawTransaction.getRawData().put("functionName", "createOrder((address,address,uint256,uint256,bytes32,uint256,uint32,bool,int64))");
        rawTransaction.getRawData().put("input", cowEthFlowCreateOrderInput(
                "0x5979d7b546e38e414f7e9822514be443a4800529",
                WALLET,
                "27638811423349461",
                "22628189600680790",
                "0xd7e27923e5a18d36851057738a3970e4ddd2905e85b5fc19436a160647863fff",
                "0",
                "1760524229",
                false,
                "58228845"
        ));
        rawTransaction.getRawData().put("value", "27638811423349461");

        String expectedCorrelation = CowSwapSupport.resolveEthFlowCorrelationId(
                com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView.wrap(rawTransaction)
        );

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DEX_ORDER_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo(expectedCorrelation);
        assertThat(result.protocolName()).isEqualTo(CowSwapSupport.PROTOCOL_NAME);
        assertThat(result.protocolVersion()).isEqualTo(CowSwapSupport.ETH_FLOW_VERSION);
        assertThat(result.missingDataReasons()).doesNotContain("GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED");
    }

    @Test
    @DisplayName("CoW settlement with persisted trade log becomes async dex order settlement")
    void cowSettlementWithPersistedTradeLogBecomesDexOrderSettlement() {
        RawTransaction request = baseRaw(NetworkId.ARBITRUM);
        request.getRawData().put("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec");
        request.getRawData().put("methodId", "0x322bba21");
        request.getRawData().put("input", cowEthFlowCreateOrderInput(
                "0x5979d7b546e38e414f7e9822514be443a4800529",
                WALLET,
                "27638811423349461",
                "22628189600680790",
                "0xd7e27923e5a18d36851057738a3970e4ddd2905e85b5fc19436a160647863fff",
                "0",
                "1760524229",
                false,
                "58228845"
        ));
        request.getRawData().put("value", "27638811423349461");
        String expectedCorrelation = CowSwapSupport.resolveEthFlowCorrelationId(
                com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView.wrap(request)
        );

        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x9008d19f58aabd9ed0d60971565aa8510560ab41");
        rawTransaction.getRawData().put("methodId", "0x13d79a0b");
        rawTransaction.getRawData().put("functionName", "settle(bytes32[][],uint256[],bytes32[],bytes)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                        .append("tokenSymbol", "wstETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                        .append("to", WALLET)
                        .append("value", "22742145033450122")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("topics", List.of("0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17"))
                                .append("data", cowTradeLogData(expectedCorrelation))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo(expectedCorrelation);
        assertThat(result.protocolName()).isEqualTo(CowSwapSupport.PROTOCOL_NAME);
        assertThat(result.protocolVersion()).isEqualTo(CowSwapSupport.GPV2_VERSION);
    }

    @Test
    @DisplayName("CoW settlement with blank top-level explorer context still becomes settlement family from trade log")
    void cowSettlementWithBlankTopLevelExplorerContextStillBecomesSettlementFromTradeLog() {
        RawTransaction request = baseRaw(NetworkId.ARBITRUM);
        request.getRawData().put("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec");
        request.getRawData().put("methodId", "0x322bba21");
        request.getRawData().put("input", cowEthFlowCreateOrderInput(
                "0x5979d7b546e38e414f7e9822514be443a4800529",
                WALLET,
                "27638811423349461",
                "22628189600680790",
                "0xd7e27923e5a18d36851057738a3970e4ddd2905e85b5fc19436a160647863fff",
                "0",
                "1760524229",
                false,
                "58228845"
        ));
        request.getRawData().put("value", "27638811423349461");
        String expectedCorrelation = CowSwapSupport.resolveEthFlowCorrelationId(
                com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView.wrap(request)
        );

        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0");
        rawTransaction.getRawData().remove("from");
        rawTransaction.getRawData().remove("to");
        rawTransaction.getRawData().remove("functionName");
        rawTransaction.getRawData().put("methodId", "0x13d79a0b");
        rawTransaction.getRawData().put("input", "deprecated");
        rawTransaction.getRawData().put("explorer", new Document("tx", new Document()
                .append("methodId", "0x13d79a0b")
                .append("functionName", "")
                .append("to", "")
                .append("from", ""))
                .append("tokenTransfers", List.of(
                        new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                                .append("tokenSymbol", "wstETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("to", WALLET)
                                .append("value", "22742145033450122")
                ))
                .append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("topics", List.of("0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17"))
                                .append("data", cowTradeLogData(expectedCorrelation))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.correlationId()).isEqualTo(expectedCorrelation);
    }

    @Test
    @DisplayName("CoW settlement without persisted trade log remains pending clarification")
    void cowSettlementWithoutPersistedTradeLogRemainsPendingClarification() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0");
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "0x9008d19f58aabd9ed0d60971565aa8510560ab41");
        rawTransaction.getRawData().put("methodId", "0x13d79a0b");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                        .append("tokenSymbol", "wstETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                        .append("to", WALLET)
                        .append("value", "22742145033450122")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED");
        assertThat(result.protocolName()).isEqualTo(CowSwapSupport.PROTOCOL_NAME);
    }

    @Test
    @DisplayName("clarified GMX execution without top-level selector becomes derivative increase")
    void clarifiedGmxExecutionWithoutTopLevelSelectorBecomesDerivativeIncrease() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7");
        rawTransaction.getRawData().put("hash", rawTransaction.getTxHash());
        rawTransaction.getRawData().put("blockNumber", "314902599");
        rawTransaction.getRawData().put("timeStamp", "1741780460");
        rawTransaction.getRawData().put("gas", "52300");
        rawTransaction.getRawData().put("gasUsed", "0");
        rawTransaction.getRawData().put("input", "");
        rawTransaction.getRawData().put("isError", "0");
        rawTransaction.getRawData().put("type", "call");
        rawTransaction.getRawData().remove("from");
        rawTransaction.getRawData().remove("to");
        rawTransaction.getRawData().remove("methodId");
        rawTransaction.getRawData().remove("functionName");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", "0xe68caaacdf6439628dfd2fe624847602991a31eb")
                                .append("to", WALLET)
                                .append("value", "19472334000000")
                                .append("isError", "0")
                )));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("receipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0xlegacy",
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x")
                )))
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0x680f10f06595d3d707241f604672ec4b6ae50eb82728ec2f3c65f6789e897760",
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("eventName", "PositionIncrease")
                                .append("decodedEvent", "OrderExecuted PositionIncrease")
                                .append("data", "0x")
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106");
        assertThat(result.excludedFromAccounting()).isFalse();
    }

    @Test
    @DisplayName("GMX helper multicall deposit request with persisted event key becomes LP entry request")
    void gmxHelperMulticallDepositRequestWithPersistedEventKeyBecomesLpEntryRequest() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28");
        rawTransaction.getRawData().put("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8000000007d39aaf100000000e6d66ac8000000004c82aa41");
        rawTransaction.getRawData().put("value", "104501240797800");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                        .append("value", "1000000000")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0xccee02d31cafad9001fbdc4dd5cf4957e152a372530316a7d856401e4c5d74bd",
                                        "0x395f6f1dc755f00d492b144a2fcb74f2e084fe5f842ae11b89fe1c6b140268f2",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x395f6f1dc755f00d492b144a2fcb74f2e084fe5f842ae11b89fe1c6b140268f2");
        assertThat(result.protocolName()).isEqualTo("GMX");
    }

    @Test
    @DisplayName("GMX executeDeposit settlement becomes pending clarification when settlement key is not yet persisted")
    void gmxExecuteDepositSettlementBecomesPendingClarification() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab");
        rawTransaction.getRawData().put("to", null);
        rawTransaction.getRawData().put("methodId", "0xc30d8910");
        rawTransaction.getRawData().put("functionName", "executeDeposit(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("tokenSymbol", "GM: ETH/USD [WETH-USDC]")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "529616719874058263403")
        )).append("internalTransfers", List.of(
                new Document("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "19362414771800")
                        .append("isError", "0")
        )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(result.missingDataReasons()).contains("GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED");
    }

    @Test
    @DisplayName("GMX executeDeposit settlement with persisted event key becomes LP entry settlement")
    void gmxExecuteDepositSettlementWithPersistedEventKeyBecomesLpEntrySettlement() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab");
        rawTransaction.getRawData().put("to", null);
        rawTransaction.getRawData().put("methodId", "0xc30d8910");
        rawTransaction.getRawData().put("functionName", "executeDeposit(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("tokenSymbol", "GM: ETH/USD [WETH-USDC]")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "529616719874058263403")
        )).append("internalTransfers", List.of(
                new Document("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "19362414771800")
                        .append("isError", "0")
        )));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                        "0x2856020a9644603d22d7b029b5649a55d708b88d9049150f146ac26c4107b880",
                                        "0x395f6f1dc755f00d492b144a2fcb74f2e084fe5f842ae11b89fe1c6b140268f2",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0x395f6f1dc755f00d492b144a2fcb74f2e084fe5f842ae11b89fe1c6b140268f2");
        assertThat(result.protocolName()).isEqualTo("GMX");
    }

    @Test
    @DisplayName("GMX executeGlvDeposit settlement prefers higher-scope GLV key over intermediate deposit key")
    void gmxExecuteGlvDepositSettlementPrefersHigherScopeGlvKey() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ARBITRUM);
        rawTransaction.setTxHash("0x9fab1650749416a4fcf94f02cf16abd99b80f3ec1f1a18851c6f891a21391579");
        rawTransaction.getRawData().put("to", null);
        rawTransaction.getRawData().put("methodId", "0x5ee8ec8f");
        rawTransaction.getRawData().put("functionName", "executeGlvDeposit(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x47c031236e19d024b42f8ae6780e44a573170703")
                        .append("tokenSymbol", "GLV [WETH-USDC]")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1065435623191879647")
        )).append("internalTransfers", List.of(
                new Document("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "18106561704961")
                        .append("isError", "0")
        )));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        "0x2856020a9644603d22d7b029b5649a55d708b88d9049150f146ac26c4107b880",
                                        "0x88f3c68e3f6579ccb68ce8d428f5c74bbe0c460d5ba77fa7408bdd7c389c8eb5",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                )),
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        "0x168af62e3da2e23e63dfeb41b97ea0feef3c7a45e72ebc59e924f19ae915f14e",
                                        "0xcae9309eacbae0ae8fb295bce2293b08c0b0c80624f60b2929d14a4a0176ff6f",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                ))));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY_SETTLEMENT);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("0xcae9309eacbae0ae8fb295bce2293b08c0b0c80624f60b2929d14a4a0176ff6f");
        assertThat(result.protocolName()).isEqualTo("GMX");
    }

    @Test
    @DisplayName("burn-only initiateWithdrawal becomes staking withdraw request with correlation id")
    void burnOnlyInitiateWithdrawalBecomesStakingWithdrawRequest() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.setTxHash("0xd446b9d8a4b32b795cc957dc0e8381792bdf283824a8faf979042115f4c961c0");
        rawTransaction.getRawData().put("to", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23");
        rawTransaction.getRawData().put("methodId", "0x12edde5e");
        rawTransaction.getRawData().put("functionName", "initiateWithdrawal(uint256 withdrawAmount)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23")
                        .append("tokenSymbol", "stRESOLV")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "30000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_WITHDRAW_REQUEST);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("resolv-unstake:" + WALLET + ":30");
        assertThat(result.protocolName()).isEqualTo("Resolv");
    }

    @Test
    @DisplayName("Resolv withdraw claim becomes correlated staking withdraw")
    void resolvWithdrawClaimBecomesCorrelatedStakingWithdraw() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.setTxHash("0xde8afcabd1284b6b287eb9550d204e03d9d1c59da518a2d5acbe3f4613f38a5b");
        rawTransaction.getRawData().put("to", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23");
        rawTransaction.getRawData().put("methodId", "0xe1e13847");
        rawTransaction.getRawData().put("functionName", "withdraw(bool withdrawBNB, address token)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x259338656198ec7a76c729514d3cb45dfbf768a1")
                        .append("tokenSymbol", "RESOLV")
                        .append("tokenDecimal", "18")
                        .append("from", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23")
                        .append("to", WALLET)
                        .append("value", "30000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_WITHDRAW);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.correlationId()).isEqualTo("resolv-unstake:" + WALLET + ":30");
        assertThat(result.protocolName()).isEqualTo("Resolv");
        assertThat(result.flows())
                .filteredOn(flow -> flow.getAssetSymbol().equals("RESOLV"))
                .singleElement()
                .satisfies(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
    }

    @Test
    @DisplayName("claimSharesAndRequestRedeem becomes narrow review instead of EXTERNAL_TRANSFER_OUT")
    void claimSharesAndRequestRedeemBecomesNarrowReviewInsteadOfExternalTransferOut() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.getRawData().put("to", "0x3048925b3ea5a8c12eecccb8810f5f7544db54af");
        rawTransaction.getRawData().put("methodId", "0x5cfe2fe4");
        rawTransaction.getRawData().put("functionName",
                "claimSharesAndRequestRedeem(uint256 sharesToRedeem) returns (uint40 requestId)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x3048925b3ea5a8c12eecccb8810f5f7544db54af")
                        .append("tokenSymbol", "turtleAvalancheUSDC")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x3048925b3ea5a8c12eecccb8810f5f7544db54af")
                        .append("value", "2787570915169867351889")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).contains("PENDING_REDEEM_REQUEST");
    }

    @Test
    @DisplayName("claim-like airdrop becomes narrow review instead of resolved inbound")
    void claimLikeAirdropBecomesNarrowReviewInsteadOfResolvedInbound() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "");
        rawTransaction.getRawData().put("methodId", "0x729ad39e");
        rawTransaction.getRawData().put("functionName", "airdrop(address[] _bots)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xfae7d01301e2eeede488f0953547e712a56c5e1d")
                        .append("tokenSymbol", "OracleAI")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "3000000000000000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).contains("CLAIM_LIKE_SPAM_OR_AIRDROP");
    }

    @Test
    @DisplayName("claim-like airdrop still becomes narrow review when explorer proves inbound but movement legs do not materialize")
    void claimLikeAirdropUsesExplorerInboundEvidenceWhenMovementLegsDoNotMaterialize() {
        RawTransaction rawTransaction = baseRaw(NetworkId.BASE);
        rawTransaction.getRawData().put("from", COUNTERPARTY);
        rawTransaction.getRawData().put("to", "");
        rawTransaction.getRawData().put("methodId", "0x729ad39e");
        rawTransaction.getRawData().put("functionName", "airdrop(address[] _bots)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xfae7d01301e2eeede488f0953547e712a56c5e1d")
                        .append("tokenSymbol", "OracleAI")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).contains("CLAIM_LIKE_SPAM_OR_AIRDROP");
    }

    @Test
    @DisplayName("fee-bearing claim admin action becomes narrow review")
    void feeBearingClaimAdminActionBecomesNarrowReview() {
        RawTransaction rawTransaction = baseRaw(NetworkId.MANTLE);
        rawTransaction.getRawData().put("to", "0x4205e56a69a0130a9e0828d45d0c84e45340a196");
        rawTransaction.getRawData().put("methodId", "0xdc4b201d");
        rawTransaction.getRawData().put("functionName",
                "claim(tuple pinData,address adminTreasury,uint256 adminFee,uint256 signedAt,string cid,bytes signature)");
        rawTransaction.getRawData().put("value", "1500000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.missingDataReasons()).contains("FEE_BEARING_CLAIM_ADMIN_ACTION");
    }

    @Test
    @DisplayName("zkSync Aave borrow keeps reserve asset economic and debt marker plus settlement continuity-only")
    void zkSyncAaveBorrowKeepsOnlyReserveAssetEconomic() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0x69aa8504aabff01fa86c3f8910ecb186f7d5568e7594c4c1dcfa044291d9f021");
        rawTransaction.getRawData().put("to", "0x78e30497a3c7527d953c6b1e3541b021a98ac43c");
        rawTransaction.getRawData().put("methodId", "0xa415bcad");
        rawTransaction.getRawData().put("functionName", "borrow(address,uint256,uint256,uint16,address)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000008001")
                        .append("value", "51074328540000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", WALLET)
                        .append("value", "14500292040000"),
                new Document("contractAddress", "0x0049250d15a8550c5a14baa5af5b662a93a525b9")
                        .append("tokenSymbol", "variableDebtZksUSDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "1200000001"),
                new Document("contractAddress", "0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1200000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BORROW);
        assertThat(result.protocolName()).isEqualTo("Aave");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole())
                .containsExactlyInAnyOrder(
                        "variableDebtZksUSDC:TRANSFER",
                        "USDC:BUY"
                );
    }

    @Test
    @DisplayName("zkSync native alias fee transfer matching gas is emitted once and keeps raw native net")
    void zkSyncNativeAliasFeeTransferMatchingGasIsEmittedOnceAndKeepsRawNativeNet() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0x69aa8504aabff01fa86c3f8910ecb186f7d5568e7594c4c1dcfa044291d9f021");
        rawTransaction.getRawData().put("to", "0x78e30497a3c7527d953c6b1e3541b021a98ac43c");
        rawTransaction.getRawData().put("methodId", "0xa415bcad");
        rawTransaction.getRawData().put("functionName", "borrow(address,uint256,uint256,uint16,address)");
        rawTransaction.getRawData().put("gasUsed", "1");
        rawTransaction.getRawData().put("gasPrice", "51074328540000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000008001")
                        .append("value", "51074328540000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", WALLET)
                        .append("value", "14500292040000"),
                new Document("contractAddress", "0x0049250d15a8550c5a14baa5af5b662a93a525b9")
                        .append("tokenSymbol", "variableDebtZksUSDC")
                        .append("tokenDecimal", "6")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "1200000001"),
                new Document("contractAddress", "0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1200000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BORROW);
        assertThat(result.flows())
                .filteredOn(flow -> "ETH".equals(flow.getAssetSymbol()) && flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-0.00005107432854"));
        assertThat(result.flows())
                .filteredOn(flow -> "ETH".equals(flow.getAssetSymbol()) && flow.getRole() != NormalizedLegRole.FEE)
                .isEmpty();

        BigDecimal ethNet = result.flows().stream()
                .filter(flow -> "ETH".equals(flow.getAssetSymbol()))
                .map(NormalizedTransaction.Flow::getQuantityDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ethNet).isEqualByComparingTo("-0.00005107432854");
    }

    @Test
    @DisplayName("zkSync Aave withdrawETH resolves to lending withdraw before generic fallback")
    void zkSyncAaveWithdrawEthResolvesToLendingWithdraw() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0xb20600840451280027707eee9330bfbea5737063ec9f648cca425657d61aa35a");
        rawTransaction.getRawData().put("methodId", "0x80500d20");
        rawTransaction.getRawData().put("functionName", "withdrawETH(address,uint256,address)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xb7b93bcf82519bb757fd18b23a389245dbd8ca64")
                        .append("tokenSymbol", "aZksWETH")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "620119937887953280"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "620119937887953280")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.protocolName()).isEqualTo("Aave");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        "aZksWETH:-0.62011993788795328",
                        "ETH:0.62011993788795328"
                );
    }

    @Test
    @DisplayName("zkSync Aave supplyWithPermit resolves to lending deposit instead of unwrap")
    void zkSyncAaveSupplyWithPermitResolvesToLendingDeposit() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0xcfe0fd4d86b0116fecf0ffaaba0a41c5b26a174a7360981e968a6b2ed57f4e96");
        rawTransaction.getRawData().put("methodId", "0x02c205f0");
        rawTransaction.getRawData().put(
                "functionName",
                "supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)"
        );
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91")
                        .append("tokenSymbol", "WETH")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "966986134250302027"),
                new Document("contractAddress", "0xb7b93bcf82519bb757fd18b23a389245dbd8ca64")
                        .append("tokenSymbol", "aZksWETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "966986587248377320")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.protocolName()).isEqualTo("Aave");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        "WETH:-0.966986134250302027",
                        "aZksWETH:0.96698658724837732"
                );
    }

    @Test
    @DisplayName("zkSync Aave depositETH resolves to lending deposit instead of LP exit")
    void zkSyncAaveDepositEthResolvesToLendingDeposit() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0xb7f3cd6b871b410276f3254618cf24572385408e376cdfd442b3cc58d82288ee");
        rawTransaction.getRawData().put("methodId", "0x474cf53d");
        rawTransaction.getRawData().put("functionName", "depositETH(address,address,uint16)");
        rawTransaction.getRawData().put("value", "620119937887953280");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xb7b93bcf82519bb757fd18b23a389245dbd8ca64")
                        .append("tokenSymbol", "aZksWETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "620119937887953280")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.METHOD_ID);
        assertThat(result.protocolName()).isEqualTo("Aave");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getQuantityDelta().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        "ETH:-0.62011993788795328",
                        "aZksWETH:0.62011993788795328"
                );
    }

    @Test
    @DisplayName("zkSync Aave gateway selector does not fire without audited receipt shape")
    void zkSyncAaveGatewaySelectorRequiresAuditedReceiptShape() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.getRawData().put("methodId", "0x474cf53d");
        rawTransaction.getRawData().put("functionName", "depositETH(address,address,uint16)");
        rawTransaction.getRawData().put("value", "100000000000000000");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isNotEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
    }

    @Test
    @DisplayName("zkSync Aave repay keeps reserve asset economic and debt marker plus settlement continuity-only")
    void zkSyncAaveRepayKeepsOnlyReserveAssetEconomic() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0x8fb1c9606fd170f13e052e213460925c3d99aef986bc2b1cf74ddffec4bc50e1");
        rawTransaction.getRawData().put("to", "0x78e30497a3c7527d953c6b1e3541b021a98ac43c");
        rawTransaction.getRawData().put("methodId", "0x573ade81");
        rawTransaction.getRawData().put("functionName", "repay(address,uint256,uint256,address)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000008001")
                        .append("value", "32541221370000"),
                new Document("contractAddress", "0x000000000000000000000000000000000000800a")
                        .append("tokenSymbol", "ETH")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000008001")
                        .append("to", WALLET)
                        .append("value", "6400794120000"),
                new Document("contractAddress", "0x0049250d15a8550c5a14baa5af5b662a93a525b9")
                        .append("tokenSymbol", "variableDebtZksUSDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "1996008"),
                new Document("contractAddress", "0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "2000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REPAY);
        assertThat(result.protocolName()).isEqualTo("Aave");
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole())
                .containsExactlyInAnyOrder(
                        "variableDebtZksUSDC:TRANSFER",
                        "USDC:SELL"
                );
    }

    @Test
    @DisplayName("generic repay selector without Aave debt marker does not assume protocol handoff")
    void genericRepaySelectorWithoutAaveDebtMarkerKeepsProtocolNull() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ZKSYNC);
        rawTransaction.setTxHash("0x6d16bf9098770891d7ac26d6d0de7bb5bdfbdf5f980b1dc68a5f40da12345678");
        rawTransaction.getRawData().put("to", COUNTERPARTY);
        rawTransaction.getRawData().put("methodId", "0x573ade81");
        rawTransaction.getRawData().put("functionName", "repay(address,uint256,uint256,address)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", COUNTERPARTY)
                        .append("value", "2000000")
        )).append("internalTransfers", List.of()));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REPAY);
        assertThat(result.protocolName()).isNull();
    }

    @Test
    @DisplayName("Angle claim removes self-canceling wrapper pair from active reward economics")
    void angleClaimRemovesSelfCancelingWrapperPair() {
        String rewardDistributor = "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae";
        RawTransaction rawTransaction = baseRaw(NetworkId.KATANA);
        rawTransaction.setTxHash("0x627fecf2e434d5f10e783745611aad780c7d6680fdcaef267e568e1f793ef093");
        rawTransaction.getRawData().put("to", rewardDistributor);
        rawTransaction.getRawData().put("methodId", "0x71ee95c0");
        rawTransaction.getRawData().put("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x17bff452dae47e07cea877ff0e1aba17eb62b0ab")
                        .append("tokenSymbol", "SUSHI")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "1618757346699700745"),
                new Document("contractAddress", "0x7f1f4b4b29f5058fa32cc7a97141b8d7e5abdc2d")
                        .append("tokenSymbol", "KAT")
                        .append("tokenDecimal", "18")
                        .append("from", "0x6e9c1f88a960fe63387eb4b71bc525a9313d8461")
                        .append("to", WALLET)
                        .append("value", "116577519457094965406"),
                new Document("contractAddress", "0x6e9c1f88a960fe63387eb4b71bc525a9313d8461")
                        .append("tokenSymbol", "KAT")
                        .append("tokenDecimal", "18")
                        .append("from", rewardDistributor)
                        .append("to", WALLET)
                        .append("value", "116577519457094965406"),
                new Document("contractAddress", "0x6e9c1f88a960fe63387eb4b71bc525a9313d8461")
                        .append("tokenSymbol", "KAT")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "116577519457094965406")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.KATANA, rewardDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        rewardDistributor,
                        Set.of(NetworkId.KATANA),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Angle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole())
                .containsExactlyInAnyOrder("SUSHI:BUY", "KAT:BUY");
    }

    @Test
    @DisplayName("Pendle zap out bundle becomes LP exit with reward side-flow")
    void pendleZapOutBundleBecomesLpExitWithRewardSideFlow() {
        String pendleDistributor = "0x70f61901658aafb7ae57da0c30695ce4417e72b9";
        RawTransaction rawTransaction = baseRaw(NetworkId.MANTLE);
        rawTransaction.setTxHash("0xf7f8908b455261dc67a7f905ca99f1041987de690a7574d440e31739c3132430");
        rawTransaction.getRawData().put("to", pendleDistributor);
        rawTransaction.getRawData().put("methodId", "0x8b284b0e");
        rawTransaction.getRawData().put("functionName", "zapOutV3SingleToken(uint256 _pid,uint256 _amount,tuple _output,tuple _limit,bool _stake)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xc2535b24b47afc15379b55e3ad077bf720dbb34d")
                        .append("tokenSymbol", "eqbPENDLE-LPT")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "445041029858104302"),
                new Document("contractAddress", "0xd27b18915e7acc8fd6ac75db6766a80f8d2f5729")
                        .append("tokenSymbol", "PENDLE")
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "12731662739929251"),
                new Document("contractAddress", "0xc2535b24b47afc15379b55e3ad077bf720dbb34d")
                        .append("tokenSymbol", "eqbPENDLE-LPT")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", pendleDistributor)
                        .append("value", "445041029858104302"),
                new Document("contractAddress", "0xe6829d9a7ee3040e1276fa75293bde931859e8fa")
                        .append("tokenSymbol", "cmETH")
                        .append("tokenDecimal", "18")
                        .append("from", ROUTER)
                        .append("to", WALLET)
                        .append("value", "862092260317885000")
        )).append("internalTransfers", List.of()));
        when(protocolRegistryService.lookup(NetworkId.MANTLE, pendleDistributor))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        pendleDistributor,
                        Set.of(NetworkId.MANTLE),
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.REWARD_ROUTER,
                        ProtocolRegistryEventType.REWARD_CLAIM,
                        ConfidenceLevel.HIGH,
                        "Pendle",
                        "V1",
                        false,
                        null
                )));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(result.flows()).filteredOn(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole())
                .containsExactlyInAnyOrder("PENDLE:BUY", "cmETH:TRANSFER");
    }

    private static RawTransaction tokenSwapRaw(NetworkId networkId) {
        RawTransaction rawTransaction = baseRaw(networkId);
        rawTransaction.getRawData()
                .put("to", ROUTER);
        rawTransaction.getRawData()
                .put("methodId", "0x38ed1739");
        rawTransaction.getRawData()
                .put("functionName", "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)");
        rawTransaction.getRawData()
                .put("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", ROUTER)
                                .append("value", "1000000"),
                        new Document("contractAddress", TOKEN_B)
                                .append("tokenSymbol", "ARB")
                                .append("tokenDecimal", "18")
                                .append("from", ROUTER)
                                .append("to", WALLET)
                                .append("value", "2000000000000000000")
                )).append("internalTransfers", List.of()));
        return rawTransaction;
    }

    private static RawTransaction baseRaw(NetworkId networkId) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xabc:" + networkId.name() + ":" + WALLET);
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", ROUTER)
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("gasPrice", "50000000000")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private static RawTransaction promoDistributionRaw(
            NetworkId networkId,
            String methodId,
            String functionName,
            String tokenSymbol,
            String tokenName
    ) {
        RawTransaction rawTransaction = baseRaw(networkId);
        rawTransaction.getRawData().put("from", "");
        rawTransaction.getRawData().put("to", "");
        rawTransaction.getRawData().put("methodId", methodId);
        rawTransaction.getRawData().put("functionName", functionName);
        rawTransaction.getRawData().put("value", "0");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", TOKEN_A)
                        .append("tokenSymbol", tokenSymbol)
                        .append("tokenName", tokenName)
                        .append("tokenDecimal", "18")
                        .append("from", COUNTERPARTY)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));
        return rawTransaction;
    }

    private static RawTransaction promoInboundRaw(
            NetworkId networkId,
            String methodId,
            String functionName,
            String tokenContract,
            String tokenSymbol,
            String tokenName,
            String senderAddress
    ) {
        RawTransaction rawTransaction = baseRaw(networkId);
        rawTransaction.getRawData().put("from", "");
        rawTransaction.getRawData().put("to", "");
        rawTransaction.getRawData().put("methodId", methodId);
        rawTransaction.getRawData().put("functionName", functionName);
        rawTransaction.getRawData().put("value", "0");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", tokenContract)
                        .append("tokenSymbol", tokenSymbol)
                        .append("tokenName", tokenName)
                        .append("tokenDecimal", "18")
                        .append("from", senderAddress)
                        .append("to", WALLET)
                        .append("value", "1000000000000000000")
        )).append("internalTransfers", List.of()));
        return rawTransaction;
    }

    private static String cowEthFlowCreateOrderInput(
            String buyToken,
            String receiver,
            String sellAmount,
            String buyAmount,
            String appData,
            String feeAmount,
            String validTo,
            boolean partiallyFillable,
            String quoteId
    ) {
        return "0x322bba21"
                + paddedAddress(buyToken)
                + paddedAddress(receiver)
                + paddedUint(sellAmount)
                + paddedUint(buyAmount)
                + paddedBytes32(appData)
                + paddedUint(feeAmount)
                + paddedUint(validTo)
                + paddedBool(partiallyFillable)
                + paddedInt64(quoteId);
    }

    private static String cowTradeLogData(String orderDigest) {
        String digest = strip0x(orderDigest);
        return "0x"
                + paddedAddress(TOKEN_A)
                + paddedAddress(TOKEN_B)
                + paddedUint("27638811423349461")
                + paddedUint("22742145033450122")
                + paddedUint("0")
                + paddedUint("192")
                + paddedUint("32")
                + digest;
    }

    private static String multicallInput(String... subcalls) {
        List<String> normalizedSubcalls = java.util.Arrays.stream(subcalls)
                .map(OnChainClassifierTest::strip0x)
                .map(value -> value.length() % 2 == 0 ? value : value + "0")
                .toList();
        StringBuilder payload = new StringBuilder();
        payload.append(paddedUint("32"));
        payload.append(paddedUint(String.valueOf(normalizedSubcalls.size())));

        List<String> encodedElements = new java.util.ArrayList<>(normalizedSubcalls.size());
        int currentOffsetBytes = 32 * normalizedSubcalls.size();
        StringBuilder offsets = new StringBuilder();
        for (String subcall : normalizedSubcalls) {
            int byteLength = subcall.length() / 2;
            int paddedBytes = ((byteLength + 31) / 32) * 32;
            String encoded = paddedUint(String.valueOf(byteLength))
                    + subcall
                    + "0".repeat((paddedBytes * 2) - subcall.length());
            offsets.append(paddedUint(String.valueOf(currentOffsetBytes)));
            currentOffsetBytes += encoded.length() / 2;
            encodedElements.add(encoded);
        }

        payload.append(offsets);
        encodedElements.forEach(payload::append);
        return "0xac9650d8" + payload;
    }

    private static String oneInchSwapInput(
            String executor,
            String srcToken,
            String dstToken,
            String srcReceiver,
            String dstReceiver,
            String amount,
            String minReturnAmount
    ) {
        return "0x07ed2379"
                + paddedAddress(executor)
                + paddedAddress(srcToken)
                + paddedAddress(dstToken)
                + paddedAddress(srcReceiver)
                + paddedAddress(dstReceiver)
                + paddedUint(amount)
                + paddedUint(minReturnAmount)
                + paddedUint("0")
                + paddedUint("288")
                + paddedUint("0");
    }

    private static String paddedAddress(String address) {
        return "%064x".formatted(new java.math.BigInteger(strip0x(address), 16));
    }

    private static String paddedUint(String value) {
        return "%064x".formatted(new java.math.BigInteger(value));
    }

    private static String paddedInt64(String value) {
        return "%064x".formatted(new java.math.BigInteger(value));
    }

    private static String paddedBytes32(String value) {
        String normalized = strip0x(value);
        return "0".repeat(Math.max(0, 64 - normalized.length())) + normalized;
    }

    private static String paddedBool(boolean value) {
        return value ? "0".repeat(63) + "1" : "0".repeat(64);
    }

    private static String strip0x(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }

    private static String asciiHex(String value) {
        StringBuilder hex = new StringBuilder();
        for (char character : value.toCharArray()) {
            hex.append(String.format("%02x", (int) character));
        }
        return hex.toString();
    }
}
