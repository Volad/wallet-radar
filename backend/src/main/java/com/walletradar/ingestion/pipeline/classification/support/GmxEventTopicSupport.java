package com.walletradar.ingestion.pipeline.classification.support;

import org.bouncycastle.jcajce.provider.digest.Keccak;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical GMX EventEmitter topic hashing.
 */
public final class GmxEventTopicSupport {

    public static final String EVENT_EMITTER_TOPIC = "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5";

    private static final Map<String, String> CANONICAL_EVENT_NAMES = Map.ofEntries(
            Map.entry("ordercreated", "OrderCreated"),
            Map.entry("orderexecuted", "OrderExecuted"),
            Map.entry("ordercancelled", "OrderCancelled"),
            Map.entry("positionincrease", "PositionIncrease"),
            Map.entry("positiondecrease", "PositionDecrease"),
            Map.entry("withdrawalcreated", "WithdrawalCreated"),
            Map.entry("withdrawalexecuted", "WithdrawalExecuted"),
            Map.entry("glvwithdrawalcreated", "GlvWithdrawalCreated"),
            Map.entry("glvwithdrawalexecuted", "GlvWithdrawalExecuted")
    );

    private GmxEventTopicSupport() {
    }

    public static String topicHash(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return null;
        }
        String canonical = canonicalEventName(eventName);
        Keccak.Digest256 digest = new Keccak.Digest256();
        byte[] input = canonical.getBytes(StandardCharsets.UTF_8);
        digest.update(input, 0, input.length);
        byte[] hashed = digest.digest();
        StringBuilder builder = new StringBuilder("0x");
        for (byte value : hashed) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String canonicalEventName(String eventName) {
        String normalized = eventName.trim().toLowerCase(Locale.ROOT);
        return CANONICAL_EVENT_NAMES.getOrDefault(normalized, eventName.trim());
    }
}
