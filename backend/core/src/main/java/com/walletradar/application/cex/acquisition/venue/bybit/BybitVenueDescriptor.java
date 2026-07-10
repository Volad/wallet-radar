package com.walletradar.application.cex.acquisition.venue.bybit;

import com.walletradar.application.cex.port.VenueDescriptor;
import com.walletradar.application.cex.port.VenueLiveBalanceCapability;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Venue descriptor for Bybit — encodes Bybit's sub-account topology, external-capital policy,
 * and live-balance adapter as a single ingestion-plane component.
 *
 * <p>Sub-accounts: FUND (capital gate, stablecoin-only NEC), UTA (trading), EARN (flexible saving).</p>
 * <p>External-capital policy: stablecoin-only FUND inflows, $5 floor (RC1 + RC-fund-dust).</p>
 */
@Component
@RequiredArgsConstructor
public class BybitVenueDescriptor implements VenueDescriptor, VenueLiveBalanceCapability {

    /** Normalised (lowercase) stablecoin symbols that qualify as Bybit FUND external capital. */
    static final Set<String> STABLECOIN_SYMBOLS = Set.of(
            "USDT", "USDC", "USDE", "USDS", "USDD", "USD"
    );

    static final BigDecimal MINIMUM_INFLOW_USD = new BigDecimal("5");

    private final BybitCexLiveBalancePortAdapter bybitLiveBalancePortAdapter;

    // ---- VenueIdentity ----

    @Override
    public String venueId() {
        return "bybit";
    }

    @Override
    public String providerPrefix() {
        return "BYBIT";
    }

    @Override
    public NormalizedTransactionSource normalizedSource() {
        return NormalizedTransactionSource.BYBIT;
    }

    @Override
    public Set<String> supportedStreams() {
        return Set.of(
                BybitIntegrationStream.TRANSACTION_LOG.name(),
                BybitIntegrationStream.EXECUTION_LINEAR.name(),
                BybitIntegrationStream.EXECUTION_INVERSE.name(),
                BybitIntegrationStream.EXECUTION_SPOT.name(),
                BybitIntegrationStream.EXECUTION_OPTION.name(),
                BybitIntegrationStream.FUNDING_HISTORY.name(),
                BybitIntegrationStream.INTERNAL_TRANSFER.name(),
                BybitIntegrationStream.UNIVERSAL_TRANSFER.name(),
                BybitIntegrationStream.DEPOSIT_ONCHAIN.name(),
                BybitIntegrationStream.DEPOSIT_INTERNAL.name(),
                BybitIntegrationStream.WITHDRAWAL.name(),
                BybitIntegrationStream.CONVERT_HISTORY.name(),
                BybitIntegrationStream.EARN_FLEXIBLE_SAVING.name()
        );
    }

    @Override
    public Set<String> accountKindSuffixes() {
        return Set.of(":FUND", ":UTA", ":EARN");
    }

    // ---- VenueWalletModel ----

    @Override
    public List<String> expandSubAccountRefs(String baseAccountRef) {
        if (baseAccountRef == null || baseAccountRef.isBlank()) {
            return List.of();
        }
        String normalized = baseAccountRef.trim();
        // If already has sub-account suffix, return as-is
        if (normalized.split(":", -1).length >= 3) {
            return List.of(normalized);
        }
        // Ensure uppercase BYBIT: prefix
        if (!ownsRef(normalized)) {
            return List.of(normalized);
        }
        return List.of(
                normalized + ":UTA",
                normalized + ":FUND",
                normalized + ":EARN"
        );
    }

    @Override
    public List<String> dashboardWalletRefs(String baseAccountRef) {
        // Dashboard groups all sub-accounts under the umbrella (no sub-account split in UI)
        if (baseAccountRef == null || baseAccountRef.isBlank()) {
            return List.of();
        }
        return List.of(baseAccountRef.trim());
    }

    @Override
    public boolean ledgerMatchesUmbrella(String walletAddress, String umbrellaKey) {
        if (walletAddress == null || umbrellaKey == null) {
            return false;
        }
        String normWallet = walletAddress.trim().toLowerCase(Locale.ROOT);
        String normUmbrella = umbrellaKey.trim().toLowerCase(Locale.ROOT);
        if (!normWallet.startsWith("bybit:")) {
            return false;
        }
        String[] parts = normWallet.split(":", -1);
        if (parts.length < 3) {
            return normWallet.equals(normUmbrella);
        }
        String walletBase = parts[0] + ":" + parts[1];
        return walletBase.equals(normUmbrella);
    }

    @Override
    public Set<String> subAccountKinds() {
        return Set.of("FUND", "UTA", "EARN");
    }

    // ---- VenueExternalCapitalPolicy ----

    @Override
    public BigDecimal minimumInflowUsd() {
        return MINIMUM_INFLOW_USD;
    }

    /**
     * Bybit external-capital (RC1): only stablecoin-denominated FUND inflows count as NEC.
     * Crypto deposits to FUND (MNT, DOGS, SOL, …) are crypto-to-crypto movements, not fiat injections.
     */
    @Override
    public boolean isEligibleInflowAsset(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String upper = assetSymbol.trim().toUpperCase(Locale.ROOT);
        // Strip common vault/aToken prefixes (aUSDT → USDT, vbUSDC → USDC)
        String normalized = normalizeStablecoinSymbol(upper);
        return STABLECOIN_SYMBOLS.contains(normalized);
    }

    /**
     * Bybit capital gate: only the {@code :FUND} sub-account is the capital entry/exit point.
     */
    @Override
    public boolean isCapitalGateWallet(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return false;
        }
        String upper = walletAddress.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("BYBIT:") && upper.endsWith(":FUND");
    }

    // ---- VenueLiveBalanceCapability ----

    @Override
    public Optional<CexLiveBalancePort> liveBalancePort() {
        return Optional.of(bybitLiveBalancePortAdapter);
    }

    // ---- helpers ----

    private static String normalizeStablecoinSymbol(String upper) {
        String s = upper.replace('\u20AE', 'T'); // ₮ → T
        if (s.startsWith("VB") && s.length() > 2) {
            s = s.substring(2);
        } else if ((s.startsWith("A") || s.startsWith("S")) && s.length() > 1) {
            String candidate = s.substring(1);
            if (STABLECOIN_SYMBOLS.contains(candidate) || candidate.startsWith("USD")
                    || candidate.startsWith("DAI") || candidate.startsWith("FRAX")) {
                s = candidate;
            }
        }
        if (s.endsWith("0") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
