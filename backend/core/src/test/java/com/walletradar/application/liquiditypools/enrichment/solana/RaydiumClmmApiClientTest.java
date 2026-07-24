package com.walletradar.application.liquiditypools.enrichment.solana;

import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient.RaydiumPool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RaydiumClmmApiClientTest {

    private RaydiumClmmApiClient client(ClientResponse response) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(response));
        return new RaydiumClmmApiClient(builder, new LiquidityPoolsProperties());
    }

    @Test
    void parsesPoolMintsAndPrice() {
        String body = "{\"id\":\"req\",\"success\":true,\"data\":[{"
                + "\"type\":\"Concentrated\",\"programId\":\"CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK\","
                + "\"id\":\"3ucNos4NbumPLZNWztqGHNFFgkHeRMBQAVemeeomsUxv\","
                + "\"mintA\":{\"address\":\"So11111111111111111111111111111111111111112\",\"symbol\":\"WSOL\",\"decimals\":9},"
                + "\"mintB\":{\"address\":\"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\",\"symbol\":\"USDC\",\"decimals\":6},"
                + "\"price\":76.1}]}";
        Optional<RaydiumPool> pool = client(json(HttpStatus.OK, body)).fetchPool("3ucNos4NbumPLZNWztqGHNFFgkHeRMBQAVemeeomsUxv");

        assertThat(pool).isPresent();
        RaydiumPool p = pool.get();
        assertThat(p.mintA().symbol()).isEqualTo("WSOL");
        assertThat(p.mintA().decimals()).isEqualTo(9);
        assertThat(p.mintB().mint()).isEqualTo("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
        assertThat(p.price()).isEqualByComparingTo("76.1");
    }

    @Test
    void returnsEmptyWhenSuccessFalse() {
        assertThat(client(json(HttpStatus.OK, "{\"success\":false,\"data\":[]}")).fetchPool("x")).isEmpty();
    }

    @Test
    void returnsEmptyOnEmptyData() {
        assertThat(client(json(HttpStatus.OK, "{\"success\":true,\"data\":[]}")).fetchPool("x")).isEmpty();
    }

    @Test
    void returnsEmptyOnErrorStatus() {
        assertThat(client(json(HttpStatus.INTERNAL_SERVER_ERROR, "boom")).fetchPool("x")).isEmpty();
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", "application/json").body(body).build();
    }
}
