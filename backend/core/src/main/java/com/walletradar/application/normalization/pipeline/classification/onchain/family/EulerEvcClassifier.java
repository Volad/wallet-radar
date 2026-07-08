package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Direct address+method classifier for Euler EVC (Ethereum Vault Connector) batch calls on
 * Avalanche that perform leveraged loop rebalances.
 *
 * <p>The three Avalanche txs (0x305f37, 0x1e0c42, 0x233c2b) call the Euler EVC at
 * {@code 0xddcbe30a761edd2e19bba930a977475265f36fa1} with method {@code 0xc16ae7a4} and deliver
 * eUSDC-2 vault shares within a leveraged loop. They are misclassified as EXTERNAL_TRANSFER_IN
 * by the general classifier because the existing Euler sub-classifiers require receipt-level
 * clarification evidence or specific borrow/rebalance patterns that these pure-share loop
 * operations do not exhibit.
 *
 * <p>This classifier runs at STANDARD ({@link OnChainClassificationInsertionPoint#PROTOCOL_LIFECYCLE})
 * and matches exclusively on network+address+method, bypassing the ambiguous heuristics.
 * Routing to LENDING_LOOP_REBALANCE ensures the carry passes through
 * {@code EulerLoopReplayHandler}, which preserves basis correctly.
 */
@Component
public class EulerEvcClassifier implements OnChainFamilyClassifier {

    /**
     * Euler EVC (Ethereum Vault Connector) batch router on Avalanche.
     */
    private static final String EULER_EVC_AVALANCHE = "0xddcbe30a761edd2e19bba930a977475265f36fa1";

    /**
     * {@code batch(tuple[] items)} method selector on the Euler EVC.
     */
    private static final String EULER_BATCH_METHOD_ID = "0xc16ae7a4";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (context == null || context.view() == null) {
            return Optional.empty();
        }
        OnChainRawTransactionView view = context.view();
        if (!NetworkId.AVALANCHE.equals(view.networkId())) {
            return Optional.empty();
        }
        if (!EULER_EVC_AVALANCHE.equals(normalizeAddress(view.toAddress()))) {
            return Optional.empty();
        }
        if (!EULER_BATCH_METHOD_ID.equalsIgnoreCase(view.methodId())) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                OnChainClassificationSupport.initialStatus(
                        view,
                        NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                        ConfidenceLevel.MEDIUM
                ),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(
                        context.movementLegs(),
                        NormalizedTransactionType.LENDING_LOOP_REBALANCE
                ),
                        List.of(),
                "Euler V2",
                null
        ));
    }

    private static String normalizeAddress(String address) {
        return address == null ? null : address.trim().toLowerCase(Locale.ROOT);
    }
}
