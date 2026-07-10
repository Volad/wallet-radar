package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.support.BridgeAssetFamilySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.application.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.application.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.application.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.application.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ReplayPendingTransferKeyFactory {

    private final ReplayAssetSupport assetSupport;

    public ReplayPendingTransferKeyFactory(ReplayAssetSupport assetSupport) {
        this.assetSupport = assetSupport;
    }

    public boolean usesBybitVenueInternalCarryQueue(NormalizedTransaction transaction) {
        if (isBybitEarnPrincipalPairedTransfer(transaction)) {
            return false;
        }
        return isBybitEarnInternalTransfer(transaction)
                || isBybitSameUidInternalTransfer(transaction)
                || isBybitEarnProductTransfer(transaction)
                || isBybitUniversalTransfer(transaction);
    }

    private static boolean isBybitEarnPrincipalPairedTransfer(NormalizedTransaction transaction) {
        if (transaction == null || !Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null) {
            return false;
        }
        // bybit-earn-principal-v1:    — BybitEarnPrincipalTransferPairer (Flexible Savings LENDING pairs)
        // bybit-earn-onchain-fund-v1: — BybitOnChainEarnOrphanRepairService, corridor-funded repairs
        // Both use corr-family: keyed matching so FUND CARRY_OUT and EARN CARRY_IN are matched
        // by their shared deterministic corrId rather than the shared UID+asset FIFO queue.
        //
        // NOTE: bybit-earn-onchain-v1: (spot-funded repairs) is intentionally excluded here so
        // those FUND→EARN legs fall through to the isBybitEarnInternalTransfer FIFO path. Their
        // position key is the stripped BYBIT:uid wallet, and the FIFO queue correctly pairs FUND
        // CARRY_OUT with EARN CARRY_IN using the shared uid+asset key.
        //
        // NOTE: bybit-collapsed-v1: is intentionally excluded here. All bybit-collapsed-v1:
        // pairs (UTA↔FUND and FUND↔EARN) have continuityCandidate=true and are routed to
        // corr-family: via the generic continuityCandidate path in transferKey(). This ensures
        // both legs of a collapsed pair always land in the same corr-family queue regardless
        // of whether one leg involves an :EARN wallet.
        return correlationId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX);
    }

    /**
     * Flexible Savings / Earn product subscription and redemption rows (classified as
     * {@code LENDING_DEPOSIT} / {@code LENDING_WITHDRAW} / {@code EARN_FLEXIBLE_SAVING}) move
     * principal between FUND/UTA and
     * {@code :EARN}. They must share the venue FIFO queue — composite wrapper buckets are empty
     * for these single-leg Bybit rows and previously materialised as uncovered REALLOCATE_IN.
     */
    private boolean isBybitEarnProductTransfer(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT) {
            return false;
        }
        return transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.EARN_FLEXIBLE_SAVING;
    }

    /**
     * Cross-subaccount {@code UNIVERSAL_TRANSFER} on the same UID must use the venue FIFO queue
     * (not blind umbrella {@code CARRY_IN}) so basis follows the outbound leg.
     */
    private boolean isBybitUniversalTransfer(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT
                || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String id = transaction.getId();
        if (id == null || !id.contains(":UNIVERSAL_TRANSFER:")) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId != null && corrId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX)) {
            return false;
        }
        return sharesBybitUidWithCounterparty(transaction);
    }

    public TransferPendingKey transferKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        if (!isBybitEarnPrincipalPairedTransfer(transaction)
                && (isBybitEarnInternalTransfer(transaction) || isBybitEarnProductTransfer(transaction))) {
            String uid = extractBybitUid(transaction.getWalletAddress());
            String earnAssetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
            return new TransferPendingKey(CorrelationContract.BYBIT_EARN_CARRY_PREFIX + uid + ":" + earnAssetKey);
        }
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()
                && Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            String assetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
            return new TransferPendingKey(CorrelationContract.CORR_FAMILY_PREFIX + transaction.getCorrelationId() + ":" + assetKey);
        }
        if (isBybitSameUidInternalTransfer(transaction)) {
            String uid = extractBybitUid(transaction.getWalletAddress());
            String earnAssetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
            return new TransferPendingKey(CorrelationContract.BYBIT_EARN_CARRY_PREFIX + uid + ":" + earnAssetKey);
        }

        String quantityKey = flow.getQuantityDelta().abs().stripTrailingZeros().toPlainString();
        String assetKey = assetSupport.continuityIdentity(transaction, flow);
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
            return new TransferPendingKey(
                    "corr:" + transaction.getCorrelationId() + ":" + assetSupport.correlatedTransferIdentity(transaction, flow) + ":" + quantityKey
            );
        }
        if (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()) {
            return new TransferPendingKey("tx:" + transaction.getTxHash() + ":" + assetKey + ":" + quantityKey);
        }
        return null;
    }

    /**
     * EARN-specific same-UID Bybit internal transfer. EARN round-trip legs use different
     * Bybit stream sources, producing mismatched correlation prefixes (e.g.
     * {@code bybit-econ-v1:*} vs {@code bybit-collapsed-v1:*}). Correlation-based keys
     * cannot match cross-prefix pairs, so ALL EARN-involved transfers use the shared
     * FIFO queue.
     * <p>
     * Exceptions:
     * <ul>
     *   <li>{@code bybit-it-bundle-v1:*} — multi-leg bundles require correlation-based keys.</li>
     *   <li>{@code bybit-collapsed-v1:*} — UTA↔FUND↔EARN collapsed pairs have
     *       {@code continuityCandidate=true}; both the FUND debit and the EARN credit
     *       carry the same {@code bybit-collapsed-v1:} corrId and must route to the
     *       {@code corr-family:} queue via the {@code continuityCandidate} path in
     *       {@link #transferKey}. Routing the EARN credit to the EARN-carry FIFO while
     *       the UTA/FUND debit goes to {@code corr-family:} causes a queue mismatch
     *       that orphans the CARRY_OUT basis.</li>
     * </ul>
     */
    private boolean isBybitEarnInternalTransfer(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT
                || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId != null && (corrId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX)
                || corrId.startsWith(CorrelationContract.BYBIT_IT_BUNDLE_V1_PREFIX)
                || corrId.startsWith(CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX))) {
            return false;
        }
        String wallet = transaction.getWalletAddress();
        String counterparty = transaction.getMatchedCounterparty();
        if (counterparty == null || counterparty.isBlank()) {
            counterparty = transaction.getCounterpartyAddress();
        }
        boolean walletIsEarn = wallet != null && wallet.endsWith(CorrelationContract.WALLET_SUFFIX_EARN);
        boolean cpIsEarn = counterparty != null && counterparty.endsWith(CorrelationContract.WALLET_SUFFIX_EARN);
        if (!walletIsEarn && !cpIsEarn) {
            return false;
        }
        // RC-C (ADR-043): a non-EARN wallet → EARN INTERNAL_TRANSFER that has neither a
        // continuityCandidate flag nor a correlation ID was NOT confirmed/paired by the
        // BybitEarnPrincipalTransferPairer or any downstream matcher. Routing it to
        // bybit-earn-carry creates an orphaned CARRY_OUT when the EARN counterpart either
        // went to corr-family: (has its own corrId) or was never fetched (data gap).
        // Exclude these; they fall through to applyUnknownTransfer.
        if (!walletIsEarn && cpIsEarn
                && !Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && (corrId == null || corrId.isBlank())) {
            return false;
        }
        String walletUid = extractBybitUid(wallet);
        if (counterparty == null || counterparty.isBlank()) {
            return true;
        }
        WalletRef cpRef = WalletRef.parse(counterparty);
        if (cpRef.domain() != WalletDomainKind.CEX || !CorrelationContract.VENUE_BYBIT.equalsIgnoreCase(cpRef.venueId())) {
            return false;
        }
        return walletUid.equals(cpRef.uid());
    }

    /**
     * Bybit INTERNAL_TRANSFER within the same master UID (EARN round-trips: UTA/FUND ↔ EARN).
     * These use a shared {@code bybit-earn-carry:uid:asset} FIFO queue so that CARRY_OUT and
     * CARRY_IN legs match regardless of differing correlation IDs.
     * <p>
     * Cross-UID transfers (main ↔ sub-account) and corridor transfers (Bybit ↔ on-chain) are
     * excluded so they use correlation-based keys for proper IT-linker pairing.
     */
    private boolean isBybitSameUidInternalTransfer(NormalizedTransaction transaction) {
        if (transaction.getSource() != NormalizedTransactionSource.BYBIT
                || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId != null && corrId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX)) {
            return false;
        }
        // bybit-collapsed-v1: pairs are cross-account carries (UTA↔FUND) and must use
        // permitUncoveredFallback=true (same as non-Bybit transfers). They already
        // route to corr-family: via the continuityCandidate path in transferKey(); routing
        // them through the bybit-earn-carry FIFO queue causes FUND inbounds (which replay
        // first due to .000Z timestamps) to fail enqueue when no market price is available.
        if (corrId != null && corrId.startsWith(CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX)) {
            return false;
        }
        // RC-C (ADR-043): a non-EARN wallet → EARN INTERNAL_TRANSFER that was NOT confirmed
        // or paired by the BybitEarnPrincipalTransferPairer (cc=false AND corrId=null) is a
        // normalization anomaly. isBybitEarnInternalTransfer already rejects it; ensure this
        // broader same-UID check doesn't re-admit it to bybit-earn-carry, which would create
        // an orphaned CARRY_OUT when no matching EARN CARRY_IN exists (data gap).
        String wallet = transaction.getWalletAddress();
        String cpRaw = transaction.getMatchedCounterparty();
        if (cpRaw == null || cpRaw.isBlank()) {
            cpRaw = transaction.getCounterpartyAddress();
        }
        boolean walletIsEarn = wallet != null && wallet.endsWith(CorrelationContract.WALLET_SUFFIX_EARN);
        boolean cpIsEarn = cpRaw != null && cpRaw.endsWith(CorrelationContract.WALLET_SUFFIX_EARN);
        if (!walletIsEarn && cpIsEarn
                && !Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && (corrId == null || corrId.isBlank())) {
            return false;
        }
        return sharesBybitUidWithCounterparty(transaction);
    }

    private static boolean sharesBybitUidWithCounterparty(NormalizedTransaction transaction) {
        String walletUid = extractBybitUid(transaction.getWalletAddress());
        String counterparty = transaction.getMatchedCounterparty();
        if (counterparty == null || counterparty.isBlank()) {
            counterparty = transaction.getCounterpartyAddress();
        }
        // RC-B fix: an unmatched INTERNAL_TRANSFER with no known counterparty must NOT be
        // routed to the bybit-earn-carry: FIFO queue. Routing it there creates an orphaned
        // CARRY_OUT with no matching CARRY_IN, triggering a corridor conservation breach.
        // Returning false causes the transaction to fall back to a non-corridor routing
        // (standalone basis disposal / acquisition on the originating wallet).
        if (counterparty == null || counterparty.isBlank()) {
            return false;
        }
        WalletRef cpRef = WalletRef.parse(counterparty);
        if (cpRef.domain() != WalletDomainKind.CEX || !CorrelationContract.VENUE_BYBIT.equalsIgnoreCase(cpRef.venueId())) {
            return false;
        }
        return walletUid.equals(cpRef.uid());
    }

    private static String extractBybitUid(String walletAddress) {
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() != WalletDomainKind.CEX || ref.uid().isBlank()) {
            return "unknown";
        }
        return ref.uid();
    }

    /**
     * Returns the {@code bybit-earn-carry} FIFO key for a bundle inbound flow,
     * used as a secondary fallback in {@code applyBybitMultiLegBundleTransfer}
     * when the primary {@code corr-family} queue is exhausted.
     */
    public TransferPendingKey earnCarryFifoKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        String uid = extractBybitUid(transaction.getWalletAddress());
        if (uid == null) return null;
        String earnAssetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
        return new TransferPendingKey(CorrelationContract.BYBIT_EARN_CARRY_PREFIX + uid + ":" + earnAssetKey);
    }

    public BridgePendingKey bridgeTransferKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        Optional<String> supplementalCorrelationId = supplementalBridgeCorrelationId(transaction, flow);
        if (supplementalCorrelationId.isPresent()) {
            String bridgeFamilyIdentity = BridgeAssetFamilySupport.continuityIdentity(flow);
            if (bridgeFamilyIdentity == null || bridgeFamilyIdentity.isBlank()) {
                return null;
            }
            return new BridgePendingKey("bridge:" + supplementalCorrelationId.orElseThrow() + ":" + bridgeFamilyIdentity);
        }

        String bridgeFamilyIdentity = assetSupport.bridgeFamilyIdentity(transaction, flow);
        if (bridgeFamilyIdentity == null
                || transaction == null
                || transaction.getCorrelationId() == null
                || transaction.getCorrelationId().isBlank()) {
            return null;
        }
        return new BridgePendingKey("bridge:" + transaction.getCorrelationId() + ":" + bridgeFamilyIdentity);
    }

    private Optional<String> supplementalBridgeCorrelationId(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return Optional.empty();
        }
        if (transaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return Optional.empty();
        }
        if (flow.getRole() != NormalizedLegRole.TRANSFER) {
            return Optional.empty();
        }
        if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
            return Optional.empty();
        }
        String counterpartyAddress = flow.getCounterpartyAddress();
        if (counterpartyAddress == null || counterpartyAddress.isBlank()) {
            return Optional.empty();
        }
        if (!counterpartyAddress.regionMatches(true, 0, "LINKED:", 0, 7)) {
            return Optional.empty();
        }
        String sourceHash = counterpartyAddress.substring(7).trim();
        if (sourceHash.isBlank() || !sourceHash.regionMatches(true, 0, "0x", 0, 2)) {
            return Optional.empty();
        }
        return Optional.of("bridge:lifi:" + sourceHash.toLowerCase(Locale.ROOT));
    }

    public BridgeSettlementPendingKey bridgeSettlementKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || transaction.getType() == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0
                || transaction.getCorrelationId() == null
                || transaction.getCorrelationId().isBlank()
                || transaction.getMatchedCounterparty() == null
                || transaction.getMatchedCounterparty().isBlank()
                || Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return null;
        }
        if (transaction.getType() != NormalizedTransactionType.BRIDGE_OUT
                && transaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return null;
        }
        // For cross-asset BRIDGE_IN (cc=false), LI.FI may deliver the primary stablecoin asset plus
        // an ETH gas refund in one transaction. Both arrive as TRANSFER flows before role-alignment.
        // Rule: when there is exactly one non-ETH TRANSFER plus one or more ETH TRANSFER flows, the
        // non-ETH flow is the principal and the ETH flows are gas refunds. The ETH gas refund must NOT
        // produce a settlement key; only the non-ETH principal may.
        // When ETH is the only TRANSFER flow it IS the principal (USDC→ETH cross-asset bridge) and
        // the standard single-principal check applies.
        if (transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                && Boolean.FALSE.equals(transaction.getContinuityCandidate())
                && isEthFamilySymbol(flow.getAssetSymbol())
                && hasSinglePrincipalNonEthTransferFlow(transaction)) {
            // This ETH flow is a gas refund alongside a stablecoin principal — no settlement key.
            return null;
        }
        if (!hasSinglePrincipalTransferFlow(transaction)) {
            // Relaxed check for cross-asset BRIDGE_IN: if there's exactly one non-ETH TRANSFER
            // (the bridged stablecoin) with ETH-family gas refunds occupying the remaining TRANSFER
            // slots, treat the non-ETH flow as the sole principal.
            if (transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                    && Boolean.FALSE.equals(transaction.getContinuityCandidate())
                    && !isEthFamilySymbol(flow.getAssetSymbol())
                    && hasSinglePrincipalNonEthTransferFlow(transaction)) {
                return new BridgeSettlementPendingKey("bridge-settlement:" + transaction.getCorrelationId());
            }
            return null;
        }
        return new BridgeSettlementPendingKey("bridge-settlement:" + transaction.getCorrelationId());
    }

    /**
     * True when this transaction routes bucketed flows through a shared {@code lp:} or
     * {@code wrapper:} composite continuity bucket (multi-asset LP mint/exit, gauge/vault wrap).
     */
    public boolean usesCompositeContinuityBucket(NormalizedTransaction transaction) {
        return lpCompositeBucketIdentity(transaction) != null
                || wrapperCompositeBucketIdentity(transaction) != null;
    }

    /**
     * True when this transaction uses a wrapper composite bucket — a 2-leg receipt-style
     * transaction (VAULT_DEPOSIT/WITHDRAW, STAKING_DEPOSIT/WITHDRAW, etc.) where the receipt
     * token has a different denomination from the deposited/returned asset. On VAULT_WITHDRAW,
     * the inbound restored quantity (e.g., 1628 USDC) is incompatible with the bucket quantity
     * in receipt-token units (e.g., 1,598,068,583 mevUSDC shares) — proportional slicing
     * yields near-zero basis. Full drain is required instead.
     */
    public boolean usesWrapperCompositeBucket(NormalizedTransaction transaction) {
        return wrapperCompositeBucketIdentity(transaction) != null;
    }

    /** Receipt token continuity identity for composite {@code lp:} bucket routing, if any. */
    public String lpCompositeReceiptIdentity(NormalizedTransaction transaction) {
        return lpCompositeBucketIdentity(transaction);
    }

    public ContinuityKey continuityKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        // Cycle/8 S5: composite LP bucket. Multi-asset LP entries (e.g., AAVE GHO/USDT/USDC,
        // Balancer / Curve stable pools) deposit several distinct families and mint one LP
        // token. Each source leg parked its basis in its own FAMILY:XXX bucket previously, so
        // the LP receipt itself carried $0 basis and the ledger reported the LP position as
        // uncovered until exit. The mirror problem afflicts LP exits: the LP token burn could
        // not find the family-keyed source basis on the way back.
        //
        // The fix routes EVERY bucketed flow of an LP_ENTRY / LP_EXIT* transaction into the
        // same composite key {@code lp:<lpReceiptIdentity>}. Source-asset basis is therefore
        // aggregated in a single LP-receipt bucket on entry and reapplied on exit. The LP
        // receipt token inherits the full source basis on its inbound restore, eliminating the
        // 100% uncovered LP positions observed in Cycle/8 audit (AAVE GHO/USDT/USDC = 2144 /
        // 0 cov before this change).
        // Cycle/17 R7: gauge↔LP round-trip must reuse the same bucket key as stake. Stake deposits
        // into {@code wrapper:<gauge>} via LENDING_DEPOSIT; misclassified unstake as LP_EXIT used
        // to route through {@code lp:<lpToken>} and read an empty bucket (AAVE GHO gauge cov=0%).
        String wrapperCompositeIdentity = wrapperCompositeBucketIdentity(transaction);
        String lpCompositeIdentity = lpCompositeBucketIdentity(transaction);
        if (wrapperCompositeIdentity != null && lpCompositeIdentity != null) {
            return new ContinuityKey(
                    transaction.getWalletAddress(),
                    transaction.getNetworkId(),
                    "wrapper:" + wrapperCompositeIdentity
            );
        }
        if (lpCompositeIdentity != null) {
            return new ContinuityKey(
                    transaction.getWalletAddress(),
                    transaction.getNetworkId(),
                    "lp:" + lpCompositeIdentity
            );
        }
        if (wrapperCompositeIdentity != null) {
            return new ContinuityKey(
                    transaction.getWalletAddress(),
                    transaction.getNetworkId(),
                    "wrapper:" + wrapperCompositeIdentity
            );
        }
        return new ContinuityKey(
                transaction.getWalletAddress(),
                transaction.getNetworkId(),
                assetSupport.continuityIdentity(transaction, flow)
        );
    }

    /**
     * Returns the LP receipt token continuity identity for transactions that look like a
     * multi-asset LP entry / exit, otherwise {@code null}.
     *
     * <p>Recognises two flavors:
     * <ul>
     *   <li><b>Explicit LP type</b> — {@code LP_ENTRY} and {@code LP_EXIT*}. Always composite,
     *       regardless of source-leg asset count.</li>
     *   <li><b>Implicit multi-asset LP</b> via {@code LENDING_DEPOSIT} / {@code LENDING_WITHDRAW}.
     *       Some on-chain protocols mint a single ERC-20 receipt token for multi-asset
     *       deposits (e.g., AAVE GHO/USDT/USDC stable pool, Balancer-style boosted pools).
     *       When the receipt-side flow's identity is not a {@code FAMILY:*} alias AND the
     *       opposite side has more than one distinct asset family, we treat the transaction
     *       as a composite LP for bucketing purposes. Without this carve-out, the receipt
     *       inherits no basis and the position stays uncovered.</li>
     * </ul>
     *
     * <p>The LP receipt is the flow whose continuity identity does NOT start with
     * {@code FAMILY:}. For entry-shaped flows it's the inbound positive-qty leg; for exit
     * it's the outbound negative-qty leg.</p>
     */
    private String lpCompositeBucketIdentity(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type == null) {
            return null;
        }
        boolean isExplicitEntry = type == NormalizedTransactionType.LP_ENTRY;
        boolean isExplicitExit = type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
        boolean isImplicitEntry = type == NormalizedTransactionType.LENDING_DEPOSIT
                || type == NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT
                || type == NormalizedTransactionType.STAKING_DEPOSIT
                || type == NormalizedTransactionType.VAULT_DEPOSIT;
        boolean isImplicitExit = type == NormalizedTransactionType.LENDING_WITHDRAW
                || type == NormalizedTransactionType.EARN_FLEXIBLE_SAVING
                || type == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                || type == NormalizedTransactionType.STAKING_WITHDRAW
                || type == NormalizedTransactionType.VAULT_WITHDRAW;
        boolean entryShape = isExplicitEntry || isImplicitEntry;
        boolean exitShape = isExplicitExit || isImplicitExit;
        if (!entryShape && !exitShape) {
            return null;
        }

        NonFamilyReceiptCandidate positiveReceipt = dominantNonFamilyReceipt(transaction, 1, assetSupport);
        NonFamilyReceiptCandidate negativeReceipt = dominantNonFamilyReceipt(transaction, -1, assetSupport);

        String receiptIdentity;
        if (positiveReceipt != null && negativeReceipt == null) {
            receiptIdentity = positiveReceipt.identity();
        } else if (negativeReceipt != null && positiveReceipt == null) {
            receiptIdentity = negativeReceipt.identity();
        } else if (negativeReceipt != null && positiveReceipt != null) {
            if (entryShape) {
                receiptIdentity = positiveReceipt.identity();
            } else {
                // Dominant leg by abs qty disambiguates true LP_EXIT (large negative LP burn) from
                // misclassified mint-shaped LP_EXIT (large positive LP receipt, smaller outbound legs).
                boolean mintShapedExit =
                        positiveReceipt.absQuantity().compareTo(negativeReceipt.absQuantity()) > 0;
                receiptIdentity = mintShapedExit ? positiveReceipt.identity() : negativeReceipt.identity();
            }
        } else {
            return null;
        }

        if (isExplicitEntry || isExplicitExit) {
            return receiptIdentity;
        }

        int counterpartySign = positiveReceipt != null && receiptIdentity.equals(positiveReceipt.identity()) ? -1 : 1;
        // Implicit LP detection: require >=2 distinct counterparty-side asset identities.
        // Single-asset lending / staking deposits keep the legacy family-keyed bucket so the
        // {@link FamilyEquivalentCustodyReplayHandler} fast path remains intact.
        java.util.Set<String> counterpartyIdentities = new java.util.HashSet<>();
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() == NormalizedLegRole.FEE
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() != counterpartySign) {
                continue;
            }
            String identity = assetSupport.continuityIdentity(transaction, candidate);
            if (identity != null && !identity.isBlank()) {
                counterpartyIdentities.add(identity);
            }
        }
        if (counterpartyIdentities.size() < 2) {
            return null;
        }
        return receiptIdentity;
    }

    private record NonFamilyReceiptCandidate(String identity, java.math.BigDecimal absQuantity) {
    }

    private static NonFamilyReceiptCandidate dominantNonFamilyReceipt(
            NormalizedTransaction transaction,
            int sign,
            ReplayAssetSupport assetSupport
    ) {
        if (transaction == null || transaction.getFlows() == null || assetSupport == null) {
            return null;
        }
        NonFamilyReceiptCandidate best = null;
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() == NormalizedLegRole.FEE
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() != sign) {
                continue;
            }
            String identity = assetSupport.continuityIdentity(transaction, candidate);
            if (identity == null || identity.isBlank() || identity.startsWith("FAMILY:")) {
                continue;
            }
            java.math.BigDecimal abs = candidate.getQuantityDelta().abs();
            if (best == null || abs.compareTo(best.absQuantity()) > 0) {
                best = new NonFamilyReceiptCandidate(identity, abs);
            }
        }
        return best;
    }

    /**
     * Two-leg wrapper pass-through (LP token → gauge/vault share, or reverse). Covers explicit
     * {@link NormalizedTransactionType#LP_POSITION_STAKE} / {@code VAULT_DEPOSIT} and production
     * Curve/Aura rows still classified as {@code LENDING_DEPOSIT} / {@code LENDING_WITHDRAW}.
     */
    private String wrapperCompositeBucketIdentity(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type == null) {
            return null;
        }
        boolean depositShape = switch (type) {
            case LP_POSITION_STAKE,
                    VAULT_DEPOSIT,
                    LENDING_DEPOSIT,
                    STAKING_DEPOSIT,
                    PROTOCOL_CUSTODY_DEPOSIT,
                    LP_ENTRY -> true;
            default -> false;
        };
        boolean withdrawShape = switch (type) {
            case LP_POSITION_UNSTAKE,
                    VAULT_WITHDRAW,
                    LENDING_WITHDRAW,
                    EARN_FLEXIBLE_SAVING,
                    STAKING_WITHDRAW,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
        if (!depositShape && !withdrawShape) {
            return null;
        }
        int receiptSign = depositShape ? 1 : -1;
        int counterpartySign = -receiptSign;

        String receiptIdentity = null;
        int principalLegs = 0;
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() == NormalizedLegRole.FEE
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (candidate.getRole() != NormalizedLegRole.TRANSFER) {
                // For vault/lending withdrawals: inbound BUY-role flows represent yield/interest
                // payouts on top of the principal return. Skip them — they are not principal legs
                // and must not abort wrapper-composite detection. The BUY flow is handled
                // separately by the buy-role handler.
                if (withdrawShape && candidate.getQuantityDelta().signum() > 0) {
                    continue;
                }
                return null;
            }
            principalLegs++;
            int sign = candidate.getQuantityDelta().signum();
            if (sign != receiptSign) {
                continue;
            }
            String identity = assetSupport.continuityIdentity(transaction, candidate);
            if (identity == null || identity.isBlank() || identity.startsWith("FAMILY:")) {
                continue;
            }
            receiptIdentity = identity;
        }
        if (principalLegs != 2 || receiptIdentity == null) {
            return null;
        }
        return receiptIdentity;
    }

    private boolean hasSinglePrincipalTransferFlow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        long principalTransfers = transaction.getFlows().stream()
                .filter(flow -> flow != null
                        && flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0)
                .count();
        return principalTransfers == 1;
    }

    /**
     * True when a cross-asset BRIDGE_IN has exactly one non-ETH-family TRANSFER flow.
     * ETH-family TRANSFER siblings are treated as gas refunds and excluded from the principal count.
     */
    private boolean hasSinglePrincipalNonEthTransferFlow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        long nonEthPrincipal = transaction.getFlows().stream()
                .filter(flow -> flow != null
                        && flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0
                        && !isEthFamilySymbol(flow.getAssetSymbol()))
                .count();
        return nonEthPrincipal == 1;
    }

    private static final Set<String> ETH_FAMILY_SYMBOLS_BRIDGE = Set.of(
            "ETH", "WETH", "WEETH", "STETH", "WSTETH", "RETH", "CBETH"
    );

    private static boolean isEthFamilySymbol(String symbol) {
        if (symbol == null) {
            return false;
        }
        return ETH_FAMILY_SYMBOLS_BRIDGE.contains(symbol.trim().toUpperCase(Locale.ROOT));
    }
}
