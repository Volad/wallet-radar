package com.walletradar.application.normalization.pipeline.onchain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.support.TokenSymbolFallbackSupport;
import com.walletradar.application.normalization.pipeline.onchain.support.RawOrderingMetadataResolver;
import com.walletradar.platform.persistence.support.BsonCoercionSupport;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
     * Operator-supplied LP position correlationId override. When non-null and non-blank, the
     * normalizer uses this value instead of attempting to decode the tokenId from calldata or
     * receipt logs. Populated manually for transactions whose full receipt is permanently unavailable.
     */
    public String manualCorrelationOverride() {
        String override = rawTransaction.getManualCorrelationOverride();
        return (override == null || override.isBlank()) ? null : override.trim();
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

    public RawSyncMethod syncMethod() {
        return rawTransaction.getSyncMethod();
    }

    public Instant blockTimestamp() {
        Long epochSeconds = RawOrderingMetadataResolver.resolve(rawTransaction).epochSeconds();
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    public String fromAddress() {
        return normalizeAddress(coerceAddressValue(readTxLevelField("from", true)));
    }

    public String toAddress() {
        return normalizeAddress(coerceAddressValue(readTxLevelField("to", true)));
    }

    /**
     * Returns the interacted transaction recipient without suppressing transfer-row fallback.
     * This is useful for protocol identity enrichment where the router/vault entrypoint still matters
     * even if explorer payloads also materialize token-transfer style top-level rows.
     */
    public String interactionToAddress() {
        Object explorerValue = readExplorerTxField("to");
        if (explorerValue != null) {
            return normalizeAddress(coerceAddressValue(explorerValue));
        }
        return normalizeAddress(coerceAddressValue(readRawField("to")));
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
        if (value == null || value.isBlank()) {
            value = stringify(readTxLevelField("raw_input", false));
        }
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public BigInteger rawValue() {
        return parseUnsignedInteger(readTxLevelField("value", true));
    }

    /**
     * Block height of this transaction, or null when unavailable. Used to pin historical on-chain
     * reads (e.g. EVK {@code convertToAssets}) to the rate that applied at the time of the transaction.
     */
    public Long blockNumber() {
        BigInteger value = parseUnsignedInteger(readTxLevelField("blockNumber", false));
        if (value == null) {
            value = parseUnsignedInteger(readRawField("blockNumber"));
        }
        if (value == null || value.signum() <= 0 || value.bitLength() > 63) {
            return null;
        }
        return value.longValue();
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
        List<Document> clarificationTransfers = shouldExposeReceiptClarificationEvidence()
                ? readDocumentList(clarificationTransfersDocument(), "tokenTransfers")
                : List.of();
        List<Document> explorerTransfers = readDocumentList(readExplorerSection(), "tokenTransfers");
        return mergeTokenTransferEvidence(clarificationTransfers, explorerTransfers);
    }

    public List<Document> explorerInternalTransfers() {
        List<Document> clarificationTransfers = shouldExposeReceiptClarificationEvidence()
                ? readDocumentList(clarificationTransfersDocument(), "internalTransfers")
                : List.of();
        List<Document> explorerTransfers = readDocumentList(readExplorerSection(), "internalTransfers");
        return mergeInternalTransferEvidence(clarificationTransfers, explorerTransfers);
    }

    public List<Document> persistedLogs() {
        List<Document> fullReceiptLogs = shouldExposeReceiptClarificationEvidence()
                ? readDocumentList(clarificationFullReceiptDocument(), "logs")
                : List.of();
        if (!fullReceiptLogs.isEmpty()) {
            return fullReceiptLogs;
        }
        List<Document> clarificationLogs = shouldExposeReceiptClarificationEvidence()
                ? readDocumentList(clarificationReceiptDocument(), "logs")
                : List.of();
        if (!clarificationLogs.isEmpty()) {
            return clarificationLogs;
        }
        Document rawData = rawTransaction.getRawData();
        return filterSyntheticLogs(readDocumentList(rawData, "logs"));
    }

    public boolean hasClarificationEvidence() {
        return clarificationEvidenceDocument() != null;
    }

    public boolean hasFullReceiptClarificationEvidence() {
        if (!shouldExposeReceiptClarificationEvidence()) {
            return false;
        }
        if (clarificationFullReceiptDocument() != null) {
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
        return 0;
    }

    public String tokenTransferFrom(Document transfer) {
        return normalizeAddress(coerceAddressValue(transfer == null ? null : transfer.get("from")));
    }

    public String tokenTransferTo(Document transfer) {
        return normalizeAddress(coerceAddressValue(transfer == null ? null : transfer.get("to")));
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
        // RC-2: check for authoritative decimal override first (takes precedence over explorer data).
        // Some explorers (Etherscan) report incorrect tokenDecimal for certain contracts; the override
        // map in TokenSymbolFallbackSupport provides the on-chain-verified correct decimal.
        String contract = tokenTransferContract(transfer);
        Integer overrideDecimal = TokenSymbolFallbackSupport.resolveDecimalOverride(contract);
        if (overrideDecimal != null) {
            return new BigDecimal(value).movePointLeft(Math.max(0, overrideDecimal));
        }
        Integer decimals = parseInteger(transfer.get("tokenDecimal"));
        if (decimals == null) {
            decimals = TokenSymbolFallbackSupport.resolveDecimalsByContract(contract);
        }
        int scale = decimals == null ? 0 : Math.max(0, decimals);
        return new BigDecimal(value).movePointLeft(scale);
    }

    public String internalTransferFrom(Document transfer) {
        return normalizeAddress(coerceAddressValue(transfer == null ? null : transfer.get("from")));
    }

    public String internalTransferTo(Document transfer) {
        return normalizeAddress(coerceAddressValue(transfer == null ? null : transfer.get("to")));
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
        }
        if (networkId != NetworkId.SOLANA) {
            if (blockTimestamp() == null) {
                errors.add("Missing rawData.timeStamp");
            }
            if (transactionIndex() == null) {
                errors.add("Missing rawData.transactionIndex");
            }
        }
        return errors;
    }

    /**
     * EVM-only address normalization (ADR-066): lowercases and {@code 0x}-prefixes to the 20-byte
     * hex form, returning {@code null} for anything that is not a 42-char {@code 0x} address. This
     * deliberately rejects Solana base58 program IDs / mints — callers handling non-EVM families must
     * use {@link com.walletradar.application.normalization.pipeline.classification.registry.AddressNormalizer}
     * instead so case-sensitive identifiers survive.
     */
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
        return BsonCoercionSupport.asDocument(explorer.get("tx"));
    }

    private Document readExplorerSection() {
        return BsonCoercionSupport.asDocument(readRawField("explorer"));
    }

    private Document clarificationEvidenceDocument() {
        if (rawTransaction.getClarificationEvidence() != null) {
            return BsonCoercionSupport.asDocument(rawTransaction.getClarificationEvidence());
        }
        return BsonCoercionSupport.asDocument(readRawField("clarificationEvidence"));
    }

    private Document clarificationReceiptDocument() {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        return BsonCoercionSupport.asDocument(clarificationEvidence.get("receipt"));
    }

    private Document clarificationTransfersDocument() {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        return BsonCoercionSupport.asDocument(clarificationEvidence.get("transfers"));
    }

    private Document clarificationFullReceiptDocument() {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        return BsonCoercionSupport.asDocument(clarificationEvidence.get("fullReceipt"));
    }

    private Object clarificationEvidenceValue(String key) {
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null) {
            return null;
        }
        return clarificationEvidence.get(key);
    }

    private boolean shouldExposeReceiptClarificationEvidence() {
        Integer explicitAttempts = parseInteger(clarificationEvidenceValue("fullReceiptClarificationAttempts"));
        if (explicitAttempts != null && explicitAttempts > 0) {
            return true;
        }
        Document clarificationEvidence = clarificationEvidenceDocument();
        if (clarificationEvidence == null || clarificationEvidence.isEmpty()) {
            return false;
        }
        Document fullReceipt = clarificationFullReceiptDocument();
        if (fullReceipt != null && !fullReceipt.isEmpty()) {
            return true;
        }
        Document receipt = clarificationReceiptDocument();
        if (receipt != null && !receipt.isEmpty()) {
            return true;
        }
        Document transfers = clarificationTransfersDocument();
        return transfers != null && !transfers.isEmpty();
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
        return Collections.unmodifiableList(new ArrayList<>(BsonCoercionSupport.asDocumentList(parent.get(key))));
    }

    private static List<Document> mergeTokenTransferEvidence(
            List<Document> clarificationTransfers,
            List<Document> explorerTransfers
    ) {
        return mergeDocumentsByKey(
                clarificationTransfers,
                explorerTransfers,
                OnChainRawTransactionView::tokenTransferMergeKey
        );
    }

    private static List<Document> mergeInternalTransferEvidence(
            List<Document> clarificationTransfers,
            List<Document> explorerTransfers
    ) {
        return mergeDocumentsByKey(
                clarificationTransfers,
                explorerTransfers,
                OnChainRawTransactionView::internalTransferMergeKey
        );
    }

    private static List<Document> mergeDocumentsByKey(
            List<Document> primary,
            List<Document> secondary,
            java.util.function.Function<Document, String> keyBuilder
    ) {
        if (primary.isEmpty()) {
            return secondary;
        }
        if (secondary.isEmpty()) {
            return primary;
        }
        Map<String, Document> merged = new LinkedHashMap<>();
        appendMergedDocuments(merged, primary, keyBuilder);
        appendMergedDocuments(merged, secondary, keyBuilder);
        return Collections.unmodifiableList(new ArrayList<>(merged.values()));
    }

    private static void appendMergedDocuments(
            Map<String, Document> target,
            List<Document> source,
            java.util.function.Function<Document, String> keyBuilder
    ) {
        for (Document document : source) {
            if (document == null) {
                continue;
            }
            String key = keyBuilder.apply(document);
            target.putIfAbsent(key, document);
        }
    }

    private static String tokenTransferMergeKey(Document transfer) {
        return mergeKey(
                "token",
                normalizeAddress(stringify(transfer.get("contractAddress"))),
                normalizeAddress(stringify(transfer.get("tokenAddress"))),
                normalizeAddress(stringify(transfer.get("from"))),
                normalizeAddress(stringify(transfer.get("to"))),
                stringify(transfer.get("value")),
                stringify(transfer.get("tokenSymbol")),
                stringify(transfer.get("tokenDecimal"))
        );
    }

    private static String internalTransferMergeKey(Document transfer) {
        return mergeKey(
                "internal",
                normalizeAddress(stringify(transfer.get("from"))),
                normalizeAddress(stringify(transfer.get("to"))),
                stringify(transfer.get("value")),
                stringify(transfer.get("isError")),
                stringify(transfer.get("traceId")),
                stringify(transfer.get("type"))
        );
    }

    private static String mergeKey(String prefix, String... parts) {
        StringBuilder key = new StringBuilder(prefix);
        for (String part : parts) {
            key.append('|').append(part == null ? "" : part);
        }
        return key.toString();
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

    /**
     * Blockscout v2 encodes addresses as {@code {hash: "0x..."}} objects; legacy explorers use plain strings.
     */
    static String coerceAddressValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Document document) {
            String hash = stringify(document.get("hash"));
            if (hash != null) {
                return hash;
            }
            hash = stringify(document.get("address_hash"));
            if (hash != null) {
                return hash;
            }
        }
        return stringify(value);
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
            // Use coerceAddressValue so that BlockScout rich-object `to` fields
            // (e.g. {"hash":"0x…","name":"…","implementations":[…]}) are correctly
            // resolved to their hex address before the null-check — otherwise
            // stringify() returns null on Document values and falsely signals a
            // contract-creation transaction.
            return normalizeAddress(coerceAddressValue(explorerTx.get("to"))) == null;
        }
        Document rawData = rawTransaction.getRawData();
        return rawData != null
                && rawData.containsKey("to")
                && normalizeAddress(coerceAddressValue(rawData.get("to"))) == null;
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
