package com.walletradar.application.normalization.pipeline.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.config.NativeSettlementRecoveryProperties;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.support.NativeSettlementClarificationTrigger;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.AdminConfigClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.AaveReceiptShapeClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.BridgeStartClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.BridgeMethodAwareClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.BridgeSettlementClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.CompoundCometClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.DefaultClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.EulerEvcClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.FailedExecutionClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.FluidVaultClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.FunctionNameClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.GmxLpClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.HeuristicClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LendingClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LendingSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LpFeeClaimClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LpClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LpSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.MethodIdClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.NonEconomicClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.OnChainClassificationInsertionPoint;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.OnChainFamilyClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.PreSpamAdminConfigClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.PreSpamUnknownClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.RewardRouteClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.ResolvedWarningAdminConfigClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.RoutedAggregatorSendClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.SpamClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.SpoofTokenClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.StakingClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.SwapClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.SwapSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.SwapRegistryClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.TradingClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.TransferClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LendingRegistryClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.LpRegistryClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.VaultClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.VaultSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.WrappedNativeClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.ZkSyncAaveGatewayClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.ZkSyncAcrossRoutedBridgeClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.balancer.BalancerProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.cow.CowSwapProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.euler.EulerProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.gmx.GmxProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.morpho.MorphoProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.pendle.PendleProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryService;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticService;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.registry.MethodAwareRegistryReviewClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.registry.RegistryDirectTypeClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.registry.SpecialHandlerRegistryReviewClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.resolv.ResolvProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.LpStakingWrapperResolver;
import com.walletradar.application.normalization.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.session.application.TrackedWalletLookupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates on-chain classification through context building, protocol semantics, and staged family execution.
 */
@Component
public class OnChainClassifier {

    private final OnChainClassificationContextFactory contextFactory;
    private final List<OnChainFamilyClassifier> earlyGuardFamilyClassifiers;
    private final List<OnChainFamilyClassifier> preEconomicReviewFamilyClassifiers;
    private final List<OnChainFamilyClassifier> preProtocolReviewFamilyClassifiers;
    private final List<OnChainFamilyClassifier> protocolLifecycleFamilyClassifiers;
    private final List<OnChainFamilyClassifier> preSpamReviewFamilyClassifiers;
    private final List<OnChainFamilyClassifier> postSpamReviewFamilyClassifiers;
    private final List<OnChainFamilyClassifier> finalFallbackFamilyClassifiers;
    private final ClassificationDecisionMapper decisionMapper;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;
    private final NativeSettlementRecoveryProperties nativeSettlementRecoveryProperties;

