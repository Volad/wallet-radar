package com.walletradar.domain.transaction.externalledger;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for immutable Bybit raw ledger rows.
 */
public interface ExternalLedgerRawRepository extends MongoRepository<ExternalLedgerRaw, String> {
}
