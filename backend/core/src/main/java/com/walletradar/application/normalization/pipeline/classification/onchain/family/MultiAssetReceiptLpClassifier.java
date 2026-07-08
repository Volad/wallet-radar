package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.lp.PendleLpCorrelationSupport;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.lending.application.LendingAssetSymbolSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cycle/9 S3: classifies multi-asset LP deposits/withdrawals (Curve/Balancer-style stable pools,
 * AAVE GHO/USDT/USDC pool) as {@link NormalizedTransactionType#LP_ENTRY} / {@code LP_EXIT}.
 *
 * <p>This runs BEFORE {@link AaveReceiptShapeClassifier} so that multi-asset Aave deposits do not
 * get tagged as {@code LENDING_DEPOSIT} (which breaks composite-bucket basis carry — see
 * {@code ReplayPendingTransferKeyFactory.lpCompositeBucketIdentity}).</p>
 *
 * <p>Two activation paths:</p>
 * <ol>
 *   <li><b>Registry hint:</b> {@code tx.to} is a contract registered with
 *       {@code family=LP, role=POOL} in {@code protocol-registry.json}.</li>
 *   <li><b>Shape detection:</b> ≥2 distinct outbound asset families AND exactly one inbound
 *       non-family ERC-20 receipt (LP entry). Symmetric for exits.</li>
 * </ol>
 */
@Component
public class MultiAssetReceiptLpClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public MultiAssetReceiptLpClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        // Runs before AaveReceiptShapeClassifier (+130) and LpClassifier (+200) so that
        // multi-asset receipts are not misclassified as LENDING_DEPOSIT or skipped as NFT-only LP.
        return Ordered.HIGHEST_PRECEDENCE + 110;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (context == null || context.view() == null || context.movementLegs() == null) {
            return Optional.empty();
        }
        if (LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(context.view())) {
            // NFT-backed LP positions are handled by LpClassifier — no overlap.
            return Optional.empty();
        }
        if (hasPendleLpToken(context.movementLegs())) {
            // Pendle LP tokens (PENDLE-LPT, eqbPENDLE-LPT) need a pendle-lp: correlationId that
            // only LpSemanticClassifier can assign. Defer so it runs next (order +151).
            return Optional.empty();
        }

        Direction direction = detectDirection(context);
        if (direction == Direction.NONE) {
            return Optional.empty();
        }

        NormalizedTransactionType type = direction == Direction.ENTRY
                ? NormalizedTransactionType.LP_ENTRY
                : NormalizedTransactionType.LP_EXIT;

        boolean registeredPoolHit = isRegisteredLpPool(context);
        ConfidenceLevel confidence = registeredPoolHit ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;
        ClassificationSource source = registeredPoolHit
                ? ClassificationSource.PROTOCOL_REGISTRY
                : ClassificationSource.HEURISTIC;

        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, confidence),
                source,
                confidence,
                OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                List.of(),
                registeredPoolHit ? registeredProtocolName(context).orElse(null) : null,
                null
        ));
    }

    private Direction detectDirection(OnChainClassificationContext context) {
        Set<String> outboundIdentities = new HashSet<>();
        Set<String> inboundIdentities = new HashSet<>();
        Set<String> nonFamilyInboundIdentities = new HashSet<>();
        Set<String> nonFamilyOutboundIdentities = new HashSet<>();

        for (RawLeg leg : context.movementLegs()) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            String identity = continuityIdentity(leg);
            if (identity == null) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0) {
                outboundIdentities.add(identity);
                if (isNonFamilyReceipt(identity, leg.assetSymbol())) {
                    nonFamilyOutboundIdentities.add(identity);
                }
            } else {
                inboundIdentities.add(identity);
                if (isNonFamilyReceipt(identity, leg.assetSymbol())) {
                    nonFamilyInboundIdentities.add(identity);
                }
            }
        }

        // LP_ENTRY shape: ≥2 distinct outbound underlying families + exactly one inbound receipt
        // that itself does not share a family with the underlying assets (otherwise it's a lending
        // supply, not a pool deposit).
        if (outboundIdentities.size() >= 2 && nonFamilyInboundIdentities.size() == 1) {
            return Direction.ENTRY;
        }
        // LP_EXIT shape: 1 outbound receipt + ≥2 distinct inbound underlying families.
        if (inboundIdentities.size() >= 2 && nonFamilyOutboundIdentities.size() == 1) {
            return Direction.EXIT;
        }
        return Direction.NONE;
    }

    private static String continuityIdentity(RawLeg leg) {
        return AccountingAssetFamilySupport.continuityIdentity(leg.assetSymbol(), leg.assetContract());
    }

    private static boolean isNonFamilyReceipt(String identity, String assetSymbol) {
        if (identity == null) {
            return false;
        }
        // Identity not based on FAMILY: means the receipt token did not collapse to an
        // underlying-asset family — i.e., it represents an independent LP receipt token.
        if (identity.startsWith("FAMILY:")) {
            return false;
        }
        // Aave aTokens are handled by AaveReceiptShapeClassifier — exclude them so single-asset
        // lending deposits keep working.
        String normalized = assetSymbol == null ? "" : assetSymbol.trim().toUpperCase(Locale.ROOT);
        if (LendingAssetSymbolSupport.lendingReceiptLifecycleUnderlying(normalized) != null) {
            return false;
        }
        if (normalized.startsWith("VARIABLEDEBT") || normalized.startsWith("STABLEDEBT")) {
            return false;
        }
        return true;
    }

    private boolean isRegisteredLpPool(OnChainClassificationContext context) {
        return protocolRegistryService.lookup(
                        context.view().networkId(),
                        context.view().toAddress()
                )
                .filter(MultiAssetReceiptLpClassifier::isLpPool)
                .isPresent();
    }

    private Optional<String> registeredProtocolName(OnChainClassificationContext context) {
        return protocolRegistryService.lookup(
                        context.view().networkId(),
                        context.view().toAddress()
                )
                .map(ProtocolRegistryEntry::protocolName);
    }

    private static boolean isLpPool(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.LP
                && entry.role() == ProtocolRegistryRole.POOL;
    }

    private static boolean hasPendleLpToken(List<RawLeg> legs) {
        if (legs == null) {
            return false;
        }
        for (RawLeg leg : legs) {
            if (leg != null && !leg.fee() && PendleLpCorrelationSupport.marketIdFromSymbol(leg.assetSymbol()) != null) {
                return true;
            }
        }
        return false;
    }

    private enum Direction {
        NONE,
        ENTRY,
        EXIT
    }
}
