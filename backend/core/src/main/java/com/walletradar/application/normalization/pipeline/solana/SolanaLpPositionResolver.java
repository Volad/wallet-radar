package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.platform.networks.solana.SolanaChain;
import org.bson.Document;

import java.util.List;

/**
 * Resolves a deterministic {@code lp-position:} correlation id for Solana concentrated-liquidity
 * positions so that an {@code LP_ENTRY} and its later {@code LP_EXIT} link into the same
 * position-scoped basis pool during AVCO replay (see {@code LpReceiptEntryReplayHandler} and
 * {@code PositionScopedLpExitReplayHandler}).
 *
 * <p><b>Meteora DLMM positions are NFT-based</b> — there is no fungible LP token minted to the
 * wallet, so entry↔exit continuity cannot rely on a receipt-token flow. Instead the position is
 * identified by its on-chain <b>position account (PDA)</b>, which is passed as the first account of
 * the Meteora DLMM {@code addLiquidity*} / {@code removeLiquidity*} instructions. The same PDA
 * appears on the entry and the exit, giving a stable, deterministic, rerun-safe identity with no
 * additional RPC.</p>
 *
 * <p><b>LbPair pool capture.</b> {@code accounts[1]} of the same largest DLMM liquidity instruction
 * is the <b>LbPair pool address</b> (layout {@code [position, lbPair, ...]}). Unlike the per-user
 * position PDA — which is deallocated and its rent reclaimed on close — the LbPair pool account is
 * shared across the pool and persists on-chain indefinitely. It is captured here
 * ({@link #resolveLpPoolAddress}) and persisted on the normalized row so LP enrichment can resolve
 * the SOL/&lt;SPL&gt; token pair for a later-<b>closed</b> single-sided position (whose PDA can no
 * longer be decoded), and can skip a read-path RPC decode for open positions. The LbPair is
 * auxiliary metadata only — the correlation id stays keyed on the position PDA so basis-pool
 * continuity is unaffected.</p>
 *
 * <p>The correlation id format mirrors the EVM CL-NFT scheme
 * ({@code lp-position:<network>:<manager>:<tokenId>}) so it is consumed unchanged by
 * {@code LpReceiptSymbolSupport.fromLpPositionCorrelation}:
 * {@code lp-position:solana:meteora-dlmm:<positionPda>}.</p>
 *
 * <p><b>Scope</b>: only <b>direct</b> Meteora DLMM and Raydium CLMM interactions (where the wallet
 * itself calls the AMM program) are resolved. <b>Hawksight-managed positions are explicitly
 * excluded</b> (RC-S-LP UNSUPPORTED_SCOPE): Hawksight is an automation/custody wrapper whose vault
 * PDA owns the concentrated-liquidity position, and the Meteora DLMM {@code addLiquidity} /
 * {@code removeLiquidity} runs as an <b>inner CPI under the Hawksight program</b>, not as a
 * top-level wallet instruction. Because {@link SolanaRawTransactionView#flattenedInstructions()}
 * walks inner instructions, the DLMM leg is still visible here — so the resolver must detect the
 * Hawksight wrapper and fall through to {@code null} rather than fabricate a wallet-scoped position
 * identity. Fabricating one produced the phantom "open" DLMM positions whose per-position basis
 * pool never drained (the Hawksight rebalance/close credits the wallet only rent, so flow-shape
 * inference booked it as a fee-only {@code LP_ENTRY} instead of an {@code LP_EXIT}). Meteora
 * Dynamic AMM is likewise not resolved here. Excluded rows ride the generic family-continuity
 * bucket (no phantom disposal/acquisition).</p>
 *
 * <p><b>Raydium CLMM position identity.</b> Raydium CLMM positions are NFT-based, exactly like
 * Meteora DLMM. The position is identified by its <b>position NFT token account</b> (the ATA holding
 * the supply-1 position NFT), which is {@code accounts[1]} of both {@code increaseLiquidity*} and
 * {@code decreaseLiquidity*} (layout {@code [nftOwner, nftAccount, ...]} — verified against the
 * Raydium CLMM / amm-v3 IDL). The same NFT account appears on the entry (increase) and the exit
 * (decrease), giving stable, deterministic, rerun-safe per-position continuity with no read-path RPC.
 * When the position is created and funded in a single {@code openPosition*} call (layout
 * {@code [payer, positionNftOwner, positionNftMint, positionNftAccount, ...]} where payer and owner
 * are the wallet), the same NFT account is read from {@code accounts[3]}. The correlation id
 * ({@code lp-position:solana:raydium-clmm:<nftAccount>}) is consumed unchanged by
 * {@code LpReceiptSymbolSupport.fromLpPositionCorrelation}. {@code closePosition}-only management
 * legs are below the liquidity-account threshold and fall through to {@code null}.</p>
 */
