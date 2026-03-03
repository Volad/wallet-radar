package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import org.bson.Document;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classification-time view over {@link RawTransaction} that normalizes rawData shape
 * (Map/List -> BSON Document/List) once and exposes typed accessors required by classifiers.
 */
public final class RawTransactionNormalizationView {

    private static final String SYNTHETIC_LOG_MARKER = "__syntheticTransferLog";
    private static final String SAFE_TRANSFER_FROM_SELECTOR = "0x42842e0e";

    private final RawTransaction rawTransaction;
    private Document normalizedRawData;
    private Document explorer;
    private Document explorerTx;
    private Document explorerDetails;

    private RawTransactionNormalizationView(RawTransaction rawTransaction) {
        this.rawTransaction = rawTransaction;
        replaceRawData(rawTransaction != null ? rawTransaction.getRawData() : null);
    }

    public static RawTransactionNormalizationView wrap(RawTransaction rawTransaction) {
        return new RawTransactionNormalizationView(rawTransaction);
    }

    public RawTransaction rawTransaction() {
        return rawTransaction;
    }

    public String txHash() {
        return rawTransaction != null ? rawTransaction.getTxHash() : null;
    }

    public String networkId() {
        return rawTransaction != null ? rawTransaction.getNetworkId() : null;
    }

    public Long blockNumber() {
        return rawTransaction != null ? rawTransaction.getBlockNumber() : null;
    }

    public Document rawData() {
        return normalizedRawData;
    }

    public boolean hasRawData() {
        return normalizedRawData != null;
    }

    public List<Document> logs() {
        return readDocumentList(normalizedRawData, "logs");
    }

    public boolean hasLogs() {
        return !logs().isEmpty();
    }

