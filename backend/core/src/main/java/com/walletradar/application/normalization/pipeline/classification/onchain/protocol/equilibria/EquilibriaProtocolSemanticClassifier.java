package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.equilibria;

import com.walletradar.application.normalization.pipeline.classification.lp.PendleLpCorrelationSupport;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Classifies Equilibria Finance transactions on MANTLE:
 *
 * <ul>
 *   <li>{@code deposit(uint256 _pid, uint256 _amount, bool _stake)} (methodId {@code 0x43a0d066}) on
 *       the Equilibria Depositor contract → {@link NormalizedTransactionType#STAKING_DEPOSIT}.
 *       Deposits PENDLE-LPT into Equilibria staking; eqbPENDLE-LPT is minted internally (no ERC-20
 *       transfer visible in the flows).</li>
 *   <li>{@code zapOutV3SingleToken} on the Equilibria Booster contract:
 *     <ul>
 *       <li>When Pendle LP-wrapper tokens (eqbPENDLE-LPT) appear in movement legs → produces
 *           {@link NormalizedTransactionType#LP_EXIT} hint so {@code LpSemanticClassifier} assigns
 *           the {@code pendle-lp:} correlation ID from the token legs, allowing
 *           {@code LpReceiptExitReplayHandler} to restore cmETH basis from the LP pool.</li>
 *       <li>Otherwise (no LP-wrapper legs) → {@link NormalizedTransactionType#REWARD_CLAIM}
 *           (legacy pure-reward zap-out path).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Detection of Equilibria context requires {@code resolveEquilibriaMatch()} to find a registered
 * Equilibria contract as the primary protocol discovery match. Both the Depositor and the Booster
 * are registered in {@code protocol-registry.json}.
 *
 * <p>See ADR-047 for the full staking-deposit/LP-corridor design.
 */
@Component
public class EquilibriaProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    private static final String PROTOCOL_NAME = "Equilibria";
    private static final String PROTOCOL_KEY = "equilibria";
    private static final String ZAP_OUT_FUNCTION_KEY = "zapout";
    private static final String DEPOSIT_METHOD_ID = "0x43a0d066";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 140;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        if (isEquilibriaDeposit(context)) {
            Optional<ProtocolMatch> match = resolveEquilibriaMatch(context);
            if (match.isPresent()) {
                ProtocolMatch m = match.get();
                return List.of(new ProtocolSemanticHint(
                        PROTOCOL_KEY,
                        "staking_deposit",
                        m.protocolName(),
                        m.protocolVersion(),
                        null,
                        NormalizedTransactionType.STAKING_DEPOSIT,
                        m.confidence()
                ));
            }
        }

        if (!isEquilibriaZapOut(context)) {
            return List.of();
        }
        if (!hasInboundMovements(context.movementLegs())) {
            return List.of();
        }

        Optional<ProtocolMatch> match = resolveEquilibriaMatch(context);
        if (match.isEmpty()) {
            return List.of();
        }

        ProtocolMatch m = match.get();

        // ADR-047: when the zap-out wraps a Pendle LP position (eqbPENDLE-LPT in legs),
        // classify as LP_EXIT so LpSemanticClassifier assigns the pendle-lp: correlation ID
        // from the movement legs. LpReceiptExitReplayHandler then restores cmETH basis from
        // the LP receipt pool instead of acquiring at market price.
        if (hasPendleLpLeg(context.movementLegs())) {
            return List.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    "lp_exit",
                    m.protocolName(),
                    m.protocolVersion(),
                    null,
                    NormalizedTransactionType.LP_EXIT,
                    m.confidence()
            ));
        }

        return List.of(new ProtocolSemanticHint(
                PROTOCOL_KEY,
                "reward_claim",
                m.protocolName(),
                m.protocolVersion(),
                null,
                NormalizedTransactionType.REWARD_CLAIM,
                m.confidence()
        ));
    }

    private boolean isEquilibriaDeposit(ProtocolSemanticContext context) {
        String methodId = context.view().methodId();
        if (methodId != null && DEPOSIT_METHOD_ID.equalsIgnoreCase(methodId.trim())) {
            return true;
        }
        String functionName = context.view().functionName();
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        return functionKey(functionName).startsWith("deposit");
    }

    private boolean isEquilibriaZapOut(ProtocolSemanticContext context) {
        String functionName = context.view().functionName();
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        String key = functionKey(functionName);
        return key.startsWith(ZAP_OUT_FUNCTION_KEY);
    }

    private Optional<ProtocolMatch> resolveEquilibriaMatch(ProtocolSemanticContext context) {
        Optional<ProtocolMatch> primary = context.protocolDiscovery().primaryMatch();
        if (primary.isPresent() && PROTOCOL_NAME.equals(primary.get().protocolName())) {
            return primary;
        }
        return Optional.empty();
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

    /**
     * Returns {@code true} when any non-fee movement leg carries a Pendle LP-wrapper token
     * (e.g., {@code eqbPENDLE-LPT} or any symbol whose {@link PendleLpCorrelationSupport#marketIdFromSymbol}
     * resolves to a non-null market id).
     */
    private boolean hasPendleLpLeg(List<RawLeg> legs) {
        if (legs == null) {
            return false;
        }
        for (RawLeg leg : legs) {
            if (leg == null || leg.fee()) {
                continue;
            }
            if (PendleLpCorrelationSupport.marketIdFromSymbol(leg.assetSymbol()) != null) {
                return true;
            }
        }
        return false;
    }

    private static String functionKey(String functionName) {
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int idx = normalized.indexOf('(');
        return idx > 0 ? normalized.substring(0, idx) : normalized;
    }
}
