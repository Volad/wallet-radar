package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.platform.networks.solana.SolanaChain;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package-private utility: infers LP / swap / transfer shape from the wallet's net token flow.
 *
 * <p>All methods are stateless and side-effect-free. They implement the mechanical flow-shape
 * decisions that the rule dispatch in {@link SolanaTransactionClassifier} consults; no rule
 * ordering, no protocol naming, and no Helius vocabulary sets live here.</p>
 *
 * <p>W17 extraction: moved verbatim from {@link SolanaTransactionClassifier}. No decision logic
 * was changed; only the class boundary shifted.</p>
 */
final class SolanaFlowShape {

    /**
     * Helius {@code type} strings that unambiguously signal a CLMM LP add/increase.
     * Moved here together with {@link #LP_EXIT_TYPES} so {@link #resolveClmmType} can live in
     * this class rather than the rule dispatcher.
     */
    static final Set<String> LP_ENTRY_TYPES = Set.of(
            "ADD_LIQUIDITY", "ADD_LIQUIDITY_BY_STRATEGY", "INITIALIZE_POSITION"
    );

    /** Helius {@code type} strings that unambiguously signal a CLMM LP remove/decrease. */
    static final Set<String> LP_EXIT_TYPES = Set.of(
            "REMOVE_LIQUIDITY", "REMOVE_LIQUIDITY_BY_RANGE"
    );

    /**
     * RC-S8: non-DeFi system programs. When every program a transaction touches is in this set, the
     * transaction cannot be a DEX/LP/lending/staking interaction — it is a plain SPL/native transfer
     * (or account housekeeping), so it is safe to classify by net-flow shape rather than leaving it
     * UNKNOWN.
     *
     * <p>W12/W16: only chain-runtime system programs are referenced here; they are intentionally
     * excluded from the protocol registry and live in {@link SolanaChain}.</p>
     */
    static final Set<String> NON_DEFI_SYSTEM_PROGRAMS = Set.of(
            SolanaChain.SYSTEM_PROGRAM,
            SolanaChain.TOKEN_PROGRAM,
            SolanaChain.TOKEN_2022_PROGRAM,
            SolanaChain.ASSOCIATED_TOKEN_PROGRAM,
            SolanaChain.COMPUTE_BUDGET_PROGRAM,
            SolanaChain.MEMO_PROGRAM,
            SolanaChain.MEMO_PROGRAM_LEGACY,
            // Lighthouse is a wallet-injected runtime assertion program (no economic flow); ignoring it
            // lets a plain transfer that Phantom guarded still classify by net-flow shape.
            SolanaChain.LIGHTHOUSE
    );

    private SolanaFlowShape() {
    }

    /**
     * Classifies a concentrated-liquidity AMM (CLMM) interaction — e.g. Raydium CLMM — that can be a
     * swap OR an LP add/remove. A CLMM must never be hard-forced to {@code SWAP}: an LP add/remove
     * booked as a swap loses one leg and blocks the AVCO conservation gate
     * ({@code STAT_SWAP_MISSING_BUY_LEG} / {@code STAT_SWAP_MISSING_SELL_LEG}).
     *
     * <p>Decision precedence:
     * <ol>
     *   <li>Explicit Helius liquidity discriminators (increase/decrease/open/close position,
     *       add/remove liquidity, collect/claim fee) — used when reliably present.</li>
     *   <li>Otherwise infer from the shape of the wallet's own net token movements
     *       ({@link #inferClmmTypeByFlowShape}).</li>
     * </ol>
     */
    static NormalizedTransactionType resolveClmmType(SolanaRawTransactionView view) {
        String heliusType = view.heliusType();
        if (LP_ENTRY_TYPES.contains(heliusType)
                || heliusType.contains("ADD_LIQUIDITY")
                || heliusType.contains("OPEN_POSITION")
                || heliusType.contains("INCREASE_LIQUIDITY")) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (LP_EXIT_TYPES.contains(heliusType)
                || heliusType.contains("REMOVE_LIQUIDITY")
                || heliusType.contains("CLOSE_POSITION")
                || heliusType.contains("DECREASE_LIQUIDITY")) {
            return NormalizedTransactionType.LP_EXIT;
        }
        if ("COLLECT_FEE".equals(heliusType)
                || "CLAIM_FEE".equals(heliusType)
                || "COLLECT_REWARD".equals(heliusType)) {
            return NormalizedTransactionType.LP_FEE_CLAIM;
        }
        return inferClmmTypeByFlowShape(view);
    }

    /**
     * Infers a CLMM event type from the shape of the wallet's own net token movements (net per mint,
     * dust excluded):
     * <ul>
     *   <li>1 mint net-out + 1 different mint net-in → {@code SWAP} (both legs materialize).</li>
     *   <li>≥1 mint net-out and 0 net-in → {@code LP_ENTRY} (add/increase liquidity, incl. single-sided).</li>
     *   <li>≥1 mint net-in and 0 net-out → {@code LP_EXIT} (remove/decrease liquidity, incl. single-sided).</li>
     *   <li>Any other non-clean shape (e.g. 2 net-out + 1 tiny residual net-in) → the dominant side
     *       decides {@code LP_ENTRY} / {@code LP_EXIT}: a CLMM interaction that is not a clean
     *       1-out/1-in swap is an LP op, never a broken swap.</li>
     * </ul>
     * When no wallet token movement is observable, a present {@code events.swap} keeps {@code SWAP}
     * (legs come from the swap event); otherwise the fee-only row defaults to {@code LP_ENTRY} so no
     * swap-leg validation applies.
     */
    static NormalizedTransactionType inferClmmTypeByFlowShape(SolanaRawTransactionView view) {
        Map<String, BigDecimal> netByMint = SolanaWalletNetFlow.walletNetByMint(view);
        int netOut = 0;
        int netIn = 0;
        for (BigDecimal net : netByMint.values()) {
            if (net.signum() < 0) {
                netOut++;
            } else if (net.signum() > 0) {
                netIn++;
            }
        }
        if (netOut == 0 && netIn == 0) {
            return view.swapEvent() != null
                    ? NormalizedTransactionType.SWAP
                    : NormalizedTransactionType.LP_ENTRY;
        }
        if (netOut == 1 && netIn == 1) {
            return NormalizedTransactionType.SWAP;
        }
        if (netIn == 0) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (netOut == 0) {
            return NormalizedTransactionType.LP_EXIT;
        }
        return netOut >= netIn ? NormalizedTransactionType.LP_ENTRY : NormalizedTransactionType.LP_EXIT;
    }

