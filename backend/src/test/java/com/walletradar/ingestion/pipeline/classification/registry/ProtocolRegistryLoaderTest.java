package com.walletradar.ingestion.pipeline.classification.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolRegistryLoaderTest {

    private final ProtocolRegistryLoader loader = new ProtocolRegistryLoader(new ObjectMapper());

    @Test
    @DisplayName("loads protocol registry from classpath resource")
    void loadsProtocolRegistryFromClasspathResource() {
        ProtocolRegistryLoader.LoadedProtocolRegistry loaded = loader.loadFromClasspath();

        assertThat(loaded.entriesByKey()).isNotEmpty();
        assertThat(loaded.entriesByKey())
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ETHEREUM,
                        "0xba12222222228d8ba445958a75a0704d566bf2c8"
                ));
        assertThat(loaded.entriesByKey())
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ZKSYNC,
                        "0x341e94069f53234fe6dabef707ad424830525715"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ZKSYNC,
                        "0xdaee41e335322c85ff2c5a6745c98e1351806e98"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ARBITRUM,
                        "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ARBITRUM,
                        "0x5e09acf80c0296740ec5d6f643005a4ef8daa694"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BASE,
                        "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ARBITRUM,
                        "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.UNICHAIN,
                        "0x4529a01c7a0410167c5740c487a8de60232617bf"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BASE,
                        "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.UNICHAIN,
                        "0x6f7d514bbd4aff3bcd1140b7344b32f063dee486"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BSC,
                        "0x55f4c8aba71a1e923edc303eb4feff14608cc226"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BSC,
                        "0x7816f1711828c52eb3ca5a2f075a0c06e0548bd6"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BSC,
                        "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BSC,
                        "0xea64df3a17b5172bfaf0e4215660cdec22ee7d57"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.BSC,
                        "0x212102fc6d0ed9ee784b25404db02b22b1e6dc42"
                ));
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.ARBITRUM,
                "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13"
        )).specialHandler()).isEqualTo(ProtocolRegistrySpecialHandlerType.MORPHO_BUNDLER);
        assertThat(loaded.methodDescriptions()).containsKey("0x0ad58d2f");
    }

    @Test
    @DisplayName("ignores event_topics and decorative keys while loading")
    void ignoresEventTopicsAndDecorativeKeys() {
        String json = """
                {
                  "supported_networks": ["ETHEREUM"],
                  "families": ["DEX"],
                  "contracts": {
                    "── DEX ──": {},
                    "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa": {
                      "name": "Test Router",
                      "protocol": "Test",
                      "version": "V1",
                      "family": "DEX",
                      "role": "ROUTER",
                      "event_type": "SWAP",
                      "networks": ["ETHEREUM"],
                      "confidence": "HIGH"
                    }
                  },
                  "method_ids": {
                    "description": "metadata only",
                    "0x12345678": "swap()"
                  },
                  "event_topics": {
                    "description": "should be ignored",
                    "swap": { "topic": "0xabc", "event": "Swap(...)" }
                  }
                }
                """;

        ProtocolRegistryLoader.LoadedProtocolRegistry loaded = loader.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "inline"
        );

        assertThat(loaded.entriesByKey()).hasSize(1);
        assertThat(loaded.methodDescriptions()).containsEntry("0x12345678", "swap()");
    }

    @Test
    @DisplayName("fails fast on unsupported family values")
    void failsFastOnUnsupportedFamilyValues() {
        String json = """
                {
                  "supported_networks": ["ETHEREUM"],
                  "families": ["DEX"],
                  "contracts": {
                    "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa": {
                      "name": "Bad Router",
                      "protocol": "Bad",
                      "version": "V1",
                      "family": "UNKNOWN_FAMILY",
                      "role": "ROUTER",
                      "event_type": "SWAP",
                      "networks": ["ETHEREUM"],
                      "confidence": "HIGH"
                    }
                  },
                  "method_ids": {}
                }
                """;

        assertThatThrownBy(() -> loader.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "inline"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported ProtocolRegistryFamily value");
    }
}
