package com.walletradar.ingestion.adapter.evm.rpc.nativerpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class NativeRpcTransactionRepairGateway {

    private final EvmRpcClient rpcClient;
    private final ObjectMapper objectMapper;

    public void repair(String endpoint, String txHash, Document rawData) {
        if (endpoint == null || endpoint.isBlank() || txHash == null || txHash.isBlank() || rawData == null) {
            return;
        }

        JsonNode transaction = needsTransactionRepair(rawData)
                ? getResult(endpoint, "eth_getTransactionByHash", List.of(txHash))
                : null;
        JsonNode receipt = needsReceiptRepair(rawData)
                ? getResult(endpoint, "eth_getTransactionReceipt", List.of(txHash))
                : null;

        mergeTransaction(rawData, transaction);
        mergeReceipt(rawData, receipt);

        if (missing(rawData, "timeStamp")) {
            Long blockNumber = parseFlexibleLong(rawData.get("blockNumber"));
            if (blockNumber != null && blockNumber > 0L) {
                JsonNode block = getResult(endpoint, "eth_getBlockByNumber", List.of("0x" + Long.toHexString(blockNumber), false));
                if (block != null && !block.isNull()) {
                    Long epochSeconds = parseFlexibleLong(block.path("timestamp").asText(null));
                    if (epochSeconds != null) {
                        rawData.put("timeStamp", Long.toString(epochSeconds));
                    }
                }
            }
        }

        addIngestBlockerIfMissing(rawData, "transactionIndex", "MISSING_TRANSACTION_INDEX");
        addIngestBlockerIfMissing(rawData, "timeStamp", "MISSING_BLOCK_TIMESTAMP");
        addIngestBlockerIfMissing(rawData, "txreceipt_status", "MISSING_RECEIPT_STATUS");
        addIngestBlockerIfMissing(rawData, "gasUsed", "MISSING_GAS_USED");
    }

    private boolean needsTransactionRepair(Document rawData) {
        return missing(rawData, "transactionIndex")
                || missing(rawData, "input")
                || missing(rawData, "from")
                || missing(rawData, "to")
                || missing(rawData, "value")
                || missing(rawData, "blockNumber");
    }

    private boolean needsReceiptRepair(Document rawData) {
        return missing(rawData, "transactionIndex")
                || missing(rawData, "txreceipt_status")
                || missing(rawData, "gasUsed")
                || missing(rawData, "logs");
    }

    private void mergeTransaction(Document rawData, JsonNode transaction) {
        if (transaction == null || transaction.isMissingNode() || transaction.isNull()) {
            return;
        }
        copyIfMissing(rawData, "hash", transaction, "hash");
        copyIfMissing(rawData, "blockHash", transaction, "blockHash");
        copyIfMissing(rawData, "blockNumber", transaction, "blockNumber");
        copyIfMissing(rawData, "from", transaction, "from");
        copyIfMissing(rawData, "to", transaction, "to");
        copyIfMissing(rawData, "input", transaction, "input");
        copyIfMissing(rawData, "value", transaction, "value");
        copyIfMissing(rawData, "nonce", transaction, "nonce");
        copyIfMissing(rawData, "gas", transaction, "gas");
        copyIfMissing(rawData, "gasPrice", transaction, "gasPrice");
        copyIfMissing(rawData, "maxFeePerGas", transaction, "maxFeePerGas");
        copyIfMissing(rawData, "maxPriorityFeePerGas", transaction, "maxPriorityFeePerGas");
        copyIfMissing(rawData, "type", transaction, "type");
        copyIfMissing(rawData, "transactionIndex", transaction, "transactionIndex");

        if (missing(rawData, "methodId")) {
            String input = textValue(rawData.get("input"));
            if (input != null && input.length() >= 10) {
                rawData.put("methodId", input.substring(0, 10).toLowerCase(Locale.ROOT));
            }
        }
    }

    private void mergeReceipt(Document rawData, JsonNode receipt) {
        if (receipt == null || receipt.isMissingNode() || receipt.isNull()) {
            return;
        }
        copyIfMissing(rawData, "contractAddress", receipt, "contractAddress");
        copyIfMissing(rawData, "gasUsed", receipt, "gasUsed");
        copyIfMissing(rawData, "cumulativeGasUsed", receipt, "cumulativeGasUsed");
        copyIfMissing(rawData, "effectiveGasPrice", receipt, "effectiveGasPrice");
        if (missing(rawData, "transactionIndex")) {
            copyIfMissing(rawData, "transactionIndex", receipt, "transactionIndex");
        }
        if (missing(rawData, "logs")) {
            rawData.put("logs", jsonNodeToDocumentList(receipt.path("logs")));
        }

        String status = normalizeReceiptStatus(receipt.path("status").asText(null));
        if (status != null && missing(rawData, "txreceipt_status")) {
            rawData.put("txreceipt_status", status);
        }
        if (status != null && missing(rawData, "isError")) {
            rawData.put("isError", "0".equals(status) ? "1" : "0");
        }
    }

    private JsonNode getResult(String endpoint, String method, Object params) {
        try {
            String json = rpcClient.call(endpoint, method, params).block();
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull() && !error.isEmpty()) {
                return null;
            }
            JsonNode result = root.path("result");
            return result.isMissingNode() || result.isNull() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyIfMissing(Document target, String targetKey, JsonNode source, String sourceKey) {
        if (target == null || source == null || source.isMissingNode() || source.isNull() || !missing(target, targetKey)) {
            return;
        }
        JsonNode value = source.path(sourceKey);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        target.put(targetKey, value.asText());
    }

    private static boolean missing(Document rawData, String key) {
        return textValue(rawData.get(key)) == null;
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

    private List<Document> jsonNodeToDocumentList(JsonNode logs) {
        if (logs == null || !logs.isArray()) {
            return List.of();
        }
        try {
            List<Document> documents = new java.util.ArrayList<>();
            for (JsonNode log : logs) {
                documents.add(Document.parse(objectMapper.writeValueAsString(log)));
            }
            return List.copyOf(documents);
        } catch (Exception ex) {
            throw new RpcException("Failed to convert repaired logs into Document list", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addIngestBlockerIfMissing(Document rawData, String key, String blocker) {
        if (!missing(rawData, key)) {
            return;
        }
        Object current = rawData.get("ingestBlockers");
        if (current instanceof List<?> blockers) {
            if (!blockers.contains(blocker)) {
                ((List<Object>) blockers).add(blocker);
            }
            return;
        }
        rawData.put("ingestBlockers", new java.util.ArrayList<>(List.of(blocker)));
    }
}
