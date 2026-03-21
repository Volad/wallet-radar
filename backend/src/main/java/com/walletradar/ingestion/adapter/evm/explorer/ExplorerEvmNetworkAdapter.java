package com.walletradar.ingestion.adapter.evm.explorer;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import com.walletradar.ingestion.config.IngestionExplorerProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explorer-backed EVM adapter that merges transaction, token transfer, and internal transfer pages.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class ExplorerEvmNetworkAdapter implements NetworkAdapter {

    private final ExplorerProvider explorerProvider;
    private final IngestionExplorerProperties explorerProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId != null && networkId != NetworkId.SOLANA && explorerProvider.supports(networkId);
    }

    @Override
    public List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock) {
        if (!supports(networkId) || fromBlock > toBlock) {
            return List.of();
        }
        int maxPages = Math.max(1, explorerProperties.getMaxPagesPerWindow());
        Map<String, TxAggregate> byHash = new LinkedHashMap<>();

        for (int page = 1; page <= maxPages; page++) {
            log.info("Fetching page {} for wallet {} on network {} (blocks {}-{})",
                page, walletAddress, networkId.name(), fromBlock, toBlock);
            List<ExplorerTransaction> txlist = explorerProvider.getTransactions(walletAddress, networkId, fromBlock, toBlock, page);
            List<ExplorerTokenTransfer> tokentx = explorerProvider.getTokenTransfers(walletAddress, networkId, fromBlock, toBlock, page);
            List<ExplorerInternalTransfer> internal = explorerProvider.getInternalTransfers(walletAddress, networkId, fromBlock, toBlock, page);
            mergeTxList(byHash, txlist);
            mergeTokenTransfers(byHash, tokentx);
            mergeInternalTransfers(byHash, internal);

            if (txlist.isEmpty() && tokentx.isEmpty() && internal.isEmpty()) {
                break;
            }
        }

        List<RawTransaction> out = new ArrayList<>(byHash.size());
        for (Map.Entry<String, TxAggregate> e : byHash.entrySet()) {
            String txHash = e.getKey();
            TxAggregate aggregate = e.getValue();
            ExplorerPayloadDetails txDetails = aggregate.preferredTxDetails();

            RawTransaction tx = toRawTransaction(txHash, networkId, walletAddress, aggregate, txDetails);
            if (tx != null) {
                out.add(tx);
            }
        }
        return out;
    }

    @Override
    public int getMaxBlockBatchSize() {
        // Explorer API is page-based; use wide range and page through results.
        return 1_000_000;
    }

    private RawTransaction toRawTransaction(String txHash, NetworkId networkId, String walletAddress,
                                            TxAggregate aggregate, ExplorerPayloadDetails txDetails) {
        RawTransaction tx = new RawTransaction();
        tx.setId(txHash + ":" + networkId.name() + ":" + walletAddress);
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId.name());
        tx.setSyncMethod(resolveSyncMethod(networkId));
        tx.setWalletAddress(walletAddress);
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount(0);
        tx.setCreatedAt(Instant.now());

        Document rawData;
        if (txDetails != null) {
            rawData = txDetails.asDocument();
        } else {
            rawData = new Document();
        }
        rawData.put("explorer", aggregate.toDocument());
        tx.setRawData(rawData);

        Long blockFromTx = parseHexOrDecimalBlock(txDetails != null ? txDetails.blockNumber() : null);
        if (blockFromTx != null && blockFromTx > 0) {
            tx.setBlockNumber(blockFromTx);
        } else {
            tx.setBlockNumber(aggregate.findBestBlockNumber());
        }
        return tx;
    }

    private void mergeTxList(Map<String, TxAggregate> byHash, List<ExplorerTransaction> txlist) {
        for (ExplorerTransaction node : txlist) {
            String hash = normalizeHash(node.hash());
            if (hash == null) continue;
            byHash.computeIfAbsent(hash, k -> new TxAggregate()).tx = node;
        }
    }

    private void mergeTokenTransfers(Map<String, TxAggregate> byHash, List<ExplorerTokenTransfer> tokentx) {
        for (ExplorerTokenTransfer node : tokentx) {
            String hash = normalizeHash(node.hash());
            if (hash == null) continue;
            byHash.computeIfAbsent(hash, k -> new TxAggregate()).tokenTransfers.add(node);
        }
    }

    private void mergeInternalTransfers(Map<String, TxAggregate> byHash, List<ExplorerInternalTransfer> internal) {
        for (ExplorerInternalTransfer node : internal) {
            String hash = normalizeHash(node.hash());
            if (hash == null) continue;
            byHash.computeIfAbsent(hash, k -> new TxAggregate()).internalTransfers.add(node);
        }
    }

    private static String normalizeHash(String hash) {
        return (hash == null || hash.isBlank()) ? null : hash.toLowerCase();
    }

    private RawSyncMethod resolveSyncMethod(NetworkId networkId) {
        if (networkId == null) {
            return RawSyncMethod.ETHERSCAN;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry =
                ingestionNetworkProperties.getNetwork().get(networkId.name());
        if (entry == null || entry.getSyncMethod() == null) {
            return RawSyncMethod.ETHERSCAN;
        }
        return switch (entry.getSyncMethod()) {
            case ETHERSCAN -> RawSyncMethod.ETHERSCAN;
            case BLOCKSCOUT -> RawSyncMethod.BLOCKSCOUT;
            case RPC -> RawSyncMethod.RPC;
        };
    }

    private static Long parseHexOrDecimalBlock(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Long.parseLong(value.substring(2), 16);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final class TxAggregate {
        private ExplorerTransaction tx;
        private final List<ExplorerTokenTransfer> tokenTransfers = new ArrayList<>();
        private final List<ExplorerInternalTransfer> internalTransfers = new ArrayList<>();

        private ExplorerPayloadDetails preferredTxDetails() {
            if (tx != null) {
                return new ExplorerPayloadDetails(tx.asDocument(), tx.blockNumber());
            }
            if (!tokenTransfers.isEmpty()) {
                ExplorerTokenTransfer firstTokenTransfer = tokenTransfers.get(0);
                if (firstTokenTransfer != null) {
                    return new ExplorerPayloadDetails(firstTokenTransfer.asDocument(), firstTokenTransfer.blockNumber());
                }
            }
            return null;
        }

        private Long findBestBlockNumber() {
            String block = tx != null ? tx.blockNumber() : null;
            if (block != null) {
                try {
                    return parseHexOrDecimalBlock(block);
                } catch (NumberFormatException ignored) {
                    // ignore and fallback
                }
            }
            for (ExplorerTokenTransfer t : tokenTransfers) {
                String b = t.blockNumber();
                if (b != null) {
                    try {
                        return parseHexOrDecimalBlock(b);
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
            }
            for (ExplorerInternalTransfer i : internalTransfers) {
                String b = i.blockNumber();
                if (b != null) {
                    try {
                        return parseHexOrDecimalBlock(b);
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
            }
            return 0L;
        }

        private Document toDocument() {
            Document doc = new Document();
            if (tx != null) {
                doc.put("tx", tx.asDocument());
            }
            List<Document> tokenDocs = new ArrayList<>();
            for (ExplorerTokenTransfer n : tokenTransfers) {
                tokenDocs.add(n.asDocument());
            }
            List<Document> internalDocs = new ArrayList<>();
            for (ExplorerInternalTransfer n : internalTransfers) {
                internalDocs.add(n.asDocument());
            }
            doc.put("tokenTransfers", tokenDocs);
            doc.put("internalTransfers", internalDocs);
            return doc;
        }
    }

    private record ExplorerPayloadDetails(Document document, String blockNumber) {
        private Document asDocument() {
            return document == null ? new Document() : new Document(document);
        }
    }
}
