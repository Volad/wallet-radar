package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.special.ProtocolSpecialHandlerDispatcher;
import com.walletradar.ingestion.pipeline.classification.special.SpecialHandlerResult;
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
    private ProtocolSpecialHandlerDispatcher protocolSpecialHandlerDispatcher;
    @Mock
    private TrackedWalletLookupService trackedWalletLookupService;

    private OnChainClassifier classifier;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());
        classifier = new OnChainClassifier(
                protocolRegistryService,
                protocolSpecialHandlerDispatcher,
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
        when(protocolSpecialHandlerDispatcher.dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(SpecialHandlerResult.unsupported());

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
    @DisplayName("tracked counterparty yields INTERNAL_TRANSFER with transfer roles")
    void trackedCounterpartyYieldsInternalTransfer() {
        RawTransaction rawTransaction = baseRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("to", COUNTERPARTY);
        rawTransaction.getRawData().put("value", "1000000000000000000");
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, COUNTERPARTY))
                .thenReturn(Optional.empty());
        when(trackedWalletLookupService.contains(COUNTERPARTY))
                .thenReturn(true);

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(result.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(result.flows())
                .allSatisfy(flow -> assertThat(flow.getRole()).isIn(NormalizedLegRole.TRANSFER, NormalizedLegRole.FEE));
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
        verifyNoInteractions(protocolRegistryService, protocolSpecialHandlerDispatcher);
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

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_INBOUND);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(result.missingDataReasons()).isEmpty();
    }

    @Test
    @DisplayName("reward-like inbound without verified distributor stays EXTERNAL_INBOUND with ambiguity metadata")
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

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_INBOUND);
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

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_INBOUND);
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
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
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
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("PROMO_SPAM_PHISHING");
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

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_INBOUND);
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
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
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
        verifyNoInteractions(protocolRegistryService);
        verifyNoInteractions(protocolSpecialHandlerDispatcher);
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
        verifyNoInteractions(protocolRegistryService);
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
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("FAILED_TRANSACTION");
        verifyNoInteractions(protocolRegistryService, protocolSpecialHandlerDispatcher);
    }

    @Test
    @DisplayName("failed transaction by isError bypasses downstream classification")
    void failedTransactionByIsErrorBypassesDownstreamClassification() {
        RawTransaction rawTransaction = tokenSwapRaw(NetworkId.ETHEREUM);
        rawTransaction.getRawData().put("isError", "1");

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(result.missingDataReasons()).containsExactly("FAILED_TRANSACTION");
        verifyNoInteractions(protocolRegistryService, protocolSpecialHandlerDispatcher);
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
        rawTransaction.getRawData().put("input", "0xb6b55f25000000000000000000000000");
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
        assertThat(result.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
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
        assertThat(signerResult.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(signerResult.missingDataReasons()).containsExactly("CLAIM_WITHOUT_MOVEMENT");
    }

    @Test
    @DisplayName("clarification full receipt batch with share inbound and principal outbound becomes lending deposit")
    void clarificationFullReceiptBatchWithShareInboundAndPrincipalOutboundBecomesLendingDeposit() {
        RawTransaction rawTransaction = baseRaw(NetworkId.AVALANCHE);
        rawTransaction.setTxHash("0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df");
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.getRawData().put("clarificationEvidence", new Document()
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
    @DisplayName("clarification full receipt burn-only cleanup family narrows to admin config")
    void clarificationFullReceiptBurnOnlyCleanupFamilyNarrowsToAdminConfig() {
        RawTransaction rawTransaction = baseRaw(NetworkId.OPTIMISM);
        rawTransaction.setTxHash("0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("input", "0xac9650d80000000000000000000000000000000000000000000000000000000042966c68");
        rawTransaction.getRawData().put("clarificationEvidence", new Document("receipt", new Document("logs", List.of(
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
}