public final class SolanaLpPositionResolver {

    private static final String METEORA_DLMM_CORRELATION_PREFIX = "lp-position:solana:meteora-dlmm:";
    private static final String RAYDIUM_CLMM_CORRELATION_PREFIX = "lp-position:solana:raydium-clmm:";
    /**
     * ADR-081 (C1): Meteora Dynamic AMM (DAMM v1) issues a <b>fungible</b> {@code MLP} receipt token
     * (unlike NFT-based DLMM/CLMM), so continuity cannot key on a per-position PDA. The pool address
     * is a stable, deterministic per-pool identity that appears on both the entry and the exit; it is
     * combined with the wallet (fungible receipts are held per wallet) to key the receipt basis pool:
     * {@code lp-position:solana:meteora-damm:{poolAddress}:{walletLower}}.
     */
    private static final String METEORA_DAMM_CORRELATION_PREFIX = "lp-position:solana:meteora-damm:";

    /**
     * Minimum account count of a Meteora DAMM v1 balanced liquidity instruction
     * ({@code addBalanceLiquidity} / {@code removeBalanceLiquidity}, layout
     * {@code [pool, lpMint, userPoolLp, aVaultLp, bVaultLp, aVault, bVault, aTokenVault, bTokenVault,
     * aVaultLpMint, bVaultLpMint, userAToken, userBToken, user, tokenProgram, vaultProgram]} — ~16
     * accounts, verified against the MeteoraAg/damm-v1-sdk IDL). Distinguishes the economic liquidity
     * leg from smaller management legs.
     */
    private static final int MIN_DAMM_LIQUIDITY_ACCOUNTS = 12;
    /** {@code accounts[0]} of a DAMM v1 liquidity leg = the pool (PDA) — the per-pool identity. */
    private static final int DAMM_POOL_ACCOUNT_INDEX = 0;
    /** {@code accounts[1]} of a DAMM v1 liquidity leg = the pool LP mint (the fungible MLP receipt). */
    private static final int DAMM_LP_MINT_ACCOUNT_INDEX = 1;
    /** {@code accounts[2]} of a DAMM v1 liquidity leg = the user pool-LP token account (userPoolLp). */
    private static final int DAMM_USER_POOL_LP_ACCOUNT_INDEX = 2;

    /**
     * Minimum account count that distinguishes a Meteora DLMM {@code addLiquidity*} /
     * {@code removeLiquidity*} instruction (16-17 accounts) from smaller position-management
     * instructions such as {@code initializePosition} / {@code closePosition} (8 accounts). The
     * liquidity instruction is the economic leg whose first account is the position PDA.
     */
    private static final int MIN_LIQUIDITY_INSTRUCTION_ACCOUNTS = 10;

    /**
     * Minimum account count that distinguishes a Raydium CLMM liquidity leg
     * ({@code increaseLiquidity*} / {@code decreaseLiquidity*} / {@code openPosition*}, 13-16
     * accounts) from smaller management legs such as {@code closePosition} (~5-6 accounts), which
     * carry a different account layout and must not be mined for a position identity.
     */
    private static final int MIN_RAYDIUM_CLMM_LIQUIDITY_ACCOUNTS = 10;

    /** {@code accounts[1]} of a Raydium CLMM increase/decrease-liquidity leg = position NFT account. */
    private static final int RAYDIUM_CLMM_LIQUIDITY_NFT_ACCOUNT_INDEX = 1;
    /** {@code accounts[3]} of a Raydium CLMM open-position leg = position NFT account. */
    private static final int RAYDIUM_CLMM_OPEN_POSITION_NFT_ACCOUNT_INDEX = 3;

