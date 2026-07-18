package com.walletradar.application.costbasis.support;

import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;

import java.util.Locale;

/**
 * Replay/materialization identity contract separate from immutable audit evidence.
 */
public final class AccountingAssetIdentitySupport {

    private static final String EARN_PRINCIPAL_CORRELATION_PREFIX = CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX;
    private static final String EARN_ONCHAIN_FUND_CORRELATION_PREFIX = CorrelationContract.BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX;
    private static final String BYBIT_CORRIDOR_CORRELATION_PREFIX = CorrelationContract.BYBIT_CORRIDOR_PREFIX;
    private static final String BYBIT_COLLAPSED_CORRELATION_PREFIX = CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX;
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

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
        if (walletAddress == null) {
            return null;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() == WalletDomainKind.CEX && ref.subAccount() != null) {
            String sub = ref.subAccount().toUpperCase(Locale.ROOT);
            if (sub.equals("UTA") || sub.equals("FUND")) {
                return ref.umbrellaKey();
            }
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
     * <p>BYBIT-CORRIDOR legs are direction-aware (BLOCKER-4). An <em>outbound drain</em>
     * ({@code quantityDelta < 0}, non-FEE flow) from the {@code :FUND} sub-account keeps the full
     * {@code BYBIT:UID:FUND} wallet because the earn-principal transfer that placed assets into
     * {@code :FUND} stored them there; stripping the suffix would drain an empty root position.
     * An <em>inbound deposit</em> ({@code quantityDelta > 0}) instead collapses {@code :FUND}/
     * {@code :UTA} to the UID umbrella so the credited inventory lands on the same key that all
     * other spot activity (converts, EXECUTION_SPOT sells, {@code bybit-collapsed-v1} transfers)
     * consumes — otherwise the deposit is stranded on {@code :FUND} and later spot disposals
     * over-sell against a phantom shortfall. Conservation still holds: the corridor conservation
     * guards key on the corridor queue (correlationId + asset identity), not the position wallet,
     * so the {@code CARRY_IN}→queue consumption is invariant to this position-key collapse.
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
                WalletRef walletRef = WalletRef.parse(wallet);
                if (walletRef.domain() == WalletDomainKind.CEX && walletRef.subAccount() != null) {
                    String sub = walletRef.subAccount().toUpperCase(Locale.ROOT);
                    if (sub.equals("FUND") || sub.equals("UTA")) {
                        return walletRef.umbrellaKey();
                    }
                }
            }
            return wallet;
        }
        if (isBybitCorridorOutboundDrainFromFund(transaction, flow)) {
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
            if (wallet != null) {
                WalletRef ref = WalletRef.parse(wallet);
                if (ref.domain() == WalletDomainKind.CEX && ref.subAccount() != null) {
                    return ref.umbrellaKey();
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
     * Returns true only for BYBIT-CORRIDOR <em>outbound drains</em> from the {@code :FUND}
     * sub-account (BLOCKER-4). The full {@code :FUND} wallet is preserved so the carry-out drains
     * the sub-account pool that earn-principal transfers funded directly on {@code :FUND}.
     *
     * <p>Gate: correlationId under {@link CorrelationContract#BYBIT_CORRIDOR_PREFIX}, wallet is a
     * CEX {@code :FUND} sub-account, and the flow is an outbound value move (non-{@code FEE} role,
     * negative {@code quantityDelta}). Bybit corridor legs are single-flow {@code INTERNAL_TRANSFER}
     * rows with role {@code TRANSFER} and no FEE legs, so the flow sign is the direction signal.
     *
     * <p>Inbound corridor deposits ({@code quantityDelta > 0}) return false and fall through to
     * {@link #positionWalletAddress(NormalizedTransaction)}, which collapses {@code :FUND}/
     * {@code :UTA} onto the UID umbrella. FEE-role or zero/null-delta flows also return false and
     * never preserve {@code :FUND}.
     */
    private static boolean isBybitCorridorOutboundDrainFromFund(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId == null || !corrId.startsWith(BYBIT_CORRIDOR_CORRELATION_PREFIX)) {
            return false;
        }
        String wallet = transaction.getWalletAddress();
        if (wallet == null) {
            return false;
        }
        WalletRef ref = WalletRef.parse(wallet);
        if (ref.domain() != WalletDomainKind.CEX || !"FUND".equalsIgnoreCase(ref.subAccount())) {
            return false;
        }
        return flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() < 0;
    }

    public static boolean isVenueScopedCex(NormalizedTransactionSource source) {
        return source == NormalizedTransactionSource.BYBIT || source == NormalizedTransactionSource.DZENGI;
    }

    public static NetworkId positionNetwork(NormalizedTransaction transaction) {
        if (transaction != null && isVenueScopedCex(transaction.getSource())) {
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
                transaction != null && isVenueScopedCex(transaction.getSource())
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
            boolean venueScopedCex
    ) {
        String symbol = normalizeSymbol(assetSymbol);
        String contract = normalizeContract(assetContract);

        if (venueScopedCex) {
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
        // Contract check first: if the contract is in the alias set the token is treated as
        // the network native regardless of symbol (e.g. WAVAX contract → AVAX accounting
        // identity). Symbol-only check (native token with no contract) comes second.
        // Source of truth: network-descriptors.yml (wrapped-native ∪ native-alias contracts),
        // bridged at startup via NetworkNativeAssets.
        if (contract != null) {
            return NetworkNativeAssets.nativeAliasContracts(networkId).contains(contract);
        }
        // No contract → pure native token; accept only when symbol matches.
        String nativeSymbol = NetworkNativeAssets.nativeSymbol(networkId);
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
