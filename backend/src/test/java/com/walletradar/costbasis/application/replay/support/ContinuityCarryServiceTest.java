package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridor;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuityCarryServiceTest {

    private final GenericFlowReplayEngine engine = new GenericFlowReplayEngine();
    private final ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
    private final ContinuityCarryService service = new ContinuityCarryService(engine, flowSupport);

    private final AssetKey wethKey = new AssetKey(
            "0x1a87", NetworkId.ZKSYNC, "0x5aea", "WETH", "WETH:0x5aea"
    );

    @Test
    void removeTransferCarryAbsorbsOrphanBasisLeftByLaterSpotFallback() {
        // Cycle/17 R7 — reproduces zkSync WETH BRIDGE_IN + LENDING_DEPOSIT pass-through corridor:
        // the reserved carry is captured with basis=0 (upstream BRIDGE_OUT had none); R6 inbound
        // shortfall spot fallback then promotes basis on the position to $1748; the outbound
        // LENDING_DEPOSIT consumes the stale carry and used to leave $1748 stranded as zombie
        // basis on the now-empty WETH position. The fix drains that residual into the carry.
        PositionState position = new PositionState(wethKey);
        position.setQuantity(new BigDecimal("0.545880216141647307"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("1748.290568236854"));
        position.setPerWalletAvco(new BigDecimal("3202.7"));

        FlowRef outboundFlowRef = FlowRef.of("tx-deposit:0");
        Map<FlowRef, CarryTransfer> reserved = new LinkedHashMap<>();
        reserved.put(
                outboundFlowRef,
                new CarryTransfer(
                        new BigDecimal("0.545880216141647307"),
                        new BigDecimal("0.545880216141647307"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        false,
                        wethKey
                )
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-deposit");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new BigDecimal("-0.545880216141647307"));

        PassThroughCorridorPlan plan = new PassThroughCorridorPlan(new HashMap<>(), new HashMap<>());

        CarryTransfer carry = service.removeTransferCarry(tx, flow, 0, position, plan, reserved);

        assertThat(carry.costBasisUsd()).isEqualByComparingTo("1748.290568236854");
        assertThat(carry.avco()).isNotNull();
        assertThat(carry.avco().doubleValue()).isCloseTo(3202.7, org.assertj.core.data.Offset.offset(0.1));
        assertThat(position.quantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
    }

    @Test
    void sliceCarryTransferPreservesOrphanBasisWhenCarryQuantityIsZero() {
        AssetKey ethKey = new AssetKey("BYBIT:33625378:FUND", null, "eth", "ETH", "ETH:eth");
        CarryTransfer orphan = new CarryTransfer(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("7127.80"),
                null,
                false,
                ethKey
        );

        CarryTransfer sliced = service.sliceCarryTransfer(orphan, new BigDecimal("3.06"), ethKey);

        assertThat(sliced.costBasisUsd()).isEqualByComparingTo("7127.80");
        assertThat(sliced.quantity()).isEqualByComparingTo("3.06");
        assertThat(sliced.coveredQuantity()).isEqualByComparingTo("3.06");
    }

    @Test
    void bridgeInboundCarryPreservesOrphanBasisWhenCarryQuantityIsZero() {
        AssetKey ethKey = new AssetKey("BYBIT:33625378:FUND", null, "eth", "ETH", "ETH:eth");
        CarryTransfer orphan = new CarryTransfer(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("7127.80"),
                null,
                false,
                ethKey
        );

        CarryTransfer bridged = service.bridgeInboundCarry(orphan, new BigDecimal("3.06"), ethKey);

        assertThat(bridged.costBasisUsd()).isEqualByComparingTo("7127.80");
        assertThat(bridged.coveredQuantity()).isEqualByComparingTo("3.06");
    }
}
