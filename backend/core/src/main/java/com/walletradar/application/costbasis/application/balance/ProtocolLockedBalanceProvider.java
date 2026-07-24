package com.walletradar.application.costbasis.application.balance;

import com.walletradar.domain.common.NetworkId;

import java.util.List;

/**
 * SPI for contributing <em>protocol-locked</em> collateral into {@code on_chain_balances} (WS-3).
 *
 * <p>Some protocols (e.g. Jupiter Lend on Solana) issue no fungible receipt token, so a wallet's
 * collateral locked inside the protocol is invisible to the wallet-token enumeration in
 * {@link OnChainBalanceProvider}. This SPI lets a live-position reader surface that locked collateral
 * as an additional balance leg so it merges into the wallet's on-chain quantity exactly like an Aave
 * aToken would (single-authority carry — value flows from the asset's existing AVCO, no fresh basis).</p>
 *
 * <p>Implementations are read from a persisted snapshot (snapshot-first; never perform network I/O on
 * the balance-refresh path), are idempotent and best-effort, and return an empty list when no fresh
 * live position is known (so the balance refresh degrades gracefully to the guarded synthesized path).</p>
 */
public interface ProtocolLockedBalanceProvider {

    /**
     * @param walletAddress canonical wallet address (case-sensitive for base58 families)
     * @param networkId     the network being refreshed
     * @return locked collateral balances to merge into the wallet's on-chain quantity; never null
     */
    List<OnChainBalanceProvider.ProviderBalance> fetchLockedBalances(String walletAddress, NetworkId networkId);
}
