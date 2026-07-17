package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolResourceLoaderTest {

    @Test
    void loadsProtocolResourcesByCanonicalKeyAndAlias() {
        ProtocolResourceLoader loader = new ProtocolResourceLoader(new ObjectMapper());

        assertThat(loader.find("GMX", "v2"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.key()).isEqualTo("gmx-v2");
                    assertThat(resource.capabilities()).contains("DERIVATIVES", "POOL_LIFECYCLE");
                    assertThat(resource.matchesMethodSelector("executeOrder", "0x7ebc83f7")).isTrue();
                    assertThat(resource.matchesSubcallSelector("derivativeOrderRequest", "0x6996807b")).isTrue();
                    assertThat(resource.matchesEventTopic("eventEmitter",
                            "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5")).isTrue();
                });

        assertThat(loader.find("CoWSwap", "v1"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.protocol()).isEqualTo("CoW Swap");
                    assertThat(resource.matchesMethodSelector("ethFlowRequest", "0x322bba21")).isTrue();
                    assertThat(resource.matchesEventTopic("trade",
                            "0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17")).isTrue();
                });

        assertThat(loader.find("Euler", "v1"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.matchesMethodSelector("batch", "0xc16ae7a4")).isTrue();
                    assertThat(resource.matchesMethodSelector("borrowSubcall", "0x4b3fd148")).isTrue();
                    assertThat(resource.matchesAssetMarker("debtSymbolFragments", "eUSDt-2-DEBT")).isTrue();
                    assertThat(resource.matchesAssetMarker("stableContracts",
                            "0xb57b25851fe2311cc3fe511c8f10e868932e0680")).isTrue();
                });

        assertThat(loader.find("Aave", "V3"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.key()).isEqualTo("aave");
                    assertThat(resource.matchesMethodSelector("lendingDeposit", "0x617ba037")).isTrue();
                    assertThat(resource.matchesMethodSelector("lendingWithdraw", "0x69328dec")).isTrue();
                    assertThat(resource.matchesFunctionMarker("repay", "repayWithATokens(address,uint256,uint256)")).isTrue();
                    assertThat(resource.matchesAssetMarker("debtSymbolFragments", "variableDebtAvaEURC")).isTrue();
                });
    }

    @Test
    void foldedDescriptorFieldsParseFromProtocolProfiles() {
        ProtocolResourceLoader loader = new ProtocolResourceLoader(new ObjectMapper());

        assertThat(loader.find("Aave", "V3"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.semanticClassifier()).isEqualTo("aave-v3");
                    assertThat(resource.lending()).isNotNull();
                    assertThat(resource.lending().marketRateSource())
                            .isEqualTo("protocol-registry+aave-pool-addresses-provider");
                    assertThat(resource.lending().supportsVariableDebt()).isTrue();
                    assertThat(resource.valuationSource()).isNotNull();
                    assertThat(resource.valuationSource().primarySource()).isEqualTo("aave-oracle");
                    assertThat(resource.valuationSource().fallbackSources()).containsExactly("coingecko-underlying");
                    assertThat(resource.lpPresentation()).isNull();
                });

        assertThat(loader.find("GMX", "v2"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.semanticClassifier()).isEqualTo("gmx-v2");
                    assertThat(resource.lpPresentation()).isNotNull();
                    assertThat(resource.lpPresentation().positionIdentityStrategy()).isEqualTo("gmx-market-key");
                    assertThat(resource.lpPresentation().receiptTokenPatterns()).containsExactly("GM", "GLP");
                    assertThat(resource.valuationSource()).isNotNull();
                    assertThat(resource.valuationSource().primarySource()).isEqualTo("gmx-reader");
                });

        assertThat(loader.find("Uniswap", "v3"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.semanticClassifier()).isEqualTo("uniswap-v3");
                    assertThat(resource.lpPresentation()).isNotNull();
                    assertThat(resource.lpPresentation().positionIdentityStrategy()).isEqualTo("nfpm-token-id");
                    assertThat(resource.lpPresentation().receiptTokenPatterns()).containsExactly("UNI-V3-POS");
                    assertThat(resource.lending()).isNull();
                    assertThat(resource.valuationSource()).isNull();
                });

        assertThat(loader.find("CoWSwap", "v1"))
                .isPresent()
                .get()
                .satisfies(resource -> {
                    assertThat(resource.semanticClassifier()).isNull();
                    assertThat(resource.lpPresentation()).isNull();
                    assertThat(resource.lending()).isNull();
                    assertThat(resource.valuationSource()).isNull();
                });
    }
}
