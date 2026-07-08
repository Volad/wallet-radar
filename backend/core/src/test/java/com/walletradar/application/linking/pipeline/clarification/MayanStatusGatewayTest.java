package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MayanStatusGatewayTest {

    @Test
    @DisplayName("official Mayan status response parses settled receiving route and fee metadata")
    void officialMayanStatusResponseParsesSettledReceivingRouteAndFeeMetadata() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.getMayanStatus().setEnabled(true);
        properties.getMayanStatus().setBaseUrl("https://explorer-api.mayan.finance");
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse("""
                        {
                          "sourceTxHash": "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                          "status": "REDEEMED_ON_EVM_WITH_FEE",
                          "clientStatus": "COMPLETED",
                          "destChain": "6",
                          "destAddress": "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                          "fromAmount": "3.139239",
                          "toAmount": "3.107299",
                          "redeemRelayerFee": "0.03194",
                          "bridgeFee": "0",
                          "service": "MCTP_BRIDGE",
                          "redeemTxHash": "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                          "fulfillTxHash": "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467"
                        }
                        """)));

        MayanStatusGateway gateway = new MayanStatusGateway(webClientBuilder, properties);

        Optional<MayanBridgeStatus> status = gateway.fetchBridgeStatus(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98"
        );

        assertThat(status).isPresent();
        assertThat(status.get().sourceTxHash())
                .isEqualTo("0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98");
        assertThat(status.get().receivingTxHash())
                .isEqualTo("0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467");
        assertThat(status.get().receivingNetworkId()).isEqualTo(NetworkId.AVALANCHE);
        assertThat(status.get().destinationWalletAddress())
                .isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(status.get().service()).isEqualTo("MCTP_BRIDGE");
        assertThat(status.get().apiStatus()).isEqualTo("REDEEMED_ON_EVM_WITH_FEE");
        assertThat(status.get().clientStatus()).isEqualTo("COMPLETED");
        assertThat(status.get().fromAmount()).isEqualTo("3.139239");
        assertThat(status.get().toAmount()).isEqualTo("3.107299");
        assertThat(status.get().redeemRelayerFee()).isEqualTo("0.03194");
        assertThat(status.get().bridgeFee()).isEqualTo("0");
        assertThat(status.get().isSettled()).isTrue();
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
