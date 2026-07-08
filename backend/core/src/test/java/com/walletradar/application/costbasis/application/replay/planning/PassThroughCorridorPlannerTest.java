package com.walletradar.application.costbasis.application.replay.planning;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-020 P0-b: Defensive cross-network rejection in the wallet-scoped fallback path
 * of {@link PassThroughCorridorPlanner}.
 */
class PassThroughCorridorPlannerTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String ETH_IDENTITY = "ETH:null";

    private final PassThroughCorridorPlanner planner = new PassThroughCorridorPlanner();

    @Test
    @DisplayName("ZKSync BRIDGE_IN pairs with ZKSync LENDING_DEPOSIT via wallet-scoped fallback (same network)")
    void sameNetworkBridgeInPairsWithLendingDeposit() {
        // BRIDGE_IN on ZKSync: inbound 0.5 ETH, matchedCounterparty = txHash of the paired BRIDGE_OUT
        NormalizedTransaction bridgeIn = tx("bridge-in", NetworkId.ZKSYNC,
                NormalizedTransactionType.BRIDGE_IN, 0,
                flow(NormalizedLegRole.BUY, "ETH", "0.5", null));
        bridgeIn.setMatchedCounterparty("0xunichain-bridge-out-txhash");
        bridgeIn.setCorrelationId("bridge:lifi:0xunichain-bridge-out-txhash");

        // LENDING_DEPOSIT on ZKSync: outbound 0.5 ETH, no matchedCounterparty (wallet-scoped)
        NormalizedTransaction lendingDeposit = tx("lending-deposit", NetworkId.ZKSYNC,
                NormalizedTransactionType.LENDING_DEPOSIT, 1,
                flow(NormalizedLegRole.SELL, "ETH", "-0.5", null));

        PassThroughCorridorPlan plan = planner.buildPlan(
                List.of(bridgeIn, lendingDeposit),
                (tx, fl) -> assetKey(fl)
        );

        // Corridor must be built: BRIDGE_IN → LENDING_DEPOSIT
        assertThat(plan.byInboundFlowRef()).hasSize(1);
        assertThat(plan.byOutboundFlowRef()).hasSize(1);

        String inboundFlowRef = "bridge-in:0";
        String outboundFlowRef = "lending-deposit:0";
        assertThat(plan.byInboundFlowRef()).containsKey(com.walletradar.application.costbasis.application.replay.model.FlowRef.of(inboundFlowRef));
        assertThat(plan.byOutboundFlowRef()).containsKey(com.walletradar.application.costbasis.application.replay.model.FlowRef.of(outboundFlowRef));
    }

    @Test
    @DisplayName("ADR-020 P0-b: UNICHAIN BRIDGE_IN must NOT pair with ZKSync LENDING_DEPOSIT (cross-network rejection)")
    void crossNetworkBridgeInDoesNotPairWithLendingDepositOnDifferentNetwork() {
        // BRIDGE_IN on UNICHAIN: inbound 0.5 ETH
        NormalizedTransaction bridgeIn = tx("bridge-in-unichain", NetworkId.UNICHAIN,
                NormalizedTransactionType.BRIDGE_IN, 0,
                flow(NormalizedLegRole.BUY, "ETH", "0.5", null));
        bridgeIn.setMatchedCounterparty("0xzksync-bridge-out-txhash");
        bridgeIn.setCorrelationId("bridge:lifi:0xzksync-bridge-out-txhash");

        // LENDING_DEPOSIT on ZKSync: different network than the BRIDGE_IN
        NormalizedTransaction lendingDeposit = tx("lending-deposit-zksync", NetworkId.ZKSYNC,
                NormalizedTransactionType.LENDING_DEPOSIT, 1,
                flow(NormalizedLegRole.SELL, "ETH", "-0.5", null));

        PassThroughCorridorPlan plan = planner.buildPlan(
                List.of(bridgeIn, lendingDeposit),
                (tx, fl) -> assetKey(fl)
        );

        // No corridor must be built — cross-network pairing rejected
        assertThat(plan.byInboundFlowRef()).isEmpty();
        assertThat(plan.byOutboundFlowRef()).isEmpty();
    }

    @Test
    @DisplayName("Two same-quantity BRIDGE_INs on different networks: only the ZKSync one pairs with ZKSync LENDING_DEPOSIT")
    void whenTwoBridgeInsWithSameQtyOnlyCorrectNetworkPairs() {
        // UNICHAIN BRIDGE_IN — arrives first (lower seq), same qty as ZKSync one
        NormalizedTransaction bridgeInUnichain = tx("bridge-in-unichain", NetworkId.UNICHAIN,
                NormalizedTransactionType.BRIDGE_IN, 0,
                flow(NormalizedLegRole.BUY, "ETH", "0.5", null));
        bridgeInUnichain.setMatchedCounterparty("0xsome-other-txhash");
        bridgeInUnichain.setCorrelationId("bridge:lifi:0xsome-other-txhash");

        // ZKSync BRIDGE_IN — arrives second, same qty
        NormalizedTransaction bridgeInZksync = tx("bridge-in-zksync", NetworkId.ZKSYNC,
                NormalizedTransactionType.BRIDGE_IN, 1,
                flow(NormalizedLegRole.BUY, "ETH", "0.5", null));
        bridgeInZksync.setMatchedCounterparty("0xunichain-bridge-out-txhash");
        bridgeInZksync.setCorrelationId("bridge:lifi:0xunichain-bridge-out-txhash");

        // LENDING_DEPOSIT on ZKSync — must pair only with bridgeInZksync
        NormalizedTransaction lendingDeposit = tx("lending-deposit-zksync", NetworkId.ZKSYNC,
                NormalizedTransactionType.LENDING_DEPOSIT, 2,
                flow(NormalizedLegRole.SELL, "ETH", "-0.5", null));

        PassThroughCorridorPlan plan = planner.buildPlan(
                List.of(bridgeInUnichain, bridgeInZksync, lendingDeposit),
                (tx, fl) -> assetKey(fl)
        );

        // Only one corridor — ZKSync BRIDGE_IN → ZKSync LENDING_DEPOSIT
        assertThat(plan.byInboundFlowRef()).hasSize(1);
        assertThat(plan.byOutboundFlowRef()).hasSize(1);

        String zkSyncInboundRef = "bridge-in-zksync:0";
        assertThat(plan.byInboundFlowRef())
                .containsKey(com.walletradar.application.costbasis.application.replay.model.FlowRef.of(zkSyncInboundRef));
    }

    @Test
    @DisplayName("Bybit transit corridor (ARBITRUM→MANTLE) still builds correctly after P0-b change")
    void bybitTransitCrossNetworkCorridorViaCounterpartyMatchStillWorks() {
        // BYBIT:1 receives ETH from wallet-a on ARBITRUM
        NormalizedTransaction bybitInbound = tx("bybit-in", NetworkId.ARBITRUM,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN, 0,
                flow(NormalizedLegRole.BUY, "ETH", "1", null));
        bybitInbound.setWalletAddress("BYBIT:1");
        bybitInbound.setMatchedCounterparty("wallet-a");
        bybitInbound.setContinuityCandidate(true);

        // BYBIT:1 sends ETH to wallet-a on MANTLE (different network — Bybit transit)
        NormalizedTransaction bybitOutbound = tx("bybit-out", NetworkId.MANTLE,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, 1,
                flow(NormalizedLegRole.SELL, "ETH", "-1", null));
        bybitOutbound.setWalletAddress("BYBIT:1");
        bybitOutbound.setMatchedCounterparty("wallet-a");
        bybitOutbound.setContinuityCandidate(true);

        PassThroughCorridorPlan plan = planner.buildPlan(
                List.of(bybitInbound, bybitOutbound),
                (tx, fl) -> new AssetKey("BYBIT:1:ETH", null, null, "ETH", ETH_IDENTITY)
        );

        // Counterparty-matched corridor is not affected by networkId filtering
        assertThat(plan.byInboundFlowRef()).hasSize(1);
        assertThat(plan.byOutboundFlowRef()).hasSize(1);

        assertThat(plan.byInboundFlowRef())
                .containsKey(com.walletradar.application.costbasis.application.replay.model.FlowRef.of("bybit-in:0"));
        assertThat(plan.byOutboundFlowRef())
                .containsKey(com.walletradar.application.costbasis.application.replay.model.FlowRef.of("bybit-out:0"));
    }

    @Test
    @DisplayName("Cross-network rejection does not fire when outboundNetworkId is null (unknown network)")
    void nullNetworkIdOnOutboundMatchesAnyCandidate() {
        // BRIDGE_IN on ZKSYNC
        NormalizedTransaction bridgeIn = tx("bridge-in", NetworkId.ZKSYNC,
                NormalizedTransactionType.BRIDGE_IN, 0,
                flow(NormalizedLegRole.BUY, "ETH", "0.5", null));
        bridgeIn.setMatchedCounterparty("0xsome-txhash");
        bridgeIn.setCorrelationId("bridge:lifi:0xsome-txhash");

        // LENDING_DEPOSIT with null networkId (unknown / legacy)
        NormalizedTransaction lendingDeposit = tx("lending-deposit", null,
                NormalizedTransactionType.LENDING_DEPOSIT, 1,
                flow(NormalizedLegRole.SELL, "ETH", "-0.5", null));

        PassThroughCorridorPlan plan = planner.buildPlan(
                List.of(bridgeIn, lendingDeposit),
                (tx, fl) -> assetKey(fl)
        );

        // Null outbound networkId → filter is not applied → corridor is built
        assertThat(plan.byInboundFlowRef()).hasSize(1);
    }

    // ——— helpers ———

    private NormalizedTransaction tx(
            String id, NetworkId networkId, NormalizedTransactionType type, int txIndex,
            NormalizedTransaction.Flow flow
    ) {
        NormalizedTransaction t = new NormalizedTransaction();
        t.setId(id);
        t.setTxHash("0x" + id);
        t.setWalletAddress(WALLET);
        t.setNetworkId(networkId);
        t.setSource(NormalizedTransactionSource.ON_CHAIN);
        t.setType(type);
        t.setStatus(NormalizedTransactionStatus.CONFIRMED);
        t.setBlockTimestamp(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(txIndex));
        t.setTransactionIndex(txIndex);
        t.setFlows(List.of(flow));
        return t;
    }

    private NormalizedTransaction.Flow flow(NormalizedLegRole role, String symbol, String qty, String price) {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(role);
        f.setAssetSymbol(symbol);
        f.setQuantityDelta(new BigDecimal(qty));
        if (price != null) {
            f.setUnitPriceUsd(new BigDecimal(price));
        }
        return f;
    }

    private AssetKey assetKey(NormalizedTransaction.Flow fl) {
        if (fl.getQuantityDelta() == null) return null;
        String sym = fl.getAssetSymbol();
        return new AssetKey(WALLET + ":" + sym, null, null, sym, ETH_IDENTITY);
    }
}
