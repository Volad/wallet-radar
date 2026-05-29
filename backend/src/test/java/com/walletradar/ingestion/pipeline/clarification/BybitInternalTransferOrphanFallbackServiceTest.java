package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferPairer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitInternalTransferOrphanFallbackServiceTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void inboundOrphanIsDemotedToExternalTransferIn() {
        NormalizedTransaction orphan = internalTransfer("orphan-in", "bybit-econ-v1:solo", "BYBIT:1:EARN", "10");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(orphan));

        BybitInternalTransferOrphanFallbackService service = new BybitInternalTransferOrphanFallbackService(
                mongoOperations,
                normalizedTransactionRepository
        );
        int demoted = service.reconcileOrphanInternals();

        assertThat(demoted).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        NormalizedTransaction saved = captor.getValue().iterator().next();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(saved.getContinuityCandidate()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
    }

    @Test
    void outboundOrphanIsDemotedToExternalTransferOut() {
        NormalizedTransaction orphan = internalTransfer("orphan-out", "bybit-econ-v1:solo", "BYBIT:1:FUND", "-10");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(orphan));

        BybitInternalTransferOrphanFallbackService service = new BybitInternalTransferOrphanFallbackService(
                mongoOperations,
                normalizedTransactionRepository
        );
        int demoted = service.reconcileOrphanInternals();

        assertThat(demoted).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        NormalizedTransaction saved = captor.getValue().iterator().next();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(saved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.SELL);
    }

    @Test
    void fundEarnSingletonByEconCorrelationIsDemoted() {
        NormalizedTransaction orphan = internalTransfer(
                "orphan-fund-earn",
                "bybit-econ-v1:solo-mnt",
                "BYBIT:33625378:FUND",
                "-153"
        );
        orphan.setMatchedCounterparty("BYBIT:33625378:EARN");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(orphan));

        BybitInternalTransferOrphanFallbackService service = new BybitInternalTransferOrphanFallbackService(
                mongoOperations,
                normalizedTransactionRepository
        );
        int demoted = service.reconcileOrphanInternals();

        assertThat(demoted).isEqualTo(1);
    }

    @Test
    void fundEarnBundlePairIsNotDemoted() {
        String bundleCorr = BybitInternalTransferPairer.BUNDLE_CORRELATION_PREFIX + "fund|earn";
        NormalizedTransaction fund = internalTransfer("fund", bundleCorr, "BYBIT:33625378:FUND", "-153");
        fund.setMatchedCounterparty("BYBIT:33625378:EARN");
        NormalizedTransaction earn = internalTransfer("earn", bundleCorr, "BYBIT:33625378:EARN", "153");
        earn.setMatchedCounterparty("BYBIT:33625378:FUND");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fund, earn));

        BybitInternalTransferOrphanFallbackService service = new BybitInternalTransferOrphanFallbackService(
                mongoOperations,
                normalizedTransactionRepository
        );

        assertThat(service.reconcileOrphanInternals()).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    @Test
    void pairedAfterBundleIsNotDemoted() {
        String bundleCorr = BybitInternalTransferPairer.BUNDLE_CORRELATION_PREFIX + "a|b";
        NormalizedTransaction left = internalTransfer("a", bundleCorr, "BYBIT:1:UTA", "-5");
        NormalizedTransaction right = internalTransfer("b", bundleCorr, "BYBIT:1:EARN", "5");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(left, right));

        BybitInternalTransferOrphanFallbackService service = new BybitInternalTransferOrphanFallbackService(
                mongoOperations,
                normalizedTransactionRepository
        );
        int demoted = service.reconcileOrphanInternals();

        assertThat(demoted).isZero();
        verifyNoInteractions(normalizedTransactionRepository);
    }

    private static NormalizedTransaction internalTransfer(
            String id,
            String correlationId,
            String wallet,
            String qty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setWalletAddress(wallet);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("LDO");
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
