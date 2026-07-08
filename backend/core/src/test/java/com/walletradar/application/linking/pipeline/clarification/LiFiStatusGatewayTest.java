package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LiFiStatusGatewayTest {

    @Test
    @DisplayName("DONE+COMPLETED status response parses the resolved toAddress")
    void doneCompletedStatusResponseParsesResolvedToAddress() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse("""
                        {
                          "sending": {
                            "txHash": "0x4890E907F816A2F573559377FB97943EFCBAD26750CB3CF3BF96FF48A43504F7"
                          },
                          "receiving": {
                            "txHash": "0x25550CF1685A0CE5AB3D546B595D6C43A742B8487AB4FBC2B7913BF03645B7AA",
                            "chainId": 8453
                          },
                          "status": "DONE",
                          "substatus": "COMPLETED",
                          "toAddress": "0xFEEDFACE00000000000000000000000000000001"
                        }
                        """)));

        LiFiStatusGateway gateway = new LiFiStatusGateway(webClientBuilder, properties(), networkProperties());

        Optional<LiFiBridgeStatus> status = gateway.fetchBridgeStatus(
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7"
        );

        assertThat(status).isPresent();
        assertThat(status.get().sendingTxHash())
                .isEqualTo("0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7");
        assertThat(status.get().receivingTxHash())
                .isEqualTo("0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa");
        assertThat(status.get().receivingNetworkId()).isEqualTo(NetworkId.BASE);
        assertThat(status.get().apiStatus()).isEqualTo("DONE");
        assertThat(status.get().substatus()).isEqualTo("COMPLETED");
        assertThat(status.get().toAddress()).isEqualTo("0xfeedface00000000000000000000000000000001");
        assertThat(status.get().isDoneAndCompleted()).isTrue();
        assertThat(status.get().hasResolvedToAddress()).isTrue();
    }

    @Test
    @DisplayName("a not-yet-settled response with a missing toAddress parses as unresolved, not a false non-match")
    void pendingStatusResponseWithMissingToAddressParsesAsUnresolved() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse("""
                        {
                          "sending": {
                            "txHash": "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7"
                          },
                          "receiving": {
                            "txHash": "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa",
                            "chainId": 8453
                          },
                          "status": "PENDING"
                        }
                        """)));

        LiFiStatusGateway gateway = new LiFiStatusGateway(webClientBuilder, properties(), networkProperties());

        Optional<LiFiBridgeStatus> status = gateway.fetchBridgeStatus(
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7"
        );

        assertThat(status).isPresent();
        assertThat(status.get().apiStatus()).isEqualTo("PENDING");
        assertThat(status.get().toAddress()).isNull();
        assertThat(status.get().isDoneAndCompleted()).isFalse();
        assertThat(status.get().hasResolvedToAddress()).isFalse();
    }

    private OnChainClarificationProperties properties() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.getLiFiStatus().setEnabled(true);
        properties.getLiFiStatus().setBaseUrl("https://li.quest");
        return properties;
    }

    private IngestionNetworkProperties networkProperties() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setChainId("8453");
        properties.getNetwork().put("BASE", entry);
        return properties;
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
