package com.walletradar.application.costbasis.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replay/materialization identity contract separate from immutable audit evidence.
 */
public final class AccountingAssetIdentitySupport {

    private static final String EARN_PRINCIPAL_CORRELATION_PREFIX = "bybit-earn-principal-v1:";
    private static final String EARN_ONCHAIN_FUND_CORRELATION_PREFIX = "bybit-earn-onchain-fund-v1:";
    private static final String BYBIT_CORRIDOR_CORRELATION_PREFIX = "BYBIT-CORRIDOR:";
    private static final String BYBIT_COLLAPSED_CORRELATION_PREFIX = "bybit-collapsed-v1:";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private static final Map<NetworkId, String> NATIVE_SYMBOLS = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM, "ETH"),
            Map.entry(NetworkId.ARBITRUM, "ETH"),
            Map.entry(NetworkId.OPTIMISM, "ETH"),
            Map.entry(NetworkId.BASE, "ETH"),
            Map.entry(NetworkId.UNICHAIN, "ETH"),
            Map.entry(NetworkId.ZKSYNC, "ETH"),
            Map.entry(NetworkId.LINEA, "ETH"),
            Map.entry(NetworkId.KATANA, "ETH"),
            Map.entry(NetworkId.BSC, "BNB"),
            Map.entry(NetworkId.POLYGON, "MATIC"),
            Map.entry(NetworkId.AVALANCHE, "AVAX"),
            Map.entry(NetworkId.MANTLE, "MNT"),
            Map.entry(NetworkId.PLASMA, "XPL")
    );

    /**
     * Wrapped-native contracts that are accounting-identical to the network's native token.
     * Wrap/unwrap swaps between native and these contracts are treated as same-asset moves
     * (no cost-basis impact). Addresses are lowercase.
     */
    private static final Map<NetworkId, Set<String>> NATIVE_ALIAS_CONTRACTS = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM,  Set.of("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")),  // WETH
            Map.entry(NetworkId.ARBITRUM,  Set.of("0x82af49447d8a07e3bd95bd0d56f35241523fbab1")),  // WETH
            Map.entry(NetworkId.OPTIMISM,  Set.of("0x4200000000000000000000000000000000000006")),  // WETH
            Map.entry(NetworkId.BASE,      Set.of("0x4200000000000000000000000000000000000006")),  // WETH
            Map.entry(NetworkId.UNICHAIN,  Set.of("0x4200000000000000000000000000000000000006")),  // WETH
            Map.entry(NetworkId.ZKSYNC,    Set.of(
                    "0x000000000000000000000000000000000000800a",  // native ETH alias (system contract)
                    "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91"   // WETH
            )),
            Map.entry(NetworkId.LINEA,     Set.of("0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f")),  // WETH
            Map.entry(NetworkId.BSC,       Set.of("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c")),  // WBNB
            Map.entry(NetworkId.POLYGON,   Set.of("0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270")),  // WMATIC
            Map.entry(NetworkId.AVALANCHE, Set.of("0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7")),  // WAVAX
            Map.entry(NetworkId.MANTLE,    Set.of("0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8"))   // WMNT
    );

    private static final String VARIABLE_DEBT_PREFIX = "VARIABLEDEBT";
    private static final String STABLE_DEBT_PREFIX = "STABLEDEBT";

    private AccountingAssetIdentitySupport() {
    }

    /**
     * Aave-style debt receipt identity (ADR-012 §D8, F-4).
     *
     * <p>{@code variableDebt*} / {@code stableDebt*} tokens are liability markers, never held
     * assets. They must be excluded from portfolio positions (the borrow is tracked as a
     * {@code BorrowLiability} instead), so the same token is never both subtracted from
     * {@code portfolioValue} and registered as a liability (the double-subtract guard). The
     * test is symbol-shape based and chain-agnostic so it covers every network's debt receipt.</p>
     */
    public static boolean isDebtIdentity(String assetSymbol) {
        String symbol = normalizeSymbol(assetSymbol);
        return symbol.startsWith(VARIABLE_DEBT_PREFIX) || symbol.startsWith(STABLE_DEBT_PREFIX);
    }

    /**
     * Normalizes wallet address for position keying. UTA and FUND are purely
     * administrative sub-accounts within one Bybit account, so they share a
     * single position (UID-level). EARN stays separate (lending semantics).
     */
    public static String positionWalletAddress(NormalizedTransaction transaction) {
        if (transaction == null) {
            return null;
        }
        return positionWalletAddress(transaction.getWalletAddress());
    }

    public static String positionWalletAddress(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return walletAddress;
        }
        if (walletAddress.endsWith(":UTA") || walletAddress.endsWith(":FUND")) {
            return walletAddress.substring(0, walletAddress.lastIndexOf(':'));
        }
        return walletAddress;
    }

    /**
     * Replay position wallet for ledger and carry attachment.
     *
     * <p>RC-1: Flexible-Savings earn-principal pairs ({@code bybit-earn-principal-v1:}) keep their
     * held {@code :EARN} sub-account but route their FUND/UTA-side principal to the UID umbrella.
     * The collapsed UTA↔FUND inbound that funds {@code :FUND} nets onto the umbrella under RC-2,
     * leaving {@code :FUND} empty, so the subscribe must drain the umbrella (where the inventory
     * actually sits) and the redeem must credit it back. Stripping {@code :FUND}/{@code :UTA} for
     * these pairs lets a closed subscribe→redeem cycle net to zero on a single {@code :EARN} key.
     *
     * <p>{@code bybit-earn-onchain-fund-v1:} corridor-funded repairs are intentionally NOT stripped:
     * those rows place basis directly on {@code :FUND}, so the {@code CARRY_OUT} must drain that
     * sub-account.
     *
     * <p>BYBIT-CORRIDOR transactions from the {@code :FUND} sub-account also keep the full
     * wallet address because the earn-principal transfer that placed assets into {@code :FUND}
     * stored them under {@code BYBIT:UID:FUND}. Stripping the suffix would cause the carry-out
     * to drain an empty root position instead of the actual funded position.
     */
    public static String replayPositionWalletAddress(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (isEarnPrincipalPaired(transaction)) {
            String wallet = (flow != null && flow.getAccountRef() != null && !flow.getAccountRef().isBlank())
                    ? flow.getAccountRef().trim()
                    : (transaction == null ? null : transaction.getWalletAddress());
            if (wallet == null || wallet.isBlank()) {
                return null;
            }
            wallet = wallet.trim();
            if (isFlexibleEarnPrincipal(transaction)) {
                String upper = wallet.toUpperCase(Locale.ROOT);
                if (upper.endsWith(":FUND") || upper.endsWith(":UTA")) {
                    return wallet.substring(0, wallet.lastIndexOf(':'));
                }
            }
            return wallet;
        }
        if (isBybitCorridorFromFund(transaction)) {
            String wallet = transaction.getWalletAddress();
            if (wallet != null && !wallet.isBlank()) {
                return wallet.trim();
            }
        }
        if (isBybitCollapsedFundSide(transaction, flow)) {
            String wallet = transaction.getWalletAddress();
            if (wallet != null && !wallet.isBlank()) {
                return wallet.trim();
            }
        }
        // XPL double-count: a Bybit REWARD_CLAIM is claimed yield, i.e. liquid spendable balance,
        // not staked principal. Route it to the UID umbrella (stripping :EARN as well as :FUND/:UTA)
        // so a subsequent collapsed FUND->UTA move drains a real umbrella lot instead of an empty
        // one. Otherwise the reward stays siloed on :EARN while the collapsed UTA-inbound
        // materialises a fresh uncovered phantom on the umbrella, doubling the reward across
        // :EARN + umbrella. Combined per-asset balance is unchanged (:FUND rewards already strip to
        // the umbrella via positionWalletAddress), so reconciled earn assets (LDO/ONDO/LTC) keep
        // their combined totals.
        if (isBybitRewardClaim(transaction)) {
            String wallet = transaction.getWalletAddress();
            if (wallet != null && wallet.startsWith("BYBIT:")) {
                String upper = wallet.toUpperCase(Locale.ROOT);
                if (upper.endsWith(":EARN") || upper.endsWith(":FUND") || upper.endsWith(":UTA")) {
                    return wallet.substring(0, wallet.lastIndexOf(':'));
                }
                return wallet;
            }
        }
        return positionWalletAddress(transaction);
    }

    private static boolean isBybitRewardClaim(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getSource() == NormalizedTransactionSource.BYBIT
                && transaction.getType() == NormalizedTransactionType.REWARD_CLAIM;
    }

    private static boolean isEarnPrincipalPaired(NormalizedTransaction transaction) {
        if (transaction == null || !Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null) {
            return false;
        }
        // bybit-earn-principal-v1:      — BybitEarnPrincipalTransferPairer (Flexible Savings LENDING pairs)
        // bybit-earn-onchain-fund-v1:   — BybitOnChainEarnOrphanRepairService, corridor-funded FUND events
        //                                 These require full :FUND/:EARN wallet in the replay position key
        //                                 so the CARRY_OUT drains the correct funded sub-account position.
        //
        // NOTE: bybit-earn-onchain-v1: (spot-funded, non-corridor) is intentionally excluded here.
        // Spot-funded FUND events have their basis at BYBIT:uid (stripped) so stripping the :FUND
        // suffix is correct for them. Treating them as earn-principal-paired would misroute the
        // CARRY_OUT to an empty BYBIT:uid:FUND position instead of the actual root position.
        return correlationId.startsWith(EARN_PRINCIPAL_CORRELATION_PREFIX)
                || correlationId.startsWith(EARN_ONCHAIN_FUND_CORRELATION_PREFIX);
    }

    /**
     * RC-1: Flexible-Savings earn-principal pairs only ({@code bybit-earn-principal-v1:}). Their
     * FUND/UTA-side principal lives on the UID umbrella (collapsed UTA↔FUND inbound nets there),
     * so the FUND/UTA leg routes to the umbrella while the held {@code :EARN} leg stays scoped.
     * Excludes {@code bybit-earn-onchain-fund-v1:}, whose basis legitimately sits on {@code :FUND}.
     */
    private static boolean isFlexibleEarnPrincipal(NormalizedTransaction transaction) {
        String correlationId = transaction == null ? null : transaction.getCorrelationId();
        return correlationId != null && correlationId.startsWith(EARN_PRINCIPAL_CORRELATION_PREFIX);
    }

    /**
     * Formerly preserved {@code :FUND} on inbound UTA↔FUND {@code bybit-collapsed-v1:} legs while
     * outbound stripped to the umbrella — that asymmetry created inbound-only phantom {@code :FUND}
     * pools (RC-2). Both halves now strip to the umbrella so round-trips net to zero on one key.
     */
    private static boolean isBybitCollapsedFundSide(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return false;
    }

    /**
     * Returns true for BYBIT-CORRIDOR transactions whose Bybit-side wallet is the {@code :FUND}
     * sub-account. These transactions carry out assets that earn-principal transfers deposited
     * directly into {@code :FUND}, so the full sub-account address must be preserved.
     */
    private static boolean isBybitCorridorFromFund(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId == null || !corrId.startsWith(BYBIT_CORRIDOR_CORRELATION_PREFIX)) {
            return false;
        }
        String wallet = transaction.getWalletAddress();
        return wallet != null && wallet.toUpperCase(Locale.ROOT).endsWith(":FUND");
    }

    public static NetworkId positionNetwork(NormalizedTransaction transaction) {
        if (transaction != null && transaction.getSource() == NormalizedTransactionSource.BYBIT) {
            return null;
        }
        return transaction == null ? null : transaction.getNetworkId();
    }

    public static String positionAssetIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow == null) {
            return null;
        }
        return positionAssetIdentity(
                transaction == null ? null : transaction.getNetworkId(),
                flow.getAssetSymbol(),
                flow.getAssetContract(),
                transaction != null && transaction.getSource() == NormalizedTransactionSource.BYBIT
        );
    }

    public static String positionAssetIdentity(
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
        return positionAssetIdentity(networkId, assetSymbol, assetContract, false);
    }

    public static String positionAssetIdentity(
            NetworkId networkId,
            String assetSymbol,
            String assetContract,
            boolean venueScopedBybit
    ) {
        String symbol = normalizeSymbol(assetSymbol);
        String contract = normalizeContract(assetContract);

        if (venueScopedBybit) {
            if (!symbol.isBlank()) {
                return "SYMBOL:" + symbol;
            }
            return contract;
        }

        if (isNativeAlias(networkId, symbol, contract)) {
            return "NATIVE:" + networkId.name();
        }

        if (contract != null) {
            return contract;
        }
        return symbol.isBlank() ? null : "SYMBOL:" + symbol;
    }

    private static boolean isNativeAlias(
            NetworkId networkId,
            String symbol,
            String contract
    ) {
        if (networkId == null) {
            return false;
        }
        // Contract check first: if the contract is in the alias map the token is treated as
        // the network native regardless of symbol (e.g. WAVAX contract → AVAX accounting
        // identity). Symbol-only check (native token with no contract) comes second.
        if (contract != null) {
            return NATIVE_ALIAS_CONTRACTS.getOrDefault(networkId, Set.of()).contains(contract);
        }
        // No contract → pure native token; accept only when symbol matches.
        String nativeSymbol = NATIVE_SYMBOLS.get(networkId);
        return nativeSymbol != null && nativeSymbol.equals(symbol);
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        String normalized = contract.trim();
        if (ZERO_ADDRESS.equalsIgnoreCase(normalized)) {
            return null;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NATIVE:") || upper.startsWith("SYMBOL:")) {
            return upper;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
