package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.lp.LpNftClFlowMaterializer;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
            return Optional.empty();
        }

        String tokenId = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(context.view());

        if (tokenId == null) {
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

        String networkName = context.view().networkId() != null
                ? context.view().networkId().name().toLowerCase(Locale.ROOT)
                : "unknown";
        String correlationId = "lp-position:" + networkName + ":uniswap:" + tokenId;

        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.LP_ENTRY,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.LP_ENTRY, ConfidenceLevel.MEDIUM),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                enrichedFlows(context, NormalizedTransactionType.LP_ENTRY, "uniswap"),
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

    private static List<NormalizedTransaction.Flow> enrichedFlows(
            OnChainClassificationContext context,
            NormalizedTransactionType type,
            String protocolName
    ) {
        List<NormalizedTransaction.Flow> base = OnChainClassificationSupport.toFlows(
                context.movementLegs(), type
        );
        return LpNftClFlowMaterializer.enrich(context.view(), context.movementLegs(), type, protocolName, base);
    }
}
