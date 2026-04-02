package com.walletradar.session.application;

import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recovery watchdog that re-emits session backfill completion when raw backfill
 * is already complete but the event-driven pipeline still has pending work.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionPipelineResumeScheduler {

    private static final Duration RUNNING_STATE_STALE_AFTER = Duration.ofMinutes(2);
    private static final Criteria ACTIVE_ACCOUNTING_CRITERIA = new Criteria().orOperator(
            Criteria.where("excludedFromAccounting").exists(false),
            Criteria.where("excludedFromAccounting").is(Boolean.FALSE)
    );

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final MongoOperations mongoOperations;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Scheduled(fixedDelayString = "${walletradar.pipeline.resume-interval-ms:60000}")
    public void resumeReadySessions() {
        for (UserSession session : userSessionRepository.findAll()) {
            ResumeAction action = nextAction(session);
            if (action == null) {
                continue;
            }
            applicationEventPublisher.publishEvent(action.event());
            log.info(
                    "Session pipeline resume published: sessionId={}, stage={}, reason={}",
                    session.getId(),
                    action.stage(),
                    action.reason()
            );
        }
    }

    private ResumeAction nextAction(UserSession session) {
        if (session == null || session.getId() == null || session.getWallets() == null || session.getWallets().isEmpty()) {
            return null;
        }
        if (hasFreshRunningState(session)) {
            log.info(
                    "Session pipeline resume deferred: sessionId={}, stage={}, status=RUNNING, updatedAt={}",
                    session.getId(),
                    session.getPipelineState() == null ? null : session.getPipelineState().getStage(),
                    session.getPipelineState() == null ? null : session.getPipelineState().getUpdatedAt()
            );
            return null;
        }
        if (!allBackfillTargetsComplete(session)) {
            return null;
        }
        List<String> addresses = trackedAddresses(session);
        if (hasPendingRaw(addresses) || !hasNormalizedRows(addresses)) {
            int targetCount = targetCount(session);
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                    "pending-raw-or-empty-normalized",
                    new SessionBackfillCompletedEvent(session.getId(), session.getWallets().size(), targetCount)
            );
        }
        if (hasPendingClarification(addresses)) {
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    "pending-clarification",
                    new OnChainNormalizationCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        if (hasPendingBybitWork(session)) {
            return new ResumeAction(
                    UserSession.PipelineStage.BYBIT_NORMALIZATION,
                    "raw-bybit-or-unmatched-rematch",
                    new OnChainClarificationCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        if (hasPendingPrice(addresses)) {
            return new ResumeAction(
                    UserSession.PipelineStage.PRICING,
                    "pending-price",
                    new BybitNormalizationCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        if (hasPendingStat(addresses) || requiresReplayBootstrap(addresses)) {
            return new ResumeAction(
                    UserSession.PipelineStage.ACCOUNTING_REPLAY,
                    "pending-stat-or-empty-positions",
                    new PricingCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        return null;
    }

    private boolean allBackfillTargetsComplete(UserSession session) {
        List<String> addresses = trackedAddresses(session);
        Map<String, SyncStatus> syncStatusByPair = syncStatusRepository.findByWalletAddressIn(addresses).stream()
                .filter(status -> status.getWalletAddress() != null && status.getNetworkId() != null)
                .collect(Collectors.toMap(
                        status -> pairKey(status.getWalletAddress(), status.getNetworkId()),
                        status -> status,
                        (left, right) -> right
                ));

        for (UserSession.SessionWallet wallet : session.getWallets()) {
            for (var network : wallet.getNetworks()) {
                SyncStatus status = syncStatusByPair.get(pairKey(wallet.getAddress(), network.name()));
                if (status == null || !status.isBackfillComplete()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasPendingRaw(List<String> addresses) {
        Query pendingRawQuery = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("normalizationStatus").is("PENDING")
        ));
        return mongoOperations.exists(pendingRawQuery, RawTransaction.class);
    }

    private boolean hasPendingBybitWork(UserSession session) {
        Query rawBybitQuery = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(session.getId()),
                Criteria.where("status").is("RAW")
        ));
        if (mongoOperations.exists(rawBybitQuery, ExternalLedgerRaw.class)) {
            return true;
        }

        Query bybitRematchQuery = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(session.getId()),
                Criteria.where("status").is("CONFIRMED"),
                Criteria.where("sourceFileType").is("withdraw_deposit"),
                Criteria.where("onChainCorrelation.status").is("UNMATCHED")
        ));
        return mongoOperations.exists(bybitRematchQuery, ExternalLedgerRaw.class);
    }

    private boolean hasPendingClarification(List<String> addresses) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_CLARIFICATION")
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean hasPendingPrice(List<String> addresses) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_PRICE"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean hasPendingStat(List<String> addresses) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_STAT"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean requiresReplayBootstrap(List<String> addresses) {
        Query confirmedQuery = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        if (!mongoOperations.exists(confirmedQuery, NormalizedTransaction.class)) {
            return false;
        }
        return !mongoOperations.exists(new Query(), "asset_positions");
    }

    private boolean hasNormalizedRows(List<String> addresses) {
        Query normalizedQuery = new Query(Criteria.where("walletAddress").in(addresses));
        return mongoOperations.exists(normalizedQuery, "normalized_transactions");
    }

    private boolean hasFreshRunningState(UserSession session) {
        UserSession.PipelineState pipelineState = session.getPipelineState();
        if (pipelineState == null || pipelineState.getStatus() != UserSession.PipelineStatus.RUNNING) {
            return false;
        }
        Instant updatedAt = pipelineState.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        return updatedAt.isAfter(Instant.now().minus(RUNNING_STATE_STALE_AFTER));
    }

    private List<String> trackedAddresses(UserSession session) {
        return session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();
    }

    private int targetCount(UserSession session) {
        return session.getWallets().stream()
                .mapToInt(wallet -> wallet.getNetworks() == null ? 0 : wallet.getNetworks().size())
                .sum();
    }

    private static String pairKey(String walletAddress, String networkId) {
        return walletAddress + "|" + networkId;
    }

    private record ResumeAction(
            UserSession.PipelineStage stage,
            String reason,
            Object event
    ) {
    }
}
