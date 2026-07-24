package com.walletradar.platform.networks.ton.price;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the canonical jetton-master key normalisation that lets STON.fi ({@code EQ…} friendly) and
 * on-chain balance ({@code 0:hex}) address forms resolve to a single lookup key.
 */
class WebClientTonPriceClientTest {

    @Test
    void rawAddressLowercasedInPlace() {
        String raw = "0:3690254DC15B2297610CDA60744A45F2B710AA4234B89ADB630E99D79B01BD4F";
        assertThat(WebClientTonPriceClient.rawKey(raw))
                .isEqualTo("0:3690254dc15b2297610cda60744a45f2b710aa4234b89adb630e99d79b01bd4f");
    }

    @Test
    void nullOrBlankOrNonTonYieldsNull() {
        assertThat(WebClientTonPriceClient.rawKey(null)).isNull();
        assertThat(WebClientTonPriceClient.rawKey("   ")).isNull();
        assertThat(WebClientTonPriceClient.rawKey("not-a-ton-address")).isNull();
    }

    @Test
    void friendlyAddressResolvesToRawForm() {
        // USDT-TON master friendly form → its raw 0:hex form (the shared lookup key). We assert the
        // key is a well-formed raw address; the exact hex is validated by the canonicaliser itself.
        String friendly = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";
        String key = WebClientTonPriceClient.rawKey(friendly);
        assertThat(key).isNotNull();
        assertThat(key).matches("^-?\\d+:[0-9a-f]{64}$");
    }
}
