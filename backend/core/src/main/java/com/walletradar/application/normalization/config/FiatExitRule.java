package com.walletradar.application.normalization.config;

/**
 * SPI for detecting fiat-exit events — withdrawals that represent money leaving the
 * investment ecosystem into a real-world bank or card account.
 *
 * <p>Implementations are registered as Spring beans and consulted by venue-specific
 * normalisation builders before emitting {@link
 * com.walletradar.domain.transaction.normalized.NormalizedTransactionType#EXTERNAL_TRANSFER_OUT}.
 * When a rule matches, the transaction is classified as
 * {@link com.walletradar.domain.transaction.normalized.NormalizedTransactionType#FIAT_EXIT}
 * instead, giving the UI and analytics layer a distinct signal.
 *
 * <h3>Accounting contract</h3>
 * <p>FIAT_EXIT carries identical accounting semantics to EXTERNAL_TRANSFER_OUT: it reduces
 * the fiat/stablecoin balance and counts as a NEC outflow. The difference is semantic only.
 */
public interface FiatExitRule {

    /**
     * Returns {@code true} if the given transfer-out event should be classified as FIAT_EXIT.
     *
     * @param sourceStream    venue-specific stream identifier (e.g. {@code "WITHDRAWALS"})
     * @param paymentMethod   venue payment method string (e.g. {@code "MASTERCARD"}, {@code "BLOCKCHAIN"})
     * @param assetSymbol     normalised asset symbol (e.g. {@code "BYN"}, {@code "USD"})
     */
    boolean matches(String sourceStream, String paymentMethod, String assetSymbol);
}
