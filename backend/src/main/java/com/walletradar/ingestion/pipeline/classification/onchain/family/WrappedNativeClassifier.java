package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.WrappedNativeSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Selector-driven wrap/unwrap family.
 */
@Component
public class WrappedNativeClassifier implements OnChainFamilyClassifier {

    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public WrappedNativeClassifier(NativeAssetSymbolResolver nativeAssetSymbolResolver) {
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.EARLY_GUARDS;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<NormalizedTransactionType> wrappedType = WrappedNativeSupport.detectType(context.view(), nativeAssetSymbolResolver);
        if (wrappedType.isEmpty() || !WrappedNativeSupport.hasWrappedNativeIdentity(context.view(), nativeAssetSymbolResolver)) {
            return Optional.empty();
        }

        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(context.view().networkId());
        String wrappedContract = nativeAssetSymbolResolver.wrappedNativeContract(context.view().networkId());
        List<RawLeg> movementLegs = context.movementLegs();

        if (wrappedType.get() == NormalizedTransactionType.WRAP
                && hasNativeOutbound(movementLegs, nativeSymbol)
                && hasWrappedInbound(movementLegs, wrappedContract)) {
            return Optional.of(FamilyDecisionSupport.build(
                    NormalizedTransactionType.WRAP,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.HIGH,
                    movementLegs,
                    List.of()
            ));
        }

        if (wrappedType.get() == NormalizedTransactionType.UNWRAP
                && hasNativeInbound(movementLegs, nativeSymbol)
                && hasWrappedOutbound(movementLegs, wrappedContract)) {
            return Optional.of(FamilyDecisionSupport.build(
                    NormalizedTransactionType.UNWRAP,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.HIGH,
                    movementLegs,
                    List.of()
            ));
        }

        return Optional.empty();
    }

    private boolean hasNativeOutbound(List<RawLeg> movementLegs, String nativeSymbol) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.assetContract() == null
                        && nativeSymbol != null
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol())
                        && leg.quantityDelta().signum() < 0);
    }

    private boolean hasNativeInbound(List<RawLeg> movementLegs, String nativeSymbol) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.assetContract() == null
                        && nativeSymbol != null
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol())
                        && leg.quantityDelta().signum() > 0);
    }

    private boolean hasWrappedInbound(List<RawLeg> movementLegs, String wrappedContract) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee()
                        && wrappedContract != null
                        && wrappedContract.equalsIgnoreCase(leg.assetContract())
                        && leg.quantityDelta().signum() > 0);
    }

    private boolean hasWrappedOutbound(List<RawLeg> movementLegs, String wrappedContract) {
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee()
                        && wrappedContract != null
                        && wrappedContract.equalsIgnoreCase(leg.assetContract())
                        && leg.quantityDelta().signum() < 0);
    }
}
