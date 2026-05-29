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
import com.walletradar.ingestion.pipeline.classification.lp.LpClassificationFlowSupport;
import com.walletradar.ingestion.pipeline.classification.lp.PendleLpCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.BlockScoutNativeSettlementClarificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPrincipalCloseEvidence;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
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
                return Optional.of(buildPositionManagerDecision(context, entry.get(), type));
            }

            if (RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType multicallType =
                        LpPositionLifecycleSupport.resolvePositionManagerMulticallType(context.view(), context.movementLegs());
                if (multicallType != null) {
                    return Optional.of(buildPositionManagerDecision(context, entry.get(), multicallType));
                }
            }

            // Fallback for vault-style POSITION_MANAGER contracts with non-standard method selectors
            // (e.g. Angle vbETH-vbUSDC vault on Katana) that have no NFT tokenId and don't use
            // the known Uniswap V3 selectors. Only applies when the contract does NOT require
            // method-aware dispatch (multicall/overloaded contracts must stay NEEDS_REVIEW on
            // unknown inner calls to avoid mis-classification).
            if (!RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType vaultType = LpRegistryFamilySupport.resolveByMovementLegsOnly(context.movementLegs());
                if (vaultType != null) {
                    return Optional.of(buildPositionManagerDecision(context, entry.get(), vaultType));
                }
            }
        }

        if (LpPositionLifecycleSupport.isDexStakeContract(entry.get())) {
            NormalizedTransactionType type =
                    LpPositionLifecycleSupport.resolveDexStakeContractType(context.view(), context.movementLegs());
            if (type != null) {
                return Optional.of(buildStakeContractDecision(context, entry.get(), type));
            }

            if (RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
                NormalizedTransactionType multicallType =
                        LpPositionLifecycleSupport.resolveDexStakeContractMulticallType(context.view(), context.movementLegs());
                if (multicallType != null) {
                    return Optional.of(buildStakeContractDecision(context, entry.get(), multicallType));
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
                OnChainClassificationSupport.initialStatus(
                        context.view(),
                        NormalizedTransactionType.LP_EXIT,
                        entry.confidence()
                ),
                flows,
                List.of(),
                PendleLpCorrelationSupport.correlationIdFromMovementLegs(context.view(), effectiveLegs)
        ));
    }

    private ClassificationDecision buildPositionManagerDecision(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type
    ) {
        return buildRegistryLpDecision(context, entry, type);
    }

    private ClassificationDecision buildStakeContractDecision(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type
    ) {
        return buildRegistryLpDecision(context, entry, type);
    }

    private ClassificationDecision buildRegistryLpDecision(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType rawType
    ) {
        NormalizedTransactionType type = LpPrincipalCloseEvidence.refineLifecycleType(
                context.view(),
                context.movementLegs(),
                rawType
        );
        List<String> pendingReasons = pendingClarificationReasons(context, entry, type);
        String correlationId = resolveCorrelationId(context, entry, type);
        List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                context.view(),
                context.movementLegs(),
                type,
                entry.protocolName()
        );
        if (!pendingReasons.isEmpty()) {
            return RegistryDecisionSupport.registryResult(
                    context.view(),
                    entry,
                    type,
                    NormalizedTransactionStatus.PENDING_CLARIFICATION,
                    flows,
                    pendingReasons,
                    correlationId
            );
        }
        return RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, entry.confidence()),
                flows,
                List.of(),
                correlationId
        );
    }

    private String resolveCorrelationId(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type
    ) {
        String corrId = LpPositionCorrelationSupport.lifecycleCorrelationId(
                context.view(),
                type,
                entry.protocolName()
        );
        if (corrId != null) {
            return corrId;
        }
        // Fallback for vault-style LP contracts that have no NFT tokenId (e.g. Angle vaults on Katana).
        // Only apply when the tx does NOT look like an NFT-minting LP entry that needs clarification —
        // i.e., skip the fallback when requiresReceiptClarification is true (those should stay PENDING_CLARIFICATION
        // with null correlationId so the NFT tokenId can be resolved via the clarification pipeline).
        if (entry.role() == com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole.POSITION_MANAGER
                && LpPositionCorrelationSupport.supportsLpPositionCorrelation(type)
                && !LpPositionCorrelationSupport.requiresReceiptClarification(context.view(), type)
                && entry.contractAddress() != null && !entry.contractAddress().isBlank()) {
            String networkId = context.view().networkId() == null
                    ? "unknown"
                    : context.view().networkId().name().toLowerCase(java.util.Locale.ROOT);
            String protocolSlug = entry.protocolName() == null
                    ? "unknown"
                    : entry.protocolName().trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "-");
            String contractSuffix = entry.contractAddress().toLowerCase(java.util.Locale.ROOT)
                    .replaceFirst("^0x", "").substring(0, Math.min(16, entry.contractAddress().length()));
            return "lp-position:" + networkId + ":" + protocolSlug + ":" + contractSuffix;
        }
        return null;
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
