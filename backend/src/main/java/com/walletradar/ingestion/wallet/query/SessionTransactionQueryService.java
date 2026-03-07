package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.session.SessionTransaction;
import com.walletradar.domain.transaction.session.SessionTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only query service for session transaction timeline.
 */
@Service
@RequiredArgsConstructor
public class SessionTransactionQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final UserSessionRepository userSessionRepository;
    private final SessionTransactionRepository sessionTransactionRepository;

    public Optional<SessionTransactionsView> findTransactions(String sessionId, Integer limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalizedSessionId = sessionId.trim();
        if (!userSessionRepository.existsById(normalizedSessionId)) {
            return Optional.empty();
        }

        int effectiveLimit = clampLimit(limit);
        PageRequest pageRequest = PageRequest.of(
                0,
                effectiveLimit,
                Sort.by(Sort.Order.desc("blockTimestamp"), Sort.Order.desc("sortKey")));
        List<SessionTransactionItemView> items = sessionTransactionRepository
                .findBySessionId(normalizedSessionId, pageRequest).stream()
                .map(this::toItem)
                .toList();
        return Optional.of(new SessionTransactionsView(normalizedSessionId, items));
    }

    private SessionTransactionItemView toItem(SessionTransaction row) {
        return new SessionTransactionItemView(
                row.getId(),
                row.getSourceType() != null ? row.getSourceType().name() : null,
                row.getTxHash(),
                row.getNetworkId() != null ? row.getNetworkId().name() : null,
                row.getWalletAddress(),
                row.getBlockTimestamp(),
                row.getType() != null ? row.getType().name() : null,
                row.getBridgeStatus() != null ? row.getBridgeStatus().name() : null,
                row.getRealisedPnlUsdTotal(),
                row.getAvcoSnapshotVersion(),
                row.getFlows().stream()
                        .map(flow -> new SessionTransactionFlowView(
                                flow.getRole() != null ? flow.getRole().name() : null,
                                flow.getAssetContract(),
                                flow.getAssetSymbol(),
                                flow.getQuantityDelta(),
                                flow.getUnitPriceUsd(),
                                flow.getValueUsd(),
                                flow.getPriceSource() != null ? flow.getPriceSource().name() : null,
                                flow.getLogIndex()))
                        .toList()
        );
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    public record SessionTransactionsView(
            String sessionId,
            List<SessionTransactionItemView> items
    ) {
    }

    public record SessionTransactionItemView(
            String id,
            String sourceType,
            String txHash,
            String networkId,
            String walletAddress,
            Instant blockTimestamp,
            String type,
            String bridgeStatus,
            BigDecimal realisedPnlUsdTotal,
            Long avcoSnapshotVersion,
            List<SessionTransactionFlowView> flows
    ) {
    }

    public record SessionTransactionFlowView(
            String role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            BigDecimal valueUsd,
            String priceSource,
            Integer logIndex
    ) {
    }
}
