package com.walletradar.ingestion.pipeline.classification.onchain.protocol.registry;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegistryDirectTypeClassifier}, focusing on the fromAddress fallback
 * added to handle inbound token-transfer rows where toAddress() resolves to the wallet itself.
 */
class RegistryDirectTypeClassifierTest {

    private static final String PARADEX_CONTRACT = "0xe3cbe3a636ab6a754e9e41b12b09d09ce9e53db3";
    private static final String WALLET = "0xf03b52e8686b962e051a6075a06b96cb8a663021";

    @Test
    @DisplayName("falls back to fromAddress when toAddress registry lookup fails — inbound token-transfer from registered custody contract")
    void fromAddressFallbackMatchesRegisteredCustodyContract() {
        // Paradex L1 Core withdrawal: USDC moves FROM the Paradex contract TO the wallet.
        // The raw tx is an Etherscan token-transfer row: from=Paradex, to=wallet.
        // toAddress() returns the wallet (not in registry). fromAddress() returns Paradex.
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(NetworkId.ETHEREUM, WALLET))
                .thenReturn(Optional.empty());
        when(registryService.lookup(NetworkId.ETHEREUM, PARADEX_CONTRACT))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        PARADEX_CONTRACT,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.CUSTODY,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.PROTOCOL_CUSTODY_WITHDRAW,
                        ConfidenceLevel.HIGH,
                        "Paradex",
                        "L1",
                        false,
                        null
                )));

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xc7aa483f0805a3548ff61a250209059ae8a91e28d172fcf0e8daf8f55d8a68ee")
                .setNetworkId(NetworkId.ETHEREUM.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("from", PARADEX_CONTRACT)
                        .append("to", WALLET)
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "42"));

        OnChainClassificationContext context = new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );

        RegistryDirectTypeClassifier classifier = new RegistryDirectTypeClassifier(registryService);
        Optional<ClassificationDecision> result = classifier.classify(context);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW);
        assertThat(result.get().classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.get().confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.get().protocolName()).isEqualTo("Paradex");
    }

    @Test
    @DisplayName("toAddress registry match is used directly without consulting fromAddress")
    void toAddressMatchTakesPrecedence() {
        String aavePool = "0x794a61358d6845594f94dc1db02a252b5b4814ad";
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(NetworkId.ETHEREUM, aavePool))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        aavePool,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.LENDING,
                        ProtocolRegistryRole.POOL,
                        ProtocolRegistryEventType.LENDING_DEPOSIT,
                        ConfidenceLevel.HIGH,
                        "Aave",
                        "V3",
                        false,
                        null
                )));

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xaaaa")
                .setNetworkId(NetworkId.ETHEREUM.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("from", WALLET)
                        .append("to", aavePool)
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "1"));

        OnChainClassificationContext context = new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );

        RegistryDirectTypeClassifier classifier = new RegistryDirectTypeClassifier(registryService);
        Optional<ClassificationDecision> result = classifier.classify(context);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
    }

    @Test
    @DisplayName("returns empty when fromAddress equals walletAddress and toAddress has no registry match")
    void noFallbackWhenFromAddressIsWallet() {
        String unknownContract = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(any(), any())).thenReturn(Optional.empty());

        // Normal outbound: wallet sends to an unknown contract; fromAddress == wallet.
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xbbbb")
                .setNetworkId(NetworkId.ETHEREUM.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("from", WALLET)
                        .append("to", unknownContract)
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "1"));

        OnChainClassificationContext context = new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );

        RegistryDirectTypeClassifier classifier = new RegistryDirectTypeClassifier(registryService);
        Optional<ClassificationDecision> result = classifier.classify(context);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("falls back to token-transfer sender when rawData.from/to are empty — TERMINAL_METADATA_ONLY Paradex withdrawal")
    void tokenTransferSenderFallbackMatchesWhenRawFromToAreEmpty() {
        // Simulates Paradex L1 withdrawal where Etherscan returns an empty raw tx body
        // (rawData.from="" rawData.to="") but explorer.tokenTransfers[0].from = Paradex contract.
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(any(), any())).thenReturn(Optional.empty());
        when(registryService.lookup(NetworkId.ETHEREUM, PARADEX_CONTRACT))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        PARADEX_CONTRACT,
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.CUSTODY,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.PROTOCOL_CUSTODY_WITHDRAW,
                        ConfidenceLevel.HIGH,
                        "Paradex",
                        "L1",
                        false,
                        null
                )));

        // Token transfer: Paradex → wallet (USDC inbound), but raw tx from/to are blank
        Document tokenTransfer = new Document("from", PARADEX_CONTRACT)
                .append("to", WALLET)
                .append("contractAddress", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
                .append("tokenSymbol", "USDC")
                .append("value", "1266468083")
                .append("tokenDecimal", "6");
        Document explorer = new Document("tokenTransfers", List.of(tokenTransfer));

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xc7aa483f0805a3548ff61a250209059ae8a91e28d172fcf0e8daf8f55d8a68ee")
                .setNetworkId(NetworkId.ETHEREUM.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("from", "")
                        .append("to", "")
                        .append("functionName", "withdraw(address token, uint256 amount, address destination)")
                        .append("timeStamp", "1777259759")
                        .append("transactionIndex", "438")
                        .append("explorer", explorer));

        OnChainClassificationContext context = new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );

        RegistryDirectTypeClassifier classifier = new RegistryDirectTypeClassifier(registryService);
        Optional<ClassificationDecision> result = classifier.classify(context);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW);
        assertThat(result.get().classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.get().protocolName()).isEqualTo("Paradex");
    }

    @Test
    @DisplayName("returns empty when both toAddress and fromAddress have no registry match")
    void returnsEmptyWhenNeitherAddressMatches() {
        String unknownFrom = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String unknownTo = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        ProtocolRegistryService registryService = mock(ProtocolRegistryService.class);
        when(registryService.lookup(any(), any())).thenReturn(Optional.empty());

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xcccc")
                .setNetworkId(NetworkId.ETHEREUM.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("from", unknownFrom)
                        .append("to", unknownTo)
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "1"));

        OnChainClassificationContext context = new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );

        RegistryDirectTypeClassifier classifier = new RegistryDirectTypeClassifier(registryService);
        Optional<ClassificationDecision> result = classifier.classify(context);

        assertThat(result).isEmpty();
    }
}
