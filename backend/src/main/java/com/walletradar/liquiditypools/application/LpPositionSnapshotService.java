package com.walletradar.liquiditypools.application;

import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LpPositionSnapshotService {

    private final LpPositionSnapshotRepository repository;

    public LpPositionSnapshot upsert(LpPositionSnapshot snapshot) {
        return repository.save(snapshot);
    }

    public List<LpPositionSnapshot> findByUniverseId(String universeId) {
        if (universeId == null || universeId.isBlank()) {
            return List.of();
        }
        return repository.findByUniverseId(universeId.trim());
    }

    public Optional<LpPositionSnapshot> findByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByCorrelationId(correlationId.trim());
    }
}
