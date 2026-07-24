package com.walletradar.application.normalization.pipeline.solana;

/**
 * Well-known Solana program IDs used during normalization classification.
 *
 * <p>Classification is program-ID-first; Helius source/type labels serve only as hints
 * when the program ID is ambiguous (e.g. system-level swap via aggregator).</p>
 */
public final class SolanaProgramIds {

    // --- Jupiter ---
    public static final String JUPITER_SWAP_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4";
    public static final String JUPITER_SWAP_V4 = "JUP4Fb2cqiRUcaTHdrPC8h2gNsA2ETXiPDD33WcGuJB";
    /**
     * Jupiter Order Engine (RFQ) — fills a taker order against a market-maker maker in a single
     * transaction. This is a <em>swap</em> program (taker sends one asset, receives another, often
     * native SOL); it is <b>not</b> a yield vault. Verified: constants.rs / jup-ag/rfq-webhook-toolkit.
     */
    public static final String JUPITER_RFQ_ORDER_ENGINE = "61DFfeTKM7trxYcPQCM78bJ794ddZprZpAwAnLiwTpYH";
    /**
     * Jupiter Lend program IDs (borrow / earn / liquidity sub-programs) — <b>derived from
     * {@code protocol-registry.json}</b>, which is the single source of truth (WS-1 DoD / WS-8b).
     * Jupiter Lend spreads a single logical action across these cooperating programs, so
     * classification recognises any of them rather than a single hardcoded router constant.
     */
    public static final java.util.Set<String> JUPITER_LEND_PROGRAM_IDS =
            SolanaProtocolPrograms.jupiterLendProgramIds();
    /**
     * Jupiter Lend borrow router. Kept only as a readable anchor for tests/comments; its membership
     * in {@link #JUPITER_LEND_PROGRAM_IDS} (the authoritative registry-derived set) is asserted at
     * load, so it can never drift from {@code protocol-registry.json}.
     */
    public static final String JUPITER_LEND = requireRegistered("jupr81YtYssSyPt8jbnGuiWon5f6x9TcDEFxYe3Bdzi");

    // --- Meteora ---
    public static final String METEORA_DLMM = "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo";
    /** Meteora Dynamic Vault (yield). Authoritative per docs.meteora.ag. */
    public static final String METEORA_VAULT = "24Uqj9JCLxUeoC3hGfh5W3s9FM9uCHDS2SG3LYwBpyTi";
    /** Meteora Dynamic AMM (DAMM v1). Authoritative per docs.meteora.ag / verified executable on-chain. */
    public static final String METEORA_DYNAMIC_AMM = "Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EQVn5UaB";
    /** Meteora DAMM v2. Verified executable on-chain. */
    public static final String METEORA_DAMM_V2 = "cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG";

    // --- Raydium ---
    public static final String RAYDIUM_AMM_V4 = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8";
    public static final String RAYDIUM_CLMM = "CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK";
    public static final String RAYDIUM_CPMM = "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C";

    // --- Kamino ---
    public static final String KAMINO_LEND = "KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD";
    public static final String KAMINO_VAULT = "kvauTFR8qm1dhniz6pYuBZkuene38GjkNbFxHWe4s1o";

    // --- Meteora farming (LP stake / reward) ---
    public static final String METEORA_FARM = "FarmuwXPWXvefWUeqFAa5w6rifLkq5X6E8bimYvrhCB1";

    // --- Hawksight (automation layer over Meteora) ---
    public static final String HAWKSIGHT = "FqGg2Y1FNxMiGd51Q6UETixQWkF5fB92MysbYogRJb3P";

    // --- Metaplex Bubblegum (compressed NFT mint / transfer) ---
    public static final String BUBBLEGUM = "BGUMAp9Gq7iTEuizy4pqaxsTyUCBK68MDfK752saRPUY";

    // --- Other swap aggregators ---
    public static final String DFLOW = "DF1ow4tspfHX9JwWJsAb9epbkA8hmpSEAtxXy1V27QBH";
    public static final String OKX_DEX_ROUTER = "routeUGWgWzqBWFcrCfv8tritsqukccJPu3q5GPP3xS";

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

    // --- Liquid staking ---
    public static final String MARINADE = "MarBmsSgKXdrN1egZf5sqe1TMai9K1rChYNDJgjq7aD";
    public static final String JITO_STAKE_POOL = "Jito4APyf642JPZPx3hGc6WWJ8zPKtRbRs4P815Awbb";

    // --- Notable mints ---
    /** Wrapped SOL (wSOL) — also used as the native SOL asset contract for pricing. */
    public static final String WSOL_MINT = "So11111111111111111111111111111111111111112";
    public static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    public static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";

    /**
     * SPL mints treated as USD-stable borrowable/quote assets on Solana. Mirrors the
     * {@code SOLANA.usd-stable-contracts} network-descriptor set; used by lending net-flow
     * classification to tell a borrowed stablecoin apart from the SOL collateral and from the
     * protocol position-receipt token.
     */
    public static final java.util.Set<String> SOLANA_USD_STABLE_MINTS = java.util.Set.of(USDC_MINT, USDT_MINT);

    /**
     * Fail-fast guard: the named program constant must be present in the registry-derived
     * {@link #JUPITER_LEND_PROGRAM_IDS} set so the code anchor and {@code protocol-registry.json}
     * cannot silently diverge.
     */
    private static String requireRegistered(String programId) {
        if (!JUPITER_LEND_PROGRAM_IDS.contains(programId)) {
            throw new IllegalStateException(
                    "Jupiter Lend program " + programId + " is missing from protocol-registry.json");
        }
        return programId;
    }

    private SolanaProgramIds() {
    }
}
