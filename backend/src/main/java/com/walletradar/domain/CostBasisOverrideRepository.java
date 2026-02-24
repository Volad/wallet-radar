package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for cost_basis_overrides. Used by AvcoEngine to apply active overrides to on-chain events (INV-08).
 */
public interface CostBasisOverrideRepository extends MongoRepository<CostBasisOverride, String> {

    /** Active overrides for the given event ids (on-chain events only). */
    List<CostBasisOverride> findByEconomicEventIdInAndIsActiveTrue(List<String> economicEventIds);
}
