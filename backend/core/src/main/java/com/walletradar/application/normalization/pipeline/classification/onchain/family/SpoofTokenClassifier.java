package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.classification.support.SpoofTokenQuarantineSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * SF-1(b): classification-time guard that quarantines spoof tokens at the earliest stage so newly
 * ingested address-poisoning rows are flagged immediately, not only on the idempotent sweep.
 *
 * <p>A transaction whose principal (non-FEE) movement leg carries a confusable homoglyph ticker on
 * a non-canonical contract (see {@link SpoofTokenQuarantineSupport}) is terminal-quarantined:
 * {@code excludedFromAccounting=true}, reason {@code SPOOF_TOKEN_CONFUSABLE_SYMBOL}. This covers the
 * OUT, IN, and SWAP directions — if any principal leg is a spoof the row is a scam interaction and
 * is excluded from accounting (the cost basis is already protected because confusable symbols are
 * never aliased onto a canonical asset, so a legitimate counter-leg cannot be corrupted).</p>
 *
 * <p>Note: native-symbol ERC-20 impersonation (SF-1(c), e.g. a fake "ETH" ERC-20 on Arbitrum at a
 * known scam contract) is handled post-classification by {@code ScamDisperseClonePhishingTagger},
 * which sets {@code excludedFromAccounting=true} for known phishing disperse-clone contracts.</p>
 */
@Component
public class SpoofTokenClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.EARLY_GUARDS;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (context == null || context.view() == null) {
            return Optional.empty();
        }
        NetworkId networkId = context.view().networkId();
        if (!hasConfusableSpoofPrincipal(networkId, context.movementLegs())) {
            return Optional.empty();
        }
        List<NormalizedTransaction.Flow> flows = OnChainClassificationSupport.toFlows(
                context.view(),
                context.movementLegs(),
                NormalizedTransactionType.UNKNOWN
        );
        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.HIGH,
                flows,
                List.of(SpoofTokenQuarantineSupport.REASON),
                null,
                null,
                null,
                Boolean.TRUE,
                SpoofTokenQuarantineSupport.REASON,
                null,
                null
        ));
    }

    private boolean hasConfusableSpoofPrincipal(NetworkId networkId, List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return false;
        }
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee())
                .anyMatch(leg -> SpoofTokenQuarantineSupport.isConfusableSpoofAsset(
                        networkId, leg.assetContract(), leg.assetSymbol()));
    }
}
