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
import com.walletradar.ingestion.pipeline.classification.support.LpStakingWrapperResolver;
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
    private final LpStakingWrapperResolver lpStakingWrapperResolver;

    public LpRegistryClassifier(
            ProtocolRegistryService protocolRegistryService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            LpStakingWrapperResolver lpStakingWrapperResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
        this.lpStakingWrapperResolver = lpStakingWrapperResolver;
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

        // Use the ORIGINAL (pre-cancellation) movement legs for corrId derivation so that
        // the eqbPENDLE-LPT token is still visible and produces the correct "pendle-lpt" market ID.
        // Using effectiveLegs would only have the plain PENDLE reward token, giving a wrong corrId.
        String corrId = PendleLpCorrelationSupport.correlationIdFromMovementLegs(context.view(), context.movementLegs());
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
                corrId
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
        NormalizedTransactionType type = LpPrincipalCloseEvidence.refineFinalExitType(
                context.view(),
                context.movementLegs(),
                rawType
        );
        List<String> pendingReasons = pendingClarificationReasons(context, entry, type);
        // The contract-keyed (tokenId-resolved) identity drives receipt-leg materialization: a receipt
        // leg is only emitted for a real position tokenId, never for the vault-style pool fallback.
        String receiptCorrelationId = resolveContractKeyedCorrelationId(context, type);
        String correlationId = receiptCorrelationId != null
                ? receiptCorrelationId
                : resolveVaultFallbackCorrelationId(context, entry, type);
        List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                context.view(),
                context.movementLegs(),
                type,
                receiptCorrelationId
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

    /**
     * The contract-keyed, tokenId-resolved CL position identity (or {@code null} when no tokenId
     * resolves). RC-5 (ADR-018): resolve the interacted position-manager contract, then canonicalize
     * a known LP-staking/farming wrapper to the underlying NFPM so a staked position keys to the
     * single {@code (network, NFPM, tokenId)} identity instead of a duplicate wrapper-keyed pool.
     */
    private String resolveContractKeyedCorrelationId(
            OnChainClassificationContext context,
            NormalizedTransactionType type
    ) {
        String resolvedContract = LpPositionCorrelationSupport.resolvePositionManagerContract(context.view());
        String canonicalContract = lpStakingWrapperResolver.canonicalPositionManager(
                context.view().networkId(),
                resolvedContract
        );
        return LpPositionCorrelationSupport.contractKeyedLifecycleCorrelationId(
                context.view(),
                type,
                canonicalContract
        );
    }

    /**
     * Pool-identity fallback for genuine vault-style LP contracts that have no NFT tokenId (e.g. Angle
     * vaults on Katana). RC-6: never produce a truncated-contract aggregate — use the FULL contract so
     * the entry and exit of a no-NFT vault key to the same per-contract pool. This identity is used
     * only as the decision's pool correlationId; it never materializes a receipt leg (that path is
     * reserved for a resolved tokenId). Mint-shapes stay PENDING_CLARIFICATION
     * ({@code requiresReceiptClarification}) so the minted tokenId is resolved downstream.
     */
    private String resolveVaultFallbackCorrelationId(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type
    ) {
        String resolvedContract = LpPositionCorrelationSupport.resolvePositionManagerContract(context.view());
        String canonicalContract = lpStakingWrapperResolver.canonicalPositionManager(
                context.view().networkId(),
                resolvedContract
        );
        // Accept POSITION_MANAGER entries directly, or POOL entries that declare an
        // underlyingPositionManager (e.g. Katana vbETH-vbUSDC pool → vault). In the latter
        // case canonicalContract has already been remapped to the vault POSITION_MANAGER address
        // by LpStakingWrapperResolver.canonicalPositionManager.
        boolean isPositionManagerEntry = entry.role() == com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole.POSITION_MANAGER;
        boolean isPoolWithUnderlyingManager = entry.underlyingPositionManager() != null && !entry.underlyingPositionManager().isBlank();
        // When the raw tx's `to` address is an intermediate router (not in registry), the
        // contract resolution via the view falls back to null/router address and canonicalContract
        // is unresolved. For POOL entries with underlyingPositionManager, retry resolution using
        // the entry's own pool address — the staking wrapper resolver maps it to the vault.
        if (isPoolWithUnderlyingManager && (canonicalContract == null || canonicalContract.isBlank()
                || canonicalContract.equalsIgnoreCase(resolvedContract))) {
            String entryCanonical = lpStakingWrapperResolver.canonicalPositionManager(
                    context.view().networkId(),
                    entry.contractAddress()
            );
            if (entryCanonical != null && !entryCanonical.isBlank()) {
                canonicalContract = entryCanonical;
            }
        }
        if ((isPositionManagerEntry || isPoolWithUnderlyingManager)
                && LpPositionCorrelationSupport.supportsLpPositionCorrelation(type)
                && !LpPositionCorrelationSupport.requiresReceiptClarification(context.view(), type)
                && canonicalContract != null && !canonicalContract.isBlank()) {
            String networkId = context.view().networkId() == null
                    ? "unknown"
                    : context.view().networkId().name().toLowerCase(java.util.Locale.ROOT);
            return "lp-position:" + networkId + ":" + canonicalContract + ":vault";
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
        } else if (LpPositionCorrelationSupport.requiresVaultReceiptClarification(context.view(), type)) {
            // Vault-wrapped LP_ENTRY (e.g. routeSingle on Angle vault) with non-standard selector:
            // the underlying NFPM's ERC-721 mint event is only visible in the full receipt.
            // Without it we cannot produce an NFT-keyed correlationId, so defer to clarification.
            reasons.add(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code());
        }
        return List.copyOf(reasons);
    }
}
