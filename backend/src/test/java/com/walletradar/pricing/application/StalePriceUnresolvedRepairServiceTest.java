package com.walletradar.pricing.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StalePriceUnresolvedRepairServiceTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void clearsReasonWhenAllReplayRequiredFlowsAreAlreadyPriced() {
        NormalizedTransaction transaction = swapTx();
        transaction.setMissingDataReasons(List.of(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON));

        when(mongoOperations.find(any(), org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)))
                .thenReturn(List.of(transaction));

        StalePriceUnresolvedRepairService service = new StalePriceUnresolvedRepairService(mongoOperations);
        int repaired = service.repairNextBatch(50);

        assertThat(repaired).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(mongoOperations).save(captor.capture());
        assertThat(captor.getValue().getMissingDataReasons()).doesNotContain(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON);
    }

    @Test
    void keepsReasonWhenRequiredFlowStillHasUnknownPrice() {
        NormalizedTransaction transaction = swapTx();
        transaction.setMissingDataReasons(List.of(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON));
        transaction.getFlows().get(0).setUnitPriceUsd(null);
        transaction.getFlows().get(0).setValueUsd(null);
        transaction.getFlows().get(0).setPriceSource(PriceSource.UNKNOWN);

        when(mongoOperations.find(any(), org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)))
                .thenReturn(List.of(transaction));

        StalePriceUnresolvedRepairService service = new StalePriceUnresolvedRepairService(mongoOperations);
        int repaired = service.repairNextBatch(50);

        assertThat(repaired).isZero();
        verify(mongoOperations, never()).save(any());
    }

    private NormalizedTransaction swapTx() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("swap-1");
        transaction.setTxHash("0xswap");
        transaction.setNetworkId(NetworkId.UNICHAIN);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.SWAP);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setFlows(List.of(
                flow(NormalizedLegRole.SELL, "weETH", "-0.3", "1682.12", PriceSource.SWAP_DERIVED),
                flow(NormalizedLegRole.BUY, "wstETH", "0.26", "1894.09", PriceSource.COINGECKO),
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "1581.73", PriceSource.BYBIT)
        ));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetSymbol,
            String quantity,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        flow.setValueUsd(unitPriceUsd == null ? null : new BigDecimal(quantity).abs().multiply(new BigDecimal(unitPriceUsd)));
        flow.setPriceSource(priceSource);
        return flow;
    }
}
