package com.walletradar.lending.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class LendingProtocolNameSupport {

    private LendingProtocolNameSupport() {
    }

    static String displayProtocol(String protocolName) {
        return displayProtocol(protocolName, List.of());
    }

    static String displayProtocol(String protocolName, String assetSymbol) {
        return displayProtocol(protocolName, List.of(assetSymbol));
    }

    static String resolveProtocol(NormalizedTransaction transaction) {
        if (transaction == null) {
            return "Unknown lending";
        }
        return displayProtocol(
                transaction.getProtocolName(),
                transaction.getFlows() == null
                        ? List.of()
                        : transaction.getFlows().stream()
                        .map(NormalizedTransaction.Flow::getAssetSymbol)
                        .toList()
        );
    }

    static boolean isKnownLendingProtocol(String protocolName) {
        return switch (normalizeProtocolKey(displayProtocol(protocolName))) {
            case "AAVE", "EULER", "MORPHO", "FLUID", "COMPOUND", "SILO" -> true;
            default -> false;
        };
    }

    private static String displayProtocol(String protocolName, List<String> assetSymbols) {
        String normalized = protocolName == null ? "" : protocolName.trim();
        if (!normalized.isBlank()) {
            return knownProtocol(normalized).orElse(normalized);
        }
        return assetSymbols.stream()
                .map(LendingProtocolNameSupport::protocolFromAssetSymbol)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse("Unknown lending");
    }

    private static Optional<String> knownProtocol(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("AAVE")) {
            return Optional.of("Aave");
        }
        if (upper.contains("EULER")) {
            return Optional.of("Euler");
        }
        if (upper.contains("MORPHO")) {
            return Optional.of("Morpho");
        }
        if (upper.contains("FLUID")) {
            return Optional.of("Fluid");
        }
        if (upper.contains("COMPOUND")) {
            return Optional.of("Compound");
        }
        if (upper.contains("SILO")) {
            return Optional.of("Silo");
        }
        return Optional.empty();
    }

    private static Optional<String> protocolFromAssetSymbol(String assetSymbol) {
        String normalized = LendingAssetSymbolSupport.normalizeSymbol(assetSymbol).replace("-", "");
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (normalized.contains("AAVE")
                || normalized.startsWith("AARB")
                || normalized.startsWith("AMAN")
                || normalized.startsWith("AMANA")
                || normalized.startsWith("AAVA")
                || normalized.startsWith("ABAS")
                || normalized.startsWith("AZKS")
                || normalized.startsWith("ALIN")
                || normalized.startsWith("AOPT")
                || normalized.startsWith("APOL")
                || normalized.startsWith("AETH")) {
            return Optional.of("Aave");
        }
        if (normalized.startsWith("EUSDC")
                || normalized.startsWith("EUSDT")
                || normalized.startsWith("EETH")) {
            return Optional.of("Euler");
        }
        if (normalized.startsWith("FUSDC")
                || normalized.startsWith("FUSDT")
                || normalized.startsWith("FDAI")
                || normalized.startsWith("FWETH")
                || normalized.startsWith("FETH")) {
            return Optional.of("Fluid");
        }
        if (normalized.startsWith("CUSDC")
                || normalized.startsWith("CUSDT")
                || normalized.startsWith("CDAI")
                || normalized.startsWith("CWETH")
                || normalized.startsWith("CETH")) {
            return Optional.of("Compound");
        }
        if (normalized.startsWith("SOUSDC")
                || normalized.startsWith("SOUSDT")
                || normalized.startsWith("SOWETH")) {
            return Optional.of("Silo");
        }
        return Optional.empty();
    }

    private static String normalizeProtocolKey(String protocolName) {
        return protocolName == null ? "" : protocolName.trim().toUpperCase(Locale.ROOT);
    }
}
