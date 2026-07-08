package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WS-3a: FEE leg exclusion from MULTI counterparty resolution.
 */
class FlowCounterpartySupportFeeExclusionTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String RECIPIENT = "0xaaaa000000000000000000000000000000000001";
    private static final String OTHER_RECIPIENT = "0xbbbb000000000000000000000000000000000002";

    @Test
    void singleRecipientErc20WithFeeLeg_counterpartyIsConcreteNotMulti() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-ws3a-1");
        tx.setWalletAddress(WALLET);

        // Principal flow: ERC20 out to RECIPIENT
        NormalizedTransaction.Flow transfer = new NormalizedTransaction.Flow();
        transfer.setRole(NormalizedLegRole.TRANSFER);
        transfer.setAssetSymbol("USDC");
        transfer.setQuantityDelta(new BigDecimal("-100"));
        transfer.setCounterpartyAddress(RECIPIENT);

        // FEE flow: synthetic network-fee placeholder
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("ETH");
        fee.setQuantityDelta(new BigDecimal("-0.001"));
        fee.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");

        tx.setFlows(List.of(transfer, fee));

        FlowCounterpartySupport.applyTransactionCounterparty(tx);

        assertThat(tx.getCounterpartyAddress())
                .as("single-recipient + FEE leg must yield concrete address, not MULTI")
                .isEqualTo(RECIPIENT);
    }

    @Test
    void genuineMultiRecipientSwap_stillMulti() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-ws3a-2");
        tx.setWalletAddress(WALLET);

        NormalizedTransaction.Flow flow1 = new NormalizedTransaction.Flow();
        flow1.setRole(NormalizedLegRole.TRANSFER);
        flow1.setQuantityDelta(new BigDecimal("-1"));
        flow1.setCounterpartyAddress(RECIPIENT);

        NormalizedTransaction.Flow flow2 = new NormalizedTransaction.Flow();
        flow2.setRole(NormalizedLegRole.TRANSFER);
        flow2.setQuantityDelta(new BigDecimal("-1"));
        flow2.setCounterpartyAddress(OTHER_RECIPIENT);

        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setQuantityDelta(new BigDecimal("-0.001"));
        fee.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");

        tx.setFlows(List.of(flow1, flow2, fee));

        FlowCounterpartySupport.applyTransactionCounterparty(tx);

        assertThat(tx.getCounterpartyAddress())
                .as("genuine multi-recipient must yield MULTI even after FEE exclusion")
                .isEqualTo(FlowCounterpartySupport.MULTI_COUNTERPARTY);
    }

    @Test
    void unknownSyntheticCounterpartyExcluded_noCounterpartySet() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-ws3a-3");
        tx.setWalletAddress(WALLET);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setQuantityDelta(new BigDecimal("-1"));
        flow.setCounterpartyAddress("UNKNOWN:some-key");

        tx.setFlows(List.of(flow));

        FlowCounterpartySupport.applyTransactionCounterparty(tx);

        assertThat(tx.getCounterpartyAddress())
                .as("synthetic UNKNOWN:* placeholders must not set transaction counterparty")
                .isNull();
    }

    @Test
    void noFlows_noCounterpartySet() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-ws3a-4");
        tx.setFlows(List.of());

        FlowCounterpartySupport.applyTransactionCounterparty(tx);

        assertThat(tx.getCounterpartyAddress()).isNull();
    }
}
