package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SwapRegistryFamilySupport {

    private static final Set<String> METHOD_AWARE_ROUTER_SELECTORS = Set.of(
            "0x7b939232",
            "0xac9650d8",
            "0xc16ae7a4",
            "0x3593564c",
            "0xae0b91e5",
            "0xda35bb0d"
    );

    private SwapRegistryFamilySupport() {
    }

    static boolean isRouterSwapLike(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        if (entry.family() != ProtocolRegistryFamily.DEX
                && entry.family() != ProtocolRegistryFamily.AGGREGATOR) {
            return false;
        }
        boolean routeLikeRole = entry.role() == ProtocolRegistryRole.ROUTER
                || entry.role() == ProtocolRegistryRole.EXCHANGE_ROUTER
                || entry.role() == ProtocolRegistryRole.POSITION_ROUTER;
        if (!routeLikeRole) {
            return false;
        }
        if (!METHOD_AWARE_ROUTER_SELECTORS.contains(view.methodId())
                && !containsAny(view.functionName(), "multicall", "batch", "execute")) {
            return false;
        }

        MovementSummary summary =
                MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (hasDistinctNetInboundAndOutboundAssets(movementLegs)) {
            return true;
        }
        if (summary.singleTokenOut() && summary.singleTokenIn() && !summary.sameAssetInAndOut()) {
            return true;
        }
        if (summary.nativeOutbound() && summary.tokenInboundCount() == 1 && summary.tokenOutboundCount() == 0) {
            return true;
        }
        if (summary.nativeInbound() && summary.tokenOutboundCount() == 1 && summary.tokenInboundCount() == 0) {
            return true;
        }
        return summary.tokenOutboundCount() >= 1
                && summary.tokenInboundCount() >= 1
                && !summary.sameAssetInAndOut();
    }

    private static boolean hasDistinctNetInboundAndOutboundAssets(List<RawLeg> movementLegs) {
        Map<String, BigDecimal> netByAsset = new LinkedHashMap<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            netByAsset.merge(assetKey(leg), leg.quantityDelta(), BigDecimal::add);
        }
        boolean hasInbound = false;
        boolean hasOutbound = false;
        for (BigDecimal netDelta : netByAsset.values()) {
            if (netDelta == null || netDelta.signum() == 0) {
                continue;
            }
            if (netDelta.signum() > 0) {
                hasInbound = true;
            } else {
                hasOutbound = true;
            }
            if (hasInbound && hasOutbound) {
                return true;
            }
        }
        return false;
    }

    private static String assetKey(RawLeg leg) {
        if (leg.assetContract() != null) {
            return leg.assetContract();
        }
        String symbol = leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT);
        return "native:" + symbol;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private record MovementSummary(
            Set<String> inboundAssets,
            Set<String> outboundAssets,
            boolean nativeInbound,
            boolean nativeOutbound
    ) {
        private static MovementSummary from(List<RawLeg> legs, String nativeSymbol) {
            Set<String> inbound = new LinkedHashSet<>();
            Set<String> outbound = new LinkedHashSet<>();
            boolean nativeIn = false;
            boolean nativeOut = false;
            for (RawLeg leg : legs) {
                if (leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                    continue;
                }
                String key = assetKey(leg);
                if (leg.quantityDelta().signum() > 0) {
                    inbound.add(key);
                    if (nativeSymbol != null && nativeSymbol.equalsIgnoreCase(leg.assetSymbol())) {
                        nativeIn = true;
                    }
                } else {
                    outbound.add(key);
                    if (nativeSymbol != null && nativeSymbol.equalsIgnoreCase(leg.assetSymbol())) {
                        nativeOut = true;
                    }
                }
            }
            return new MovementSummary(inbound, outbound, nativeIn, nativeOut);
        }

        private int tokenInboundCount() {
            return (int) inboundAssets.stream().filter(asset -> !asset.startsWith("native:")).count();
        }

        private int tokenOutboundCount() {
            return (int) outboundAssets.stream().filter(asset -> !asset.startsWith("native:")).count();
        }

        private boolean singleTokenIn() {
            return tokenInboundCount() == 1;
        }

        private boolean singleTokenOut() {
            return tokenOutboundCount() == 1;
        }

        private boolean sameAssetInAndOut() {
            return inboundAssets.size() == 1 && inboundAssets.equals(outboundAssets);
        }
    }
}
