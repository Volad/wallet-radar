package com.walletradar.platform.networks.solana;

/**
 * Solana chain-runtime constants: well-known program IDs and mint addresses that are defined by
 * the Solana protocol itself (SPL token programs, system programs, wSOL mint). These are
 * chain-runtime standards (§2.2) — code-resident because they are immutable on-chain identifiers,
 * not protocol-registry configuration. Protocol-specific program IDs (Jupiter, Meteora, Raydium,
 * etc.) live in {@code SolanaProtocolPrograms} (loaded from protocol-registry.json).
 *
 * <p>All addresses are case-sensitive base58; never lowercase Solana keys.</p>
 */
public final class SolanaChain {

    // --- Native Solana system programs ---
    public static final String SYSTEM_PROGRAM = "11111111111111111111111111111111";
    public static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    public static final String TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";
    public static final String ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
    public static final String STAKE_PROGRAM = "Stake11111111111111111111111111111111111111";
    public static final String COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111";
    public static final String MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";
    public static final String MEMO_PROGRAM_LEGACY = "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo";

    /**
     * Lighthouse — a runtime <em>assertion</em> program injected by wallets (e.g. Phantom) to guard a
     * transaction against simulation spoofing. It carries no economic wallet flow and must never be
     * treated as a DeFi/vault counterparty; it is ignored like other non-DeFi system programs.
     */
    public static final String LIGHTHOUSE = "L2TExMFKdjpN9kozasaurPirfHy9P8sbXoAN1qA3S95";

    /**
     * Wrapped SOL (wSOL) mint — also used as the native SOL asset contract key for pricing and
     * flow-shape net-delta tracking (native SOL movements are keyed by this mint in classifier/builder
     * maps so SOL and wSOL share one accounting bucket).
     */
    public static final String WSOL_MINT = "So11111111111111111111111111111111111111112";

    private SolanaChain() {
    }
}
