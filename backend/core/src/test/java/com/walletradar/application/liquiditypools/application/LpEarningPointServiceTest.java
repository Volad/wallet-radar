package com.walletradar.application.liquiditypools.application;

import com.walletradar.application.liquiditypools.persistence.LpEarningPoint;
import com.walletradar.application.liquiditypools.persistence.LpEarningPointRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LpEarningPointServiceTest {

    @Mock
    private LpEarningPointRepository repository;

    @Test
    void upsertDailyPointComposesIdAndPersistsClaimAddBackScenario() {
        LpEarningPointService service = new LpEarningPointService(repository);
        LocalDate day = LocalDate.of(2025, 6, 24);
        LpEarningPoint point = new LpEarningPoint();
        point.setCorrelationId("lp-position:ethereum:0xabc:123");
        point.setUniverseId("universe-1");
        point.setDay(day);
        point.setCumulativeEarnedUsd(new BigDecimal("250.00"));
        point.setDailyEarnedUsd(new BigDecimal("69.40"));
        point.setDailyAprPct(new BigDecimal("12.5"));
        point.setPositionValueUsd(new BigDecimal("5000"));

        service.upsertDailyPoint(point);

        ArgumentCaptor<LpEarningPoint> captor = ArgumentCaptor.forClass(LpEarningPoint.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("lp-position:ethereum:0xabc:123:2025-06-24");
        assertThat(captor.getValue().getDailyEarnedUsd()).isEqualByComparingTo("69.40");
        assertThat(captor.getValue().getCumulativeEarnedUsd()).isEqualByComparingTo("250.00");
    }
}
