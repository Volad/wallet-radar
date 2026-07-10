package com.walletradar.application.normalization.config;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Dzengi-specific fiat exit rule.
 *
 * <p>A Dzengi withdrawal is a FIAT_EXIT when ALL conditions hold:
 * <ol>
 *   <li>Stream is {@code WITHDRAWALS} (not DEPOSITS or LEDGER).</li>
 *   <li>Payment method is a real-world fiat channel — NOT {@code BLOCKCHAIN} or {@code CRYPTO}.
 *       Recognised fiat methods: MASTERCARD, VISA, BANK, ERIP, SWIFT, SEPA.</li>
 *   <li>Asset is a fiat currency (BYN, USD, EUR, GBP) — NOT a crypto or stablecoin.</li>
 * </ol>
 *
 * <p>Blockchain withdrawals (USDT/USDC to on-chain wallet) remain EXTERNAL_TRANSFER_OUT
 * because they are intra-portfolio moves, not real-world cash exits.
 */
@Component
public class DzengiFiatExitRule implements FiatExitRule {

    private static final Set<String> FIAT_ASSETS =
            Set.of("BYN", "USD", "EUR", "GBP", "PLN", "CZK", "HUF", "RUB");

    private static final Set<String> NON_FIAT_METHODS =
            Set.of("BLOCKCHAIN", "CRYPTO", "ONCHAIN");

    @Override
    public boolean matches(String sourceStream, String paymentMethod, String assetSymbol) {
        if (!"WITHDRAWALS".equalsIgnoreCase(sourceStream)) {
            return false;
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return false;
        }
        String pm = paymentMethod.trim().toUpperCase(Locale.ROOT);
        if (NON_FIAT_METHODS.contains(pm)) {
            return false;
        }
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String sym = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return FIAT_ASSETS.contains(sym);
    }
}
