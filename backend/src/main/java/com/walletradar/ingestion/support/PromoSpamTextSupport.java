package com.walletradar.ingestion.support;

import java.util.Locale;

/**
 * Shared promo/phishing text heuristics used by raw-ingestion scam filtering and legacy classification triage.
 */
public final class PromoSpamTextSupport {

    private PromoSpamTextSupport() {
    }

    public static boolean isSuspiciousTokenText(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return false;
        }
        if (isPromotionalSpamText(normalized)) {
            return true;
        }

        boolean hasUrlLike = normalized.contains("http")
                || normalized.contains("www")
                || normalized.contains("://")
                || normalized.contains("t.me")
                || normalized.contains("@")
                || normalized.contains(".com")
                || normalized.contains(".io")
                || normalized.contains(".us")
                || normalized.contains(".xyz")
                || normalized.contains(".site")
                || normalized.contains(".vip")
                || normalized.contains(".top")
                || normalized.contains(".bot")
                || normalized.contains(".so")
                || normalized.contains(".do")
                || normalized.contains(".cfd")
                || normalized.contains(".store");
        if (hasUrlLike) {
            return true;
        }

        boolean hasBaitWords = normalized.contains("claim")
                || normalized.contains("visit")
                || normalized.contains("airdrop")
                || normalized.contains("reward")
                || normalized.contains("free")
                || normalized.contains("mint")
                || normalized.contains("voucher")
                || normalized.contains("telegram")
                || normalized.contains("verify")
                || normalized.contains("redeem");
        return hasBaitWords && (normalized.contains(".") || normalized.length() >= 40);
    }

    public static boolean isPromotionalSpamText(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return false;
        }

        if (normalized.contains("telegram @")
                || normalized.contains("visit www")
                || normalized.contains("claim reward")
                || normalized.contains("claim your airdrop")
                || normalized.contains("swap your voucher")
                || normalized.contains("voucher on ")
                || normalized.contains("verify:")) {
            return true;
        }

        boolean hasPromotionalWord = normalized.contains("claim")
                || normalized.contains("airdrop")
                || normalized.contains("reward")
                || normalized.contains("voucher")
                || normalized.contains("visit")
                || normalized.contains("verify")
                || normalized.contains("redeem");
        boolean hasDomainMarker = normalized.contains(".vip")
                || normalized.contains(".top")
                || normalized.contains(".bot")
                || normalized.contains(".cfd")
                || normalized.contains(".store")
                || normalized.contains(".site")
                || normalized.contains(".xyz")
                || normalized.contains(".so")
                || normalized.contains(".do");
        return hasPromotionalWord && hasDomainMarker;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
