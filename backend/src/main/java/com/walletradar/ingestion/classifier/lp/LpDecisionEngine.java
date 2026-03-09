package com.walletradar.ingestion.classifier.lp;

import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.RawTransactionNormalizationView;
import com.walletradar.ingestion.classifier.TransferClassifier;
import org.bson.Document;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LpDecisionEngine {

    private static final Set<String> LP_ENTRY_FUNCTION_HINTS = Set.of("addliquidity", "joinpool", "mint", "increaseliquidity");
    private static final Set<String> LP_ENTRY_NO_MINT_FUNCTION_HINTS = Set.of("addliquidity", "joinpool", "increaseliquidity");
    private static final Set<String> LP_ENTRY_NO_MINT_SELECTOR_HINTS = Set.of("0xa3c7271a");
    private static final Set<String> LP_EXIT_FUNCTION_HINTS = Set.of("removeliquidity", "exitpool", "burn", "decreaseliquidity");
    private static final Set<String> LP_EXIT_NO_BURN_FUNCTION_HINTS = Set.of("removeliquidity", "exitpool", "decreaseliquidity", "zapout");
    private static final Set<String> LP_EXIT_NO_BURN_SELECTOR_HINTS = Set.of("0xc22159b6", "0x8b284b0e");
    private static final Set<String> LP_POSITION_ENTRY_SELECTOR_HINTS = Set.of("0x88316456", "0x219f5d17", "0x42842e0e");
    private static final Set<String> LP_POSITION_EXIT_SELECTOR_HINTS = Set.of("0x0c49ccbe", "0x42966c68", "0x00f714ce");
    private static final Set<String> LP_POSITION_ID_INPUT_SELECTORS = Set.of(
            "0x00f714ce", "0x18fccc76", "0x219f5d17", "0x0c49ccbe", "0xfc6f7865", "0x42842e0e"
    );
    private static final Set<String> LP_FEE_CLAIM_SELECTOR_HINTS = Set.of("0xfc6f7865", "0x18fccc76");
    private static final Set<String> LP_FEE_CLAIM_FUNCTION_HINTS = Set.of("collect", "harvest", "claim");

    private final LpProtocolRegistry lpProtocolRegistry;
    private final LpEvidenceExtractor lpEvidenceExtractor;

    public LpDecisionEngine(LpProtocolRegistry lpProtocolRegistry, LpEvidenceExtractor lpEvidenceExtractor) {
        this.lpProtocolRegistry = lpProtocolRegistry;
        this.lpEvidenceExtractor = lpEvidenceExtractor;
    }

    public boolean isLikelyLpEntryPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs)) {
            return false;
        }
        if (!isLpEntryContext(tx) && !lpProtocolRegistry.isKnownLpRouter(tx.readRawOrExplorerAddress("to"))) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        boolean hasOutflow = false;
        boolean hasMint = false;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            if (walletTopic.equalsIgnoreCase(topics.get(1))) {
                hasOutflow = true;
            } else if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))
                    && walletTopic.equalsIgnoreCase(topics.get(2))) {
                hasMint = true;
            }
        }
        return hasOutflow && hasMint;
    }

    public boolean isLikelyLpEntryWithoutMintPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs) || !isLpEntryWithoutMintContext(tx)) {
            return false;
        }
        LpEvidenceExtractor.FlowSummary summary = lpEvidenceExtractor.collectFlowSummary(tx, walletAddress, logs);
        if (!summary.mintToWallet().isEmpty()) {
            return false;
        }
        if (summary.outflows().size() < 2) {
            return false;
        }
        return summary.inflows().isEmpty() || summary.outflows().keySet().containsAll(summary.inflows().keySet());
    }

    public boolean isLikelyLpExitPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs)) {
            return false;
        }
        if (!isLpExitContext(tx) && !lpProtocolRegistry.isKnownLpRouter(tx.readRawOrExplorerAddress("to"))) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        boolean hasBurn = false;
        boolean hasInbound = false;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (walletTopic.equalsIgnoreCase(topics.get(1)) && LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                hasBurn = true;
            } else if (walletTopic.equalsIgnoreCase(topics.get(2)) && !LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))) {
                hasInbound = true;
            }
        }
        return hasBurn && hasInbound;
    }

    public boolean isLikelyLpExitWithoutBurnPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs)) {
            return false;
        }
        if (!isLpExitWithoutBurnContext(tx) || !lpProtocolRegistry.isKnownLpRouter(tx.readRawOrExplorerAddress("to"))) {
            return false;
        }
        LpEvidenceExtractor.FlowSummary summary = lpEvidenceExtractor.collectFlowSummary(tx, walletAddress, logs);
        if (!summary.burnFromWallet().isEmpty() || !summary.mintToWallet().isEmpty()) {
            return false;
        }
        if (!summary.outflows().isEmpty() && !isZapOutNoBurnContext(tx)) {
            return false;
        }
        return !summary.inflows().isEmpty();
    }

    public boolean isLikelyLpPositionPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs)) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String nftContract = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (!lpProtocolRegistry.isKnownPositionManager(nftContract)) {
                continue;
            }
            if (walletTopic.equalsIgnoreCase(topics.get(1)) || walletTopic.equalsIgnoreCase(topics.get(2))) {
                return true;
            }
        }
        return false;
    }

    public boolean isLikelyLpFeeClaimPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs)) {
            return false;
        }
        if (isLpPositionExitContext(tx) || isLpExitContext(tx)) {
            return false;
        }
        if (!isLpFeeClaimContext(tx)) {
            return false;
        }
        if (isGenericClaimFunctionContext(tx) && !hasLpFeeClaimEvidence(tx, logs)) {
            return false;
        }
        boolean hasInboundValue = lpEvidenceExtractor.hasWalletInboundErc20(tx, walletAddress, logs)
                || lpEvidenceExtractor.detectNativePayoutEvidence(tx, walletAddress, logs) != null;
        return hasInboundValue && !lpEvidenceExtractor.hasWalletOutboundErc20(tx, walletAddress, logs);
    }

    public boolean isLikelyLpExitFromPositionContext(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs) || !isLpPositionExitContext(tx)) {
            return false;
        }
        if (lpEvidenceExtractor.hasWalletInboundErc20(tx, walletAddress, logs)) {
            return true;
        }
        String managerAddress = tx.readRawOrExplorerAddress("to");
        String managerTopic = managerAddress != null ? tx.padAddressForTopic(managerAddress) : null;
        if (lpProtocolRegistry.isKnownPositionManager(managerAddress) && managerTopic != null) {
            String walletTopic = tx.padAddressForTopic(walletAddress);
            for (Document log : logs) {
                if (!lpEvidenceExtractor.isManagerInboundErc20Transfer(tx, log, managerTopic)) {
                    continue;
                }
                String fromTopic = tx.getLogTopics(log).get(1);
                if (!LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)
                        && !walletTopic.equalsIgnoreCase(fromTopic)) {
                    return true;
                }
            }
        }
        return lpEvidenceExtractor.detectNativePayoutEvidence(tx, walletAddress, logs) != null;
    }

    public boolean isLikelyLpEntryFromPositionContext(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (!isWalletSenderWithLogs(tx, walletAddress, logs)) {
            return false;
        }
        String txTo = tx.readRawOrExplorerAddress("to");
        boolean knownManager = lpProtocolRegistry.isKnownPositionManager(txTo);
        if (!isLpPositionEntryContext(tx) && !knownManager) {
            return false;
        }
        boolean hasOutboundPrincipal = lpEvidenceExtractor.hasWalletOutboundErc20(tx, walletAddress, logs)
                || lpEvidenceExtractor.positiveNetNativeValue(tx, walletAddress).signum() > 0;
        if (!hasOutboundPrincipal) {
            return false;
        }
        if (lpEvidenceExtractor.hasMintedPositionNftToWallet(tx, walletAddress, logs)) {
            return true;
        }
        return knownManager && lpEvidenceExtractor.hasEntryLifecycleEvidence(tx, logs);
    }

    public boolean isKnownLpSurfaceTarget(RawTransactionNormalizationView txView) {
        if (txView == null) {
            return false;
        }
        String txTo = txView.readRawOrExplorerAddress("to");
        if (lpProtocolRegistry.isKnownLpSurfaceTarget(txTo)) {
            return true;
        }
        for (Document log : txView.logs()) {
            String address = txView.normalizeAddressValue(txView.getLogAddress(log));
            if (lpProtocolRegistry.isKnownPositionManager(address) || lpProtocolRegistry.isKnownCustodyWrapper(address)) {
                return true;
            }
            List<String> topics = txView.getLogTopics(log);
            if (!topics.isEmpty() && lpProtocolRegistry.isKnownLpLifecycleTopic(topics.get(0))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLpFeeClaimEvidence(RawTransactionNormalizationView tx, List<Document> logs) {
        String txTo = tx.readRawOrExplorerAddress("to");
        if (lpProtocolRegistry.isKnownLpSurfaceTarget(txTo)) {
            return true;
        }
        if (lpEvidenceExtractor.resolvePositionId(tx, logs) != null) {
            return true;
        }
        if (lpEvidenceExtractor.hasLpPositionNftTransferLog(tx, logs)) {
            return true;
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String selector : LP_FEE_CLAIM_SELECTOR_HINTS) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        for (String selector : LP_POSITION_ID_INPUT_SELECTORS) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWalletSenderWithLogs(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String sender = tx.readRawOrExplorerAddress("from");
        return wallet != null && sender != null && wallet.equals(sender);
    }

    private static boolean isLpEntryContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName == null) {
            return false;
        }
        for (String hint : LP_ENTRY_FUNCTION_HINTS) {
            if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLpExitContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName == null) {
            return false;
        }
        for (String hint : LP_EXIT_FUNCTION_HINTS) {
            if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLpEntryWithoutMintContext(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null && LP_ENTRY_NO_MINT_SELECTOR_HINTS.stream().anyMatch(known -> known.equalsIgnoreCase(selector))) {
            return true;
        }
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            for (String hint : LP_ENTRY_NO_MINT_FUNCTION_HINTS) {
                if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return inputContainsAnySelector(tx.readRawOrExplorerLower("input"), LP_ENTRY_NO_MINT_SELECTOR_HINTS);
    }

    private static boolean isLpExitWithoutBurnContext(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null && LP_EXIT_NO_BURN_SELECTOR_HINTS.stream().anyMatch(known -> known.equalsIgnoreCase(selector))) {
            return true;
        }
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            for (String hint : LP_EXIT_NO_BURN_FUNCTION_HINTS) {
                if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return inputContainsAnySelector(tx.readRawOrExplorerLower("input"), LP_EXIT_NO_BURN_SELECTOR_HINTS);
    }

    private static boolean isZapOutNoBurnContext(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        return selector != null && selector.equalsIgnoreCase("0x8b284b0e");
    }

    private static boolean isLpPositionEntryContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            if (functionName.contains("mint") || functionName.contains("increaseliquidity") || functionName.contains("modifyliquidities")) {
                return true;
            }
            if (functionName.contains("transferfrom") || functionName.contains("safetransferfrom")) {
                return true;
            }
        }
        return inputContainsAnySelector(tx.readRawOrExplorerLower("input"), LP_POSITION_ENTRY_SELECTOR_HINTS);
    }

    private static boolean isLpPositionExitContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            if (functionName.contains("decreaseliquidity") || functionName.contains("withdraw") || functionName.contains("modifyliquidities")) {
                return true;
            }
            if (functionName.contains("burn") || functionName.contains("unstake")) {
                return true;
            }
        }
        return inputContainsAnySelector(tx.readRawOrExplorerLower("input"), LP_POSITION_EXIT_SELECTOR_HINTS);
    }

    private static boolean isLpFeeClaimContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            for (String hint : LP_FEE_CLAIM_FUNCTION_HINTS) {
                if (functionName.contains(hint)) {
                    return true;
                }
            }
        }
        return inputContainsAnySelector(tx.readRawOrExplorerLower("input"), LP_FEE_CLAIM_SELECTOR_HINTS);
    }

    private static boolean isGenericClaimFunctionContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        return functionName != null
                && functionName.contains("claim")
                && !functionName.contains("collect")
                && !functionName.contains("harvest");
    }

    private static boolean inputContainsAnySelector(String input, Set<String> selectors) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String selector : selectors) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inputContainsSelector(String input, String selector) {
        if (input == null || selector == null || selector.isBlank()) {
            return false;
        }
        String normalizedSelector = selector.toLowerCase(Locale.ROOT);
        if (input.contains(normalizedSelector)) {
            return true;
        }
        if (normalizedSelector.startsWith("0x")) {
            return input.contains(normalizedSelector.substring(2));
        }
        return input.contains("0x" + normalizedSelector);
    }

    private static boolean isFailedTx(RawTransactionNormalizationView tx) {
        String isError = tx.readRawOrExplorerLower("isError");
        if ("1".equals(isError)) {
            return true;
        }
        String receiptStatus = tx.readRawOrExplorerLower("txreceipt_status");
        if ("0".equals(receiptStatus) || "0x0".equals(receiptStatus)) {
            return true;
        }
        String status = tx.readRawOrExplorerLower("status");
        return "0x0".equals(status);
    }
}
