package com.walletradar.accounting.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;

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

    private static final Map<NetworkId, Set<String>> NATIVE_ALIAS_CONTRACTS = Map.of(
            NetworkId.ZKSYNC,
            Set.of("0x000000000000000000000000000000000000800a")
    );

    private AccountingAssetIdentitySupport() {
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
     * Replay position wallet for ledger and carry attachment. Earn-principal paired rows keep
     * their venue sub-account ({@code :FUND}, {@code :EARN}, {@code :UTA}) so inbound restore
     * lands on the same wallet as normalized evidence and pending carry keys can match.
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
            if (flow != null && flow.getAccountRef() != null && !flow.getAccountRef().isBlank()) {
                return flow.getAccountRef().trim();
            }
            String wallet = transaction == null ? null : transaction.getWalletAddress();
            return wallet == null || wallet.isBlank() ? null : wallet.trim();
        }
        if (isBybitCorridorFromFund(transaction)) {
            String wallet = transaction.getWalletAddress();
            if (wallet != null && !wallet.isBlank()) {
                return wallet.trim();
            }
        }
        return positionWalletAddress(transaction);
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
        String nativeSymbol = NATIVE_SYMBOLS.get(networkId);
        if (nativeSymbol == null || !nativeSymbol.equals(symbol)) {
            return false;
        }
        if (contract == null) {
            return true;
        }
        return NATIVE_ALIAS_CONTRACTS.getOrDefault(networkId, Set.of()).contains(contract);
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
