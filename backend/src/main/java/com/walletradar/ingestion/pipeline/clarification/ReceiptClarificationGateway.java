package com.walletradar.ingestion.pipeline.clarification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.ingestion.adapter.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.rpc.support.RpcTokenTransferResolver;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.support.BsonCoercionSupport;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.Instant;
import java.util.Set;

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

    public Optional<ClarificationReceiptEnrichment> fetchReceipt(RawTransaction rawTransaction) {
        return fetch(rawTransaction, false);
    }

    public Optional<ClarificationReceiptEnrichment> fetchReceiptWithTransferEvidence(RawTransaction rawTransaction) {
        return fetch(rawTransaction, true);
    }

    public List<String> findWalletRelatedTransactionHashes(
            String walletAddress,
            NetworkId networkId,
            RawSyncMethod sourceFamily,
            long fromBlock,
            long toBlock,
            int maxPages
    ) {
        if (walletAddress == null || networkId == null || fromBlock <= 0 || toBlock < fromBlock) {
            return List.of();
        }
        ExplorerProvider provider = providerFor(sourceFamily, networkId);
        if (provider == null) {
            return List.of();
        }

        Set<String> hashes = new LinkedHashSet<>();
        int pageLimit = Math.max(1, maxPages);
        for (int page = 1; page <= pageLimit; page++) {
            List<ExplorerTokenTransfer> tokenTransfers = provider.getTokenTransfers(walletAddress, networkId, fromBlock, toBlock, page);
            List<ExplorerInternalTransfer> internalTransfers = provider.getInternalTransfers(walletAddress, networkId, fromBlock, toBlock, page);
            if (tokenTransfers.isEmpty() && internalTransfers.isEmpty()) {
                break;
            }
            for (ExplorerTokenTransfer transfer : tokenTransfers) {
                if (transfer != null && transfer.hash() != null && !transfer.hash().isBlank()) {
                    hashes.add(transfer.hash().trim().toLowerCase(Locale.ROOT));
                }
            }
            for (ExplorerInternalTransfer transfer : internalTransfers) {
                if (transfer != null && transfer.hash() != null && !transfer.hash().isBlank()) {
                    hashes.add(transfer.hash().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(hashes);
    }

    public Optional<RawTransaction> fetchRawTransactionByHash(
            String txHash,
            NetworkId networkId,
            String walletAddress,
            RawSyncMethod sourceFamily
    ) {
        if (txHash == null || txHash.isBlank() || networkId == null || walletAddress == null || walletAddress.isBlank()) {
            return Optional.empty();
        }
        ExplorerProvider provider = providerFor(sourceFamily, networkId);
        if (provider == null) {
            return Optional.empty();
        }

        ExplorerTransaction transaction = provider.getTransaction(txHash, networkId);
        ExplorerTransactionDetails details = provider.getTransactionDetails(txHash, networkId);
        ExplorerReceipt receipt = provider.getReceipt(txHash, networkId);
        if (transaction == null && details == null && receipt == null) {
            return Optional.empty();
        }

        Document preferredTxDetails = details != null
                ? details.asDocument()
                : transaction != null ? transaction.asDocument() : new Document();
        long blockNumber = parseFlexibleLong(
                details != null ? details.blockNumber() : transaction != null ? transaction.blockNumber() : null
        );
        List<Document> receiptLogs = receipt == null
                ? List.of()
                : readDocumentList(receipt.asDocument(), "logs");
        List<Document> tokenTransfers = loadExplorerTokenTransfers(
                provider,
                walletAddress,
                networkId,
                txHash,
                blockNumber,
                receiptLogs
        );
        List<Document> internalTransfers = loadExplorerInternalTransfers(
                provider,
                walletAddress,
                networkId,
                txHash,
                blockNumber
        );

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash.toLowerCase(Locale.ROOT) + ":" + networkId.name() + ":" + walletAddress.toLowerCase(Locale.ROOT));
        rawTransaction.setTxHash(txHash.toLowerCase(Locale.ROOT));
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setSyncMethod(sourceFamily != null ? sourceFamily : sourceFamilyFromConfig(networkId));
        rawTransaction.setWalletAddress(walletAddress.toLowerCase(Locale.ROOT));
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setRetryCount(0);
        rawTransaction.setCreatedAt(Instant.now());
        if (blockNumber > 0) {
            rawTransaction.setBlockNumber(blockNumber);
        }

        Document rawData = preferredTxDetails == null ? new Document() : new Document(preferredTxDetails);
        Document explorer = new Document();
        if (preferredTxDetails != null && !preferredTxDetails.isEmpty()) {
            explorer.put("tx", new Document(preferredTxDetails));
        }
        explorer.put("tokenTransfers", copyDocuments(tokenTransfers));
        explorer.put("internalTransfers", copyDocuments(internalTransfers));
        rawData.put("explorer", explorer);
        rawTransaction.setRawData(rawData);

        Optional<ClarificationReceiptEnrichment> enrichment = receipt == null
                ? Optional.empty()
                : ClarificationReceiptEnrichment.fromReceipt(
                        receipt,
                        rawTransaction.getSyncMethod(),
                        tokenTransfers,
                        internalTransfers
                );
        enrichment.ifPresent(candidate -> {
            RawTransactionClarificationEnricher enricher = new RawTransactionClarificationEnricher();
            enricher.merge(rawTransaction, candidate);
            enricher.recordFullReceiptAttempt(rawTransaction, 0, null);
        });
        return Optional.of(rawTransaction);
    }

    private Optional<ClarificationReceiptEnrichment> fetch(RawTransaction rawTransaction, boolean includeTransferEvidence) {
        if (rawTransaction == null) {
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
            case ETHERSCAN -> fetchFromExplorer(etherscanProvider, rawTransaction, view, includeTransferEvidence, sourceFamily);
            case BLOCKSCOUT -> fetchFromExplorer(blockScoutProvider, rawTransaction, view, includeTransferEvidence, sourceFamily);
            case RPC -> fetchFromRpc(rawTransaction, view, includeTransferEvidence, sourceFamily);
        };
    }

    private ExplorerProvider providerFor(RawSyncMethod sourceFamily, NetworkId networkId) {
        if (sourceFamily == RawSyncMethod.ETHERSCAN) {
            return etherscanProvider.supports(networkId) ? etherscanProvider : null;
        }
        if (sourceFamily == RawSyncMethod.BLOCKSCOUT) {
            return blockScoutProvider.supports(networkId) ? blockScoutProvider : null;
        }
        RawSyncMethod resolved = sourceFamily != null ? sourceFamily : sourceFamilyFromConfig(networkId);
        if (resolved == RawSyncMethod.ETHERSCAN) {
            return etherscanProvider.supports(networkId) ? etherscanProvider : null;
        }
        if (resolved == RawSyncMethod.BLOCKSCOUT) {
            return blockScoutProvider.supports(networkId) ? blockScoutProvider : null;
        }
        return null;
    }

    private Optional<ClarificationReceiptEnrichment> fetchFromExplorer(
            ExplorerProvider provider,
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            boolean includeTransferEvidence,
            RawSyncMethod sourceFamily
    ) {
        ExplorerReceipt receipt = provider.getReceipt(view.txHash(), view.networkId());
        if (receipt == null) {
            return Optional.empty();
        }
        long blockNumber = parseFlexibleLong(receipt.blockNumber());
        List<Document> receiptLogs = readDocumentList(receipt.asDocument(), "logs");
        List<Document> tokenTransfers = includeTransferEvidence
                ? loadExplorerTokenTransfers(provider, rawTransaction, view, blockNumber, receiptLogs)
                : List.of();
        List<Document> internalTransfers = includeTransferEvidence
                ? loadExplorerInternalTransfers(provider, rawTransaction, view, blockNumber)
                : List.of();
        return ClarificationReceiptEnrichment.fromReceipt(
                receipt,
                sourceFamily,
                tokenTransfers,
                internalTransfers
        );
    }

    private Optional<ClarificationReceiptEnrichment> fetchFromRpc(
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            boolean includeTransferEvidence,
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
            List<Document> tokenTransfers = includeTransferEvidence
                    ? rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                            endpoint,
                            rawTransaction.getNetworkId(),
                            receiptLogs
                    )
                    : List.of();
            return ClarificationReceiptEnrichment.fromReceiptDocument(
                    receipt,
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
            long blockNumber,
            List<Document> receiptLogs
    ) {
        if (provider instanceof BlockScoutExplorerProvider blockScoutProvider) {
            List<Document> byHash = blockScoutProvider.getTransactionTokenTransfers(view.txHash(), view.networkId())
                    .stream()
                    .map(ExplorerTokenTransfer::asDocument)
                    .toList();
            if (!byHash.isEmpty()) {
                return List.copyOf(byHash);
            }
        }

        List<Document> filtered = new ArrayList<>();
        if (blockNumber > 0) {
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
        }
        if (!filtered.isEmpty()) {
            return List.copyOf(filtered);
        }

        String endpoint = primaryRpcEndpoint(view.networkId());
        if (endpoint == null || receiptLogs == null || receiptLogs.isEmpty()) {
            return List.of();
        }
        List<Document> derived = rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                endpoint,
                rawTransaction.getNetworkId(),
                receiptLogs
        );
        if (derived.isEmpty()) {
            return List.of();
        }
        List<Document> txScoped = new ArrayList<>(derived.size());
        for (Document transfer : derived) {
            if (transfer != null) {
                txScoped.add(new Document(transfer));
            }
        }
        return List.copyOf(txScoped);
    }

    private List<Document> loadExplorerTokenTransfers(
            ExplorerProvider provider,
            String walletAddress,
            NetworkId networkId,
            String txHash,
            long blockNumber,
            List<Document> receiptLogs
    ) {
        if (provider instanceof BlockScoutExplorerProvider blockScoutProvider) {
            List<Document> byHash = blockScoutProvider.getTransactionTokenTransfers(txHash, networkId)
                    .stream()
                    .map(ExplorerTokenTransfer::asDocument)
                    .toList();
            if (!byHash.isEmpty()) {
                return List.copyOf(byHash);
            }
        }
        if (walletAddress != null && blockNumber > 0) {
            List<Document> filtered = new ArrayList<>();
            for (int page = 1; page <= MAX_EXPLORER_TRANSFER_PAGES; page++) {
                List<ExplorerTokenTransfer> transfers = provider.getTokenTransfers(
                        walletAddress,
                        networkId,
                        blockNumber,
                        blockNumber,
                        page
                );
                if (transfers.isEmpty()) {
                    break;
                }
                for (ExplorerTokenTransfer transfer : transfers) {
                    if (transfer != null && sameHash(txHash, transfer.hash())) {
                        filtered.add(transfer.asDocument());
                    }
                }
            }
            if (!filtered.isEmpty()) {
                return List.copyOf(filtered);
            }
        }
        String endpoint = primaryRpcEndpoint(networkId);
        if (endpoint == null || receiptLogs == null || receiptLogs.isEmpty()) {
            return List.of();
        }
        return List.copyOf(rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                endpoint,
                networkId.name(),
                receiptLogs
        ));
    }

    private List<Document> loadExplorerInternalTransfers(
            ExplorerProvider provider,
            RawTransaction rawTransaction,
            OnChainRawTransactionView view,
            long blockNumber
    ) {
        if (provider instanceof BlockScoutExplorerProvider blockScoutProvider) {
            List<Document> byHash = blockScoutProvider.getTransactionInternalTransfers(view.txHash(), view.networkId())
                    .stream()
                    .map(ExplorerInternalTransfer::asDocument)
                    .toList();
            if (!byHash.isEmpty()) {
                return List.copyOf(byHash);
            }
        }
        if (blockNumber <= 0) {
            return List.of();
        }
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

    private List<Document> loadExplorerInternalTransfers(
            ExplorerProvider provider,
            String walletAddress,
            NetworkId networkId,
            String txHash,
            long blockNumber
    ) {
        if (provider instanceof BlockScoutExplorerProvider blockScoutProvider) {
            List<Document> byHash = blockScoutProvider.getTransactionInternalTransfers(txHash, networkId)
                    .stream()
                    .map(ExplorerInternalTransfer::asDocument)
                    .toList();
            if (!byHash.isEmpty()) {
                return List.copyOf(byHash);
            }
        }
        if (walletAddress == null || blockNumber <= 0) {
            return List.of();
        }
        List<Document> filtered = new ArrayList<>();
        for (int page = 1; page <= MAX_EXPLORER_TRANSFER_PAGES; page++) {
            List<ExplorerInternalTransfer> transfers = provider.getInternalTransfers(
                    walletAddress,
                    networkId,
                    blockNumber,
                    blockNumber,
                    page
            );
            if (transfers.isEmpty()) {
                break;
            }
            for (ExplorerInternalTransfer transfer : transfers) {
                if (transfer != null && sameHash(txHash, transfer.hash())) {
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

    private long parseFlexibleLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Long.parseLong(value.substring(2), 16);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private List<Document> copyDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> copies = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document != null) {
                copies.add(BsonCoercionSupport.copyDocument(document));
            }
        }
        return List.copyOf(copies);
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

    private static List<Document> readDocumentList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        return BsonCoercionSupport.asDocumentList(parent.get(key));
    }
}
