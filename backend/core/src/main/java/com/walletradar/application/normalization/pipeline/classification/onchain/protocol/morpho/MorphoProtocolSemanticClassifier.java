package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.morpho;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.application.normalization.pipeline.classification.support.CalldataDecodingSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class MorphoProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    private static final String RESOURCE_PROTOCOL = "Morpho";
    private static final String RESOURCE_VERSION = "bundler3";
    private static final String PROTOCOL_KEY = "morpho";
    private static final String SEMANTIC_SWAP = "swap";
    private static final String SEMANTIC_COLLATERAL_BORROW = "collateral_borrow";
    private static final String SEMANTIC_VAULT_DEPOSIT = "vault_deposit";
    private static final String SEMANTIC_VAULT_WITHDRAW = "vault_withdraw";
    private static final String SEMANTIC_LENDING_WITHDRAW = "lending_withdraw";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final ProtocolResourceDefinition resource;

    public MorphoProtocolSemanticClassifier(ProtocolResourceCatalog protocolResourceCatalog) {
        this.resource = protocolResourceCatalog.find(RESOURCE_PROTOCOL, RESOURCE_VERSION).orElse(null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 142;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        Optional<ProtocolMatch> entry = context.protocolDiscovery().firstSpecialHandlerMatch(
                ProtocolRegistrySpecialHandlerType.MORPHO_BUNDLER
        );
        if (entry.isEmpty() || !isBundlerCall(context.view())) {
            return List.of();
        }
        ProtocolMatch value = entry.orElseThrow();
        boolean hasOutbound = context.movementLegs().stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        boolean hasInbound = context.movementLegs().stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        if (containsWithdrawCollateral(context.view()) && hasInbound) {
            return List.of(hint(value, SEMANTIC_LENDING_WITHDRAW, NormalizedTransactionType.LENDING_WITHDRAW));
        }
        if (!hasOutbound || !hasInbound) {
            return List.of();
        }
        if (hasMintedShareInbound(context.view()) && hasOutbound) {
            return List.of(hint(value, SEMANTIC_VAULT_DEPOSIT, NormalizedTransactionType.VAULT_DEPOSIT));
        }
        if (hasCollateralBorrowShape(context)) {
            return List.of(hint(value, SEMANTIC_COLLATERAL_BORROW, NormalizedTransactionType.LENDING_LOOP_OPEN));
        }
        if (hasShareOutbound(context.view()) && hasInbound) {
            return List.of(hint(value, SEMANTIC_VAULT_WITHDRAW, NormalizedTransactionType.VAULT_WITHDRAW));
        }
        return List.of(hint(value, SEMANTIC_SWAP, NormalizedTransactionType.SWAP));
    }

    private boolean isBundlerCall(OnChainRawTransactionView view) {
        if (resource == null || view == null) {
            return false;
        }
        return resource.matchesMethodSelector("bundlerMulticall", view.methodId())
                || resource.matchesFunctionMarker("bundlerMulticall", view.functionName());
    }

    private boolean containsWithdrawCollateral(OnChainRawTransactionView view) {
        if (resource == null || view == null) {
            return false;
        }
        for (String selector : resource.methodSelectors("withdrawCollateral")) {
            if (selector != null && CalldataDecodingSupport.containsEmbeddedSelector(view.inputData(), selector)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMintedShareInbound(OnChainRawTransactionView view) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferTo(transfer))) {
                continue;
            }
            if (!ZERO_ADDRESS.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            if (isShareLikeToken(view, transfer)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasShareOutbound(OnChainRawTransactionView view) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            if (isShareLikeToken(view, transfer)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCollateralBorrowShape(ProtocolSemanticContext context) {
        String walletAddress = context.view().walletAddress();
        if (walletAddress == null) {
            return false;
        }
        boolean collateralOutbound = false;
        boolean loanInbound = false;
        for (Document transfer : context.view().explorerTokenTransfers()) {
            if (context.view().tokenTransferQuantity(transfer) == null
                    || context.view().tokenTransferQuantity(transfer).signum() <= 0) {
                continue;
            }
            String symbol = context.view().tokenTransferSymbol(transfer);
            if (walletAddress.equals(context.view().tokenTransferFrom(transfer))
                    && !isShareLikeToken(context.view(), transfer)
                    && !isStableLike(symbol)) {
                collateralOutbound = true;
            }
            if (walletAddress.equals(context.view().tokenTransferTo(transfer))
                    && !ZERO_ADDRESS.equals(context.view().tokenTransferFrom(transfer))
                    && !isShareLikeToken(context.view(), transfer)
                    && isStableLike(symbol)) {
                loanInbound = true;
            }
        }
        return collateralOutbound && loanInbound;
    }

    private boolean isShareLikeToken(OnChainRawTransactionView view, Document transfer) {
        if (resource == null) {
            return false;
        }
        String symbol = normalize(view.tokenTransferSymbol(transfer));
        String name = normalize(view.tokenTransferName(transfer));
        return resource.matchesAssetMarker("shareSymbolMarkers", symbol)
                || resource.matchesAssetMarker("shareNameMarkers", name);
    }

    private ProtocolSemanticHint hint(
            ProtocolMatch entry,
            String semanticType,
            NormalizedTransactionType type
    ) {
        return new ProtocolSemanticHint(
                PROTOCOL_KEY,
                semanticType,
                entry.protocolName(),
                entry.protocolVersion(),
                null,
                type,
                entry.confidence()
        );
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private boolean isStableLike(String symbol) {
        String normalized = normalize(symbol);
        return normalized != null && switch (normalized) {
            case "usdc", "usdt", "usdt0", "usd₮0", "dai", "gho", "usde", "deusd", "eurc" -> true;
            default -> false;
        };
    }
}
