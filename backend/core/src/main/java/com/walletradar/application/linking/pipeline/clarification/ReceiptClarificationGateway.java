package com.walletradar.application.linking.pipeline.clarification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.platform.networks.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.ExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerReceipt;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTransaction;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.support.RpcTokenTransferResolver;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import com.walletradar.application.normalization.pipeline.classification.support.TokenSymbolFallbackSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.platform.persistence.support.BsonCoercionSupport;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.Set;

/**
 * Fetches clarification evidence through the same source family that produced the raw row.
 */
@Service
@RequiredArgsConstructor
public class ReceiptClarificationGateway {

    private static final String ERC20_TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final int MAX_EXPLORER_TRANSFER_PAGES = 3;

    private final EtherscanV2ExplorerProvider etherscanProvider;
    private final BlockScoutExplorerProvider blockScoutProvider;
    private final EvmRpcClient rpcClient;
    private final RpcTokenTransferResolver rpcTokenTransferResolver;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final ObjectMapper objectMapper;

    public Optional<ClarificationReceiptEnrichment> fetchReceipt(RawTransaction rawTransaction) {
        return fetch(rawTransaction, false, false);
    }

    public Optional<ClarificationReceiptEnrichment> fetchReceiptWithTransferEvidence(RawTransaction rawTransaction) {
        return fetch(rawTransaction, true, false);
    }

    public Optional<ClarificationReceiptEnrichment> fetchFullReceipt(RawTransaction rawTransaction) {
        return fetch(rawTransaction, false, true);
    }

