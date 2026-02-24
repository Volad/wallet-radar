package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for cost_basis_overrides. Used by AvcoEngine to apply active overrides to on-chain events (INV-08).
 */
public interface CostBasisOverrideRepository extends MongoRepository<CostBasisOverride, String> {

    /** Active overrides for the given event ids (on-chain events only). */
    List<CostBasisOverride> findByEconomicEventIdInAndIsActiveTrue(List<String> economicEventIds);

    /** Check for existing active override (one per event); used for 409 OVERRIDE_EXISTS. */
    Optional<CostBasisOverride> findByEconomicEventIdAndIsActiveTrue(String economicEventId);

    /** Find override for event (for revert: deactivate). One document per eventId by design. */
    Optional<CostBasisOverride> findFirstByEconomicEventId(String economicEventId);
}