    /**
     * {@code accounts[1]} of a Meteora DLMM {@code addLiquidityByStrategy*} / {@code removeLiquidityByRange*}
     * liquidity leg = the <b>LbPair pool</b> address (layout {@code [position, lbPair, ...]}). The
     * LbPair pool account is shared across all positions in the pool and persists on-chain even after
     * an individual user's position PDA is closed, so capturing it at normalization time is the only
     * reliable way to resolve the token pair of a later-closed single-sided position.
     */
    private static final int METEORA_DLMM_LBPAIR_ACCOUNT_INDEX = 1;

    private SolanaLpPositionResolver() {
    }

    /**
     * The resolved on-chain position and its {@code lp-position:} correlation id. {@code account} is
     * the bare position identity (Meteora DLMM position PDA or Raydium CLMM position NFT account)
     * used both to build the correlation id and to look the account up in {@code accountData} when
     * deciding full-position closure.
     */
    private record ResolvedPosition(String correlationId, String account) {
    }

    /**
     * Returns the position-scoped {@code lp-position:} correlation id for a direct Meteora DLMM or
     * Raydium CLMM liquidity interaction at the wallet level, otherwise {@code null}.
     */
    public static String resolveCorrelationId(SolanaRawTransactionView view) {
        ResolvedPosition resolved = resolvePosition(view);
        return resolved == null ? null : resolved.correlationId();
    }

    /**
     * The Meteora DLMM <b>LbPair pool address</b> captured from {@code accounts[1]} of the largest
     * DLMM liquidity instruction (the same instruction whose {@code accounts[0]} is the position PDA),
     * or {@code null} when this is not a wallet-level Meteora DLMM liquidity interaction (Raydium CLMM,
     * Hawksight-wrapped, or no plausible LbPair). Persisted on the normalized LP row so enrichment can
     * resolve the SOL/&lt;SPL&gt; pair for a later-closed position whose position PDA is deallocated.
     * The correlation id is unchanged — this is auxiliary metadata, not part of the position identity.
     */
    public static String resolveLpPoolAddress(SolanaRawTransactionView view) {
        if (view == null || isHawksightManaged(view)) {
            return null;
        }
        MeteoraDlmmPosition dlmm = resolveMeteoraDlmm(view);
        return dlmm == null ? null : dlmm.lbPair();
    }

    /**
     * True when this transaction fully closes the resolved concentrated-liquidity position, i.e. the
     * position account itself is deallocated (its rent lamports are reclaimed) within the same
     * transaction. This is the network-neutral analogue of the EVM position-NFT burn
     * ({@code LpPrincipalCloseEvidence.isFullPositionClose}): a Meteora DLMM position PDA or a
     * Raydium CLMM position NFT account only loses lamports when it is closed, so a strictly-negative
     * {@code nativeBalanceChange} on that exact account is deterministic, rerun-safe evidence of full
     * closure. A <b>partial</b> remove-liquidity leaves the position account open
     * ({@code nativeBalanceChange == 0}), so residual per-asset basis legitimately stays parked until
     * the terminal close. Program/account derived only — never keyed on a wallet or a specific tx.
     */
    public static boolean isFullPositionClose(SolanaRawTransactionView view) {
        ResolvedPosition resolved = resolvePosition(view);
        if (resolved == null) {
            return false;
        }
        return isPositionAccountRentReclaimed(view, resolved.account());
    }

    private static ResolvedPosition resolvePosition(SolanaRawTransactionView view) {
        if (view == null || isHawksightManaged(view)) {
            return null;
        }
        MeteoraDlmmPosition dlmmPosition = resolveMeteoraDlmm(view);
        if (dlmmPosition != null) {
            return new ResolvedPosition(
                    METEORA_DLMM_CORRELATION_PREFIX + dlmmPosition.position(), dlmmPosition.position());
        }
        String clmmPosition = resolveRaydiumClmmPosition(view);
        if (clmmPosition != null) {
            return new ResolvedPosition(RAYDIUM_CLMM_CORRELATION_PREFIX + clmmPosition, clmmPosition);
        }
        // ADR-081 (C1): Meteora DAMM v1 fungible-MLP position. Keyed on {pool}:{walletLower} because
        // the MLP receipt is fungible (not a per-position PDA/NFT). Full-close is detected on the user
        // pool-LP token account (userPoolLp), not the pool (which is shared and persists on-chain).
        MeteoraDammPosition dammPosition = resolveMeteoraDamm(view);
        if (dammPosition != null) {
            String wallet = view.walletAddress();
            if (wallet != null && !wallet.isBlank()) {
                // Solana wallet addresses are case-sensitive base58 — preserve case (never lowercase,
                // which would corrupt the identity). Only EVM 0x-hex wallet segments are lowercased.
                String correlationId = METEORA_DAMM_CORRELATION_PREFIX
                        + dammPosition.pool() + ":" + wallet.trim();
                return new ResolvedPosition(correlationId, dammPosition.userPoolLp());
            }
        }
        return null;
    }

