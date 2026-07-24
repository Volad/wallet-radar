package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Program-ID-first classifier for Solana transactions parsed by the Helius Enhanced API.
 *
 * <p>Rules execute in priority order — the first matching rule wins. Helius {@code type} and
 * {@code source} labels act only as secondary hints when program-ID evidence is ambiguous.</p>
 */
@Component
public class SolanaTransactionClassifier {

    private static final Set<String> STAKING_WITHDRAW_TYPES = Set.of(
            "UNSTAKE_SOL", "DEACTIVATE_STAKE", "WITHDRAW_STAKE"
    );
    private static final Set<String> STAKING_DEPOSIT_TYPES = Set.of(
            "STAKE_SOL", "DELEGATE_STAKE", "INITIALIZE_STAKE"
    );
    private static final Set<String> LP_ENTRY_TYPES = Set.of(
            "ADD_LIQUIDITY", "ADD_LIQUIDITY_BY_STRATEGY", "INITIALIZE_POSITION"
    );
    private static final Set<String> LP_EXIT_TYPES = Set.of(
            "REMOVE_LIQUIDITY", "REMOVE_LIQUIDITY_BY_RANGE"
    );
    private static final Set<String> OTHER_AGGREGATOR_SOURCES = Set.of(
            "ORCA", "DFLOW", "OKX", "TITAN", "OPENBOOK"
    );
    // RC-S6: SPL/system housekeeping Helius types that carry no economic wallet flow. Classified as
    // ADMIN_CONFIG so they normalize fee-only and clear SOLANA_UNCLASSIFIED (replay-safe).
    private static final Set<String> HOUSEKEEPING_TYPES = Set.of(
            "CLOSE_ACCOUNT", "INITIALIZE_ACCOUNT", "CREATE_ACCOUNT", "CREATE_ASSOCIATED_TOKEN_ACCOUNT",
            "INITIALIZE_ACCOUNT_3", "REALLOC", "SYNC_NATIVE"
    );
    // RC-S6: compressed-NFT mint Helius types (Metaplex Bubblegum). Non-economic → NFT_MINT fee-only.
    private static final Set<String> NFT_MINT_TYPES = Set.of(
            "COMPRESSED_NFT_MINT", "NFT_MINT", "MINT_CNFT", "COMPRESSED_NFT_TRANSFER"
    );
    /**
     * RC-S8: non-DeFi system programs. When every program a transaction touches is in this set, the
     * transaction cannot be a DEX/LP/lending/staking interaction — it is a plain SPL/native transfer
     * (or account housekeeping), so it is safe to classify by net-flow shape rather than leaving it
     * UNKNOWN.
     */
    private static final Set<String> NON_DEFI_SYSTEM_PROGRAMS = Set.of(
            SolanaProgramIds.SYSTEM_PROGRAM,
            SolanaProgramIds.TOKEN_PROGRAM,
            SolanaProgramIds.TOKEN_2022_PROGRAM,
            SolanaProgramIds.ASSOCIATED_TOKEN_PROGRAM,
            SolanaProgramIds.COMPUTE_BUDGET_PROGRAM,
            SolanaProgramIds.MEMO_PROGRAM,
            SolanaProgramIds.MEMO_PROGRAM_LEGACY,
            // Lighthouse is a wallet-injected runtime assertion program (no economic flow); ignoring it
            // lets a plain transfer that Phantom guarded still classify by net-flow shape.
            SolanaProgramIds.LIGHTHOUSE
    );

    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);
    /**
     * Native SOL dust floor (in SOL) for flow-shape counting: net wallet SOL deltas below this are
     * rent/noise and are not counted as an economic side. Mirrors the builder's dust threshold.
     */
    private static final BigDecimal NATIVE_SOL_DUST_THRESHOLD = new BigDecimal("0.000001");

    public SolanaClassificationResult classify(SolanaRawTransactionView view) {
        String heliusType = view.heliusType();
        String heliusSource = view.heliusSource();

        // 1. Jupiter Lend — Helius returns a generic/empty type for Jupiter Lend, so the event
        //    (borrow / supply / withdraw / loop) is decided by the wallet's net asset flow relative
        //    to the lending liquidity account, never by the Helius type string (RULE 1). The set of
        //    Jupiter Lend programs (borrow / earn / liquidity) is authoritative in the protocol
        //    registry (WS-1 DoD); any of them present marks a Jupiter Lend interaction.
        if (touchesJupiterLend(view)) {
            return SolanaClassificationResult.of(
                    resolveJupiterLendType(view),
                    "Jupiter Lend",
                    "jupiter-lend",
                    "LENDING"
            );
        }

        // 2. Kamino Lend
        if (view.hasProgramId(SolanaProgramIds.KAMINO_LEND)) {
            return SolanaClassificationResult.of(
                    resolveLendingType(heliusType),
                    "Kamino Lend",
                    "kamino-lend",
                    "LENDING"
            );
        }

        // 3. Kamino Vault
        if (view.hasProgramId(SolanaProgramIds.KAMINO_VAULT)) {
            return SolanaClassificationResult.of(
                    resolveVaultType(heliusType),
                    "Kamino Vault",
                    "kamino-vault",
                    "YIELD"
            );
        }

        // 3b. Routed-swap guard. A dedicated swap-router / aggregator program (OKX DEX router, DFLOW,
        //     Jupiter swap v6/v4, Jupiter RFQ order engine) is EXCLUSIVELY a swap venue: it CPIs the
        //     underlying AMM/CLMM pool (Meteora DLMM / Raydium CLMM) as the swap execution venue, not
        //     as an LP add/remove. Placed here — AFTER the Jupiter Lend / Kamino rules (a Jupiter Lend
        //     loop-open that also invokes Jupiter Swap is already classified as lending above) and
        //     BEFORE the Meteora/Raydium CLMM rules (4/7) — so a routed swap through a CLMM pool is a
        //     SWAP, never a phantom LP_ENTRY with no matching exit. Direct Meteora/Raydium LP
        //     adds/removes never route through these programs, so they still reach rules 4/7 unchanged.
        if (touchesSwapRouter(view)) {
            return routedSwapResult(view);
        }

        // 4. Meteora DLMM — concentrated (bin) liquidity. Add vs remove MUST be decided by the wallet's
        //    net token flow, not the Helius `type` string: Helius frequently reports a generic/unknown
        //    type for DLMM remove-liquidity, and the old string-only resolver defaulted those to
        //    LP_ENTRY. That booked exits as entries, so the LP-receipt basis pool never drained —
        //    surfacing as ghost "open" pools and inflating on-chain SOL/token balances with liquidity
        //    the user had already withdrawn. resolveClmmType() infers ENTRY/EXIT/SWAP/FEE from flow shape.
        if (view.hasProgramId(SolanaProgramIds.METEORA_DLMM)) {
            return SolanaClassificationResult.of(
                    resolveClmmType(view),
                    "Meteora DLMM",
                    "meteora-dlmm",
                    "LP"
            );
        }

        // 5. Meteora Dynamic Vault (yield). Only the authoritative program ID is honoured — the two
        //    IDs previously registered here were misidentified (61DFf = Jupiter RFQ swap; L2TExMF =
        //    Lighthouse assertion program), which booked swaps as one-sided vault deposits.
        //
        //    Guard on DAMM-absence: a Meteora Dynamic AMM (DAMM v1/v2) pool parks its idle liquidity
        //    in dynamic vaults, so EVERY DAMM swap / LP add-remove CPIs this same vault program. The
        //    bare presence of the vault program is therefore NOT sufficient to call the tx a vault
        //    deposit — when a DAMM program is also present the user's action is a swap or LP op routed
        //    through the pool's vault and must be decided by flow shape in rule 9b. Without this guard a
        //    plain SOL->vSOL (or SOL->mSOL/bSOL) DAMM swap was mis-booked as a one-sided VAULT_DEPOSIT
        //    that dropped the received LST leg and parked the SOL basis (never disposing it), inflating
        //    the SOL cost pool. A genuine standalone vault deposit/withdraw touches only the vault
        //    program (no DAMM) and still lands here.
        if (view.hasProgramId(SolanaProgramIds.METEORA_VAULT)
                && !view.hasProgramId(SolanaProgramIds.METEORA_DYNAMIC_AMM)
                && !view.hasProgramId(SolanaProgramIds.METEORA_DAMM_V2)) {
            return SolanaClassificationResult.of(
                    resolveVaultType(heliusType),
                    "Meteora Vault",
                    "meteora-vault",
                    "YIELD"
            );
        }

        // 5b. Meteora farming (LP stake / reward harvest)
        if (view.hasProgramId(SolanaProgramIds.METEORA_FARM)) {
            return SolanaClassificationResult.of(
                    resolveMeteoraFarmType(heliusType),
                    "Meteora Farm",
                    "meteora-farm",
                    "STAKING"
            );
        }

        // 6. Hawksight — automation wrapper over Meteora DLMM. Same flow-shape rule as raw DLMM so an
        //    automated remove-liquidity is not mislabeled as an entry.
        if (view.hasProgramId(SolanaProgramIds.HAWKSIGHT)) {
            return SolanaClassificationResult.of(
                    resolveClmmType(view),
                    "Meteora DLMM (via Hawksight)",
                    "meteora-dlmm",
                    "LP"
            );
        }

        // 7. Raydium CLMM — concentrated-liquidity AMM: swap OR LP add/remove. Decide by
        //    instruction/flow shape, never a hard-forced SWAP (which drops an LP leg and blocks AVCO).
        if (view.hasProgramId(SolanaProgramIds.RAYDIUM_CLMM)) {
            return SolanaClassificationResult.of(
                    resolveClmmType(view),
                    "Raydium CLMM",
                    "raydium-clmm",
                    "LP"
            );
        }

        // 8. Raydium AMM v4
        if (view.hasProgramId(SolanaProgramIds.RAYDIUM_AMM_V4)) {
            return SolanaClassificationResult.of(
                    resolveRaydiumAmmType(heliusType),
                    "Raydium AMM v4",
                    "raydium-amm",
                    "DEX"
            );
        }

        // 9. Raydium CPMM
        if (view.hasProgramId(SolanaProgramIds.RAYDIUM_CPMM)) {
            return SolanaClassificationResult.of(
                    resolveRaydiumAmmType(heliusType),
                    "Raydium CPMM",
                    "raydium-cpmm",
                    "DEX"
            );
        }

        // 9b. Meteora Dynamic AMM (DAMM v1 / v2) — constant-product/constant-sum pools that expose a
        //     swap OR LP add/remove. Decide by flow shape so an LP op is never booked as a broken swap.
        if (view.hasProgramId(SolanaProgramIds.METEORA_DYNAMIC_AMM)
                || view.hasProgramId(SolanaProgramIds.METEORA_DAMM_V2)) {
            return SolanaClassificationResult.of(
                    resolveClmmType(view),
                    "Meteora Dynamic AMM",
                    "meteora-damm",
                    "LP"
            );
        }

        // 10. Jupiter swap (aggregator router, RFQ order engine, or helius source). The RFQ Order
        //     Engine fills a taker order against a maker in one tx and frequently delivers native SOL;
        //     the swap flow builder reconstructs both legs from accountData net-by-mint + native delta.
        if (view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V6)
                || view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V4)
                || view.hasProgramId(SolanaProgramIds.JUPITER_RFQ_ORDER_ENGINE)
                || "JUPITER".equals(heliusSource)) {
            boolean rfq = view.hasProgramId(SolanaProgramIds.JUPITER_RFQ_ORDER_ENGINE);
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.SWAP,
                    rfq ? "Jupiter RFQ" : "Jupiter",
                    rfq ? "jupiter-rfq" : "jupiter",
                    "AGGREGATOR"
            );
        }

        // 11. Other known aggregators by helius source
        if (OTHER_AGGREGATOR_SOURCES.contains(heliusSource)
                || view.hasProgramId(SolanaProgramIds.DFLOW)
                || view.hasProgramId(SolanaProgramIds.OKX_DEX_ROUTER)
                || "RAYDIUM".equals(heliusSource)
                || "METEORA".equals(heliusSource)) {
            // If Helius source says METEORA/RAYDIUM but we got here, no specific LP program matched
            // — treat as simple swap (likely an AMM swap without LP management)
            String protocolKey = heliusSource.toLowerCase().replace("_", "-");
            String protocolName = capitalizeSource(heliusSource);
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.SWAP,
                    protocolName,
                    protocolKey,
                    "AGGREGATOR"
            );
        }

        // 12. Solana native staking
        if (view.hasProgramId(SolanaProgramIds.STAKE_PROGRAM)) {
            if (STAKING_WITHDRAW_TYPES.contains(heliusType)) {
                return SolanaClassificationResult.of(
                        NormalizedTransactionType.STAKING_WITHDRAW,
                        "Solana Staking",
                        "solana-staking",
                        "STAKING"
                );
            }
            return SolanaClassificationResult.of(
                    STAKING_DEPOSIT_TYPES.contains(heliusType)
                            ? NormalizedTransactionType.STAKING_DEPOSIT
                            : NormalizedTransactionType.STAKING_DEPOSIT,
                    "Solana Staking",
                    "solana-staking",
                    "STAKING"
            );
        }

        // 13. Liquid staking (Marinade, Jito)
        if (view.hasProgramId(SolanaProgramIds.MARINADE)) {
            return SolanaClassificationResult.of(
                    resolveStakingType(heliusType),
                    "Marinade",
                    "marinade",
                    "STAKING"
            );
        }
        if (view.hasProgramId(SolanaProgramIds.JITO_STAKE_POOL)) {
            return SolanaClassificationResult.of(
                    resolveStakingType(heliusType),
                    "Jito",
                    "jito",
                    "STAKING"
            );
        }

        // 14. Transfer heuristic (SYSTEM_PROGRAM / SPL token program, no DeFi program).
        //     RC-S5: direction from net wallet delta (IN / OUT / INTERNAL), not a hardcoded IN.
        if ("TRANSFER".equals(heliusType)) {
            return SolanaClassificationResult.transfer(resolveTransferType(view));
        }

        // 15. SWAP by type alone (e.g. unknown AMM)
        if ("SWAP".equals(heliusType)) {
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.SWAP,
                    null,
                    null,
                    null
            );
        }

        // 16. RC-S6: compressed-NFT mint (Metaplex Bubblegum) — non-economic, fee-only.
        if (view.hasProgramId(SolanaProgramIds.BUBBLEGUM) || NFT_MINT_TYPES.contains(heliusType)) {
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.NFT_MINT,
                    "Solana NFT",
                    "solana-nft",
                    null
            );
        }

        // 17. RC-S6: SPL / system account housekeeping (close/initialize/create) — non-economic.
        if (HOUSEKEEPING_TYPES.contains(heliusType)) {
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.ADMIN_CONFIG,
                    null,
                    null,
                    null
            );
        }

        // 18. RC-S8: pure SPL/native transfer fallback for an unlabeled Helius type.
        //     A plain outbound SPL transfer (e.g. a pump.fun memecoin) often carries a non-"TRANSFER"
        //     Helius type (UNKNOWN), which would otherwise fall through to UNKNOWN/NEEDS_REVIEW and be
        //     wrongly pulled into EVM clarification. When NO DeFi/aggregator/staking program matched
        //     above AND the transaction touches only non-DeFi system programs (System, SPL Token,
        //     Token-2022, ATA, ComputeBudget, Memo), classify by the wallet's net-flow shape — a real
        //     wallet-net movement is a genuine transfer even with an unknown/unpriced mint.
        SolanaClassificationResult nonDefiTransfer = nonDefiTransferFallback(view);
        if (nonDefiTransfer != null) {
            return nonDefiTransfer;
        }

        // Unknown — flag for review
        return SolanaClassificationResult.unknown();
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
    private NormalizedTransactionType resolveTransferType(SolanaRawTransactionView view) {
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
            long lamports = toLong(transfer.get("amount"));
            if (lamports <= 0) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL);
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
            BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
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
    private SolanaClassificationResult nonDefiTransferFallback(SolanaRawTransactionView view) {
        if (!touchesOnlyNonDefiPrograms(view)) {
            return null;
        }
        // Require a real wallet-net movement so a truly empty/no-op tx is not fabricated as a transfer.
        if (walletNetByMint(view).isEmpty()) {
            return null;
        }
        return SolanaClassificationResult.transfer(resolveTransferType(view));
    }

    private boolean touchesOnlyNonDefiPrograms(SolanaRawTransactionView view) {
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

    private NormalizedTransactionType resolveMeteoraFarmType(String heliusType) {
        if (heliusType.contains("CLAIM") || heliusType.contains("HARVEST") || heliusType.contains("REWARD")) {
            return NormalizedTransactionType.REWARD_CLAIM;
        }
        if (heliusType.contains("WITHDRAW") || heliusType.contains("UNSTAKE") || heliusType.contains("REMOVE")) {
            return NormalizedTransactionType.LP_POSITION_UNSTAKE;
        }
        return NormalizedTransactionType.LP_POSITION_STAKE;
    }

    private static long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static BigDecimal parseBigDecimal(Object value) {
        if (value instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * RULE 1 — Jupiter Lend event classification by net asset flow (never by Helius {@code type},
     * which is empty/generic for Jupiter Lend). Decision precedence, using the wallet's net per-mint
     * delta ({@link #walletNetByMint}) where SOL is the collateral, the SOLANA USD-stable mints are
     * the borrowable/quote assets, and the protocol position-receipt token (e.g. jl-SOL) is ignored
     * because it is neither SOL nor a stablecoin:
     * <ol>
     *   <li>Jupiter <em>Swap</em> invoked in the same tx with a net collateral change → the borrowed
     *       asset was acquired and swapped/re-supplied within one tx → {@code LENDING_LOOP_OPEN}.</li>
     *   <li>Net-positive stablecoin (no matching outbound of it) → {@code BORROW}.</li>
     *   <li>Net-negative stablecoin → {@code REPAY}.</li>
     *   <li>Net-positive collateral (SOL) → {@code LENDING_WITHDRAW}.</li>
     *   <li>Net-negative collateral (SOL) → {@code LENDING_DEPOSIT}.</li>
     * </ol>
     * Receipt-dust-only changes (no SOL/stable movement) default to {@code LENDING_DEPOSIT} and
     * normalize fee-only via the net-flow leg builder.
     */
    /**
     * True when the transaction touches a dedicated swap-router / aggregator program. These programs
     * are exclusively swap venues (an AMM/CLMM pool they CPI is the swap execution venue, not an LP
     * add/remove), so a transaction touching one is a swap even when it routes through a Meteora DLMM
     * or Raydium CLMM pool.
     */
    private boolean touchesSwapRouter(SolanaRawTransactionView view) {
        return view.hasProgramId(SolanaProgramIds.OKX_DEX_ROUTER)
                || view.hasProgramId(SolanaProgramIds.DFLOW)
                || view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V6)
                || view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V4)
                || view.hasProgramId(SolanaProgramIds.JUPITER_RFQ_ORDER_ENGINE);
    }

    /**
     * Builds the {@code SWAP} classification result for a routed-swap program, reusing the protocol
     * naming of rules 10 (Jupiter) and 11 (source-labelled aggregators): Jupiter RFQ order engine →
     * "Jupiter RFQ"; Jupiter swap v6/v4 → "Jupiter"; OKX/DFLOW → the capitalized Helius source when
     * present (e.g. a Raydium-routed OKX swap → "Raydium"), else the router's own name.
     */
    private SolanaClassificationResult routedSwapResult(SolanaRawTransactionView view) {
        if (view.hasProgramId(SolanaProgramIds.JUPITER_RFQ_ORDER_ENGINE)) {
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, "Jupiter RFQ", "jupiter-rfq", "AGGREGATOR");
        }
        if (view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V6)
                || view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V4)) {
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, "Jupiter", "jupiter", "AGGREGATOR");
        }
        String source = view.heliusSource();
        if (source != null && !source.isBlank() && !"UNKNOWN".equals(source)) {
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.SWAP,
                    capitalizeSource(source),
                    source.toLowerCase().replace("_", "-"),
                    "AGGREGATOR"
            );
        }
        if (view.hasProgramId(SolanaProgramIds.OKX_DEX_ROUTER)) {
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, "OKX", "okx", "AGGREGATOR");
        }
        return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, "DFLOW", "dflow", "AGGREGATOR");
    }

    /** True when the transaction touches any registry-declared Jupiter Lend program. */
    private boolean touchesJupiterLend(SolanaRawTransactionView view) {
        for (String programId : SolanaProgramIds.JUPITER_LEND_PROGRAM_IDS) {
            if (view.hasProgramId(programId)) {
                return true;
            }
        }
        return false;
    }

    private NormalizedTransactionType resolveJupiterLendType(SolanaRawTransactionView view) {
        Map<String, BigDecimal> net = walletNetByMint(view);
        BigDecimal solNet = net.getOrDefault(SolanaProgramIds.WSOL_MINT, BigDecimal.ZERO);
        BigDecimal stableNet = BigDecimal.ZERO;
        for (String mint : SolanaProgramIds.SOLANA_USD_STABLE_MINTS) {
            BigDecimal delta = net.get(mint);
            if (delta != null) {
                stableNet = stableNet.add(delta);
            }
        }
        boolean hasSwap = view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V6)
                || view.hasProgramId(SolanaProgramIds.JUPITER_SWAP_V4)
                || "JUPITER".equals(view.heliusSource());
        // A leveraged loop-open borrows a stablecoin and swaps it into additional collateral within the
        // same tx, so the WALLET participates in the swap; a plain collateral deposit that merely shares
        // a transaction/CPI with an unrelated Jupiter swap has the swap's value legs owned by maker/pool
        // accounts, not the tracked wallet. It is a loop when the wallet has any stablecoin participation
        // (net OR gross) OR the co-located swap is not a third-party swap (no value leg is entirely
        // between non-wallet accounts) — the WS-1 B1 anchor wraps native SOL through its own accounts
        // with a Jupiter Swap and no visible stablecoin, so `walletHasStableFlow` alone under-classifies
        // it; the foreign-swap-legs guard keeps the 5YMocs co-located third-party swap a deposit while
        // restoring the genuine wallet-participating loop.
        if (hasSwap && solNet.signum() != 0
                && (stableNet.signum() != 0 || walletHasStableFlow(view) || !hasForeignValueSwapLegs(view))) {
            return NormalizedTransactionType.LENDING_LOOP_OPEN;
        }
        if (stableNet.signum() > 0) {
            return NormalizedTransactionType.BORROW;
        }
        if (stableNet.signum() < 0) {
            return NormalizedTransactionType.REPAY;
        }
        if (solNet.signum() > 0) {
            return NormalizedTransactionType.LENDING_WITHDRAW;
        }
        if (solNet.signum() < 0) {
            return NormalizedTransactionType.LENDING_DEPOSIT;
        }
        return NormalizedTransactionType.LENDING_DEPOSIT;
    }

    private NormalizedTransactionType resolveLendingType(String heliusType) {
        return switch (heliusType) {
            case "DEPOSIT", "SUPPLY", "LENDING_DEPOSIT" -> NormalizedTransactionType.LENDING_DEPOSIT;
            case "WITHDRAW", "REDEEM", "LENDING_WITHDRAW" -> NormalizedTransactionType.LENDING_WITHDRAW;
            case "BORROW" -> NormalizedTransactionType.BORROW;
            case "REPAY" -> NormalizedTransactionType.REPAY;
            case "LOOP_OPEN", "LEVERAGE_OPEN", "LENDING_LOOP_OPEN" -> NormalizedTransactionType.LENDING_LOOP_OPEN;
            case "LOOP_REBALANCE", "LENDING_LOOP_REBALANCE" -> NormalizedTransactionType.LENDING_LOOP_REBALANCE;
            case "CLAIM_REWARD", "CLAIM", "HARVEST" -> NormalizedTransactionType.REWARD_CLAIM;
            default -> NormalizedTransactionType.LENDING_DEPOSIT;
        };
    }

    private NormalizedTransactionType resolveVaultType(String heliusType) {
        if (heliusType.contains("WITHDRAW") || heliusType.contains("REDEEM")) {
            return NormalizedTransactionType.VAULT_WITHDRAW;
        }
        return NormalizedTransactionType.VAULT_DEPOSIT;
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
    private NormalizedTransactionType resolveClmmType(SolanaRawTransactionView view) {
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
    private NormalizedTransactionType inferClmmTypeByFlowShape(SolanaRawTransactionView view) {
        Map<String, BigDecimal> netByMint = walletNetByMint(view);
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
     * Net per-mint wallet delta (native SOL keyed as wSOL) for flow-shape inference. Prefers the
     * authoritative {@code accountData[].tokenBalanceChanges} (owner == wallet) plus the wallet's
     * native SOL delta; falls back to {@code tokenTransfers} + {@code nativeTransfers} when no
     * account-level balance changes are present. Native SOL deltas below the dust floor are ignored;
     * strictly-zero SPL deltas are dropped, and zero-net mints (in==out) are pruned.
     */
    private Map<String, BigDecimal> walletNetByMint(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        if (wallet == null) {
            return net;
        }

        BigDecimal solDelta = BigDecimal.ZERO;
        boolean sawAccountData = false;
        for (Document account : view.accountData()) {
            if (account == null) {
                continue;
            }
            if (wallet.equals(account.getString("account"))) {
                long nativeChange = toLong(account.get("nativeBalanceChange"));
                if (nativeChange != 0) {
                    solDelta = solDelta.add(BigDecimal.valueOf(nativeChange).divide(LAMPORTS_PER_SOL));
                }
            }
            for (Document change : docList(account, "tokenBalanceChanges")) {
                if (change == null || !wallet.equals(change.getString("userAccount"))) {
                    continue;
                }
                String mint = change.getString("mint");
                if (mint == null || mint.isBlank()) {
                    continue;
                }
                BigDecimal delta = rawTokenAmountToDecimal(change.get("rawTokenAmount"), mint.trim());
                if (delta == null || delta.signum() == 0) {
                    continue;
                }
                sawAccountData = true;
                net.merge(mint.trim(), delta, BigDecimal::add);
            }
        }

        if (sawAccountData) {
            // The native SOL delta from accountData embeds the tx fee ONLY when the wallet paid it;
            // add back the wallet-scoped fee so a pure fee payment is not miscounted as an economic
            // outflow (and third-party-paid fees are not falsely added back).
            BigDecimal walletSolDelta = solDelta.add(
                    BigDecimal.valueOf(view.walletFeeInLamports()).divide(LAMPORTS_PER_SOL));
            if (walletSolDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
                net.merge(SolanaProgramIds.WSOL_MINT, walletSolDelta, BigDecimal::add);
            }
        } else {
            accumulateTransferNet(view, wallet, net);
        }

        net.values().removeIf(v -> v.signum() == 0);
        return net;
    }

    /**
     * True when the tracked wallet's own token accounts show any (gross, non-net) movement of a
     * SOLANA USD-stable mint. Used to tell a genuine leveraged loop-open (wallet borrows a stablecoin
     * and swaps it to collateral within one tx, netting the stable to ~0) apart from a plain collateral
     * deposit that merely co-locates with an unrelated Jupiter swap whose stable legs are owned by
     * maker/pool accounts rather than the wallet.
     */
    private boolean walletHasStableFlow(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        boolean sawAccountData = false;
        for (Document account : view.accountData()) {
            if (account == null) {
                continue;
            }
            for (Document change : docList(account, "tokenBalanceChanges")) {
                if (change == null || !wallet.equals(change.getString("userAccount"))) {
                    continue;
                }
                String mint = change.getString("mint");
                if (mint == null || mint.isBlank()) {
                    continue;
                }
                sawAccountData = true;
                if (SolanaProgramIds.SOLANA_USD_STABLE_MINTS.contains(mint.trim())) {
                    BigDecimal delta = rawTokenAmountToDecimal(change.get("rawTokenAmount"), mint.trim());
                    if (delta != null && delta.signum() != 0) {
                        return true;
                    }
                }
            }
        }
        if (!sawAccountData) {
            for (Document transfer : view.tokenTransfers()) {
                String mint = transfer.getString("mint");
                if (mint == null || !SolanaProgramIds.SOLANA_USD_STABLE_MINTS.contains(mint.trim())) {
                    continue;
                }
                String from = transfer.getString("fromUserAccount");
                String to = transfer.getString("toUserAccount");
                if (!wallet.equals(from) && !wallet.equals(to)) {
                    continue;
                }
                BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
                if (amount != null && amount.signum() != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the co-located swap is a THIRD-PARTY swap the tracked wallet does not participate in:
     * there is a token transfer of a value mint (wSOL or a SOLANA USD-stable) whose BOTH endpoints are
     * accounts other than the tracked wallet (maker/pool ↔ maker/pool). A genuine leveraged loop routes
     * the borrowed stablecoin and re-supplied collateral through the WALLET's own token accounts, so its
     * value legs always touch the wallet; a plain SOL deposit that merely shares a transaction/CPI with
     * an unrelated Jupiter swap shows the swap's stable/SOL legs owned by maker/pool accounts only.
     *
     * <p>Returns {@code false} when the wallet's balance evidence is authoritative {@code accountData}
     * with no {@code tokenTransfers} (the WS-1 B1 anchor, where a native-SOL wrap is co-located with a
     * Jupiter Swap and there are no visible foreign legs) — those are genuine wallet-participating loops.
     */
    private boolean hasForeignValueSwapLegs(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        for (Document transfer : view.tokenTransfers()) {
            String mint = transfer.getString("mint");
            if (mint == null) {
                continue;
            }
            String trimmed = mint.trim();
            boolean valueMint = SolanaProgramIds.WSOL_MINT.equals(trimmed)
                    || SolanaProgramIds.SOLANA_USD_STABLE_MINTS.contains(trimmed);
            if (!valueMint) {
                continue;
            }
            BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            if (!wallet.equals(from) && !wallet.equals(to)) {
                return true;
            }
        }
        return false;
    }

    private void accumulateTransferNet(SolanaRawTransactionView view, String wallet, Map<String, BigDecimal> net) {
        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            String mint = transfer.getString("mint");
            BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
            if (mint == null || mint.isBlank() || amount == null || amount.signum() == 0) {
                continue;
            }
            if (wallet.equals(to)) {
                net.merge(mint.trim(), amount, BigDecimal::add);
            }
            if (wallet.equals(from)) {
                net.merge(mint.trim(), amount.negate(), BigDecimal::add);
            }
        }
        BigDecimal solDelta = BigDecimal.ZERO;
        for (Document transfer : view.nativeTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            long lamports = toLong(transfer.get("amount"));
            if (lamports <= 0) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL);
            if (wallet.equals(to)) {
                solDelta = solDelta.add(amount);
            }
            if (wallet.equals(from)) {
                solDelta = solDelta.subtract(amount);
            }
        }
        if (solDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
            net.merge(SolanaProgramIds.WSOL_MINT, solDelta, BigDecimal::add);
        }
    }

    private static BigDecimal rawTokenAmountToDecimal(Object rawTokenAmount, String mint) {
        if (!(rawTokenAmount instanceof Document doc)) {
            return null;
        }
        BigDecimal raw = parseBigDecimal(doc.get("tokenAmount"));
        if (raw == null) {
            return null;
        }
        int decimals = (int) toLong(doc.get("decimals"));
        if (decimals <= 0) {
            Integer seeded = SolanaSplTokenMetadataRegistry.decimals(mint);
            if (seeded != null && seeded > 0) {
                return raw.movePointLeft(seeded);
            }
            return raw;
        }
        return raw.movePointLeft(decimals);
    }

    private static List<Document> docList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        Object val = parent.get(key);
        if (val instanceof List<?> list) {
            List<Document> result = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Document doc) {
                    result.add(doc);
                }
            }
            return result;
        }
        return List.of();
    }

    private NormalizedTransactionType resolveRaydiumAmmType(String heliusType) {
        if (heliusType.contains("ADD_LIQUIDITY")) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (heliusType.contains("REMOVE_LIQUIDITY")) {
            return NormalizedTransactionType.LP_EXIT;
        }
        return NormalizedTransactionType.SWAP;
    }

    private NormalizedTransactionType resolveStakingType(String heliusType) {
        if (heliusType.contains("UNSTAKE") || heliusType.contains("WITHDRAW") || heliusType.contains("DEACTIVATE")) {
            return NormalizedTransactionType.STAKING_WITHDRAW;
        }
        return NormalizedTransactionType.STAKING_DEPOSIT;
    }

    private String capitalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String lower = source.toLowerCase().replace("_", " ");
        if (lower.isEmpty()) {
            return lower;
        }
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
