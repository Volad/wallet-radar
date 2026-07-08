package com.walletradar.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * DEBUG / DIAGNOSTIC (Cycle 15) — TEMPORARY.
 * Backs {@link AccountingShortfallAudit}. REMOVE after coverage acceptance.
 */
public interface AccountingShortfallAuditRepository extends MongoRepository<AccountingShortfallAudit, String> {

    List<AccountingShortfallAudit> findByAccountingUniverseIdOrderByBlockTimestampDesc(String accountingUniverseId);

    void deleteByAccountingUniverseId(String accountingUniverseId);
}
