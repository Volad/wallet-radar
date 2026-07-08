package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Guardrails for audited protocol-funded destination gas assistance.
 */
public final class SponsoredGasTopUpSupport {

    private static final Map<NetworkId, BigDecimal> MAX_NATIVE_QTY_BY_NETWORK = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM, new BigDecimal("0.001")),
            Map.entry(NetworkId.ARBITRUM, new BigDecimal("0.001")),
            Map.entry(NetworkId.OPTIMISM, new BigDecimal("0.001")),
            Map.entry(NetworkId.BASE, new BigDecimal("0.005")),
            Map.entry(NetworkId.BSC, new BigDecimal("0.0001")),
            Map.entry(NetworkId.AVALANCHE, new BigDecimal("0.005")),
            Map.entry(NetworkId.MANTLE, new BigDecimal("0.1")),
            Map.entry(NetworkId.LINEA, new BigDecimal("0.02")),
            Map.entry(NetworkId.UNICHAIN, new BigDecimal("0.001")),
            Map.entry(NetworkId.KATANA, new BigDecimal("0.001")),
            Map.entry(NetworkId.PLASMA, new BigDecimal("0.001")),
            Map.entry(NetworkId.ZKSYNC, new BigDecimal("0.001"))
    );

    private SponsoredGasTopUpSupport() {
    }

    public static Optional<ProtocolRegistryEntry> findVerifiedSender(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryService protocolRegistryService
    ) {
        if (!hasSponsoredGasShape(view, movementLegs) || protocolRegistryService == null) {
            return Optional.empty();
        }
        return protocolRegistryService.lookup(view.networkId(), view.fromAddress())
                .filter(entry -> entry.role() == ProtocolRegistryRole.GAS_PAYER)
                .filter(entry -> fitsNetworkEnvelope(view.networkId(), principalQuantity(movementLegs)));
    }

    private static boolean hasSponsoredGasShape(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view == null || movementLegs == null || movementLegs.isEmpty()) {
            return false;
        }
        if (view.networkId() == null || view.walletAddress() == null || view.toAddress() == null) {
            return false;
        }
        if (!view.walletAddress().equalsIgnoreCase(view.toAddress())) {
            return false;
        }
        if (view.rawValue() == null || view.rawValue().signum() <= 0) {
            return false;
        }
        if (!view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty()) {
            return false;
        }
        if (view.methodId() != null && !"0x".equals(view.methodId())) {
            return false;
        }
        String functionName = view.functionName();
        if (functionName != null && !functionName.isBlank()) {
            return false;
        }
        List<RawLeg> effectiveLegs = movementLegs.stream()
                .filter(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() != 0)
                .toList();
        if (effectiveLegs.size() != 1) {
            return false;
        }
        RawLeg principal = effectiveLegs.getFirst();
        if (principal.quantityDelta().signum() <= 0) {
            return false;
        }
        if (principal.assetContract() != null && !principal.assetContract().isBlank()) {
            return false;
        }
        BigDecimal rawValueQuantity = new BigDecimal(view.rawValue()).movePointLeft(18);
        return rawValueQuantity.compareTo(principal.quantityDelta().abs()) == 0;
    }

    private static BigDecimal principalQuantity(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0)
                .map(leg -> leg.quantityDelta().abs())
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private static boolean fitsNetworkEnvelope(NetworkId networkId, BigDecimal quantity) {
        if (networkId == null || quantity == null || quantity.signum() <= 0) {
            return false;
        }
        BigDecimal maxNativeQty = MAX_NATIVE_QTY_BY_NETWORK.get(networkId);
        if (maxNativeQty == null) {
            return false;
        }
        return quantity.compareTo(maxNativeQty) <= 0;
    }
}
