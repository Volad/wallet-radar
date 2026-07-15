package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.lp.LpClassificationFlowSupport;
import com.walletradar.application.normalization.pipeline.classification.lp.PendleLpCorrelationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.BlockScoutNativeSettlementClarificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.CalldataDecodingSupport;
import com.walletradar.application.normalization.pipeline.classification.support.LpExitFeeClarificationTrigger;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionCorrelationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.application.normalization.pipeline.classification.support.LpPrincipalCloseEvidence;
import com.walletradar.application.normalization.pipeline.classification.support.LpStakingWrapperResolver;
import com.walletradar.application.normalization.pipeline.classification.support.LpV4ExitFeeDecomposer;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RegistryMethodDispatchSupport;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class LpRegistryClassifier implements OnChainFamilyClassifier {

    private static final Logger log = LoggerFactory.getLogger(LpRegistryClassifier.class);

    private static final String PENDLE_REWARD_DISTRIBUTOR = "0x70f61901658aafb7ae57da0c30695ce4417e72b9";
    private static final String PENDLE_ZAP_OUT_SINGLE_TOKEN_SELECTOR = "0x8b284b0e";

    // C1 (R7): LFJ LBRouter selector allowlist — only addLiquidity and removeLiquidity are LP ops.
    // All other selectors (swaps, etc.) fall through to HEURISTIC via Optional.empty().
    private static final String LFJ_ADD_LIQUIDITY_SELECTOR = "0xa3c7271a";
    private static final String LFJ_REMOVE_LIQUIDITY_SELECTOR = "0xc22159b6";

    private final ProtocolRegistryService protocolRegistryService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;
    private final LpStakingWrapperResolver lpStakingWrapperResolver;
    private final LpV4ExitFeeDecomposer lpV4ExitFeeDecomposer;

    // C1 (R7): (networkId, min(tokenX,tokenY), max(tokenX,tokenY), binStep) → pairAddress.
    // Populated at startup by scanning all LFJ_LB_PAIR entries in the registry.
    private volatile Map<LfjPairKey, String> lfjPairIndex = Map.of();

    public LpRegistryClassifier(
            ProtocolRegistryService protocolRegistryService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            LpStakingWrapperResolver lpStakingWrapperResolver,
            LpV4ExitFeeDecomposer lpV4ExitFeeDecomposer
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
        this.lpStakingWrapperResolver = lpStakingWrapperResolver;
        this.lpV4ExitFeeDecomposer = lpV4ExitFeeDecomposer;
    }

    /**
     * C1 (R7): Builds the LFJ pair inverse index from all registered LFJ_LB_PAIR entries.
     * Key: (networkId, canonicalized tokenX, canonicalized tokenY, binStep) where token addresses
     * are sorted (min, max) for canonical ordering regardless of the order they appear in calldata.
     */
    @PostConstruct
    void buildLfjPairIndex() {
        Map<LfjPairKey, String> index = new HashMap<>();
        for (ProtocolRegistryEntry entry : protocolRegistryService.allEntries()) {
            if (entry.specialHandler() != ProtocolRegistrySpecialHandlerType.LFJ_LB_PAIR) {
                continue;
            }
            if (entry.tokenX() == null || entry.tokenY() == null || entry.binStep() == null) {
                log.warn("LFJ_LB_PAIR entry {} is missing tokenX/tokenY/binStep — skipping index build",
                        entry.contractAddress());
                continue;
            }
            for (NetworkId networkId : entry.networks()) {
                LfjPairKey key = LfjPairKey.of(networkId, entry.tokenX(), entry.tokenY(), entry.binStep());
                String existing = index.put(key, entry.contractAddress());
                if (existing != null && !existing.equalsIgnoreCase(entry.contractAddress())) {
                    log.warn("LFJ pair key collision on network={} for pair={} vs {} — keeping first",
                            networkId, existing, entry.contractAddress());
                    index.put(key, existing);
                }
            }
        }
        this.lfjPairIndex = Map.copyOf(index);
        log.info("LFJ pair index built: {} entries", index.size());
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        // C1 (R7): Moved from POST_SPAM_REVIEW to PRE_PROTOCOL_REVIEW so that special-handler entries
        // (e.g. LFJ_LB_ROUTER) are handled by LpRegistryClassifier before
        // SpecialHandlerRegistryReviewClassifier (order +199) can intercept them with a generic
        // "HANDLER_UNSUPPORTED_METHOD" PENDING_REVIEW classification.
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        // Order 190: after LpSemanticClassifier (+151) and AaveReceiptShapeClassifier (+130) but
        // before SpecialHandlerRegistryReviewClassifier (+199) and LpClassifier (+200).
        return Ordered.HIGHEST_PRECEDENCE + 190;
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
            ProtocolRegistrySpecialHandlerType sh = entry.get().specialHandler();
            // R6a: Balancer V3 Vault classification is handled here, not in the semantic layer.
            if (sh == ProtocolRegistrySpecialHandlerType.BALANCER_V3_VAULT) {
                return classifyBalancerV3VaultTx(context, entry.get());
            }
            // R7: LFJ Liquidity Book pair-level LP (direct pair interaction).
            if (sh == ProtocolRegistrySpecialHandlerType.LFJ_LB_PAIR) {
                return classifyLfjLbPairTx(context, entry.get());
            }
            // C1 (R7): LFJ LBRouter — decode tokenX/tokenY/binStep from calldata, resolve pair.
            if (sh == ProtocolRegistrySpecialHandlerType.LFJ_LB_ROUTER) {
                return classifyLfjLbRouterTx(context, entry.get());
            }
            return Optional.empty();
        }

        Optional<ClassificationDecision> pendleBundle = classifyPendleRewardDistributorBundle(context, entry.get());
        if (pendleBundle.isPresent()) {
            return pendleBundle;
        }

        if (entry.get().role() == com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole.POSITION_MANAGER) {
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
        // R6a: Balancer V3 pool-level identity for gauge/Aura STAKE_CONTRACTs whose
        // underlyingPositionManager points to a known Balancer V3 BPT pool.
        String balancerV3CorrelationId = receiptCorrelationId == null
                ? resolveBalancerV3PoolCorrelationId(context, entry)
                : null;
        String correlationId = receiptCorrelationId != null
                ? receiptCorrelationId
                : balancerV3CorrelationId != null
                ? balancerV3CorrelationId
                : resolveVaultFallbackCorrelationId(context, entry, type);
        // R4: for V4/Infinity exits, pre-compute fee fractions via the V4 decomposer so the
        // materializer can split principal vs LP_FEE_INCOME without visiting liquiditypools.enrichment.
        Map<String, BigDecimal> v4FeeFractions = null;
        if (lpV4ExitFeeDecomposer != null
                && LpExitFeeClarificationTrigger.isV4DecreaseLiquidity(context.view())
                && LpV4ExitFeeDecomposer.hasModifyLiquidityEvidence(context.view())) {
            v4FeeFractions = lpV4ExitFeeDecomposer.feeFractionsForContracts(context.view())
                    .orElse(null);
        }
        // For Balancer V3 gauge STAKE/UNSTAKE (receiptCorrelationId is null, balancerV3CorrelationId
        // is set), pass the BPT-pool correlation ID to the materializer so it can rewrite raw BPT
        // flows to canonical LP-RECEIPT legs. For all other cases keep receiptCorrelationId so that
        // PancakeSwap/Uniswap V3 NFT positions and vault-fallback paths are unaffected.
        String flowsCorrelationId = (receiptCorrelationId == null && balancerV3CorrelationId != null)
                ? balancerV3CorrelationId
                : receiptCorrelationId;
        List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                context.view(),
                context.movementLegs(),
                type,
                flowsCorrelationId,
                v4FeeFractions
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
        boolean isPositionManagerEntry = entry.role() == com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole.POSITION_MANAGER;
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

    /**
     * R7: LFJ Liquidity Book pair-level handler (Option-B carry, ERC-1155 receipt deferred).
     *
     * <p>LFJ Liquidity Book positions are ERC-1155 bin shares keyed by pair contract, not NFT
     * tokenIds. Direction is determined solely from movement legs:
     * <ul>
     *   <li>Net-outbound: tokens leave the wallet → {@code LP_ENTRY} (addLiquidity).</li>
     *   <li>Net-inbound: tokens return to the wallet → {@code LP_EXIT} (removeLiquidity).</li>
     * </ul>
     *
     * <p>Correlation: {@code lp-position:<net>:lfj:<pairAddress>}.
     * No LP-RECEIPT leg is emitted (vault-style carry); per-bin fee split is deferred for
     * volatile pairs per the plan (stable AUSD/USDC: Option B, negligible materiality).
     */
    private Optional<ClassificationDecision> classifyLfjLbPairTx(
            OnChainClassificationContext context,
            com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry entry
    ) {
        NormalizedTransactionType type = LpRegistryFamilySupport.resolveByMovementLegsOnly(context.movementLegs());
        if (type == null) {
            return Optional.empty();
        }
        type = LpPrincipalCloseEvidence.refineFinalExitType(context.view(), context.movementLegs(), type);

        String networkId = context.view().networkId() == null
                ? "unknown"
                : context.view().networkId().name().toLowerCase(java.util.Locale.ROOT);
        String pairAddr = entry.contractAddress().toLowerCase(java.util.Locale.ROOT);
        String correlationId = "lp-position:" + networkId + ":lfj:" + pairAddr;

        // Emit a synthetic qty=1 LP-RECEIPT (A2 — LFJ ERC-1155 synthetic placeholder).
        // Full ERC-1155 TransferBatch share decoding is deferred (stable pair, negligible
        // materiality). The correlationId seeds the 3-segment receipt symbol so entry/exit link.
        List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                context.view(),
                context.movementLegs(),
                type,
                correlationId,
                null
        );

        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, entry.confidence()),
                flows,
                List.of(),
                correlationId
        ));
    }

    /**
     * R6a/R6b: Balancer V3 Vault / CompositeLiquidityRouter / Relayer handler.
     *
     * <p>All Balancer V3 LP lifecycle events are routed here regardless of the specific
     * entry-point contract (Vault, CompositeLiquidityRouter, Relayer). Direction is derived
     * purely from movement legs:
     *
     * <ul>
     *   <li>Gauge token outbound + BPT inbound → {@code LP_POSITION_UNSTAKE}.</li>
     *   <li>Gauge token inbound + BPT outbound → {@code LP_POSITION_STAKE}.</li>
     *   <li>BPT inbound only → {@code LP_ENTRY}.</li>
     *   <li>BPT outbound only → {@code LP_EXIT}.</li>
     * </ul>
     *
     * <p>Correlation: {@code lp-position:<net>:balancerv3:<bptPoolAddress>}.
     * The BPT pool address (from registered POOL role entry) always anchors the correlation,
     * even for gauge/unstake transactions where the underlying pool is derived from the
     * gauge's {@code underlyingPositionManager} field.
     */
    private Optional<ClassificationDecision> classifyBalancerV3VaultTx(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry
    ) {
        String bptPoolAddr = null;
        boolean bptIsInbound = false;
        String gaugeAddr = null;
        boolean gaugeIsInbound = false;
        String gaugeUnderlyingPool = null;

        for (RawLeg leg : context.movementLegs()) {
            if (leg == null || leg.fee() || leg.assetContract() == null) {
                continue;
            }
            Optional<ProtocolRegistryEntry> legEntry =
                    protocolRegistryService.lookup(context.view().networkId(), leg.assetContract());
            if (legEntry.isEmpty()) {
                continue;
            }
            ProtocolRegistryEntry pe = legEntry.get();
            if (!"Balancer".equalsIgnoreCase(pe.protocolName())) {
                continue;
            }
            // Detect gauge token (Balancer STAKE_CONTRACT)
            if (pe.role() == ProtocolRegistryRole.STAKE_CONTRACT && gaugeAddr == null) {
                gaugeAddr = leg.assetContract();
                gaugeIsInbound = leg.quantityDelta() != null && leg.quantityDelta().signum() > 0;
                gaugeUnderlyingPool = pe.underlyingPositionManager();
            }
            // Detect BPT pool token (Balancer V3 POOL)
            if ("V3".equalsIgnoreCase(pe.protocolVersion())
                    && pe.role() == ProtocolRegistryRole.POOL
                    && bptPoolAddr == null) {
                bptPoolAddr = leg.assetContract();
                bptIsInbound = leg.quantityDelta() != null && leg.quantityDelta().signum() > 0;
            }
        }

        String networkStr = context.view().networkId() == null
                ? "unknown"
                : context.view().networkId().name().toLowerCase(java.util.Locale.ROOT);

        // R6b: Gauge-based lifecycle (STAKE/UNSTAKE) when both gauge token and BPT appear.
        // gauge outbound + BPT inbound → LP_POSITION_UNSTAKE (burn gauge, receive BPT)
        // gauge inbound  + BPT outbound → LP_POSITION_STAKE   (deposit BPT, receive gauge)
        if (gaugeAddr != null && bptPoolAddr != null) {
            NormalizedTransactionType type = (!gaugeIsInbound && bptIsInbound)
                    ? NormalizedTransactionType.LP_POSITION_UNSTAKE
                    : NormalizedTransactionType.LP_POSITION_STAKE;
            String poolAddr = (gaugeUnderlyingPool != null && !gaugeUnderlyingPool.isBlank())
                    ? gaugeUnderlyingPool.toLowerCase(java.util.Locale.ROOT)
                    : bptPoolAddr.toLowerCase(java.util.Locale.ROOT);
            String correlationId = "lp-position:" + networkStr + ":balancerv3:" + poolAddr;
            List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                    context.view(), context.movementLegs(), type, correlationId, null);
            return Optional.of(RegistryDecisionSupport.registryResult(
                    context.view(), entry, type,
                    OnChainClassificationSupport.initialStatus(context.view(), type, entry.confidence()),
                    flows, List.of(), correlationId));
        }

        // BPT-only: LP_ENTRY (BPT inbound) or LP_EXIT (BPT outbound)
        if (bptPoolAddr == null) {
            return Optional.empty();
        }

        NormalizedTransactionType type = bptIsInbound
                ? NormalizedTransactionType.LP_ENTRY
                : NormalizedTransactionType.LP_EXIT;
        String correlationId = "lp-position:" + networkStr + ":balancerv3:" + bptPoolAddr.toLowerCase(java.util.Locale.ROOT);

        List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                context.view(),
                context.movementLegs(),
                type,
                correlationId,
                null
        );

        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, entry.confidence()),
                flows,
                List.of(),
                correlationId
        ));
    }

    /**
     * C1 (R7): LFJ LBRouter handler. Decodes {@code tokenX}, {@code tokenY}, {@code binStep} from
     * {@code addLiquidity(LiquidityParameters)} or {@code removeLiquidity(…)} calldata, resolves the
     * pair address via the pre-built {@link #lfjPairIndex}, and classifies the transaction as
     * {@code LP_ENTRY} or {@code LP_EXIT} based on movement-leg direction.
     *
     * <p>Selector allowlist (any other selector → {@code Optional.empty()} / fall through to HEURISTIC):
     * <ul>
     *   <li>{@code 0xa3c7271a} — {@code addLiquidity(LiquidityParameters)} — struct-encoded args</li>
     *   <li>{@code 0xc22159b6} — {@code removeLiquidity(address,address,uint16,…)} — flat args</li>
     * </ul>
     */
    private Optional<ClassificationDecision> classifyLfjLbRouterTx(
            OnChainClassificationContext context,
            ProtocolRegistryEntry entry
    ) {
        String methodId = context.view().methodId();
        if (methodId == null) {
            return Optional.empty();
        }
        String normalizedMethod = methodId.trim().toLowerCase(Locale.ROOT);
        String inputData = context.view().inputData();

        String tokenX;
        String tokenY;
        BigInteger binStepRaw;

        if (LFJ_ADD_LIQUIDITY_SELECTOR.equals(normalizedMethod)) {
            // addLiquidity(LiquidityParameters calldata):
            // Arg 0 = tuple offset (0x20) — skip.
            // Arg 1 = struct[0] = tokenX
            // Arg 2 = struct[1] = tokenY
            // Arg 3 = struct[2] = binStep (uint256-padded)
            tokenX = CalldataDecodingSupport.decodeAddressArgument(inputData, 1);
            tokenY = CalldataDecodingSupport.decodeAddressArgument(inputData, 2);
            binStepRaw = CalldataDecodingSupport.decodeUint256Argument(inputData, 3);
        } else if (LFJ_REMOVE_LIQUIDITY_SELECTOR.equals(normalizedMethod)) {
            // removeLiquidity(address tokenX, address tokenY, uint16 binStep, ...):
            // Arg 0 = tokenX, arg 1 = tokenY, arg 2 = binStep (uint16 zero-padded to 32 bytes)
            tokenX = CalldataDecodingSupport.decodeAddressArgument(inputData, 0);
            tokenY = CalldataDecodingSupport.decodeAddressArgument(inputData, 1);
            binStepRaw = CalldataDecodingSupport.decodeUint256Argument(inputData, 2);
        } else {
            // Swap or other non-LP selector — fall through to HEURISTIC.
            return Optional.empty();
        }

        if (tokenX == null || tokenY == null || binStepRaw == null) {
            log.warn("LFJ LBRouter calldata decode failed for method={} tx={}",
                    normalizedMethod, context.view().txHash());
            return Optional.empty();
        }

        int binStep = binStepRaw.intValueExact();
        NetworkId networkId = context.view().networkId();
        LfjPairKey key = LfjPairKey.of(networkId, tokenX, tokenY, binStep);
        String pairAddress = lfjPairIndex.get(key);
        if (pairAddress == null) {
            // Also try swapped token order (calldata may have tokenX/tokenY swapped vs. registry)
            pairAddress = lfjPairIndex.get(LfjPairKey.of(networkId, tokenY, tokenX, binStep));
        }
        if (pairAddress == null) {
            log.warn("LFJ pair not registered: tokenX={} tokenY={} binStep={} network={}",
                    tokenX, tokenY, binStep, networkId);
            return Optional.empty();
        }

        // C1 (R7): For LFJ removeLiquidity we KNOW the exact type from the selector — skip
        // refineFinalExitType which doesn't know the LFJ selector (0xc22159b6) and would
        // wrongly downgrade LP_EXIT to LP_FEE_CLAIM because hasPositionReductionEvidence()
        // only recognises Uniswap V3-style selectors and ERC-721 transfers.
        NormalizedTransactionType type;
        if (LFJ_REMOVE_LIQUIDITY_SELECTOR.equals(normalizedMethod)) {
            type = NormalizedTransactionType.LP_EXIT;
        } else {
            type = LpRegistryFamilySupport.resolveByMovementLegsOnly(context.movementLegs());
            if (type == null) {
                return Optional.empty();
            }
            type = LpPrincipalCloseEvidence.refineFinalExitType(context.view(), context.movementLegs(), type);
        }

        String networkStr = networkId == null ? "unknown" : networkId.name().toLowerCase(Locale.ROOT);
        String correlationId = "lp-position:" + networkStr + ":lfj:" + pairAddress.toLowerCase(Locale.ROOT);

        List<NormalizedTransaction.Flow> flows = LpClassificationFlowSupport.flows(
                context.view(),
                context.movementLegs(),
                type,
                correlationId,
                null
        );

        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, entry.confidence()),
                flows,
                List.of(),
                correlationId
        ));
    }

    /**
     * C1 (R7): Canonical key for the LFJ pair inverse index.
     * Token addresses are sorted (min < max lexicographically) so that addLiquidity/removeLiquidity
     * with any tokenX/tokenY order map to the same key.
     */
    private record LfjPairKey(NetworkId networkId, String tokenMin, String tokenMax, int binStep) {
        static LfjPairKey of(NetworkId networkId, String tokenA, String tokenB, int binStep) {
            String a = tokenA == null ? "" : tokenA.trim().toLowerCase(Locale.ROOT);
            String b = tokenB == null ? "" : tokenB.trim().toLowerCase(Locale.ROOT);
            String min = a.compareTo(b) <= 0 ? a : b;
            String max = a.compareTo(b) <= 0 ? b : a;
            return new LfjPairKey(networkId, min, max, binStep);
        }
    }

    /**
     * R6a: For Balancer V3 gauge / Aura STAKE_CONTRACT entries whose
     * {@code underlyingPositionManager} is a registered Balancer V3 BPT pool, builds the
     * pool-level correlation key {@code lp-position:<net>:balancerv3:<poolAddress>}
     * so that stake/unstake lifecycle events share the same basis pool as the JOIN/BURN txs.
     *
     * @return correlation key, or {@code null} if the underlying pool is not Balancer V3
     */
    private String resolveBalancerV3PoolCorrelationId(
            OnChainClassificationContext context,
            com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry entry
    ) {
        String underlyingPool = entry.underlyingPositionManager();
        if (underlyingPool == null || underlyingPool.isBlank()) {
            return null;
        }
        Optional<com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry> poolEntry =
                protocolRegistryService.lookup(context.view().networkId(), underlyingPool);
        if (poolEntry.isEmpty()) {
            return null;
        }
        com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry pe = poolEntry.get();
        if (!"Balancer".equalsIgnoreCase(pe.protocolName())
                || !"V3".equalsIgnoreCase(pe.protocolVersion())
                || pe.role() != com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole.POOL) {
            return null;
        }
        String networkId = context.view().networkId() == null
                ? "unknown"
                : context.view().networkId().name().toLowerCase(java.util.Locale.ROOT);
        return "lp-position:" + networkId + ":balancerv3:" + underlyingPool.toLowerCase(java.util.Locale.ROOT);
    }
}
