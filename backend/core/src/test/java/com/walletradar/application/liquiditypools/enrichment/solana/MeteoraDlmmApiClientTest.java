package com.walletradar.application.liquiditypools.enrichment.solana;

import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient.MeteoraPool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MeteoraDlmmApiClientTest {

    private MeteoraDlmmApiClient client(ClientResponse response) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(response));
        return new MeteoraDlmmApiClient(builder, new LiquidityPoolsProperties());
    }

    @Test
    void parsesPoolTokensPriceAndBinStep() {
        String body = "{\"name\":\"SOL-USDC\","
                + "\"token_x\":{\"address\":\"So11111111111111111111111111111111111111112\",\"symbol\":\"SOL\",\"decimals\":9},"
                + "\"token_y\":{\"address\":\"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\",\"symbol\":\"USDC\",\"decimals\":6},"
                + "\"current_price\":76.02,\"pool_config\":{\"bin_step\":4,\"base_fee_pct\":0.04}}";
        Optional<MeteoraPool> pool = client(json(HttpStatus.OK, body)).fetchPool("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6");

        assertThat(pool).isPresent();
        MeteoraPool p = pool.get();
        assertThat(p.name()).isEqualTo("SOL-USDC");
        assertThat(p.tokenX().mint()).isEqualTo("So11111111111111111111111111111111111111112");
        assertThat(p.tokenX().symbol()).isEqualTo("SOL");
        assertThat(p.tokenX().decimals()).isEqualTo(9);
        assertThat(p.tokenY().symbol()).isEqualTo("USDC");
        assertThat(p.tokenY().decimals()).isEqualTo(6);
        assertThat(p.currentPrice()).isEqualByComparingTo("76.02");
        assertThat(p.binStep()).isEqualTo(4);
        assertThat(p.baseFeePct()).isEqualByComparingTo("0.04");
    }

    @Test
    void returnsEmptyOnErrorStatus() {
        assertThat(client(json(HttpStatus.NOT_FOUND, "not found")).fetchPool("pool")).isEmpty();
    }

    @Test
    void returnsEmptyOnMissingTokens() {
        assertThat(client(json(HttpStatus.OK, "{\"name\":\"x\"}")).fetchPool("pool")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertThat(client(json(HttpStatus.OK, "{}")).fetchPool("  ")).isEmpty();
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", "application/json").body(body).build();
    }
}
