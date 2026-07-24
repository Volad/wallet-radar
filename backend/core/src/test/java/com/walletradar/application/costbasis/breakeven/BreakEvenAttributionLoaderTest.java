package com.walletradar.application.costbasis.breakeven;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakEvenAttributionLoaderTest {

    private final BreakEvenAttributionLoader loader = new BreakEvenAttributionLoader(new ObjectMapper());

    @Test
    void classpathResourceParsesOffsetLaneNetByDefault() {
        assertThat(loader.loadFromClasspath().offsetLane()).isEqualTo(OffsetLane.NET);
    }

    @Test
    void offsetLaneDefaultsToNetWhenAbsent() {
        BreakEvenAttributionLoader.LoadedBreakEvenAttribution loaded =
                load("{\"version\":1,\"attributions\":[]}");
        assertThat(loaded.offsetLane()).isEqualTo(OffsetLane.NET);
    }

    @Test
    void offsetLaneDefaultsToNetWhenNullOrBlank() {
        assertThat(load("{\"version\":1,\"offsetLane\":null,\"attributions\":[]}").offsetLane())
                .isEqualTo(OffsetLane.NET);
        assertThat(load("{\"version\":1,\"offsetLane\":\"   \",\"attributions\":[]}").offsetLane())
                .isEqualTo(OffsetLane.NET);
    }

    @Test
    void offsetLaneParsesNetAndMarketCaseInsensitively() {
        assertThat(load("{\"version\":1,\"offsetLane\":\"NET\",\"attributions\":[]}").offsetLane())
                .isEqualTo(OffsetLane.NET);
        assertThat(load("{\"version\":1,\"offsetLane\":\"net\",\"attributions\":[]}").offsetLane())
                .isEqualTo(OffsetLane.NET);
        assertThat(load("{\"version\":1,\"offsetLane\":\"MARKET\",\"attributions\":[]}").offsetLane())
                .isEqualTo(OffsetLane.MARKET);
        assertThat(load("{\"version\":1,\"offsetLane\":\"Market\",\"attributions\":[]}").offsetLane())
                .isEqualTo(OffsetLane.MARKET);
    }

    @Test
    void offsetLaneRejectsUnknownValue() {
        assertThatThrownBy(() -> load("{\"version\":1,\"offsetLane\":\"GROSS\",\"attributions\":[]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offsetLane must be NET or MARKET")
                .hasMessageContaining("GROSS");
    }

    private BreakEvenAttributionLoader.LoadedBreakEvenAttribution load(String json) {
        return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "inline");
    }
}