    public boolean hasCanonicalLogs() {
        List<Document> logs = logs();
        if (logs.isEmpty()) {
            return false;
        }
        for (Document log : logs) {
            if (log == null) {
                continue;
            }
            Object marker = log.get(SYNTHETIC_LOG_MARKER);
            if (!(marker instanceof Boolean bool) || !bool) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSyntheticLogsOnly() {
        List<Document> logs = logs();
        if (logs.isEmpty()) {
            return false;
        }
        for (Document log : logs) {
            if (log == null) {
                return false;
            }
            Object marker = log.get(SYNTHETIC_LOG_MARKER);
            if (!(marker instanceof Boolean bool) || !bool) {
                return false;
            }
        }
        return true;
    }

    public List<Document> explorerTokenTransfers() {
        return readDocumentList(explorer, "tokenTransfers");
    }

    public List<Document> explorerInternalTransfers() {
        return readDocumentList(explorer, "internalTransfers");
    }

    public Document explorerDetails() {
        return explorerDetails == null ? null : new Document(explorerDetails);
    }

    public boolean hasExplorerDetails() {
        return explorerDetails != null && !explorerDetails.isEmpty();
    }

    public boolean hasExplorerReceipt() {
        Document receipt = getDocument(explorer, "receipt");
        return receipt != null && !receipt.isEmpty();
    }

    public boolean hasExplorerReceiptLogs() {
        Document receipt = getDocument(explorer, "receipt");
        if (receipt == null || receipt.isEmpty()) {
            return false;
        }
        return !readDocumentList(receipt, "logs").isEmpty();
    }

    public String readRawOrExplorerTx(String field) {
        if (field == null || field.isBlank() || normalizedRawData == null) {
            return null;
        }
        Object direct = normalizedRawData.get(field);
        String directText = asString(direct);
        if (directText != null && !directText.isBlank()) {
            return directText;
        }
        if (explorerTx == null) {
            return readExplorerDetailsField(field);
        }
        String fromExplorerTx = asString(explorerTx.get(field));
        if (fromExplorerTx != null && !fromExplorerTx.isBlank()) {
            return fromExplorerTx;
        }
        return readExplorerDetailsField(field);
    }

    public String readRawOrExplorerLower(String field) {
        return normalizeText(readRawOrExplorerTx(field));
    }

    public String readRawOrExplorerAddress(String field) {
        return normalizeAddress(readRawOrExplorerTx(field));
    }

    public BigInteger readRawOrExplorerUnsigned(String field) {
        return parseUnsignedNumeric(readRawOrExplorerTx(field));
    }

    /**
     * Canonical 4-byte selector resolved from methodId or calldata.
     * Priority:
     * 1) methodId (if valid and non-empty)
     * 2) first 4 bytes of input calldata
     */
    public String selector() {
        String methodId = normalizeSelector(readRawOrExplorerTx("methodId"));
        if (methodId != null) {
            return methodId;
        }
        String input = readRawOrExplorerLower("input");
        if (input == null || input.length() < 10 || !input.startsWith("0x")) {
            return null;
        }
        return normalizeSelector(input.substring(0, 10));
    }

    public boolean hasSelector(String selector) {
        String expected = normalizeSelector(selector);
        if (expected == null) {
            return false;
        }
        String actual = selector();
        return actual != null && actual.equals(expected);
    }

    /**
     * True when selector was resolved from calldata because methodId was missing/invalid.
     */
    public boolean isSelectorFromInputFallback() {
        String methodId = normalizeSelector(readRawOrExplorerTx("methodId"));
        if (methodId != null) {
            return false;
        }
        String input = readRawOrExplorerLower("input");
        return input != null && input.startsWith("0x") && input.length() >= 10
                && normalizeSelector(input.substring(0, 10)) != null;
    }

    /**
     * Decodes ABI address argument from top-level calldata by argument index.
     * Works for simple static ABI signatures (32-byte words).
     */
    public String decodeCalldataAddressArg(int argIndex) {
        String word = calldataWordAt(argIndex);
        if (word == null) {
            return null;
        }
        return normalizeAddress("0x" + word.substring(24));
    }

    /**
     * Decodes ABI uint argument from top-level calldata by argument index.
     * Works for simple static ABI signatures (32-byte words).
     */
    public BigInteger decodeCalldataUintArg(int argIndex) {
        String word = calldataWordAt(argIndex);
        if (word == null) {
            return null;
        }
        try {
            return new BigInteger(word, 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public SafeTransferFromCall decodeSafeTransferFrom() {
        if (!hasSelector(SAFE_TRANSFER_FROM_SELECTOR)) {
            return null;
        }
        String from = decodeCalldataAddressArg(0);
        String to = decodeCalldataAddressArg(1);
        BigInteger tokenId = decodeCalldataUintArg(2);
        if (from == null || to == null || tokenId == null) {
            return null;
        }
        return new SafeTransferFromCall(from, to, tokenId.toString());
    }

    public String getLogAddress(Document log) {
        return log != null ? asString(log.get("address")) : null;
    }

    public List<String> getLogTopics(Document log) {
        if (log == null) {
            return List.of();
        }
        Object topicsObj = log.get("topics");
        if (!(topicsObj instanceof List<?> topics) || topics.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(topics.size());
        for (Object topic : topics) {
            if (topic != null) {
                out.add(topic.toString());
            }
        }
        return out;
    }

    public String getLogData(Document log) {
        return log != null ? asString(log.get("data")) : null;
    }

    public BigInteger getLogAmount(Document log) {
        return parseAmountHex(getLogData(log));
    }

    public Integer getLogIndex(Document log) {
        if (log == null) {
            return null;
        }
        Object li = log.get("logIndex");
        if (li == null) {
            return null;
        }
        try {
            if (li instanceof Number number) {
                return number.intValue();
            }
            String text = li.toString().trim();
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Integer.parseInt(text.substring(2), 16);
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public boolean isTransferLog(Document log, String transferTopic) {
        if (transferTopic == null || transferTopic.isBlank()) {
            return false;
        }
        List<String> topics = getLogTopics(log);
        return !topics.isEmpty() && transferTopic.equalsIgnoreCase(topics.get(0));
    }

    public String tokenTransferContract(Document transfer) {
        if (transfer == null) {
            return null;
        }
        String contract = normalizeAddress(asString(transfer.get("contractAddress")));
        if (contract != null) {
            return contract;
        }
        return normalizeAddress(asString(transfer.get("tokenAddress")));
    }

    public String tokenTransferSymbol(Document transfer) {
        return transfer != null ? asString(transfer.get("tokenSymbol")) : null;
    }

    public String tokenTransferName(Document transfer) {
        return transfer != null ? asString(transfer.get("tokenName")) : null;
    }

    public Integer tokenTransferDecimals(Document transfer) {
        if (transfer == null) {
            return null;
        }
        return parseInteger(transfer.get("tokenDecimal"));
    }

    public String tokenTransferFrom(Document transfer) {
        return transfer != null ? normalizeAddress(asString(transfer.get("from"))) : null;
    }

    public String tokenTransferTo(Document transfer) {
        return transfer != null ? normalizeAddress(asString(transfer.get("to"))) : null;
    }

    public BigInteger tokenTransferValue(Document transfer) {
        return transfer != null ? parseUnsignedNumeric(asString(transfer.get("value"))) : null;
    }

    public String tokenTransferIsError(Document transfer) {
        return transfer != null ? asString(transfer.get("isError")) : null;
    }

    public String internalTransferFrom(Document transfer) {
        return transfer != null ? normalizeAddress(asString(transfer.get("from"))) : null;
    }

    public String internalTransferTo(Document transfer) {
        return transfer != null ? normalizeAddress(asString(transfer.get("to"))) : null;
    }

    public BigInteger internalTransferValue(Document transfer) {
        return transfer != null ? parseUnsignedNumeric(asString(transfer.get("value"))) : null;
    }

    public String internalTransferIsError(Document transfer) {
        return transfer != null ? asString(transfer.get("isError")) : null;
    }

    public String padAddressForTopic(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null) {
            return "";
        }
        return "0x" + "0".repeat(24) + normalized.substring(2);
    }

    public String topicToAddress(String topic) {
        if (topic == null || topic.length() < 66) {
            return null;
        }
        return normalizeAddress("0x" + topic.substring(topic.length() - 40));
    }

    public String normalizeAddressValue(String address) {
        return normalizeAddress(address);
    }

    public String normalizeTextValue(String text) {
        return normalizeText(text);
    }

    public BigInteger parseUnsignedNumericValue(String value) {
        return parseUnsignedNumeric(value);
    }

    public BigInteger parseAmountHexValue(String data) {
        return parseAmountHex(data);
    }

    public void ensureSyntheticTransferLogsFromExplorer(String transferTopic) {
        if (normalizedRawData == null || transferTopic == null || transferTopic.isBlank()) {
            return;
        }
        if (normalizedRawData.containsKey("logs")) {
            return;
        }
        List<Document> tokenTransfers = explorerTokenTransfers();
        if (tokenTransfers.isEmpty()) {
            return;
        }

        List<Document> syntheticLogs = new ArrayList<>();
        for (Document transfer : tokenTransfers) {
            if (transfer == null) {
                continue;
            }
            Document synthetic = toSyntheticTransferLog(transfer, transferTopic);
            if (synthetic != null) {
                syntheticLogs.add(synthetic);
            }
        }
        if (!syntheticLogs.isEmpty()) {
            normalizedRawData.put("logs", syntheticLogs);
        }
    }

    public void mergeReceipt(ExplorerReceipt receipt) {
        if (receipt == null) {
            return;
        }
        Document receiptDoc = normalizeDocument(receipt.asDocument());
        if (receiptDoc.isEmpty()) {
            return;
        }
        if (normalizedRawData == null) {
            normalizedRawData = new Document();
        }
        normalizedRawData.putAll(receiptDoc);
        Document explorerSection = ensureExplorerSection();
        explorerSection.put("receipt", new Document(receiptDoc));
        persistAndRefresh();
        Long blockFromReceipt = parseHexLong(receipt.blockNumber());
        if (blockFromReceipt != null && rawTransaction != null) {
            rawTransaction.setBlockNumber(blockFromReceipt);
        }
    }

    public void promoteStoredExplorerReceipt() {
        Document receipt = getDocument(explorer, "receipt");
        if (receipt == null || receipt.isEmpty()) {
            return;
        }
        if (normalizedRawData == null) {
            normalizedRawData = new Document();
        }
        normalizedRawData.putAll(normalizeDocument(receipt));
        persistAndRefresh();
        Long blockFromReceipt = parseHexLong(asString(receipt.get("blockNumber")));
        if (blockFromReceipt != null && rawTransaction != null) {
            rawTransaction.setBlockNumber(blockFromReceipt);
        }
    }

    public void mergeTransactionDetails(ExplorerTransactionDetails details) {
        if (details == null) {
            return;
        }
        Document detailsDoc = normalizeDocument(details.asDocument());
        if (detailsDoc.isEmpty()) {
            return;
        }
        if (normalizedRawData == null) {
            normalizedRawData = new Document();
        }
        Document explorerSection = ensureExplorerSection();
        explorerSection.put("details", new Document(detailsDoc));
        persistAndRefresh();

        String blockNumber = details.blockNumber();
        if (blockNumber != null && rawTransaction != null) {
            Long decimalBlock = parseDecimalLong(blockNumber);
            Long hexBlock = decimalBlock != null ? decimalBlock : parseHexLong(blockNumber);
            if (hexBlock != null && hexBlock > 0) {
                rawTransaction.setBlockNumber(hexBlock);
            }
        }
    }

    public void replaceRawData(Document rawData) {
        normalizedRawData = normalizeDocument(rawData);
        persistAndRefresh();
    }

    public Long blockNumberFromRawHex() {
        if (normalizedRawData == null) {
            return null;
        }
        return parseHexLong(asString(normalizedRawData.get("blockNumber")));
    }

    public Instant readTimestamp() {
        Instant direct = parseEpochSeconds(normalizedRawData != null ? normalizedRawData.get("timeStamp") : null);
        if (direct != null) {
            return direct;
        }
        Instant explorerTs = parseEpochSeconds(explorerTx != null ? explorerTx.get("timeStamp") : null);
        if (explorerTs != null) {
            return explorerTs;
        }
        for (Document transfer : explorerTokenTransfers()) {
            Instant transferTs = parseEpochSeconds(transfer != null ? transfer.get("timeStamp") : null);
            if (transferTs != null) {
                return transferTs;
            }
        }
        return null;
    }

    private static Document normalizeDocument(Document source) {
        Document out = new Document();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            out.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return out;
    }

    private static Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Document document) {
            return normalizeDocument(document);
        }
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out.put(entry.getKey().toString(), normalizeValue(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalizeValue(item));
            }
            return out;
        }
        return value;
    }

    private static List<Document> readDocumentList(Document parent, String field) {
        if (parent == null || field == null || field.isBlank()) {
            return List.of();
        }
        Object raw = parent.get(field);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Document> out = new ArrayList<>(list.size());
        for (Object item : list) {
            Document document = toDocument(item);
            if (document != null) {
                out.add(document);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Document getDocument(Document parent, String field) {
        if (parent == null || field == null) {
            return null;
        }
        return toDocument(parent.get(field));
    }

    private static Document toDocument(Object value) {
        if (value instanceof Document document) {
            return normalizeDocument(document);
        }
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), normalizeValue(entry.getValue()));
                }
            }
            return out;
        }
        return null;
    }

    private static Document toSyntheticTransferLog(Document transfer, String transferTopic) {
        String contract = tokenContract(transfer);
        String from = normalizeAddress(asString(transfer.get("from")));
        String to = normalizeAddress(asString(transfer.get("to")));
        BigInteger value = parseUnsignedNumeric(asString(transfer.get("value")));
        String logIndex = asString(transfer.get("logIndex"));
        if (contract == null || from == null || to == null || value == null) {
            return null;
        }
        Document log = new Document();
        log.put("address", contract);
        log.put("topics", List.of(transferTopic, topicAddress(from), topicAddress(to)));
        log.put("data", "0x" + String.format("%064x", value));
        log.put(SYNTHETIC_LOG_MARKER, true);
        if (logIndex != null && !logIndex.isBlank()) {
            log.put("logIndex", logIndex);
        }
        return log;
    }

    private String readExplorerDetailsField(String field) {
        if (explorerDetails == null || field == null || field.isBlank()) {
            return null;
        }
        Object directValue = explorerDetails.get(field);
        if (!(directValue instanceof Map<?, ?>) && !(directValue instanceof Document)) {
            String direct = asString(directValue);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        return switch (field) {
            case "input" -> firstNonBlank(
                    asString(explorerDetails.get("raw_input")),
                    asString(explorerDetails.get("input"))
            );
            case "methodId" -> firstNonBlank(
                    asString(explorerDetails.get("method_id")),
                    asString(explorerDetails.get("methodId"))
            );
            case "functionName" -> firstNonBlank(
                    asString(explorerDetails.get("method_call")),
                    asString(explorerDetails.get("method"))
            );
            case "txreceipt_status" -> normalizeReceiptStatus(
                    firstNonBlank(asString(explorerDetails.get("result")), asString(explorerDetails.get("status")))
            );
            case "isError" -> normalizeErrorFlag(asString(explorerDetails.get("status")));
            case "blockNumber" -> firstNonBlank(asString(explorerDetails.get("block_number")), asString(explorerDetails.get("blockNumber")));
            case "gasPrice" -> firstNonBlank(asString(explorerDetails.get("gas_price")), asString(explorerDetails.get("gasPrice")));
            case "gasUsed" -> firstNonBlank(asString(explorerDetails.get("gas_used")), asString(explorerDetails.get("gasUsed")));
            case "timeStamp" -> normalizeTimestamp(explorerDetails.get("timestamp"));
            case "from" -> firstNonBlank(addressFromNested(explorerDetails.get("from")), asString(explorerDetails.get("from")));
            case "to" -> firstNonBlank(addressFromNested(explorerDetails.get("to")), asString(explorerDetails.get("to")));
            default -> null;
        };
    }

    private static String normalizeTimestamp(Object rawTimestamp) {
        if (rawTimestamp == null) {
            return null;
        }
        Instant parsed = parseEpochSeconds(rawTimestamp);
        if (parsed != null) {
            return Long.toString(parsed.getEpochSecond());
        }
        String text = asString(rawTimestamp);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.toString(Instant.parse(text).getEpochSecond());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeReceiptStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("success".equals(normalized) || "ok".equals(normalized) || "1".equals(normalized) || "0x1".equals(normalized)) {
            return "1";
        }
        if ("failed".equals(normalized) || "error".equals(normalized) || "0".equals(normalized) || "0x0".equals(normalized)) {
            return "0";
        }
        return null;
    }

    private static String normalizeErrorFlag(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("ok".equals(normalized) || "success".equals(normalized)) {
            return "0";
        }
        if ("error".equals(normalized) || "failed".equals(normalized)) {
            return "1";
        }
        return null;
    }

    private static String addressFromNested(Object nestedAddress) {
        Document nested = toDocument(nestedAddress);
        if (nested == null) {
            return null;
        }
        return asString(nested.get("hash"));
    }

    private Document ensureExplorerSection() {
        if (normalizedRawData == null) {
            normalizedRawData = new Document();
        }
        Document explorerSection = getDocument(normalizedRawData, "explorer");
        if (explorerSection == null) {
            explorerSection = new Document();
            normalizedRawData.put("explorer", explorerSection);
        } else {
            normalizedRawData.put("explorer", explorerSection);
        }
        return explorerSection;
    }

    private void persistAndRefresh() {
        if (rawTransaction != null) {
            rawTransaction.setRawData(normalizedRawData);
        }
        explorer = getDocument(normalizedRawData, "explorer");
        explorerTx = getDocument(explorer, "tx");
        explorerDetails = getDocument(explorer, "details");
    }

    private static String tokenContract(Document transfer) {
        String contract = normalizeAddress(asString(transfer.get("contractAddress")));
        if (contract != null) {
            return contract;
        }
        return normalizeAddress(asString(transfer.get("tokenAddress")));
    }

    private static String topicAddress(String normalizedAddress) {
        return "0x" + "0".repeat(24) + normalizedAddress.substring(2);
    }

    private static String normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String clean = address.trim().toLowerCase();
        if (!clean.startsWith("0x")) {
            clean = "0x" + clean;
        }
        return clean.length() == 42 ? clean : null;
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String calldataWordAt(int argIndex) {
        if (argIndex < 0) {
            return null;
        }
        String input = readRawOrExplorerLower("input");
        if (input == null || !input.startsWith("0x") || input.length() < 10) {
            return null;
        }
        String hex = input.substring(2);
        int start = 8 + (argIndex * 64);
        int end = start + 64;
        if (start < 0 || end > hex.length()) {
            return null;
        }
        String word = hex.substring(start, end);
        if (!word.matches("[0-9a-f]{64}")) {
            return null;
        }
        return word;
    }

    private static String normalizeSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        String normalized = selector.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("0x")) {
            if (normalized.length() != 10) {
                return null;
            }
            return normalized.matches("0x[0-9a-f]{8}") ? normalized : null;
        }
        if (normalized.length() != 8 || !normalized.matches("[0-9a-f]{8}")) {
            return null;
        }
        return "0x" + normalized;
    }

    private static BigInteger parseUnsignedNumeric(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigInteger parsed = (value.startsWith("0x") || value.startsWith("0X"))
                    ? new BigInteger(value.substring(2), 16)
                    : new BigInteger(value);
            return parsed.signum() >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigInteger parseAmountHex(String data) {
        if (data == null || data.length() < 2) {
            return null;
        }
        try {
            return new BigInteger(data.substring(2), 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant parseEpochSeconds(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long seconds;
            if (value instanceof Number number) {
                seconds = number.longValue();
            } else {
                String text = value.toString().trim();
                if (text.isEmpty()) {
                    return null;
                }
                seconds = (text.startsWith("0x") || text.startsWith("0X"))
                        ? Long.parseLong(text.substring(2), 16)
                        : Long.parseLong(text);
            }
            return seconds > 0 ? Instant.ofEpochSecond(seconds) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseHexLong(String hexValue) {
        if (hexValue == null || hexValue.isBlank()) {
            return null;
        }
        String clean = hexValue.trim();
        if (!clean.startsWith("0x") && !clean.startsWith("0X")) {
            return null;
        }
        try {
            return Long.parseLong(clean.substring(2), 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseDecimalLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    public record SafeTransferFromCall(
            String from,
            String to,
            String tokenId
    ) {
    }
}
