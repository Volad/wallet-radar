package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.ingestion.pipeline.classification.support.RegistryMethodDispatchSupport;
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

    public LpRegistryClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
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
                return Optional.of(RegistryDecisionSupport.registryResult(
                        context.view(),
                        entry.get(),
                        type,
                        context.movementLegs()
                ));
            }

            if (RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType multicallType =
                        LpPositionLifecycleSupport.resolvePositionManagerMulticallType(context.view(), context.movementLegs());
                if (multicallType != null) {
                    return Optional.of(RegistryDecisionSupport.registryResult(
                            context.view(),
                            entry.get(),
                            multicallType,
                            context.movementLegs()
                    ));
                }
            }
        }

        if (LpPositionLifecycleSupport.isDexStakeContract(entry.get())) {
            NormalizedTransactionType type =
                    LpPositionLifecycleSupport.resolveDexStakeContractType(context.view(), context.movementLegs());
            if (type != null) {
                return Optional.of(RegistryDecisionSupport.registryResult(
                        context.view(),
                        entry.get(),
                        type,
                        context.movementLegs()
                ));
            }

            if (RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType multicallType =
                        LpPositionLifecycleSupport.resolveDexStakeContractMulticallType(context.view(), context.movementLegs());
                if (multicallType != null) {
                    return Optional.of(RegistryDecisionSupport.registryResult(
                            context.view(),
                            entry.get(),
                            multicallType,
                            context.movementLegs()
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
}
