package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelayBridgeClassificationSupportTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String RELAY_SOLVER = "0xf70da97812cb96acdf810712aa562db8dfa3dbef";
    private static final String DEPOSITORY = "0xc59fe32c9549e3e8b5dccdabc45bd287bd5ba2bc";

    @Mock
    private ProtocolRegistryService protocolRegistryService;

    @Test
    void detectsRelayDepositorySelector() {
        RawTransaction raw = katanaDepositoryRaw();
        assertThat(RelayBridgeClassificationSupport.isRelayDepositoryBridgeOut(OnChainRawTransactionView.wrap(raw)))
                .isTrue();
    }

    @Test
    void resolvesRelayPayoutInboundFromRegistry() {
        RawTransaction raw = arbitrumInboundRaw();
        when(protocolRegistryService.lookup(eq(NetworkId.ARBITRUM), eq(RELAY_SOLVER)))
                .thenReturn(Optional.of(relayPayoutEntry()));

        Optional<ProtocolRegistryEntry> entry = RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry(
                protocolRegistryService,
                OnChainRawTransactionView.wrap(raw)
        );

        assertThat(entry).isPresent();
        assertThat(entry.orElseThrow().protocolName()).isEqualTo("Relay");
    }

    private static RawTransaction katanaDepositoryRaw() {
        RawTransaction raw = new RawTransaction();
        raw.setWalletAddress(WALLET);
        raw.setNetworkId(NetworkId.KATANA.name());
        raw.setRawData(new Document()
                .append("from", WALLET)
                .append("to", DEPOSITORY)
                .append("methodId", RelayBridgeClassificationSupport.RELAY_DEPOSITORY_METHOD_SELECTOR)
                .append("value", "2256412857954226")
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", WALLET).append("to", DEPOSITORY).append("value", "2256412857954226").append("isError", "0")
                )).append("tokenTransfers", List.of())));
        return raw;
    }

    private static RawTransaction arbitrumInboundRaw() {
        RawTransaction raw = new RawTransaction();
        raw.setWalletAddress(WALLET);
        raw.setNetworkId(NetworkId.ARBITRUM.name());
        raw.setRawData(new Document()
                .append("from", RELAY_SOLVER)
                .append("to", WALLET)
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", RELAY_SOLVER).append("to", WALLET).append("value", "2243255327040116").append("isError", "0")
                )).append("tokenTransfers", List.of())));
        return raw;
    }

    private static ProtocolRegistryEntry relayPayoutEntry() {
        return new ProtocolRegistryEntry(
                RELAY_SOLVER,
                Set.of(NetworkId.ARBITRUM),
                ProtocolRegistryFamily.AGGREGATOR,
                ProtocolRegistryRole.GAS_PAYER,
                null,
                ConfidenceLevel.HIGH,
                "Relay",
                "Solver",
                false,
                null
        );
    }
}
