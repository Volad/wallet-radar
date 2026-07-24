package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaLpPositionResolverTest {

    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
    private static final String POSITION = "H7wY3yb9LfJYv98yxfyqpPeco3ezKFE5n8VQKRcooe9w";
    private static final String POOL = "CgqwPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXaqY";

    private static SolanaRawTransactionView view(Document heliusParsed) {
        RawTransaction r = new RawTransaction();
        r.setId("sig:SOLANA:" + WALLET);
        r.setTxHash("sig");
        r.setWalletAddress(WALLET);
        r.setNetworkId("SOLANA");
        r.setRawData(new Document("source", "HELIUS_ENHANCED").append("heliusParsed", heliusParsed));
        return SolanaRawTransactionView.wrap(r);
    }

    private static Document instruction(String programId, List<String> accounts) {
        return new Document("programId", programId).append("accounts", accounts);
    }

    @Test
    @DisplayName("Resolves DLMM position PDA from accounts[0] of the add-liquidity instruction")
    void resolvesPositionFromAddLiquidity() {
        Document parsed = new Document("type", "ADD_LIQUIDITY_BY_STRATEGY").append("instructions", List.of(
                instruction("ComputeBudget111111111111111111111111111111", List.of()),
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                        "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, SolanaProgramIds.USDC_MINT,
                        "binArrayLower", "binArrayUpper", WALLET))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:meteora-dlmm:" + POSITION);
    }

    @Test
    @DisplayName("Captures the LbPair pool address from accounts[1] of the add-liquidity instruction")
    void capturesLbPairFromAccountsIndexOne() {
        Document parsed = new Document("type", "ADD_LIQUIDITY_BY_STRATEGY").append("instructions", List.of(
                instruction("ComputeBudget111111111111111111111111111111", List.of()),
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                        "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, SolanaProgramIds.USDC_MINT,
                        "binArrayLower", "binArrayUpper", WALLET))));

        assertThat(SolanaLpPositionResolver.resolveLpPoolAddress(view(parsed))).isEqualTo(POOL);
    }

    @Test
    @DisplayName("LbPair capture uses accounts[1] of the largest (liquidity) instruction, not claimFee")
    void capturesLbPairFromLargestLiquidityInstruction() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE").append("instructions", List.of(
                // removeLiquidityByRange (16 accounts) — accounts[0]=position, accounts[1]=pool
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9",
                        "a10", "a11", "a12", "a13", "a14", "a15")),
                // claimFee (14 accounts) — accounts[0]=pool, accounts[1]=position; must not be chosen
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POOL, POSITION, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9",
                        "a10", "a11", "a12", "a13"))));

        assertThat(SolanaLpPositionResolver.resolveLpPoolAddress(view(parsed))).isEqualTo(POOL);
    }

    @Test
    @DisplayName("LbPair guard: a program/system account at accounts[1] is not captured")
    void doesNotCaptureImplausibleLbPair() {
        // accounts[1] is a routed program id, not a genuine pool — the plausibility guard rejects it.
        Document parsed = new Document("type", "ADD_LIQUIDITY_BY_STRATEGY").append("instructions", List.of(
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, SolanaProgramIds.TOKEN_PROGRAM, "a2", "a3", "a4", "a5", "a6", "a7",
                        "a8", "a9", "a10"))));

        assertThat(SolanaLpPositionResolver.resolveLpPoolAddress(view(parsed))).isNull();
    }

    @Test
    @DisplayName("LbPair guard: accounts[1] identical to the position PDA is not captured")
    void doesNotCaptureLbPairEqualToPosition() {
        Document parsed = new Document("type", "ADD_LIQUIDITY_BY_STRATEGY").append("instructions", List.of(
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, POSITION, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"))));

        assertThat(SolanaLpPositionResolver.resolveLpPoolAddress(view(parsed))).isNull();
    }

    @Test
    @DisplayName("Raydium CLMM interaction captures no LbPair (Meteora-only capture)")
    void raydiumCapturesNoLbPair() {
        Document parsed = new Document("type", "SWAP").append("instructions", List.of(
                instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                        WALLET, RAYDIUM_NFT_ACCOUNT, RAYDIUM_POOL_STATE, "protocolPosition",
                        "personalPosition", "tickLower", "tickUpper", "tokenAcc0", "tokenAcc1",
                        "tokenVault0", "tokenVault1", SolanaProgramIds.TOKEN_PROGRAM))));

        assertThat(SolanaLpPositionResolver.resolveLpPoolAddress(view(parsed))).isNull();
    }

    @Test
    @DisplayName("Hawksight-wrapped DLMM captures no LbPair (excluded scope)")
    void hawksightCapturesNoLbPair() {
        Document inner = instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"));
        Document outer = new Document("programId", SolanaProgramIds.HAWKSIGHT)
                .append("accounts", List.of(WALLET))
                .append("innerInstructions", List.of(inner));
        Document parsed = new Document("type", "EXTENSION_EXECUTE")
                .append("source", "HAWKSIGHT")
                .append("instructions", List.of(outer));

        assertThat(SolanaLpPositionResolver.resolveLpPoolAddress(view(parsed))).isNull();
    }

    @Test
    @DisplayName("Picks the position over the pool when a smaller claimFee instruction is present")
    void picksPositionOverClaimFeePool() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE").append("instructions", List.of(
                // removeLiquidityByRange (16 accounts) — accounts[0] = position
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9",
                        "a10", "a11", "a12", "a13", "a14", "a15")),
                // claimFee (14 accounts) — accounts[0] = pool, accounts[1] = position
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POOL, POSITION, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9",
                        "a10", "a11", "a12", "a13")),
                // closePosition (8 accounts) — below the liquidity-instruction threshold
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7"))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:meteora-dlmm:" + POSITION);
    }

    @Test
    @DisplayName("Recurses into inner instructions to find a DLMM liquidity call under a non-wrapper router")
    void resolvesFromInnerInstruction() {
        // A non-custody router (not Hawksight) that CPIs into Meteora DLMM at the wallet level still
        // resolves to a position — the flattened traversal must keep finding inner liquidity legs.
        Document inner = instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"));
        Document outer = new Document("programId", SolanaProgramIds.JUPITER_SWAP_V6)
                .append("accounts", List.of(WALLET))
                .append("innerInstructions", List.of(inner));
        Document parsed = new Document("type", "ADD_LIQUIDITY").append("instructions", List.of(outer));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:meteora-dlmm:" + POSITION);
    }

    @Test
    @DisplayName("Hawksight-wrapped DLMM (inner CPI under the Hawksight program) resolves to null")
    void hawksightWrappedInnerDlmmResolvesToNull() {
        // RC-S-LP UNSUPPORTED_SCOPE: the Hawksight program invokes the Meteora DLMM add/remove
        // liquidity as an inner CPI. Even though flattenedInstructions() exposes the DLMM leg (with
        // the position PDA at accounts[0]), the position is owned by the Hawksight vault, not the
        // wallet — fabricating a per-position identity here produced the phantom "open" positions
        // whose basis pool never drained. Must fall through to the generic family-continuity bucket.
        Document inner = instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"));
        Document outer = new Document("programId", SolanaProgramIds.HAWKSIGHT)
                .append("accounts", List.of(WALLET))
                .append("innerInstructions", List.of(inner));
        Document parsed = new Document("type", "EXTENSION_EXECUTE")
                .append("source", "HAWKSIGHT")
                .append("instructions", List.of(outer));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed))).isNull();
    }

    @Test
    @DisplayName("Hawksight-routed DLMM entry (TRANSFER/SYSTEM_PROGRAM top-level Hawksight program) resolves to null")
    void hawksightRoutedEntryResolvesToNull() {
        // The phantom-position ENTRY leg: Helius labels it TRANSFER/SYSTEM_PROGRAM, but the wallet's
        // deposit is routed through the Hawksight program (top-level) which CPIs the DLMM add. The
        // Hawksight program presence alone must suppress the position identity so the entry and its
        // later Hawksight close both ride generic family continuity (symmetric, no stuck pool).
        Document dlmmInner = instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                POSITION, POOL, "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"));
        Document hawksight = new Document("programId", SolanaProgramIds.HAWKSIGHT)
                .append("accounts", List.of(WALLET, "vault"))
                .append("innerInstructions", List.of(dlmmInner));
        Document parsed = new Document("type", "TRANSFER")
                .append("source", "SYSTEM_PROGRAM")
                .append("instructions", List.of(hawksight));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed))).isNull();
    }

    @Test
    @DisplayName("Returns null when no DLMM liquidity instruction is present (Hawksight-internal shape)")
    void returnsNullWithoutDlmmInstruction() {
        Document parsed = new Document("type", "EXTENSION_EXECUTE").append("instructions", List.of(
                instruction(SolanaProgramIds.HAWKSIGHT, List.of(WALLET, "vault")),
                instruction(SolanaProgramIds.TOKEN_PROGRAM, List.of("a", "b"))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed))).isNull();
    }

    @Test
    @DisplayName("Ignores DLMM instructions below the liquidity account threshold")
    void ignoresSmallDlmmInstructions() {
        // initializePosition alone (8 accounts) is not the economic liquidity leg.
        Document parsed = new Document("type", "INITIALIZE_POSITION").append("instructions", List.of(
                instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        WALLET, POSITION, POOL, WALLET, SolanaProgramIds.SYSTEM_PROGRAM,
                        "SysvarRent", "eventAuthority", SolanaProgramIds.METEORA_DLMM))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed))).isNull();
    }

    // --- Raydium CLMM (NFT-based positions, verified against the amm-v3 IDL) ---

    private static final String RAYDIUM_NFT_ACCOUNT = "6ZRCB7AAqGre6c72PRz3MHLC73VMYvJ8bi9KHf1HFpNk";
    private static final String RAYDIUM_POOL_STATE = "CgqwPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXaqY";

    @Test
    @DisplayName("Raydium CLMM increaseLiquidity: NFT account at accounts[1] is the position identity")
    void resolvesRaydiumClmmPositionFromIncreaseLiquidity() {
        // increaseLiquidityV2: [nftOwner==wallet, nftAccount, poolState, protocolPosition, personalPosition, ...]
        Document parsed = new Document("type", "SWAP").append("instructions", List.of(
                instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                        WALLET, RAYDIUM_NFT_ACCOUNT, RAYDIUM_POOL_STATE, "protocolPosition",
                        "personalPosition", "tickLower", "tickUpper", "tokenAcc0", "tokenAcc1",
                        "tokenVault0", "tokenVault1", SolanaProgramIds.TOKEN_PROGRAM))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT);
    }

    @Test
    @DisplayName("Raydium CLMM decreaseLiquidity shares the same NFT-account position identity as the entry")
    void resolvesRaydiumClmmPositionFromDecreaseLiquidity() {
        // decreaseLiquidityV2: [nftOwner==wallet, nftAccount, personalPosition, poolState, ...]
        Document parsed = new Document("type", "SWAP").append("instructions", List.of(
                instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                        WALLET, RAYDIUM_NFT_ACCOUNT, "personalPosition", RAYDIUM_POOL_STATE,
                        "protocolPosition", "tokenVault0", "tokenVault1", "tickLower", "tickUpper",
                        "recipient0", "recipient1", SolanaProgramIds.TOKEN_PROGRAM))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT);
    }

    @Test
    @DisplayName("Raydium CLMM openPosition (payer==owner==wallet) resolves the NFT account at accounts[3]")
    void resolvesRaydiumClmmPositionFromOpenPosition() {
        // openPositionV2: [payer==wallet, positionNftOwner==wallet, positionNftMint, positionNftAccount, ...]
        Document parsed = new Document("type", "SWAP").append("instructions", List.of(
                instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                        WALLET, WALLET, "positionNftMint", RAYDIUM_NFT_ACCOUNT, "metadata",
                        RAYDIUM_POOL_STATE, "protocolPosition", "tickLower", "tickUpper",
                        "personalPosition", "tokenAcc0", "tokenAcc1"))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT);
    }

    @Test
    @DisplayName("Raydium CLMM closePosition-only (below the liquidity threshold) resolves to null")
    void ignoresRaydiumClmmClosePositionOnly() {
        // closePosition: [nftOwner==wallet, positionNftMint, positionNftAccount, personalPosition, systemProgram, tokenProgram]
        Document parsed = new Document("type", "CLOSE_POSITION").append("instructions", List.of(
                instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                        WALLET, "positionNftMint", RAYDIUM_NFT_ACCOUNT, "personalPosition",
                        SolanaProgramIds.SYSTEM_PROGRAM, SolanaProgramIds.TOKEN_PROGRAM))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed))).isNull();
    }

    // --- RC-S-LP-CLOSE: full-position-close detection via position-account rent reclaim ---

    private static Document dlmmLiquidityInstruction() {
        return instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                POSITION, POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, "binLower", "binUpper", WALLET));
    }

    @Test
    @DisplayName("DLMM full close: position PDA rent reclaimed (negative nativeBalanceChange) → isFullPositionClose")
    void detectsDlmmFullCloseFromPositionRentReclaim() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE")
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("accountData", List.of(
                        new Document("account", WALLET).append("nativeBalanceChange", 55_000_000L),
                        new Document("account", POSITION).append("nativeBalanceChange", -57_406_080L)));

        assertThat(SolanaLpPositionResolver.isFullPositionClose(view(parsed))).isTrue();
    }

    @Test
    @DisplayName("DLMM partial remove: position PDA untouched (nativeBalanceChange 0) → not a full close")
    void detectsDlmmPartialRemoveIsNotFullClose() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE")
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("accountData", List.of(
                        new Document("account", WALLET).append("nativeBalanceChange", 12_000_000L),
                        new Document("account", POSITION).append("nativeBalanceChange", 0L)));

        assertThat(SolanaLpPositionResolver.isFullPositionClose(view(parsed))).isFalse();
    }

    @Test
    @DisplayName("Raydium CLMM full close: position NFT account rent reclaimed → isFullPositionClose")
    void detectsRaydiumClmmFullCloseFromNftAccountRentReclaim() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(
                        instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                                WALLET, RAYDIUM_NFT_ACCOUNT, RAYDIUM_POOL_STATE, "protocolPosition",
                                "personalPosition", "tickLower", "tickUpper", "tokenAcc0", "tokenAcc1",
                                "tokenVault0", "tokenVault1", SolanaProgramIds.TOKEN_PROGRAM))))
                .append("accountData", List.of(
                        new Document("account", RAYDIUM_NFT_ACCOUNT).append("nativeBalanceChange", -2_074_080L)));

        assertThat(SolanaLpPositionResolver.isFullPositionClose(view(parsed))).isTrue();
    }

    // --- ADR-081 (C1): Meteora DAMM v1 fungible-MLP position identity ---

    private static final String DAMM_POOL = "5yuefgbJJpmFNK2iiYbLSpv1aZXq7F9AUKkZKErTYCvs";
    private static final String DAMM_USER_POOL_LP = "8dLpPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXbZq";

    private static Document dammLiquidityInstruction() {
        // addBalanceLiquidity / removeBalanceLiquidity layout:
        // [pool, lpMint, userPoolLp, aVaultLp, bVaultLp, aVault, bVault, aTokenVault, bTokenVault,
        //  aVaultLpMint, bVaultLpMint, user, tokenProgram, vaultProgram] (verified against damm-v1-sdk IDL)
        return instruction(SolanaProgramIds.METEORA_DYNAMIC_AMM, List.of(
                DAMM_POOL, "lpMint", DAMM_USER_POOL_LP, "aVaultLp", "bVaultLp", "aVault", "bVault",
                "aTokenVault", "bTokenVault", "aVaultLpMint", "bVaultLpMint", WALLET,
                SolanaProgramIds.TOKEN_PROGRAM, SolanaProgramIds.METEORA_VAULT));
    }

    @Test
    @DisplayName("DAMM: correlationId is lp-position:solana:meteora-damm:{pool}:{wallet} (per-pool + per-wallet)")
    void resolvesDammCorrelationFromPoolAndWallet() {
        Document parsed = new Document("type", "ADD_LIQUIDITY")
                .append("instructions", List.of(dammLiquidityInstruction()));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed)))
                .isEqualTo("lp-position:solana:meteora-damm:" + DAMM_POOL + ":" + WALLET);
    }

    @Test
    @DisplayName("DAMM: resolveLpReceiptMint returns the lpMint (accounts[1]) so the MLP leg can be flagged")
    void resolvesDammLpReceiptMintFromAccountsIndexOne() {
        Document parsed = new Document("type", "ADD_LIQUIDITY")
                .append("instructions", List.of(dammLiquidityInstruction()));

        // accounts[1] of the DAMM liquidity leg is the LP mint (fungible MLP receipt).
        assertThat(SolanaLpPositionResolver.resolveLpReceiptMint(view(parsed))).isEqualTo("lpMint");
    }

    @Test
    @DisplayName("DAMM: resolveLpReceiptMint is null for a non-DAMM (DLMM) tx — no fungible wallet receipt")
    void resolveLpReceiptMintNullForNonDamm() {
        Document parsed = new Document("type", "ADD_LIQUIDITY")
                .append("instructions", List.of(instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                        "PositionPda11111111111111111111111111111111", "LbPair1111111111111111111111111111111111111",
                        "binArray", "reserveX", "reserveY", "tokenXMint", "tokenYMint", WALLET))));

        assertThat(SolanaLpPositionResolver.resolveLpReceiptMint(view(parsed))).isNull();
    }

    @Test
    @DisplayName("DAMM: preserves case-sensitive base58 wallet (never lowercases the identity)")
    void dammKeyPreservesBase58WalletCase() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY")
                .append("instructions", List.of(dammLiquidityInstruction()));

        String corr = SolanaLpPositionResolver.resolveCorrelationId(view(parsed));
        assertThat(corr).endsWith(":" + WALLET);
        assertThat(corr).isNotEqualTo(corr.toLowerCase());
    }

    @Test
    @DisplayName("DAMM full close: userPoolLp token account rent reclaimed → isFullPositionClose")
    void detectsDammFullCloseFromUserPoolLpRentReclaim() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY")
                .append("instructions", List.of(dammLiquidityInstruction()))
                .append("accountData", List.of(
                        new Document("account", WALLET).append("nativeBalanceChange", 2_000_000L),
                        new Document("account", DAMM_USER_POOL_LP).append("nativeBalanceChange", -2_039_280L)));

        assertThat(SolanaLpPositionResolver.isFullPositionClose(view(parsed))).isTrue();
    }

    @Test
    @DisplayName("DAMM partial remove: userPoolLp untouched → not a full close")
    void detectsDammPartialRemoveIsNotFullClose() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY")
                .append("instructions", List.of(dammLiquidityInstruction()))
                .append("accountData", List.of(
                        new Document("account", WALLET).append("nativeBalanceChange", 1_000_000L),
                        new Document("account", DAMM_USER_POOL_LP).append("nativeBalanceChange", 0L)));

        assertThat(SolanaLpPositionResolver.isFullPositionClose(view(parsed))).isFalse();
    }

    @Test
    @DisplayName("DAMM below the liquidity account threshold (e.g. swap/claim) resolves to null")
    void ignoresSmallDammInstructions() {
        Document parsed = new Document("type", "SWAP").append("instructions", List.of(
                instruction(SolanaProgramIds.METEORA_DYNAMIC_AMM, List.of(
                        DAMM_POOL, "userSource", "userDest", "aVault", "bVault"))));

        assertThat(SolanaLpPositionResolver.resolveCorrelationId(view(parsed))).isNull();
    }

    @Test
    @DisplayName("No resolvable position → not a full close (never fabricates closure)")
    void unresolvedPositionIsNotFullClose() {
        Document parsed = new Document("type", "EXTENSION_EXECUTE").append("instructions", List.of(
                instruction(SolanaProgramIds.HAWKSIGHT, List.of(WALLET, "vault"))));

        assertThat(SolanaLpPositionResolver.isFullPositionClose(view(parsed))).isFalse();
    }
}
