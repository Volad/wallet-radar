package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Compound V3 Comet/Bulker lifecycle classifier.
 */
@Component
@RequiredArgsConstructor
public class CompoundCometClassifier implements OnChainFamilyClassifier {

    private static final String COMPOUND_BULKER_INVOKE_SELECTOR = "0x555029a6";
    private static final String COMET_SUPPLY_SELECTOR = "0xf2b9fdb8";
    private static final String COMET_WITHDRAW_SELECTOR = "0xf3fef3a3";

    private final ProtocolRegistryService protocolRegistryService;

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 153;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        OnChainRawTransactionView view = context.view();
        Optional<ProtocolRegistryEntry> directEntry = protocolRegistryService.lookup(view.networkId(), view.toAddress());
        if (directEntry.isPresent() && isCompoundComet(directEntry.orElseThrow())) {
            return classifyDirectCometCall(view, context.movementLegs(), directEntry.orElseThrow());
        }

        if (!COMPOUND_BULKER_INVOKE_SELECTOR.equals(view.methodId())) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> bulkerEntry = protocolRegistryService.lookup(view.networkId(), view.toAddress());
        if (bulkerEntry.isEmpty() || !isCompoundBulker(bulkerEntry.orElseThrow())) {
            return Optional.empty();
        }
        return classifyBulkerInvoke(view, context.movementLegs(), bulkerEntry.orElseThrow());
    }

    private Optional<ClassificationDecision> classifyDirectCometCall(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryEntry entry
    ) {
        if (COMET_WITHDRAW_SELECTOR.equals(view.methodId())) {
            NormalizedTransactionType type = hasInboundBaseAsset(movementLegs)
                    ? NormalizedTransactionType.BORROW
                    : NormalizedTransactionType.LENDING_WITHDRAW;
            return Optional.of(buildDecision(view, entry, type, movementLegs, entry.contractAddress()));
        }
        if (COMET_SUPPLY_SELECTOR.equals(view.methodId())) {
            return Optional.of(buildDecision(
                    view,
                    entry,
                    NormalizedTransactionType.LENDING_DEPOSIT,
                    movementLegs,
                    entry.contractAddress()
            ));
        }
        return Optional.empty();
    }

    private Optional<ClassificationDecision> classifyBulkerInvoke(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryEntry bulkerEntry
    ) {
        String input = normalizedInput(view);
        if (input.isBlank()) {
            return Optional.empty();
        }
        boolean supplyNative = input.contains(asciiHex("ACTION_SUPPLY_NATIVE_TOKEN"));
        boolean supplyAsset = input.contains(asciiHex("ACTION_SUPPLY_ASSET"));
        boolean withdrawAsset = input.contains(asciiHex("ACTION_WITHDRAW_ASSET"));
        boolean withdrawNative = input.contains(asciiHex("ACTION_WITHDRAW_NATIVE_TOKEN"));

        NormalizedTransactionType type = null;
        if (supplyNative && withdrawAsset) {
            type = NormalizedTransactionType.LENDING_LOOP_OPEN;
        } else if (supplyAsset && withdrawNative) {
            type = input.contains("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
                    ? NormalizedTransactionType.LENDING_LOOP_CLOSE
                    : NormalizedTransactionType.LENDING_LOOP_DECREASE;
        }
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(buildDecision(view, bulkerEntry, type, movementLegs, cometAddressFromInput(view)));
    }

    private ClassificationDecision buildDecision(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<RawLeg> movementLegs,
            String matchedCounterparty
    ) {
        ClassificationDecision decision = FamilyDecisionSupport.buildWithView(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                ParityFlowSupport.flows(view, movementLegs, type),
                List.of(),
                false,
                matchedCounterparty,
                entry.protocolName(),
                entry.protocolVersion()
        );
        return decision;
    }

    private boolean hasInboundBaseAsset(List<RawLeg> movementLegs) {
        if (movementLegs == null) {
            return false;
        }
        return movementLegs.stream().anyMatch(leg -> leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() > 0
                && isBaseAsset(leg.assetSymbol()));
    }

    private boolean isBaseAsset(String assetSymbol) {
        if (assetSymbol == null) {
            return false;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return "USDC".equals(normalized) || "USDT".equals(normalized);
    }

    private boolean isCompoundComet(ProtocolRegistryEntry entry) {
        return entry != null
                && "Compound".equals(entry.protocolName())
                && entry.family() == ProtocolRegistryFamily.LENDING
                && entry.role() == ProtocolRegistryRole.POOL
                && entry.networks().contains(NetworkId.UNICHAIN);
    }

    private boolean isCompoundBulker(ProtocolRegistryEntry entry) {
        return entry != null
                && "Compound".equals(entry.protocolName())
                && entry.family() == ProtocolRegistryFamily.LENDING
                && entry.role() == ProtocolRegistryRole.ROUTER;
    }

    private String cometAddressFromInput(OnChainRawTransactionView view) {
        String input = normalizedInput(view);
        String unichainCometWord = "0000000000000000000000002c7118c4c88b9841fcf839074c26ae8f035f2921";
        if (input.contains(unichainCometWord)) {
            return "0x2c7118c4c88b9841fcf839074c26ae8f035f2921";
        }
        return view.toAddress();
    }

    private String normalizedInput(OnChainRawTransactionView view) {
        String input = view.inputData();
        if (input == null) {
            return "";
        }
        return input.startsWith("0x") ? input.substring(2) : input;
    }

    private String asciiHex(String value) {
        StringBuilder builder = new StringBuilder();
        for (char ch : value.toCharArray()) {
            builder.append(String.format("%02x", (int) ch));
        }
        return builder.toString();
    }
}
