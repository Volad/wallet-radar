package com.walletradar.ingestion.adapter;

import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.adapter.evm.EvmBatchBlockSizeResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvmBatchBlockSizeResolverTest {

    @Test
    void resolve_unknownNetworkId_returnsDefault() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ETHEREUM)).isEqualTo(2000);
        assertThat(resolver.resolve(NetworkId.ARBITRUM)).isEqualTo(2000);
    }

    @Test
    void resolve_nullNetworkId_returnsDefault() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ETHEREUM", entry(1000)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(null)).isEqualTo(2000);
    }

    @Test
    void resolve_configuredNetwork_returnsConfiguredValue() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of(
                "ETHEREUM", entry(2000),
                "ARBITRUM", entry(1000),
                "POLYGON", entry(500)
        ));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ETHEREUM)).isEqualTo(2000);
        assertThat(resolver.resolve(NetworkId.ARBITRUM)).isEqualTo(1000);
        assertThat(resolver.resolve(NetworkId.POLYGON)).isEqualTo(500);
    }

    @Test
    void resolve_invalidZero_returnsDefault() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ETHEREUM", entry(0)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ETHEREUM)).isEqualTo(2000);
    }

    @Test
    void resolve_invalidNegative_returnsDefault() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ARBITRUM", entry(-1)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ARBITRUM)).isEqualTo(2000);
    }

    @Test
    void resolve_aboveCap_returnsDefault() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ETHEREUM", entry(15_000)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ETHEREUM)).isEqualTo(2000);
    }

    @Test
    void resolve_atCap_returnsCap() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ETHEREUM", entry(10_000)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ETHEREUM)).isEqualTo(10_000);
    }

    @Test
    void resolve_minValid_returnsOne() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ETHEREUM", entry(1)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);

        assertThat(resolver.resolve(NetworkId.ETHEREUM)).isEqualTo(1);
    }

    private static IngestionNetworkProperties.NetworkIngestionEntry entry(int batchBlockSize) {
        IngestionNetworkProperties.NetworkIngestionEntry e = new IngestionNetworkProperties.NetworkIngestionEntry();
        e.setUrls(List.of("https://example.com"));
        e.setBatchBlockSize(batchBlockSize);
        return e;
    }
}
