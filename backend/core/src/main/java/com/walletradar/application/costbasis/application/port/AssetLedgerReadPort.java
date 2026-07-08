package com.walletradar.application.costbasis.application.port;

import com.walletradar.application.costbasis.application.AssetLedgerQueryService;

import java.util.Optional;

/**
 * BFF-facing read contract for session-scoped asset ledger history.
 */
public interface AssetLedgerReadPort {

    Optional<AssetLedgerQueryService.SessionAssetLedgerView> findSessionFamilyLedger(
            String sessionId,
            String familyIdentity
    );
}
