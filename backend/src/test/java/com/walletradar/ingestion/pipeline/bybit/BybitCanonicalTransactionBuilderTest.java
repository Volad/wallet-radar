package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BybitCanonicalTransactionBuilderTest {

    @Test
    void tradePairStartsPendingPriceAndCarriesExecutionPrice() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw baseLeg = trade("buy-base", "BUY", "ETH", "1");
        baseLeg.setFeePaid(new BigDecimal("-0.001"));
        ExternalLedgerRaw quoteLeg = trade("buy-quote", "BUY", "USDT", "-2500");
        quoteLeg.setFilledPrice(new BigDecimal("2500"));

        var transaction = builder.buildTradePair(baseLeg, quoteLeg, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(transaction.getFlows()).hasSize(3);
        assertThat(transaction.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(transaction.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.EXECUTION);
        assertThat(transaction.getFlows().get(1).getRole()).isEqualTo(NormalizedLegRole.SELL);
        assertThat(transaction.getFlows().get(2).getRole()).isEqualTo(NormalizedLegRole.FEE);
    }

    @Test
    void stablecoinTradeAssignsExecutionPriceToRiskAssetAndOneDollarToQuoteAndFee() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw usdtBuy = trade("usdt-buy", "BUY", "USDT", "42.9606579");
        usdtBuy.setFilledPrice(new BigDecimal("3307.21"));
        usdtBuy.setFeePaid(new BigDecimal("-0.0429606579"));
        ExternalLedgerRaw ethSell = trade("eth-sell", "SELL", "ETH", "-0.01299");
        ethSell.setFilledPrice(new BigDecimal("3307.21"));

        var transaction = builder.buildTradePair(usdtBuy, ethSell, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(transaction.getFlows()).hasSize(3);
        assertThat(transaction.getFlows().get(0).getAssetSymbol()).isEqualTo("USDT");
        assertThat(transaction.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(transaction.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(transaction.getFlows().get(0).getValueUsd()).isEqualByComparingTo("42.9606579");
        assertThat(transaction.getFlows().get(1).getAssetSymbol()).isEqualTo("ETH");
        assertThat(transaction.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("3307.21");
        assertThat(transaction.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.EXECUTION);
        assertThat(transaction.getFlows().get(1).getValueUsd()).isEqualByComparingTo("42.9606579");
        assertThat(transaction.getFlows().get(2).getAssetSymbol()).isEqualTo("USDT");
        assertThat(transaction.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(transaction.getFlows().get(2).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(transaction.getFlows().get(2).getValueUsd()).isEqualByComparingTo("0.0429606579");
    }

    @Test
    void transferOnlyVaultRowStartsConfirmed() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("vault-1");
        row.setUid("uid-1");
        row.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        row.setCanonicalType("VAULT_DEPOSIT");
        row.setAssetSymbol("USDT");
        row.setQuantityRaw(new BigDecimal("-100"));

        var transaction = builder.buildMappedRow(row, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(transaction.getFlows()).hasSize(1);
        assertThat(transaction.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void malformedTradeWithoutRoleBecomesNeedsReviewInsteadOfThrowing() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("bad-trade");
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        row.setAssetSymbol("ETH");

        var transaction = builder.buildOrphanTrade(row, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(transaction.getMissingDataReasons()).contains("UTA_TRADE_ROLE_MISSING");
        assertThat(transaction.getExcludedFromAccounting()).isFalse();
    }

    @Test
    void orphanTradeIsExcludedFromAccountingWhenPairIsMissing() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw row = trade("orphan-buy", "BUY", "USDT", "5");
        row.setFilledPrice(new BigDecimal("246.06"));

        var transaction = builder.buildOrphanTrade(row, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(transaction.getExcludedFromAccounting()).isTrue();
        assertThat(transaction.getAccountingExclusionReason()).isEqualTo("UTA_TRADE_PAIR_NOT_FOUND");
        assertThat(transaction.getFlows()).hasSize(1);
    }

    @Test
    void legacyExternalInboundMapsToExternalTransferIn() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("legacy-inbound");
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        row.setCanonicalType("EXTERNAL_INBOUND");
        row.setBasisRelevant(true);
        row.setAssetSymbol("USDT");
        row.setQuantityRaw(new BigDecimal("100"));

        var transaction = builder.buildMappedRow(row, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(transaction.getFlows()).hasSize(1);
        assertThat(transaction.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
    }

    @Test
    void rewardClaimFallsBackToCashFlowWhenQuantityRawMissing() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("bonus-1");
        row.setUid("33625378");
        row.setWalletRef("BYBIT:33625378");
        row.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        row.setCanonicalType("REWARD_CLAIM");
        row.setBasisRelevant(true);
        row.setAssetSymbol("USDT");
        row.setCashFlow(new BigDecimal("0.2"));
        row.setChange(new BigDecimal("0.2"));

        var transaction = builder.buildMappedRow(row, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(transaction.getFlows()).hasSize(1);
        assertThat(transaction.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(transaction.getFlows().get(0).getQuantityDelta()).isEqualByComparingTo("0.2");
    }

    @Test
    void unmappedBasisRelevantCanonicalTypeBecomesNeedsReview() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("bad-canonical");
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        row.setCanonicalType("SOMETHING_NEW");
        row.setBasisRelevant(true);
        row.setAssetSymbol("USDT");
        row.setQuantityRaw(new BigDecimal("100"));

        var transaction = builder.buildMappedRow(row, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(transaction.getMissingDataReasons()).contains("BYBIT_CANONICAL_TYPE_UNMAPPED");
    }

    @Test
    void nonStablecoinPairUsesQuantitySignsToAssignBuyAndSellRoles() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw left = new ExternalLedgerRaw();
        left.setId("sol-leg");
        left.setUid("uid-1");
        left.setWalletRef("BYBIT:uid-1");
        left.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        left.setUtaDirection("SELL");
        left.setUtaContract("BBSOLSOL");
        left.setAssetSymbol("SOL");
        left.setQuantityRaw(new BigDecimal("2.043456"));
        left.setFilledPrice(new BigDecimal("1.101"));
        left.setFeePaid(new BigDecimal("-0.002043456"));

        ExternalLedgerRaw right = new ExternalLedgerRaw();
        right.setId("bbsol-leg");
        right.setUid("uid-1");
        right.setWalletRef("BYBIT:uid-1");
        right.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        right.setUtaDirection("SELL");
        right.setUtaContract("BBSOLSOL");
        right.setAssetSymbol("BBSOL");
        right.setQuantityRaw(new BigDecimal("-1.856"));
        right.setFilledPrice(new BigDecimal("1.101"));

        var transaction = builder.buildTradePair(left, right, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(transaction.getFlows()).hasSize(3);
        assertThat(transaction.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(transaction.getFlows().get(0).getAssetSymbol()).isEqualTo("SOL");
        assertThat(transaction.getFlows().get(1).getRole()).isEqualTo(NormalizedLegRole.SELL);
        assertThat(transaction.getFlows().get(1).getAssetSymbol()).isEqualTo("BBSOL");
    }

    @Test
    void convertClusterAggregatesMultipleSellLegsIntoOneSwap() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw sellA = convert("sell-a", "COOK", "-1", Instant.parse("2026-03-25T12:00:00Z"));
        ExternalLedgerRaw sellB = convert("sell-b", "ONDO", "-2", Instant.parse("2026-03-25T12:00:01Z"));
        ExternalLedgerRaw buyA = convert("buy-a", "MNT", "0.2", Instant.parse("2026-03-25T12:00:02Z"));
        ExternalLedgerRaw buyB = convert("buy-b", "MNT", "0.3", Instant.parse("2026-03-25T12:00:02Z"));

        var transaction = builder.buildConvertCluster(
                java.util.List.of(sellA, sellB, buyA, buyB),
                Instant.parse("2026-03-25T12:01:00Z")
        );

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(transaction.getFlows()).hasSize(3);
        assertThat(transaction.getFlows())
                .anySatisfy(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY);
                    assertThat(flow.getAssetSymbol()).isEqualTo("MNT");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.5");
                })
                .anySatisfy(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL);
                    assertThat(flow.getAssetSymbol()).isEqualTo("COOK");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-1");
                })
                .anySatisfy(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL);
                    assertThat(flow.getAssetSymbol()).isEqualTo("ONDO");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-2");
                });
    }

    @Test
    void stakingPairKeepsSameFamilyLiquidStakingAsContinuityTransfer() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw stake = new ExternalLedgerRaw();
        stake.setId("stake");
        stake.setUid("33625378");
        stake.setWalletRef("BYBIT:33625378");
        stake.setTimeUtc(Instant.parse("2025-03-12T20:08:36Z"));
        stake.setSourceFileType("fund_asset_changes");
        stake.setBybitType("ETH 2.0");
        stake.setCanonicalType("STAKING_DEPOSIT");
        stake.setAssetSymbol("ETH");
        stake.setQuantityRaw(new BigDecimal("-0.709"));

        ExternalLedgerRaw mint = new ExternalLedgerRaw();
        mint.setId("mint");
        mint.setUid("33625378");
        mint.setWalletRef("BYBIT:33625378");
        mint.setTimeUtc(Instant.parse("2025-03-12T20:37:05Z"));
        mint.setSourceFileType("fund_asset_changes");
        mint.setBybitType("ETH 2.0");
        mint.setCanonicalType("STAKING_DEPOSIT");
        mint.setAssetSymbol("METH");
        mint.setQuantityRaw(new BigDecimal("0.66865026"));

        var transaction = builder.buildStakingPair(stake, mint, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(transaction.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(transaction.getFlows())
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole().name() + ":" + flow.getQuantityDelta())
                .containsExactlyInAnyOrder(
                        "ETH:TRANSFER:-0.709",
                        "METH:TRANSFER:0.66865026"
                );
    }

    @Test
    void duplicateWithdrawDepositRowsShareSameCanonicalId() {
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        ExternalLedgerRaw first = new ExternalLedgerRaw();
        first.setId("raw-1");
        first.setUid("33625378");
        first.setWalletRef("BYBIT:33625378");
        first.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        first.setSourceFileType("withdraw_deposit");
        first.setCanonicalType("EXTERNAL_TRANSFER_IN");
        first.setTxHash("0xabc");
        first.setNetworkId(com.walletradar.domain.common.NetworkId.ARBITRUM);
        first.setAssetSymbol("USDC");
        first.setQuantityRaw(new BigDecimal("10.5"));

        ExternalLedgerRaw duplicate = new ExternalLedgerRaw();
        duplicate.setId("raw-2");
        duplicate.setUid("33625378");
        duplicate.setWalletRef("BYBIT:33625378");
        duplicate.setTimeUtc(Instant.parse("2026-03-25T12:00:01Z"));
        duplicate.setSourceFileType("withdraw_deposit");
        duplicate.setCanonicalType("EXTERNAL_TRANSFER_IN");
        duplicate.setTxHash("0xabc");
        duplicate.setNetworkId(com.walletradar.domain.common.NetworkId.ARBITRUM);
        duplicate.setAssetSymbol("USDC");
        duplicate.setQuantityRaw(new BigDecimal("10.500000"));

        var left = builder.buildMappedRow(first, Instant.parse("2026-03-25T12:01:00Z"));
        var right = builder.buildMappedRow(duplicate, Instant.parse("2026-03-25T12:01:00Z"));

        assertThat(left.getId()).isEqualTo(right.getId());
        assertThat(left.getId()).contains("EXTERNAL_TRANSFER_IN");
    }

    private ExternalLedgerRaw trade(String id, String direction, String assetSymbol, String quantityRaw) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setTimeUtc(Instant.parse("2026-03-25T12:00:00Z"));
        row.setUtaDirection(direction);
        row.setUtaContract("ETHUSDT");
        row.setAssetSymbol(assetSymbol);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        row.setFilledPrice(new BigDecimal("2500"));
        return row;
    }

    private ExternalLedgerRaw convert(String id, String assetSymbol, String quantityRaw, Instant timeUtc) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setTimeUtc(timeUtc);
        row.setSourceFileType("fund_asset_changes");
        row.setBybitType("Convert");
        row.setCanonicalType("SWAP");
        row.setAssetSymbol(assetSymbol);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        return row;
    }
}
