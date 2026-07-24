package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR3 RC-T1.3: USDT-TON master is config-seeded to 6 decimals and resolvable across canonical
 * forms (friendly {@code EQ…} and the raw {@code 0:hex} form TON Center emits in jetton payloads).
 */
class TonJettonMetadataRegistryTest {

    private static final String USDT_MASTER_FRIENDLY = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";

    @Test
    void usdtTonResolvesSixDecimalsFromFriendlyForm() {
        assertThat(TonJettonMetadataRegistry.decimals(USDT_MASTER_FRIENDLY)).isEqualTo(6);
        assertThat(TonJettonMetadataRegistry.symbol(USDT_MASTER_FRIENDLY)).isEqualTo("USDT");
    }

    @Test
    void usdtTonResolvesSixDecimalsFromRawForm() {
        // The seeded friendly master and its raw workchain:hex form must resolve the same entry.
        String rawForm = TonAddressCanonicalizer.lookupKeys(USDT_MASTER_FRIENDLY).stream()
                .filter(key -> key.contains(":"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a decodable raw form for USDT master"));
        assertThat(TonJettonMetadataRegistry.decimals(rawForm)).isEqualTo(6);
    }

    @Test
    void unknownJettonMasterReturnsNull() {
        List<String> keys = TonAddressCanonicalizer.lookupKeys("0:" + "a".repeat(64));
        assertThat(keys).isNotEmpty();
        assertThat(TonJettonMetadataRegistry.decimals("0:" + "a".repeat(64))).isNull();
        assertThat(TonJettonMetadataRegistry.symbol(null)).isNull();
    }
}
