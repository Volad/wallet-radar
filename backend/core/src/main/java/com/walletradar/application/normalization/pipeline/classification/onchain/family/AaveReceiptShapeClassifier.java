package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Aave Pool multicall classifier backed by receipt-shape evidence.
 */
@Component
public class AaveReceiptShapeClassifier implements OnChainFamilyClassifier {

    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final ProtocolRegistryService protocolRegistryService;

    public AaveReceiptShapeClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

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
        if (context == null || context.view() == null || !isAavePoolMulticall(context)) {
            return Optional.empty();
        }
        if (hasUnderlyingOutToMintedReceiptContract(context) && hasAaveReceiptInbound(context.movementLegs())) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.LENDING_DEPOSIT,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.LENDING_DEPOSIT,
                            ConfidenceLevel.MEDIUM
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.LENDING_DEPOSIT),
                    List.of(),
                    "Aave",
                    "V3"
            ));
        }
        return Optional.empty();
    }

    private boolean isAavePoolMulticall(OnChainClassificationContext context) {
        if (!MULTICALL_SELECTOR.equals(context.view().methodId())
                && !contains(context.view().functionName(), "multicall")) {
            return false;
        }
        return protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress())
                .filter(this::isAavePool)
                .isPresent();
    }

    private boolean isAavePool(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.LENDING
                && entry.role() == ProtocolRegistryRole.POOL
                && entry.protocolName() != null
                && entry.protocolName().trim().equalsIgnoreCase("Aave");
    }

    private boolean hasUnderlyingOutToMintedReceiptContract(OnChainClassificationContext context) {
        String wallet = context.view().walletAddress();
        for (Document mintedReceipt : context.view().explorerTokenTransfers()) {
            if (!isAaveReceiptMintToWallet(context, mintedReceipt, wallet)) {
                continue;
            }
            String receiptContract = context.view().tokenTransferContract(mintedReceipt);
            for (Document transfer : context.view().explorerTokenTransfers()) {
                BigDecimal quantity = context.view().tokenTransferQuantity(transfer);
                if (quantity == null || quantity.signum() <= 0 || isAaveReceiptSymbol(context.view().tokenTransferSymbol(transfer))) {
                    continue;
                }
                if (same(wallet, context.view().tokenTransferFrom(transfer))
                        && same(receiptContract, context.view().tokenTransferTo(transfer))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAaveReceiptMintToWallet(
            OnChainClassificationContext context,
            Document transfer,
            String wallet
    ) {
        BigDecimal quantity = context.view().tokenTransferQuantity(transfer);
        return quantity != null
                && quantity.signum() > 0
                && same(ZERO_ADDRESS, context.view().tokenTransferFrom(transfer))
                && same(wallet, context.view().tokenTransferTo(transfer))
                && isAaveReceiptSymbol(context.view().tokenTransferSymbol(transfer));
    }

    private boolean hasAaveReceiptInbound(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null)
                .anyMatch(leg -> leg.quantityDelta().signum() > 0 && isAaveReceiptSymbol(leg.assetSymbol()));
    }

    private boolean isAaveReceiptSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("A") && !normalized.startsWith("VARIABLEDEBT") && !normalized.startsWith("STABLEDEBT");
    }

    private boolean contains(String value, String needle) {
        return value != null && value.contains(needle);
    }

    private boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }
}
