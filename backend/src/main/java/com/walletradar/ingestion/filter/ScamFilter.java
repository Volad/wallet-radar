package com.walletradar.ingestion.filter;

import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters phishing/scam transactions from ingestion. Checks tx addresses (to, from, log contracts)
 * against a configurable blocklist. When enabled and a match is found, the transaction is skipped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScamFilter {

    private static final String ERC20_TRANSFER_TOPIC0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final int MASS_AIRDROP_MIN_TRANSFER_LOGS = 20;
    private static final int MASS_RELAY_SPAM_MIN_TRANSFER_LOGS = 30;
    private static final int MASS_INBOUND_FANOUT_MIN_RECIPIENTS = 40;
    private static final int ZERO_AMOUNT_POISONING_MIN_TRANSFER_LOGS = 100;
    private static final int ZERO_AMOUNT_POISONING_MIN_ZERO_TRANSFERS = 20;

    private final ScamFilterProperties properties;

    /**
     * Returns true if the transaction involves a scam/phishing address and should be skipped.
     */
    public boolean isScam(RawTransaction tx) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (isLikelyRelayOrPoisoningSpam(tx)) {
            log.debug("Scam filter: skipping tx {} on {} (heuristic: relay/poisoning spam)",
                    tx.getTxHash(), tx.getNetworkId());
            return true;
        }
        if (isLikelySpamAirdrop(tx)) {
            log.debug("Scam filter: skipping tx {} on {} (heuristic: unsolicited batch airdrop)",
                    tx.getTxHash(), tx.getNetworkId());
            return true;
        }

        Set<String> blocklist = properties.getBlocklistNormalized();
        if (blocklist.isEmpty()) {
            return false;
        }

        Set<String> addresses = extractAddresses(tx);
        for (String addr : addresses) {
            if (addr != null && !addr.isBlank() && blocklist.contains(addr.toLowerCase())) {
                log.debug("Scam filter: skipping tx {} on {} (blocklisted address: {})",
                        tx.getTxHash(), tx.getNetworkId(), addr);
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyRelayOrPoisoningSpam(RawTransaction tx) {
        if (tx == null || tx.getRawData() == null) {
            return false;
        }
        Document raw = tx.getRawData();
        if (!(raw.get("logs") instanceof List<?> logs)) {
            return false;
        }

        String wallet = normalize(rawString(tx.getWalletAddress()));
        String txSender = normalize(rawString(raw.get("from")));
        if (wallet == null || txSender == null || wallet.equals(txSender)) {
            return false;
        }

        TransferStats stats = collectTransferStats(logs, wallet);
        if (stats.transferCount < MASS_RELAY_SPAM_MIN_TRANSFER_LOGS) {
            return false;
        }

        boolean relaySweepSpam = stats.transferToWalletCount == 0 && stats.transferFromWalletCount >= 1;
        boolean inboundFanoutSpam = stats.transferToWalletCount >= 1
                && stats.transferFromWalletCount == 0
                && stats.uniqueRecipients >= MASS_INBOUND_FANOUT_MIN_RECIPIENTS;
        boolean zeroAmountPoisoning = stats.walletInvolved()
                && stats.transferCount >= ZERO_AMOUNT_POISONING_MIN_TRANSFER_LOGS
                && stats.zeroAmountTransferCount >= ZERO_AMOUNT_POISONING_MIN_ZERO_TRANSFERS;

        return relaySweepSpam || inboundFanoutSpam || zeroAmountPoisoning;
    }

    private boolean isLikelySpamAirdrop(RawTransaction tx) {
        if (tx == null || tx.getRawData() == null) {
            return false;
        }
        Document raw = tx.getRawData();
        if (!(raw.get("logs") instanceof List<?> logs) || logs.size() < MASS_AIRDROP_MIN_TRANSFER_LOGS) {
            return false;
        }

        String wallet = normalize(rawString(tx.getWalletAddress()));
        String txSender = normalize(rawString(raw.get("from")));
        String txTo = normalize(rawString(raw.get("to")));
        if (wallet == null || txSender == null || txTo == null) {
            return false;
        }
        // Only filter unsolicited inbound spam; if wallet initiated the tx, keep it.
        if (wallet.equals(txSender)) {
            return false;
        }

        Set<String> tokenContracts = new HashSet<>();
        Set<String> transferSenders = new HashSet<>();
        Set<String> transferRecipients = new HashSet<>();
        Set<String> transferValues = new HashSet<>();

        for (Object o : logs) {
            if (!(o instanceof Document log)) {
                return false;
            }
            String logAddress = normalize(rawString(log.get("address")));
            if (logAddress == null) {
                return false;
            }
            tokenContracts.add(logAddress);

            if (!(log.get("topics") instanceof List<?> topics) || topics.size() < 3) {
                return false;
            }
            String topic0 = normalize(rawString(topics.get(0)));
            if (!ERC20_TRANSFER_TOPIC0.equals(topic0)) {
                return false;
            }
            String transferFrom = topicToAddress(topics.get(1));
            String transferTo = topicToAddress(topics.get(2));
            if (transferFrom == null || transferTo == null) {
                return false;
            }
            transferSenders.add(transferFrom);
            transferRecipients.add(transferTo);

            String value = normalize(rawString(log.get("data")));
            if (value == null) {
                return false;
            }
            transferValues.add(value);
        }

        if (!transferRecipients.contains(wallet)) {
            return false;
        }
        return tokenContracts.size() == 1
                && tokenContracts.contains(txTo)
                && transferSenders.size() == 1
                && !transferSenders.contains(txSender)
                && transferValues.size() == 1
                && transferRecipients.size() >= MASS_AIRDROP_MIN_TRANSFER_LOGS;
    }

    private Set<String> extractAddresses(RawTransaction tx) {
        Set<String> out = new HashSet<>();
        if (tx.getRawData() == null) return out;

        Document raw = tx.getRawData();

        // EVM: receipt has "to", "from", and logs with "address"
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
        Set<String> recipients = new HashSet<>();

        for (Object o : logs) {
            if (!(o instanceof Document log)) {
                continue;
            }
            Object topicsObj = log.get("topics");
            if (!(topicsObj instanceof List<?> topics) || topics.size() < 3) {
                continue;
            }
            String topic0 = normalize(rawString(topics.get(0)));
            if (!ERC20_TRANSFER_TOPIC0.equals(topic0)) {
                continue;
            }
            String transferFrom = topicToAddress(topics.get(1));
            String transferTo = topicToAddress(topics.get(2));
            if (transferFrom == null || transferTo == null) {
                continue;
            }
            transferCount++;
            recipients.add(transferTo);
            if (wallet.equals(transferFrom)) {
                transferFromWalletCount++;
            }
            if (wallet.equals(transferTo)) {
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
                recipients.size()
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

    private record TransferStats(
            int transferCount,
            int zeroAmountTransferCount,
            int transferFromWalletCount,
            int transferToWalletCount,
            int uniqueRecipients
    ) {
        private boolean walletInvolved() {
            return transferFromWalletCount > 0 || transferToWalletCount > 0;
        }
    }
}
