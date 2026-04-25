package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProtocolNameResolutionServiceTest {

    @Mock
    private ProtocolRegistryService protocolRegistryService;

    @Test
    void resolvesFromDirectToAddressHit() {
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, "0xef740bf23acae26f6492b10de645d6b98dc8eaf3"))
                .thenReturn(Optional.of(entry("Uniswap", "V3", ProtocolRegistryFamily.DEX, ProtocolRegistryRole.ROUTER)));

        ProtocolNameResolutionService service = new ProtocolNameResolutionService(protocolRegistryService);
        Optional<ProtocolNameResolutionService.ResolvedProtocolName> resolved = service.resolve(
                normalized("swap-1", NormalizedTransactionType.SWAP),
                raw(
                        "0xswap",
                        "UNICHAIN",
                        "0x1111111111111111111111111111111111111111",
                        new Document()
                                .append("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0xef740bf23acae26f6492b10de645d6b98dc8eaf3")
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                )
        );

        assertThat(resolved).isPresent();
        assertThat(resolved.get().protocolName()).isEqualTo("Uniswap");
        assertThat(resolved.get().protocolVersion()).isEqualTo("V3");
    }

    @Test
    void resolvesWrapFromCanonicalWrapperRegistryEntry() {
        when(protocolRegistryService.lookup(NetworkId.UNICHAIN, "0x4200000000000000000000000000000000000006"))
                .thenReturn(Optional.of(entry("Canonical", "V1", ProtocolRegistryFamily.WRAPPER, ProtocolRegistryRole.WRAPPER_TOKEN)));

        ProtocolNameResolutionService service = new ProtocolNameResolutionService(protocolRegistryService);
        Optional<ProtocolNameResolutionService.ResolvedProtocolName> resolved = service.resolve(
                normalized("wrap-1", NormalizedTransactionType.WRAP),
                raw(
                        "0xwrap",
                        "UNICHAIN",
                        "0x1111111111111111111111111111111111111111",
                        new Document()
                                .append("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x4200000000000000000000000000000000000006")
                                .append("input", "0xd0e30db0")
                                .append("methodId", "0xd0e30db0")
                                .append("timeStamp", "1700000000")
                                .append("transactionIndex", "1")
                )
        );

        assertThat(resolved).isPresent();
        assertThat(resolved.get().protocolName()).isEqualTo("Canonical");
        assertThat(resolved.get().protocolVersion()).isEqualTo("V1");
    }

    @Test
    void resolvesFromInteractionToAddressEvenWhenTransferBackedTopLevelSuppressesToAddress() {
        when(protocolRegistryService.lookup(NetworkId.AVALANCHE, "0x45a62b090df48243f12a21897e7ed91863e2c86b"))
                .thenReturn(Optional.of(entry("LFJ", "Aggregator", ProtocolRegistryFamily.AGGREGATOR, ProtocolRegistryRole.ROUTER)));

        Document rawData = new Document()
                .append("from", "0x1111111111111111111111111111111111111111")
                .append("to", "0x45a62b090df48243f12a21897e7ed91863e2c86b")
                .append("value", "1000000")
                .append("contractAddress", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e")
                .append("tokenSymbol", "USDC")
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "4")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x45a62b090df48243f12a21897e7ed91863e2c86b")
                                .append("value", "1000000")
                                .append("contractAddress", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e")
                )));

        ProtocolNameResolutionService service = new ProtocolNameResolutionService(protocolRegistryService);
        Optional<ProtocolNameResolutionService.ResolvedProtocolName> resolved = service.resolve(
                normalized("swap-transfer-backed", NormalizedTransactionType.SWAP),
                raw(
                        "0xlfjswap",
                        "AVALANCHE",
                        "0x1111111111111111111111111111111111111111",
                        rawData
                )
        );

        assertThat(resolved).isPresent();
        assertThat(resolved.get().protocolName()).isEqualTo("LFJ");
        assertThat(resolved.get().protocolVersion()).isEqualTo("Aggregator");
    }

    @Test
    void resolvesFromClarificationLogAddressWhenDirectHitMissing() {
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(Optional.empty());
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0x1111111111111111111111111111111111111111"))
                .thenReturn(Optional.empty());
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0x5283beced7adf6d003225c13896e536f2d4264ff"))
                .thenReturn(Optional.of(entry("Aave", "V3", ProtocolRegistryFamily.LENDING, ProtocolRegistryRole.WRAPPER_CONTRACT)));

        ProtocolNameResolutionService service = new ProtocolNameResolutionService(protocolRegistryService);
        RawTransaction rawTransaction = raw(
                "0xlending",
                "ARBITRUM",
                "0x1111111111111111111111111111111111111111",
                new Document()
                        .append("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "2")
        );
        rawTransaction.setClarificationEvidence(new Document()
                .append("receipt", new Document("logs", List.of(
                        new Document("address", "0x5283beced7adf6d003225c13896e536f2d4264ff")
                ))));

        Optional<ProtocolNameResolutionService.ResolvedProtocolName> resolved = service.resolve(
                normalized("lending-1", NormalizedTransactionType.LENDING_DEPOSIT),
                rawTransaction
        );

        assertThat(resolved).isPresent();
        assertThat(resolved.get().protocolName()).isEqualTo("Aave");
    }

    @Test
    void keepsProtocolNameEmptyWhenBroaderEvidenceIsAmbiguous() {
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0xcccccccccccccccccccccccccccccccccccccccc"))
                .thenReturn(Optional.empty());
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0x1111111111111111111111111111111111111111"))
                .thenReturn(Optional.empty());
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(Optional.of(entry("Uniswap", "V3", ProtocolRegistryFamily.DEX, ProtocolRegistryRole.ROUTER)));
        when(protocolRegistryService.lookup(NetworkId.ARBITRUM, "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .thenReturn(Optional.of(entry("Paraswap", "V6.2", ProtocolRegistryFamily.AGGREGATOR, ProtocolRegistryRole.ROUTER)));

        ProtocolNameResolutionService service = new ProtocolNameResolutionService(protocolRegistryService);
        RawTransaction rawTransaction = raw(
                "0xambiguous",
                "ARBITRUM",
                "0x1111111111111111111111111111111111111111",
                new Document()
                        .append("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0xcccccccccccccccccccccccccccccccccccccccc")
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "3")
        );
        rawTransaction.setClarificationEvidence(new Document()
                .append("receipt", new Document("logs", List.of(
                        new Document("address", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                        new Document("address", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                ))));

        Optional<ProtocolNameResolutionService.ResolvedProtocolName> resolved = service.resolve(
                normalized("ambiguous-1", NormalizedTransactionType.SWAP),
                rawTransaction
        );

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolvesBridgeFromProviderBackedProtocolStatusWhenRegistryHitIsMissing() {
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, "0x89c6340b1a1f4b25d36cd8b063d49045caf3f818"))
                .thenReturn(Optional.empty());

        ProtocolNameResolutionService service = new ProtocolNameResolutionService(protocolRegistryService);
        RawTransaction rawTransaction = raw(
                "0xbridge",
                "ETHEREUM",
                "0x1111111111111111111111111111111111111111",
                new Document()
                        .append("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0x89c6340b1a1f4b25d36cd8b063d49045caf3f818")
                        .append("methodId", "0x0193b9fc")
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "1")
        );
        rawTransaction.setClarificationEvidence(new Document()
                .append("protocolStatus", new Document()
                        .append("provider", "LIFI")
                        .append("sendingTxHash", "0xbridge")
                        .append("receivingTxHash", "0xsettlement")
                        .append("receivingNetworkId", "ARBITRUM")));

        Optional<ProtocolNameResolutionService.ResolvedProtocolName> resolved = service.resolve(
                normalized("bridge-1", NormalizedTransactionType.BRIDGE_OUT),
                rawTransaction
        );

        assertThat(resolved).isPresent();
        assertThat(resolved.get().protocolName()).isEqualTo("LiFi");
    }

    private ProtocolRegistryEntry entry(
            String protocolName,
            String version,
            ProtocolRegistryFamily family,
            ProtocolRegistryRole role
    ) {
        return new ProtocolRegistryEntry(
                "0xentry",
                Set.of(NetworkId.ARBITRUM, NetworkId.UNICHAIN, NetworkId.AVALANCHE),
                family,
                role,
                null,
                ConfidenceLevel.HIGH,
                protocolName,
                version,
                false,
                null
        );
    }

    private NormalizedTransaction normalized(String id, NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
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
}
