package com.walletradar.ingestion.adapter.evm.rpc.provider;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.rpc.support.RpcTokenTransferResolver;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ProviderBackedRawTransactionMapper {

    private final RpcTokenTransferResolver tokenTransferResolver;

    public ProviderBackedRawTransactionMapper(RpcTokenTransferResolver tokenTransferResolver) {
        this.tokenTransferResolver = tokenTransferResolver;
    }

    public RawTransaction toRawTransaction(
            String walletAddress,
            String networkId,
            String providerEndpoint,
            Document providerTransaction
    ) {
        String txHash = textValue(providerTransaction.get("hash"));
        if (txHash == null) {
            return null;
        }

        RawTransaction tx = new RawTransaction();
        tx.setId(txHash + ":" + networkId + ":" + walletAddress);
        tx.setTxHash(txHash.toLowerCase(Locale.ROOT));
        tx.setNetworkId(networkId);
        tx.setSyncMethod(RawSyncMethod.RPC);
        tx.setWalletAddress(walletAddress.toLowerCase(Locale.ROOT));
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount(0);
        tx.setCreatedAt(Instant.now());

        Document rawData = providerTransaction != null ? new Document(providerTransaction) : new Document();
        normalizeProviderRaw(rawData);
        rawData.put("explorer", buildExplorerSection(rawData, txHash, providerEndpoint, networkId));

        tx.setRawData(rawData);
        Long blockNumber = parseFlexibleLong(rawData.get("blockNumber"));
        tx.setBlockNumber(blockNumber);
        return tx;
    }

    public void refreshDerivedEvidence(RawTransaction rawTransaction, String providerEndpoint) {
        if (rawTransaction == null || rawTransaction.getRawData() == null) {
            return;
        }
        Document rawData = rawTransaction.getRawData();
        normalizeProviderRaw(rawData);
        rawData.put("explorer", buildExplorerSection(rawData, rawTransaction.getTxHash(), providerEndpoint, rawTransaction.getNetworkId()));
        rawTransaction.setBlockNumber(parseFlexibleLong(rawData.get("blockNumber")));
    }

    private void normalizeProviderRaw(Document rawData) {
        if (rawData == null) {
            return;
        }
        Long timestamp = parseFlexibleLong(rawData.get("timestamp"));
        if (timestamp != null && textValue(rawData.get("timeStamp")) == null) {
            rawData.put("timeStamp", Long.toString(timestamp));
        }
        Long blockNumber = parseFlexibleLong(rawData.get("blockNumber"));
        if (blockNumber != null) {
            rawData.put("blockNumber", Long.toString(blockNumber));
        }
        Integer transactionIndex = parseFlexibleInteger(rawData.get("transactionIndex"));
        if (transactionIndex != null) {
            rawData.put("transactionIndex", Integer.toString(transactionIndex));
        }
        String input = textValue(rawData.get("input"));
        if (input != null && input.length() >= 10 && textValue(rawData.get("methodId")) == null) {
            rawData.put("methodId", input.substring(0, 10).toLowerCase(Locale.ROOT));
        }
        String status = normalizeReceiptStatus(textValue(rawData.get("status")));
        if (status != null) {
            rawData.put("txreceipt_status", status);
            rawData.put("isError", "0".equals(status) ? "1" : "0");
        }
    }

    private Document buildExplorerSection(Document rawData, String txHash, String providerEndpoint, String networkId) {
        Document explorer = new Document();
        explorer.put("tx", buildExplorerTxDocument(rawData, txHash));
        explorer.put("tokenTransfers", tokenTransferResolver.buildTokenTransfersFromDocuments(
                providerEndpoint,
                networkId,
                documentList(rawData.get("logs"))
        ));
        explorer.put("internalTransfers", List.of());
        return explorer;
    }

    private Document buildExplorerTxDocument(Document rawData, String txHash) {
        Document explorerTx = new Document();
        explorerTx.put("hash", txHash);
        explorerTx.put("txhash", txHash);
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
        return explorerTx;
    }

    private static void copyIfPresent(Document target, String targetKey, Document source, String sourceKey) {
        if (target == null || source == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private static List<Document> documentList(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return List.copyOf(documents);
    }

    private static String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Long parseFlexibleLong(Object value) {
        String text = textValue(value);
        if (text == null) {
            return null;
        }
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Long.parseLong(text.substring(2), 16);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseFlexibleInteger(Object value) {
        Long parsed = parseFlexibleLong(value);
        if (parsed == null || parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE) {
            return null;
        }
        return parsed.intValue();
    }

    private static String normalizeReceiptStatus(String status) {
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
}