    public Optional<ClarificationReceiptEnrichment> fromPersistedEvidence(
            RawTransaction rawTransaction,
            boolean includeTransferEvidence
    ) {
        if (rawTransaction == null) {
            return Optional.empty();
        }
        Document clarificationEvidence = persistedClarificationEvidence(rawTransaction);
        if (clarificationEvidence == null || clarificationEvidence.isEmpty()) {
            return Optional.empty();
        }

        Document receiptDocument = BsonCoercionSupport.asDocument(clarificationEvidence.get("receipt"));
        Document fullReceiptDocument = BsonCoercionSupport.asDocument(clarificationEvidence.get("fullReceipt"));
        Document transfersDocument = BsonCoercionSupport.asDocument(clarificationEvidence.get("transfers"));

        List<Document> receiptLogs = readDocumentList(fullReceiptDocument, "logs");
        if (receiptLogs.isEmpty()) {
            receiptLogs = readDocumentList(receiptDocument, "logs");
        }
        List<Document> tokenTransfers = readDocumentList(transfersDocument, "tokenTransfers");
        List<Document> internalTransfers = readDocumentList(transfersDocument, "internalTransfers");
        Document fullReceiptPayload = BsonCoercionSupport.copyDocument(fullReceiptDocument);
        RawSyncMethod sourceFamily = resolvePersistedSourceFamily(clarificationEvidence, rawTransaction.getSyncMethod());

        ClarificationReceiptEnrichment enrichment = new ClarificationReceiptEnrichment(
                firstNonBlank(stringify(receiptDocument == null ? null : receiptDocument.get("txReceiptStatus")),
                        normalizeStatus(stringify(fullReceiptDocument == null ? null : fullReceiptDocument.get("status")))),
                firstNonBlank(stringify(receiptDocument == null ? null : receiptDocument.get("gasUsed")),
                        stringify(fullReceiptDocument == null ? null : fullReceiptDocument.get("gasUsed"))),
                firstNonBlank(stringify(receiptDocument == null ? null : receiptDocument.get("effectiveGasPrice")),
                        stringify(receiptDocument == null ? null : receiptDocument.get("gasPrice")),
                        stringify(fullReceiptDocument == null ? null : fullReceiptDocument.get("effectiveGasPrice")),
                        stringify(fullReceiptDocument == null ? null : fullReceiptDocument.get("gasPrice"))),
                firstNonBlank(
                        OnChainRawTransactionView.normalizeAddress(stringify(receiptDocument == null ? null : receiptDocument.get("contractAddress"))),
                        OnChainRawTransactionView.normalizeAddress(stringify(fullReceiptDocument == null ? null : fullReceiptDocument.get("contractAddress")))
                ),
                firstNonBlank(stringify(receiptDocument == null ? null : receiptDocument.get("blockNumber")),
                        stringify(fullReceiptDocument == null ? null : fullReceiptDocument.get("blockNumber"))),
                List.copyOf(receiptLogs),
                List.copyOf(tokenTransfers),
                List.copyOf(internalTransfers),
                fullReceiptPayload,
                sourceFamily
        );

        if (includeTransferEvidence) {
            return !tokenTransfers.isEmpty() || !internalTransfers.isEmpty()
                    ? Optional.of(enrichment)
                    : Optional.empty();
        }
        if (!hasPersistedMetadataEvidence(enrichment)) {
            return Optional.empty();
        }
        return Optional.of(enrichment);
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
        RawSyncMethod resolvedSourceFamily = sourceFamily != null ? sourceFamily : sourceFamilyFromConfig(networkId);
        if (resolvedSourceFamily == RawSyncMethod.RPC) {
            return fetchRawTransactionByHashFromRpc(txHash, networkId, walletAddress);
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
        rawTransaction.setSyncMethod(resolvedSourceFamily);
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

    private Optional<RawTransaction> fetchRawTransactionByHashFromRpc(
            String txHash,
            NetworkId networkId,
            String walletAddress
    ) {
        String endpoint = primaryRpcEndpoint(networkId);
        if (endpoint == null) {
            return Optional.empty();
        }

        JsonNode txNode = rpcResult(endpoint, "eth_getTransactionByHash", List.of(txHash));
        JsonNode receiptNode = rpcResult(endpoint, "eth_getTransactionReceipt", List.of(txHash));
        if (txNode == null && receiptNode == null) {
            return Optional.empty();
        }

        Document txDocument = toDocument(txNode);
        Document receiptDocument = toDocument(receiptNode);
        Document rawData = txDocument != null ? new Document(txDocument) : new Document();
        mergeRpcReceipt(rawData, receiptDocument);
        enrichRpcTimestamp(endpoint, rawData);
        normalizeRpcRaw(rawData);

        List<Document> receiptLogs = readDocumentList(rawData, "logs");
        List<Document> tokenTransfers = receiptLogs.isEmpty()
                ? List.of()
                : List.copyOf(rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                endpoint,
                networkId.name(),
                receiptLogs
        ));

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash.toLowerCase(Locale.ROOT) + ":" + networkId.name() + ":" + walletAddress.toLowerCase(Locale.ROOT));
        rawTransaction.setTxHash(txHash.toLowerCase(Locale.ROOT));
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setSyncMethod(RawSyncMethod.RPC);
        rawTransaction.setWalletAddress(walletAddress.toLowerCase(Locale.ROOT));
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setRetryCount(0);
        rawTransaction.setCreatedAt(Instant.now());
        Long blockNumber = parseFlexibleLong(stringify(rawData.get("blockNumber")));
        if (blockNumber > 0) {
            rawTransaction.setBlockNumber(blockNumber);
        }

        Document explorer = new Document();
        Document explorerTx = new Document();
        copyIfPresent(explorerTx, "hash", rawData, "hash");
        copyIfPresent(explorerTx, "txhash", rawData, "hash");
        copyIfPresent(explorerTx, "blockNumber", rawData, "blockNumber");
        copyIfPresent(explorerTx, "timeStamp", rawData, "timeStamp");
        copyIfPresent(explorerTx, "transactionIndex", rawData, "transactionIndex");
        copyIfPresent(explorerTx, "from", rawData, "from");
        copyIfPresent(explorerTx, "to", rawData, "to");
        copyIfPresent(explorerTx, "input", rawData, "input");
        copyIfPresent(explorerTx, "value", rawData, "value");
        copyIfPresent(explorerTx, "methodId", rawData, "methodId");
        copyIfPresent(explorerTx, "txreceipt_status", rawData, "txreceipt_status");
        copyIfPresent(explorerTx, "isError", rawData, "isError");
        if (!explorerTx.isEmpty()) {
            explorer.put("tx", explorerTx);
        }
        explorer.put("tokenTransfers", copyDocuments(tokenTransfers));
        explorer.put("internalTransfers", List.of());
        rawData.put("explorer", explorer);
        rawTransaction.setRawData(rawData);

        Optional<ClarificationReceiptEnrichment> enrichment = receiptDocument == null
                ? Optional.empty()
                : ClarificationReceiptEnrichment.fromReceiptDocument(
                receiptDocument,
                RawSyncMethod.RPC,
                tokenTransfers,
                List.of()
        );
        enrichment.ifPresent(candidate -> {
            RawTransactionClarificationEnricher enricher = new RawTransactionClarificationEnricher();
            enricher.merge(rawTransaction, candidate);
            enricher.recordFullReceiptAttempt(rawTransaction, 0, null);
        });
        return Optional.of(rawTransaction);
    }

