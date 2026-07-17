package com.walletradar.application.costbasis.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-054 / ADR-060: {@link AccountingAssetClassificationSupport} (the C1/C2 registry) is the single
 * source of accounting-family identity. These tests assert that the registry resolves through
 * {@link AccountingAssetFamilySupport#continuityIdentity} exactly as declared, and that the W9
 * consolidation left only the documented {@code AAVASAVAX} supplemental entry outside it.
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
    void registryC2FamiliesResolveToDeclaredFamily() {
        for (Map.Entry<String, String> entry : AccountingAssetClassificationSupport.c2ContinuityFamilies().entrySet()) {
            assertThat(AccountingAssetFamilySupport.continuityIdentity(entry.getKey(), null))
                    .as("continuityIdentity for C2 %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void supplementalFamiliesHoldOnlyAaveSavaxAndNeverShadowRegistry() {
        assertThat(AccountingAssetFamilySupport.SUPPLEMENTAL_FAMILIES)
                .as("W9 residue is exactly the aAvaSAVAX supplemental entry (ADR-060)")
                .containsExactlyInAnyOrderEntriesOf(Map.of("AAVASAVAX", "FAMILY:SAVAX"));
        for (String symbol : AccountingAssetFamilySupport.SUPPLEMENTAL_FAMILIES.keySet()) {
            assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity(symbol, null))
                    .as("supplemental %s must not be classified by the registry", symbol)
                    .isNull();
        }
    }

    @Test
    void noC2SymbolResolvesToEthFamily() {
        for (String symbol : AccountingAssetClassificationSupport.c2DistinctAssetSymbols()) {
            assertThat(AccountingAssetFamilySupport.continuityIdentity(symbol, null))
                    .as("C2 symbol %s must not resolve to FAMILY:ETH", symbol)
                    .isNotEqualTo("FAMILY:ETH");
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
