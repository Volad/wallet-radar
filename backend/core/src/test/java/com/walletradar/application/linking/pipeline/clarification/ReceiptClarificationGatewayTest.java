package com.walletradar.application.linking.pipeline.clarification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerReceipt;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.support.RpcTokenTransferResolver;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptClarificationGatewayTest {

    @Mock
    private EtherscanV2ExplorerProvider etherscanProvider;
    @Mock
    private BlockScoutExplorerProvider blockScoutProvider;
    @Mock
    private EvmRpcClient rpcClient;
    @Mock
    private RpcTokenTransferResolver rpcTokenTransferResolver;

    @Test
    @DisplayName("persisted transfer evidence short-circuits external fetch for receipt clarification")
    void persistedTransferEvidenceShortCircuitsExternalFetchForReceiptClarification() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.BASE, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.BLOCKSCOUT);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.BASE, RawSyncMethod.BLOCKSCOUT, "0xpersisted");
        rawTransaction.setClarificationEvidence(new Document()
                .append("sourceFamily", "BLOCKSCOUT")
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                .append("tokenSymbol", "WETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0xpm")
                                .append("to", rawTransaction.getWalletAddress())
                                .append("value", "100000000000000000")
                ))));

        Optional<ClarificationReceiptEnrichment> enrichment = gateway.fetchReceiptWithTransferEvidence(rawTransaction);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().sourceFamily()).isEqualTo(RawSyncMethod.BLOCKSCOUT);
        assertThat(enrichment.get().tokenTransfers()).hasSize(1);
        verifyNoInteractions(etherscanProvider, blockScoutProvider, rpcClient, rpcTokenTransferResolver);
    }

    @Test
    @DisplayName("persisted receipt logs without transfer evidence stay retryable for transfer clarification")
    void persistedReceiptLogsWithoutTransferEvidenceStayRetryableForTransferClarification() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.AVALANCHE, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.AVALANCHE, RawSyncMethod.ETHERSCAN, "0xreceipt-only");
        rawTransaction.setClarificationEvidence(new Document()
                .append("sourceFamily", "ETHERSCAN")
                .append("receipt", new Document("logs", List.of(
                        new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                                .append("topics", List.of("0xborrow"))
                ))));

        Optional<ClarificationReceiptEnrichment> transferEnrichment = gateway.fromPersistedEvidence(rawTransaction, true);
        Optional<ClarificationReceiptEnrichment> metadataEnrichment = gateway.fromPersistedEvidence(rawTransaction, false);

        assertThat(transferEnrichment).isEmpty();
        assertThat(metadataEnrichment).isPresent();
        assertThat(metadataEnrichment.get().receiptLogs()).hasSize(1);
        verifyNoInteractions(etherscanProvider, blockScoutProvider, rpcClient, rpcTokenTransferResolver);
    }

    @Test
    @DisplayName("rpc-backed metadata clarification still persists full fetched receipt")
    void rpcBackedMetadataClarificationStillPersistsFullFetchedReceipt() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.BSC, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.BSC, RawSyncMethod.RPC, "0xrpc");
        when(rpcClient.call("https://rpc.example", "eth_getTransactionReceipt", List.of("0xrpc")))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"status":"0x1","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca00","logs":[{"address":"0xtoken","topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","0x0000000000000000000000001111111111111111111111111111111111111111"],"data":"0x64"}]}}
                        """));

        Optional<ClarificationReceiptEnrichment> enrichment = gateway.fetchReceipt(rawTransaction);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().sourceFamily()).isEqualTo(RawSyncMethod.RPC);
        assertThat(enrichment.get().receiptLogs()).hasSize(1);
        assertThat(enrichment.get().tokenTransfers()).isEmpty();
        assertThat(enrichment.get().fullReceiptPayload()).isNotNull();
        verify(rpcTokenTransferResolver, never()).buildTokenTransfersFromDocuments(any(), any(), any());
        verify(etherscanProvider, never()).getReceipt(any(), any());
        verify(blockScoutProvider, never()).getReceipt(any(), any());
    }

    @Test
    @DisplayName("full receipt clarification derives token transfers from receipt logs without metadata RPC")
    void fullReceiptClarificationDerivesTokenTransfersFromReceiptLogsWithoutMetadataRpc() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.BSC, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.BSC, RawSyncMethod.RPC, "0xfull");
        rawTransaction.setRawData(rawTransaction.getRawData()
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x55d398326f99059ff775485246999027b3197955")
                                .append("tokenSymbol", "USDT")
                                .append("tokenDecimal", "18")
                                .append("tokenName", "Tether USD")
                ))));
        when(rpcClient.call("https://rpc.example", "eth_getTransactionReceipt", List.of("0xfull")))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"status":"0x1","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca00","logs":[{"address":"0x55d398326f99059ff775485246999027b3197955","logIndex":"0x1","topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","0x0000000000000000000000001111111111111111111111111111111111111111"],"data":"0x64"}]}}
                        """));

        Optional<ClarificationReceiptEnrichment> enrichment = gateway.fetchFullReceipt(rawTransaction);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().tokenTransfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.getString("contractAddress")).isEqualTo("0x55d398326f99059ff775485246999027b3197955");
            assertThat(transfer.getString("from")).isEqualTo("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            assertThat(transfer.getString("to")).isEqualTo("0x1111111111111111111111111111111111111111");
            assertThat(transfer.getString("value")).isEqualTo("100");
            assertThat(transfer.getString("tokenSymbol")).isEqualTo("USDT");
            assertThat(transfer.getString("tokenDecimal")).isEqualTo("18");
        });
        verify(rpcTokenTransferResolver, never()).buildTokenTransfersFromDocuments(any(), any(), any());
    }

    @Test
    @DisplayName("rpc-backed fetchRawTransactionByHash builds raw evidence for targeted LI.FI receiving discovery")
    void rpcBackedFetchRawTransactionByHashBuildsRawEvidence() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.BSC, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        List<Document> derivedTransfers = List.of(new Document("contractAddress", "0x55d398326f99059ff775485246999027b3197955")
                .append("from", "0x8c826f795466e39acbff1bb4eeeb759609377ba1")
                .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                .append("value", "1000000")
                .append("tokenDecimal", "18")
                .append("tokenSymbol", "USDT"));
        when(rpcClient.call("https://rpc.example", "eth_getTransactionByHash", List.of("0xrecv")))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"hash":"0xrecv","blockNumber":"0x10","transactionIndex":"0x2","from":"0x8c826f795466e39acbff1bb4eeeb759609377ba1","to":"0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f","input":"0xa9059cbb0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f","value":"0x0"}} 
                        """));
        when(rpcClient.call("https://rpc.example", "eth_getTransactionReceipt", List.of("0xrecv")))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"status":"0x1","blockNumber":"0x10","transactionIndex":"0x2","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca00","logs":[{"address":"0x55d398326f99059ff775485246999027b3197955","topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x0000000000000000000000008c826f795466e39acbff1bb4eeeb759609377ba1","0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"],"data":"0x0de0b6b3a7640000"}]}}
                        """));
        when(rpcClient.call("https://rpc.example", "eth_getBlockByNumber", List.of("0x10", false)))
                .thenReturn(Mono.just("""
                        {"jsonrpc":"2.0","id":1,"result":{"timestamp":"0x65f1e100"}}
                        """));
        when(rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                eq("https://rpc.example"),
                eq(NetworkId.BSC.name()),
                anyList()
        )).thenReturn(derivedTransfers);

        Optional<RawTransaction> rawTransaction = gateway.fetchRawTransactionByHash(
                "0xrecv",
                NetworkId.BSC,
                "0x1A87f12ac07e9746e9B053B8D7EF1d45270D693f",
                null
        );

        assertThat(rawTransaction).isPresent();
        assertThat(rawTransaction.get().getSyncMethod()).isEqualTo(RawSyncMethod.RPC);
        assertThat(rawTransaction.get().getId()).isEqualTo("0xrecv:BSC:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(rawTransaction.get().getRawData().getString("timeStamp")).isEqualTo("1710350592");
        assertThat(rawTransaction.get().getRawData().getString("methodId")).isEqualTo("0xa9059cbb");
        assertThat(((Document) rawTransaction.get().getRawData().get("explorer")).getList("tokenTransfers", Document.class))
                .hasSize(1);
        assertThat(rawTransaction.get().getClarificationEvidence()).isNotNull();
        verify(etherscanProvider, never()).getTransaction(any(), any());
        verify(blockScoutProvider, never()).getTransaction(any(), any());
    }

    @Test
    @DisplayName("etherscan-backed raw chooses etherscan clarification path")
    void etherscanBackedRawChoosesEtherscanClarificationPath() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.BASE, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.BASE, RawSyncMethod.ETHERSCAN, "0xeth");
        when(etherscanProvider.getReceipt("0xeth", NetworkId.BASE))
                .thenReturn(new ExplorerReceipt(new Document("status", "0x1")
                        .append("gasUsed", "0x5208")
                        .append("effectiveGasPrice", "0x3b9aca00")
                        .append("blockNumber", "0x2")
                        .append("logs", List.of(new Document("address", "0xpm")))));
        when(etherscanProvider.getTokenTransfers(rawTransaction.getWalletAddress(), NetworkId.BASE, 2L, 2L, 1))
                .thenReturn(List.of(new ExplorerTokenTransfer(new Document("hash", "0xeth")
                        .append("contractAddress", "0xtoken")
                        .append("from", "0xpm")
                        .append("to", "0x1111111111111111111111111111111111111111")
                        .append("value", "100")
                        .append("tokenDecimal", "18")
                        .append("tokenSymbol", "WETH"))));
        when(etherscanProvider.getTokenTransfers(rawTransaction.getWalletAddress(), NetworkId.BASE, 2L, 2L, 2))
                .thenReturn(List.of());
        when(etherscanProvider.getInternalTransfers(rawTransaction.getWalletAddress(), NetworkId.BASE, 2L, 2L, 1))
                .thenReturn(List.of(new ExplorerInternalTransfer(new Document("hash", "0xeth")
                        .append("from", "0xpm")
                        .append("to", rawTransaction.getWalletAddress())
                        .append("value", "100"))));
        when(etherscanProvider.getInternalTransfers(rawTransaction.getWalletAddress(), NetworkId.BASE, 2L, 2L, 2))
                .thenReturn(List.of());

        Optional<ClarificationReceiptEnrichment> enrichment = gateway.fetchReceiptWithTransferEvidence(rawTransaction);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().sourceFamily()).isEqualTo(RawSyncMethod.ETHERSCAN);
        assertThat(enrichment.get().receiptLogs()).hasSize(1);
        assertThat(enrichment.get().tokenTransfers()).hasSize(1);
        assertThat(enrichment.get().internalTransfers()).hasSize(1);
        assertThat(enrichment.get().fullReceiptPayload()).isNotNull();
        verify(rpcClient, never()).call(any(), any(), any());
        verify(blockScoutProvider, never()).getReceipt(any(), any());
    }

    @Test
    @DisplayName("blockscout-backed clarification prefers tx-hash transfer subresources")
    void blockscoutBackedClarificationPrefersTxHashTransferSubresources() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.BASE, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.BLOCKSCOUT);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.BASE, RawSyncMethod.BLOCKSCOUT, "0xblockscout");
        when(blockScoutProvider.getReceipt("0xblockscout", NetworkId.BASE))
                .thenReturn(new ExplorerReceipt(new Document("status", "0x1")
                        .append("gasUsed", "0x5208")
                        .append("effectiveGasPrice", "0x3b9aca00")
                        .append("blockNumber", "0x2")
                        .append("logs", List.of(new Document("address", "0xpm")))));
        when(blockScoutProvider.getTransactionTokenTransfers("0xblockscout", NetworkId.BASE))
                .thenReturn(List.of(new ExplorerTokenTransfer(new Document("hash", "0xblockscout")
                        .append("contractAddress", "0xtoken")
                        .append("from", "0xpm")
                        .append("to", "0x1111111111111111111111111111111111111111")
                        .append("value", "100")
                        .append("tokenDecimal", "18")
                        .append("tokenSymbol", "WETH"))));
        when(blockScoutProvider.getTransactionInternalTransfers("0xblockscout", NetworkId.BASE))
                .thenReturn(List.of(new ExplorerInternalTransfer(new Document("hash", "0xblockscout")
                        .append("from", "0xpm")
                        .append("to", "0x1111111111111111111111111111111111111111")
                        .append("value", "100")
                        .append("isError", "0"))));

        Optional<ClarificationReceiptEnrichment> enrichment = gateway.fetchReceiptWithTransferEvidence(rawTransaction);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().sourceFamily()).isEqualTo(RawSyncMethod.BLOCKSCOUT);
        assertThat(enrichment.get().tokenTransfers()).hasSize(1);
        assertThat(enrichment.get().internalTransfers()).hasSize(1);
        verify(blockScoutProvider, never()).getTokenTransfers(any(), any(), anyLong(), anyLong(), anyInt());
        verify(blockScoutProvider, never()).getInternalTransfers(any(), any(), anyLong(), anyLong(), anyInt());
        verify(rpcTokenTransferResolver, never()).buildTokenTransfersFromDocuments(any(), any(), any());
    }

    @Test
    @DisplayName("explorer-backed clarification derives token transfers from receipt logs when endpoints are empty")
    void explorerBackedClarificationDerivesTransfersFromReceiptLogsWhenEndpointsAreEmpty() {
        IngestionNetworkProperties networkProperties = networkProperties(NetworkId.AVALANCHE, IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        ReceiptClarificationGateway gateway = new ReceiptClarificationGateway(
                etherscanProvider,
                blockScoutProvider,
                rpcClient,
                rpcTokenTransferResolver,
                networkProperties,
                new ObjectMapper()
        );

        RawTransaction rawTransaction = raw(NetworkId.AVALANCHE, RawSyncMethod.ETHERSCAN, "0xderive");
        List<Document> receiptLogs = List.of(new Document("address", "0xtoken")
                .append("topics", List.of(
                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                        "0x0000000000000000000000001111111111111111111111111111111111111111",
                        "0x0000000000000000000000002222222222222222222222222222222222222222"
                ))
                .append("data", "0x64"));
        when(etherscanProvider.getReceipt("0xderive", NetworkId.AVALANCHE))
                .thenReturn(new ExplorerReceipt(new Document("status", "0x1")
                        .append("gasUsed", "0x5208")
                        .append("effectiveGasPrice", "0x3b9aca00")
                        .append("blockNumber", "0x2")
                        .append("logs", receiptLogs)));
        when(etherscanProvider.getTokenTransfers(rawTransaction.getWalletAddress(), NetworkId.AVALANCHE, 2L, 2L, 1))
                .thenReturn(List.of());
        when(etherscanProvider.getInternalTransfers(rawTransaction.getWalletAddress(), NetworkId.AVALANCHE, 2L, 2L, 1))
                .thenReturn(List.of());
        when(rpcTokenTransferResolver.buildTokenTransfersFromDocuments("https://rpc.example", NetworkId.AVALANCHE.name(), receiptLogs))
                .thenReturn(List.of(new Document("contractAddress", "0xtoken")
                        .append("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0x2222222222222222222222222222222222222222")
                        .append("value", "100")
                        .append("tokenDecimal", "18")
                        .append("tokenSymbol", "TOK")));

        Optional<ClarificationReceiptEnrichment> enrichment = gateway.fetchReceiptWithTransferEvidence(rawTransaction);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().tokenTransfers()).hasSize(1);
        verify(rpcTokenTransferResolver).buildTokenTransfersFromDocuments("https://rpc.example", NetworkId.AVALANCHE.name(), receiptLogs);
    }

    private static IngestionNetworkProperties networkProperties(
            NetworkId networkId,
            IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod syncMethod
    ) {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(syncMethod);
        entry.setUrls(List.of("https://rpc.example"));
        properties.getNetwork().put(networkId.name(), entry);
        return properties;
    }

    private static RawTransaction raw(NetworkId networkId, RawSyncMethod syncMethod, String txHash) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":0xwallet");
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress("0x1111111111111111111111111111111111111111");
        rawTransaction.setSyncMethod(syncMethod);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", "0x1111111111111111111111111111111111111111")
                .append("to", "0x2222222222222222222222222222222222222222")
                .append("input", "0x")
                .append("value", "0"));
        return rawTransaction;
    }
}
