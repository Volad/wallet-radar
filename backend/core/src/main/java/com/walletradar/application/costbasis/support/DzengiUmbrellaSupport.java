package com.walletradar.application.costbasis.support;

import com.walletradar.domain.session.UserSession;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared helpers for Dzengi CEX umbrella ledger wallets ({@code dzengi:<userId>}).
 */
public final class DzengiUmbrellaSupport {

    private DzengiUmbrellaSupport() {
    }

    public static List<String> enabledDzengiAccountRefs(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null || integration.getStatus() == UserSession.IntegrationStatus.DISABLED) {
                continue;
            }
            String accountRef = integration.getAccountRef();
            if (accountRef == null || accountRef.isBlank()) {
                continue;
            }
            if (!accountRef.toUpperCase(Locale.ROOT).startsWith("DZENGI:")) {
                continue;
            }
            refs.add(normalizeAddress(accountRef));
        }
        return List.copyOf(refs);
    }

    public static boolean dzengiLedgerMatchesEnabledVenue(String ledgerWallet, Set<String> enabledDzengiVenueRefs) {
        if (ledgerWallet == null || ledgerWallet.isBlank() || enabledDzengiVenueRefs.isEmpty()) {
            return false;
        }
        String norm = normalizeAddress(ledgerWallet);
        return norm.startsWith("dzengi:") && enabledDzengiVenueRefs.contains(norm);
    }

    public static String ledgerWalletKeyForAggregation(String walletAddress) {
        return normalizeAddress(walletAddress);
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }
}
