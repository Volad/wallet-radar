package com.walletradar.ingestion.pipeline.classification.onchain.protocol;

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
}
