package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.lp.LpNftClFlowMaterializer;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpStakingWrapperResolver;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LP family classifier for clarified NFT-backed LP entry paths.
 *
 * <p>Handles selectors that produce an ERC-721 position NFT minted to the wallet when
 * full-receipt clarification evidence is available:
 * <ul>
 *   <li>{@code routeSingle} (0xb94c3609) — single-asset routed LP entry</li>
 *   <li>{@code multicall} (0xac9650d8) — Uniswap V3 NonfungiblePositionManager multicall
 *       wrapping {@code mint} or {@code increaseLiquidity}; produces an ERC-721 LP receipt NFT</li>
 * </ul>
 */
@Component
public class LpClassifier implements OnChainFamilyClassifier {

    private static final String ROUTE_SINGLE_SELECTOR = "0xb94c3609";
    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final Logger log = LoggerFactory.getLogger(LpClassifier.class);

    /** Dedup set for the one-time "LP NFPM missing from registry" coverage warning (observability only). */
    private static final Set<String> WARNED_UNREGISTERED_NFPMS = ConcurrentHashMap.newKeySet();

    private final ProtocolRegistryService protocolRegistryService;
    private final LpStakingWrapperResolver lpStakingWrapperResolver;

    public LpClassifier(
            ProtocolRegistryService protocolRegistryService,
            LpStakingWrapperResolver lpStakingWrapperResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.lpStakingWrapperResolver = lpStakingWrapperResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        String methodId = context.view().methodId();
        if (!ROUTE_SINGLE_SELECTOR.equals(methodId) && !MULTICALL_SELECTOR.equals(methodId)) {
            return Optional.empty();
        }
        if (!context.view().hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (!LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(context.view())) {
            // No ERC-721 mint detected in available logs.
            // For vault-style routeSingle entries (e.g. Angle vault on Katana): the NFT is minted
            // inside the vault by the underlying NFPM and only appears in the full receipt.
            // hasFullReceiptClarificationEvidence() returns true even for token-transfer-only
            // evidence (no receipt logs yet). When no full receipt was fetched yet, claim
            // LP_ENTRY + PENDING_CLARIFICATION here so classifiers at the same
            // PRE_PROTOCOL_REVIEW stage (e.g. SwapClassifier) cannot mis-classify the
            // ETH-out + token-in pattern as SWAP.
            if (ROUTE_SINGLE_SELECTOR.equals(methodId)
                    && context.view().fullReceiptClarificationAttemptCount() <= 0) {
                return Optional.of(vaultLpPendingClarification(context));
            }
            return Optional.empty();
        }

        String tokenId = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(context.view());

        // RC-1 (ADR-018): identity is keyed by the NFPM contract (rawData.to), never the protocol
        // slug. The slug is display-only, resolved from the registry by contract — an unrecognized
        // V3-interface NFPM must NOT be silently labeled `uniswap`.
        // RC-5: if the interacted contract is a known staking/farming wrapper, canonicalize it to the
        // underlying NFPM so a staked position shares the single (network, NFPM, tokenId) identity.
        String resolvedContract = LpPositionCorrelationSupport.resolvePositionManagerContract(context.view());
        String canonicalContract = lpStakingWrapperResolver.canonicalPositionManager(
                context.view().networkId(),
                resolvedContract
        );
        // Display-label resolution (also emits the one-time unregistered-NFPM coverage warning).
        resolveDisplayProtocol(context, canonicalContract);
        String correlationId = tokenId == null
                ? null
                : LpPositionCorrelationSupport.contractKeyedCorrelationId(
                        context.view().networkId(), canonicalContract, tokenId);

        if (tokenId == null || correlationId == null) {
            return Optional.of(new ClassificationDecision(
                    NormalizedTransactionType.LP_ENTRY,
                    NormalizedTransactionStatus.NEEDS_REVIEW,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    enrichedFlows(context, NormalizedTransactionType.LP_ENTRY, null),
                    List.of("LP_NFT_ID_MISSING"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ));
        }

        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.LP_ENTRY,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.LP_ENTRY, ConfidenceLevel.MEDIUM),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                enrichedFlows(context, NormalizedTransactionType.LP_ENTRY, correlationId),
                List.of(),
                correlationId,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    /**
     * Resolves the display-only protocol label from the registry by the canonical (wrapper-resolved)
     * NFPM contract. RC-1: never default to {@code uniswap}; an unregistered V3-interface NFPM yields
     * {@code null} (contract-derived identity already keys the pool) plus a one-time coverage warning
     * so the registry can be extended.
     */
    private String resolveDisplayProtocol(OnChainClassificationContext context, String canonicalContract) {
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                context.view().networkId(),
                canonicalContract
        );
        if (entry.isPresent() && entry.get().protocolName() != null) {
            return entry.get().protocolName();
        }
        warnUnregisteredNfpmOnce(context, canonicalContract);
        return null;
    }

    private void warnUnregisteredNfpmOnce(OnChainClassificationContext context, String contract) {
        if (contract == null) {
            return;
        }
        String network = context.view().networkId() == null ? "unknown" : context.view().networkId().name();
        if (WARNED_UNREGISTERED_NFPMS.add(network + ":" + contract)) {
            log.warn("LP NFPM missing from protocol registry (identity stays contract-keyed): network={} contract={}",
                    network, contract);
        }
    }

    /**
     * Produces an LP_ENTRY + PENDING_CLARIFICATION decision for a vault routeSingle entry that has
     * token-transfer evidence but no full receipt yet. The correlationId remains null; it will be
     * resolved once the full receipt is fetched and the underlying NFPM ERC-721 mint log is visible.
     */
    private ClassificationDecision vaultLpPendingClarification(OnChainClassificationContext context) {
        return new ClassificationDecision(
                NormalizedTransactionType.LP_ENTRY,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                enrichedFlows(context, NormalizedTransactionType.LP_ENTRY, null),
                List.of(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code()),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static List<NormalizedTransaction.Flow> enrichedFlows(
            OnChainClassificationContext context,
            NormalizedTransactionType type,
            String correlationId
    ) {
        List<NormalizedTransaction.Flow> base = OnChainClassificationSupport.toFlows(
                context.movementLegs(), type
        );
        return LpNftClFlowMaterializer.enrich(context.view(), context.movementLegs(), type, correlationId, base);
    }
}
