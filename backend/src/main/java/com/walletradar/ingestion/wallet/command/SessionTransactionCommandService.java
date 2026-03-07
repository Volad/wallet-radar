package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.session.SessionTransaction;
import com.walletradar.domain.transaction.session.SessionTransactionRepository;
import com.walletradar.domain.transaction.session.SessionTransactionSortKeyFactory;
import com.walletradar.domain.transaction.session.SessionTransactionSourceType;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds and persists session-scoped CHAIN transaction rows from confirmed normalized transactions.
 */
@Service
@RequiredArgsConstructor
public class SessionTransactionCommandService {

    private final UserSessionRepository userSessionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final SessionTransactionRepository sessionTransactionRepository;

    public Optional<RebuildResult> rebuildChainTransactions(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalizedSessionId = sessionId.trim();
        Optional<UserSession> sessionOpt = userSessionRepository.findById(normalizedSessionId);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        List<SessionTransaction> projected = new ArrayList<>();
        List<String> walletAddresses = sessionOpt.get().getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .filter(address -> address != null && !address.isBlank())
                .map(address -> address.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();

        for (String walletAddress : walletAddresses) {
            List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                    .findByWalletAddressAndStatusOrderByBlockTimestampAsc(walletAddress, NormalizedTransactionStatus.CONFIRMED);
            for (NormalizedTransaction tx : confirmed) {
                projected.add(toSessionTransaction(normalizedSessionId, tx, now));
            }
        }

        sessionTransactionRepository.deleteBySessionIdAndSourceType(normalizedSessionId, SessionTransactionSourceType.CHAIN);
        if (!projected.isEmpty()) {
            sessionTransactionRepository.saveAll(projected);
        }

        return Optional.of(new RebuildResult(normalizedSessionId, projected.size(), "Session transactions rebuilt"));
    }

    private static SessionTransaction toSessionTransaction(String sessionId, NormalizedTransaction tx, Instant now) {
        SessionTransaction row = new SessionTransaction();
        String sourceId = resolveSourceId(tx);
        row.setId(sessionId + ":" + SessionTransactionSourceType.CHAIN.name() + ":" + sourceId);
        row.setSessionId(sessionId);
        row.setSourceType(SessionTransactionSourceType.CHAIN);
        row.setSourceId(sourceId);
        row.setTxHash(tx.getTxHash());
        row.setNetworkId(tx.getNetworkId());
        row.setWalletAddress(tx.getWalletAddress());
        row.setBlockTimestamp(tx.getBlockTimestamp());
        row.setType(tx.getType());
        row.setSortKey(SessionTransactionSortKeyFactory.fromNormalized(tx, SessionTransactionSourceType.CHAIN));
        row.setFlows(mapFlows(tx.getFlows()));
        row.setRealisedPnlUsdTotal(sumRealisedPnl(tx.getFlows()));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static String resolveSourceId(NormalizedTransaction tx) {
        if (tx.getId() != null && !tx.getId().isBlank()) {
            return tx.getId();
        }
        return (tx.getTxHash() != null ? tx.getTxHash() : "unknown") + ":"
                + (tx.getNetworkId() != null ? tx.getNetworkId().name() : "unknown") + ":"
                + (tx.getWalletAddress() != null ? tx.getWalletAddress() : "unknown");
    }

    private static List<SessionTransaction.Flow> mapFlows(List<NormalizedTransaction.Flow> flows) {
        List<SessionTransaction.Flow> mapped = new ArrayList<>();
        if (flows == null) {
            return mapped;
        }
        for (NormalizedTransaction.Flow flow : flows) {
            SessionTransaction.Flow rowFlow = new SessionTransaction.Flow();
            rowFlow.setRole(flow.getRole());
            rowFlow.setAssetContract(flow.getAssetContract());
            rowFlow.setAssetSymbol(flow.getAssetSymbol());
            rowFlow.setQuantityDelta(flow.getQuantityDelta());
            rowFlow.setUnitPriceUsd(flow.getUnitPriceUsd());
            rowFlow.setValueUsd(flow.getValueUsd());
            rowFlow.setPriceSource(flow.getPriceSource());
            rowFlow.setInferred(flow.isInferred());
            rowFlow.setInferenceReason(flow.getInferenceReason());
            rowFlow.setConfidence(flow.getConfidence());
            rowFlow.setLogIndex(flow.getLogIndex());
            mapped.add(rowFlow);
        }
        return mapped;
    }

    private static BigDecimal sumRealisedPnl(List<NormalizedTransaction.Flow> flows) {
        if (flows == null || flows.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        boolean hasAny = false;
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow.getRealisedPnlUsd() != null) {
                hasAny = true;
                sum = sum.add(flow.getRealisedPnlUsd());
            }
        }
        return hasAny ? sum : null;
    }

    public record RebuildResult(
            String sessionId,
            Integer projectedTransactions,
            String message
    ) {
    }
}