    /**
     * True when {@code positionAccount} appears in {@code accountData} with a strictly-negative
     * {@code nativeBalanceChange} — the on-chain signature of an account being closed and its rent
     * refunded. Program-owned position PDAs / NFT token accounts never pay fees, so a negative
     * lamport delta on that exact account can only be a close.
     */
    private static boolean isPositionAccountRentReclaimed(SolanaRawTransactionView view, String positionAccount) {
        if (positionAccount == null || positionAccount.isBlank()) {
            return false;
        }
        for (Document account : view.accountData()) {
            if (account == null || !positionAccount.equals(account.getString("account"))) {
                continue;
            }
            if (nativeBalanceChange(account) < 0) {
                return true;
            }
        }
        return false;
    }

    private static long nativeBalanceChange(Document account) {
        Object value = account.get("nativeBalanceChange");
        if (value instanceof Number num) {
            return num.longValue();
        }
        return 0L;
    }

    /**
     * True when the transaction is a Hawksight-managed automation over Meteora DLMM (RC-S-LP
     * UNSUPPORTED_SCOPE). Hawksight owns the position PDA inside its vault and invokes the Meteora
     * DLMM liquidity instruction as an inner CPI under its own program, so the wallet never directly
     * holds a per-position identity: entry funds move wallet→vault, and rebalances/closes credit the
     * wallet only rent (near-zero net flow). Resolving a position here would fabricate a phantom
     * wallet-scoped position whose basis pool is never drained. Detection is general and
     * program/label based — the registered Hawksight program id and the Helius {@code HAWKSIGHT}
     * source label — never a wallet or vault address.
     */
    private static boolean isHawksightManaged(SolanaRawTransactionView view) {
        return view.hasProgramId(SolanaProtocolPrograms.HAWKSIGHT_ID)
                || "HAWKSIGHT".equals(view.heliusSource());
    }

    /**
     * The Raydium CLMM position NFT account — {@code accounts[1]} of the largest Raydium CLMM
     * increase/decrease-liquidity leg (layout {@code [nftOwner==wallet, nftAccount, ...]}), or
     * {@code accounts[3]} of an {@code openPosition*} leg (layout
     * {@code [payer==wallet, positionNftOwner==wallet, positionNftMint, positionNftAccount, ...]}).
     * Both variants resolve to the same NFT account, so an entry and its later exit share one
     * correlation. Returns {@code null} when no economic liquidity leg is present at the wallet level.
     */
    private static String resolveRaydiumClmmPosition(SolanaRawTransactionView view) {
        if (view == null) {
            return null;
        }
        String wallet = view.walletAddress();
        if (wallet == null || wallet.isBlank()) {
            return null;
        }
        String position = null;
        int bestAccountCount = -1;
        for (Document instruction : view.flattenedInstructions()) {
            String programId = instruction.getString("programId");
            if (programId == null || !SolanaProtocolPrograms.RAYDIUM_CLMM_ID.equals(programId.trim())) {
                continue;
            }
            List<String> accounts = SolanaRawTransactionView.instructionAccounts(instruction);
            if (accounts.size() < MIN_RAYDIUM_CLMM_LIQUIDITY_ACCOUNTS) {
                continue;
            }
            String candidate = raydiumClmmNftAccount(accounts, wallet);
            if (candidate == null) {
                continue;
            }
            if (accounts.size() > bestAccountCount) {
                bestAccountCount = accounts.size();
                position = candidate;
            }
        }
        return position;
    }

