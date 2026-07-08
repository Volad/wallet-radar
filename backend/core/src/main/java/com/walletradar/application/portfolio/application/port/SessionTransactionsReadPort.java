package com.walletradar.application.portfolio.application.port;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.portfolio.application.SessionTransactionsQueryService;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * BFF-facing read contract for session transaction history.
 */
public interface SessionTransactionsReadPort {

    Optional<SessionTransactionsQueryService.SessionTransactionsView> findSessionTransactions(
            String sessionId,
            SessionTransactionsQueryService.TransactionsQuery query
    );

    Optional<SessionTransactionsQueryService.RebuildTransactionsView> rebuildSessionTransactions(String sessionId);

    static SessionTransactionsQueryService.TransactionsQuery normalizeQuery(
            Integer limit,
            Integer offset,
            String search,
            Collection<String> categories,
            Collection<String> walletIds,
            List<NetworkId> networkIds
    ) {
        return SessionTransactionsQueryService.normalizeQuery(
                limit,
                offset,
                search,
                categories,
                walletIds,
                networkIds
        );
    }
}
