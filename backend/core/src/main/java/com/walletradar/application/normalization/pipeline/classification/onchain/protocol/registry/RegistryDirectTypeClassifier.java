package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.registry;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.OnChainClassificationInsertionPoint;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.OnChainFamilyClassifier;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.DirectMethodIdSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.application.normalization.pipeline.classification.support.SameWalletSwapShapeSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
public class RegistryDirectTypeClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public RegistryDirectTypeClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 470;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> protocolMatch =
                protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress());

        // Fallback: when the raw tx is an inbound token-transfer row (e.g., USDC received from a
        // custody contract via Etherscan's token-transfer API), `toAddress()` resolves to the
        // wallet itself. Try `fromAddress()` in case the sending contract is a registered protocol
        // (e.g., Paradex L1 Core whose event_type is PROTOCOL_CUSTODY_WITHDRAW). Only attempt
        // the fallback when `fromAddress` is distinct from the wallet to avoid self-transfer noise.
        if (protocolMatch.isEmpty()) {
            String fromAddress = context.view().fromAddress();
            String walletAddress = context.view().walletAddress();
            if (fromAddress != null && !fromAddress.equalsIgnoreCase(walletAddress)) {
                protocolMatch = protocolRegistryService.lookup(context.view().networkId(), fromAddress);
            }
        }

        // Third fallback: when both fromAddress() and toAddress() are blank (e.g., TERMINAL_METADATA_ONLY
        // Etherscan txs where the raw body has no from/to), scan inbound token-transfer senders.
        // This covers Paradex L1 Core withdrawals where the USDC transfer carries the sender address
        // even though the top-level raw tx fields are empty.
        if (protocolMatch.isEmpty()) {
            String walletAddress = context.view().walletAddress();
            for (Document transfer : context.view().explorerTokenTransfers()) {
                String sender = context.view().tokenTransferFrom(transfer);
                if (sender != null && !sender.equalsIgnoreCase(walletAddress)) {
                    protocolMatch = protocolRegistryService.lookup(context.view().networkId(), sender);
                    if (protocolMatch.isPresent()) {
                        break;
                    }
                }
            }
        }

        if (protocolMatch.isEmpty()) {
            return Optional.empty();
        }
        ProtocolRegistryEntry entry = protocolMatch.get();

        if (entry.decomposeByLegs()) {
            return Optional.of(RegistryDecisionSupport.pendingRegistryReview(
                    context.view(),
                    entry,
                    "REGISTRY_SPECIAL_HANDLER_REQUIRED"
            ));
        }

        if (shouldYieldToApproveFallback(context)) {
            return Optional.empty();
        }

        NormalizedTransactionType type = entry.normalizedType();
        if (type == null) {
            return Optional.empty();
        }
        if (type == NormalizedTransactionType.BRIDGE_OUT
                && SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(context.movementLegs())) {
            return Optional.empty();
        }

        // For LP POOL entries that declare an underlyingPositionManager (e.g. Katana vbETH-vbUSDC pool
        // found via token-transfer sender), resolve the vault-style correlationId so LP lifecycle
        // events (exits, fee claims) link to the same position as LP_ENTRY on the POSITION_MANAGER.
        String correlationId = resolveVaultCorrelationId(context.view(), entry);
        if (correlationId != null) {
            return Optional.of(RegistryDecisionSupport.registryResult(
                    context.view(),
                    entry,
                    type,
                    context.movementLegs(),
                    correlationId
            ));
        }
        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                type,
                context.movementLegs()
        ));
    }

    private boolean shouldYieldToApproveFallback(OnChainClassificationContext context) {
        if (DirectMethodIdSupport.resolveType(context.view().methodId()) == NormalizedTransactionType.APPROVE) {
            return true;
        }
        String functionName = context.view().functionName();
        return functionName != null && functionName.startsWith("approve");
    }

    /**
     * When the matched registry entry is an LP POOL with a declared {@code underlyingPositionManager}
     * (e.g. Katana vbETH-vbUSDC pool → vault), build the vault-style correlationId so that LP_EXIT
     * and fee-claim events link to the same position identity as the LP_ENTRY on the POSITION_MANAGER.
     */
    private static String resolveVaultCorrelationId(OnChainRawTransactionView view, ProtocolRegistryEntry entry) {
        if (entry.family() != ProtocolRegistryFamily.LP) {
            return null;
        }
        String underlying = OnChainRawTransactionView.normalizeAddress(entry.underlyingPositionManager());
        if (underlying == null || underlying.isBlank()) {
            return null;
        }
        if (view.networkId() == null) {
            return null;
        }
        String networkId = view.networkId().name().toLowerCase(Locale.ROOT);
        return "lp-position:" + networkId + ":" + underlying + ":vault";
    }
}
