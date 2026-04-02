package com.walletradar.ingestion.pipeline.classification.onchain.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtocolDiscoveryServiceTest {

    private static final String ROUTER = "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5";

    @Test
    void discoversRegistryMatchAndAttachesProtocolResource() {
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(eq(NetworkId.ARBITRUM), eq(ROUTER)))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        ROUTER,
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.PERP,
                        ProtocolRegistryRole.ORDER_VAULT,
                        ProtocolRegistryEventType.PROTOCOL_CUSTODY_DEPOSIT,
                        ConfidenceLevel.HIGH,
                        "GMX",
                        "v2",
                        false,
                        null
                )));
        when(registryService.lookup(eq(NetworkId.ARBITRUM), eq("0x1111111111111111111111111111111111111111")))
                .thenReturn(Optional.empty());
        when(registryService.lookupMethodDescription("0xac9650d8"))
                .thenReturn(Optional.of("multicall(bytes[])"));

        ProtocolDiscoveryService service = new ProtocolDiscoveryService(
                registryService,
                new ProtocolResourceLoader(new ObjectMapper())
        );

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xabc")
                .setNetworkId(NetworkId.ARBITRUM.name())
                .setWalletAddress("0x1111111111111111111111111111111111111111")
                .setRawData(new Document("to", ROUTER)
                        .append("from", "0x1111111111111111111111111111111111111111")
                        .append("methodId", "0xac9650d8"));

        ProtocolDiscoveryResult result = service.discover(OnChainRawTransactionView.wrap(rawTransaction));

        assertThat(result.methodDescription()).isEqualTo("multicall(bytes[])");
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.protocolName()).isEqualTo("GMX");
            assertThat(match.protocolVersion()).isEqualTo("v2");
            assertThat(match.matchSource()).isEqualTo("TO_ADDRESS");
            assertThat(match.resource()).isNotNull();
            assertThat(match.resource().key()).isEqualTo("gmx-v2");
        });
    }
}
