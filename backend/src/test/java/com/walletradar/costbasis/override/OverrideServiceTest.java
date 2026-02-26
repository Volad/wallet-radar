package com.walletradar.costbasis.override;

import com.walletradar.domain.CostBasisOverride;
import com.walletradar.domain.CostBasisOverrideRepository;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RecalcJob;
import com.walletradar.domain.RecalcJobRepository;
import com.walletradar.costbasis.event.OverrideSavedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverrideServiceTest {

    private static final String EVENT_ID = "ev1";
    private static final String WALLET = "0xwallet";
    private static final String ASSET_CONTRACT = "0xasset";
    private static final String ASSET_SYMBOL = "ETH";

    @Mock
    EconomicEventRepository economicEventRepository;
    @Mock
    CostBasisOverrideRepository costBasisOverrideRepository;
    @Mock
    RecalcJobRepository recalcJobRepository;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    OverrideService overrideService;

    private EconomicEvent onChainEvent;

    @BeforeEach
    void setUp() {
        onChainEvent = new EconomicEvent();
        onChainEvent.setId(EVENT_ID);
        onChainEvent.setTxHash("0xtx");
        onChainEvent.setNetworkId(NetworkId.ETHEREUM);
        onChainEvent.setWalletAddress(WALLET);
        onChainEvent.setAssetContract(ASSET_CONTRACT);
        onChainEvent.setAssetSymbol(ASSET_SYMBOL);
        onChainEvent.setEventType(EconomicEventType.SWAP_BUY);
    }

    @Test
    @DisplayName("setOverride throws EVENT_NOT_FOUND when event missing")
    void setOverride_eventNotFound() {
        when(economicEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overrideService.setOverride(EVENT_ID, new BigDecimal("100"), "note"))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.EVENT_NOT_FOUND));

        verify(costBasisOverrideRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("setOverride throws EVENT_NOT_FOUND for MANUAL_COMPENSATING event")
    void setOverride_manualEventRejected() {
        onChainEvent.setEventType(EconomicEventType.MANUAL_COMPENSATING);
        when(economicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(onChainEvent));

        assertThatThrownBy(() -> overrideService.setOverride(EVENT_ID, new BigDecimal("100"), "note"))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.EVENT_NOT_FOUND));

        verify(costBasisOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("setOverride throws OVERRIDE_EXISTS when active override already present")
    void setOverride_overrideExists() {
        when(economicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(onChainEvent));
        when(costBasisOverrideRepository.findByEconomicEventIdAndActiveTrue(EVENT_ID))
                .thenReturn(Optional.of(new CostBasisOverride()));

        assertThatThrownBy(() -> overrideService.setOverride(EVENT_ID, new BigDecimal("100"), "note"))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.OVERRIDE_EXISTS));

        verify(costBasisOverrideRepository, never()).save(any(CostBasisOverride.class));
    }

    @Test
    @DisplayName("setOverride upserts override, creates job, publishes event and returns jobId")
    void setOverride_success() {
        when(economicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(onChainEvent));
        when(costBasisOverrideRepository.findByEconomicEventIdAndActiveTrue(EVENT_ID)).thenReturn(Optional.empty());
        when(costBasisOverrideRepository.findFirstByEconomicEventId(EVENT_ID)).thenReturn(Optional.empty());

        RecalcJob savedJob = new RecalcJob();
        savedJob.setId("job-1");
        when(recalcJobRepository.save(any(RecalcJob.class))).thenAnswer(inv -> {
            RecalcJob j = inv.getArgument(0);
            j.setId("job-1");
            return j;
        });

        String jobId = overrideService.setOverride(EVENT_ID, new BigDecimal("2500.50"), "Airdrop price");

        assertThat(jobId).isEqualTo("job-1");

        ArgumentCaptor<CostBasisOverride> overrideCaptor = ArgumentCaptor.forClass(CostBasisOverride.class);
        verify(costBasisOverrideRepository).save(overrideCaptor.capture());
        CostBasisOverride saved = overrideCaptor.getValue();
        assertThat(saved.getEconomicEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getPriceUsd()).isEqualByComparingTo("2500.50");
        assertThat(saved.getNote()).isEqualTo("Airdrop price");
        assertThat(saved.isActive()).isTrue();

        ArgumentCaptor<RecalcJob> jobCaptor = ArgumentCaptor.forClass(RecalcJob.class);
        verify(recalcJobRepository).save(jobCaptor.capture());
        RecalcJob job = jobCaptor.getValue();
        assertThat(job.getStatus()).isEqualTo(RecalcJob.RecalcStatus.PENDING);
        assertThat(job.getWalletAddress()).isEqualTo(WALLET);
        assertThat(job.getNetworkId()).isEqualTo("ETHEREUM");
        assertThat(job.getAssetContract()).isEqualTo(ASSET_CONTRACT);
        assertThat(job.getAssetSymbol()).isEqualTo(ASSET_SYMBOL);

        ArgumentCaptor<OverrideSavedEvent> eventCaptor = ArgumentCaptor.forClass(OverrideSavedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getJobId()).isEqualTo("job-1");
    }

    @Test
    @DisplayName("revertOverride throws EVENT_NOT_FOUND when event missing")
    void revertOverride_eventNotFound() {
        when(economicEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overrideService.revertOverride(EVENT_ID))
                .isInstanceOf(OverrideServiceException.class)
                .satisfies(e -> assertThat(((OverrideServiceException) e).getErrorCode()).isEqualTo(OverrideService.EVENT_NOT_FOUND));
    }

    @Test
    @DisplayName("revertOverride deactivates override, creates job, publishes event")
    void revertOverride_success() {
        when(economicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(onChainEvent));
        CostBasisOverride existing = new CostBasisOverride();
        existing.setId("ov1");
        existing.setEconomicEventId(EVENT_ID);
        existing.setActive(true);
        when(costBasisOverrideRepository.findFirstByEconomicEventId(EVENT_ID)).thenReturn(Optional.of(existing));
        when(recalcJobRepository.save(any(RecalcJob.class))).thenAnswer(inv -> {
            RecalcJob j = inv.getArgument(0);
            j.setId("job-2");
            return j;
        });

        String jobId = overrideService.revertOverride(EVENT_ID);

        assertThat(jobId).isEqualTo("job-2");
        verify(costBasisOverrideRepository).save(existing);
        assertThat(existing.isActive()).isFalse();
        verify(applicationEventPublisher).publishEvent(any(OverrideSavedEvent.class));
    }
}
