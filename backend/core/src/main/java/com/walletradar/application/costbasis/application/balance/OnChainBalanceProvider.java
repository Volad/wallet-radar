package com.walletradar.application.costbasis.application.balance;

import com.walletradar.domain.common.NetworkId;

import java.math.BigDecimal;
import java.util.List;

/**
 * SPI for enumerating a wallet's current on-chain balances on a single non-EVM network family.
 *
 * <p>The EVM balance path (Ankr / Etherscan / BlockScout / JSON-RPC batch) is derived from the
 * bounded accounting universe candidate set and stays inside
 * {@code com.walletradar.application.portfolio.application.OnChainBalanceRefreshService}. Non-EVM
 * families (Solana, TON) instead enumerate holdings directly from their RPC because their canonical
 * (case-sensitive base58 / friendly) addresses never match the lowercased EVM candidate query.</p>
 *
 * <p>Lives in the {@code costbasis} balance package alongside {@code OnChainBalance},
 * {@code OnChainBalanceRefreshQueryService} and the {@code OnChainBalanceRefresher} port so the
 * family implementations may read the normalization-time metadata registries (SPL mint / jetton
 * master) without the portfolio read model taking a compile-time dependency on the normalization
 * pipeline (enforced by {@code ModuleBoundaryTest}).</p>
 *
 * <p>Implementations must be idempotent, retry-safe (they run inside the scheduled portfolio
 * snapshot refresh), and must never throw for a routine empty wallet — they return an empty list.</p>
 */
public interface OnChainBalanceProvider {

    /** The single network family this provider fetches balances for (e.g. {@code SOLANA}). */
    NetworkId networkId();

    /**
     * Fetches the wallet's current native + token balances on {@link #networkId()}.
     *
     * @param walletAddress canonical member ref for the wallet (case-sensitive for base58 families)
     * @return non-null list of observed balances; zero-quantity entries may be omitted by the impl
     */
    List<ProviderBalance> fetchBalances(String walletAddress);

    /**
     * A single observed on-chain balance.
     *
     * @param assetSymbol   display/accounting symbol (resolved from metadata, or the raw mint/master)
     * @param assetContract on-chain asset identity: SPL mint / jetton master, or the native contract
     *                      sentinel the family's normalizer stamps on native flows
     * @param tokenDecimals token decimals used to scale the raw amount (native = family precision)
     * @param quantity      human-scaled quantity (raw amount already divided by 10^decimals)
     * @param nativeAsset   true for the network-native asset (always surfaced, never bounded away)
     */
    record ProviderBalance(
            String assetSymbol,
            String assetContract,
            Integer tokenDecimals,
            BigDecimal quantity,
            boolean nativeAsset
    ) {
    }
}
