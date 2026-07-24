package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.support.UncoveredExternalInboundSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * B2b — a sourceless bridge inbound (reclassified to {@code EXTERNAL_TRANSFER_IN} and stamped with
 * {@link UncoveredExternalInboundSupport#SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN}) must land as an
 * <em>uncovered</em> position (basis-unknown), never a market-priced acquisition.
 *
 * <p>Evidence anchors (prod audit B2b): lone {@code BRIDGE_IN} legs {@code 0xb71f4e…} (≈ +$47) and
 * {@code 0xaddb9f…} (≈ +$20) had no correlatable source leg, yet were priced at the spot market of
 * their arrival block — fabricating a cost basis the wallet never paid. The hashes are evidence
 * anchors only, never runtime keys.</p>
 *
 * <p>These tests exercise the replay gate at its fix locus. The market authority is deliberately
 * wired to resolve a real quote and the flow itself carries a market price, so the only thing that
 * keeps the leg uncovered is the basis-unknown marker. The control case (same inputs, no marker)
 * proves the previous market-fabrication behaviour is intact for every other row.</p>
 */
class GenericFlowReplayEngineSourcelessInboundTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final BigDecimal INBOUND_QTY = new BigDecimal("0.010000000000000000");
    private static final BigDecimal MARKET_PRICE = new BigDecimal("4110.00");

    private GenericFlowReplayEngine engine;

    @BeforeEach
    void setUp() {
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        // Market authority DOES resolve a quote — proving the gate, not a missing price, is what
        // keeps the leg uncovered.
        lenient().when(marketAuthority.resolve(any(), any())).thenReturn(Optional.of(
                new ReplayMarketAuthority.ResolvedMarketPrice(
                        MARKET_PRICE,
                        PriceSource.COINGECKO,
                        ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE)));
        engine = new GenericFlowReplayEngine(marketAuthority);
    }

    @Test
    @DisplayName("B2b: basis-unknown sourceless inbound stays uncovered — no fabricated market basis")
    void basisUnknownInboundStaysUncovered() {
        NormalizedTransaction tx = sourcelessInbound(true);
        PositionState position = new PositionState(ethKey());

        Optional<BigDecimal> provisionalBasis = engine.materializePendingInbound(
                tx, tx.getFlows().getFirst(), position, true);

        // Quantity grows, but basis is NOT fabricated: the whole inbound is uncovered / flagged.
        assertThat(position.quantity()).isEqualByComparingTo(INBOUND_QTY);
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo(INBOUND_QTY);
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(position.hasIncompleteHistory()).isTrue();
        assertThat(provisionalBasis).contains(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("B2b control: an unflagged inbound still market-prices (behaviour preserved for other rows)")
    void unflaggedInboundStillMarketPrices() {
        NormalizedTransaction tx = sourcelessInbound(false);
        PositionState position = new PositionState(ethKey());

        engine.materializePendingInbound(tx, tx.getFlows().getFirst(), position, true);

        // Without the marker the leg is booked at the resolved market price (the pre-fix behaviour
        // that B2b removes only for genuinely sourceless inbounds).
        assertThat(position.quantity()).isEqualByComparingTo(INBOUND_QTY);
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd())
                .isEqualByComparingTo(INBOUND_QTY.multiply(MARKET_PRICE));
    }

    private static AssetKey ethKey() {
        return new AssetKey(WALLET, NetworkId.LINEA, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
    }

    private static NormalizedTransaction sourcelessInbound(boolean basisUnknown) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("EXTERNAL_TRANSFER_IN:LINEA:" + basisUnknown);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.LINEA);
        List<String> reasons = new ArrayList<>();
        if (basisUnknown) {
            reasons.add(UncoveredExternalInboundSupport.SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN);
        }
        tx.setMissingDataReasons(reasons);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(INBOUND_QTY);
        // Even a present flow price must be ignored for a basis-unknown leg.
        flow.setUnitPriceUsd(MARKET_PRICE);
        flow.setPriceSource(PriceSource.COINGECKO);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
