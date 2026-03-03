package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RawTransactionNormalizationViewTest {

    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Test
    void wrap_should_normalize_map_based_raw_data_and_expose_typed_accessors() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "from", "0x1111111111111111111111111111111111111111",
                "logs", List.of(Map.of(
                        "address", "0x2222222222222222222222222222222222222222",
                        "topics", List.of(
                                TRANSFER_TOPIC,
                                "0x000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "0x000000000000000000000000bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        ),
                        "data", "0x000000000000000000000000000000000000000000000000000000000000000a",
                        "logIndex", "0x0a"
                )),
                "explorer", Map.of(
                        "tx", Map.of("to", "0x3333333333333333333333333333333333333333"),
                        "tokenTransfers", List.of(Map.of(
                                "contractAddress", "0x4444444444444444444444444444444444444444",
                                "tokenSymbol", "USDC",
                                "tokenName", "USD Coin",
                                "tokenDecimal", "6",
                                "from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "to", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                                "value", "1234567"
                        ))
                )
        )));

        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);
        Document log = view.logs().get(0);
        Document transfer = view.explorerTokenTransfers().get(0);

        assertThat(view.readRawOrExplorerAddress("from")).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(view.readRawOrExplorerAddress("to")).isEqualTo("0x3333333333333333333333333333333333333333");
        assertThat(view.getLogAddress(log)).isEqualTo("0x2222222222222222222222222222222222222222");
        assertThat(view.getLogTopics(log)).hasSize(3);
        assertThat(view.getLogAmount(log)).isEqualTo(BigInteger.TEN);
        assertThat(view.getLogIndex(log)).isEqualTo(10);
        assertThat(view.tokenTransferContract(transfer)).isEqualTo("0x4444444444444444444444444444444444444444");
        assertThat(view.tokenTransferDecimals(transfer)).isEqualTo(6);
        assertThat(view.tokenTransferFrom(transfer)).isEqualTo("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(view.tokenTransferTo(transfer)).isEqualTo("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(view.tokenTransferValue(transfer)).isEqualTo(new BigInteger("1234567"));
    }

    @Test
    void ensureSyntheticTransferLogsFromExplorer_should_build_logs_when_receipt_logs_absent() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "explorer", Map.of(
                        "tokenTransfers", List.of(Map.of(
                                "contractAddress", "0x4444444444444444444444444444444444444444",
                                "from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "to", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                                "value", "25",
                                "logIndex", "9"
                        ))
                )
        )));

        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);
        assertThat(view.logs()).isEmpty();
        assertThat(view.hasCanonicalLogs()).isFalse();

        view.ensureSyntheticTransferLogsFromExplorer(TRANSFER_TOPIC);

        assertThat(view.logs()).hasSize(1);
        assertThat(view.hasSyntheticLogsOnly()).isTrue();
        assertThat(view.hasCanonicalLogs()).isFalse();
        Document synthetic = view.logs().get(0);
        assertThat(view.getLogAddress(synthetic)).isEqualTo("0x4444444444444444444444444444444444444444");
        assertThat(view.getLogTopics(synthetic)).hasSize(3);
        assertThat(view.getLogTopics(synthetic).get(0)).isEqualTo(TRANSFER_TOPIC);
        assertThat(view.getLogAmount(synthetic)).isEqualTo(BigInteger.valueOf(25));
    }

    @Test
    void mergeReceipt_should_replace_synthetic_logs_with_canonical_logs() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "from", "0x1111111111111111111111111111111111111111",
                "explorer", Map.of(
                        "tx", Map.of("to", "0x3333333333333333333333333333333333333333"),
                        "tokenTransfers", List.of(Map.of(
                                "contractAddress", "0x4444444444444444444444444444444444444444",
                                "from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "to", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                                "value", "25",
                                "logIndex", "9"
                        ))
                )
        )));
        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);
        view.ensureSyntheticTransferLogsFromExplorer(TRANSFER_TOPIC);
        assertThat(view.hasSyntheticLogsOnly()).isTrue();

        ExplorerReceipt receipt = new ExplorerReceipt(new Document(Map.of(
                "blockNumber", "0x65",
                "logs", List.of(Map.of(
                        "address", "0x2222222222222222222222222222222222222222",
                        "topics", List.of(
                                TRANSFER_TOPIC,
                                "0x000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "0x000000000000000000000000bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        ),
                        "data", "0x000000000000000000000000000000000000000000000000000000000000000a",
                        "logIndex", "0x0a"
                ))
        )));
        view.mergeReceipt(receipt);

        assertThat(view.hasCanonicalLogs()).isTrue();
        assertThat(view.hasSyntheticLogsOnly()).isFalse();
        assertThat(tx.getBlockNumber()).isEqualTo(101L);
    }

    @Test
    void mergeTransactionDetails_should_expose_blockscout_alias_fields() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "explorer", Map.of(
                        "tx", Map.of("hash", "0xaaa")
                )
        )));
        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);
        ExplorerTransactionDetails details = new ExplorerTransactionDetails(new Document(Map.of(
                "hash", "0xbbb",
                "blockNumber", "123",
                "raw_input", "0xabcdef",
                "method_id", "0xac9650d8",
                "method_call", "multicall(bytes[] data)",
                "status", "ok",
                "result", "success",
                "from", Map.of("hash", "0x1111111111111111111111111111111111111111"),
                "to", Map.of("hash", "0x2222222222222222222222222222222222222222")
        )));
        view.mergeTransactionDetails(details);

        assertThat(view.hasExplorerDetails()).isTrue();
        assertThat(view.readRawOrExplorerTx("input")).isEqualTo("0xabcdef");
        assertThat(view.readRawOrExplorerTx("methodId")).isEqualTo("0xac9650d8");
        assertThat(view.readRawOrExplorerTx("functionName")).isEqualTo("multicall(bytes[] data)");
        assertThat(view.readRawOrExplorerTx("txreceipt_status")).isEqualTo("1");
        assertThat(view.readRawOrExplorerTx("isError")).isEqualTo("0");
        assertThat(view.readRawOrExplorerAddress("from")).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(view.readRawOrExplorerAddress("to")).isEqualTo("0x2222222222222222222222222222222222222222");
        assertThat(tx.getBlockNumber()).isEqualTo(123L);
    }

    @Test
    void promoteStoredExplorerReceipt_should_copy_receipt_to_top_level_raw_data() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "explorer", Map.of(
                        "receipt", Map.of(
                                "blockNumber", "0x65",
                                "logs", List.of(Map.of(
                                        "address", "0x2222222222222222222222222222222222222222",
                                        "topics", List.of(
                                                TRANSFER_TOPIC,
                                                "0x000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                                "0x000000000000000000000000bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                        ),
                                        "data", "0x000000000000000000000000000000000000000000000000000000000000000a",
                                        "logIndex", "0x0a"
                                ))
                        )
                )
        )));
        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);

        assertThat(view.hasExplorerReceipt()).isTrue();
        assertThat(view.hasExplorerReceiptLogs()).isTrue();
        assertThat(view.logs()).isEmpty();

        view.promoteStoredExplorerReceipt();

        assertThat(view.hasCanonicalLogs()).isTrue();
        assertThat(view.logs()).hasSize(1);
        assertThat(tx.getBlockNumber()).isEqualTo(101L);
    }

    @Test
    void selector_should_fallback_to_input_when_methodId_is_empty() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "methodId", "0x",
                "input", "0x42842e0e0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                        + "000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3"
                        + "000000000000000000000000000000000000000000000000000000000006a68d"
        )));

        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);

        assertThat(view.selector()).isEqualTo("0x42842e0e");
        assertThat(view.hasSelector("42842e0e")).isTrue();
        assertThat(view.isSelectorFromInputFallback()).isTrue();
    }

    @Test
    void decodeSafeTransferFrom_should_decode_addresses_and_tokenId() {
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document(Map.of(
                "input", "0x42842e0e0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                        + "000000000000000000000000c6a2db661d5a5690172d8eb0a7dea2d3008665a3"
                        + "000000000000000000000000000000000000000000000000000000000006a68d"
        )));

        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);
        RawTransactionNormalizationView.SafeTransferFromCall decoded = view.decodeSafeTransferFrom();

        assertThat(decoded).isNotNull();
        assertThat(decoded.from()).isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(decoded.to()).isEqualTo("0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3");
        assertThat(decoded.tokenId()).isEqualTo("435853");
    }
}
