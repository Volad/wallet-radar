package com.walletradar.ingestion.pipeline.clarification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.rpc.support.RpcTokenTransferResolver;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fetches clarification evidence through the same source family that produced the raw row.
 */
@Service
@RequiredArgsConstructor
public class ReceiptClarificationGateway {

    private static final int MAX_EXPLORER_TRANSFER_PAGES = 3;

    private final EtherscanV2ExplorerProvider etherscanProvider;
    private final BlockScoutExplorerProvider blockScoutProvider;
    private final EvmRpcClient rpcClient;
    private final RpcTokenTransferResolver rpcTokenTransferResolver;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final ObjectMapper objectMapper;

    public Optional<ClarificationReceiptEnrichment> fetch(RawTransaction rawTransaction, ClarificationMode mode) {
        if (rawTransaction == null || mode == null) {
            return Optional.empty();
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NetworkId networkId = view.networkId();
        String txHash = view.txHash();
        if (networkId == null || txHash == null || txHash.isBlank()) {
            return Optional.empty();
        }

        RawSyncMethod sourceFamily = rawTransaction.getSyncMethod();
        if (sourceFamily == null) {
            sourceFamily = sourceFamilyFromConfig(networkId);
        }
        if (sourceFamily == null) {
            return Optional.empty();
        }

        return switch (sourceFamily) {
            case ETHERSCAN -> fetchFromExplorer(etherscanProvider, rawTransaction, view, mode, sourceFamily);
            case BLOCKSCOUT -> fetchFromExplorer(blockScoutProvider, rawTransaction, view, mode, sourceFamily);
            case RPC -> fetchFromRpc(rawTransaction, view, mode, sourceFamily);
        };
    }

    private Optional<ClarificationReceiptEnrichment> fetchFromExplorer(
            ExplorerProvider provider,
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            ClarificationMode mode,
            RawSyncMethod sourceFamily
    ) {
        ExplorerReceipt receipt = provider.getReceipt(view.txHash(), view.networkId());
        if (receipt == null) {
            return Optional.empty();
        }
        if (mode == ClarificationMode.METADATA_ONLY) {
            return ClarificationReceiptEnrichment.fromReceipt(receipt, mode, sourceFamily, List.of(), List.of());
        }

        long blockNumber = parseFlexibleLong(receipt.blockNumber());
        List<Document> tokenTransfers = blockNumber > 0
                ? loadExplorerTokenTransfers(provider, rawTransaction, view, blockNumber)
                : List.of();
        List<Document> internalTransfers = blockNumber > 0
                ? loadExplorerInternalTransfers(provider, rawTransaction, view, blockNumber)
                : List.of();
        return ClarificationReceiptEnrichment.fromReceipt(
                receipt,
                mode,
                sourceFamily,
                tokenTransfers,
                internalTransfers
        );
    }

    private Optional<ClarificationReceiptEnrichment> fetchFromRpc(
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            ClarificationMode mode,
            RawSyncMethod sourceFamily
    ) {
        String endpoint = primaryRpcEndpoint(view.networkId());
        if (endpoint == null) {
            return Optional.empty();
        }
        try {
            String json = rpcClient.call(endpoint, "eth_getTransactionReceipt", List.of(view.txHash())).block();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull() || !result.isObject()) {
                return Optional.empty();
            }
            Document receipt = Document.parse(objectMapper.writeValueAsString(result));
            List<Document> receiptLogs = readDocumentList(receipt, "logs");
            List<Document> tokenTransfers = mode == ClarificationMode.FULL_RECEIPT
                    ? rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                            endpoint,
                            rawTransaction.getNetworkId(),
                            receiptLogs
                    )
                    : List.of();
            return ClarificationReceiptEnrichment.fromReceiptDocument(
                    receipt,
                    mode,
                    sourceFamily,
                    tokenTransfers,
                    List.of()
            );
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private List<Document> loadExplorerTokenTransfers(
            ExplorerProvider provider,
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            long blockNumber
    ) {
        List<Document> filtered = new ArrayList<>();
        for (int page = 1; page <= MAX_EXPLORER_TRANSFER_PAGES; page++) {
            List<ExplorerTokenTransfer> transfers = provider.getTokenTransfers(
                    rawTransaction.getWalletAddress(),
                    view.networkId(),
                    blockNumber,
                    blockNumber,
                    page
            );
            if (transfers.isEmpty()) {
                break;
            }
            for (ExplorerTokenTransfer transfer : transfers) {
                if (transfer != null && sameHash(view.txHash(), transfer.hash())) {
                    filtered.add(transfer.asDocument());
                }
            }
        }
        return List.copyOf(filtered);
    }

    private List<Document> loadExplorerInternalTransfers(
            ExplorerProvider provider,
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            long blockNumber
    ) {
        List<Document> filtered = new ArrayList<>();
        for (int page = 1; page <= MAX_EXPLORER_TRANSFER_PAGES; page++) {
            List<ExplorerInternalTransfer> transfers = provider.getInternalTransfers(
                    rawTransaction.getWalletAddress(),
                    view.networkId(),
                    blockNumber,
                    blockNumber,
                    page
            );
            if (transfers.isEmpty()) {
                break;
            }
            for (ExplorerInternalTransfer transfer : transfers) {
                if (transfer != null && sameHash(view.txHash(), transfer.hash())) {
                    filtered.add(transfer.asDocument());
                }
            }
        }
        return List.copyOf(filtered);
    }

    private RawSyncMethod sourceFamilyFromConfig(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = ingestionNetworkProperties.getNetwork()
                .get(networkId.name().toUpperCase(Locale.ROOT));
        if (entry == null || entry.getSyncMethod() == null) {
            return null;
        }
        return switch (entry.getSyncMethod()) {
            case ETHERSCAN -> RawSyncMethod.ETHERSCAN;
            case BLOCKSCOUT -> RawSyncMethod.BLOCKSCOUT;
            case RPC -> RawSyncMethod.RPC;
        };
    }

    private String primaryRpcEndpoint(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = ingestionNetworkProperties.getNetwork()
                .get(networkId.name().toUpperCase(Locale.ROOT));
        if (entry == null || entry.getUrls() == null || entry.getUrls().isEmpty()) {
            return null;
        }
        String endpoint = entry.getUrls().getFirst();
        return endpoint == null || endpoint.isBlank() ? null : endpoint.trim();
    }

    private static boolean sameHash(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static long parseFlexibleLong(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Long.parseLong(value.substring(2), 16);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static List<Document> readDocumentList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        Object raw = parent.get(key);
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Document document) {
                documents.add(new Document(document));
            }
        }
        return List.copyOf(documents);
    }
}
