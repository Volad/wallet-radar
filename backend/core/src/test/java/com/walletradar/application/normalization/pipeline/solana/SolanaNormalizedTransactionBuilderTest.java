package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.application.costbasis.domain.AssetFamily;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaNormalizedTransactionBuilderTest {

    private static final String WALLET = "6Rc7yKz3aT2j2n7f3Q8Q3zvz1n2u9Wq3rXyZabCdEfG";
    private static final String PEER = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    private final SolanaNormalizedTransactionBuilder builder = new SolanaNormalizedTransactionBuilder(
            new SolanaTransactionClassifier(),
            Mockito.mock(AccountingUniverseService.class)
    );

    private static RawTransaction raw(Document heliusParsed) {
        RawTransaction r = new RawTransaction();
        r.setId("sig1:SOLANA:" + WALLET);
        r.setTxHash("sig1");
        r.setWalletAddress(WALLET);
        r.setNetworkId("SOLANA");
        // Default the fee payer to the tracked wallet so existing fixtures represent wallet-paid
        // transactions (fee leg emitted). Tests exercising third-party-paid fees set feePayer explicitly.
        if (!heliusParsed.containsKey("feePayer")) {
            heliusParsed.append("feePayer", WALLET);
        }
        r.setRawData(new Document("source", "HELIUS_ENHANCED").append("heliusParsed", heliusParsed));
        return r;
    }

    @Test
    @DisplayName("RC-S4: reconstructs SELL + BUY swap legs from accountData token/native deltas")
    void reconstructsSwapLegsFromAccountData() {
        long fee = 5_000L;
        Document walletAccount = new Document("account", WALLET)
                .append("nativeBalanceChange", -(1_000_000_000L + fee))
                .append("tokenBalanceChanges", List.of(new Document("userAccount", WALLET)
                        .append("mint", SolanaProgramIds.USDC_MINT)
                        .append("rawTokenAmount", new Document("tokenAmount", "1000000").append("decimals", 6))));
        Document parsed = new Document("type", "SWAP")
                .append("fee", fee)
                .append("instructions", List.of(new Document("programId", SolanaProgramIds.JUPITER_SWAP_V6)))
                .append("accountData", List.of(walletAccount));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        NormalizedTransaction.Flow sell = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL).findFirst().orElseThrow();
        NormalizedTransaction.Flow buy = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(SolanaProgramIds.WSOL_MINT);
        assertThat(sell.getQuantityDelta().doubleValue()).isEqualTo(-1.0);
        assertThat(buy.getAssetContract()).isEqualTo(SolanaProgramIds.USDC_MINT);
        assertThat(buy.getQuantityDelta().doubleValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GAS: fee leg is dropped when a third party (bot/protocol) paid the tx fee")
    void thirdPartyPaidFeeDoesNotChargeTrackedWallet() {
        // Bot- or protocol-managed rebalance: Helius reports meta.fee, but the fee payer is not the
        // tracked wallet, so the wallet's nativeBalanceChange does not embed the fee. Charging it here
        // would overstate gas paid — the FEE leg must not be emitted, and the swap SOL delta must not
        // be inflated by an add-back.
        long fee = 5_000_000L;
        Document walletAccount = new Document("account", WALLET)
                .append("nativeBalanceChange", -1_000_000_000L)
                .append("tokenBalanceChanges", List.of(new Document("userAccount", WALLET)
                        .append("mint", SolanaProgramIds.USDC_MINT)
                        .append("rawTokenAmount", new Document("tokenAmount", "1000000").append("decimals", 6))));
        Document parsed = new Document("type", "SWAP")
                .append("feePayer", "BoT1111111111111111111111111111111111111111")
                .append("fee", fee)
                .append("instructions", List.of(new Document("programId", SolanaProgramIds.JUPITER_SWAP_V6)))
                .append("accountData", List.of(walletAccount));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.FEE);
        NormalizedTransaction.Flow sell = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL).findFirst().orElseThrow();
        // No fee add-back: the wallet's SOL outflow is exactly nativeBalanceChange (-1.0 SOL).
        assertThat(sell.getQuantityDelta().doubleValue()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("RC-S7b: Jupiter RFQ (Order Engine) is a SWAP, not a vault deposit — both legs materialize")
    void jupiterRfqOrderEngineClassifiesAsSwapWithBothLegs() {
        // Reproduces the misclassification where program 61DFf (Jupiter RFQ) was registered as
        // "Meteora Vault": the wallet sends 33.5 USDT and receives 0.529 SOL (native), which must
        // book a SELL(USDT) + BUY(SOL), never a one-sided VAULT_DEPOSIT that drops the SOL leg.
        long fee = 5_000L;
        Document walletAccount = new Document("account", WALLET)
                .append("nativeBalanceChange", 529_292_579L)
                .append("tokenBalanceChanges", List.of(new Document("userAccount", WALLET)
                        .append("mint", SolanaProgramIds.USDT_MINT)
                        .append("rawTokenAmount", new Document("tokenAmount", "-33500000").append("decimals", 6))));
        Document parsed = new Document("type", "UNKNOWN")
                .append("source", "UNKNOWN")
                .append("fee", fee)
                .append("instructions", List.of(new Document("programId", SolanaProgramIds.JUPITER_RFQ_ORDER_ENGINE)))
                .append("accountData", List.of(walletAccount));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getProtocolName()).isEqualTo("Jupiter RFQ");
        NormalizedTransaction.Flow sell = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL).findFirst().orElseThrow();
        NormalizedTransaction.Flow buy = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(SolanaProgramIds.USDT_MINT);
        assertThat(sell.getQuantityDelta().doubleValue()).isEqualTo(-33.5);
        assertThat(buy.getAssetContract()).isEqualTo(SolanaProgramIds.WSOL_MINT);
        assertThat(buy.getQuantityDelta().signum()).isPositive();
    }

    @Test
    @DisplayName("RC-S5: inbound native-SOL dust is dropped but the fee leg is preserved")
    void dustInboundKeepsFeeLeg() {
        long fee = 5_000L;
        Document parsed = new Document("type", "TRANSFER")
                .append("fee", fee)
                .append("nativeTransfers", List.of(new Document("fromUserAccount", PEER)
                        .append("toUserAccount", WALLET)
                        .append("amount", 500L))); // 5e-7 SOL, below 1e-6 dust threshold

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getFlows()).hasSize(1);
        NormalizedTransaction.Flow feeFlow = tx.getFlows().get(0);
        assertThat(feeFlow.getRole()).isEqualTo(NormalizedLegRole.FEE);
        assertThat(feeFlow.getQuantityDelta().signum()).isNegative();
    }

    @Test
    @DisplayName("RC-S5: non-dust inbound native-SOL transfer keeps both the transfer and fee legs")
    void inboundTransferKeepsEconomicLeg() {
        long fee = 5_000L;
        Document parsed = new Document("type", "TRANSFER")
                .append("fee", fee)
                .append("nativeTransfers", List.of(new Document("fromUserAccount", PEER)
                        .append("toUserAccount", WALLET)
                        .append("amount", 2_000_000_000L)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(tx.getFlows().stream().anyMatch(f -> f.getRole() == NormalizedLegRole.TRANSFER
                && f.getQuantityDelta().signum() > 0)).isTrue();
        assertThat(tx.getFlows().stream().anyMatch(f -> f.getRole() == NormalizedLegRole.FEE)).isTrue();
    }

    // --- RC-S-LP: Solana LP / lending / vault move-basis continuity ---

    private static final String DLMM_POSITION = "H7wY3yb9LfJYv98yxfyqpPeco3ezKFE5n8VQKRcooe9w";
    private static final String DLMM_POOL = "CgqwPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXaqY";
    private static final String PUMP_MINT = "7oBYdEhV4GkXC19ZfgAvXpJWp2Rn9pm1Bx2cVNxFpump";
    private static final String HAWKSIGHT_VAULT = "GJRai8ArFdXyZSMxXYCNReXxAtZkaE3hHbUEz1i7ecNZ";

    private static Document instruction(String programId, List<String> accounts) {
        return new Document("programId", programId).append("accounts", accounts);
    }

    private static Document dlmmLiquidityInstruction() {
        // >= 10 accounts, accounts[0] = position PDA (add/remove-liquidity layout).
        return instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                DLMM_POSITION, DLMM_POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, "binLower", "binUpper", WALLET));
    }

    private static Document tokenTransfer(String from, String to, String mint, String symbol, double amount) {
        return new Document("fromUserAccount", from)
                .append("toUserAccount", to)
                .append("mint", mint)
                .append("symbol", symbol)
                .append("tokenAmount", amount);
    }

    @Test
    @DisplayName("RC-S-LP: direct Meteora DLMM entry carries basis as TRANSFER-out with a position correlation")
    void directDlmmEntryBooksTransferOutWithPositionCorrelation() {
        Document parsed = new Document("type", "ADD_LIQUIDITY_BY_STRATEGY")
                .append("fee", 5_000L)
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, DLMM_POOL, SolanaProgramIds.WSOL_MINT, "SOL", 1.15788447)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(tx.getCorrelationId()).isEqualTo("lp-position:solana:meteora-dlmm:" + DLMM_POSITION);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getQuantityDelta().signum()).isNegative();
        assertThat(principal.getAssetContract()).isEqualTo(SolanaProgramIds.WSOL_MINT);
        assertThat(principal.getCounterpartyAddress()).isEqualTo(DLMM_POOL);
    }

    @Test
    @DisplayName("RC-S-LP: Meteora DLMM exit returns basis as TRANSFER-in under the same position correlation")
    void directDlmmExitSharesEntryCorrelationAndBooksTransferIn() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE")
                .append("fee", 5_000L)
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(DLMM_POOL, WALLET, SolanaProgramIds.WSOL_MINT, "SOL", 2.0),
                        tokenTransfer(DLMM_POOL, WALLET, PUMP_MINT, "PUMP", 100.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(tx.getCorrelationId()).isEqualTo("lp-position:solana:meteora-dlmm:" + DLMM_POSITION);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        assertThat(tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER)
                .allMatch(f -> f.getQuantityDelta().signum() > 0)).isTrue();
    }

    @Test
    @DisplayName("RC-S-LP: Hawksight-wrapped LP return (tokens back to wallet) books LP_EXIT with a basis-carrying TRANSFER, never a phantom BUY")
    void hawksightWrappedReturnBooksTransferNotBuy() {
        Document parsed = new Document("type", "EXTENSION_EXECUTE")
                .append("source", "HAWKSIGHT")
                .append("fee", 5_000L)
                .append("instructions", List.of(instruction(SolanaProgramIds.HAWKSIGHT, List.of(WALLET, HAWKSIGHT_VAULT))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(HAWKSIGHT_VAULT, WALLET, PUMP_MINT, "PUMP", 149.424164)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        // The wallet RECEIVES liquidity back (net-in), which is a remove/return — an LP_EXIT.
        // Classifying this as LP_ENTRY was the DLMM misclassification that left basis pools
        // permanently non-empty (ghost open pools + inflated balances).
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        // No direct DLMM instruction at wallet level → generic family-continuity bucket, not a
        // fabricated position identity.
        assertThat(tx.getCorrelationId()).isNull();
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(principal.getQuantityDelta().signum()).isPositive();
    }

    @Test
    @DisplayName("RC-S-LP: Hawksight close with inner DLMM removeLiquidity + wallet-rent-only does NOT fabricate a position (phantom-open regression)")
    void hawksightCloseWithInnerDlmmDoesNotFabricatePosition() {
        // Reproduces the phantom-open DLMM bug: a Hawksight EXTENSION_EXECUTE rebalance/close runs
        // the Meteora DLMM removeLiquidity + closePosition as INNER CPI (position PDA at accounts[0]).
        // The liquidity is drained to the Hawksight vault, so the wallet nets only rent (near-zero
        // native change) — flow-shape inference defaults this to a fee-only LP_ENTRY. Previously the
        // resolver walked the inner instruction and stamped an lp-position correlation, creating a
        // per-position basis pool that was funded on the (also-Hawksight) entry and never drained,
        // surfacing as a phantom "open" position. The resolver must now return null so the row rides
        // generic family continuity and no lpConcentrated position is fabricated.
        Document dlmmRemoveInner = instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                DLMM_POSITION, DLMM_POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, "binLower", "binUpper", WALLET));
        Document hawksight = new Document("programId", SolanaProgramIds.HAWKSIGHT)
                .append("accounts", List.of(WALLET, HAWKSIGHT_VAULT))
                .append("innerInstructions", List.of(dlmmRemoveInner));
        // Wallet receives only position rent (small positive native change), no token legs.
        Document walletAccount = new Document("account", WALLET)
                .append("nativeBalanceChange", 57_406_080L)
                .append("tokenBalanceChanges", List.of());
        Document parsed = new Document("type", "EXTENSION_EXECUTE")
                .append("source", "HAWKSIGHT")
                .append("fee", 14_200L)
                .append("instructions", List.of(hawksight))
                .append("accountData", List.of(walletAccount));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        // No fabricated per-position identity → no lpConcentrated → no phantom LP page position.
        assertThat(tx.getCorrelationId()).isNull();
        assertThat(tx.getLpConcentrated()).isNull();
        // No phantom market disposal/acquisition is booked for the wallet on this row.
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
    }

    @Test
    @DisplayName("RC-S-LP: Hawksight-routed DLMM entry (inner add-liquidity) rides generic continuity, not a position pool")
    void hawksightRoutedDlmmEntryDoesNotFabricatePosition() {
        // The ENTRY leg of the phantom position: Helius labels it TRANSFER/SYSTEM_PROGRAM, but the
        // wallet's deposit is routed through the Hawksight program (top-level) which CPIs the DLMM
        // add-liquidity (position PDA at accounts[0]). The wallet's principal (SOL) leaves to the
        // Hawksight vault. Must book an LP move WITHOUT a fabricated position correlation so the
        // entry and its later Hawksight close stay symmetric on the generic family-continuity bucket.
        Document dlmmAddInner = instruction(SolanaProgramIds.METEORA_DLMM, List.of(
                DLMM_POSITION, DLMM_POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, "binLower", "binUpper", WALLET));
        Document hawksight = new Document("programId", SolanaProgramIds.HAWKSIGHT)
                .append("accounts", List.of(WALLET, HAWKSIGHT_VAULT))
                .append("innerInstructions", List.of(dlmmAddInner));
        Document parsed = new Document("type", "TRANSFER")
                .append("source", "SYSTEM_PROGRAM")
                .append("fee", 5_000L)
                .append("instructions", List.of(hawksight))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, HAWKSIGHT_VAULT, SolanaProgramIds.WSOL_MINT, "SOL", 0.241654293)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getCorrelationId()).isNull();
        assertThat(tx.getLpConcentrated()).isNull();
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
    }

    @Test
    @DisplayName("RC-S-LP: Jupiter Lend deposit books basis-carrying TRANSFER-out (no market disposal)")
    void lendingDepositBooksTransferOut() {
        Document parsed = new Document("type", "DEPOSIT")
                .append("fee", 5_000L)
                .append("instructions", List.of(instruction(SolanaProgramIds.JUPITER_LEND, List.of(WALLET, "reserve"))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, "reserve", SolanaProgramIds.WSOL_MINT, "SOL", 2.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getQuantityDelta().signum()).isNegative();
    }

    // --- Raydium CLMM: swap-vs-LP disambiguation by flow shape (concentrated-liquidity AMM) ---

    private static final String RAYDIUM_POOL_VAULT = "zHGN3Kh1miQSuehUWD1TPYTxkCkUrtT8foNxtASsiKJ";
    private static final String RAYDIUM_NFT_ACCOUNT = "6ZRCB7AAqGre6c72PRz3MHLC73VMYvJ8bi9KHf1HFpNk";
    private static final String WLFI_MINT = "WLFinEv6ypjkczcS83FZqFpgFZYwQXutRbxGe7oC16g";
    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    /** increaseLiquidity/decreaseLiquidity layout: accounts[0]=nftOwner(wallet), accounts[1]=nftAccount. */
    private static Document raydiumClmmLiquidityInstruction() {
        return instruction(SolanaProgramIds.RAYDIUM_CLMM, List.of(
                WALLET, RAYDIUM_NFT_ACCOUNT, "poolState", "protocolPosition", "personalPosition",
                "tickArrayLower", "tickArrayUpper", "tokenAccount0", "tokenAccount1", "tokenVault0",
                "tokenVault1", SolanaProgramIds.TOKEN_PROGRAM));
    }

    @Test
    @DisplayName("Raydium CLMM: 2 net-out mints → LP_ENTRY with TRANSFER legs and a stable position correlation")
    void raydiumClmmTwoOutboundIsLpEntry() {
        Document parsed = new Document("type", "SWAP")
                .append("fee", 5_000L)
                .append("instructions", List.of(raydiumClmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, RAYDIUM_POOL_VAULT, WLFI_MINT, "WLFI", 0.365493),
                        tokenTransfer(WALLET, RAYDIUM_POOL_VAULT, USDC_MINT, "USDC", 0.158109)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(tx.getCorrelationId())
                .isEqualTo("lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        assertThat(tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER)
                .allMatch(f -> f.getQuantityDelta().signum() < 0)).isTrue();
        assertThat(tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Raydium CLMM: 2 net-in mints → LP_EXIT with inbound TRANSFER legs under the same position correlation")
    void raydiumClmmTwoInboundIsLpExit() {
        Document parsed = new Document("type", "SWAP")
                .append("fee", 5_000L)
                .append("instructions", List.of(raydiumClmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(RAYDIUM_POOL_VAULT, WALLET, WLFI_MINT, "WLFI", 0.40),
                        tokenTransfer(RAYDIUM_POOL_VAULT, WALLET, USDC_MINT, "USDC", 0.20)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(tx.getCorrelationId())
                .isEqualTo("lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        assertThat(tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER)
                .allMatch(f -> f.getQuantityDelta().signum() > 0)).isTrue();
    }

    @Test
    @DisplayName("Raydium CLMM: clean 1-out/1-in remains SWAP with BUY + SELL legs")
    void raydiumClmmOneOutOneInIsSwap() {
        Document parsed = new Document("type", "SWAP")
                .append("fee", 5_000L)
                .append("instructions", List.of(raydiumClmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, RAYDIUM_POOL_VAULT, USDC_MINT, "USDC", 10.0),
                        tokenTransfer(RAYDIUM_POOL_VAULT, WALLET, WLFI_MINT, "WLFI", 25.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getCorrelationId()).isNull();
        NormalizedTransaction.Flow sell = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL).findFirst().orElseThrow();
        NormalizedTransaction.Flow buy = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(USDC_MINT);
        assertThat(buy.getAssetContract()).isEqualTo(WLFI_MINT);
    }

    // --- ADR-081 (C1): Meteora DAMM fungible-MLP LP + farm stake/unstake ---

    private static final String DAMM_POOL = "5yuefgbJJpmFNK2iiYbLSpv1aZXq7F9AUKkZKErTYCvs";
    private static final String DAMM_USER_POOL_LP = "8dLpPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXbZq";
    private static final String MLP_MINT = "MLPzZ9AUKkZKErTYCvs5yuefgbJJpmFNK2iiYbLSpv1a";
    private static final String MSOL_MINT = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
    private static final String METEORA_FARM_ADDR = "FarmPoolZ9AUKkZKErTYCvs5yuefgbJJpmFNK2iiYbLS";

    private static Document dammLiquidityInstruction() {
        // addBalanceLiquidity / removeBalanceLiquidity: [pool, lpMint, userPoolLp, aVaultLp, bVaultLp,
        // aVault, bVault, aTokenVault, bTokenVault, aVaultLpMint, bVaultLpMint, user, tokenProgram,
        // vaultProgram] (verified against MeteoraAg/damm-v1-sdk IDL).
        return instruction(SolanaProgramIds.METEORA_DYNAMIC_AMM, List.of(
                DAMM_POOL, MLP_MINT, DAMM_USER_POOL_LP, "aVaultLp", "bVaultLp", "aVault", "bVault",
                "aTokenVault", "bTokenVault", "aVaultLpMint", "bVaultLpMint", WALLET,
                SolanaProgramIds.TOKEN_PROGRAM, SolanaProgramIds.METEORA_VAULT));
    }

    @Test
    @DisplayName("C1: Meteora DAMM entry mints meteora-damm:{pool}:{wallet} and books all legs as non-priced TRANSFER")
    void dammEntryMintsCorrelationAndBooksTransferLegs() {
        Document parsed = new Document("type", "ADD_LIQUIDITY")
                .append("fee", 5_000L)
                .append("instructions", List.of(dammLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, DAMM_POOL, SolanaProgramIds.WSOL_MINT, "SOL", 1.0),
                        tokenTransfer(WALLET, DAMM_POOL, MSOL_MINT, "MSOL", 0.9),
                        tokenTransfer(DAMM_POOL, WALLET, MLP_MINT, "MLP", 0.3096)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(tx.getCorrelationId())
                .isEqualTo("lp-position:solana:meteora-damm:" + DAMM_POOL + ":" + WALLET);
        // MLP receipt + underlying legs are non-priced continuity moves (no SELL/BUY mis-tag).
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        assertThat(tx.getFlows().stream()
                .anyMatch(f -> f.getRole() == NormalizedLegRole.TRANSFER
                        && MLP_MINT.equals(f.getAssetContract()))).isTrue();
        // Gap 1: the MLP receipt leg is flagged lpReceipt (durable identity/flag route) so replay
        // stamps its ledger point FAMILY:LP_RECEIPT despite the confusable "MLP" symbol; the
        // underlying SOL/mSOL legs are NOT flagged (they stay priced spot families).
        assertThat(tx.getFlows().stream()
                .filter(f -> MLP_MINT.equals(f.getAssetContract())).findFirst().orElseThrow()
                .getLpReceipt()).isTrue();
        assertThat(tx.getFlows().stream()
                .filter(f -> !MLP_MINT.equals(f.getAssetContract()))
                .noneMatch(f -> Boolean.TRUE.equals(f.getLpReceipt()))).isTrue();
    }

    @Test
    @DisplayName("C1: Meteora DAMM terminal remove (userPoolLp rent reclaimed) is promoted to LP_EXIT_FINAL")
    void dammTerminalRemoveIsLpExitFinal() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY")
                .append("fee", 5_000L)
                .append("instructions", List.of(dammLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(DAMM_POOL, WALLET, SolanaProgramIds.WSOL_MINT, "SOL", 1.1),
                        tokenTransfer(DAMM_POOL, WALLET, MSOL_MINT, "MSOL", 0.95),
                        tokenTransfer(WALLET, DAMM_POOL, MLP_MINT, "MLP", 0.3096)))
                .append("accountData", List.of(
                        new Document("account", WALLET).append("nativeBalanceChange", 2_000_000L),
                        new Document("account", DAMM_USER_POOL_LP).append("nativeBalanceChange", -2_039_280L)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_FINAL);
        assertThat(tx.getCorrelationId())
                .isEqualTo("lp-position:solana:meteora-damm:" + DAMM_POOL + ":" + WALLET);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        // Gap 1: the burned MLP receipt leg on the terminal exit is also flagged, so its disposal
        // ledger point carries FAMILY:LP_RECEIPT.
        assertThat(tx.getFlows().stream()
                .filter(f -> MLP_MINT.equals(f.getAssetContract())).findFirst().orElseThrow()
                .getLpReceipt()).isTrue();
    }

    @Test
    @DisplayName("C1: Meteora farm MLP stake books a non-priced TRANSFER, never a SELL (removes the mis-tagged STAKE loss)")
    void meteoraFarmStakeBooksTransferNotSell() {
        Document parsed = new Document("type", "STAKE_TOKEN")
                .append("fee", 5_000L)
                .append("instructions", List.of(instruction(SolanaProgramIds.METEORA_FARM,
                        List.of(WALLET, METEORA_FARM_ADDR, MLP_MINT))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, METEORA_FARM_ADDR, MLP_MINT, "MLP", 0.3096)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_POSITION_STAKE);
        // The mis-tag fix: the MLP staked into the farm must NOT be booked as a market disposal.
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow mlp = tx.getFlows().stream()
                .filter(f -> MLP_MINT.equals(f.getAssetContract())).findFirst().orElseThrow();
        assertThat(mlp.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(mlp.getQuantityDelta().signum()).isNegative();
        // Gap 2 (auditor): the staked MLP leg is flagged lpReceipt so replay stamps FAMILY:LP_RECEIPT
        // on the stake bucket point too (not only LP_ENTRY) — otherwise C7's identity exclusion keys
        // on the latest bucket point and misses it.
        assertThat(mlp.getLpReceipt()).isTrue();
    }

    @Test
    @DisplayName("C1: Meteora farm MLP unstake books a non-priced inbound TRANSFER, never a BUY")
    void meteoraFarmUnstakeBooksTransferNotBuy() {
        Document parsed = new Document("type", "UNSTAKE_TOKEN")
                .append("fee", 5_000L)
                .append("instructions", List.of(instruction(SolanaProgramIds.METEORA_FARM,
                        List.of(WALLET, METEORA_FARM_ADDR, MLP_MINT))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(METEORA_FARM_ADDR, WALLET, MLP_MINT, "MLP", 0.3096)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow mlp = tx.getFlows().stream()
                .filter(f -> MLP_MINT.equals(f.getAssetContract())).findFirst().orElseThrow();
        assertThat(mlp.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(mlp.getQuantityDelta().signum()).isPositive();
        // Gap 2 (auditor): the unstaked MLP leg is flagged lpReceipt so the unstake bucket point also
        // resolves FAMILY:LP_RECEIPT (this is the bucket point C7 was previously keying on and missing).
        assertThat(mlp.getLpReceipt()).isTrue();
    }

    // --- RC-S8 (FIX B) / RC-S9 (FIX C): SPL symbol + stable-family resolution ---

    private static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String CAR_PUMP_MINT = "7oBYdEhV4GkXC19ZfgAvXpJWp2Rn9pm1Bx2cVNxFpump";

    private static Document tokenTransferNoSymbol(String from, String to, String mint, double amount) {
        return new Document("fromUserAccount", from)
                .append("toUserAccount", to)
                .append("mint", mint)
                .append("tokenAmount", amount);
    }

    private static Document computeBudgetInstruction() {
        return new Document("programId", SolanaProgramIds.COMPUTE_BUDGET_PROGRAM);
    }

    private static Document splTokenInstruction() {
        return new Document("programId", SolanaProgramIds.TOKEN_PROGRAM);
    }

    @Test
    @DisplayName("RC-S8 (FIX B): unlabeled outbound SPL transfer of an unknown mint books EXTERNAL_TRANSFER_OUT in PENDING_PRICE with a well-formed flow")
    void unknownMintOutboundSplTransferBooksExternalOut() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("instructions", List.of(computeBudgetInstruction(), splTokenInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransferNoSymbol(WALLET, PEER, CAR_PUMP_MINT, 149.589668)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getAssetContract()).isEqualTo(CAR_PUMP_MINT);
        assertThat(principal.getQuantityDelta().doubleValue()).isEqualTo(-149.589668);
        assertThat(principal.getCounterpartyAddress()).isEqualTo(PEER);
        // Unknown/unseeded mint keeps a null symbol — the flow is still well-formed and prices by contract.
        assertThat(principal.getAssetSymbol()).isNull();
    }

    @Test
    @DisplayName("RC-S9 (FIX C): outbound USDC transfer with null Helius symbol resolves symbol USDC → STABLE_USD family")
    void usdcTransferResolvesStableUsdFamily() {
        Document parsed = new Document("type", "TRANSFER")
                .append("fee", 5_000L)
                .append("instructions", List.of(splTokenInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransferNoSymbol(WALLET, PEER, USDC_MINT, 250.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getAssetContract()).isEqualTo(USDC_MINT);
        assertThat(principal.getAssetSymbol()).isEqualTo("USDC");
        assertThat(AssetFamily.resolve(principal.getAssetSymbol())).isEqualTo("STABLE_USD");
    }

    @Test
    @DisplayName("RC-S9 (FIX C): inbound USDT transfer with null Helius symbol resolves symbol USDT → STABLE_USD family")
    void usdtTransferResolvesStableUsdFamily() {
        Document parsed = new Document("type", "TRANSFER")
                .append("fee", 5_000L)
                .append("instructions", List.of(splTokenInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransferNoSymbol(PEER, WALLET, USDT_MINT, 100.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getAssetSymbol()).isEqualTo("USDT");
        assertThat(AssetFamily.resolve(principal.getAssetSymbol())).isEqualTo("STABLE_USD");
    }

    // --- RULE 1: Jupiter Lend borrow / loop leg building ---

    private static final String JL_RESERVE = "7s1da8DduuBFqGra5bJBjpnvL5E9mGzCuMk1Qkh4or2Z";
    private static final String JL_RECEIPT_MINT = "4XuocgW9zK5ozuaCQMLjRHkvzeT63D3jwpNKKHaE7BY5";

    private static Document lendInstruction() {
        return instruction(SolanaProgramIds.JUPITER_LEND, List.of(WALLET, JL_RESERVE));
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend borrow emits the inbound USDT BUY leg (no dropped inbound, no phantom SELL) with a stable loan correlation")
    void jupiterLendBorrowEmitsInboundBuyLeg() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("instructions", List.of(lendInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(JL_RESERVE, WALLET, USDT_MINT, "USDT", 210.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BORROW);
        assertThat(tx.getCorrelationId())
                .isEqualTo("solana:jupiter-lend:" + USDT_MINT + ":" + WALLET);
        NormalizedTransaction.Flow borrow = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY).findFirst().orElseThrow();
        assertThat(borrow.getAssetContract()).isEqualTo(USDT_MINT);
        assertThat(borrow.getAssetSymbol()).isEqualTo("USDT");
        assertThat(borrow.getQuantityDelta().doubleValue()).isEqualTo(210.0);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.SELL);
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend loop carries only the net SOL collateral leg — no phantom USDT SELL legs")
    void jupiterLendLoopCarriesSolOnlyNoPhantomUsdt() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("instructions", List.of(lendInstruction(), instruction(SolanaProgramIds.JUPITER_SWAP_V6, List.of(WALLET))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(JL_RESERVE, WALLET, USDT_MINT, "USDT", 15.81),
                        tokenTransfer(WALLET, "pool", USDT_MINT, "USDT", 15.81),
                        tokenTransfer(WALLET, JL_RESERVE, SolanaProgramIds.WSOL_MINT, "SOL", 0.10)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_OPEN);
        assertThat(tx.getFlows()).noneMatch(f -> USDT_MINT.equals(f.getAssetContract()));
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getAssetContract()).isEqualTo(SolanaProgramIds.WSOL_MINT);
        assertThat(principal.getQuantityDelta().signum()).isNegative();
    }

    @Test
    @DisplayName("WS-1 B1: Jupiter Lend loop from accountData sums NATIVE SOL collateral + dust wSOL residual (no empty leg)")
    void jupiterLendLoopFromAccountDataExtractsNetCollateral() {
        // Mirrors anchor 5YMocs…: the wallet wraps ~0.1 SOL as collateral (native −100105001 lamports,
        // fee 105000 re-added) and only a dust wSOL token residual (+159795) stays in the ATA. The
        // net collateral leg must be ~−0.09984 SOL, not the +0.00016 dust the old guard emitted.
        long fee = 105_000L;
        Document walletAccount = new Document("account", WALLET)
                .append("nativeBalanceChange", -100_105_001L)
                .append("tokenBalanceChanges", List.of(new Document("userAccount", WALLET)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("rawTokenAmount", new Document("tokenAmount", "159795").append("decimals", 9))));
        Document parsed = new Document("type", "TRANSFER")
                .append("source", "SYSTEM_PROGRAM")
                .append("fee", fee)
                .append("instructions", List.of(
                        lendInstruction(),
                        instruction(SolanaProgramIds.JUPITER_SWAP_V6, List.of(WALLET))))
                .append("accountData", List.of(walletAccount));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_OPEN);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getAssetContract()).isEqualTo(SolanaProgramIds.WSOL_MINT);
        assertThat(principal.getQuantityDelta().doubleValue()).isEqualTo(-0.099840206);
    }

    @Test
    @DisplayName("WS-9: SOL→wSOL wrap deposit is not double-counted — 0.5 SOL supply books −0.5, not −1.0 (anchor 2aeksw…)")
    void jupiterLendWrapDepositIsNotDoubleCounted() {
        // Anchor 2aekswNmbc…: the wallet supplies 0.5 SOL to Jupiter Lend. The wrap emits BOTH a
        // nativeTransfer of 0.5 SOL into the wallet's temporary wSOL ATA AND a wSOL tokenTransfer of
        // 0.5 out to the reserve for the SAME lamports. No wSOL token account is owned by the wallet
        // afterwards, so the old sawAccountData guard fell back to summing transfers and booked
        // −1.0 SOL. The authoritative wallet nativeBalanceChange (−0.500105001, fee re-added) yields
        // the true −0.5 SOL collateral leg.
        String tempWsolAta = "8rndoC1dpgEYteZZRdu96tqBV8TK5v1xC9fMYSNebYJa";
        long fee = 105_000L;
        Document walletAccount = new Document("account", WALLET)
                .append("nativeBalanceChange", -500_105_001L)
                .append("tokenBalanceChanges", List.of());
        Document parsed = new Document("type", "TRANSFER")
                .append("source", "SYSTEM_PROGRAM")
                .append("fee", fee)
                .append("instructions", List.of(lendInstruction()))
                .append("accountData", List.of(walletAccount))
                .append("nativeTransfers", List.of(new Document("fromUserAccount", WALLET)
                        .append("toUserAccount", tempWsolAta)
                        .append("amount", 500_000_001L)))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, JL_RESERVE, SolanaProgramIds.WSOL_MINT, "SOL", 0.500000001)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
        List<NormalizedTransaction.Flow> collateralLegs = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER
                        && SolanaProgramIds.WSOL_MINT.equals(f.getAssetContract()))
                .toList();
        assertThat(collateralLegs).hasSize(1);
        assertThat(collateralLegs.get(0).getQuantityDelta().doubleValue()).isEqualTo(-0.500000001);
    }

    @Test
    @DisplayName("WS-1: earn-only Jupiter Lend program (no borrow router) still classifies as a Jupiter Lend supply")
    void jupiterLendEarnProgramClassifiesAsLending() {
        String earnProgram = SolanaProgramIds.JUPITER_LEND_PROGRAM_IDS.stream()
                .filter(id -> !id.equals(SolanaProgramIds.JUPITER_LEND))
                .findFirst().orElseThrow();
        Document parsed = new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("instructions", List.of(instruction(earnProgram, List.of(WALLET, JL_RESERVE))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, JL_RESERVE, SolanaProgramIds.WSOL_MINT, "SOL", 1.5)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(tx.getProtocolName()).isEqualTo("Jupiter Lend");
    }

    @Test
    @DisplayName("RULE 1: Jupiter Lend withdraw returns the SOL collateral as an inbound TRANSFER (receipt excluded)")
    void jupiterLendWithdrawEmitsInboundTransfer() {
        Document parsed = new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("instructions", List.of(lendInstruction()))
                .append("tokenTransfers", List.of(
                        tokenTransfer(JL_RESERVE, WALLET, SolanaProgramIds.WSOL_MINT, "SOL", 0.939),
                        tokenTransfer(WALLET, JL_RESERVE, JL_RECEIPT_MINT, "jlSOL", 0.939)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(tx.getFlows()).noneMatch(f -> JL_RECEIPT_MINT.equals(f.getAssetContract()));
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getAssetContract()).isEqualTo(SolanaProgramIds.WSOL_MINT);
        assertThat(principal.getQuantityDelta().signum()).isPositive();
    }

    @Test
    @DisplayName("RC-S-LP: Meteora Vault deposit books basis-carrying TRANSFER-out (no market disposal)")
    void vaultDepositBooksTransferOut() {
        Document parsed = new Document("type", "DEPOSIT")
                .append("fee", 5_000L)
                .append("instructions", List.of(instruction(SolanaProgramIds.METEORA_VAULT, List.of(WALLET, "vault"))))
                .append("tokenTransfers", List.of(
                        tokenTransfer(WALLET, "vault", SolanaProgramIds.WSOL_MINT, "SOL", 1.0)));

        NormalizedTransaction tx = builder.build(raw(parsed), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.SELL);
        NormalizedTransaction.Flow principal = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(principal.getQuantityDelta().signum()).isNegative();
    }
}
