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
                        NetworkId.ARBITRUM,
                        "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.PLASMA,
                        "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.UNICHAIN,
                        "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.ARBITRUM,
                        "0x94312a608246cecfce6811db84b3ef4b2619054e"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.MANTLE,
                        "0x70f61901658aafb7ae57da0c30695ce4417e72b9"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.MANTLE,
                        "0x0045601c3c4c561012c108ea84a81e36eac24296"
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
                        NetworkId.OPTIMISM,
                        "0x416b433906b1b72fa758e166e239c43d68dc6f29"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.OPTIMISM,
                        "0x0f5212f63ba8eab0fabd94fc2071d461d9d6ddb2"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.OPTIMISM,
                        "0xc762d18800b3f78ae56e9e61ad7be98a413d59de"
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
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.MANTLE,
                        "0x888888888889758f76e7103c6cbf23abbf58f946"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.LINEA,
                        "0x5828a3c0f07c6b841205d12660e0abb869bf98dc"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.MANTLE,
                        "0xed884f0460a634c69dbb7def54858465808aacef"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.KATANA,
                        "0xac4c6e212a361c968f1725b4d055b47e63f80b75"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.KATANA,
                        "0x3067bdba0e6628497d527bef511c22da8b32ca3f"
                ));
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.ARBITRUM,
                "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13"
        )).specialHandler()).isEqualTo(ProtocolRegistrySpecialHandlerType.MORPHO_BUNDLER);
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.MANTLE,
                "0x888888888889758f76e7103c6cbf23abbf58f946"
        )).protocolName()).isEqualTo("Pendle");
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.KATANA,
                "0x3067bdba0e6628497d527bef511c22da8b32ca3f"
        )).protocolName()).isEqualTo("SushiSwap");
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.KATANA,
                "0x3067bdba0e6628497d527bef511c22da8b32ca3f"
        )).role()).isEqualTo(com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole.POSITION_MANAGER);
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
    @DisplayName("supports explicit address override so the same address can map to different protocols on different networks")
    void supportsExplicitAddressOverrideForCrossNetworkAddressReuse() {
        String json = """
                {
                  "supported_networks": ["ARBITRUM", "AVALANCHE", "MANTLE"],
                  "families": ["DEX", "AGGREGATOR"],
                  "contracts": {
                    "lfj-aggregator-arb-avax": {
                      "address": "0x45a62b090df48243f12a21897e7ed91863e2c86b",
                      "name": "LFJ Joe Aggregator",
                      "protocol": "LFJ",
                      "version": "Aggregator",
                      "family": "AGGREGATOR",
                      "role": "ROUTER",
                      "networks": ["ARBITRUM", "AVALANCHE"],
                      "confidence": "HIGH"
                    },
                    "merchant-moe-aggregator-mantle": {
                      "address": "0x45a62b090df48243f12a21897e7ed91863e2c86b",
                      "name": "Merchant Moe Aggregator",
                      "protocol": "Merchant Moe",
                      "version": "V1",
                      "family": "AGGREGATOR",
                      "role": "ROUTER",
                      "networks": ["MANTLE"],
                      "confidence": "HIGH"
                    }
                  },
                  "method_ids": {}
                }
                """;

        ProtocolRegistryLoader.LoadedProtocolRegistry loaded = loader.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "inline"
        );

        assertThat(loaded.entriesByKey())
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.AVALANCHE,
                        "0x45a62b090df48243f12a21897e7ed91863e2c86b"
                ))
                .containsKey(new ProtocolRegistryLoader.RegistryKey(
                        NetworkId.MANTLE,
                        "0x45a62b090df48243f12a21897e7ed91863e2c86b"
                ));
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.AVALANCHE,
                "0x45a62b090df48243f12a21897e7ed91863e2c86b"
        )).protocolName()).isEqualTo("LFJ");
        assertThat(loaded.entriesByKey().get(new ProtocolRegistryLoader.RegistryKey(
                NetworkId.MANTLE,
                "0x45a62b090df48243f12a21897e7ed91863e2c86b"
        )).protocolName()).isEqualTo("Merchant Moe");
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
