package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.aura;

import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies Aura Finance staking exits ({@code withdrawAndUnwrap}) as {@link NormalizedTransactionType#REWARD_CLAIM}.
 *
 * <p>Aura's {@code withdrawAndUnwrap(amount, claim)} is a mixed operation: it returns the underlying
 * Balancer LP token (BPT) AND claims accumulated gauge rewards (BAL, AURA, extra incentives) in one
 * transaction. The LP token has no tracked cost basis (Aura positions are not linked), so treating the
 * entire transaction as {@code REWARD_CLAIM} is correct — all inbound flows enter at $0 Net AVCO.
 *
 * <p>Detection strategy (OR):
 * <ol>
 *   <li>The {@code toAddress} resolves to a known Aura vault (protocol = "Aura" in registry), OR</li>
 *   <li>A token transfer originates from the Aura {@code BoosterLite} contract on the target network.</li>
 * </ol>
 * AND the called function is {@code withdrawAndUnwrap}.
 */
@Component
public class AuraProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    private static final String WITHDRAW_AND_UNWRAP_METHOD_ID = "0xc32e7202";
    private static final String WITHDRAW_AND_UNWRAP_FUNCTION_KEY = "withdrawandunwrap";

    private static final String PROTOCOL_NAME = "Aura";
    private static final String PROTOCOL_KEY = "aura";
    private static final String SEMANTIC_REWARD_CLAIM = "reward_claim";

    /**
     * Known Aura BoosterLite addresses per network (official sidechain deployments).
     * Source: https://docs.aura.finance/developers/deployed-addresses/sidechain-deployment-addresses
     */
    private static final Set<String> AURA_BOOSTER_LITE_ADDRESSES = Set.of(
            "0x98ef32edd24e2c92525e59afc4475c1242a30184"  // AVALANCHE BoosterLite
    );

    @Override
    public int getOrder() {
        // Before BalancerProtocolSemanticClassifier (HIGHEST_PRECEDENCE + 140)
        return Ordered.HIGHEST_PRECEDENCE + 135;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        if (!isWithdrawAndUnwrap(context.view().methodId(), context.view().functionName())) {
            return List.of();
        }
        if (!hasInboundMovements(context.movementLegs())) {
            return List.of();
        }

        Optional<ProtocolMatch> auraMatch = resolveAuraMatch(context);
        if (auraMatch.isEmpty()) {
            return List.of();
        }

        ProtocolMatch match = auraMatch.get();
        return List.of(new ProtocolSemanticHint(
                PROTOCOL_KEY,
                SEMANTIC_REWARD_CLAIM,
                match.protocolName(),
                match.protocolVersion(),
                null,
                NormalizedTransactionType.REWARD_CLAIM,
                match.confidence()
        ));
    }

    private Optional<ProtocolMatch> resolveAuraMatch(ProtocolSemanticContext context) {
        // Primary: toAddress is a known Aura vault in registry
        Optional<ProtocolMatch> primary = context.protocolDiscovery().primaryMatch();
        if (primary.isPresent() && PROTOCOL_NAME.equals(primary.get().protocolName())) {
            return primary;
        }

        // Fallback: a token transfer originated from a known Aura BoosterLite contract
        if (hasBoosterLiteTokenTransfer(context)) {
            return Optional.of(new ProtocolMatch(
                    PROTOCOL_NAME, "Vault",
                    null, null,
                    ConfidenceLevel.HIGH,
                    null, null, "BOOSTER_LITE_TRANSFER",
                    null, null
            ));
        }
        return Optional.empty();
    }

    private boolean hasBoosterLiteTokenTransfer(ProtocolSemanticContext context) {
        for (Document transfer : context.view().explorerTokenTransfers()) {
            String from = context.view().tokenTransferFrom(transfer);
            if (from != null && AURA_BOOSTER_LITE_ADDRESSES.contains(from.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithdrawAndUnwrap(String methodId, String functionName) {
        if (WITHDRAW_AND_UNWRAP_METHOD_ID.equals(methodId)) {
            return true;
        }
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        String key = functionName.trim().toLowerCase(Locale.ROOT);
        int idx = key.indexOf('(');
        return WITHDRAW_AND_UNWRAP_FUNCTION_KEY.equals(idx > 0 ? key.substring(0, idx) : key);
    }

    private boolean hasInboundMovements(List<RawLeg> legs) {
        if (legs == null) {
            return false;
        }
        for (RawLeg leg : legs) {
            if (!leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() > 0) {
                return true;
            }
        }
        return false;
    }
}
