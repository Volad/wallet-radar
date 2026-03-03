package com.walletradar.domain.accounting;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for cost_basis_overrides. Used by AvcoEngine to apply active overrides to on-chain events (INV-08).
 */
public interface CostBasisOverrideRepository extends MongoRepository<CostBasisOverride, String> {

    /** Active overrides for the given normalized leg ids. */
    List<CostBasisOverride> findByNormalizedLegIdInAndActiveTrue(List<String> normalizedLegIds);

    /** Check for existing active override (one per leg); used for 409 OVERRIDE_EXISTS. */
    Optional<CostBasisOverride> findByNormalizedLegIdAndActiveTrue(String normalizedLegId);

    /** Find override for leg (for revert: deactivate). One document per normalizedLegId by design. */
    Optional<CostBasisOverride> findFirstByNormalizedLegId(String normalizedLegId);
}
