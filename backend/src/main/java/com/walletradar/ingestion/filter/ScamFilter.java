package com.walletradar.ingestion.filter;

import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic score-based scam/spam filter used during raw ingestion.
 * Transactions with score >= dropThreshold are dropped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScamFilter {

    private static final String ERC20_TRANSFER_TOPIC0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ERC20_APPROVAL_TOPIC0 =
            "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925";

    private final ScamFilterProperties properties;

    /**
     * Returns true when transaction should be dropped as spam/scam.
     */
    public boolean shouldDrop(RawTransaction tx) {
        if (!properties.isEnabled() || tx == null) {
            return false;
        }

        ScamEvaluation evaluation = evaluate(tx);
        if (evaluation.drop()) {
            log.debug("Scam filter: dropping tx {} on {} score={} signals={}",
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

        if (!(raw.get("logs") instanceof List<?> logs)) {
            return finalizeEvaluation(score, signals);
        }

        String wallet = normalize(rawString(tx.getWalletAddress()));
        String txSender = normalize(rawString(raw.get("from")));
        String txTo = normalize(rawString(raw.get("to")));
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

    private Set<String> extractAddresses(RawTransaction tx) {
        Set<String> out = new HashSet<>();
        if (tx.getRawData() == null) return out;

        Document raw = tx.getRawData();

        if (raw.containsKey("logs") && raw.get("logs") instanceof List<?> logs) {
            for (Object o : logs) {
                if (o instanceof Document log) {
                    Object addr = log.get("address");
                    if (addr != null) addNormalized(out, addr.toString());
                }
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
            if (!(o instanceof Document log)) {
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
