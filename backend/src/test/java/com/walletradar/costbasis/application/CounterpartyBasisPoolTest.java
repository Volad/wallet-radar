package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.CounterpartyBasisPoolReplayContext;
import com.walletradar.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetFamilyResolver;
import com.walletradar.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolKey;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyType;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterpartyBasisPoolTest {

    private static final String UNIVERSE_ID = "n19-test";
    private static final String COUNTERPARTY = "0xtonwalletunknown";

    @Mock
    private CounterpartyBasisPoolRepository repository;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    private CounterpartyBasisPoolService poolService;
    private CounterpartyBasisPoolReplayHook replayHook;
    private GenericFlowReplayEngine engine;
    private ReplayFlowSupport flowSupport;
    private Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> pools;
    private Set<CounterpartyBasisPoolKey> dirtyKeys;
    private CounterpartyBasisPoolReplayContext poolContext;

    @Test
    void shouldTrackFlowSkipsProtocolAndBridgeCounterparties() {
        NormalizedTransaction.Flow protocol = new NormalizedTransaction.Flow();
        protocol.setCounterpartyAddress("0x6a000f20005980200259b80c5102003040001068");
        protocol.setCounterpartyType(CounterpartyType.PROTOCOL);
        protocol.setQuantityDelta(new BigDecimal("1"));

        NormalizedTransaction.Flow bridge = new NormalizedTransaction.Flow();
        bridge.setCounterpartyAddress("0xf5f93d26229482adca3e42f84d08d549cf131658");
        bridge.setCounterpartyType(CounterpartyType.BRIDGE);
        bridge.setQuantityDelta(new BigDecimal("1"));

        NormalizedTransaction.Flow external = new NormalizedTransaction.Flow();
        external.setCounterpartyAddress("0x31fc2cb761083a77cf218461e2072bcea0ca35ef");
        external.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        external.setQuantityDelta(new BigDecimal("1"));

        assertThat(poolService.shouldTrackFlow(protocol)).isFalse();
        assertThat(poolService.shouldTrackFlow(bridge)).isFalse();
        assertThat(poolService.shouldTrackFlow(external)).isTrue();
    }

    @BeforeEach
    void setUp() {
        poolService = new CounterpartyBasisPoolService(
                repository,
                new AssetFamilyResolver(),
                accountingUniverseService
        );
        ReplayTransferClassifier transferClassifier = new ReplayTransferClassifier(
                new ReplayPendingTransferKeyFactory(new com.walletradar.costbasis.application.replay.support.ReplayAssetSupport())
        );
        replayHook = new CounterpartyBasisPoolReplayHook(poolService, transferClassifier);
        engine = new GenericFlowReplayEngine();
        flowSupport = new ReplayFlowSupport(engine);
        pools = new HashMap<>();
        dirtyKeys = new HashSet<>();
        poolContext = new CounterpartyBasisPoolReplayContext(UNIVERSE_ID, pools, dirtyKeys);
        lenient().when(accountingUniverseService.classify(eq(UNIVERSE_ID), eq(COUNTERPARTY), eq(NetworkId.TON)))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));
    }

    @Test
    void roundTripOutAndInLeavesZeroRealisedPnlAndUnchangedOwnAvco() {
        PositionState position = fundedUsdtPosition("100", "100");

        NormalizedTransaction outTx = tx("out", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        NormalizedTransaction.Flow out = flow(NormalizedLegRole.SELL, "USDT", "-100", "1.05", PriceSource.BINANCE);
        sellThroughPool(outTx, out, position);

        NormalizedTransaction inTx = tx("in", NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        NormalizedTransaction.Flow in = flow(NormalizedLegRole.BUY, "USDT", "100", "1.05", PriceSource.BINANCE);
        buyThroughPool(inTx, in, position);

        CounterpartyBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(pool.getAvcoUsd()).isEqualByComparingTo("0");
        assertThat(out.getRealisedPnlUsd()).isEqualByComparingTo("0");
        assertThat(in.getRealisedPnlUsd()).isEqualByComparingTo("0");
        assertThat(position.perWalletAvco()).isEqualByComparingTo("1");
        assertThat(position.quantity()).isEqualByComparingTo("100");
    }

    @Test
    void partialReturnRetainsPoolBalance() {
        PositionState position = fundedUsdtPosition("100", "100");

        NormalizedTransaction outTx = tx("out", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        sellThroughPool(outTx, flow(NormalizedLegRole.SELL, "USDT", "-100", "1", PriceSource.BINANCE), position);

        NormalizedTransaction inTx = tx("in", NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        buyThroughPool(inTx, flow(NormalizedLegRole.BUY, "USDT", "30", "1.05", PriceSource.BINANCE), position);

        CounterpartyBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("70");
        assertThat(pool.getAvcoUsd()).isEqualByComparingTo("1");
    }

    @Test
    void asymmetricPricePopUsesPoolAvcoNotMarketOnReturn() {
        PositionState position = fundedUsdtPosition("100", "100");

        sellThroughPool(
                tx("out", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                flow(NormalizedLegRole.SELL, "USDT", "-100", "1", PriceSource.BINANCE),
                position
        );

        NormalizedTransaction.Flow extraBuy = flow(NormalizedLegRole.BUY, "USDT", "100", "1.05", PriceSource.BINANCE);
        engine.applyBuy(extraBuy, position);
        assertThat(position.perWalletAvco()).isEqualByComparingTo("1.05");

        buyThroughPool(
                tx("in", NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                flow(NormalizedLegRole.BUY, "USDT", "100", "1.05", PriceSource.BINANCE),
                position
        );

        assertThat(position.perWalletAvco()).isEqualByComparingTo("1.025");
    }

    @Test
    void oneWaySinkRecordsLifetimeOutWithoutIn() {
        PositionState position = fundedUsdtPosition("500", "500");

        sellThroughPool(
                tx("out", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                flow(NormalizedLegRole.SELL, "USDT", "-500", "1", PriceSource.BINANCE),
                position
        );

        CounterpartyBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getLifetimeOutBasisUsd()).isEqualByComparingTo("500");
        assertThat(pool.getLifetimeInBasisUsd()).isEqualByComparingTo("0");
        assertThat(pool.getNetCapitalDeltaUsd()).isEqualByComparingTo("-500");
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("500");
    }

    @Test
    void stableFamilyCollapsesUsdtOutAndUsdcIn() {
        PositionState position = fundedUsdtPosition("100", "100");

        sellThroughPool(
                tx("out", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                flow(NormalizedLegRole.SELL, "USDT", "-100", "1", PriceSource.BINANCE),
                position
        );

        NormalizedTransaction.Flow in = flow(NormalizedLegRole.BUY, "USDC", "100", "1.005", PriceSource.BINANCE);
        buyThroughPool(tx("in", NormalizedTransactionType.EXTERNAL_TRANSFER_IN), in, position);

        assertThat(pools).hasSize(1);
        CounterpartyBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getAssetFamily()).isEqualTo("STABLE_USD");
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(in.getRealisedPnlUsd()).isEqualByComparingTo("0");
    }

    @Test
    void fullReplayBatchUsesSinglePoolFindAndSingleBulkSave() {
        when(repository.findByUniverseId(UNIVERSE_ID)).thenReturn(List.of());

        Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> loaded = poolService.loadAllForUniverse(UNIVERSE_ID);
        CounterpartyBasisPoolKey key = new CounterpartyBasisPoolKey(UNIVERSE_ID, COUNTERPARTY, NetworkId.TON, "STABLE_USD");
        CounterpartyBasisPool pool = new CounterpartyBasisPool();
        pool.setId(key.documentId());
        pool.setUniverseId(UNIVERSE_ID);
        pool.setCounterpartyAddress(COUNTERPARTY);
        pool.setNetworkId(NetworkId.TON);
        pool.setAssetFamily("STABLE_USD");
        loaded.put(key, pool);

        poolService.replaceUniversePools(UNIVERSE_ID, loaded);

        verify(repository, times(1)).findByUniverseId(UNIVERSE_ID);
        verify(repository, times(1)).deleteByUniverseId(UNIVERSE_ID);
        verify(repository, times(1)).saveAll(any());
    }

    private void sellThroughPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position
    ) {
        PositionSnapshot before = flowSupport.snapshot(position);
        flowSupport.applySell(flow, position);
        replayHook.undoSellRealisedPnl(flow, position, before);
        replayHook.afterSell(transaction, flow, before, poolContext, false);
    }

    private void buyThroughPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position
    ) {
        BigDecimal acquisitionCost = replayHook.acquisitionCostUsdForBuy(transaction, flow, poolContext, false);
        flowSupport.applyBuyWithAcquisitionCost(flow, position, acquisitionCost);
    }

    private PositionState fundedUsdtPosition(String quantity, String costBasis) {
        PositionState position = new PositionState(usdtAssetKey());
        position.setQuantity(new BigDecimal(quantity));
        position.setTotalCostBasisUsd(new BigDecimal(costBasis));
        engine.recomputePerWalletAvco(position);
        return position;
    }

    private AssetKey usdtAssetKey() {
        return new AssetKey("wallet-a", NetworkId.TON, "USDT:TON", "USDT", "USDT:TON");
    }

    private NormalizedTransaction tx(String id, NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setNetworkId(NetworkId.TON);
        transaction.setType(type);
        transaction.setBlockTimestamp(Instant.parse("2026-05-01T00:00:00Z"));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String quantityDelta,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        flow.setUnitPriceUsd(new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        flow.setCounterpartyAddress(COUNTERPARTY);
        flow.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        return flow;
    }
}