    /**
     * Reads the Raydium CLMM position NFT account from a liquidity leg. {@code accounts[0]} is always
     * the wallet (nftOwner / payer). For increase/decrease liquidity, {@code accounts[1]} is the NFT
     * account (not the wallet). For a combined open-position leg the wallet also occupies
     * {@code accounts[1]} (positionNftOwner), and the NFT account is at {@code accounts[3]}.
     */
    private static String raydiumClmmNftAccount(List<String> accounts, String wallet) {
        if (!wallet.equals(accounts.get(0))) {
            return null;
        }
        String liquidityNftAccount = accounts.get(RAYDIUM_CLMM_LIQUIDITY_NFT_ACCOUNT_INDEX);
        if (!wallet.equals(liquidityNftAccount) && isPlausiblePositionPda(liquidityNftAccount)) {
            return liquidityNftAccount;
        }
        // open-position layout: payer and positionNftOwner both == wallet, NFT account at index 3.
        if (wallet.equals(liquidityNftAccount)
                && accounts.size() > RAYDIUM_CLMM_OPEN_POSITION_NFT_ACCOUNT_INDEX) {
            String openNftAccount = accounts.get(RAYDIUM_CLMM_OPEN_POSITION_NFT_ACCOUNT_INDEX);
            if (!wallet.equals(openNftAccount) && isPlausiblePositionPda(openNftAccount)) {
                return openNftAccount;
            }
        }
        return null;
    }

    /**
     * A resolved Meteora DLMM interaction: the position PDA ({@code accounts[0]}) and the LbPair pool
     * address ({@code accounts[1]}) of the largest DLMM liquidity instruction. The {@code lbPair} may
     * be {@code null} when no plausible pool account is present (guarded), but {@code position} is
     * always non-null when this record is produced.
     */
    private record MeteoraDlmmPosition(String position, String lbPair) {
    }

    /**
     * Resolves the Meteora DLMM position PDA — the first account of the largest DLMM liquidity
     * instruction — together with the LbPair pool address at {@code accounts[1]} of that same
     * instruction.
     *
     * <p>Both {@code addLiquidityByStrategy} (entry) and {@code removeLiquidityByRange} (exit) carry
     * the position PDA at {@code accounts[0]} and the LbPair pool at {@code accounts[1]}, and are the
     * largest DLMM instructions in the transaction (they enumerate bin arrays plus both token vaults).
     * Selecting the max-account instruction avoids the smaller {@code claimFee} instruction, whose
     * {@code accounts[0]} is the pool rather than the position.</p>
     */
    private static MeteoraDlmmPosition resolveMeteoraDlmm(SolanaRawTransactionView view) {
        if (view == null) {
            return null;
        }
        String position = null;
        String lbPair = null;
        int bestAccountCount = -1;
        for (Document instruction : view.flattenedInstructions()) {
            String programId = instruction.getString("programId");
            if (programId == null || !SolanaProtocolPrograms.METEORA_DLMM_ID.equals(programId.trim())) {
                continue;
            }
            List<String> accounts = SolanaRawTransactionView.instructionAccounts(instruction);
            if (accounts.size() < MIN_LIQUIDITY_INSTRUCTION_ACCOUNTS) {
                continue;
            }
            String candidate = accounts.get(0);
            if (!isPlausiblePositionPda(candidate)) {
                continue;
            }
            if (accounts.size() > bestAccountCount) {
                bestAccountCount = accounts.size();
                position = candidate;
                lbPair = resolvePlausibleLbPair(accounts, candidate);
            }
        }
        return position == null ? null : new MeteoraDlmmPosition(position, lbPair);
    }

    /**
     * ADR-081 (C1): the fungible MLP receipt mint (DAMM v1 {@code lpMint}, {@code accounts[1]}) of a
     * direct wallet-level Meteora DAMM liquidity interaction, or {@code null} when this is not a DAMM
     * liquidity tx. Used at normalization to flag the wallet's MLP receipt leg as an LP receipt (so
     * its ledger point carries {@code FAMILY:LP_RECEIPT} rather than the confusable pool-mint family),
     * driven by LP-correlation membership — not by the confusable {@code MLP} symbol.
     */
    public static String resolveLpReceiptMint(SolanaRawTransactionView view) {
        MeteoraDammPosition damm = resolveMeteoraDamm(view);
        return damm == null ? null : damm.lpMint();
    }

