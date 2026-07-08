package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.PositionStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BybitCarrySourceResolverTest {

    private static final String SYMBOL = "ETH";
    private static final String IDENTITY = "SYMBOL:ETH";

    @Test
    void flowCovers_singleSliceOnFlow() {
        PositionStore store = new PositionStore();
        PositionState fund = position(store, "BYBIT:1:FUND", "2");
        PositionState umbrella = position(store, "BYBIT:1", "5");

        BybitCarrySourceResolver.BybitDrainPlan plan =
                BybitCarrySourceResolver.plan(fund, fund, store, new BigDecimal("1.5"));

        assertThat(plan.isMultiSlice()).isFalse();
        assertThat(plan.slices()).hasSize(1);
        assertThat(plan.slices().getFirst().position()).isSameAs(fund);
        assertThat(plan.slices().getFirst().quantity()).isEqualByComparingTo("1.5");
        // umbrella untouched in plan
        assertThat(umbrella.quantity()).isEqualByComparingTo("5");
    }

    @Test
    void flowUnderCovers_waterfallsRemainderOntoUmbrella() {
        PositionStore store = new PositionStore();
        PositionState fund = position(store, "BYBIT:1:FUND", "0.6929");
        PositionState umbrella = position(store, "BYBIT:1", "1.149");

        BybitCarrySourceResolver.BybitDrainPlan plan =
                BybitCarrySourceResolver.plan(fund, fund, store, new BigDecimal("1.0"));

        assertThat(plan.isMultiSlice()).isTrue();
        assertThat(plan.slices()).hasSize(2);
        assertThat(plan.slices().get(0).position()).isSameAs(fund);
        assertThat(plan.slices().get(0).quantity()).isEqualByComparingTo("0.6929");
        assertThat(plan.slices().get(1).position()).isSameAs(umbrella);
        assertThat(plan.slices().get(1).quantity()).isEqualByComparingTo("0.3071");
        // Σ slice qty == outbound qty
        assertThat(plan.slices().get(0).quantity().add(plan.slices().get(1).quantity()))
                .isEqualByComparingTo("1.0");
    }

    @Test
    void flowEmptyButUmbrellaHoldsInventory_singleSliceOnUmbrella() {
        PositionStore store = new PositionStore();
        PositionState fund = position(store, "BYBIT:1:FUND", "0");
        PositionState umbrella = position(store, "BYBIT:1", "1.149");

        BybitCarrySourceResolver.BybitDrainPlan plan =
                BybitCarrySourceResolver.plan(fund, fund, store, new BigDecimal("1.0"));

        assertThat(plan.isMultiSlice()).isFalse();
        assertThat(plan.slices()).hasSize(1);
        assertThat(plan.slices().getFirst().position()).isSameAs(umbrella);
        assertThat(plan.slices().getFirst().quantity()).isEqualByComparingTo("1.0");
    }

    @Test
    void flowUnderCoversButUmbrellaEmpty_singleSliceOnFlowWithShortfall() {
        // Behaviour-preserving: no umbrella inventory to waterfall onto → keep the legacy
        // clamp-with-shortfall single slice on the primary (drives recordQuantityShortfall there).
        PositionStore store = new PositionStore();
        PositionState fund = position(store, "BYBIT:1:FUND", "0.4");
        position(store, "BYBIT:1", "0");

        BybitCarrySourceResolver.BybitDrainPlan plan =
                BybitCarrySourceResolver.plan(fund, fund, store, new BigDecimal("1.0"));

        assertThat(plan.isMultiSlice()).isFalse();
        assertThat(plan.slices()).hasSize(1);
        assertThat(plan.slices().getFirst().position()).isSameAs(fund);
        assertThat(plan.slices().getFirst().quantity()).isEqualByComparingTo("1.0");
    }

    @Test
    void primaryIsUmbrella_noSuffixSibling_singleSlice() {
        // XRP RC-2: plain collapsed FUND↔UTA already strips to the umbrella → no distinct sibling →
        // candidate degenerates to [umbrella], single slice (drain + shortfall on the umbrella key).
        PositionStore store = new PositionStore();
        PositionState umbrella = position(store, "BYBIT:1", "0.5");

        BybitCarrySourceResolver.BybitDrainPlan plan =
                BybitCarrySourceResolver.plan(umbrella, umbrella, store, new BigDecimal("1.0"));

        assertThat(plan.isMultiSlice()).isFalse();
        assertThat(plan.slices()).hasSize(1);
        assertThat(plan.slices().getFirst().position()).isSameAs(umbrella);
        assertThat(plan.slices().getFirst().quantity()).isEqualByComparingTo("1.0");
    }

    @Test
    void earnRedirectAlreadyResolvedToCoveredUmbrella_singleSlice() {
        // :EARN earn-principal redeem: resolveCarrySourcePosition already returned the umbrella when
        // the held :EARN slice had no covering basis. The plan sees a covered umbrella primary → it
        // must keep a single slice (no further split), preserving earn-principal net-to-zero.
        PositionStore store = new PositionStore();
        position(store, "BYBIT:1:EARN", "0");
        PositionState umbrella = position(store, "BYBIT:1", "3");

        BybitCarrySourceResolver.BybitDrainPlan plan =
                BybitCarrySourceResolver.plan(
                        store.position(earnKey()), umbrella, store, new BigDecimal("2"));

        assertThat(plan.isMultiSlice()).isFalse();
        assertThat(plan.slices().getFirst().position()).isSameAs(umbrella);
        assertThat(plan.slices().getFirst().quantity()).isEqualByComparingTo("2");
    }

    private static AssetKey earnKey() {
        return new AssetKey("BYBIT:1:EARN", null, null, SYMBOL, IDENTITY);
    }

    private static PositionState position(PositionStore store, String wallet, String quantity) {
        PositionState position = store.position(new AssetKey(wallet, null, null, SYMBOL, IDENTITY));
        position.setQuantity(new BigDecimal(quantity));
        return position;
    }
}
