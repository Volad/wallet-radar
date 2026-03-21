package com.walletradar.ingestion.adapter.evm.explorer;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import com.walletradar.ingestion.config.IngestionExplorerProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExplorerEvmNetworkAdapterTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Mock
    private ExplorerProvider explorerProvider;

    private ExplorerEvmNetworkAdapter adapter;

    @BeforeEach
    void setUp() {
        IngestionExplorerProperties properties = new IngestionExplorerProperties();
        properties.setMaxPagesPerWindow(5);
        IngestionNetworkProperties networkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry networkEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        networkEntry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        networkProperties.getNetwork().put(NetworkId.ARBITRUM.name(), networkEntry);
        adapter = new ExplorerEvmNetworkAdapter(explorerProvider, properties, networkProperties);
    }

    @Test
    void fetchTransactionsMergesExplorerStreamsAndStoresTransactionDetails() throws Exception {
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getTransactions(WALLET, NetworkId.ARBITRUM, 100L, 200L, 1))
                .thenReturn(List.of(new ExplorerTransaction(new Document()
                        .append("hash", "0xAbC")
                        .append("blockNumber", "123")
                        .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                        .append("value", "0"))));
        when(explorerProvider.getTransactions(WALLET, NetworkId.ARBITRUM, 100L, 200L, 2))
                .thenReturn(List.of());
        when(explorerProvider.getTokenTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 1))
                .thenReturn(List.of(new ExplorerTokenTransfer(new Document()
                        .append("hash", "0xabc")
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                        .append("value", "1000000")
                        .append("logIndex", "42")
                        .append("tokenSymbol", "USDC"))));
        when(explorerProvider.getTokenTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 2))
                .thenReturn(List.of());
        when(explorerProvider.getInternalTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 1))
                .thenReturn(List.of());
        when(explorerProvider.getInternalTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 2))
                .thenReturn(List.of());
        List<RawTransaction> result = adapter.fetchTransactions(WALLET, NetworkId.ARBITRUM, 100L, 200L);

        assertThat(result).hasSize(1);
        RawTransaction tx = result.get(0);
        assertThat(tx.getTxHash()).isEqualTo("0xabc");
        assertThat(tx.getId()).isEqualTo("0xabc:ARBITRUM:" + WALLET);
        assertThat(tx.getRetryCount()).isZero();
        assertThat(tx.getSyncMethod()).isEqualTo(RawSyncMethod.ETHERSCAN);
        assertThat(tx.getBlockNumber()).isEqualTo(123L);

        Document explorer = (Document) tx.getRawData().get("explorer");
        assertThat(explorer).isNotNull();
        assertThat((Document) explorer.get("tx")).containsEntry("hash", "0xAbC");
        assertThat((List<?>) explorer.get("tokenTransfers")).hasSize(1);
        assertThat((List<?>) explorer.get("internalTransfers")).isEmpty();
        assertThat(tx.getRawData()).containsEntry("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        assertThat(tx.getRawData().containsKey("logs")).isFalse();

        verify(explorerProvider, never()).getTransaction("0xabc", NetworkId.ARBITRUM);
    }

    @Test
    void fetchTransactionsUsesTokenTransferAsTxDetailsWhenTxListMissing() throws Exception {
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getTransactions(WALLET, NetworkId.ARBITRUM, 100L, 200L, 1))
                .thenReturn(List.of());
        when(explorerProvider.getTransactions(WALLET, NetworkId.ARBITRUM, 100L, 200L, 2))
                .thenReturn(List.of());
        when(explorerProvider.getTokenTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 1))
                .thenReturn(List.of(new ExplorerTokenTransfer(new Document()
                        .append("hash", "0xabc")
                        .append("blockNumber", "123")
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                        .append("value", "1000000")
                        .append("logIndex", "1")
                        .append("tokenSymbol", "USDC"))));
        when(explorerProvider.getTokenTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 2))
                .thenReturn(List.of());
        when(explorerProvider.getInternalTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 1))
                .thenReturn(List.of());
        when(explorerProvider.getInternalTransfers(WALLET, NetworkId.ARBITRUM, 100L, 200L, 2))
                .thenReturn(List.of());

        List<RawTransaction> result = adapter.fetchTransactions(WALLET, NetworkId.ARBITRUM, 100L, 200L);

        assertThat(result).hasSize(1);
        RawTransaction tx = result.get(0);
        assertThat(tx.getBlockNumber()).isEqualTo(123L);
        assertThat(tx.getRawData().get("explorer")).isNotNull();
        assertThat(tx.getRawData()).containsEntry("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        assertThat(tx.getRawData()).containsEntry("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(tx.getRawData()).containsEntry("value", "1000000");

        verify(explorerProvider, never()).getTransaction("0xabc", NetworkId.ARBITRUM);
    }

    @Test
    void fetchTransactionsReturnsEmptyWhenNetworkNotSupported() {
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(false);

        List<RawTransaction> result = adapter.fetchTransactions(WALLET, NetworkId.ARBITRUM, 1L, 2L);

        assertThat(result).isEmpty();
    }

}
