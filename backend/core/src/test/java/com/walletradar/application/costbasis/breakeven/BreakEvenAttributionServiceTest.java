package com.walletradar.application.costbasis.breakeven;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BreakEvenAttributionServiceTest {

    private final BreakEvenAttributionService service =
            new BreakEvenAttributionService(new BreakEvenAttributionLoader(new ObjectMapper()));

    @Test
    void stakedDerivativeFamilyRedirectsToEthViaCluster() {
        assertThat(service.resolveTarget("FAMILY:METH", "CMETH")).isEqualTo("FAMILY:ETH");
        assertThat(service.resolveTarget("FAMILY:METH", "METH")).isEqualTo("FAMILY:ETH");
        assertThat(service.resolveTarget("FAMILY:STETH", "STETH")).isEqualTo("FAMILY:ETH");
    }

    @Test
    void ethFamilyResolvesToSelfBecauseClusterTargetEqualsFamily() {
        assertThat(service.resolveTarget("FAMILY:ETH", "ETH")).isEqualTo("FAMILY:ETH");
    }

    @Test
    void unrelatedFamilyResolvesToSelf() {
        assertThat(service.resolveTarget("FAMILY:USDC", "USDC")).isEqualTo("FAMILY:USDC");
        assertThat(service.resolveTarget("FAMILY:BTC", "BTC")).isEqualTo("FAMILY:BTC");
    }

    @Test
    void blankOrNullFamilyReturnedUnchanged() {
        assertThat(service.resolveTarget(null, "ETH")).isNull();
        assertThat(service.resolveTarget("  ", "ETH")).isEqualTo("  ");
    }

    @Test
    void explicitFamilyMappingOverridesCluster() {
        BreakEvenAttributionLoader.LoadedBreakEvenAttribution loaded =
                new BreakEvenAttributionLoader(new ObjectMapper())
                        .load(new java.io.ByteArrayInputStream((
                                "{\"version\":1,\"attributions\":["
                                        + "{\"source\":\"CLUSTER:ETH_STAKING\",\"target\":\"FAMILY:ETH\"},"
                                        + "{\"source\":\"FAMILY:METH\",\"target\":\"FAMILY:MNT\"}]}"
                        ).getBytes()), "inline");
        BreakEvenAttributionService overridden = new BreakEvenAttributionService(new BreakEvenAttributionLoader(new ObjectMapper()) {
            @Override
            public LoadedBreakEvenAttribution loadFromClasspath() {
                return loaded;
            }
        });
        assertThat(overridden.resolveTarget("FAMILY:METH", "CMETH")).isEqualTo("FAMILY:MNT");
        assertThat(overridden.resolveTarget("FAMILY:STETH", "STETH")).isEqualTo("FAMILY:ETH");
    }

    @Test
    void childFamiliesOfEthIncludeStakedDerivatives() {
        Set<String> children = service.resolveChildFamilies("FAMILY:ETH");
        assertThat(children).contains("FAMILY:METH", "FAMILY:STETH", "FAMILY:WSTETH");
        assertThat(children).doesNotContain("FAMILY:ETH");
    }

    @Test
    void childFamiliesOfUnrelatedFamilyIsEmpty() {
        assertThat(service.resolveChildFamilies("FAMILY:USDC")).isEmpty();
    }
}
