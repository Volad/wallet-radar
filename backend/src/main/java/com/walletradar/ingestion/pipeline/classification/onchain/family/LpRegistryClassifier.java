package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.support.BlockScoutNativeSettlementClarificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.ingestion.pipeline.classification.support.RegistryMethodDispatchSupport;
import com.walletradar.ingestion.pipeline.classification.support.ParityFlowSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LpRegistryClassifier implements OnChainFamilyClassifier {

    private static final String PENDLE_REWARD_DISTRIBUTOR = "0x70f61901658aafb7ae57da0c30695ce4417e72b9";
    private static final String PENDLE_ZAP_OUT_SINGLE_TOKEN_SELECTOR = "0x8b284b0e";

    private final ProtocolRegistryService protocolRegistryService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public LpRegistryClassifier(
            ProtocolRegistryService protocolRegistryService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 420;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                context.view().networkId(),
                context.view().toAddress()
        );
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (entry.get().specialHandler() != null) {
            return Optional.empty();
        }

        Optional<ClassificationDecision> pendleBundle = classifyPendleRewardDistributorBundle(context, entry.get());
        if (pendleBundle.isPresent()) {
            return pendleBundle;
        }

        if (entry.get().role() == com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole.POSITION_MANAGER) {
            NormalizedTransactionType type = LpRegistryFamilySupport.resolvePositionManagerType(
                    context.view(),
                    context.movementLegs(),
                    protocolRegistryService
            );
            if (type != null) {
                List<String> pendingReasons = pendingClarificationReasons(context, entry.get(), type);
                String correlationId = LpPositionCorrelationSupport.correlationId(
                        context.view(),
                        type,
                        entry.get().protocolName()
                );
                if (!pendingReasons.isEmpty()) {
                    return Optional.of(RegistryDecisionSupport.registryResult(
                            context.view(),
                            entry.get(),
                            type,
                            NormalizedTransactionStatus.PENDING_CLARIFICATION,
                            ParityFlowSupport.flows(context.view(), context.movementLegs(), type),
                            pendingReasons,
                            correlationId
                    ));
                }
                return Optional.of(RegistryDecisionSupport.registryResult(
                        context.view(),
                        entry.get(),
                        type,
                        context.movementLegs(),
                        correlationId
                ));
            }

            if (RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType multicallType =
                        LpPositionLifecycleSupport.resolvePositionManagerMulticallType(context.view(), context.movementLegs());
                if (multicallType != null) {
                    List<String> pendingReasons = pendingClarificationReasons(context, entry.get(), multicallType);
                    String correlationId = LpPositionCorrelationSupport.correlationId(
                            context.view(),
                            multicallType,
                            entry.get().protocolName()
                    );
                    if (!pendingReasons.isEmpty()) {
                        return Optional.of(RegistryDecisionSupport.registryResult(
                                context.view(),
                                entry.get(),
                                multicallType,
                                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                                ParityFlowSupport.flows(context.view(), context.movementLegs(), multicallType),
                                pendingReasons,
                                correlationId
                        ));
                    }
                    return Optional.of(RegistryDecisionSupport.registryResult(
                            context.view(),
                            entry.get(),
                            multicallType,
                            context.movementLegs(),
                            correlationId
                    ));
                }
            }
        }

        if (LpPositionLifecycleSupport.isDexStakeContract(entry.get())) {
            NormalizedTransactionType type =
                    LpPositionLifecycleSupport.resolveDexStakeContractType(context.view(), context.movementLegs());
            if (type != null) {
                String correlationId = LpPositionCorrelationSupport.correlationId(
                        context.view(),
                        type,
                        entry.get().protocolName()
                );
                return Optional.of(RegistryDecisionSupport.registryResult(
                        context.view(),
                        entry.get(),
                        type,
                        context.movementLegs(),
                        correlationId
                ));
            }

            if (RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType multicallType =
                        LpPositionLifecycleSupport.resolveDexStakeContractMulticallType(context.view(), context.movementLegs());
                if (multicallType != null) {
                    String correlationId = LpPositionCorrelationSupport.correlationId(
                            context.view(),
                            multicallType,
                            entry.get().protocolName()
                    );
                    return Optional.of(RegistryDecisionSupport.registryResult(
                            context.view(),
                            entry.get(),
                            multicallType,
                            context.movementLegs(),
                            correlationId
                    ));
                }
            }
        }

        return Optional.empty();
    }

    private Optional<ClassificationDecision> classifyPendleRewardDistributorBundle(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry
    ) {
        if (!"Pendle".equalsIgnoreCase(entry.protocolName())
                || !PENDLE_REWARD_DISTRIBUTOR.equalsIgnoreCase(entry.contractAddress())
                || !PENDLE_ZAP_OUT_SINGLE_TOKEN_SELECTOR.equals(context.view().methodId())
                || !"zapoutv3singletoken".equals(LpRegistryFamilySupport.functionKey(context.view().functionName()))) {
            return Optional.empty();
        }

        List<RawLeg> effectiveLegs = LpRegistryFamilySupport.removeExactSelfCancelingPairs(context.movementLegs());
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (RawLeg leg : effectiveLegs) {
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            if (leg.fee()) {
                flow.setRole(NormalizedLegRole.FEE);
            } else if (leg.quantityDelta().signum() > 0 && "PENDLE".equalsIgnoreCase(leg.assetSymbol())) {
                flow.setRole(NormalizedLegRole.BUY);
            } else {
                flow.setRole(NormalizedLegRole.TRANSFER);
            }
            flows.add(flow);
        }

        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                NormalizedTransactionType.LP_EXIT,
                flows,
                List.of()
        ));
    }

    private List<String> pendingClarificationReasons(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type
    ) {
        List<String> reasons = new ArrayList<>();
        if (BlockScoutNativeSettlementClarificationSupport.requiresReceiptClarification(
                context.view(),
                context.movementLegs(),
                type,
                nativeAssetSymbolResolver
        )) {
            reasons.add(ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code());
        }
        if (LpPositionCorrelationSupport.requiresReceiptClarification(context.view(), type)) {
            reasons.add(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code());
        }
        return List.copyOf(reasons);
    }
}
