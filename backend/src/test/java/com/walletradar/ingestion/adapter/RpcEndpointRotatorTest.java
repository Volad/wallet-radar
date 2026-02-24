package com.walletradar.ingestion.adapter;

import com.walletradar.common.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpcEndpointRotatorTest {

    @Test
    void getNextEndpoint_roundRobins() {
        RpcEndpointRotator rotator = new RpcEndpointRotator(
                List.of("https://a.com", "https://b.com", "https://c.com"),
                RetryPolicy.defaultPolicy());
        assertThat(rotator.getNextEndpoint()).isEqualTo("https://a.com");
        assertThat(rotator.getNextEndpoint()).isEqualTo("https://b.com");
        assertThat(rotator.getNextEndpoint()).isEqualTo("https://c.com");
        assertThat(rotator.getNextEndpoint()).isEqualTo("https://a.com");
    }

    @Test
    void getNextEndpoint_singleEndpoint_alwaysSame() {
        RpcEndpointRotator rotator = new RpcEndpointRotator(List.of("https://only.com"), null);
        IntStream.range(0, 5).forEach(i -> assertThat(rotator.getNextEndpoint()).isEqualTo("https://only.com"));
    }

    @Test
    void retryDelayMs_usesPolicy() {
        RpcEndpointRotator rotator = new RpcEndpointRotator(
                List.of("https://a.com"),
                new RetryPolicy(100L, 0, 3));
        assertThat(rotator.retryDelayMs(0)).isEqualTo(100L);
        assertThat(rotator.retryDelayMs(1)).isEqualTo(200L);
    }

    @Test
    void constructor_emptyEndpoints_throws() {
        assertThatThrownBy(() -> new RpcEndpointRotator(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one endpoint");
        assertThatThrownBy(() -> new RpcEndpointRotator(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getEndpoints_returnsUnmodifiableList() {
        List<String> list = List.of("https://a.com");
        RpcEndpointRotator rotator = new RpcEndpointRotator(list, null);
        assertThat(rotator.getEndpoints()).isEqualTo(list);
    }
}
