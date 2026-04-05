package com.walletradar.pricing.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.event.EventLocalPriceResolverChain;
import com.walletradar.pricing.resolver.event.ExecutionPriceResolver;
import com.walletradar.pricing.resolver.event.StablecoinPriceResolver;
import com.walletradar.pricing.resolver.event.SwapDerivedPriceResolver;
import com.walletradar.pricing.resolver.event.WrapperPriceResolver;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceResolutionServiceTest {

    @Mock
    private PriceExternalSourceOrchestrator externalSources;

    @Test
    void stablecoinSwapAnchorUsesTxLocalRatioBeforeExternalLookup() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.SWAP,
                null,
                flow(NormalizedLegRole.SELL, "0x4200000000000000000000000000000000000006", "WETH", "-1"),
                flow(NormalizedLegRole.BUY, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", "2000")
        );

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(priced.getMissingDataReasons()).doesNotContain(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON);
        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("2000");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.SWAP_DERIVED);
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        verify(externalSources, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void twoLegSwapUsesExternalAnchorOnceThenDerivesCounterpart() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.SWAP,
                null,
                flow(NormalizedLegRole.SELL, "0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "WETH", "-1"),
                flow(NormalizedLegRole.BUY, "0x912ce59144191c1204e64559fe8253a0e49e6548", "ARB", "1000")
        );
        when(externalSources.resolve(argThat(matchesSymbol("WETH")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2000"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "ETHUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("2000");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.BINANCE);
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("2");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.SWAP_DERIVED);
        verify(externalSources).resolve(argThat(matchesSymbol("WETH")));
        verify(externalSources, never()).resolve(argThat(matchesSymbol("ARB")));
    }

    @Test
    void multiLegSameCanonicalSwapFallsBackToExternalPriceInsteadOfSwapDerived() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.SWAP,
                null,
                flow(NormalizedLegRole.SELL, "0x000000000000000000000000000000000000800a", "ETH", "-0.00005147300208"),
                flow(NormalizedLegRole.BUY, "0x000000000000000000000000000000000000800a", "ETH", "0.00001012463808"),
                flow(NormalizedLegRole.BUY, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", "800.14231")
        );
        when(externalSources.resolve(argThat(matchesSymbol("ETH")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("3200"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "ETHUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("3200");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.BINANCE);
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("3200");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.WRAPPER);
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(priced.getFlows().get(2).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        verify(externalSources).resolve(argThat(matchesSymbol("ETH")));
    }

    @Test
    void lpContinuityPrincipalDoesNotRequireSyntheticPrincipalPricing() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.LP_ENTRY,
                null,
                flow(NormalizedLegRole.TRANSFER, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", "-1000"),
                flow(NormalizedLegRole.TRANSFER, null, "ETH", "-1"),
                flow(NormalizedLegRole.FEE, null, "ETH", "-0.01")
        );
        when(externalSources.resolve(argThat(matchesSymbol("ETH")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2500"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "ETHUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("2500");
        assertThat(priced.getFlows().get(2).getPriceSource()).isEqualTo(PriceSource.BINANCE);
    }

    @Test
    void continuityTypeStillPricesExplicitRewardSideFlow() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.LP_EXIT,
                null,
                flow(NormalizedLegRole.TRANSFER, "0xe6829d9a7ee3040e1276fa75293bde931859e8fa", "cmETH", "0.862092260317885000"),
                flow(NormalizedLegRole.BUY, "0xd27b18915e7acc8fd6ac75db6766a80f8d2f5729", "PENDLE", "0.012731662739929251"),
                flow(NormalizedLegRole.FEE, null, "MNT", "-0.0743242821")
        );
        when(externalSources.resolve(argThat(matchesSymbol("PENDLE")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("5"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "PENDLEUSDT"
        )));
        when(externalSources.resolve(argThat(matchesSymbol("MNT")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("0.8"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "MNTUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("5");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.BINANCE);
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("0.8");
        assertThat(priced.getFlows().get(2).getPriceSource()).isEqualTo(PriceSource.BINANCE);
    }

    @Test
    void classicStakingContinuityPricesRewardButNotPrincipal() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.STAKING_DEPOSIT,
                null,
                flow(NormalizedLegRole.TRANSFER, "0x0e09fabb73bd3ade0a17ecc321fd13a19e81ce82", "CAKE", "-1"),
                flow(NormalizedLegRole.BUY, "0x1234", "U", "0.5"),
                flow(NormalizedLegRole.FEE, null, "BNB", "-0.001")
        );
        when(externalSources.resolve(argThat(matchesSymbol("U")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "UUSDT"
        )));
        when(externalSources.resolve(argThat(matchesSymbol("BNB")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("600"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "BNBUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("2");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.BINANCE);
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("600");
        verify(externalSources, never()).resolve(argThat(matchesSymbol("CAKE")));
        verify(externalSources).resolve(argThat(matchesSymbol("U")));
    }

    @Test
    void asyncDexOrderRequestDoesNotRequireSyntheticPrincipalPricing() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.DEX_ORDER_REQUEST,
                "cow-order:1",
                flow(NormalizedLegRole.SELL, null, "ETH", "-0.027638811423349461"),
                flow(NormalizedLegRole.FEE, null, "ETH", "-0.0000006453781")
        );
        when(externalSources.resolve(argThat(matchesSymbol("ETH")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2500"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "ETHUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("2500");
        verify(externalSources).resolve(argThat(matchesSymbol("ETH")));
    }

    @Test
    void bridgeContinuityPrincipalDoesNotRequireSyntheticPrincipalPricing() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.BRIDGE_OUT,
                "bridge-correlation-1",
                flow(NormalizedLegRole.TRANSFER, null, "ETH", "-2"),
                flow(NormalizedLegRole.FEE, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", "-3")
        );

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        verify(externalSources, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bybitTradeReusesExecutionPriceWithoutExternalPrincipalLookup() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.BYBIT,
                NormalizedTransactionType.SWAP,
                null,
                flowWithPrice(NormalizedLegRole.BUY, null, "BTC", "0.1", "60000", PriceSource.EXECUTION),
                flow(NormalizedLegRole.SELL, null, "USDT", "-6000"),
                flow(NormalizedLegRole.FEE, null, "USDT", "-10")
        );

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("60000");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.EXECUTION);
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("1");
        verify(externalSources, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void matchedBybitTransferPricesOnlyFeeAndPreservesPrincipalContinuity() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.BYBIT,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                "corr-1",
                flow(NormalizedLegRole.SELL, null, "BTC", "-0.25"),
                flow(NormalizedLegRole.FEE, null, "USDT", "-5")
        );
        transaction.setContinuityCandidate(true);

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        verify(externalSources, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void eulerLoopPrincipalKeepsPreResolvedTxLocalPrices() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.LENDING_LOOP_DECREASE,
                null,
                flowWithPrice(NormalizedLegRole.SELL, "0x39de0f00189306062d79edec6dca5bb6bfd108f9", "eUSDC-2", "-1444.356263", "0.7196480577321642477719817124575103249", PriceSource.SWAP_DERIVED),
                flowWithPrice(NormalizedLegRole.BUY, "0xb57b25851fe2311cc3fe511c8f10e868932e0680", "deUSD", "1039.254268973979242470", "1", PriceSource.STABLECOIN),
                flow(NormalizedLegRole.FEE, null, "AVAX", "-0.0004")
        );
        when(externalSources.resolve(argThat(matchesSymbol("AVAX")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("25"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "AVAXUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("0.7196480577321642477719817124575103249");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.SWAP_DERIVED);
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isEqualByComparingTo("1");
        assertThat(priced.getFlows().get(1).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("25");
        verify(externalSources).resolve(argThat(matchesSymbol("AVAX")));
        verify(externalSources, never()).resolve(argThat(matchesSymbol("eUSDC-2")));
        verify(externalSources, never()).resolve(argThat(matchesSymbol("deUSD")));
    }

    @Test
    void eulerLoopRebalancePricesOnlyFeeAndLeavesTransferLegsUntouched() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                null,
                flow(NormalizedLegRole.TRANSFER, "0x39de0f00189306062d79edec6dca5bb6bfd108f9", "eUSDC-2", "-1444.591868"),
                flow(NormalizedLegRole.TRANSFER, "0xa45189636c04388adbb4d865100dd155e55682ec", "edeUSD-1", "2.011887556269756470"),
                flow(NormalizedLegRole.FEE, null, "AVAX", "-0.0009")
        );
        when(externalSources.resolve(argThat(matchesSymbol("AVAX")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("25"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "AVAXUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(1).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(2).getUnitPriceUsd()).isEqualByComparingTo("25");
        verify(externalSources).resolve(argThat(matchesSymbol("AVAX")));
        verify(externalSources, never()).resolve(argThat(matchesSymbol("eUSDC-2")));
        verify(externalSources, never()).resolve(argThat(matchesSymbol("edeUSD-1")));
    }

    @Test
    void unmatchedBybitOutboundFallsBackToExternalPricing() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.BYBIT,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                null,
                flow(NormalizedLegRole.SELL, null, "ETH", "-0.5")
        );
        when(externalSources.resolve(argThat(matchesSymbol("ETH")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2500"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "ETHUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("2500");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.BINANCE);
        verify(externalSources).resolve(argThat(matchesSymbol("ETH")));
    }

    @Test
    void bybitRowWithoutNetworkIdStillPricesSafely() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.BYBIT,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                null,
                flow(NormalizedLegRole.SELL, null, "ETH", "-0.5")
        );
        transaction.setNetworkId(null);
        when(externalSources.resolve(argThat(matchesSymbol("ETH")))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2500"),
                PriceSource.BINANCE,
                transaction.getBlockTimestamp(),
                "USD",
                "ETHUSDT"
        )));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo("2500");
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.BINANCE);
        verify(externalSources).resolve(argThat(matchesSymbol("ETH")));
    }

    @Test
    void unresolvedPriceDoesNotBlockConfirmation() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                null,
                flow(NormalizedLegRole.BUY, "0xdead", "XYZ", "100")
        );
        when(externalSources.resolve(argThat(matchesSymbol("XYZ")))).thenReturn(Optional.empty());

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(priced.getMissingDataReasons()).contains(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON);
        assertThat(priced.getFlows().get(0).getUnitPriceUsd()).isNull();
        assertThat(priced.getFlows().get(0).getPriceSource()).isEqualTo(PriceSource.UNKNOWN);
    }

    @Test
    void stalePriceUnresolvableReasonClearsWhenAllRequiredFlowsArePriced() {
        PriceResolutionService service = service();
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionSource.ON_CHAIN,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                null,
                flow(NormalizedLegRole.BUY, "0xdead", "USDC", "100", "1", PriceSource.STABLECOIN)
        );
        transaction.setMissingDataReasons(List.of(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON));

        NormalizedTransaction priced = service.resolve(transaction, Instant.parse("2026-03-25T12:00:00Z"));

        assertThat(priced.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(priced.getMissingDataReasons()).doesNotContain(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON);
        verify(externalSources, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    private PriceResolutionService service() {
        EventLocalPriceResolverChain chain = new EventLocalPriceResolverChain(List.of(
                new StablecoinPriceResolver(),
                new ExecutionPriceResolver(),
                new SwapDerivedPriceResolver(),
                new WrapperPriceResolver()
        ));
        return new PriceResolutionService(chain, externalSources, new PricingResultMapper());
    }

    private NormalizedTransaction transaction(
            NormalizedTransactionSource source,
            NormalizedTransactionType type,
            String correlationId,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(source);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setCorrelationId(correlationId);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:15:45Z"));
        transaction.setFlows(List.of(flows));
        transaction.setMissingDataReasons(List.of());
        transaction.setPricingAttempts(0);
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetContract,
            String assetSymbol,
            String quantity
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(assetContract);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetContract,
            String assetSymbol,
            String quantity,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = flow(role, assetContract, assetSymbol, quantity);
        flow.setUnitPriceUsd(new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        return flow;
    }

    private NormalizedTransaction.Flow flowWithPrice(
            NormalizedLegRole role,
            String assetContract,
            String assetSymbol,
            String quantity,
            String unitPrice,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = flow(role, assetContract, assetSymbol, quantity);
        flow.setUnitPriceUsd(new BigDecimal(unitPrice));
        flow.setPriceSource(priceSource);
        flow.setValueUsd(flow.getQuantityDelta().abs().multiply(new BigDecimal(unitPrice)));
        return flow;
    }

    private ArgumentMatcher<PriceRequest> matchesSymbol(String symbol) {
        return request -> request != null && symbol.equals(request.assetSymbol());
    }
}