    private Optional<ClarificationReceiptEnrichment> fetch(
            RawTransaction rawTransaction,
            boolean includeTransferEvidence,
            boolean deriveTokenTransfersFromReceipt
    ) {
        if (rawTransaction == null) {
            return Optional.empty();
        }
        Optional<ClarificationReceiptEnrichment> persisted = fromPersistedEvidence(rawTransaction, includeTransferEvidence);
        if (persisted.isPresent()) {
            return persisted;
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
            case ETHERSCAN -> fetchFromExplorer(
                    etherscanProvider,
                    rawTransaction,
                    view,
                    includeTransferEvidence,
                    deriveTokenTransfersFromReceipt,
                    sourceFamily
            );
            case BLOCKSCOUT -> fetchFromExplorer(
                    blockScoutProvider,
                    rawTransaction,
                    view,
                    includeTransferEvidence,
                    deriveTokenTransfersFromReceipt,
                    sourceFamily
            );
            case RPC -> fetchFromRpc(
                    rawTransaction,
                    view,
                    includeTransferEvidence,
                    deriveTokenTransfersFromReceipt,
                    sourceFamily
            );
        };
    }

    private Document persistedClarificationEvidence(RawTransaction rawTransaction) {
        if (rawTransaction.getClarificationEvidence() != null) {
            return BsonCoercionSupport.asDocument(rawTransaction.getClarificationEvidence());
        }
        if (rawTransaction.getRawData() == null) {
            return null;
        }
        return BsonCoercionSupport.asDocument(rawTransaction.getRawData().get("clarificationEvidence"));
    }

