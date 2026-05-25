package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FlowCounterpartySupportTest {

    @Test
    void enrichOnChainFlowsAppendsReasonWhenMissingDataReasonsAreImmutable() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setMissingDataReasons(List.of("GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED"));

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("100"));
        transaction.setFlows(List.of(flow));

        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.ARBITRUM.name());
        raw.setWalletAddress("0x1111111111111111111111111111111111111111");
        raw.setRawData(new Document());

        assertThatCode(() -> FlowCounterpartySupport.enrichOnChainFlows(
                transaction,
                OnChainRawTransactionView.wrap(raw),
                (peer, network) -> CounterpartyType.PROTOCOL
        )).doesNotThrowAnyException();

        assertThat(transaction.getMissingDataReasons())
                .containsExactly(
                        "GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED",
                        "COUNTERPARTY_ADDRESS_INFERRED"
                );
        assertThat(flow.getCounterpartyAddress()).startsWith("UNKNOWN:");
    }
}
