package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryLoader;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
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
    // NEW-11 anchors.
    private static final String RELAY_ARB_RECEIVER = "0x1619de6b6b20ed217a58d00f37b9d47c7663feca";
    private static final String RELAY_ZKSYNC_SOLVER = "0x91604f590d66ace8975eed6bd16cf55647d1c499";

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

    @Test
    void resolvesArbitrumRelayReceiverFromRealRegistry() {
        ProtocolRegistryService realRegistry =
                new ProtocolRegistryService(new ProtocolRegistryLoader(new ObjectMapper()));
        RawTransaction raw = inboundNativeRaw(NetworkId.ARBITRUM, RELAY_ARB_RECEIVER);

        Optional<ProtocolRegistryEntry> entry = RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry(
                realRegistry,
                OnChainRawTransactionView.wrap(raw)
        );

        assertThat(entry).isPresent();
        assertThat(RelayBridgeClassificationSupport.isRelayPayoutEntry(entry.orElseThrow())).isTrue();
        assertThat(entry.orElseThrow().protocolName()).isEqualTo("Relay");
    }

    @Test
    void resolvesZkSyncRelaySolverFromRealRegistry() {
        ProtocolRegistryService realRegistry =
                new ProtocolRegistryService(new ProtocolRegistryLoader(new ObjectMapper()));
        RawTransaction raw = inboundNativeRaw(NetworkId.ZKSYNC, RELAY_ZKSYNC_SOLVER);

        Optional<ProtocolRegistryEntry> entry = RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry(
                realRegistry,
                OnChainRawTransactionView.wrap(raw)
        );

        assertThat(entry).isPresent();
        assertThat(RelayBridgeClassificationSupport.isRelayPayoutEntry(entry.orElseThrow())).isTrue();
    }

    @Test
    void unregisteredSenderStaysUnresolved() {
        ProtocolRegistryService realRegistry =
                new ProtocolRegistryService(new ProtocolRegistryLoader(new ObjectMapper()));
        RawTransaction raw = inboundNativeRaw(NetworkId.ARBITRUM, "0x7f6ccd2419a8f97d5f2a6a3c1e11d3f0e7b1a2c3");

        Optional<ProtocolRegistryEntry> entry = RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry(
                realRegistry,
                OnChainRawTransactionView.wrap(raw)
        );

        assertThat(entry).isEmpty();
    }

    private static RawTransaction inboundNativeRaw(NetworkId networkId, String sender) {
        RawTransaction raw = new RawTransaction();
        raw.setWalletAddress(WALLET);
        raw.setNetworkId(networkId.name());
        raw.setRawData(new Document()
                .append("from", sender)
                .append("to", WALLET)
                .append("explorer", new Document("internalTransfers", List.of(
                        new Document("from", sender).append("to", WALLET).append("value", "500447118879749188").append("isError", "0")
                )).append("tokenTransfers", List.of())));
        return raw;
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
