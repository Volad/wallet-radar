package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Fluid vault operate/operatePerfect calls encode supply, withdraw, borrow, and payback
 * through signed deltas. Explorer token movements provide the deterministic economic side.
 */
@Component
@RequiredArgsConstructor
public class FluidVaultClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 154;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> entry = resolveFluidVaultEntry(context.view());
        if (entry.isEmpty()) {
            return Optional.empty();
        }

        FlowShape shape = FlowShape.from(context.movementLegs());
        FluidIntent intent = FluidIntent.from(context.view());
        NormalizedTransactionType type = intent.resolveType(shape)
                .orElseGet(shape::resolveType);
        if (type == null) {
            return Optional.empty();
        }

        return Optional.of(RegistryDecisionSupport.registryResultWithMatchedCounterparty(
                context.view(),
                entry.orElseThrow(),
                type,
                context.movementLegs(),
                entry.orElseThrow().contractAddress()
        ));
    }

    private Optional<ProtocolRegistryEntry> resolveFluidVaultEntry(OnChainRawTransactionView view) {
        Optional<ProtocolRegistryEntry> directEntry = protocolRegistryService.lookup(
                view.networkId(),
                view.toAddress()
        );
        if (directEntry.isPresent() && isFluidVault(directEntry.orElseThrow()) && isOperateCall(view.functionName())) {
            return directEntry;
        }
        if (!isSupportedFluidWrapperCall(view)) {
            return Optional.empty();
        }
        return findNestedFluidVaultEntry(view);
    }

    private Optional<ProtocolRegistryEntry> findNestedFluidVaultEntry(OnChainRawTransactionView view) {
        String input = view.inputData();
        if (input == null || input.length() < 66) {
            return Optional.empty();
        }
        String hex = input.startsWith("0x") ? input.substring(2) : input;
        Set<String> candidates = new LinkedHashSet<>();
        for (int index = 0; index + 64 <= hex.length(); index += 2) {
            Optional<String> address = addressWord(hex.substring(index, index + 64));
            address.ifPresent(candidates::add);
        }
        for (String address : candidates) {
            Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                    view.networkId(),
                    address
            );
            if (entry.isPresent() && isFluidVault(entry.orElseThrow())) {
                return entry;
            }
        }
        return Optional.empty();
    }

    private Optional<String> addressWord(String word) {
        if (word == null || word.length() != 64) {
            return Optional.empty();
        }
        String prefix = word.substring(0, 24);
        if (!prefix.chars().allMatch(ch -> ch == '0')) {
            return Optional.empty();
        }
        String address = "0x" + word.substring(24);
        if ("0x0000000000000000000000000000000000000000".equals(address)) {
            return Optional.empty();
        }
        return Optional.of(address.toLowerCase(Locale.ROOT));
    }

    private boolean isSupportedFluidWrapperCall(OnChainRawTransactionView view) {
        String functionName = view.functionName();
        String methodId = view.methodId();
        return "0x57b7bf20".equals(methodId)
                || "0xb88d4fde".equals(methodId)
                || (functionName != null && (functionName.startsWith("cast(")
                        || functionName.startsWith("safetransferfrom(")));
    }

    private enum FluidWrapperKind {
        NONE,
        CAST,
        NFT_TRANSFER
    }

    private record FluidIntent(
            FluidWrapperKind wrapperKind,
            boolean hasRepayAllOrNegativeDebtHint,
            boolean hasPositiveDebtHint
    ) {
        private static final String INT256_MIN =
                "8000000000000000000000000000000000000000000000000000000000000000";

        private static FluidIntent from(OnChainRawTransactionView view) {
            String input = view.inputData();
            String hex = input == null ? "" : (input.startsWith("0x") ? input.substring(2) : input);
            FluidWrapperKind wrapperKind = wrapperKind(view);
            boolean hasRepayAllOrNegativeDebtHint = wrapperKind == FluidWrapperKind.NONE
                    && hasRepayAllOrNegativeDebtHint(view, hex);
            boolean hasPositiveDebtHint = wrapperKind == FluidWrapperKind.CAST && !hasRepayAllOrNegativeDebtHint;
            return new FluidIntent(wrapperKind, hasRepayAllOrNegativeDebtHint, hasPositiveDebtHint);
        }

        private Optional<NormalizedTransactionType> resolveType(FlowShape shape) {
            if (hasRepayAllOrNegativeDebtHint) {
                if (shape.inboundStable && shape.inboundCollateral) {
                    return Optional.of(NormalizedTransactionType.LENDING_LOOP_DECREASE);
                }
                if (shape.inboundStable) {
                    return Optional.of(NormalizedTransactionType.LENDING_WITHDRAW);
                }
                if (shape.outboundStable) {
                    return Optional.of(NormalizedTransactionType.REPAY);
                }
            }
            if (wrapperKind == FluidWrapperKind.NFT_TRANSFER && shape.inboundStable) {
                return Optional.of(NormalizedTransactionType.LENDING_LOOP_DECREASE);
            }
            if (wrapperKind == FluidWrapperKind.CAST && shape.outboundCollateral) {
                return Optional.of(NormalizedTransactionType.LENDING_LOOP_OPEN);
            }
            if (hasPositiveDebtHint && shape.outboundStable) {
                return Optional.of(NormalizedTransactionType.LENDING_LOOP_OPEN);
            }
            return Optional.empty();
        }

        private static FluidWrapperKind wrapperKind(OnChainRawTransactionView view) {
            if ("0x57b7bf20".equals(view.methodId())
                    || startsWithFunction(view.functionName(), "cast(")) {
                return FluidWrapperKind.CAST;
            }
            if ("0xb88d4fde".equals(view.methodId())
                    || startsWithFunction(view.functionName(), "safetransferfrom(")) {
                return FluidWrapperKind.NFT_TRANSFER;
            }
            return FluidWrapperKind.NONE;
        }

        private static boolean hasRepayAllOrNegativeDebtHint(OnChainRawTransactionView view, String hex) {
            if (hex.contains(INT256_MIN)) {
                return true;
            }
            if ("0x032d2276".equals(view.methodId())) {
                return isNegativeWord(argument(hex, 2));
            }
            if ("0x0931bf2d".equals(view.methodId())) {
                return isNegativeWord(argument(hex, 2)) || isNegativeWord(argument(hex, 3));
            }
            return false;
        }

        private static String argument(String hex, int index) {
            int start = index * 64;
            if (hex == null || start < 0 || start + 64 > hex.length()) {
                return "";
            }
            return hex.substring(start, start + 64);
        }

        private static boolean isNegativeWord(String word) {
            return word != null && word.length() == 64
                    && (word.charAt(0) == '8'
                    || word.charAt(0) == '9'
                    || word.charAt(0) == 'a'
                    || word.charAt(0) == 'b'
                    || word.charAt(0) == 'c'
                    || word.charAt(0) == 'd'
                    || word.charAt(0) == 'e'
                    || word.charAt(0) == 'f');
        }

        private static boolean startsWithFunction(String functionName, String prefix) {
            return functionName != null && functionName.startsWith(prefix);
        }
    }

    private boolean isFluidVault(ProtocolRegistryEntry entry) {
        return entry != null
                && "Fluid".equals(entry.protocolName())
                && entry.family() == ProtocolRegistryFamily.LENDING
                && entry.role() == ProtocolRegistryRole.VAULT;
    }

    private boolean isOperateCall(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        return functionName.toLowerCase(Locale.ROOT).startsWith("operate");
    }

    private static boolean isStableDebtAsset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return "USDC".equals(normalized)
                || "USDT".equals(normalized)
                || "USDT0".equals(normalized)
                || "USD\u20AE0".equals(normalized)
                || "DEUSD".equals(normalized);
    }

    private record FlowShape(
            boolean inboundStable,
            boolean outboundStable,
            boolean inboundCollateral,
            boolean outboundCollateral
    ) {
        private static FlowShape from(Iterable<RawLeg> legs) {
            boolean inboundStable = false;
            boolean outboundStable = false;
            boolean inboundCollateral = false;
            boolean outboundCollateral = false;
            if (legs != null) {
                for (RawLeg leg : legs) {
                    if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                        continue;
                    }
                    boolean stable = isStableDebtAsset(leg.assetSymbol());
                    boolean inbound = leg.quantityDelta().signum() > 0;
                    if (stable && inbound) {
                        inboundStable = true;
                    } else if (stable) {
                        outboundStable = true;
                    } else if (inbound) {
                        inboundCollateral = true;
                    } else {
                        outboundCollateral = true;
                    }
                }
            }
            return new FlowShape(inboundStable, outboundStable, inboundCollateral, outboundCollateral);
        }

        private NormalizedTransactionType resolveType() {
            if (outboundCollateral && inboundStable) {
                return NormalizedTransactionType.LENDING_LOOP_OPEN;
            }
            if (inboundCollateral && outboundStable) {
                return NormalizedTransactionType.LENDING_LOOP_DECREASE;
            }
            if (inboundStable && !outboundStable && !inboundCollateral && !outboundCollateral) {
                return NormalizedTransactionType.BORROW;
            }
            if (outboundStable && !inboundStable && !inboundCollateral && !outboundCollateral) {
                return NormalizedTransactionType.REPAY;
            }
            if (outboundCollateral && !inboundStable && !outboundStable) {
                return NormalizedTransactionType.LENDING_DEPOSIT;
            }
            if (inboundCollateral && !inboundStable && !outboundStable) {
                return NormalizedTransactionType.LENDING_WITHDRAW;
            }
            return null;
        }
    }
}
