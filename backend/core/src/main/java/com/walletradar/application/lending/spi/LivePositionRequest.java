package com.walletradar.application.lending.spi;

/**
 * Per-wallet request handed to a {@link LendingLivePositionReader}. Carries only the identity of the
 * borrow group to refresh; the reader owns all protocol-specific transport.
 *
 * @param sessionId      owning session (snapshot scoping)
 * @param protocolKey    display protocol key of the group (e.g. {@code Aave}, {@code Jupiter Lend})
 * @param networkId      network id (upper-case name, e.g. {@code SOLANA}, {@code ARBITRUM})
 * @param walletAddress  canonical wallet address (case-sensitive for base58 families)
 */
public record LivePositionRequest(
        String sessionId,
        String protocolKey,
        String networkId,
        String walletAddress
) {
}
