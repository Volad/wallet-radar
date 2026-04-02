package com.walletradar.ingestion.pipeline.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.onchain.family.AdminConfigClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.BridgeStartClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.BridgeMethodAwareClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.BridgeSettlementClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.DefaultClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.FailedExecutionClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.FunctionNameClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.GmxLpClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.HeuristicClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LendingClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LendingSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LpFeeClaimClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LpClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LpSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.MethodIdClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.NonEconomicClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.OnChainClassificationInsertionPoint;
import com.walletradar.ingestion.pipeline.classification.onchain.family.OnChainFamilyClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.PreSpamAdminConfigClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.PreSpamUnknownClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.RewardRouteClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.ResolvedWarningAdminConfigClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.RoutedAggregatorSendClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.SpamClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.StakingClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.SwapClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.SwapSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.SwapRegistryClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.TradingClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.TransferClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LendingRegistryClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.LpRegistryClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.VaultClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.VaultSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.family.WrappedNativeClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.balancer.BalancerProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.cow.CowSwapProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.euler.EulerProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.gmx.GmxProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.morpho.MorphoProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.pendle.PendleProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryService;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticService;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.registry.MethodAwareRegistryReviewClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.registry.RegistryDirectTypeClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.registry.SpecialHandlerRegistryReviewClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.resolv.ResolvProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    @Autowired
    public OnChainClassifier(
            OnChainClassificationContextFactory contextFactory,
            List<OnChainFamilyClassifier> familyClassifiers,
            ClassificationDecisionMapper decisionMapper
    ) {
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
        List<OnChainFamilyClassifier> familyClassifiers = List.of(
                new FailedExecutionClassifier(),
                new AdminConfigClassifier(),
                new WrappedNativeClassifier(nativeAssetSymbolResolver),
                new BridgeSettlementClassifier(protocolRegistryService),
                new RewardRouteClassifier(protocolRegistryService),
                new BridgeStartClassifier(protocolRegistryService, nativeAssetSymbolResolver),
                new TransferClassifier(protocolRegistryService),
                new LpClassifier(),
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
                new SwapSemanticClassifier(),
                new LpSemanticClassifier(),
                new LendingSemanticClassifier(),
                new VaultSemanticClassifier(),
                new SwapRegistryClassifier(protocolRegistryService, nativeAssetSymbolResolver),
                new LpRegistryClassifier(protocolRegistryService),
                new LendingRegistryClassifier(protocolRegistryService, protocolResourceLoader),
                new VaultClassifier(protocolRegistryService),
                new SpecialHandlerRegistryReviewClassifier(),
                new MethodAwareRegistryReviewClassifier(protocolRegistryService),
                new RegistryDirectTypeClassifier(protocolRegistryService),
                new MethodIdClassifier(),
                new FunctionNameClassifier(protocolRegistryService, nativeAssetSymbolResolver),
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
            return decisionMapper.toResult(earlyGuardDecision.get());
        }

        Optional<ClassificationDecision> preEconomicReviewDecision =
                firstDecision(preEconomicReviewFamilyClassifiers, context);
        if (preEconomicReviewDecision.isPresent()) {
            return decisionMapper.toResult(preEconomicReviewDecision.get());
        }

        Optional<ClassificationDecision> preProtocolReviewDecision = firstDecision(preProtocolReviewFamilyClassifiers, context);
        if (preProtocolReviewDecision.isPresent()) {
            return decisionMapper.toResult(preProtocolReviewDecision.get());
        }

        Optional<ClassificationDecision> protocolLifecycleDecision = firstDecision(protocolLifecycleFamilyClassifiers, context);
        if (protocolLifecycleDecision.isPresent()) {
            return decisionMapper.toResult(protocolLifecycleDecision.get());
        }

        Optional<ClassificationDecision> preSpamReviewDecision = firstDecision(preSpamReviewFamilyClassifiers, context);
        if (preSpamReviewDecision.isPresent()) {
            return decisionMapper.toResult(preSpamReviewDecision.get());
        }

        Optional<ClassificationDecision> postSpamReviewDecision = firstDecision(postSpamReviewFamilyClassifiers, context);
        if (postSpamReviewDecision.isPresent()) {
            return decisionMapper.toResult(postSpamReviewDecision.get());
        }

        Optional<ClassificationDecision> finalFallbackDecision = firstDecision(finalFallbackFamilyClassifiers, context);
        if (finalFallbackDecision.isPresent()) {
            return decisionMapper.toResult(finalFallbackDecision.get());
        }
        throw new IllegalStateException("Final fallback classifiers must produce a terminal decision");
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
