package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.application.normalization.pipeline.metadata.ResolvedTokenMetadata;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.ton.metadata.TonMetadataClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the live TON jetton metadata resolver (WS-7): it supports only TON, upper-cases the symbol,
 * and never throws (venue errors resolve to empty).
 */
class TonJettonMetadataResolverTest {

    private static final String XAUT_MASTER = "0:3547f2ee4022c794c80ea354b81bb63b5b571dd05ac091b035d19abbadd74ac6";

    private final TonMetadataClient client = mock(TonMetadataClient.class);
    private final TonJettonMetadataResolver resolver = new TonJettonMetadataResolver(client);

    @Test
    void supportsOnlyTon() {
        assertThat(resolver.supports(NetworkId.TON)).isTrue();
        assertThat(resolver.supports(NetworkId.SOLANA)).isFalse();
        assertThat(resolver.supports(NetworkId.ETHEREUM)).isFalse();
    }

    @Test
    void resolvesLiveMetadataUpperCasingSymbol() {
        when(client.fetchJettonMetadata(XAUT_MASTER))
                .thenReturn(Optional.of(new TonMetadataClient.TonJettonMetadata("xaut0", 6)));

        Optional<ResolvedTokenMetadata> resolved = resolver.resolve(NetworkId.TON, XAUT_MASTER);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().symbol()).isEqualTo("XAUT0");
        assertThat(resolved.get().decimals()).isEqualTo(6);
        assertThat(resolved.get().source()).isEqualTo(ResolvedTokenMetadata.Source.LIVE_RESOLVER);
    }

    @Test
    void unresolvableMasterResolvesToEmpty() {
        when(client.fetchJettonMetadata(anyString())).thenReturn(Optional.empty());
        assertThat(resolver.resolve(NetworkId.TON, XAUT_MASTER)).isEmpty();
    }

    @Test
    void venueErrorNeverThrows() {
        when(client.fetchJettonMetadata(anyString())).thenThrow(new RuntimeException("boom"));
        assertThat(resolver.resolve(NetworkId.TON, XAUT_MASTER)).isEmpty();
    }

    @Test
    void nonTonNetworkResolvesToEmpty() {
        assertThat(resolver.resolve(NetworkId.SOLANA, XAUT_MASTER)).isEmpty();
    }
}
