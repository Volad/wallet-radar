package com.walletradar.ingestion.pipeline.onchain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.support.RawOrderingMetadataResolver;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Safe typed accessor over the raw BSON payload used by the normalization worker.
 */
public final class OnChainRawTransactionView {

    private final RawTransaction rawTransaction;

    private OnChainRawTransactionView(RawTransaction rawTransaction) {
        this.rawTransaction = rawTransaction;
    }

    public static OnChainRawTransactionView wrap(RawTransaction rawTransaction) {
        return new OnChainRawTransactionView(rawTransaction);
    }

    public String txHash() {
        if (rawTransaction.getTxHash() == null) {
            return null;
        }
        String value = rawTransaction.getTxHash().trim();
        return value.isEmpty() ? null : value;
    }

    public String walletAddress() {
        if (rawTransaction.getWalletAddress() == null) {
            return null;
        }
        String value = rawTransaction.getWalletAddress().trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    public NetworkId networkId() {
        if (rawTransaction.getNetworkId() == null || rawTransaction.getNetworkId().isBlank()) {
            return null;
        }
        try {
            return NetworkId.valueOf(rawTransaction.getNetworkId().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Instant blockTimestamp() {
        Long epochSeconds = RawOrderingMetadataResolver.resolve(rawTransaction).epochSeconds();
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    public String fromAddress() {
        return normalizeAddress(stringify(readTxLevelField("from", true)));
    }

    public String toAddress() {
        return normalizeAddress(stringify(readTxLevelField("to", true)));
    }

    public String methodId() {
        String topLevelMethodId = normalizeSelector(stringify(readRawField("methodId")));
        if (topLevelMethodId != null) {
            return topLevelMethodId;
        }
        String explorerMethodId = normalizeSelector(stringify(readExplorerTxField("methodId")));
        if (explorerMethodId != null) {
            return explorerMethodId;
        }

        String input = inputData();
        if (input != null && input.length() >= 10) {
            String derivedFromInput = normalizeSelector(input.substring(0, 10));
            if (derivedFromInput != null) {
                return derivedFromInput;
            }
        }

        return "0x";
    }

    public String functionName() {
        String value = stringify(readTxLevelField("functionName", false));
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public String inputData() {
        String value = stringify(readTxLevelField("input", false));
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public BigInteger rawValue() {
        return parseUnsignedInteger(readTxLevelField("value", true));
    }

    public BigInteger gasUsed() {
        return parseUnsignedInteger(readRawField("gasUsed"));
    }

    public BigInteger effectiveGasPrice() {
        return parseUnsignedInteger(readRawField("effectiveGasPrice"));
    }

    public BigInteger gasPrice() {
        BigInteger effectiveGasPrice = effectiveGasPrice();
        return effectiveGasPrice != null ? effectiveGasPrice : parseUnsignedInteger(readRawField("gasPrice"));
    }

    public boolean hasGasUsed() {
        return gasUsed() != null;
    }

    public boolean hasGasPriceEvidence() {
        return gasPrice() != null;
    }

    public boolean hasEffectiveGasPriceEvidence() {
        return effectiveGasPrice() != null;
    }

    public boolean hasExecutionStatusEvidence() {
        return stringify(readRawField("txreceipt_status")) != null || stringify(readRawField("isError")) != null;
    }

    public String contractAddress() {
        if (readExplorerTxField("contractAddress") != null) {
            return normalizeAddress(stringify(readExplorerTxField("contractAddress")));
        }
        if (isTransferRowBackedTopLevel()) {
            return null;
        }
        return normalizeAddress(stringify(readRawField("contractAddress")));
    }

    public boolean hasContractAddress() {
        return contractAddress() != null;
    }

    public boolean isContractCreation() {
        return hasExplicitContractCreationSignal()
                && inputData() != null
                && !"0x".equals(inputData());
    }

    public boolean isFeePayer() {
        return walletAddress() != null && walletAddress().equals(fromAddress());
    }

    public List<Document> explorerTokenTransfers() {
        List<Document> clarificationTransfers = readDocumentList(clarificationTransfersDocument(), "tokenTransfers");
        if (!clarificationTransfers.isEmpty()) {
            return clarificationTransfers;
        }
        return readDocumentList(readExplorerSection(), "tokenTransfers");
    }

    public List<Document> explorerInternalTransfers() {
        List<Document> clarificationTransfers = readDocumentList(clarificationTransfersDocument(), "internalTransfers");
        if (!clarificationTransfers.isEmpty()) {
            return clarificationTransfers;
        }
        return readDocumentList(readExplorerSection(), "internalTransfers");
    }

    public List<Document> persistedLogs() {
        List<Document> clarificationLogs = readDocumentList(clarificationReceiptDocument(), "logs");
        if (!clarificationLogs.isEmpty()) {
            return clarificationLogs;
        }
        List<Document> fullReceiptLogs = readDocumentList(clarificationFullReceiptDocument(), "logs");
        if (!fullReceiptLogs.isEmpty()) {
            return fullReceiptLogs;
        }
        Document rawData = rawTransaction.getRawData();
        return filterSyntheticLogs(readDocumentList(rawData, "logs"));
    }

    public boolean hasClarificationEvidence() {
        return clarificationEvidenceDocument() != null;
    }

    public boolean hasFullReceiptClarificationEvidence() {
        if (clarificationEvidenceDocument() == null) {
            return false;
        }
        if (clarificationEvidenceDocument().get("fullReceipt") instanceof Document) {
            return true;
        }
        Document transfers = clarificationTransfersDocument();
        if (transfers != null && (!readDocumentList(transfers, "tokenTransfers").isEmpty()
                || !readDocumentList(transfers, "internalTransfers").isEmpty())) {
            return true;
        }
        Document receipt = clarificationReceiptDocument();
        return receipt != null && !readDocumentList(receipt, "logs").isEmpty();
    }

    public int clarificationAttemptCount() {
        Integer explicitAttempts = parseInteger(clarificationEvidenceValue("clarificationAttempts"));
        if (explicitAttempts != null) {
            return Math.max(0, explicitAttempts);
        }
        return hasClarificationEvidence() ? 1 : 0;
    }

    public int fullReceiptClarificationAttemptCount() {
        Integer explicitAttempts = parseInteger(clarificationEvidenceValue("fullReceiptClarificationAttempts"));
        if (explicitAttempts != null) {
            return Math.max(0, explicitAttempts);
        }
        return hasFullReceiptClarificationEvidence() ? 1 : 0;
    }

    public String tokenTransferFrom(Document transfer) {
        return normalizeAddress(transfer == null ? null : stringify(transfer.get("from")));
    }

    public String tokenTransferTo(Document transfer) {
        return normalizeAddress(transfer == null ? null : stringify(transfer.get("to")));
    }

    public String tokenTransferContract(Document transfer) {
        if (transfer == null) {
            return null;
        }
        String contract = normalizeAddress(stringify(transfer.get("contractAddress")));
        if (contract != null) {
            return contract;
        }
        return normalizeAddress(stringify(transfer.get("tokenAddress")));
    }

    public String tokenTransferSymbol(Document transfer) {
        return transfer == null ? null : stringify(transfer.get("tokenSymbol"));
    }

    public String tokenTransferName(Document transfer) {
        return transfer == null ? null : stringify(transfer.get("tokenName"));
    }

    public Integer tokenTransferDecimals(Document transfer) {
        return transfer == null ? null : parseInteger(transfer.get("tokenDecimal"));
    }

    public BigDecimal tokenTransferQuantity(Document transfer) {
        if (transfer == null) {
            return null;
        }
        BigInteger value = parseUnsignedInteger(transfer.get("value"));
        if (value == null) {
            return null;
        }
        Integer decimals = parseInteger(transfer.get("tokenDecimal"));
        int scale = decimals == null ? 0 : Math.max(0, decimals);
        return new BigDecimal(value).movePointLeft(scale);
    }

    public String internalTransferFrom(Document transfer) {
        return normalizeAddress(transfer == null ? null : stringify(transfer.get("from")));
    }

    public String internalTransferTo(Document transfer) {
        return normalizeAddress(transfer == null ? null : stringify(transfer.get("to")));
    }

    public BigDecimal internalTransferQuantity(Document transfer) {
        if (transfer == null) {
            return null;
        }
        BigInteger value = parseUnsignedInteger(transfer.get("value"));
        return value == null ? null : new BigDecimal(value).movePointLeft(18);
    }

    public boolean internalTransferErrored(Document transfer) {
        String isError = transfer == null ? null : stringify(transfer.get("isError"));
        return "1".equals(isError);
    }

    public Integer transactionIndex() {
        return RawOrderingMetadataResolver.resolve(rawTransaction).transactionIndex();
    }

    public boolean isFailedExecution() {
        String isError = stringify(readRawField("isError"));
        if ("1".equals(isError)) {
            return true;
        }
        String receiptStatus = stringify(readRawField("txreceipt_status"));
        return "0".equals(receiptStatus);
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (txHash() == null) {
            errors.add("Missing txHash");
        }
        if (walletAddress() == null) {
            errors.add("Missing walletAddress");
        }
        NetworkId networkId = networkId();
        if (networkId == null) {
            errors.add("Missing or unsupported networkId");
        } else if (networkId == NetworkId.SOLANA) {
            errors.add("SOLANA normalization is out of scope");
        }
        if (blockTimestamp() == null) {
            errors.add("Missing rawData.timeStamp");
        }
        if (transactionIndex() == null) {
            errors.add("Missing rawData.transactionIndex");
        }
        return errors;
    }

    public static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        return normalized.length() == 42 ? normalized : null;
    }

    private Object readRawField(String key) {
        Document rawData = rawTransaction.getRawData();
        if (rawData == null) {
            return null;
        }
        return rawData.get(key);
    }

    private Object readTxLevelField(String key, boolean suppressTransferRowFallback) {
        Object explorerValue = readExplorerTxField(key);
        if (explorerValue != null) {
            return explorerValue;
        }
        if (suppressTransferRowFallback && isTransferRowBackedTopLevel()) {
            return null;
        }
        return readRawField(key);
    }

    private Object readExplorerTxField(String key) {
        Document tx = explorerTxDocument();
        if (tx == null) {
            return null;
        }
        return tx.get(key);
    }

    private Document explorerTxDocument() {
        Document explorer = readExplorerSection();
        if (explorer == null) {
            return null;
        }
        Object txObject = explorer.get("tx");
        return txObject instanceof Document tx ? tx : null;
    }

    private Document readExplorerSection() {
        Object explorer = readRawField("explorer");
        if (explorer instanceof Document document) {
            return document;
        }
        return null;
    }

    private Document clarificationEvidenceDocument() {
        Object clarificationEvidence = readRawField("clarificationEvidence");
        return clarificationEvidence instanceof Document document ? document : null;
    }

    private Document clarificationReceiptDocument() {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        Object receipt = clarificationEvidence.get("receipt");
        return receipt instanceof Document document ? document : null;
    }

    private Document clarificationTransfersDocument() {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        Object transfers = clarificationEvidence.get("transfers");
        return transfers instanceof Document document ? document : null;
    }

    private Document clarificationFullReceiptDocument() {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        Object fullReceipt = clarificationEvidence.get("fullReceipt");
        return fullReceipt instanceof Document document ? document : null;
    }

    private Object clarificationEvidenceValue(String key) {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        return clarificationEvidence.get(key);
    }

    private boolean isTransferRowBackedTopLevel() {
        Document rawData = rawTransaction.getRawData();
        if (rawData == null) {
            return false;
        }
        if (readExplorerTxField("from") != null || readExplorerTxField("to") != null || readExplorerTxField("value") != null) {
            return false;
        }
        List<Document> tokenTransfers = explorerTokenTransfers();
        if (tokenTransfers.isEmpty()) {
            return false;
        }
        Document firstTransfer = tokenTransfers.getFirst();
        boolean carriesTokenMetadata = firstTransfer != null
                && (rawData.containsKey("tokenSymbol") || rawData.containsKey("tokenName") || rawData.containsKey("tokenDecimal"));
        if (!carriesTokenMetadata) {
            return false;
        }

        return safeEquals(stringify(rawData.get("value")), stringify(firstTransfer.get("value")))
                && safeEquals(normalizeAddress(stringify(rawData.get("from"))), tokenTransferFrom(firstTransfer))
                && safeEquals(normalizeAddress(stringify(rawData.get("to"))), tokenTransferTo(firstTransfer))
                && safeEquals(normalizeAddress(stringify(rawData.get("contractAddress"))), tokenTransferContract(firstTransfer));
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
                documents.add(document);
            }
        }
        return Collections.unmodifiableList(documents);
    }

    private static List<Document> filterSyntheticLogs(List<Document> logs) {
        if (logs.isEmpty()) {
            return logs;
        }
        List<Document> filtered = new ArrayList<>(logs.size());
        for (Document log : logs) {
            if (log == null) {
                continue;
            }
            Object syntheticFlag = log.get("__syntheticTransferLog");
            if (syntheticFlag instanceof Boolean synthetic && synthetic) {
                continue;
            }
            if ("true".equalsIgnoreCase(stringify(syntheticFlag)) || "1".equals(stringify(syntheticFlag))) {
                continue;
            }
            filtered.add(log);
        }
        return Collections.unmodifiableList(filtered);
    }

    private static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        String result = value.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static BigInteger parseUnsignedInteger(Object value) {
        String text = stringify(value);
        if (text == null) {
            return null;
        }
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return new BigInteger(text.substring(2), 16);
            }
            return new BigInteger(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(Object value) {
        return RawOrderingMetadataResolver.parseFlexibleLong(value);
    }

    private static String normalizeSelector(String selector) {
        String normalized = stringify(selector);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        return normalized.matches("0x[0-9a-f]{8}") ? normalized : null;
    }

    private static Integer parseInteger(Object value) {
        return RawOrderingMetadataResolver.parseFlexibleInteger(value);
    }

    private boolean hasExplicitContractCreationSignal() {
        return explicitBooleanField("creates")
                || explicitBooleanField("contractCreation")
                || explicitBooleanField("isContractCreation")
                || explicitlyMissingTxToField();
    }

    private boolean explicitBooleanField(String key) {
        Object explorerValue = readExplorerTxField(key);
        if (parseBoolean(explorerValue)) {
            return true;
        }
        return parseBoolean(readRawField(key));
    }

    private boolean explicitlyMissingTxToField() {
        Document explorerTx = explorerTxDocument();
        if (explorerTx != null && explorerTx.containsKey("to")) {
            return normalizeAddress(stringify(explorerTx.get("to"))) == null;
        }
        Document rawData = rawTransaction.getRawData();
        return rawData != null
                && rawData.containsKey("to")
                && normalizeAddress(stringify(rawData.get("to"))) == null;
    }

    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringify(value);
        if (text == null) {
            return false;
        }
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private static boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