    private RawSyncMethod resolvePersistedSourceFamily(Document clarificationEvidence, RawSyncMethod fallback) {
        String rawValue = stringify(clarificationEvidence == null ? null : clarificationEvidence.get("sourceFamily"));
        if (rawValue == null) {
            return fallback;
        }
        try {
            return RawSyncMethod.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private boolean hasPersistedMetadataEvidence(ClarificationReceiptEnrichment enrichment) {
        return enrichment != null
                && (enrichment.txReceiptStatus() != null
                || enrichment.gasUsed() != null
                || enrichment.effectiveGasPrice() != null
                || enrichment.contractAddress() != null
                || enrichment.blockNumber() != null
                || !enrichment.receiptLogs().isEmpty()
                || enrichment.fullReceiptPayload() != null);
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("0x1".equals(normalized) || "1".equals(normalized)) {
            return "1";
        }
        if ("0x0".equals(normalized) || "0".equals(normalized)) {
            return "0";
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
            boolean deriveTokenTransfersFromReceipt,
            RawSyncMethod sourceFamily
    ) {
        ExplorerReceipt receipt = provider.getReceipt(view.txHash(), view.networkId());
        if (receipt == null) {
            return Optional.empty();
        }
        long blockNumber = parseFlexibleLong(receipt.blockNumber());
        List<Document> receiptLogs = readDocumentList(receipt.asDocument(), "logs");
        List<Document> tokenTransfers;
        if (includeTransferEvidence) {
            tokenTransfers = loadExplorerTokenTransfers(provider, rawTransaction, view, blockNumber, receiptLogs);
        } else if (deriveTokenTransfersFromReceipt) {
            if (receiptLogs.isEmpty()) {
                // BlockScout receipt has no logs — indexer lag on this block.
                // Fall back to the full explorer+RPC transfer fetch to recover ERC-20 Transfer
                // events from the raw receipt which the RPC node always provides.
                tokenTransfers = loadExplorerTokenTransfers(provider, rawTransaction, view, blockNumber, receiptLogs);
            } else {
                tokenTransfers = deriveTokenTransfersFromReceiptLogs(rawTransaction, receiptLogs);
            }
        } else {
            tokenTransfers = List.of();
        }
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
            boolean deriveTokenTransfersFromReceipt,
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
                    : deriveTokenTransfersFromReceipt
                    ? deriveTokenTransfersFromReceiptLogs(rawTransaction, receiptLogs)
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
        if (endpoint == null) {
            return List.of();
        }
        List<Document> logsToUse = (receiptLogs != null && !receiptLogs.isEmpty())
                ? receiptLogs
                : fetchRpcReceiptLogs(endpoint, view.txHash());
        if (logsToUse.isEmpty()) {
            return List.of();
        }
        List<Document> derived = rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                endpoint,
                rawTransaction.getNetworkId(),
                logsToUse
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
        if (endpoint == null) {
            return List.of();
        }
        List<Document> logsToUse = (receiptLogs != null && !receiptLogs.isEmpty())
                ? receiptLogs
                : fetchRpcReceiptLogs(endpoint, txHash);
        if (logsToUse.isEmpty()) {
            return List.of();
        }
        return List.copyOf(rpcTokenTransferResolver.buildTokenTransfersFromDocuments(
                endpoint,
                networkId.name(),
                logsToUse
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

    private List<Document> deriveTokenTransfersFromReceiptLogs(
            RawTransaction rawTransaction,
            List<Document> receiptLogs
    ) {
        if (receiptLogs == null || receiptLogs.isEmpty()) {
            return List.of();
        }
        Map<String, Document> metadataByContract = existingTokenMetadataByContract(rawTransaction);
        List<Document> transfers = new ArrayList<>();
        for (Document log : receiptLogs) {
            List<String> topics = topicList(log);
            if (topics.size() != 3 || !ERC20_TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String contractAddress = OnChainRawTransactionView.normalizeAddress(stringify(log.get("address")));
            String from = topicAddress(topics.get(1));
            String to = topicAddress(topics.get(2));
            BigInteger value = parseHexQuantity(stringify(log.get("data")));
            if (contractAddress == null || from == null || to == null || value == null) {
                continue;
            }

            Document transfer = new Document()
                    .append("contractAddress", contractAddress)
                    .append("from", from)
                    .append("to", to)
                    .append("value", value.toString());
            copyIfPresent(transfer, "logIndex", log, "logIndex");
            copyIfPresent(transfer, "transactionHash", log, "transactionHash");
            Document metadata = metadataByContract.get(contractAddress);
            if (metadata != null) {
                copyIfPresent(transfer, "tokenSymbol", metadata, "tokenSymbol");
                copyIfPresent(transfer, "tokenName", metadata, "tokenName");
                copyIfPresent(transfer, "tokenDecimal", metadata, "tokenDecimal");
            } else {
                String knownSymbol = TokenSymbolFallbackSupport.resolveSymbolByContract(contractAddress);
                Integer knownDecimals = TokenSymbolFallbackSupport.resolveDecimalsByContract(contractAddress);
                if (knownSymbol != null) {
                    transfer.put("tokenSymbol", knownSymbol);
                }
                if (knownDecimals != null) {
                    transfer.put("tokenDecimal", Integer.toString(knownDecimals));
                }
            }
            transfers.add(transfer);
        }
        return List.copyOf(transfers);
    }

    private Map<String, Document> existingTokenMetadataByContract(RawTransaction rawTransaction) {
        Map<String, Document> byContract = new LinkedHashMap<>();
        if (rawTransaction == null || rawTransaction.getRawData() == null) {
            return byContract;
        }
        appendTokenMetadata(byContract, readDocumentList(rawTransaction.getRawData(), "tokenTransfers"));
        appendTokenMetadata(byContract, readDocumentList(readExplorerSection(rawTransaction), "tokenTransfers"));
        Document clarificationEvidence = BsonCoercionSupport.asDocument(rawTransaction.getClarificationEvidence());
        Document transfers = clarificationEvidence == null
                ? null
                : BsonCoercionSupport.asDocument(clarificationEvidence.get("transfers"));
        appendTokenMetadata(byContract, readDocumentList(transfers, "tokenTransfers"));
        return byContract;
    }

    private void appendTokenMetadata(Map<String, Document> byContract, List<Document> transfers) {
        for (Document transfer : transfers) {
            if (transfer == null) {
                continue;
            }
            String contract = OnChainRawTransactionView.normalizeAddress(firstNonBlank(
                    stringify(transfer.get("contractAddress")),
                    stringify(transfer.get("tokenAddress"))
            ));
            if (contract == null) {
                continue;
            }
            byContract.putIfAbsent(contract, transfer);
        }
    }

    private Document readExplorerSection(RawTransaction rawTransaction) {
        return rawTransaction == null || rawTransaction.getRawData() == null
                ? null
                : BsonCoercionSupport.asDocument(rawTransaction.getRawData().get("explorer"));
    }

    private List<String> topicList(Document log) {
        Object topicsRaw = log == null ? null : log.get("topics");
        if (!(topicsRaw instanceof List<?> topics)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(topics.size());
        for (Object topic : topics) {
            String value = stringify(topic);
            if (value != null) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private String topicAddress(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        String normalized = topic.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x") || normalized.length() < 42) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress("0x" + normalized.substring(normalized.length() - 40));
    }

    private BigInteger parseHexQuantity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank()) {
            return BigInteger.ZERO;
        }
        try {
            return new BigInteger(normalized, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private List<Document> fetchRpcReceiptLogs(String endpoint, String txHash) {
        if (endpoint == null || txHash == null || txHash.isBlank()) {
            return List.of();
        }
        try {
            String json = rpcClient.call(endpoint, "eth_getTransactionReceipt", List.of(txHash)).block();
            if (json == null || json.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull() || !result.isObject()) {
                return List.of();
            }
            Document receipt = Document.parse(objectMapper.writeValueAsString(result));
            return List.copyOf(readDocumentList(receipt, "logs"));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private JsonNode rpcResult(String endpoint, String method, Object params) {
        try {
            String json = rpcClient.call(endpoint, method, params).block();
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull() && !error.isEmpty()) {
                return null;
            }
            JsonNode result = root.path("result");
            return result.isMissingNode() || result.isNull() ? null : result;
        } catch (Exception ex) {
            return null;
        }
    }

    private Document toDocument(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        try {
            return Document.parse(objectMapper.writeValueAsString(node));
        } catch (Exception ex) {
            return null;
        }
    }

    private void mergeRpcReceipt(Document rawData, Document receiptDocument) {
        if (rawData == null || receiptDocument == null) {
            return;
        }
        copyIfPresent(rawData, "blockHash", receiptDocument, "blockHash");
        copyIfPresent(rawData, "blockNumber", receiptDocument, "blockNumber");
        copyIfPresent(rawData, "transactionIndex", receiptDocument, "transactionIndex");
        copyIfPresent(rawData, "contractAddress", receiptDocument, "contractAddress");
        copyIfPresent(rawData, "gasUsed", receiptDocument, "gasUsed");
        copyIfPresent(rawData, "cumulativeGasUsed", receiptDocument, "cumulativeGasUsed");
        copyIfPresent(rawData, "effectiveGasPrice", receiptDocument, "effectiveGasPrice");
        List<Document> logs = readDocumentList(receiptDocument, "logs");
        if (!logs.isEmpty()) {
            rawData.put("logs", copyDocuments(logs));
        }
        String status = normalizeReceiptStatus(stringify(receiptDocument.get("status")));
        if (status != null) {
            rawData.put("txreceipt_status", status);
            rawData.put("isError", "0".equals(status) ? "1" : "0");
        }
    }

    private void enrichRpcTimestamp(String endpoint, Document rawData) {
        if (endpoint == null || rawData == null || stringify(rawData.get("timeStamp")) != null) {
            return;
        }
        Long blockNumber = parseFlexibleLong(stringify(rawData.get("blockNumber")));
        if (blockNumber == null || blockNumber <= 0) {
            return;
        }
        JsonNode blockNode = rpcResult(endpoint, "eth_getBlockByNumber", List.of("0x" + Long.toHexString(blockNumber), false));
        if (blockNode == null) {
            return;
        }
        String timestamp = blockNode.path("timestamp").asText(null);
        Long epochSeconds = parseFlexibleLong(timestamp);
        if (epochSeconds != null) {
            rawData.put("timeStamp", Long.toString(epochSeconds));
        }
    }

    private void normalizeRpcRaw(Document rawData) {
        if (rawData == null) {
            return;
        }
        Long blockNumber = parseFlexibleLong(stringify(rawData.get("blockNumber")));
        if (blockNumber != null) {
            rawData.put("blockNumber", Long.toString(blockNumber));
        }
        Long transactionIndex = parseFlexibleLong(stringify(rawData.get("transactionIndex")));
        if (transactionIndex != null) {
            rawData.put("transactionIndex", Long.toString(transactionIndex));
        }
        Long timestamp = parseFlexibleLong(stringify(rawData.get("timeStamp")));
        if (timestamp != null) {
            rawData.put("timeStamp", Long.toString(timestamp));
        }
        String input = stringify(rawData.get("input"));
        if (input != null && input.length() >= 10 && stringify(rawData.get("methodId")) == null) {
            rawData.put("methodId", input.substring(0, 10).toLowerCase(Locale.ROOT));
        }
    }

    private void copyIfPresent(Document target, String targetKey, Document source, String sourceKey) {
        if (target == null || source == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizeReceiptStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if ("0".equals(status) || "1".equals(status)) {
            return status;
        }
        Long numeric = parseFlexibleLong(status);
        if (numeric == null) {
            return null;
        }
        return numeric == 0L ? "0" : "1";
    }

    private static List<Document> readDocumentList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        return BsonCoercionSupport.asDocumentList(parent.get(key));
    }
}
