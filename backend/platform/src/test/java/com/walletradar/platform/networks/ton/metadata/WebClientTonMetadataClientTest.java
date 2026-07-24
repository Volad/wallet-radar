package com.walletradar.platform.networks.ton.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.ton.metadata.WebClientTonMetadataClient.OffChainContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the WS-7 (Cluster C) TON off-chain jetton symbol resolution helpers: when TON Center
 * returns decimals-only on-chain content, the client follows the content URI to read the symbol.
 */
class WebClientTonMetadataClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("content URI is read from jetton_content (uri/_uri/content_uri), else the master node")
    void extractsContentUri() throws Exception {
        JsonNode withUri = objectMapper.readTree("{\"decimals\":\"9\",\"uri\":\"https://x/meta.json\"}");
        assertThat(WebClientTonMetadataClient.contentUri(withUri, objectMapper.readTree("{}")))
                .isEqualTo("https://x/meta.json");

        JsonNode content = objectMapper.readTree("{\"decimals\":\"9\"}");
        JsonNode master = objectMapper.readTree("{\"_uri\":\"ipfs://cid/meta.json\"}");
        assertThat(WebClientTonMetadataClient.contentUri(content, master)).isEqualTo("ipfs://cid/meta.json");

        assertThat(WebClientTonMetadataClient.contentUri(content, objectMapper.readTree("{}"))).isNull();
    }

    @Test
    @DisplayName("http(s) URIs pass through; ipfs:// maps to a public gateway; others reject")
    void normalizesContentUri() {
        assertThat(WebClientTonMetadataClient.normalizeContentUri("https://x/meta.json"))
                .isEqualTo("https://x/meta.json");
        assertThat(WebClientTonMetadataClient.normalizeContentUri("ipfs://bafy/meta.json"))
                .isEqualTo("https://ipfs.io/ipfs/bafy/meta.json");
        assertThat(WebClientTonMetadataClient.normalizeContentUri("ftp://x/meta.json")).isNull();
        assertThat(WebClientTonMetadataClient.normalizeContentUri("  ")).isNull();
        assertThat(WebClientTonMetadataClient.normalizeContentUri(null)).isNull();
    }

    @Test
    @DisplayName("off-chain JSON yields symbol + decimals (decimals may be a string)")
    void parsesOffChainContent() throws Exception {
        OffChainContent content = WebClientTonMetadataClient.parseOffChainContent(
                objectMapper.readTree("{\"name\":\"Gram\",\"symbol\":\"GRAM\",\"decimals\":\"9\"}"));
        assertThat(content).isNotNull();
        assertThat(content.symbol()).isEqualTo("GRAM");
        assertThat(content.decimals()).isEqualTo(9);

        assertThat(WebClientTonMetadataClient.parseOffChainContent(objectMapper.readTree("{}"))).isNull();
        assertThat(WebClientTonMetadataClient.parseOffChainContent(null)).isNull();
    }
}
