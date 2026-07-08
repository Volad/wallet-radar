package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Narrow audited source-side bridge classifier for zkSync routed Across sends that
 * currently do not expose a stable top-level SpokePool address and whose saved
 * explorer transfer list may contain only wallet-boundary native funding legs.
 */
@Component
public class ZkSyncAcrossRoutedBridgeClassifier implements OnChainFamilyClassifier {

    private static final String ROUTED_SELECTOR = "0x27ad57d5";
    private static final String ZKSYNC_ENTRY_ROUTER = "0xde167bb9f640a3d6de7b8c16c28920755f5921f2";
    private static final String ACROSS_HELPER = "0xb456e051867625d320a7a793897058eb7eb6093b";
    private static final String ACROSS_ZKSYNC_SETTLEMENT = "0xe0b015e54d54fc84a6cb9b666099c46ade9335ff";
    private static final String ZKSYNC_NATIVE_ALIAS = "0x000000000000000000000000000000000000800a";
    private static final String ZKSYNC_WETH = "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91";
    private static final String ARBITRUM_WETH = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 130;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (context == null || context.view() == null || context.view().networkId() != NetworkId.ZKSYNC) {
            return Optional.empty();
        }
        if (!ROUTED_SELECTOR.equals(context.view().methodId()) || !onlyOutbound(context)) {
            return Optional.empty();
        }
        if (!sameAddress(ZKSYNC_ENTRY_ROUTER, context.view().toAddress())) {
            return Optional.empty();
        }
        if (!hasAuditedAcrossRouteEvidence(context)) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                NormalizedTransactionType.BRIDGE_OUT,
                OnChainClassificationSupport.initialStatus(
                        context.view(),
                        NormalizedTransactionType.BRIDGE_OUT,
                        ConfidenceLevel.MEDIUM
                ),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(
                        context.view(),
                        context.movementLegs(),
                        NormalizedTransactionType.BRIDGE_OUT
                ),
                List.of(),
                "Across",
                null
        ));
    }

    private boolean hasAuditedAcrossRouteEvidence(OnChainClassificationContext context) {
        String inputData = context.view().inputData();
        String walletAddress = context.view().walletAddress();
        if (inputData == null || walletAddress == null) {
            return false;
        }
        if (!containsEncodedAddress(inputData, walletAddress)
                || !containsEncodedAddress(inputData, ARBITRUM_WETH)) {
            return false;
        }
        boolean hasHelper = hasTransferTouching(context.view(), ACROSS_HELPER) || containsEncodedAddress(inputData, ACROSS_HELPER);
        boolean hasSettlementPath = hasTransferTouching(context.view(), ACROSS_ZKSYNC_SETTLEMENT)
                && hasTransferTouching(context.view(), ZKSYNC_WETH);
        return (hasHelper && hasSettlementPath) || hasCalldataPlusBoundaryFundingEvidence(context);
    }

    private boolean hasCalldataPlusBoundaryFundingEvidence(OnChainClassificationContext context) {
        OnChainRawTransactionView view = context.view();
        if (view == null || view.rawValue() == null || view.rawValue().signum() <= 0) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!sameAddress(view.walletAddress(), view.tokenTransferFrom(transfer))
                    || !sameAddress(ZKSYNC_ENTRY_ROUTER, view.tokenTransferTo(transfer))
                    || !sameAddress(ZKSYNC_NATIVE_ALIAS, view.tokenTransferContract(transfer))) {
                continue;
            }
            java.math.BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null) {
                continue;
            }
            java.math.BigInteger transferValue = quantity.movePointRight(18).toBigInteger();
            if (view.rawValue().equals(transferValue)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTransferTouching(OnChainRawTransactionView view, String address) {
        for (Document transfer : view.explorerTokenTransfers()) {
            if (sameAddress(address, view.tokenTransferFrom(transfer))
                    || sameAddress(address, view.tokenTransferTo(transfer))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEncodedAddress(String inputData, String address) {
        if (inputData == null || address == null || address.isBlank()) {
            return false;
        }
        String normalizedInput = inputData.toLowerCase(java.util.Locale.ROOT);
        String normalizedAddress = address.toLowerCase(java.util.Locale.ROOT).replace("0x", "");
        return normalizedInput.contains(normalizedAddress);
    }

    private boolean onlyOutbound(OnChainClassificationContext context) {
        boolean hasInbound = context.movementLegs().stream()
                .anyMatch(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = context.movementLegs().stream()
                .anyMatch(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() < 0);
        return hasOutbound && !hasInbound;
    }

    private boolean sameAddress(String left, String right) {
        String normalizedLeft = OnChainRawTransactionView.normalizeAddress(left);
        String normalizedRight = OnChainRawTransactionView.normalizeAddress(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }
}
