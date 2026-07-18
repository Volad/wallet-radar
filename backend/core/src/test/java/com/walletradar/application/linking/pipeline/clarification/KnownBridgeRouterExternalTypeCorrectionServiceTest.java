package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.testsupport.CounterpartyHintTestFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnownBridgeRouterExternalTypeCorrectionServiceTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private KnownBridgeRouterExternalTypeCorrectionService service;

    @BeforeAll
    static void bindCounterpartyHints() {
        CounterpartyHintTestFixtures.service();
    }

    @BeforeEach
    void setUp() {
        service = new KnownBridgeRouterExternalTypeCorrectionService(mongoOperations, normalizedTransactionRepository);
    }

    @Test
    void promotesExternalInFromKnownSquidRouterToBridgeIn() {
        NormalizedTransaction tx = externalMulti("EXTERNAL_TRANSFER_IN", "0x6131b5fae19ea4f9d964eac0408e4408b66337b5", "3.06");
        when(mongoOperations.find(any(Query.class), org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));
        when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        int changed = service.reclassifyKnownRouterExternals(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(tx.getMissingDataReasons()).contains("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    void promotesExternalOutFromLiFiDiamondToBridgeOut() {
        NormalizedTransaction tx = externalMulti(
                "EXTERNAL_TRANSFER_OUT",
                "0xcd74f91e4d2a49903462d58d6951136a527a5dea",
                "-3"
        );
        when(mongoOperations.find(any(Query.class), org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));

        int changed = service.reclassifyKnownRouterExternals(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
    }

    @Test
    void ignoresUnknownRecipientExternalOut() {
        NormalizedTransaction tx = externalMulti(
                "EXTERNAL_TRANSFER_OUT",
                "0x433fdee2ae9cea22a78499f8f8592ffd5d4e60c9",
                "-1.5"
        );
        when(mongoOperations.find(any(Query.class), org.mockito.ArgumentMatchers.eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));

        int changed = service.reclassifyKnownRouterExternals(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
    }

    private static NormalizedTransaction externalMulti(String type, String flowCounterparty, String qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.valueOf(type));
        tx.setCounterpartyAddress("MULTI");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress(flowCounterparty);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
