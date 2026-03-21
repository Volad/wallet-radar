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
        return normalizeAddress(stringify(readRawField("from")));
    }

    public String toAddress() {
        return normalizeAddress(stringify(readRawField("to")));
    }

    public String methodId() {
        String topLevelMethodId = normalizeSelector(stringify(readRawField("methodId")));
        if (topLevelMethodId != null) {
            return topLevelMethodId;
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
        String value = stringify(readRawField("functionName"));
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public String inputData() {
        String value = stringify(readRawField("input"));
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public BigInteger rawValue() {
        return parseUnsignedInteger(readRawField("value"));
    }

    public BigInteger gasUsed() {
        return parseUnsignedInteger(readRawField("gasUsed"));
    }

    public BigInteger gasPrice() {
        BigInteger effectiveGasPrice = parseUnsignedInteger(readRawField("effectiveGasPrice"));
        return effectiveGasPrice != null ? effectiveGasPrice : parseUnsignedInteger(readRawField("gasPrice"));
    }

    public boolean hasGasUsed() {
        return gasUsed() != null;
    }

    public boolean hasGasPriceEvidence() {
        return gasPrice() != null;
    }

    public boolean hasExecutionStatusEvidence() {
        return stringify(readRawField("txreceipt_status")) != null || stringify(readRawField("isError")) != null;
    }

    public String contractAddress() {
        return normalizeAddress(stringify(readRawField("contractAddress")));
    }

    public boolean hasContractAddress() {
        return contractAddress() != null;
    }

    public boolean isContractCreation() {
        return toAddress() == null && inputData() != null && !"0x".equals(inputData());
    }

    public boolean isFeePayer() {
        return walletAddress() != null && walletAddress().equals(fromAddress());
    }

    public List<Document> explorerTokenTransfers() {
        return readDocumentList(readExplorerSection(), "tokenTransfers");
    }

    public List<Document> explorerInternalTransfers() {
        return readDocumentList(readExplorerSection(), "internalTransfers");
    }

    public List<Document> persistedLogs() {
        Document rawData = rawTransaction.getRawData();
        return readDocumentList(rawData, "logs");
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

    private Document readExplorerSection() {
        Object explorer = readRawField("explorer");
        if (explorer instanceof Document document) {
            return document;
        }
        return null;
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
}
