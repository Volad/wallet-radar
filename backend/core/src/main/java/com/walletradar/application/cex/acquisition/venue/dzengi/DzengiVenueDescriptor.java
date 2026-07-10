package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.walletradar.application.cex.port.VenueDescriptor;
import com.walletradar.application.cex.port.VenueLiveBalanceCapability;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * Venue descriptor for Dzengi — flat single-wallet model, no sub-account splits.
 *
 * <p>External-capital policy: all priced flows qualify (BYN + USD + stablecoins + crypto),
 * $5 floor. See ADR-050.</p>
 */
@Component
@RequiredArgsConstructor
public class DzengiVenueDescriptor implements VenueDescriptor, VenueLiveBalanceCapability {

    static final BigDecimal MINIMUM_INFLOW_USD = new BigDecimal("5");

    private final DzengiCexLiveBalancePortAdapter dzengiLiveBalancePortAdapter;

    // ---- VenueIdentity ----

    @Override
    public String venueId() {
        return "dzengi";
    }

    @Override
    public String providerPrefix() {
        return "DZENGI";
    }

    @Override
    public NormalizedTransactionSource normalizedSource() {
        return NormalizedTransactionSource.DZENGI;
    }

    @Override
    public Set<String> supportedStreams() {
        return Set.of(
                DzengiIntegrationStream.LEDGER.name(),
                DzengiIntegrationStream.DEPOSITS.name(),
                DzengiIntegrationStream.WITHDRAWALS.name(),
                DzengiIntegrationStream.MY_TRADES.name(),
                DzengiIntegrationStream.MY_TRADES_V2.name(),
                DzengiIntegrationStream.TRADING_POSITIONS_HISTORY.name(),
                DzengiIntegrationStream.EXCHANGE_INFO.name()
        );
    }

    @Override
    public Set<String> accountKindSuffixes() {
        return Set.of();
    }

    // ---- VenueWalletModel (flat — inherits no-op defaults) ----
    // expandSubAccountRefs, dashboardWalletRefs, ledgerMatchesUmbrella, subAccountKinds()
    // all use the flat defaults from VenueWalletModel.

    // ---- VenueExternalCapitalPolicy ----

    @Override
    public BigDecimal minimumInflowUsd() {
        return MINIMUM_INFLOW_USD;
    }

    /**
     * Dzengi accepts BYN (Belarusian Ruble), USD, stablecoins, and crypto deposits.
     * All priced flows qualify as external capital (per ADR-050).
     */
    @Override
    public boolean isEligibleInflowAsset(String assetSymbol) {
        return true;
    }

    /**
     * Dzengi has no sub-account split — the single umbrella wallet is always the capital gate.
     */
    @Override
    public boolean isCapitalGateWallet(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return false;
        }
        return walletAddress.trim().toUpperCase(java.util.Locale.ROOT).startsWith("DZENGI:");
    }

    // ---- VenueLiveBalanceCapability ----

    @Override
    public Optional<CexLiveBalancePort> liveBalancePort() {
        return Optional.of(dzengiLiveBalancePortAdapter);
    }
}
