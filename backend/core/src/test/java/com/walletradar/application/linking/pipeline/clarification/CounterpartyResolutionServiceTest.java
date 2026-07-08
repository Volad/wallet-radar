package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CounterpartyResolutionServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String SWAP_ROUTER = "0x6a000f20005980200259b80c5102003040001068";
    private static final String BRIDGE_SENDER = "0xf70da97812cb96acdf810712aa562db8dfa3dbef";
    private static final String PEER_ONE = "0x2222222222222222222222222222222222222222";
    private static final String PEER_TWO = "0x3333333333333333333333333333333333333333";
    private static final String ASSET = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
    private static final String VAULT = "0x4444444444444444444444444444444444444444";

    private final CounterpartyResolutionService service = new CounterpartyResolutionService();

    @Test
    void resolvesSwapCounterpartyFromInteractedContract() {
        OptionalResult result = resolve(
                normalized(NormalizedTransactionType.SWAP),
                raw(
                        "0xswap",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("from", WALLET)
                                .append("to", SWAP_ROUTER)
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                )
        );

        assertThat(result.value()).isEqualTo(SWAP_ROUTER);
    }

    @Test
    void resolvesBridgeInCounterpartyFromSettlementContractBeforeInboundSender() {
        OptionalResult result = resolve(
                normalized(NormalizedTransactionType.BRIDGE_IN),
                raw(
                        "0xbridgein",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("from", BRIDGE_SENDER)
                                .append("to", SWAP_ROUTER)
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                                .append("explorer", new Document("tokenTransfers", List.of(
                                        new Document("from", BRIDGE_SENDER)
                                                .append("to", WALLET)
                                                .append("value", "1000000")
                                                .append("contractAddress", ASSET)
                                )))
                )
        );

        assertThat(result.value()).isEqualTo(SWAP_ROUTER);
    }

    @Test
    void resolvesBridgeInCounterpartyFromUniqueInboundSenderWhenSettlementContractMissing() {
        OptionalResult result = resolve(
                normalized(NormalizedTransactionType.BRIDGE_IN),
                raw(
                        "0xbridgein",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                                .append("explorer", new Document("tokenTransfers", List.of(
                                        new Document("from", BRIDGE_SENDER)
                                                .append("to", WALLET)
                                                .append("value", "1000000")
                                                .append("contractAddress", ASSET)
                                )))
                )
        );

        assertThat(result.value()).isEqualTo(BRIDGE_SENDER);
    }

    @Test
    void leavesCounterpartyEmptyWhenExternalInboundPeersAreAmbiguous() {
        OptionalResult result = resolve(
                normalized(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                raw(
                        "0xexternalin",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                                .append("explorer", new Document("tokenTransfers", List.of(
                                        new Document("from", PEER_ONE)
                                                .append("to", WALLET)
                                                .append("value", "1")
                                                .append("contractAddress", ASSET),
                                        new Document("from", PEER_TWO)
                                                .append("to", WALLET)
                                                .append("value", "2")
                                                .append("contractAddress", ASSET)
                                )))
                )
        );

        assertThat(result.value()).isNull();
    }

    @Test
    void resolvesProtocolCounterpartyFromUniqueRegistryBackedLogWhenDirectPeerIsMissing() {
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(com.walletradar.domain.common.NetworkId.ARBITRUM, VAULT))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        VAULT,
                        Set.of(com.walletradar.domain.common.NetworkId.ARBITRUM),
                        null,
                        ProtocolRegistryRole.VAULT,
                        null,
                        null,
                        "Euler",
                        null,
                        false,
                        null
                )));
        CounterpartyResolutionService registryBackedService = new CounterpartyResolutionService(registryService, null);

        OptionalResult result = new OptionalResult(registryBackedService.resolve(
                normalized(NormalizedTransactionType.VAULT_WITHDRAW),
                raw(
                        "0xvault",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                                .append("clarificationEvidence", new Document("receipt", new Document("logs", List.of(
                                        new Document("address", VAULT),
                                        new Document("address", ASSET)
                                ))))
                )
        ).orElse(null));

        assertThat(result.value()).isEqualTo(VAULT);
    }

    @Test
    void assignsRowLocalCounterpartyTypeFromRegistryRole() {
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(com.walletradar.domain.common.NetworkId.ARBITRUM, SWAP_ROUTER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        SWAP_ROUTER,
                        Set.of(com.walletradar.domain.common.NetworkId.ARBITRUM),
                        null,
                        ProtocolRegistryRole.ROUTER,
                        null,
                        null,
                        "Uniswap",
                        null,
                        false,
                        null
                )));
        CounterpartyResolutionService registryBackedService = new CounterpartyResolutionService(registryService, null);

        CounterpartyResolutionService.ResolvedCounterparty result = registryBackedService.resolveMetadata(
                normalized(NormalizedTransactionType.SWAP),
                raw(
                        "0xswap",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("from", WALLET)
                                .append("to", SWAP_ROUTER)
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                )
        );

        assertThat(result.address()).isEqualTo(SWAP_ROUTER);
        assertThat(result.counterpartyType()).isEqualTo(CounterpartyType.PROTOCOL);
        assertThat(result.resolutionState()).isEqualTo(MetadataResolutionState.RESOLVED_EXACT);
    }

    @Test
    void terminalizesMissingRowLocalCounterpartyEvidence() {
        CounterpartyResolutionService.ResolvedCounterparty result = service.resolveMetadata(
                normalized(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                raw(
                        "0xexternalin",
                        "ARBITRUM",
                        WALLET,
                        new Document()
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                )
        );

        assertThat(result.address()).isNull();
        assertThat(result.counterpartyType()).isEqualTo(CounterpartyType.GENUINE_MISSING_SOURCE);
        assertThat(result.resolutionState()).isEqualTo(MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING);
    }

    private OptionalResult resolve(NormalizedTransaction transaction, RawTransaction rawTransaction) {
        return new OptionalResult(service.resolve(transaction, rawTransaction).orElse(null));
    }

    private NormalizedTransaction normalized(NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(type);
        return transaction;
    }

    private RawTransaction raw(String txHash, String networkId, String walletAddress, Document rawData) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":" + walletAddress);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId);
        rawTransaction.setWalletAddress(walletAddress);
        rawTransaction.setRawData(rawData);
        return rawTransaction;
    }

    private record OptionalResult(String value) {
    }
}