    /**
     * A resolved Meteora DAMM v1 interaction: the pool ({@code accounts[0]}) — the per-pool identity
     * shared across the entry and exit — the LP mint ({@code accounts[1]}, the fungible MLP receipt),
     * and the user pool-LP token account ({@code accounts[2]}, userPoolLp) whose rent reclaim signals
     * a full close (the wallet closed its MLP token account).
     */
    private record MeteoraDammPosition(String pool, String lpMint, String userPoolLp) {
    }

    /**
     * Resolves the Meteora DAMM v1 pool and user pool-LP account from the largest DAMM balanced
     * liquidity instruction (layout {@code [pool, lpMint, userPoolLp, ...]}, verified against the
     * MeteoraAg/damm-v1-sdk IDL). Both {@code addBalanceLiquidity} (entry) and
     * {@code removeBalanceLiquidity} (exit) carry the pool at {@code accounts[0]}, so the entry and
     * exit resolve to one deterministic identity with no read-path RPC. DAMM v2 (a distinct program
     * and account model) is intentionally not resolved here and rides generic family continuity.
     */
    private static MeteoraDammPosition resolveMeteoraDamm(SolanaRawTransactionView view) {
        if (view == null) {
            return null;
        }
        String pool = null;
        String lpMint = null;
        String userPoolLp = null;
        int bestAccountCount = -1;
        for (Document instruction : view.flattenedInstructions()) {
            String programId = instruction.getString("programId");
            if (programId == null || !SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID.equals(programId.trim())) {
                continue;
            }
            List<String> accounts = SolanaRawTransactionView.instructionAccounts(instruction);
            if (accounts.size() < MIN_DAMM_LIQUIDITY_ACCOUNTS) {
                continue;
            }
            String candidatePool = accounts.get(DAMM_POOL_ACCOUNT_INDEX);
            if (!isPlausiblePositionPda(candidatePool)) {
                continue;
            }
            if (accounts.size() > bestAccountCount) {
                bestAccountCount = accounts.size();
                pool = candidatePool;
                String candidateLpMint = accounts.get(DAMM_LP_MINT_ACCOUNT_INDEX);
                lpMint = isPlausiblePositionPda(candidateLpMint) ? candidateLpMint : null;
                String candidateUserPoolLp = accounts.get(DAMM_USER_POOL_LP_ACCOUNT_INDEX);
                userPoolLp = isPlausiblePositionPda(candidateUserPoolLp) ? candidateUserPoolLp : null;
            }
        }
        return pool == null ? null : new MeteoraDammPosition(pool, lpMint, userPoolLp);
    }

    /**
     * The LbPair pool address at {@code accounts[1]} of a Meteora DLMM liquidity leg, subject to the
     * same plausibility guard as the position PDA (never a routed program / system / mint account) and
     * an additional distinctness guard (the pool must differ from the position PDA). Returns
     * {@code null} when the leg is too short or the candidate is implausible, so callers degrade
     * gracefully rather than persisting a bogus pool.
     */
    private static String resolvePlausibleLbPair(List<String> accounts, String positionPda) {
        if (accounts.size() <= METEORA_DLMM_LBPAIR_ACCOUNT_INDEX) {
            return null;
        }
        String pool = accounts.get(METEORA_DLMM_LBPAIR_ACCOUNT_INDEX);
        if (!isPlausiblePositionPda(pool) || pool.equals(positionPda)) {
            return null;
        }
        return pool;
    }

    /**
     * Guards against selecting a well-known program / system account as the position identity. A
     * genuine position PDA is a base58 account address that is never one of the program ids the
     * instruction routes through.
     */
    private static boolean isPlausiblePositionPda(String account) {
        if (account == null || account.isBlank()) {
            return false;
        }
        return switch (account) {
            // W16: chain-runtime system constants live in SolanaChain (platform)
            case SolanaChain.SYSTEM_PROGRAM,
                    SolanaChain.TOKEN_PROGRAM,
                    SolanaChain.TOKEN_2022_PROGRAM,
                    SolanaChain.ASSOCIATED_TOKEN_PROGRAM,
                    SolanaChain.WSOL_MINT -> false;
            default -> !SolanaProtocolPrograms.METEORA_DLMM_ID.equals(account)
                    && !SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID.equals(account);
        };
    }
}
