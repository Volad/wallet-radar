package com.walletradar.application.costbasis.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-054: C1/C2 registry must stay aligned with {@link AccountingAssetFamilySupport#SYMBOL_FAMILIES}.
 */
class AccountingAssetClassificationArchTest {

    @Test
    void everyC2SymbolMapsToItsOwnFamilyNeverEthFamily() {
        for (String symbol : AccountingAssetClassificationSupport.c2DistinctAssetSymbols()) {
            String family = AccountingAssetFamilySupport.continuityIdentity(symbol, null);
            assertThat(family)
                    .as("C2 symbol %s must have own family", symbol)
                    .isEqualTo("FAMILY:" + symbol);
            assertThat(family).isNotEqualTo("FAMILY:ETH");
        }
    }

    @Test
    void c1EthSymbolsMapToEthFamilyNotC2Families() {
        Set<String> ethC1 = Set.of(
                "ETH", "WETH", "AWETH", "AARBWETH", "AMANWETH", "VBETH"
        );
        for (String symbol : ethC1) {
            assertThat(AccountingAssetClassificationSupport.isC1SameAsset(symbol)).isTrue();
            assertThat(AccountingAssetFamilySupport.continuityIdentity(symbol, null)).isEqualTo("FAMILY:ETH");
        }
    }

    @Test
    void registryC2FamiliesMatchSymbolFamiliesMap() {
        for (Map.Entry<String, String> entry : AccountingAssetClassificationSupport.c2ContinuityFamilies().entrySet()) {
            assertThat(AccountingAssetFamilySupport.SYMBOL_FAMILIES.get(entry.getKey()))
                    .as("SYMBOL_FAMILIES entry for C2 %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void noC2SymbolInEthFamilyBlock() {
        for (String symbol : AccountingAssetClassificationSupport.c2DistinctAssetSymbols()) {
            String mapped = AccountingAssetFamilySupport.SYMBOL_FAMILIES.get(symbol);
            if (mapped != null) {
                assertThat(mapped).isNotEqualTo("FAMILY:ETH");
            }
        }
    }

    @Test
    void eulerReceiptsMapToC2Families() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("EWETH-1", null)).isEqualTo("FAMILY:EWETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("EWEETH-1", null)).isEqualTo("FAMILY:EWEETH");
    }

    @Test
    void productionCodeHasNoTaxAvcoStrings() throws Exception {
        Path coreMain = resolveCoreMainJavaRoot();
        try (Stream<Path> paths = Files.walk(coreMain)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            assertThat(content)
                                    .as("File %s must not contain legacy Tax AVCO naming", path)
                                    .doesNotContain("Tax AVCO")
                                    .doesNotContain("taxAvco");
                        } catch (Exception exception) {
                            throw new AssertionError("Failed reading " + path, exception);
                        }
                    });
        }
    }

    private static Path resolveCoreMainJavaRoot() {
        Path fromModule = Path.of("src/main/java");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepo = Path.of("backend/core/src/main/java");
        if (Files.isDirectory(fromRepo)) {
            return fromRepo;
        }
        throw new IllegalStateException("Cannot locate backend core main sources for Tax AVCO grep gate");
    }
}
