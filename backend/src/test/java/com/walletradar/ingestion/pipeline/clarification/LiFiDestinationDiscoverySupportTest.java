package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LiFiDestinationDiscoverySupportTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String EXECUTOR = "0x2cca08ae69e0c44b18a57ab2a87644234daebaE4";

    @Mock
    private ProtocolRegistryService protocolRegistryService;

    @Test
    @DisplayName("execute302 calldata with nested beneficiary resolves LIFI_CALLDATA when LiFi DONE")
    void execute302CalldataBeneficiaryResolvesLiFiCalldataPath() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7",
                "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa",
                NetworkId.BASE,
                "DONE",
                "COMPLETED"
        );
        RawTransaction rawTransaction = layerZeroExecute302Raw();

        Optional<LiFiDestinationDiscoveryPath> path = LiFiDestinationDiscoverySupport.resolveWalletRelevance(
                rawTransaction,
                WALLET,
                status,
                protocolRegistryService
        );

        assertThat(path).contains(LiFiDestinationDiscoveryPath.LIFI_CALLDATA);
        assertThat(LiFiDestinationDiscoverySupport.decodeSettlementBeneficiaryCandidates(layerZeroInput()))
                .contains(WALLET.toLowerCase());
    }

    @Test
    @DisplayName("calldata wallet mention without LiFi DONE does not resolve LIFI_CALLDATA")
    void calldataWithoutDoneStatusIsRejected() {
        LiFiBridgeStatus pending = new LiFiBridgeStatus(
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7",
                "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa",
                NetworkId.BASE,
                "PENDING",
                null
        );

        Optional<LiFiDestinationDiscoveryPath> path = LiFiDestinationDiscoverySupport.resolveWalletRelevance(
                layerZeroExecute302Raw(),
                WALLET,
                pending,
                protocolRegistryService
        );

        assertThat(path).isEmpty();
    }

    @Test
    @DisplayName("non-settlement selector with wallet in calldata is rejected")
    void nonSettlementSelectorIsRejected() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7",
                "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa",
                NetworkId.BASE,
                "DONE",
                "COMPLETED"
        );
        RawTransaction rawTransaction = layerZeroExecute302Raw();
        rawTransaction.getRawData().put("input", "0xa9059cbb0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        rawTransaction.getRawData().put("methodId", "0xa9059cbb");
        rawTransaction.getRawData().put("functionName", "transfer");

        Optional<LiFiDestinationDiscoveryPath> path = LiFiDestinationDiscoverySupport.resolveWalletRelevance(
                rawTransaction,
                WALLET,
                status,
                protocolRegistryService
        );

        assertThat(path).isEmpty();
    }

    @Test
    @DisplayName("ordered wallet scan prefers source wallet first")
    void orderedWalletScanPrefersSourceWallet() {
        List<String> ordered = LiFiDestinationDiscoverySupport.orderedTrackedWalletAddresses(
                List.of("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", WALLET, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                WALLET
        );

        assertThat(ordered.getFirst()).isEqualTo(WALLET);
        assertThat(ordered).hasSize(3);
    }

    @Test
    @DisplayName("Blockscout v2 address objects allow LiFi-corroborated settlement enrichment")
    void blockscoutAddressObjectsAllowSettlementEnrichment() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7",
                "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa",
                NetworkId.BASE,
                "DONE",
                "COMPLETED"
        );
        RawTransaction destinationRaw = blockscoutLayerZeroExecute302Raw();
        NormalizedTransaction source = new NormalizedTransaction();
        source.setTxHash(status.sendingTxHash());
        source.setType(NormalizedTransactionType.BRIDGE_OUT);
        source.setWalletAddress(WALLET);
        source.setFlows(List.of(outboundFlow("ETH", "-0.080966355549794125")));

        boolean enriched = LiFiDestinationDiscoverySupport.enrichCalldataSettlementBeforeClassification(
                destinationRaw,
                WALLET,
                status,
                LiFiDestinationDiscoveryPath.LIFI_CALLDATA,
                source,
                null,
                protocolRegistryService
        );

        assertThat(enriched).isTrue();
        @SuppressWarnings("unchecked")
        List<Document> internalTransfers = destinationRaw.getRawData()
                .get("explorer", Document.class)
                .get("internalTransfers", List.class);
        assertThat(internalTransfers).anySatisfy(transfer ->
                assertThat(transfer.getString("discoverySource"))
                        .isEqualTo(LiFiDestinationDiscoverySupport.LIFI_CORROBORATED_SETTLEMENT));
    }

    @Test
    @DisplayName("ERC-20 token source (USDC) does not inject synthetic ETH on destination — prevents phantom ETH AVCO")
    void erc20TokenSourceDoesNotInjectSyntheticEth() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0x8b40041f0a7c0000000000000000000000000000000000000000000000000000",
                "0x884437719bfd0000000000000000000000000000000000000000000000000000",
                NetworkId.BASE,
                "DONE",
                "COMPLETED"
        );
        RawTransaction destinationRaw = blockscoutLayerZeroExecute302Raw();
        NormalizedTransaction source = new NormalizedTransaction();
        source.setTxHash(status.sendingTxHash());
        source.setType(NormalizedTransactionType.BRIDGE_OUT);
        source.setWalletAddress(WALLET);
        source.setFlows(List.of(outboundTokenFlow("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "-3.14462")));

        boolean enriched = LiFiDestinationDiscoverySupport.enrichCalldataSettlementBeforeClassification(
                destinationRaw,
                WALLET,
                status,
                LiFiDestinationDiscoveryPath.LIFI_CALLDATA,
                source,
                null,
                protocolRegistryService
        );

        assertThat(enriched).isFalse();
        @SuppressWarnings("unchecked")
        List<Document> internalTransfers = destinationRaw.getRawData()
                .get("explorer", Document.class)
                .get("internalTransfers", List.class);
        assertThat(internalTransfers).noneMatch(t ->
                LiFiDestinationDiscoverySupport.LIFI_CORROBORATED_SETTLEMENT.equals(t.getString("discoverySource")));
    }

    private NormalizedTransaction.Flow outboundFlow(String symbol, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }

    private NormalizedTransaction.Flow outboundTokenFlow(String symbol, String contractAddress, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contractAddress);
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }

    private RawTransaction blockscoutLayerZeroExecute302Raw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa:BASE:" + WALLET);
        rawTransaction.setTxHash("0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa");
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("explorer", new Document()
                        .append("tx", new Document()
                                .append("raw_input", layerZeroInput())
                                .append("method", "execute302")
                                .append("from", new Document("hash", "0x7ddb0773e979cd36ef0dc8b6e594a6ebc876e654"))
                                .append("to", new Document("hash", EXECUTOR)))
                        .append("tokenTransfers", List.of())
                        .append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private RawTransaction layerZeroExecute302Raw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa:BASE:" + WALLET);
        rawTransaction.setTxHash("0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa");
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("blockNumber", "46929654")
                .append("timeStamp", "1749112655")
                .append("transactionIndex", "131")
                .append("from", "0x7ddb0773e979cd36ef0dc8b6e594a6ebc876e654")
                .append("to", EXECUTOR)
                .append("input", layerZeroInput())
                .append("methodId", "0xcfc32570")
                .append("functionName", "execute302")
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of())
                        .append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private String layerZeroInput() {
        return "0xcfc3257000000000000000000000000000000000000000000000000000000000000000200000000000000000000000005634c4a5fed09819e3c46d86a965dd9447d86e47000000000000000000000000000000000000000000000000000000000000759e00000000000000000000000019cfce47ed54a88614648dc3f19a5980097007dd000000000000000000000000000000000000000000000000000000000009301dc30104245c15b90406479816f3d0a46a7c01e46842db2650eabf1863727e8dad000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000001800000000000000000000000000000000000000000000000000000000000021291000000000000000000000000000000000000000000000000000000000000004c0200000000000000000000000000000000000000000000000000002d79883d2000000d0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f0000000000013c340000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
    }
}
