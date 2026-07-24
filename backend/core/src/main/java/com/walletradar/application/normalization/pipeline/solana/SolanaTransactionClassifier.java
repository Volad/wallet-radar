package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.platform.networks.solana.SolanaChain;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Program-ID-first classifier for Solana transactions parsed by the Helius Enhanced API.
 *
 * <p>Rules execute in priority order — the first matching rule wins. Helius {@code type} and
 * {@code source} labels act only as secondary hints when program-ID evidence is ambiguous.</p>
 *
 * <p>Flow-shape computation is delegated to {@link SolanaWalletNetFlow} (net per-mint / native delta
 * from Helius accountData / transfers) and {@link SolanaFlowShape} (LP entry/exit/swap shape,
 * transfer direction, non-DeFi fallback). This class owns only the ordered rule table and the
 * protocol-specific type resolvers.</p>
 */
@Component
public class SolanaTransactionClassifier {

    private static final Set<String> STAKING_WITHDRAW_TYPES = Set.of(
            "UNSTAKE_SOL", "DEACTIVATE_STAKE", "WITHDRAW_STAKE"
    );
    // NOTE: STAKING_DEPOSIT_TYPES removed (E-smell-2 / W17): the former ternary
    //   STAKING_DEPOSIT_TYPES.contains(heliusType) ? STAKING_DEPOSIT : STAKING_DEPOSIT
    // had identical arms — STAKING_DEPOSIT_TYPES never influenced the emitted type.
    // Latent-bug candidate: a future wave may need to distinguish STAKING_WITHDRAW here
    // when heliusType is not in STAKING_WITHDRAW_TYPES but is still a withdrawal signal.
    // Tracked as a separate financial-audit item; not fixed here (behavior-preserving wave).

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
        if (view.hasProgramId(SolanaProtocolPrograms.KAMINO_LEND_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.KAMINO_LEND_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveLendingType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 3. Kamino Vault
        if (view.hasProgramId(SolanaProtocolPrograms.KAMINO_VAULT_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.KAMINO_VAULT_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveVaultType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
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
        if (view.hasProgramId(SolanaProtocolPrograms.METEORA_DLMM_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.METEORA_DLMM_ID).orElseThrow();
            return SolanaClassificationResult.of(SolanaFlowShape.resolveClmmType(view), pc.displayName(), pc.protocolKey(), pc.family());
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
        if (view.hasProgramId(SolanaProtocolPrograms.METEORA_VAULT_ID)
                && !view.hasProgramId(SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID)
                && !view.hasProgramId(SolanaProtocolPrograms.METEORA_DAMM_V2_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.METEORA_VAULT_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveVaultType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 5b. Meteora farming (LP stake / reward harvest)
        if (view.hasProgramId(SolanaProtocolPrograms.METEORA_FARM_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.METEORA_FARM_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveMeteoraFarmType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 6. Hawksight — automation wrapper over Meteora DLMM. Same flow-shape rule as raw DLMM so an
        //    automated remove-liquidity is not mislabeled as an entry. Registry name carries the
        //    display context "Meteora DLMM (via Hawksight)" and the same protocolKey "meteora-dlmm".
        if (view.hasProgramId(SolanaProtocolPrograms.HAWKSIGHT_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.HAWKSIGHT_ID).orElseThrow();
            return SolanaClassificationResult.of(SolanaFlowShape.resolveClmmType(view), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 7. Raydium CLMM — concentrated-liquidity AMM: swap OR LP add/remove. Decide by
        //    instruction/flow shape, never a hard-forced SWAP (which drops an LP leg and blocks AVCO).
        if (view.hasProgramId(SolanaProtocolPrograms.RAYDIUM_CLMM_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.RAYDIUM_CLMM_ID).orElseThrow();
            return SolanaClassificationResult.of(SolanaFlowShape.resolveClmmType(view), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 8. Raydium AMM v4
        if (view.hasProgramId(SolanaProtocolPrograms.RAYDIUM_AMM_V4_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.RAYDIUM_AMM_V4_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveRaydiumAmmType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 9. Raydium CPMM
        if (view.hasProgramId(SolanaProtocolPrograms.RAYDIUM_CPMM_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.RAYDIUM_CPMM_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveRaydiumAmmType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 9b. Meteora Dynamic AMM (DAMM v1 / v2) — constant-product/constant-sum pools that expose a
        //     swap OR LP add/remove. Decide by flow shape so an LP op is never booked as a broken swap.
        //     Both DAMM v1 and v2 emit the same display name / protocolKey per registry.
        if (view.hasProgramId(SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID)
                || view.hasProgramId(SolanaProtocolPrograms.METEORA_DAMM_V2_ID)) {
            String matchedId = view.hasProgramId(SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID)
                    ? SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID
                    : SolanaProtocolPrograms.METEORA_DAMM_V2_ID;
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(matchedId).orElseThrow();
            return SolanaClassificationResult.of(SolanaFlowShape.resolveClmmType(view), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 10. Jupiter swap (aggregator router, RFQ order engine, or helius source). The RFQ Order
        //     Engine fills a taker order against a maker in one tx and frequently delivers native SOL;
        //     the swap flow builder reconstructs both legs from accountData net-by-mint + native delta.
        if (view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V4_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_RFQ_ID)
                || "JUPITER".equals(heliusSource)) {
            boolean rfq = view.hasProgramId(SolanaProtocolPrograms.JUPITER_RFQ_ID);
            String programId = rfq ? SolanaProtocolPrograms.JUPITER_RFQ_ID
                    : (view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)
                            ? SolanaProtocolPrograms.JUPITER_SWAP_V6_ID
                            : SolanaProtocolPrograms.JUPITER_SWAP_V4_ID);
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(programId).orElseThrow();
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 11. Other known aggregators by helius source
        if (OTHER_AGGREGATOR_SOURCES.contains(heliusSource)
                || view.hasProgramId(SolanaProtocolPrograms.DFLOW_ID)
                || view.hasProgramId(SolanaProtocolPrograms.OKX_DEX_ROUTER_ID)
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
        if (view.hasProgramId(SolanaChain.STAKE_PROGRAM)) {
            if (STAKING_WITHDRAW_TYPES.contains(heliusType)) {
                return SolanaClassificationResult.of(
                        NormalizedTransactionType.STAKING_WITHDRAW,
                        "Solana Staking",
                        "solana-staking",
                        "STAKING"
                );
            }
            return SolanaClassificationResult.of(
                    NormalizedTransactionType.STAKING_DEPOSIT,
                    "Solana Staking",
                    "solana-staking",
                    "STAKING"
            );
        }

        // 13. Liquid staking (Marinade, Jito)
        if (view.hasProgramId(SolanaProtocolPrograms.MARINADE_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.MARINADE_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveStakingType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }
        if (view.hasProgramId(SolanaProtocolPrograms.JITO_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.JITO_ID).orElseThrow();
            return SolanaClassificationResult.of(resolveStakingType(heliusType), pc.displayName(), pc.protocolKey(), pc.family());
        }

        // 14. Transfer heuristic (SYSTEM_PROGRAM / SPL token program, no DeFi program).
        //     RC-S5: direction from net wallet delta (IN / OUT / INTERNAL), not a hardcoded IN.
        if ("TRANSFER".equals(heliusType)) {
            return SolanaClassificationResult.transfer(SolanaFlowShape.resolveTransferType(view));
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
        //     Display name and protocolKey come from the registry (Bubblegum entry, no DeFi family).
        if (view.hasProgramId(SolanaProtocolPrograms.BUBBLEGUM_ID) || NFT_MINT_TYPES.contains(heliusType)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.BUBBLEGUM_ID).orElseThrow();
            return SolanaClassificationResult.of(NormalizedTransactionType.NFT_MINT, pc.displayName(), pc.protocolKey(), pc.family());
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
        SolanaClassificationResult nonDefiTransfer = SolanaFlowShape.nonDefiTransferFallback(view);
        if (nonDefiTransfer != null) {
            return nonDefiTransfer;
        }

        // Unknown — flag for review
        return SolanaClassificationResult.unknown();
    }

    /** True when the transaction touches any registry-declared Jupiter Lend program. */
    private boolean touchesJupiterLend(SolanaRawTransactionView view) {
        for (String programId : SolanaProtocolPrograms.jupiterLendProgramIds()) {
            if (view.hasProgramId(programId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * RULE 1 — Jupiter Lend event classification by net asset flow (never by Helius {@code type},
     * which is empty/generic for Jupiter Lend). Decision precedence, using the wallet's net per-mint
     * delta ({@link SolanaWalletNetFlow#walletNetByMint}) where SOL is the collateral, the SOLANA
     * USD-stable mints are the borrowable/quote assets, and the protocol position-receipt token
     * (e.g. jl-SOL) is ignored because it is neither SOL nor a stablecoin:
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
    private NormalizedTransactionType resolveJupiterLendType(SolanaRawTransactionView view) {
        Map<String, BigDecimal> net = SolanaWalletNetFlow.walletNetByMint(view);
        BigDecimal solNet = net.getOrDefault(SolanaChain.WSOL_MINT, BigDecimal.ZERO);
        BigDecimal stableNet = BigDecimal.ZERO;
        for (String mint : NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA)) {
            BigDecimal delta = net.get(mint);
            if (delta != null) {
                stableNet = stableNet.add(delta);
            }
        }
        boolean hasSwap = view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V4_ID)
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
                && (stableNet.signum() != 0
                        || SolanaWalletNetFlow.walletHasStableFlow(view)
                        || !SolanaWalletNetFlow.hasForeignValueSwapLegs(view))) {
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

    private NormalizedTransactionType resolveMeteoraFarmType(String heliusType) {
        if (heliusType.contains("CLAIM") || heliusType.contains("HARVEST") || heliusType.contains("REWARD")) {
            return NormalizedTransactionType.REWARD_CLAIM;
        }
        if (heliusType.contains("WITHDRAW") || heliusType.contains("UNSTAKE") || heliusType.contains("REMOVE")) {
            return NormalizedTransactionType.LP_POSITION_UNSTAKE;
        }
        return NormalizedTransactionType.LP_POSITION_STAKE;
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

    /**
     * True when the transaction touches a dedicated swap-router / aggregator program. These programs
     * are exclusively swap venues (an AMM/CLMM pool they CPI is the swap execution venue, not an LP
     * add/remove), so a transaction touching one is a swap even when it routes through a Meteora DLMM
     * or Raydium CLMM pool.
     */
    private boolean touchesSwapRouter(SolanaRawTransactionView view) {
        return view.hasProgramId(SolanaProtocolPrograms.OKX_DEX_ROUTER_ID)
                || view.hasProgramId(SolanaProtocolPrograms.DFLOW_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V4_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_RFQ_ID);
    }

    /**
     * Builds the {@code SWAP} classification result for a routed-swap program, reusing the protocol
     * naming of rules 10 (Jupiter) and 11 (source-labelled aggregators): Jupiter RFQ order engine →
     * "Jupiter RFQ"; Jupiter swap v6/v4 → "Jupiter"; OKX/DFLOW → the capitalized Helius source when
     * present (e.g. a Raydium-routed OKX swap → "Raydium"), else the router's own name.
     */
    private SolanaClassificationResult routedSwapResult(SolanaRawTransactionView view) {
        if (view.hasProgramId(SolanaProtocolPrograms.JUPITER_RFQ_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.JUPITER_RFQ_ID).orElseThrow();
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, pc.displayName(), pc.protocolKey(), pc.family());
        }
        if (view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)
                || view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V4_ID)) {
            String id = view.hasProgramId(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)
                    ? SolanaProtocolPrograms.JUPITER_SWAP_V6_ID
                    : SolanaProtocolPrograms.JUPITER_SWAP_V4_ID;
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(id).orElseThrow();
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, pc.displayName(), pc.protocolKey(), pc.family());
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
        if (view.hasProgramId(SolanaProtocolPrograms.OKX_DEX_ROUTER_ID)) {
            SolanaProgramClass pc = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.OKX_DEX_ROUTER_ID).orElseThrow();
            return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, pc.displayName(), pc.protocolKey(), pc.family());
        }
        // DFLOW fallback (heliusSource is blank/UNKNOWN and only DFLOW program present)
        SolanaProgramClass dflow = SolanaProtocolPrograms.classify(SolanaProtocolPrograms.DFLOW_ID).orElseThrow();
        return SolanaClassificationResult.of(NormalizedTransactionType.SWAP, dflow.displayName(), dflow.protocolKey(), dflow.family());
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
