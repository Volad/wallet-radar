package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.NetworkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransferReclassifierTest {

    @Mock
    private EconomicEventRepository economicEventRepository;

    private InternalTransferReclassifier reclassifier;

    @BeforeEach
    void setUp() {
        reclassifier = new InternalTransferReclassifier(economicEventRepository);
    }

    @Test
    void reclassify_multiWallet_sessionFindsExternalInbound_updatesToInternalTransfer() {
        EconomicEvent e1 = event("e1", "0xWalletA", "0xWalletB");
        EconomicEvent e2 = event("e2", "0xWalletB", "0xWalletC");
        when(economicEventRepository.findByEventTypeAndCounterpartyAddressIn(
                eq(EconomicEventType.EXTERNAL_INBOUND),
                org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of(e1, e2));

        List<EconomicEvent> updated = reclassifier.reclassify(Set.of("0xWalletA", "0xWalletB", "0xWalletC"));

        assertThat(updated).hasSize(2);
        assertThat(e1.getEventType()).isEqualTo(EconomicEventType.INTERNAL_TRANSFER);
        assertThat(e1.isInternalTransfer()).isTrue();
        assertThat(e2.getEventType()).isEqualTo(EconomicEventType.INTERNAL_TRANSFER);
        assertThat(e2.isInternalTransfer()).isTrue();
        verify(economicEventRepository, times(2)).save(any(EconomicEvent.class));
    }

    @Test
    void reclassify_emptySession_returnsEmpty() {
        List<EconomicEvent> updated = reclassifier.reclassify(Set.of());

        assertThat(updated).isEmpty();
    }

    private static EconomicEvent event(String id, String wallet, String counterparty) {
        EconomicEvent e = new EconomicEvent();
        e.setId(id);
        e.setWalletAddress(wallet);
        e.setEventType(EconomicEventType.EXTERNAL_INBOUND);
        e.setCounterpartyAddress(counterparty);
        e.setNetworkId(NetworkId.ETHEREUM);
        e.setBlockTimestamp(Instant.now());
        e.setQuantityDelta(BigDecimal.ONE);
        e.setTotalValueUsd(BigDecimal.ZERO);
        e.setGasCostUsd(BigDecimal.ZERO);
        return e;
    }
}
