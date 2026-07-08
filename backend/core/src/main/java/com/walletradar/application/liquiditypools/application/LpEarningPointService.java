package com.walletradar.application.liquiditypools.application;

import com.walletradar.application.liquiditypools.persistence.LpEarningPoint;
import com.walletradar.application.liquiditypools.persistence.LpEarningPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LpEarningPointService {

    private final LpEarningPointRepository repository;

    public LpEarningPoint upsertDailyPoint(LpEarningPoint point) {
        if (point.getDay() != null && point.getCorrelationId() != null) {
            point.setId(LpEarningPoint.composeId(point.getCorrelationId(), point.getDay()));
        }
        return repository.save(point);
    }

    public List<LpEarningPoint> findSeriesByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        return repository.findByCorrelationIdOrderByDayAsc(correlationId.trim());
    }

    public Optional<LpEarningPoint> findByCorrelationIdAndDay(String correlationId, LocalDate day) {
        if (correlationId == null || day == null) {
            return Optional.empty();
        }
        return repository.findById(LpEarningPoint.composeId(correlationId.trim(), day));
    }
}
