package com.walletradar.application.lending.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class LendingProtocolNameSupport {

    static final String AAVE = "Aave";
    static final String EULER = "Euler";
    static final String MORPHO = "Morpho";
    static final String FLUID = "Fluid";
    static final String COMPOUND = "Compound";
    static final String SILO = "Silo";

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
            return Optional.of(AAVE);
        }
        if (upper.contains("EULER")) {
            return Optional.of(EULER);
        }
        if (upper.contains("MORPHO")) {
            return Optional.of(MORPHO);
        }
        if (upper.contains("FLUID")) {
            return Optional.of(FLUID);
        }
        if (upper.contains("COMPOUND")) {
            return Optional.of(COMPOUND);
        }
        if (upper.contains("SILO")) {
            return Optional.of(SILO);
        }
        return Optional.empty();
    }

    static Optional<String> protocolFromAssetSymbol(String assetSymbol) {
        String normalized = LendingAssetSymbolSupport.normalizeSymbol(assetSymbol);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (normalized.startsWith("VARIABLEDEBT") || normalized.startsWith("STABLEDEBT")) {
            return Optional.of(AAVE);
        }
        if (matchesEulerIndexedReceipt(normalized)) {
            return Optional.of(EULER);
        }
        String compact = normalized.replace("-", "");
        if (compact.contains("AAVE")
                || compact.startsWith("AARB")
                || compact.startsWith("AMAN")
                || compact.startsWith("AMANA")
                || compact.startsWith("AAVA")
                || compact.startsWith("ABAS")
                || compact.startsWith("AZKS")
                || compact.startsWith("ALIN")
                || compact.startsWith("AOPT")
                || compact.startsWith("APOL")
                || compact.startsWith("AETH")
                || compact.startsWith("AWETH")
                || compact.startsWith("AUSDC")
                || compact.startsWith("AUSDT")
                || compact.startsWith("ADAI")) {
            return Optional.of(AAVE);
        }
        if (compact.startsWith("EUSDC")
                || compact.startsWith("EUSDT")
                || compact.startsWith("EETH")) {
            return Optional.of(EULER);
        }
        if (compact.startsWith("GT")
                || compact.startsWith("MC")
                || compact.startsWith("RE7")
                || compact.startsWith("SYRUP")) {
            return Optional.of(MORPHO);
        }
        if (compact.startsWith("FUSDC")
                || compact.startsWith("FUSDT")
                || compact.startsWith("FDAI")
                || compact.startsWith("FWETH")
                || compact.startsWith("FETH")) {
            return Optional.of(FLUID);
        }
        if (compact.startsWith("CUSDC")
                || compact.startsWith("CUSDT")
                || compact.startsWith("CDAI")
                || compact.startsWith("CWETH")
                || compact.startsWith("CETH")) {
            return Optional.of(COMPOUND);
        }
        if (compact.startsWith("SOUSDC")
                || compact.startsWith("SOUSDT")
                || compact.startsWith("SOWETH")) {
            return Optional.of(SILO);
        }
        return Optional.empty();
    }

    private static String normalizeProtocolKey(String protocolName) {
        return protocolName == null ? "" : protocolName.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean matchesEulerIndexedReceipt(String normalized) {
        int dash = normalized.indexOf('-');
        if (dash <= 1 || dash >= normalized.length() - 1) {
            return false;
        }
        if (!normalized.startsWith("E")) {
            return false;
        }
        String suffix = normalized.substring(dash + 1);
        if (suffix.isBlank()) {
            return false;
        }
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
