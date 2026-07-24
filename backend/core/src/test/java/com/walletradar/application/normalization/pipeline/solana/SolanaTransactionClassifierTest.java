package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.solana.SolanaChain;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaTransactionClassifierTest {

    private static final String WALLET = "6Rc7yKz3aT2j2n7f3Q8Q3zvz1n2u9Wq3rXyZabCdEfG";
    private static final String PEER = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";

    @BeforeAll
    static void bindStablecoinContracts() {
        NetworkStablecoinContracts.bind(networkId -> NetworkTestFixtures.registry().usdStableContracts(networkId));
    }

    private final SolanaTransactionClassifier classifier = new SolanaTransactionClassifier();

    private static SolanaRawTransactionView view(String wallet, Document heliusParsed) {
        RawTransaction raw = new RawTransaction();
        raw.setWalletAddress(wallet);
        raw.setTxHash("sig1");
        raw.setRawData(new Document("source", "HELIUS_ENHANCED").append("heliusParsed", heliusParsed));
        return SolanaRawTransactionView.wrap(raw);
    }

    private static Document nativeTransfer(String from, String to, long lamports) {
        return new Document("fromUserAccount", from).append("toUserAccount", to).append("amount", lamports);
    }

    private static Document instruction(String programId) {
        return new Document("programId", programId);
    }

    @Test
    @DisplayName("RC-S5: inbound native transfer classifies EXTERNAL_TRANSFER_IN")
    void inboundTransferIsExternalIn() {
        Document parsed = new Document("type", "TRANSFER")
                .append("nativeTransfers", List.of(nativeTransfer(PEER, WALLET, 1_000_000_000L)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("RC-S5: outbound native transfer classifies EXTERNAL_TRANSFER_OUT")
    void outboundTransferIsExternalOut() {
        Document parsed = new Document("type", "TRANSFER")
                .append("nativeTransfers", List.of(nativeTransfer(WALLET, PEER, 1_000_000_000L)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
    }

    @Test
    @DisplayName("RC-S5: self→self native transfer classifies INTERNAL_TRANSFER")
    void selfTransferIsInternal() {
        Document parsed = new Document("type", "TRANSFER")
                .append("nativeTransfers", List.of(nativeTransfer(WALLET, WALLET, 1_000_000_000L)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    @Test
    @DisplayName("RC-S6: compressed-NFT mint classifies NFT_MINT (non-economic)")
    void compressedNftMintIsNftMint() {
        Document parsed = new Document("type", "COMPRESSED_NFT_MINT");
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.NFT_MINT);
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("RC-S6: SPL account housekeeping classifies ADMIN_CONFIG (non-economic)")
    void closeAccountIsAdminConfig() {
        Document parsed = new Document("type", "CLOSE_ACCOUNT");
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.ADMIN_CONFIG);
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("RC-S6: Meteora farm claim classifies REWARD_CLAIM")
    void meteoraFarmClaimIsRewardClaim() {
        Document parsed = new Document("type", "CLAIM_REWARD")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.METEORA_FARM_ID)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(result.protocolName()).isEqualTo("Meteora Farm");
    }

    @Test
    @DisplayName("Jupiter v6 program id classifies SWAP")
    void jupiterProgramIsSwap() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.SWAP);
    }

    private static Document raydiumClmmInstruction() {
        return new Document("programId", SolanaProtocolPrograms.RAYDIUM_CLMM_ID);
    }

    private static Document tokenTransfer(String from, String to, String mint, double amount) {
        return new Document("fromUserAccount", from)
                .append("toUserAccount", to)
                .append("mint", mint)
                .append("tokenAmount", amount);
    }

    @Test
    @DisplayName("Raydium CLMM Helius-labelled SWAP with 2 outbound mints reclassifies to LP_ENTRY")
    void raydiumClmmTwoOutboundReclassifiesToLpEntry() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(raydiumClmmInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, "WLFinEv6ypjkczcS83FZqFpgFZYwQXutRbxGe7oC16g", 0.365),
                        tokenTransfer(WALLET, PEER, USDC_MINT, 0.158)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(result.protocolName()).isEqualTo("Raydium CLMM");
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("Raydium CLMM with 2 inbound mints reclassifies to LP_EXIT")
    void raydiumClmmTwoInboundReclassifiesToLpExit() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(raydiumClmmInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(PEER, WALLET, "WLFinEv6ypjkczcS83FZqFpgFZYwQXutRbxGe7oC16g", 0.40),
                        tokenTransfer(PEER, WALLET, USDC_MINT, 0.20)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    @Test
    @DisplayName("Raydium CLMM clean 1-out/1-in stays SWAP")
    void raydiumClmmOneOutOneInStaysSwap() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(raydiumClmmInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, USDC_MINT, 10.0),
                        tokenTransfer(PEER, WALLET, "WLFinEv6ypjkczcS83FZqFpgFZYwQXutRbxGe7oC16g", 25.0)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.SWAP);
    }

    private static final String USD1_MINT = "USD1ttGY1N17NEEHLmELoaybftRBUSErhqYiQzvEmuB";

    @Test
    @DisplayName("Routed swap: OKX DEX router CPI-ing Raydium CLMM (USDC out / USD1 in + dust SOL) stays SWAP, not LP_ENTRY")
    void raydiumRoutedSwapViaOkxRouterStaysSwap() {
        // DAfqv9…: OKX DEX router routes a USDC→USD1 swap through the Raydium CLMM pool. Without the
        // routed-swap guard, rule 7 (RAYDIUM_CLMM present) + a dust SOL leg tips flow-shape inference
        // to a phantom LP_ENTRY. The guard must classify it as a SWAP.
        Document parsed = new Document("type", "UNKNOWN")
                .append("source", "RAYDIUM")
                .append("instructions", List.of(
                        instruction(SolanaProtocolPrograms.OKX_DEX_ROUTER_ID),
                        raydiumClmmInstruction()))
                .append("nativeTransfers", List.of(nativeTransfer(WALLET, PEER, 2_000L)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, USDC_MINT, 296.04),
                        tokenTransfer(PEER, WALLET, USD1_MINT, 295.97)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.needsReview()).isFalse();
    }

    private static final String VSOL_MINT = "vSoLxydx6akxyMD9XEcPvGYNGq6Nn66oqVb3UkGkei7";

    @Test
    @DisplayName("Meteora DAMM swap (SOL->vSOL) that CPIs the dynamic-vault program stays SWAP, not one-sided VAULT_DEPOSIT")
    void meteoraDammSwapThroughVaultStaysSwap() {
        // X7mtXGN…: a Meteora Dynamic AMM (Eo7WjK) SOL->vSOL swap. DAMM v1 pools park idle liquidity in
        // dynamic vaults, so the swap CPIs the METEORA_VAULT program (24Uqj9). The bare vault-program
        // guard used to fire rule 5 first and book this as a one-sided VAULT_DEPOSIT that dropped the
        // received vSOL leg and parked the SOL basis (inflating the SOL cost pool). The DAMM-absence
        // guard now routes it to flow-shape inference -> SWAP with both legs preserved.
        Document parsed = new Document("type", "SWAP")
                .append("source", "METEORA")
                .append("instructions", List.of(
                        instruction(SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID),
                        instruction(SolanaProtocolPrograms.METEORA_VAULT_ID)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, SolanaChain.WSOL_MINT, 0.263064376),
                        tokenTransfer(PEER, WALLET, VSOL_MINT, 0.247723139)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(result.protocolName()).isEqualTo("Meteora Dynamic AMM");
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("Genuine standalone Meteora dynamic-vault deposit (no DAMM program) stays VAULT_DEPOSIT")
    void meteoraStandaloneVaultDepositStaysVaultDeposit() {
        // A direct dynamic-vault deposit touches ONLY the vault program (no DAMM) and must remain a
        // vault deposit — the DAMM-absence guard preserves this genuine case.
        Document parsed = new Document("type", "UNKNOWN")
                .append("source", "METEORA")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.METEORA_VAULT_ID)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, USDC_MINT, 100.0)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT);
        assertThat(result.protocolName()).isEqualTo("Meteora Vault");
    }

    @Test
    @DisplayName("unknown non-economic type still flags SOLANA_UNCLASSIFIED for review")
    void unknownStillNeedsReview() {
        Document parsed = new Document("type", "SOME_FUTURE_TYPE");
        assertThat(classifier.classify(view(WALLET, parsed)).needsReview()).isTrue();
    }

    // --- RC-S8: FIX B — unlabeled pure SPL transfer must classify by flow shape, never UNKNOWN ---

    private static final String PUMP_MINT = "7oBYdEyNhK8s3f7Q2Kbm9m6t2VhqYVoq2p5c8v1Kpump";

    @Test
    @DisplayName("RC-S8: unlabeled outbound SPL transfer (unknown pump.fun mint, non-DeFi programs only) classifies EXTERNAL_TRANSFER_OUT")
    void unlabeledOutboundSplTransferIsExternalOut() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(
                        instruction(SolanaChain.COMPUTE_BUDGET_PROGRAM),
                        instruction(SolanaChain.ASSOCIATED_TOKEN_PROGRAM),
                        instruction(SolanaChain.TOKEN_PROGRAM)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, PUMP_MINT, 149.589668)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("RC-S8: unlabeled inbound SPL transfer classifies EXTERNAL_TRANSFER_IN")
    void unlabeledInboundSplTransferIsExternalIn() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(instruction(SolanaChain.TOKEN_PROGRAM)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(PEER, WALLET, PUMP_MINT, 10.0)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("RC-S8: unlabeled type with an unknown DeFi program present stays UNKNOWN (fallback guard holds)")
    void unlabeledWithUnknownProgramStaysUnknown() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(
                        instruction(SolanaChain.TOKEN_PROGRAM),
                        instruction("SomeUnknownDeFiProgram1111111111111111111111")))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, PEER, PUMP_MINT, 5.0)));
        assertThat(classifier.classify(view(WALLET, parsed)).needsReview()).isTrue();
    }

    // --- RULE 1: Jupiter Lend borrow / loop net-flow classification (Helius type is generic) ---

    private static final String RESERVE = "7s1da8DduuBFqGra5bJBjpnvL5E9mGzCuMk1Qkh4or2Z";
    private static final String JL_RECEIPT_MINT = "4XuocgW9zK5ozuaCQMLjRHkvzeT63D3jwpNKKHaE7BY5";

    private static Document lendInstruction() {
        return instruction(SolanaProtocolPrograms.jupiterLendProgramIds().iterator().next());
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend net-positive USDT with no matching outbound classifies BORROW")
    void jupiterLendNetPositiveStableIsBorrow() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(lendInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(RESERVE, WALLET, USDT_MINT, 210.0)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.BORROW);
        assertThat(result.protocolName()).isEqualTo("Jupiter Lend");
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend net-negative SOL + receipt mint classifies LENDING_DEPOSIT (receipt ignored)")
    void jupiterLendNetNegativeSolIsDeposit() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(lendInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, RESERVE, SolanaChain.WSOL_MINT, 1.96),
                        tokenTransfer(RESERVE, WALLET, JL_RECEIPT_MINT, 1.96)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend net-positive SOL classifies LENDING_WITHDRAW")
    void jupiterLendNetPositiveSolIsWithdraw() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(lendInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(RESERVE, WALLET, SolanaChain.WSOL_MINT, 0.939),
                        tokenTransfer(WALLET, RESERVE, JL_RECEIPT_MINT, 0.939)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend + Jupiter Swap with net collateral change classifies LENDING_LOOP_OPEN (USDT nets 0)")
    void jupiterLendLoopIsLoopOpen() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("instructions", List.of(lendInstruction(), instruction(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(RESERVE, WALLET, USDT_MINT, 15.81),
                        tokenTransfer(WALLET, "pool", USDT_MINT, 15.81),
                        tokenTransfer(WALLET, RESERVE, SolanaChain.WSOL_MINT, 0.10)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.LENDING_LOOP_OPEN);
    }

    @Test
    @DisplayName("RULE 1 regression (5YMocs): Jupiter Lend SOL deposit co-located with a third-party Jupiter swap (wallet has no stablecoin flow) stays LENDING_DEPOSIT, not LENDING_LOOP_OPEN")
    void jupiterLendDepositWithForeignSwapIsNotLoopOpen() {
        Document parsed = new Document("type", "TRANSFER")
                .append("instructions", List.of(lendInstruction(), instruction(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)))
                .append("tokenTransfers", List.of(
                        // Wallet only deposits SOL collateral into Jupiter Lend.
                        tokenTransfer(WALLET, RESERVE, SolanaChain.WSOL_MINT, 0.0998),
                        // The USDT<->SOL swap legs are owned by maker/pool accounts, not the wallet.
                        tokenTransfer("maker", "pool", USDT_MINT, 15.81),
                        tokenTransfer("pool", "maker", SolanaChain.WSOL_MINT, 0.236)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
    }

    @Test
    @DisplayName("RULE 1 negative case: pure Jupiter Swap (no Jupiter Lend) stays SWAP, never lending")
    void pureJupiterSwapIsNotLending() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, "pool", SolanaChain.WSOL_MINT, 1.0),
                        tokenTransfer("pool", WALLET, USDT_MINT, 150.0)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.SWAP);
    }

    // --- Rule 12: Native Solana staking (SolanaChain.STAKE_PROGRAM) ---

    @Test
    @DisplayName("Rule 12: STAKE_PROGRAM with non-withdraw helius type classifies STAKING_DEPOSIT")
    void nativeStakingDepositIsStakingDeposit() {
        Document parsed = new Document("type", "STAKE_SOL")
                .append("instructions", List.of(instruction(SolanaChain.STAKE_PROGRAM)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(result.protocolName()).isEqualTo("Solana Staking");
    }

    @Test
    @DisplayName("Rule 12: STAKE_PROGRAM with DELEGATE_STAKE helius type classifies STAKING_DEPOSIT")
    void nativeStakingDelegateIsStakingDeposit() {
        Document parsed = new Document("type", "DELEGATE_STAKE")
                .append("instructions", List.of(instruction(SolanaChain.STAKE_PROGRAM)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
    }

    @Test
    @DisplayName("Rule 12: STAKE_PROGRAM with UNSTAKE_SOL helius type classifies STAKING_WITHDRAW")
    void nativeStakingUnstakeIsStakingWithdraw() {
        Document parsed = new Document("type", "UNSTAKE_SOL")
                .append("instructions", List.of(instruction(SolanaChain.STAKE_PROGRAM)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_WITHDRAW);
        assertThat(result.protocolName()).isEqualTo("Solana Staking");
    }

    @Test
    @DisplayName("Rule 12: STAKE_PROGRAM with WITHDRAW_STAKE helius type classifies STAKING_WITHDRAW")
    void nativeStakingWithdrawIsStakingWithdraw() {
        Document parsed = new Document("type", "WITHDRAW_STAKE")
                .append("instructions", List.of(instruction(SolanaChain.STAKE_PROGRAM)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.STAKING_WITHDRAW);
    }

    // --- Rule 13: Liquid staking (Marinade, Jito) ---

    @Test
    @DisplayName("Rule 13: Marinade liquid staking deposit classifies STAKING_DEPOSIT")
    void marinadeStakingIsStakingDeposit() {
        Document parsed = new Document("type", "STAKE_SOL")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.MARINADE_ID)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(result.protocolName()).isNotNull();
    }

    @Test
    @DisplayName("Rule 13: Marinade unstake classifies STAKING_WITHDRAW")
    void marinadeUnstakeIsStakingWithdraw() {
        Document parsed = new Document("type", "UNSTAKE_SOL")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.MARINADE_ID)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.STAKING_WITHDRAW);
    }

    @Test
    @DisplayName("Rule 13: Jito stake classifies STAKING_DEPOSIT")
    void jitoStakingIsStakingDeposit() {
        Document parsed = new Document("type", "STAKE_SOL")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.JITO_ID)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
    }

    // --- Rule 2: Kamino Lend ---

    @Test
    @DisplayName("Rule 2: Kamino Lend DEPOSIT helius type classifies LENDING_DEPOSIT")
    void kaminoLendDepositIsLendingDeposit() {
        Document parsed = new Document("type", "DEPOSIT")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.KAMINO_LEND_ID)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(result.protocolName()).isNotNull();
    }

    @Test
    @DisplayName("Rule 2: Kamino Lend BORROW helius type classifies BORROW")
    void kaminoLendBorrowIsBorrow() {
        Document parsed = new Document("type", "BORROW")
                .append("instructions", List.of(instruction(SolanaProtocolPrograms.KAMINO_LEND_ID)));
        assertThat(classifier.classify(view(WALLET, parsed)).type())
                .isEqualTo(NormalizedTransactionType.BORROW);
    }

    // --- Rule 15: SWAP by Helius type alone (no DeFi program) ---

    @Test
    @DisplayName("Rule 15: SWAP helius type with no matching DeFi program classifies SWAP (unknown AMM)")
    void swapByHeliusTypeAloneIsSwap() {
        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(instruction(SolanaChain.TOKEN_PROGRAM)));
        SolanaClassificationResult result = classifier.classify(view(WALLET, parsed));
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
        // Unknown AMM: protocol name may be null
        assertThat(result.needsReview()).isFalse();
    }
}
