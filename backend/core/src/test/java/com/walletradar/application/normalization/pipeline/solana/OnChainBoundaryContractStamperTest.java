package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.ExternalCapitalBoundary;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnChainBoundaryContractStamperTest {

    private final OnChainBoundaryContractStamper stamper = new OnChainBoundaryContractStamper();

    private static NormalizedTransaction.Flow leg(NormalizedLegRole role, String quantity) {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(role);
        f.setAssetSymbol("SOL");
        f.setQuantityDelta(new BigDecimal(quantity));
        return f;
    }

    private static NormalizedTransaction tx(
            NetworkId network,
            NormalizedTransactionType type,
            String counterpartyType,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(network);
        tx.setType(type);
        tx.setCounterpartyType(counterpartyType);
        tx.setFlows(new ArrayList<>(List.of(flows)));
        return tx;
    }

    @Test
    @DisplayName("RC-S5: unknown-external Solana inbound with an economic leg is stamped INFLOW")
    void unknownExternalInboundStampedInflow() {
        NormalizedTransaction tx = tx(NetworkId.SOLANA, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                CounterpartyType.UNKNOWN_EOA, leg(NormalizedLegRole.TRANSFER, "1.5"), leg(NormalizedLegRole.FEE, "-0.000005"));

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isEqualTo(ExternalCapitalBoundary.INFLOW);
    }

    @Test
    @DisplayName("RC-S5: outbound transfer is never stamped INFLOW")
    void outboundNeverStamped() {
        NormalizedTransaction tx = tx(NetworkId.SOLANA, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                CounterpartyType.UNKNOWN_EOA, leg(NormalizedLegRole.TRANSFER, "-1.5"));

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    @Test
    @DisplayName("RC-S5: internal transfer is never stamped INFLOW")
    void internalNeverStamped() {
        NormalizedTransaction tx = tx(NetworkId.SOLANA, NormalizedTransactionType.INTERNAL_TRANSFER,
                CounterpartyType.PERSONAL_WALLET, leg(NormalizedLegRole.TRANSFER, "1.5"));

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    @Test
    @DisplayName("RC-S5: own/CEX inbound peer (not UNKNOWN_EOA) is not stamped INFLOW")
    void cexInboundNotStamped() {
        NormalizedTransaction tx = tx(NetworkId.SOLANA, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                CounterpartyType.CEX, leg(NormalizedLegRole.TRANSFER, "1.5"));

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    @Test
    @DisplayName("RC-S5: fee-only inbound (dust dropped) is not stamped INFLOW")
    void feeOnlyInboundNotStamped() {
        NormalizedTransaction tx = tx(NetworkId.SOLANA, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                CounterpartyType.UNKNOWN_EOA, leg(NormalizedLegRole.FEE, "-0.000005"));

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    @Test
    @DisplayName("EVM rows are never touched by the on-chain Solana stamper")
    void evmRowsUntouched() {
        NormalizedTransaction tx = tx(NetworkId.ETHEREUM, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                CounterpartyType.UNKNOWN_EOA, leg(NormalizedLegRole.TRANSFER, "1.5"));

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    @Test
    @DisplayName("stamping is idempotent — an already-stamped boundary is left unchanged")
    void idempotent() {
        NormalizedTransaction tx = tx(NetworkId.SOLANA, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                CounterpartyType.UNKNOWN_EOA, leg(NormalizedLegRole.TRANSFER, "1.5"));
        tx.setExternalCapitalBoundary(ExternalCapitalBoundary.OUTFLOW);

        stamper.process(tx);

        assertThat(tx.getExternalCapitalBoundary()).isEqualTo(ExternalCapitalBoundary.OUTFLOW);
    }
}