    @Autowired
    public OnChainClassifier(
            OnChainClassificationContextFactory contextFactory,
            List<OnChainFamilyClassifier> familyClassifiers,
            ClassificationDecisionMapper decisionMapper,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            NativeSettlementRecoveryProperties nativeSettlementRecoveryProperties
    ) {
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
        this.nativeSettlementRecoveryProperties = nativeSettlementRecoveryProperties;
        this.contextFactory = contextFactory;
        this.earlyGuardFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.EARLY_GUARDS);
        this.preEconomicReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PRE_ECONOMIC_REVIEW);
        this.preProtocolReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW);
        this.protocolLifecycleFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE);
        this.preSpamReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PRE_SPAM_REVIEW);
        this.postSpamReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.POST_SPAM_REVIEW);
        this.finalFallbackFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.FINAL_FALLBACK);
        this.decisionMapper = decisionMapper;
    }

    /**
     * Convenience constructor used by tests that instantiate the classifier directly.
     */
    public OnChainClassifier(
            ProtocolRegistryService protocolRegistryService,
            TrackedWalletLookupService trackedWalletLookupService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
        this.nativeSettlementRecoveryProperties = new NativeSettlementRecoveryProperties();
        MovementLegExtractor movementLegExtractor = new MovementLegExtractor(nativeAssetSymbolResolver);
        ProtocolResourceLoader protocolResourceLoader = new ProtocolResourceLoader(new ObjectMapper());
        this.contextFactory = new OnChainClassificationContextFactory(
                new ProtocolDiscoveryService(protocolRegistryService, protocolResourceLoader),
                new ProtocolSemanticService(List.of(
                        new ResolvProtocolSemanticClassifier(),
                        new BalancerProtocolSemanticClassifier(protocolResourceLoader),
                        new CowSwapProtocolSemanticClassifier(protocolResourceLoader),
                        new GmxProtocolSemanticClassifier(protocolRegistryService, protocolResourceLoader),
                        new EulerProtocolSemanticClassifier(protocolResourceLoader),
                        new MorphoProtocolSemanticClassifier(protocolResourceLoader),
                        new PendleProtocolSemanticClassifier(protocolResourceLoader)
                )),
                movementLegExtractor
        );
        LpStakingWrapperResolver lpStakingWrapperResolver = new LpStakingWrapperResolver(protocolRegistryService);
        List<OnChainFamilyClassifier> familyClassifiers = List.of(
                new SpoofTokenClassifier(),
                new FailedExecutionClassifier(),
                new AdminConfigClassifier(),
                new WrappedNativeClassifier(nativeAssetSymbolResolver),
                new BridgeSettlementClassifier(protocolRegistryService),
                new RewardRouteClassifier(protocolRegistryService),
                new BridgeStartClassifier(protocolRegistryService, nativeAssetSymbolResolver),
                new TransferClassifier(protocolRegistryService),
                new LpClassifier(protocolRegistryService, lpStakingWrapperResolver),
                new LpFeeClaimClassifier(),
                new RoutedAggregatorSendClassifier(protocolRegistryService),
                new BridgeMethodAwareClassifier(protocolRegistryService),
                new ResolvedWarningAdminConfigClassifier(),
                new SwapClassifier(),
                new TradingClassifier(),
                new GmxLpClassifier(),
                new StakingClassifier(),
                new DefaultClassifier(),
                new PreSpamUnknownClassifier(),
                new PreSpamAdminConfigClassifier(),
                new SpamClassifier(protocolRegistryService),
                new NonEconomicClassifier(),
                new ZkSyncAcrossRoutedBridgeClassifier(),
                new AaveReceiptShapeClassifier(protocolRegistryService),
                new ZkSyncAaveGatewayClassifier(),
                new SwapSemanticClassifier(),
                new LpSemanticClassifier(nativeAssetSymbolResolver),
                new LendingSemanticClassifier(),
                new VaultSemanticClassifier(),
                new CompoundCometClassifier(protocolRegistryService),
                new FluidVaultClassifier(protocolRegistryService),
                new SwapRegistryClassifier(protocolRegistryService, nativeAssetSymbolResolver),
                new LpRegistryClassifier(protocolRegistryService, nativeAssetSymbolResolver, lpStakingWrapperResolver),
                new LendingRegistryClassifier(protocolRegistryService, protocolResourceLoader),
                new VaultClassifier(protocolRegistryService),
                new SpecialHandlerRegistryReviewClassifier(),
                new MethodAwareRegistryReviewClassifier(protocolRegistryService),
                new RegistryDirectTypeClassifier(protocolRegistryService),
                new MethodIdClassifier(),
                new FunctionNameClassifier(protocolRegistryService, nativeAssetSymbolResolver),
                new EulerEvcClassifier(),
                new LendingClassifier(),
                new HeuristicClassifier(protocolRegistryService, trackedWalletLookupService, nativeAssetSymbolResolver)
        );
        this.earlyGuardFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.EARLY_GUARDS);
        this.preEconomicReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PRE_ECONOMIC_REVIEW);
        this.preProtocolReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW);
        this.protocolLifecycleFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE);
        this.preSpamReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.PRE_SPAM_REVIEW);
        this.postSpamReviewFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.POST_SPAM_REVIEW);
        this.finalFallbackFamilyClassifiers = classifiersFor(familyClassifiers, OnChainClassificationInsertionPoint.FINAL_FALLBACK);
        this.decisionMapper = new ClassificationDecisionMapper();
    }

    public OnChainClassificationResult classify(RawTransaction rawTransaction) {
        OnChainClassificationContext context = contextFactory.create(rawTransaction);
        Optional<ClassificationDecision> earlyGuardDecision = firstDecision(earlyGuardFamilyClassifiers, context);
        if (earlyGuardDecision.isPresent()) {
            return finalizeDecision(earlyGuardDecision.get(), context);
        }

        Optional<ClassificationDecision> preEconomicReviewDecision =
                firstDecision(preEconomicReviewFamilyClassifiers, context);
        if (preEconomicReviewDecision.isPresent()) {
            return finalizeDecision(preEconomicReviewDecision.get(), context);
        }

        Optional<ClassificationDecision> preProtocolReviewDecision = firstDecision(preProtocolReviewFamilyClassifiers, context);
        if (preProtocolReviewDecision.isPresent()) {
            return finalizeDecision(preProtocolReviewDecision.get(), context);
        }

        Optional<ClassificationDecision> protocolLifecycleDecision = firstDecision(protocolLifecycleFamilyClassifiers, context);
        if (protocolLifecycleDecision.isPresent()) {
            return finalizeDecision(protocolLifecycleDecision.get(), context);
        }

        Optional<ClassificationDecision> preSpamReviewDecision = firstDecision(preSpamReviewFamilyClassifiers, context);
        if (preSpamReviewDecision.isPresent()) {
            return finalizeDecision(preSpamReviewDecision.get(), context);
        }

        Optional<ClassificationDecision> postSpamReviewDecision = firstDecision(postSpamReviewFamilyClassifiers, context);
        if (postSpamReviewDecision.isPresent()) {
            return finalizeDecision(postSpamReviewDecision.get(), context);
        }

        Optional<ClassificationDecision> finalFallbackDecision = firstDecision(finalFallbackFamilyClassifiers, context);
        if (finalFallbackDecision.isPresent()) {
            return finalizeDecision(finalFallbackDecision.get(), context);
        }
        throw new IllegalStateException("Final fallback classifiers must produce a terminal decision");
    }

    private OnChainClassificationResult finalizeDecision(ClassificationDecision decision, OnChainClassificationContext context) {
        return decisionMapper.toResult(applyNativeSettlementClarification(decision, context));
    }

    /**
     * ADR-044 D3: route a native-output {@code SWAP} / {@code LP_EXIT*} / {@code LP_FEE_CLAIM} /
     * {@code UNWRAP} that would otherwise finalize without its native inbound leg to the existing
     * full-receipt clarification path so the WETH {@code Withdrawal} evidence is fetched. Gated by
     * the per-chain {@code native-settlement-recovery} flag (defaults off); a no-op otherwise.
     */
    private ClassificationDecision applyNativeSettlementClarification(
            ClassificationDecision decision,
            OnChainClassificationContext context
    ) {
        if (decision == null
                || context == null
                || !nativeSettlementRecoveryProperties.isEnabledForChain(context.view().networkId())) {
            return decision;
        }
        NormalizedTransactionStatus status = decision.status();
        // Only downgrade decisions that would otherwise silently finalize without the native leg.
        if (status != NormalizedTransactionStatus.CONFIRMED
                && status != NormalizedTransactionStatus.PENDING_PRICE) {
            return decision;
        }
        if (!NativeSettlementClarificationTrigger.requiresReceiptClarification(
                context.view(), context.movementLegs(), decision.type(), nativeAssetSymbolResolver)) {
            return decision;
        }
        List<String> reasons = decision.missingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(decision.missingDataReasons());
        String reason = ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code();
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
        return new ClassificationDecision(
                decision.type(),
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                decision.classifiedBy(),
                decision.confidence(),
                decision.flows(),
                List.copyOf(reasons),
                decision.correlationId(),
                decision.continuityCandidate(),
                decision.matchedCounterparty(),
                decision.excludedFromAccounting(),
                decision.accountingExclusionReason(),
                decision.protocolName(),
                decision.protocolVersion()
        );
    }

    private static List<OnChainFamilyClassifier> classifiersFor(
            List<OnChainFamilyClassifier> familyClassifiers,
            OnChainClassificationInsertionPoint insertionPoint
    ) {
        return familyClassifiers.stream()
                .filter(classifier -> classifier.insertionPoint() == insertionPoint)
                .sorted(Comparator.comparingInt(OnChainFamilyClassifier::getOrder))
                .toList();
    }

    private static Optional<ClassificationDecision> firstDecision(
            List<OnChainFamilyClassifier> familyClassifiers,
            OnChainClassificationContext context
    ) {
        return familyClassifiers.stream()
                .map(classifier -> classifier.classify(context))
                .flatMap(Optional::stream)
                .findFirst();
    }
}
