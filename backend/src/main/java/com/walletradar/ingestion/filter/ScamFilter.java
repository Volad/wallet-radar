package com.walletradar.ingestion.filter;

import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

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

    private final ScamFilterProperties properties;

    /**
     * Returns true if the transaction involves a scam/phishing address and should be skipped.
     */
    public boolean isScam(RawTransaction tx) {
        if (!properties.isEnabled() || properties.getBlocklistNormalized().isEmpty()) {
            return false;
        }
        Set<String> addresses = extractAddresses(tx);
        Set<String> blocklist = properties.getBlocklistNormalized();
        for (String addr : addresses) {
            if (addr != null && !addr.isBlank() && blocklist.contains(addr.toLowerCase())) {
                log.debug("Scam filter: skipping tx {} on {} (blocklisted address: {})",
                        tx.getTxHash(), tx.getNetworkId(), addr);
                return true;
            }
        }
        return false;
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
}
