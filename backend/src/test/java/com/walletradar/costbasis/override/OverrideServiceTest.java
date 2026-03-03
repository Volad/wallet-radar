package com.walletradar.costbasis.override;

import com.walletradar.costbasis.event.OverrideSavedEvent;
import com.walletradar.domain.accounting.CostBasisOverride;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.accounting.RecalcJob;
import com.walletradar.domain.accounting.RecalcJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverrideServiceTest {

    private static final String EVENT_ID = "tx-1:0";

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private CostBasisOverrideRepository costBasisOverrideRepository;
    @Mock
    private RecalcJobRepository recalcJobRepository;
    @Mock
    private ApplicationEventPublisher publisher;

    private OverrideService service;

    private NormalizedTransaction onChainTx;

    @BeforeEach
    void setUp() {
        service = new OverrideService(
                normalizedTransactionRepository,
                costBasisOverrideRepository,
                recalcJobRepository,
                publisher
        );

        onChainTx = new NormalizedTransaction();
        onChainTx.setId("tx-1");
        onChainTx.setWalletAddress("0xwallet");
        onChainTx.setNetworkId(NetworkId.ARBITRUM);
        onChainTx.setType(NormalizedTransactionType.SWAP);

        NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
        leg.setRole(NormalizedLegRole.BUY);
        leg.setAssetContract("0xasset");
        leg.setAssetSymbol("ASSET");
        onChainTx.setFlows(List.of(leg));
    }

    @Test
    @DisplayName("setOverride stores active override and creates recalc job")
    void setOverride_success() {
        when(normalizedTransactionRepository.findById("tx-1")).thenReturn(Optional.of(onChainTx));
        when(costBasisOverrideRepository.findByNormalizedLegIdAndActiveTrue(EVENT_ID)).thenReturn(Optional.empty());
        when(costBasisOverrideRepository.findFirstByNormalizedLegId(EVENT_ID)).thenReturn(Optional.empty());
        when(costBasisOverrideRepository.save(any(CostBasisOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(recalcJobRepository.save(any(RecalcJob.class))).thenAnswer(invocation -> {
            RecalcJob job = invocation.getArgument(0);
            job.setId("job-1");
            return job;
        });

        String jobId = service.setOverride(EVENT_ID, new BigDecimal("123.45"), "note");

        assertThat(jobId).isEqualTo("job-1");

        ArgumentCaptor<CostBasisOverride> overrideCaptor = ArgumentCaptor.forClass(CostBasisOverride.class);
        verify(costBasisOverrideRepository).save(overrideCaptor.capture());
        CostBasisOverride saved = overrideCaptor.getValue();
        assertThat(saved.getNormalizedLegId()).isEqualTo(EVENT_ID);
        assertThat(saved.getPriceUsd()).isEqualByComparingTo("123.45");
        assertThat(saved.isActive()).isTrue();

        verify(recalcJobRepository).save(any(RecalcJob.class));
        verify(publisher).publishEvent(any(OverrideSavedEvent.class));
    }

    @Test
    @DisplayName("setOverride rejects duplicate active override")
    void setOverride_duplicate() {
        when(normalizedTransactionRepository.findById("tx-1")).thenReturn(Optional.of(onChainTx));
        when(costBasisOverrideRepository.findByNormalizedLegIdAndActiveTrue(EVENT_ID))
                .thenReturn(Optional.of(new CostBasisOverride()));

        assertThatThrownBy(() -> service.setOverride(EVENT_ID, new BigDecimal("1"), null))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.OVERRIDE_EXISTS));
    }

    @Test
    @DisplayName("setOverride rejects manual compensating legs")
    void setOverride_manualRejected() {
        onChainTx.setType(NormalizedTransactionType.MANUAL_COMPENSATING);
        when(normalizedTransactionRepository.findById("tx-1")).thenReturn(Optional.of(onChainTx));

        assertThatThrownBy(() -> service.setOverride(EVENT_ID, new BigDecimal("1"), null))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.EVENT_NOT_FOUND));
    }

    @Test
    @DisplayName("revertOverride deactivates existing override and creates recalc job")
    void revertOverride_success() {
        when(normalizedTransactionRepository.findById("tx-1")).thenReturn(Optional.of(onChainTx));
        CostBasisOverride existing = new CostBasisOverride();
        existing.setNormalizedLegId(EVENT_ID);
        existing.setActive(true);
        when(costBasisOverrideRepository.findFirstByNormalizedLegId(EVENT_ID)).thenReturn(Optional.of(existing));
        when(recalcJobRepository.save(any(RecalcJob.class))).thenAnswer(invocation -> {
            RecalcJob job = invocation.getArgument(0);
            job.setId("job-2");
            return job;
        });

        String jobId = service.revertOverride(EVENT_ID);

        assertThat(jobId).isEqualTo("job-2");
        verify(costBasisOverrideRepository, times(1)).save(existing);
        assertThat(existing.isActive()).isFalse();
        verify(publisher).publishEvent(any(OverrideSavedEvent.class));
    }

    @Test
    @DisplayName("setOverride fails for invalid leg id format")
    void setOverride_invalidId() {
        assertThatThrownBy(() -> service.setOverride("bad-id", new BigDecimal("1"), null))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.EVENT_NOT_FOUND));
    }
}
