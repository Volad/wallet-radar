package com.walletradar.platform.networks.solana.metaplex;

import com.walletradar.platform.networks.solana.metaplex.MetaplexMetadataClient.MetaplexTokenMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Borsh decode tests for {@link MetaplexMetadataDecoder}. The fixture is a real Metaplex
 * {@code Metadata} account prefix (key=4, 32-byte update-authority + mint, then puffed Borsh strings
 * name="USD Coin", symbol="USDC"), NUL-padded exactly as Metaplex stores them.
 */
class MetaplexMetadataDecoderTest {

    private static final String METADATA_ACCOUNT_BASE64 =
            "BBERERERERERERERERERERERERERERERERERERERERERxvp6877brTo9ZfNqq8l0MbG75MLS9uDk"
                    + "fKYCA0UvXWEgAAAAVVNEIENvaW4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKAAAAVVNEQwAAAAAA"
                    + "AMgAAABodHRwczovL2V4YW1wbGUuY29tL3UuanNvbgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    @DisplayName("decodes puffed name/symbol and strips trailing NUL padding")
    void decodesNameAndSymbol() {
        byte[] data = Base64.getDecoder().decode(METADATA_ACCOUNT_BASE64);

        Optional<MetaplexTokenMetadata> decoded = MetaplexMetadataDecoder.decode(data);

        assertThat(decoded).isPresent();
        assertThat(decoded.get().name()).isEqualTo("USD Coin");
        assertThat(decoded.get().symbol()).isEqualTo("USDC");
    }

    @Test
    @DisplayName("truncated / empty data resolves to empty, never throws")
    void truncatedDataResolvesEmpty() {
        assertThat(MetaplexMetadataDecoder.decode(null)).isEmpty();
        assertThat(MetaplexMetadataDecoder.decode(new byte[0])).isEmpty();
        assertThat(MetaplexMetadataDecoder.decode(new byte[64])).isEmpty();
    }
}
