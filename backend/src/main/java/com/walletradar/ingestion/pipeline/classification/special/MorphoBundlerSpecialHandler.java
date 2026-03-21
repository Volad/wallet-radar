package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Method-aware handler for Morpho Bundler3 style bundle execution.
 * Uses only raw token-transfer evidence already available at normalization time.
 */
@Component
public class MorphoBundlerSpecialHandler implements ProtocolSpecialHandler {

    private static final String MULTICALL_SELECTOR = "0x374f435d";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    @Override
    public ProtocolRegistrySpecialHandlerType handlerType() {
        return ProtocolRegistrySpecialHandlerType.MORPHO_BUNDLER;
    }

    @Override
    public SpecialHandlerResult classify(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> legs
    ) {
        if (!MULTICALL_SELECTOR.equals(view.methodId())
                && !SpecialHandlerSupport.containsAny(view.functionName(), "multicall", "bundle")) {
            return SpecialHandlerResult.unsupported();
        }

        boolean hasOutbound = legs.stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        boolean hasInbound = legs.stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        if (!hasOutbound || !hasInbound) {
            return SpecialHandlerResult.unsupported();
        }

        if (hasMintedShareInbound(view) && hasOutbound) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.VAULT_DEPOSIT, entry.confidence(), legs);
        }
        if (hasShareOutbound(view) && hasInbound) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.VAULT_WITHDRAW, entry.confidence(), legs);
        }
        return SpecialHandlerResult.of(view, NormalizedTransactionType.SWAP, entry.confidence(), legs);
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

    private boolean isShareLikeToken(OnChainRawTransactionView view, Document transfer) {
        String symbol = normalize(view.tokenTransferSymbol(transfer));
        String name = normalize(view.tokenTransferName(transfer));
        return (symbol != null && (symbol.startsWith("gt") || symbol.startsWith("syrup")))
                || (name != null && (name.contains("vault") || name.contains("syrup")));
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