    /**
     * RC-S5: resolves the direction of a plain transfer from the net per-asset wallet delta across
     * {@code nativeTransfers} + {@code tokenTransfers}.
     *
     * <ul>
     *   <li>Only inbound legs (net positive) → {@code EXTERNAL_TRANSFER_IN}.</li>
     *   <li>Only outbound legs (net negative) → {@code EXTERNAL_TRANSFER_OUT}.</li>
     *   <li>Every leg is wallet↔wallet (self) → {@code INTERNAL_TRANSFER}.</li>
     *   <li>Mixed in+out → direction of the primary asset (largest absolute net delta). Cross-wallet
     *       transfers to another owned wallet are promoted to INTERNAL downstream by the counterparty
     *       resolver once the peer is classified {@code PERSONAL_WALLET}.</li>
     * </ul>
     */
    static NormalizedTransactionType resolveTransferType(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return NormalizedTransactionType.EXTERNAL_TRANSFER_IN;
        }
        Map<String, BigDecimal> netByAsset = new LinkedHashMap<>();
        boolean anyLegTouchesWallet = false;
        boolean allSelf = true;

        for (Document transfer : view.nativeTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            long lamports = SolanaWalletNetFlow.toLong(transfer.get("amount"));
            if (lamports <= 0) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(lamports).divide(SolanaWalletNetFlow.LAMPORTS_PER_SOL);
            boolean inbound = wallet.equals(to);
            boolean outbound = wallet.equals(from);
            if (inbound || outbound) {
                anyLegTouchesWallet = true;
            }
            if (!(inbound && outbound)) {
                allSelf = false;
            }
            if (inbound) {
                netByAsset.merge("SOL", amount, BigDecimal::add);
            }
            if (outbound) {
                netByAsset.merge("SOL", amount.negate(), BigDecimal::add);
            }
        }

        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            String mint = transfer.getString("mint");
            BigDecimal amount = SolanaWalletNetFlow.parseBigDecimal(transfer.get("tokenAmount"));
            if (mint == null || amount == null || amount.signum() == 0) {
                continue;
            }
            boolean inbound = wallet.equals(to);
            boolean outbound = wallet.equals(from);
            if (inbound || outbound) {
                anyLegTouchesWallet = true;
            }
            if (!(inbound && outbound)) {
                allSelf = false;
            }
            if (inbound) {
                netByAsset.merge(mint, amount, BigDecimal::add);
            }
            if (outbound) {
                netByAsset.merge(mint, amount.negate(), BigDecimal::add);
            }
        }

        if (!anyLegTouchesWallet) {
            return NormalizedTransactionType.EXTERNAL_TRANSFER_IN;
        }
        if (allSelf) {
            return NormalizedTransactionType.INTERNAL_TRANSFER;
        }

        BigDecimal primaryNet = BigDecimal.ZERO;
        BigDecimal primaryMagnitude = BigDecimal.valueOf(-1);
        for (BigDecimal net : netByAsset.values()) {
            BigDecimal magnitude = net.abs();
            if (magnitude.compareTo(primaryMagnitude) > 0) {
                primaryMagnitude = magnitude;
                primaryNet = net;
            }
        }
        if (primaryNet.signum() < 0) {
            return NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
        }
        return NormalizedTransactionType.EXTERNAL_TRANSFER_IN;
    }

    /**
     * RC-S8: classifies an unlabeled transaction as a plain transfer when it touches only non-DeFi
     * system programs and the wallet has an actual net token/SOL movement. Returns {@code null} when
     * the guard does not hold (an unknown DeFi program is present, or there is no wallet-net flow),
     * leaving the caller to fall through to {@code UNKNOWN}/NEEDS_REVIEW.
     */
    static SolanaClassificationResult nonDefiTransferFallback(SolanaRawTransactionView view) {
        if (!touchesOnlyNonDefiPrograms(view)) {
            return null;
        }
        // Require a real wallet-net movement so a truly empty/no-op tx is not fabricated as a transfer.
        if (SolanaWalletNetFlow.walletNetByMint(view).isEmpty()) {
            return null;
        }
        return SolanaClassificationResult.transfer(resolveTransferType(view));
    }

    static boolean touchesOnlyNonDefiPrograms(SolanaRawTransactionView view) {
        List<String> programIds = view.programIds();
        if (programIds.isEmpty()) {
            return false;
        }
        for (String programId : programIds) {
            if (!NON_DEFI_SYSTEM_PROGRAMS.contains(programId)) {
                return false;
            }
        }
        return true;
    }
}
