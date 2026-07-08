package com.walletradar.canonical.correlation;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.normalized.VenueInternalCarryKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class BybitCarryContinuitySupportTest {

    private static final String UID = "33625378";

    @Test
    void collapsedFundLeg_isNotSelfTransferNoop() {
        NormalizedTransaction fundCarryIn = internalTransfer(
                "BYBIT:" + UID + ":FUND",
                "BYBIT:" + UID + ":UTA",
                CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX + "abc"
        );
        BybitCarryContinuitySupport.stamp(fundCarryIn);

        assertThat(fundCarryIn.getSelfTransferNoop()).isFalse();
        assertThat(fundCarryIn.getVenueInternalCarry()).isEqualTo(VenueInternalCarryKind.CORR_FAMILY);
        assertThat(fundCarryIn.getCarrySourceHint()).isEqualTo("collapsed");
    }

    @Test
    void crossUidPair_isNotSelfTransferNoop() {
        NormalizedTransaction tx = internalTransfer(
                "BYBIT:" + UID + ":UTA",
                "BYBIT:99999999:UTA",
                CorrelationContract.BYBIT_CROSS_UID_V1_PREFIX + "uuid"
        );
        BybitCarryContinuitySupport.stamp(tx);

        assertThat(tx.getSelfTransferNoop()).isFalse();
        assertThat(tx.getCarrySourceHint()).isEqualTo("cross-uid");
    }

    @Test
    void plainUtaFundEcon_isSelfTransferNoop() {
        NormalizedTransaction tx = internalTransfer(
                "BYBIT:" + UID + ":UTA",
                "BYBIT:" + UID + ":FUND",
                CorrelationContract.BYBIT_ECON_V1_PREFIX + "hash"
        );
        BybitCarryContinuitySupport.stamp(tx);

        assertThat(tx.getSelfTransferNoop()).isTrue();
        assertThat(tx.getVenueInternalCarry()).isEqualTo(VenueInternalCarryKind.SELF_TRANSFER_NOOP);
    }

    private static NormalizedTransaction internalTransfer(
            String wallet,
            String counterparty,
            String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(wallet);
        tx.setMatchedCounterparty(counterparty);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setFlows(new ArrayList<>());
        return tx;
    }
}
