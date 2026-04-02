package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetPosition;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.costbasis.domain.ReconciliationStatus;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvcoReplayServiceTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AssetPositionRepository assetPositionRepository;

    @Test
    void deterministicReplayOrderingUsesIdAsFinalTieBreaker() {
        NormalizedTransaction sell = tx("b", "0xsell", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", "20", PriceSource.BINANCE));
        NormalizedTransaction buy = tx("a", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "10", PriceSource.BINANCE));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sell, buy));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<NormalizedTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(txCaptor.capture());
        NormalizedTransaction replayedSell = txCaptor.getValue().stream()
                .filter(tx -> "b".equals(tx.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(replayedSell.getFlows().get(0).getAvcoAtTimeOfSale()).isEqualByComparingTo("10");
        assertThat(replayedSell.getFlows().get(0).getRealisedPnlUsd()).isEqualByComparingTo("10");
    }

    @Test
    void transferCarryOverMovesBasisWithoutDoubleCounting() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE));
        sourceBuy.setWalletAddress("wallet-a");
        NormalizedTransaction sourceTransfer = tx("2", "0xtransfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        sourceTransfer.setWalletAddress("wallet-a");
        sourceTransfer.setContinuityCandidate(true);
        sourceTransfer.setMatchedCounterparty("wallet-b");
        NormalizedTransaction destTransfer = tx("3", "0xtransfer", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        destTransfer.setWalletAddress("wallet-b");
        destTransfer.setContinuityCandidate(true);
        destTransfer.setMatchedCounterparty("wallet-a");
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, sourceTransfer, destTransfer));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition source = captor.getValue().stream().filter(position -> "wallet-a".equals(position.getWalletAddress())).findFirst().orElseThrow();
        AssetPosition dest = captor.getValue().stream().filter(position -> "wallet-b".equals(position.getWalletAddress())).findFirst().orElseThrow();
        assertThat(source.getQuantity()).isZero();
        assertThat(dest.getQuantity()).isEqualByComparingTo("1");
        assertThat(dest.getPerWalletAvco()).isEqualByComparingTo("100");
    }

    @Test
    void matchedBybitTransferDoesNotDoubleCountAcrossAccountingUniverse() {
        NormalizedTransaction bybitBuy = tx("1", null, 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.EXECUTION));
        bybitBuy.setWalletAddress("BYBIT:uid-1");
        bybitBuy.setSource(NormalizedTransactionSource.BYBIT);
        bybitBuy.setNetworkId(null);

        NormalizedTransaction bybitTransfer = tx("2", "0xcorr", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null, null));
        bybitTransfer.setWalletAddress("BYBIT:uid-1");
        bybitTransfer.setSource(NormalizedTransactionSource.BYBIT);
        bybitTransfer.setNetworkId(null);
        bybitTransfer.setCorrelationId("corr-1");
        bybitTransfer.setContinuityCandidate(true);
        bybitTransfer.setMatchedCounterparty("0xwallet");

        NormalizedTransaction onChainTransfer = tx("3", "0xcorr", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", null, null));
        onChainTransfer.setWalletAddress("0xwallet");
        onChainTransfer.setCorrelationId("corr-1");
        onChainTransfer.setContinuityCandidate(true);
        onChainTransfer.setMatchedCounterparty("BYBIT:uid-1");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(bybitBuy, bybitTransfer, onChainTransfer));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        BigDecimal totalQty = captor.getValue().stream()
                .map(AssetPosition::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQty).isEqualByComparingTo("1");
    }

    @Test
    void correlatedBridgePairCarriesBasisAcrossNetworksWhenPlainMoveBasisIsProven() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", "1", PriceSource.STABLECOIN));
        sourceBuy.setNetworkId(NetworkId.BASE);

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-100", null, null));
        bridgeOut.setNetworkId(NetworkId.BASE);
        bridgeOut.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "USDC", "100", null, null));
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, bridgeOut, bridgeIn));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition base = captor.getValue().stream()
                .filter(position -> position.getNetworkId() == NetworkId.BASE)
                .findFirst()
                .orElseThrow();
        AssetPosition arbitrum = captor.getValue().stream()
                .filter(position -> position.getNetworkId() == NetworkId.ARBITRUM)
                .findFirst()
                .orElseThrow();

        assertThat(base.getQuantity()).isZero();
        assertThat(arbitrum.getQuantity()).isEqualByComparingTo("100");
        assertThat(arbitrum.getPerWalletAvco()).isEqualByComparingTo("1");
    }

    @Test
    void familyEquivalentBridgePairCarriesBasisAcrossNetworksEvenWhenDestinationQuantityDiffers() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "vbUSDC", "28.997378", "1", PriceSource.STABLECOIN));
        sourceBuy.setNetworkId(NetworkId.KATANA);
        sourceBuy.getFlows().getFirst().setAssetContract("0x203a662b0bd271a6ed5a60edfbd04bfce608fd36");

        NormalizedTransaction bridgeOut = tx("2", "0xbridge-out", 1, NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "vbUSDC", "-28.997378", null, null));
        bridgeOut.setNetworkId(NetworkId.KATANA);
        bridgeOut.getFlows().getFirst().setAssetContract("0x203a662b0bd271a6ed5a60edfbd04bfce608fd36");
        bridgeOut.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeOut.setContinuityCandidate(true);
        bridgeOut.setMatchedCounterparty("0xbridge-in");

        NormalizedTransaction bridgeIn = tx("3", "0xbridge-in", 2, NormalizedTransactionType.BRIDGE_IN,
                flow(NormalizedLegRole.TRANSFER, "USDC", "28.920966", null, null));
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.getFlows().getFirst().setAssetContract("0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        bridgeIn.setCorrelationId("bridge:lifi:0xbridge-out");
        bridgeIn.setContinuityCandidate(true);
        bridgeIn.setMatchedCounterparty("0xbridge-out");

        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, bridgeOut, bridgeIn));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition arbitrum = captor.getValue().stream()
                .filter(position -> position.getNetworkId() == NetworkId.ARBITRUM)
                .findFirst()
                .orElseThrow();

        assertThat(arbitrum.getQuantity()).isEqualByComparingTo("28.920966");
        assertThat(arbitrum.getTotalCostBasisUsd()).isEqualByComparingTo("28.997378");
        assertThat(arbitrum.getPerWalletAvco()).isEqualByComparingTo("1.002642097086245321127931895497543");
    }

    @Test
    void lpPrincipalContinuityUsesBucketAndIgnoresReceiptMarkers() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", "1", PriceSource.STABLECOIN));
        NormalizedTransaction lpEntry = tx("2", "0xlp-entry", 1, NormalizedTransactionType.LP_ENTRY,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-100", null, null),
                flow(NormalizedLegRole.TRANSFER, "BPT", "10", null, null));
        NormalizedTransaction lpExit = tx("3", "0xlp-exit", 2, NormalizedTransactionType.LP_EXIT,
                flow(NormalizedLegRole.TRANSFER, "BPT", "-10", null, null),
                flow(NormalizedLegRole.TRANSFER, "USDC", "100", null, null));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, lpEntry, lpExit));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(position -> {
            assertThat(position.getAssetSymbol()).isEqualTo("USDC");
            assertThat(position.getQuantity()).isEqualByComparingTo("100");
            assertThat(position.getPerWalletAvco()).isEqualByComparingTo("1");
            assertThat(position.getHasIncompleteHistory()).isFalse();
        });
    }

    @Test
    void lpExitBundleRestoresPrincipalAndKeepsRewardAsEconomicBuy() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "cmETH", "1", "100", PriceSource.BINANCE));
        NormalizedTransaction lpEntry = tx("2", "0xlp-entry", 1, NormalizedTransactionType.LP_ENTRY,
                flow(NormalizedLegRole.TRANSFER, "cmETH", "-1", null, null));
        NormalizedTransaction lpExitBundle = tx("3", "0xlp-exit-bundle", 2, NormalizedTransactionType.LP_EXIT,
                flow(NormalizedLegRole.TRANSFER, "cmETH", "1", null, null),
                flow(NormalizedLegRole.BUY, "PENDLE", "0.1", "5", PriceSource.BINANCE));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, lpEntry, lpExitBundle));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("cmETH".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("1");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("100");
            }
        }).anySatisfy(position -> {
            if ("PENDLE".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("0.1");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("5");
            }
        });
    }

    @Test
    void classicStakingContinuityMovesPrincipalIntoBucketAndKeepsRewardEconomic() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "CAKE", "10", "2", PriceSource.BINANCE));
        NormalizedTransaction stakingDeposit = tx("2", "0xstake", 1, NormalizedTransactionType.STAKING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "CAKE", "-5", null, null),
                flow(NormalizedLegRole.BUY, "U", "1", "3", PriceSource.BINANCE));
        NormalizedTransaction stakingWithdraw = tx("3", "0xunstake", 2, NormalizedTransactionType.STAKING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "CAKE", "5", null, null),
                flow(NormalizedLegRole.BUY, "U", "0.5", "4", PriceSource.BINANCE));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, stakingDeposit, stakingWithdraw));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("CAKE".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("10");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("2");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        }).anySatisfy(position -> {
            if ("U".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("1.5");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("3.333333333333333333333333333333333");
            }
        });
    }

    @Test
    void asyncLpRequestSettlementCarriesBasisAcrossCorrelationId() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USDC", "100", "1", PriceSource.STABLECOIN));
        NormalizedTransaction request = tx("2", "0xgmx-request", 1, NormalizedTransactionType.LP_ENTRY_REQUEST,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-100", null, null));
        request.setCorrelationId("gmx-deposit:1");
        request.setProtocolName("GMX");
        NormalizedTransaction settlement = tx("3", "0xgmx-settlement", 2, NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                flow(NormalizedLegRole.TRANSFER, "GM", "10", null, null));
        settlement.setCorrelationId("gmx-deposit:1");
        settlement.setProtocolName("GMX");
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, request, settlement));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("GM".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("10");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("10");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        }).anySatisfy(position -> {
            if ("USDC".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isZero();
            }
        });
    }

    @Test
    void asyncLpExitSettlementRestoresRefundCarryBeforeAllocatingShareBasis() {
        NormalizedTransaction shareBuy = tx("1", "0xshare-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "GLV", "10", "10", PriceSource.BINANCE));
        NormalizedTransaction ethBuy = tx("2", "0xeth-buy", 1, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "0.01", "2000", PriceSource.BINANCE));
        NormalizedTransaction request = tx("3", "0xgmx-exit-request", 2, NormalizedTransactionType.LP_EXIT_REQUEST,
                flow(NormalizedLegRole.TRANSFER, "GLV", "-10", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.001", null, null));
        request.setCorrelationId("gmx-withdrawal:1");
        request.setProtocolName("GMX");
        NormalizedTransaction settlement = tx("4", "0xgmx-exit-settlement", 3, NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                flow(NormalizedLegRole.TRANSFER, "WETH", "0.03", null, null),
                flow(NormalizedLegRole.TRANSFER, "ETH", "0.001", null, null));
        settlement.setCorrelationId("gmx-withdrawal:1");
        settlement.setProtocolName("GMX");
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(shareBuy, ethBuy, request, settlement));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("ETH".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("0.01");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("2000");
            }
        }).anySatisfy(position -> {
            if ("WETH".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("0.03");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("3333.333333333333333333333333333333");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        }).anySatisfy(position -> {
            if ("GLV".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isZero();
            }
        });
    }

    @Test
    void asyncDexOrderRequestSettlementRealisesSourcePnlAndSeedsDestinationBasis() {
        NormalizedTransaction sourceBuy = tx("1", "0xbuy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "2000", PriceSource.BINANCE));
        NormalizedTransaction request = tx("2", "0xcow-request", 1, NormalizedTransactionType.DEX_ORDER_REQUEST,
                flow(NormalizedLegRole.SELL, "ETH", "-0.5", null, null));
        request.setCorrelationId("cow-order:1");
        request.setProtocolName("CoW Swap");
        NormalizedTransaction settlement = tx("3", "0xcow-settlement", 2, NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                flow(NormalizedLegRole.BUY, "wstETH", "0.4", "3000", PriceSource.BINANCE));
        settlement.setCorrelationId("cow-order:1");
        settlement.setProtocolName("CoW Swap");
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(sourceBuy, request, settlement));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<NormalizedTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(txCaptor.capture());
        NormalizedTransaction replayedRequest = txCaptor.getValue().stream()
                .filter(tx -> "2".equals(tx.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(replayedRequest.getFlows().getFirst().getAvcoAtTimeOfSale()).isEqualByComparingTo("2000");
        assertThat(replayedRequest.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("200");

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("ETH".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("0.5");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("2000");
            }
        }).anySatisfy(position -> {
            if ("wstETH".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("0.4");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("3000");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        });
    }

    @Test
    void resolvAsyncWithdrawCarriesBasisFromStakedReceiptIntoUnderlyingSettlement() {
        NormalizedTransaction stakedBuy = tx("1", "0xstake-buy", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "stRESOLV", "30", "2", PriceSource.BINANCE));
        NormalizedTransaction request = tx("2", "0xunstake-request", 1, NormalizedTransactionType.STAKING_WITHDRAW_REQUEST,
                flow(NormalizedLegRole.TRANSFER, "stRESOLV", "-30", null, null));
        request.setCorrelationId("resolv-unstake:0xwallet:30");
        request.setProtocolName("Resolv");
        NormalizedTransaction settlement = tx("3", "0xunstake-claim", 2, NormalizedTransactionType.STAKING_WITHDRAW,
                flow(NormalizedLegRole.TRANSFER, "RESOLV", "30", null, null));
        settlement.setCorrelationId("resolv-unstake:0xwallet:30");
        settlement.setProtocolName("Resolv");
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(stakedBuy, request, settlement));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("RESOLV".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("30");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("2");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        }).anySatisfy(position -> {
            if ("stRESOLV".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isZero();
            }
        });
    }

    @Test
    void eulerLoopShareFlowsReplayDeterministicallyWithoutCustomCarry() {
        NormalizedTransaction open = tx("1", "0xeuler-open", 0, NormalizedTransactionType.LENDING_LOOP_OPEN,
                flow(NormalizedLegRole.BUY, "eUSDC-2", "100", "1.02", PriceSource.SWAP_DERIVED));
        NormalizedTransaction decrease = tx("2", "0xeuler-decrease", 1, NormalizedTransactionType.LENDING_LOOP_DECREASE,
                flow(NormalizedLegRole.SELL, "eUSDC-2", "-40", "1.05", PriceSource.SWAP_DERIVED),
                flow(NormalizedLegRole.BUY, "USDC", "42", "1", PriceSource.STABLECOIN));
        NormalizedTransaction close = tx("3", "0xeuler-close", 2, NormalizedTransactionType.LENDING_LOOP_CLOSE,
                flow(NormalizedLegRole.SELL, "eUSDC-2", "-60", "1.10", PriceSource.SWAP_DERIVED),
                flow(NormalizedLegRole.BUY, "USDC", "66", "1", PriceSource.STABLECOIN));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(open, decrease, close));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("eUSDC-2".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isZero();
                assertThat(position.getTotalRealisedPnlUsd()).isEqualByComparingTo("6.00");
            }
        }).anySatisfy(position -> {
            if ("USDC".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("108");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("1");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        });
    }

    @Test
    void eulerLoopRebalanceCarriesBasisIntoReplacementShareWithoutRealisedPnl() {
        NormalizedTransaction open = tx("1", "0xeuler-open", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "eUSDC-2", "100", "1.02", PriceSource.BINANCE));
        NormalizedTransaction rebalance = tx("2", "0xeuler-rebalance", 1, NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                flow(NormalizedLegRole.TRANSFER, "eUSDC-2", "-100", null, null),
                flow(NormalizedLegRole.TRANSFER, "edeUSD-1", "2", null, null));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(open, rebalance));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("eUSDC-2".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isZero();
                assertThat(position.getTotalRealisedPnlUsd()).isZero();
            }
        }).anySatisfy(position -> {
            if ("edeUSD-1".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("2");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("51");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        });
    }

    @Test
    void eulerLoopRebalanceRestoresDustToSourceAssetBeforeMovingRemainingBasis() {
        NormalizedTransaction open = tx("1", "0xeuler-open", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "edeUSD-1", "2", "50", PriceSource.BINANCE));
        NormalizedTransaction rebalance = tx("2", "0xeuler-rebalance", 1, NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                flow(NormalizedLegRole.TRANSFER, "edeUSD-1", "-2", null, null),
                flow(NormalizedLegRole.TRANSFER, "edeUSD-1", "0.1", null, null),
                flow(NormalizedLegRole.TRANSFER, "eUSDC-2", "1", null, null));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(open, rebalance));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(position -> {
            if ("edeUSD-1".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("0.1");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("50");
            }
        }).anySatisfy(position -> {
            if ("eUSDC-2".equals(position.getAssetSymbol())) {
                assertThat(position.getQuantity()).isEqualByComparingTo("1");
                assertThat(position.getPerWalletAvco()).isEqualByComparingTo("95");
                assertThat(position.getHasIncompleteHistory()).isFalse();
            }
        });
    }

    @Test
    void feeHandlingReducesFeeAssetAndTracksGasUsd() {
        NormalizedTransaction feeTx = tx("fee", "0xfee", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "100", PriceSource.BINANCE),
                flow(NormalizedLegRole.FEE, "ETH", "-0.1", "100", PriceSource.BINANCE));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(feeTx));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition position = captor.getValue().get(0);
        assertThat(position.getQuantity()).isEqualByComparingTo("0.9");
        assertThat(position.getTotalGasPaidUsd()).isEqualByComparingTo("10");
    }

    @Test
    void unknownPricePropagatesIncompleteHistory() {
        NormalizedTransaction unknownBuy = tx("1", "0xunknown", 0, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "TOKEN", "10", null, PriceSource.UNKNOWN));
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(unknownBuy));

        AvcoReplayService service = service();
        service.replayConfirmed();

        ArgumentCaptor<List<AssetPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetPositionRepository).saveAll(captor.capture());
        AssetPosition position = captor.getValue().get(0);
        assertThat(position.getQuantity()).isEqualByComparingTo("10");
        assertThat(position.getHasIncompleteHistory()).isTrue();
        assertThat(position.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NOT_APPLICABLE);
    }

    private AvcoReplayService service() {
        return new AvcoReplayService(
                new ConfirmedReplayQueryService(normalizedTransactionRepository),
                normalizedTransactionRepository,
                assetPositionRepository
        );
    }

    private NormalizedTransaction tx(
            String id,
            String txHash,
            int txIndex,
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash(txHash);
        transaction.setWalletAddress("0xwallet");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        transaction.setTransactionIndex(txIndex);
        transaction.setFlows(List.of(flows));
        transaction.setMissingDataReasons(List.of());
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String quantity,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        if (unitPriceUsd != null) {
            flow.setUnitPriceUsd(new BigDecimal(unitPriceUsd));
            flow.setValueUsd(flow.getQuantityDelta().abs().multiply(new BigDecimal(unitPriceUsd)));
        }
        flow.setPriceSource(priceSource);
        return flow;
    }

}
