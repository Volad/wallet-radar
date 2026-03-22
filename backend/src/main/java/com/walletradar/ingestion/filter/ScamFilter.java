package com.walletradar.ingestion.filter;

import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import com.walletradar.ingestion.support.PromoSpamTextSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic score-based scam/spam filter used during raw ingestion.
 * Transactions with score >= dropThreshold are dropped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScamFilter {

    private static final String ERC20_APPROVE_METHOD_ID = "0x095ea7b3";
    private static final String MULTICALL_METHOD_ID = "0xac9650d8";
    private static final String ERC20_TRANSFER_SELECTOR = "a9059cbb";
    private static final Set<String> KNOWN_ZERO_VALUE_SPOOFING_FINGERPRINTS = Set.of(
            "ARBITRUM|0x0cf79e0a|0x27117f7e48e07f9e23042931ab39fe02a62ec587",
            "ARBITRUM|0xe94a5b23|0xc50005eb632e52e2d86096e5dae7b633609b348c",
            "AVALANCHE|0xa9059cbb|0xd743caa0ad523bbeba05c29b666d66e05f18094d",
            "AVALANCHE|0x12514bba|0x2915979685d52bc301d4a6204417f4f62887a1cd",
            "ETHEREUM|0xa9059cbb|0xc6fd8084fb9b6a0768cf943c341049edd1085b82"
    );
    private static final Set<String> KNOWN_SWAP_METHOD_IDS = Set.of(
            "0xe3ead59e"
    );
    private static final String ERC20_TRANSFER_TOPIC0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ERC20_APPROVAL_TOPIC0 =
            "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925";
    private static final Set<String> KNOWN_LEGIT_BRIDGE_OR_REWARD_SELECTORS = Set.of(
            "0xe2de2a03", // redeemWithFee
            "0x9fb67b58", // claimWithRecipient
            "0x71ee95c0", // Merkl / Angle claim
            "0xb7034f7e", // Compound claim
            "0xbe5013dc", // FLUID claim
            "0x5eac6239", // Pendle claim
            "0x8b681820", // BSC claim-by-proof
            "0x379607f5", // stream claim
            "0x2f52ebb7"  // merkle claim
    );
    private static final Set<BigInteger> SUSPICIOUS_TINY_AIRDROP_VALUES = Set.of(
            BigInteger.ONE,
            BigInteger.valueOf(5),
            BigInteger.TEN
    );
    private static final int WALLET_ZERO_VALUE_SPOOF_MIN_INPUT_LENGTH = 1024;

    private final ScamFilterProperties properties;

    /**
     * Returns true when traRnsaction should be dropped as spam/scam.
     */
    public boolean shouldDrop(RawTransaction tx) {
        if (!properties.isEnabled() || tx == null) {
            return false;
        }

        ScamEvaluation evaluation = evaluate(tx);
        if (evaluation.drop()) {
            log.warn("Scam filter: dropping tx {} on {} score={} signals={}",
                    tx.getTxHash(), tx.getNetworkId(), evaluation.score(), evaluation.signals());
        }
        return evaluation.drop();
    }

    private ScamEvaluation evaluate(RawTransaction tx) {
        int score = 0;
        List<String> signals = new ArrayList<>();

        Document raw = tx.getRawData();
        if (raw == null) {
            return new ScamEvaluation(score, List.of(), false);
        }

        if (isApproveTransaction(raw)) {
            signals.add("APPROVE_TX");
        }
        if (isFailedSwapWithoutTransferEffects(tx, raw)) {
            signals.add("FAILED_SWAP_NO_TRANSFER_EFFECT");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        if (isFailedZeroValueWithoutTransferEffects(tx, raw)) {
            signals.add("FAILED_ZERO_VALUE_NO_TRANSFER_EFFECT");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }

        Set<String> blocklist = properties.getBlocklistNormalized();
        if (!blocklist.isEmpty()) {
            Set<String> addresses = extractAddresses(tx);
            for (String addr : addresses) {
                if (addr != null && !addr.isBlank() && blocklist.contains(addr.toLowerCase())) {
                    score += properties.getBlocklistScore();
                    signals.add("BLOCKLIST_MATCH");
                    break;
                }
            }
        }

        if (isKnownLegitimateBridgeOrRewardRoute(raw)) {
            signals.add("KNOWN_LEGIT_BRIDGE_OR_REWARD_ROUTE");
            return new ScamEvaluation(score, List.copyOf(signals), false);
        }

        String wallet = normalize(rawString(tx.getWalletAddress()));
        String txSender = normalize(readRawOrExplorerTx(raw, "from"));
        String txTo = normalize(readRawOrExplorerTx(raw, "to"));
        if (isLikelyExplorerTokenSpoofing(raw, wallet, txSender, txTo)) {
            signals.add("TOKEN_SPOOFING_PATTERN");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        if (isLikelyKnownZeroValueSpoofingPattern(tx.getNetworkId(), raw, wallet, txSender, txTo)) {
            signals.add("KNOWN_ZERO_VALUE_SPOOFING_PATTERN");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        if (isLikelyKnownInboundSpamPattern(tx.getNetworkId(), raw, wallet, txSender)) {
            signals.add("KNOWN_INBOUND_SPAM_FINGERPRINT");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        if (isLikelyPromotionalInboundSpamPattern(raw, wallet, txSender)) {
            signals.add("PROMOTIONAL_INBOUND_SPAM_PATTERN");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        if (isLikelyOutboundZeroValueSpoofing(raw, wallet, txSender)) {
            signals.add("OUTBOUND_ZERO_VALUE_SPOOF");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        if (isLikelyWalletInitiatedZeroValueSpoofing(raw, wallet, txSender)) {
            signals.add("WALLET_INITIATED_ZERO_VALUE_SPOOF");
            return new ScamEvaluation(properties.getDropThreshold(), List.copyOf(signals), true);
        }
        SuspiciousAirdropMetadataSignal suspiciousAirdropMetadata =
                evaluateSuspiciousAirdropMetadata(raw, wallet, txSender);
        if (suspiciousAirdropMetadata.score() > 0) {
            score += suspiciousAirdropMetadata.score();
            signals.addAll(suspiciousAirdropMetadata.signals());
        }
        if (isLikelyUnsolicitedMulticallAirdrop(raw, wallet, txSender)) {
            score += properties.getSuspiciousMulticallAirdropScore();
            signals.add("UNSOLICITED_MULTICALL_AIRDROP");
        }

        if (!(raw.get("logs") instanceof List<?> logs)) {
            return finalizeEvaluation(score, signals);
        }

        TransferStats stats = collectTransferStats(logs, wallet);

        boolean walletInitiated = wallet != null && wallet.equals(txSender);
        boolean unsolicitedInboundOnly = wallet != null
                && txSender != null
                && !wallet.equals(txSender)
                && stats.transferToWalletCount() >= 1
                && stats.transferFromWalletCount() == 0;

        if (unsolicitedInboundOnly) {
            score += properties.getUnsolicitedInboundScore();
            signals.add("UNSOLICITED_INBOUND_ONLY");
        }

        if (isLikelyRelaySweepSpam(stats)) {
            score += properties.getRelaySweepScore();
            signals.add("RELAY_SWEEP_SPAM");
        }

        if (isLikelyInboundFanoutSpam(stats)) {
            score += properties.getInboundFanoutScore();
            signals.add("INBOUND_FANOUT_SPAM");
        }

        if (isLikelyZeroAmountPoisoning(stats)) {
            score += properties.getZeroAmountPoisoningScore();
            signals.add("ZERO_AMOUNT_POISONING");
        }

        if (isLikelySpamAirdropPattern(stats, txSender, txTo, wallet)) {
            score += properties.getMassAirdropScore();
            signals.add("MASS_AIRDROP_PATTERN");
        }

        if (walletInitiated) {
            score -= properties.getWalletInitiatedCredit();
            signals.add("WALLET_INITIATED");
            if (stats.approvalCount() > 0) {
                score -= properties.getWalletApprovalCredit();
                signals.add("WALLET_APPROVAL_PATTERN");
            }
        }

        if (stats.transferFromWalletCount() > 0 && stats.transferToWalletCount() > 0) {
            score -= properties.getMixedWalletFlowCredit();
            signals.add("MIXED_WALLET_FLOW");
        }

        return finalizeEvaluation(score, signals);
    }

    private ScamEvaluation finalizeEvaluation(int score, List<String> signals) {
        boolean drop = score >= properties.getDropThreshold();
        return new ScamEvaluation(score, List.copyOf(signals), drop);
    }

    private boolean isLikelyRelaySweepSpam(TransferStats stats) {
        return stats.transferCount() >= properties.getMassRelaySpamMinTransferLogs()
                && stats.transferToWalletCount() == 0
                && stats.transferFromWalletCount() >= 1;
    }

    private boolean isLikelyInboundFanoutSpam(TransferStats stats) {
        return stats.transferCount() >= properties.getMassRelaySpamMinTransferLogs()
                && stats.transferToWalletCount() >= 1
                && stats.transferFromWalletCount() == 0
                && stats.uniqueRecipients() >= properties.getMassInboundFanoutMinRecipients();
    }

    private boolean isLikelyZeroAmountPoisoning(TransferStats stats) {
        return stats.walletInvolved()
                && stats.transferCount() >= properties.getZeroAmountPoisoningMinTransferLogs()
                && stats.zeroAmountTransferCount() >= properties.getZeroAmountPoisoningMinZeroTransfers();
    }

    private boolean isLikelySpamAirdropPattern(TransferStats stats, String txSender, String txTo, String wallet) {
        if (wallet == null || txSender == null || txTo == null || wallet.equals(txSender)) {
            return false;
        }
        if (stats.transferCount() < properties.getMassAirdropMinTransferLogs()) {
            return false;
        }
        if (stats.transferToWalletCount() == 0) {
            return false;
        }
        return stats.tokenContracts().size() == 1
                && stats.tokenContracts().contains(txTo)
                && stats.transferSenders().size() == 1
                && !stats.transferSenders().contains(txSender)
                && stats.transferValues().size() == 1
                && stats.uniqueRecipients() >= properties.getMassAirdropMinTransferLogs();
    }

    private SuspiciousAirdropMetadataSignal evaluateSuspiciousAirdropMetadata(
            Document raw, String wallet, String txSender
    ) {
        if (raw == null || wallet == null || txSender == null || wallet.equals(txSender)) {
            return SuspiciousAirdropMetadataSignal.empty();
        }

        List<Document> tokenTransfers = collectTokenTransfers(raw);
        if (tokenTransfers.isEmpty()) {
            return SuspiciousAirdropMetadataSignal.empty();
        }

        boolean inboundToWallet = false;
        boolean suspiciousTokenText = false;
        boolean cleanInboundTokenText = false;
        boolean suspiciousTinyValue = false;
        boolean suspiciousZeroDecimalsWithOddText = false;

        for (Document transfer : tokenTransfers) {
            String to = normalize(rawString(transfer.get("to")));
            if (!wallet.equals(to)) {
                continue;
            }
            String from = normalize(rawString(transfer.get("from")));
            if (wallet.equals(from)) {
                continue;
            }
            inboundToWallet = true;

            String symbol = rawString(transfer.get("tokenSymbol"));
            String name = rawString(transfer.get("tokenName"));
            if (isSuspiciousTokenText(symbol) || isSuspiciousTokenText(name)) {
                suspiciousTokenText = true;
            } else {
                cleanInboundTokenText = true;
            }

            BigInteger value = parseUnsignedNumeric(rawString(transfer.get("value")));
            if (value != null && SUSPICIOUS_TINY_AIRDROP_VALUES.contains(value)) {
                suspiciousTinyValue = true;
            }

            Integer decimals = parseInteger(rawString(transfer.get("tokenDecimal")));
            if (decimals != null
                    && decimals == 0
                    && (hasSuspiciousDisplayChars(symbol) || hasSuspiciousDisplayChars(name))) {
                suspiciousZeroDecimalsWithOddText = true;
            }
        }

        if (!inboundToWallet) {
            return SuspiciousAirdropMetadataSignal.empty();
        }

        int score = 0;
        List<String> signals = new ArrayList<>();
        if (suspiciousTokenText && !cleanInboundTokenText) {
            score += properties.getSuspiciousAirdropTokenTextScore();
            signals.add("SUSPICIOUS_AIRDROP_TOKEN_TEXT");
        }
        if (suspiciousTinyValue) {
            score += properties.getSuspiciousAirdropTinyValueScore();
            signals.add("SUSPICIOUS_AIRDROP_TINY_VALUE");
        }
        if (suspiciousZeroDecimalsWithOddText) {
            score += properties.getSuspiciousAirdropZeroDecimalsScore();
            signals.add("SUSPICIOUS_AIRDROP_ZERO_DECIMALS");
        }
        String functionName = normalize(readRawOrExplorerTx(raw, "functionName"));
        if (functionName != null && functionName.contains("airdrop")) {
            score += properties.getSuspiciousAirdropFunctionScore();
            signals.add("SUSPICIOUS_AIRDROP_FUNCTION");
        }
        return new SuspiciousAirdropMetadataSignal(score, List.copyOf(signals));
    }

    private static List<Document> collectTokenTransfers(Document raw) {
        List<Document> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Document explorer = toDocument(raw.get("explorer"));
        if (explorer != null) {
            Object tokenTransfersObj = explorer.get("tokenTransfers");
            if (tokenTransfersObj instanceof List<?> tokenTransfers) {
                for (Object tokenTransfer : tokenTransfers) {
                    Document transfer = toDocument(tokenTransfer);
                    if (transfer != null) {
                        out.add(transfer);
                    }
                }
            }
        }

        if (raw.get("contractAddress") != null
                && (raw.get("tokenSymbol") != null || raw.get("tokenName") != null || raw.get("tokenDecimal") != null)) {
            out.add(new Document()
                    .append("from", raw.get("from"))
                    .append("to", raw.get("to"))
                    .append("contractAddress", raw.get("contractAddress"))
                    .append("value", raw.get("value"))
                    .append("tokenSymbol", raw.get("tokenSymbol"))
                    .append("tokenName", raw.get("tokenName"))
                    .append("tokenDecimal", raw.get("tokenDecimal")));
        }
        return out;
    }

    private Set<String> extractAddresses(RawTransaction tx) {
        Set<String> out = new HashSet<>();
        if (tx.getRawData() == null) return out;

        Document raw = tx.getRawData();

        if (raw.containsKey("logs") && raw.get("logs") instanceof List<?> logs) {
            for (Object o : logs) {
                Document log = toDocument(o);
                if (log == null) {
                    continue;
                }
                Object addr = log.get("address");
                if (addr != null) addNormalized(out, addr.toString());
            }
        }
        Object to = raw.get("to");
        if (to != null) addNormalized(out, to.toString());
        Object from = raw.get("from");
        if (from != null) addNormalized(out, from.toString());

        return out;
    }

    private static void addNormalized(Set<String> set, String addr) {
        if (addr == null || addr.isBlank()) return;
        set.add(addr.strip().toLowerCase());
    }

    private static String topicToAddress(Object topicObj) {
        String topic = normalize(rawString(topicObj));
        if (topic == null) {
            return null;
        }
        String hex = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (hex.length() < 40) {
            return null;
        }
        return "0x" + hex.substring(hex.length() - 40);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase();
    }

    private static String rawString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isApproveTransaction(Document raw) {
        String methodId = normalize(readRawOrExplorerTx(raw, "methodId"));
        if (ERC20_APPROVE_METHOD_ID.equals(methodId)) {
            return true;
        }

        String input = normalize(readRawOrExplorerTx(raw, "input"));
        if (input != null && input.startsWith(ERC20_APPROVE_METHOD_ID)) {
            return true;
        }

        String functionName = normalize(readRawOrExplorerTx(raw, "functionName"));
        return functionName != null && functionName.startsWith("approve(");
    }

    private static boolean isKnownLegitimateBridgeOrRewardRoute(Document raw) {
        String methodId = normalizeMethodId(readRawOrExplorerTx(raw, "methodId"));
        String functionName = normalize(readRawOrExplorerTx(raw, "functionName"));
        if (methodId != null && KNOWN_LEGIT_BRIDGE_OR_REWARD_SELECTORS.contains(methodId)) {
            return true;
        }
        return functionName != null
                && (functionName.contains("redeemwithfee")
                || functionName.contains("claimwithrecipient")
                || functionName.startsWith("claim(")
                || functionName.contains("claimrewards"));
    }

    private static boolean isFailedSwapWithoutTransferEffects(RawTransaction tx, Document raw) {
        if (tx == null || raw == null) {
            return false;
        }
        String methodId = normalize(readRawOrExplorerTx(raw, "methodId"));
        String functionName = normalize(readRawOrExplorerTx(raw, "functionName"));
        boolean isSwapCall = (methodId != null && KNOWN_SWAP_METHOD_IDS.contains(methodId))
                || (functionName != null && functionName.contains("swap"));
        if (!isSwapCall) {
            return false;
        }
        if (!isFailedTx(raw)) {
            return false;
        }

        String wallet = normalize(rawString(tx.getWalletAddress()));
        String txSender = normalize(readRawOrExplorerTx(raw, "from"));
        if (wallet != null && txSender != null && !wallet.equals(txSender)) {
            return false;
        }

        return !hasTransferEffects(raw);
    }

    private static boolean isFailedZeroValueWithoutTransferEffects(RawTransaction tx, Document raw) {
        if (tx == null || raw == null) {
            return false;
        }
        if (!isFailedTx(raw)) {
            return false;
        }
        BigInteger value = parseUnsignedNumeric(readRawOrExplorerTx(raw, "value"));
        if (value == null || value.signum() != 0) {
            return false;
        }
        if (hasTransferEffects(raw)) {
            return false;
        }

        String wallet = normalize(rawString(tx.getWalletAddress()));
        String txSender = normalize(readRawOrExplorerTx(raw, "from"));
        return wallet == null || txSender == null || wallet.equals(txSender);
    }

    private static boolean isFailedTx(Document raw) {
        String isError = normalize(readRawOrExplorerTx(raw, "isError"));
        if ("1".equals(isError)) {
            return true;
        }
        String receiptStatus = normalize(readRawOrExplorerTx(raw, "txreceipt_status"));
        if ("0".equals(receiptStatus) || "0x0".equals(receiptStatus)) {
            return true;
        }
        String status = normalize(readRawOrExplorerTx(raw, "status"));
        return "0x0".equals(status);
    }

    private static boolean hasTransferEffects(Document raw) {
        Document explorer = toDocument(raw.get("explorer"));
        if (explorer == null) {
            return false;
        }
        Object tokenTransfers = explorer.get("tokenTransfers");
        if (tokenTransfers instanceof List<?> transfers && !transfers.isEmpty()) {
            return true;
        }
        Object internalTransfers = explorer.get("internalTransfers");
        return internalTransfers instanceof List<?> transfers && !transfers.isEmpty();
    }

    private static String readRawOrExplorerTx(Document raw, String field) {
        if (raw == null || field == null || field.isBlank()) {
            return null;
        }
        Object direct = raw.get(field);
        if (direct != null && !direct.toString().isBlank()) {
            String directValue = direct.toString();
            if (!"methodId".equals(field) || !isBlankSelector(directValue)) {
                return directValue;
            }
        }
        Document explorer = toDocument(raw.get("explorer"));
        if (explorer != null) {
            Document tx = toDocument(explorer.get("tx"));
            if (tx != null) {
                Object nested = tx.get(field);
                if (nested != null && !nested.toString().isBlank()) {
                    String nestedValue = nested.toString();
                    if (!"methodId".equals(field) || !isBlankSelector(nestedValue)) {
                        return nestedValue;
                    }
                }
            }
        }
        if ("methodId".equals(field)) {
            return deriveMethodIdFromInput(raw, explorer);
        }
        return null;
    }

    private static boolean isBlankSelector(String value) {
        String normalized = normalize(value);
        return normalized == null || "0x".equals(normalized);
    }

    private static String deriveMethodIdFromInput(Document raw, Document explorer) {
        String input = normalize(readInput(raw, explorer));
        if (input == null || !input.startsWith("0x") || input.length() < 10) {
            return null;
        }
        return input.substring(0, 10);
    }

    private static String readInput(Document raw, Document explorer) {
        Object direct = raw.get("input");
        if (direct != null && !direct.toString().isBlank()) {
            return direct.toString();
        }
        if (explorer == null) {
            return null;
        }
        Document tx = toDocument(explorer.get("tx"));
        if (tx == null) {
            return null;
        }
        Object nested = tx.get("input");
        return nested == null ? null : nested.toString();
    }

    private static boolean isLikelyExplorerTokenSpoofing(
            Document raw, String wallet, String txSender, String txTo
    ) {
        if (raw == null || wallet == null) {
            return false;
        }
        Document explorer = toDocument(raw.get("explorer"));
        if (explorer == null) {
            return false;
        }
        Object tokenTransfersObj = explorer.get("tokenTransfers");
        if (!(tokenTransfersObj instanceof List<?> tokenTransfers) || tokenTransfers.isEmpty()) {
            return false;
        }

        int walletOutboundCount = 0;
        Set<String> recipients = new HashSet<>();
        Set<String> tokenContracts = new HashSet<>();
        boolean hasZeroValueTransfer = false;
        boolean hasPositiveValueTransfer = false;
        boolean hasSuspiciousTokenText = false;

        for (Object transferObj : tokenTransfers) {
            Document transfer = toDocument(transferObj);
            if (transfer == null) {
                continue;
            }
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            if (wallet.equals(from)) {
                walletOutboundCount++;
                if (to != null) {
                    recipients.add(to);
                }
            }
            String tokenContract = normalize(rawString(transfer.get("contractAddress")));
            if (tokenContract != null) {
                tokenContracts.add(tokenContract);
            }
            BigInteger value = parseUnsignedNumeric(rawString(transfer.get("value")));
            if (value != null) {
                if (value.signum() == 0) {
                    hasZeroValueTransfer = true;
                } else {
                    hasPositiveValueTransfer = true;
                }
            }
            if (containsNonAscii(rawString(transfer.get("tokenSymbol")))
                    || containsNonAscii(rawString(transfer.get("tokenName")))) {
                hasSuspiciousTokenText = true;
            }
        }

        boolean singleRecipient = recipients.size() == 1 && (txTo == null || recipients.contains(txTo));
        return walletOutboundCount >= 2
                && singleRecipient
                && tokenContracts.size() >= 2
                && hasZeroValueTransfer
                && hasPositiveValueTransfer
                && hasSuspiciousTokenText;
    }

    private static boolean isLikelyOutboundZeroValueSpoofing(
            Document raw, String wallet, String txSender
    ) {
        if (raw == null || wallet == null || txSender == null || wallet.equals(txSender)) {
            return false;
        }
        BigInteger txValue = parseUnsignedNumeric(readRawOrExplorerTx(raw, "value"));
        if (txValue != null && txValue.signum() > 0) {
            return false;
        }

        List<Document> tokenTransfers = collectTokenTransfers(raw);
        if (tokenTransfers.isEmpty()) {
            return false;
        }

        boolean hasOutboundZeroFromWallet = false;
        boolean hasOutboundPositiveFromWallet = false;
        boolean hasInboundToWallet = false;
        for (Document transfer : tokenTransfers) {
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            BigInteger value = parseUnsignedNumeric(rawString(transfer.get("value")));
            if (wallet.equals(from) && !wallet.equals(to)) {
                if (value != null && value.signum() == 0) {
                    hasOutboundZeroFromWallet = true;
                } else if (value != null && value.signum() > 0) {
                    hasOutboundPositiveFromWallet = true;
                }
            }
            if (!wallet.equals(from) && wallet.equals(to)) {
                hasInboundToWallet = true;
            }
        }
        return hasOutboundZeroFromWallet
                && !hasOutboundPositiveFromWallet
                && !hasInboundToWallet;
    }

    private static boolean isLikelyKnownZeroValueSpoofingPattern(
            String networkId, Document raw, String wallet, String txSender, String txTo
    ) {
        if (networkId == null || raw == null || wallet == null || txSender == null || txTo == null || wallet.equals(txSender)) {
            return false;
        }

        String methodId = normalizeMethodId(readRawOrExplorerTx(raw, "methodId"));
        if (methodId == null) {
            return false;
        }

        String fingerprint = networkId.strip().toUpperCase() + "|" + methodId + "|" + txTo;
        if (!KNOWN_ZERO_VALUE_SPOOFING_FINGERPRINTS.contains(fingerprint)) {
            return false;
        }

        BigInteger txValue = parseUnsignedNumeric(readRawOrExplorerTx(raw, "value"));
        if (txValue == null || txValue.signum() != 0) {
            return false;
        }

        List<Document> tokenTransfers = collectTokenTransfers(raw);
        if (tokenTransfers.isEmpty()) {
            return false;
        }

        boolean hasOutboundFromWallet = false;
        for (Document transfer : tokenTransfers) {
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            BigInteger value = parseUnsignedNumeric(rawString(transfer.get("value")));

            // Keep this rule strict: only all-zero spoofing payloads.
            if (value == null || value.signum() != 0) {
                return false;
            }
            if (wallet.equals(from) && !wallet.equals(to)) {
                hasOutboundFromWallet = true;
            }
            if (wallet.equals(to) && !wallet.equals(from)) {
                return false;
            }
        }
        return hasOutboundFromWallet;
    }

    private boolean isLikelyKnownInboundSpamPattern(
            String networkId, Document raw, String wallet, String txSender
    ) {
        if (networkId == null || raw == null || wallet == null || txSender == null || wallet.equals(txSender)) {
            return false;
        }

        Set<String> knownFingerprints = properties.getKnownInboundSpamFingerprintKeysNormalized();
        if (knownFingerprints.isEmpty()) {
            return false;
        }

        String methodId = normalizeMethodId(readRawOrExplorerTx(raw, "methodId"));
        if (methodId == null) {
            return false;
        }

        List<Document> tokenTransfers = collectTokenTransfers(raw);
        if (tokenTransfers.isEmpty()) {
            return false;
        }

        String networkKey = networkId.strip().toUpperCase();
        boolean hasMatchedInbound = false;
        for (Document transfer : tokenTransfers) {
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            String tokenContract = normalize(rawString(transfer.get("contractAddress")));

            if (wallet.equals(from)) {
                return false;
            }
            if (!wallet.equals(to)) {
                continue;
            }
            if (tokenContract == null) {
                return false;
            }

            String fingerprint = networkKey + "|" + tokenContract + "|" + methodId;
            if (!knownFingerprints.contains(fingerprint)) {
                return false;
            }
            hasMatchedInbound = true;
        }
        return hasMatchedInbound;
    }

    private boolean isLikelyPromotionalInboundSpamPattern(
            Document raw, String wallet, String txSender
    ) {
        if (raw == null || wallet == null || txSender == null || wallet.equals(txSender)) {
            return false;
        }

        BigInteger txValue = parseUnsignedNumeric(readRawOrExplorerTx(raw, "value"));
        if (txValue != null && txValue.signum() > 0) {
            return false;
        }
        if (hasWalletInternalTransferEffect(raw, wallet)) {
            return false;
        }

        List<Document> tokenTransfers = collectTokenTransfers(raw);
        if (tokenTransfers.isEmpty()) {
            return false;
        }

        boolean hasInboundToWallet = false;
        boolean hasPromotionalText = false;
        for (Document transfer : tokenTransfers) {
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            if (wallet.equals(from) && !wallet.equals(to)) {
                return false;
            }
            if (!wallet.equals(to) || wallet.equals(from)) {
                continue;
            }
            hasInboundToWallet = true;
            String symbol = rawString(transfer.get("tokenSymbol"));
            String name = rawString(transfer.get("tokenName"));
            if (!isPromotionalSpamText(symbol) && !isPromotionalSpamText(name)) {
                return false;
            }
            hasPromotionalText = true;
        }
        return hasInboundToWallet && hasPromotionalText;
    }

    private static boolean hasWalletInternalTransferEffect(Document raw, String wallet) {
        if (raw == null || wallet == null) {
            return false;
        }
        Document explorer = toDocument(raw.get("explorer"));
        if (explorer == null) {
            return false;
        }
        Object internalTransfersObj = explorer.get("internalTransfers");
        if (!(internalTransfersObj instanceof List<?> internalTransfers) || internalTransfers.isEmpty()) {
            return false;
        }
        for (Object transferObj : internalTransfers) {
            Document transfer = toDocument(transferObj);
            if (transfer == null) {
                continue;
            }
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            BigInteger value = parseUnsignedNumeric(rawString(transfer.get("value")));
            if (value == null || value.signum() <= 0) {
                continue;
            }
            if ((wallet.equals(from) && !wallet.equals(to)) || (wallet.equals(to) && !wallet.equals(from))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyWalletInitiatedZeroValueSpoofing(
            Document raw, String wallet, String txSender
    ) {
        if (raw == null || wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }

        BigInteger txValue = parseUnsignedNumeric(readRawOrExplorerTx(raw, "value"));
        if (txValue != null && txValue.signum() > 0) {
            return false;
        }

        List<Document> tokenTransfers = collectTokenTransfers(raw);
        if (tokenTransfers.isEmpty()) {
            return false;
        }

        boolean hasOutboundZeroFromWallet = false;
        for (Document transfer : tokenTransfers) {
            String from = normalize(rawString(transfer.get("from")));
            String to = normalize(rawString(transfer.get("to")));
            BigInteger value = parseUnsignedNumeric(rawString(transfer.get("value")));

            if (!wallet.equals(from)) {
                return false;
            }
            if (wallet.equals(to)) {
                return false;
            }
            if (value != null && value.signum() > 0) {
                return false;
            }
            hasOutboundZeroFromWallet = true;
        }

        if (!hasOutboundZeroFromWallet) {
            return false;
        }

        String input = normalize(readRawOrExplorerTx(raw, "input"));
        if (input == null || input.length() < WALLET_ZERO_VALUE_SPOOF_MIN_INPUT_LENGTH) {
            return false;
        }

        String methodId = normalize(readRawOrExplorerTx(raw, "methodId"));
        String functionName = normalize(readRawOrExplorerTx(raw, "functionName"));
        return "12514bba".equals(methodId)
                || "0x12514bba".equals(methodId)
                || (functionName != null && functionName.contains("transfer("));
    }

    private static boolean containsNonAscii(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMethodId(String methodId) {
        String normalized = normalize(methodId);
        if (normalized == null) {
            return null;
        }
        return normalized.startsWith("0x") ? normalized : "0x" + normalized;
    }

    private static boolean isSuspiciousTokenText(String value) {
        return PromoSpamTextSupport.isSuspiciousTokenText(value);
    }

    private static boolean isPromotionalSpamText(String value) {
        return PromoSpamTextSupport.isPromotionalSpamText(value);
    }

    private boolean isLikelyUnsolicitedMulticallAirdrop(Document raw, String wallet, String txSender) {
        if (raw == null || wallet == null || txSender == null || wallet.equals(txSender)) {
            return false;
        }
        if (!hasInboundTokenTransferToWallet(raw, wallet)) {
            return false;
        }
        String methodId = normalize(readRawOrExplorerTx(raw, "methodId"));
        String functionName = normalize(readRawOrExplorerTx(raw, "functionName"));
        boolean multicall = matchesMethodId(methodId, MULTICALL_METHOD_ID)
                || (functionName != null && functionName.contains("multicall"));
        if (!multicall) {
            return false;
        }
        String input = normalize(readRawOrExplorerTx(raw, "input"));
        if (input == null || input.length() < 16) {
            return false;
        }
        int transferSelectorCount = countOccurrences(input, ERC20_TRANSFER_SELECTOR);
        return transferSelectorCount >= properties.getSuspiciousMulticallAirdropMinTransferCalls();
    }

    private static boolean hasInboundTokenTransferToWallet(Document raw, String wallet) {
        for (Document transfer : collectTokenTransfers(raw)) {
            String to = normalize(rawString(transfer.get("to")));
            if (!wallet.equals(to)) {
                continue;
            }
            String from = normalize(rawString(transfer.get("from")));
            if (!wallet.equals(from)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesMethodId(String actual, String expectedWithPrefix) {
        if (actual == null || expectedWithPrefix == null) {
            return false;
        }
        String expected = normalize(expectedWithPrefix);
        if (expected.equals(actual)) {
            return true;
        }
        if (expected.startsWith("0x")) {
            return expected.substring(2).equals(actual);
        }
        return ("0x" + expected).equals(actual);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while (true) {
            idx = haystack.indexOf(needle, idx);
            if (idx < 0) {
                break;
            }
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static boolean hasSuspiciousDisplayChars(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Integer.parseInt(value.substring(2), 16);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigInteger parseUnsignedNumeric(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                BigInteger parsed = new BigInteger(value.substring(2), 16);
                return parsed.signum() >= 0 ? parsed : null;
            }
            BigInteger parsed = new BigInteger(value);
            return parsed.signum() >= 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Document toDocument(Object value) {
        if (value instanceof Document d) {
            return d;
        }
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return out;
        }
        return null;
    }

    private static TransferStats collectTransferStats(List<?> logs, String wallet) {
        int transferCount = 0;
        int zeroAmountTransferCount = 0;
        int transferFromWalletCount = 0;
        int transferToWalletCount = 0;
        int approvalCount = 0;
        Set<String> recipients = new HashSet<>();
        Set<String> senders = new HashSet<>();
        Set<String> transferValues = new HashSet<>();
        Set<String> tokenContracts = new HashSet<>();

        for (Object o : logs) {
            Document log = toDocument(o);
            if (log == null) {
                continue;
            }
            Object topicsObj = log.get("topics");
            if (!(topicsObj instanceof List<?> topics) || topics.isEmpty()) {
                continue;
            }
            String topic0 = normalize(rawString(topics.get(0)));
            if (ERC20_APPROVAL_TOPIC0.equals(topic0)) {
                approvalCount++;
            }
            if (!ERC20_TRANSFER_TOPIC0.equals(topic0) || topics.size() < 3) {
                continue;
            }
            String transferFrom = topicToAddress(topics.get(1));
            String transferTo = topicToAddress(topics.get(2));
            if (transferFrom == null || transferTo == null) {
                continue;
            }
            String token = normalize(rawString(log.get("address")));
            if (token == null) {
                continue;
            }
            transferCount++;
            recipients.add(transferTo);
            senders.add(transferFrom);
            tokenContracts.add(token);
            String value = normalize(rawString(log.get("data")));
            if (value != null) {
                transferValues.add(value);
            }
            if (wallet != null && wallet.equals(transferFrom)) {
                transferFromWalletCount++;
            }
            if (wallet != null && wallet.equals(transferTo)) {
                transferToWalletCount++;
            }
            if (isZeroAmount(rawString(log.get("data")))) {
                zeroAmountTransferCount++;
            }
        }

        return new TransferStats(
                transferCount,
                zeroAmountTransferCount,
                transferFromWalletCount,
                transferToWalletCount,
                recipients.size(),
                Set.copyOf(senders),
                Set.copyOf(transferValues),
                Set.copyOf(tokenContracts),
                approvalCount
        );
    }

    private static boolean isZeroAmount(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return false;
        }
        if ("0x".equals(normalized)) {
            return true;
        }
        String hex = normalized.startsWith("0x") ? normalized.substring(2) : normalized;
        if (hex.isBlank()) {
            return true;
        }
        try {
            return BigInteger.ZERO.equals(new BigInteger(hex, 16));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private record ScamEvaluation(int score, List<String> signals, boolean drop) {
    }

    private record SuspiciousAirdropMetadataSignal(int score, List<String> signals) {
        private static SuspiciousAirdropMetadataSignal empty() {
            return new SuspiciousAirdropMetadataSignal(0, List.of());
        }
    }

    private record TransferStats(
            int transferCount,
            int zeroAmountTransferCount,
            int transferFromWalletCount,
            int transferToWalletCount,
            int uniqueRecipients,
            Set<String> transferSenders,
            Set<String> transferValues,
            Set<String> tokenContracts,
            int approvalCount
    ) {
        private boolean walletInvolved() {
            return transferFromWalletCount > 0 || transferToWalletCount > 0;
        }
    }
}
