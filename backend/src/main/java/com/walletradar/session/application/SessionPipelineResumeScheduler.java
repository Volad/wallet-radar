package com.walletradar.session.application;

import com.walletradar.domain.event.BybitNormalizationRequestedEvent;
import com.walletradar.domain.event.LinkingRequestedEvent;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.event.PricingRequestedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.job.linking.LinkingDataGateService;
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
    private static final Criteria IN_SCOPE_BYBIT_RAW_CRITERIA = new Criteria().andOperator(
            Criteria.where("status").is("RAW"),
            Criteria.where("basisRelevant").is(Boolean.TRUE),
            new Criteria().orOperator(
                    Criteria.where("outOfScope").exists(false),
                    Criteria.where("outOfScope").is(Boolean.FALSE)
            ),
            new Criteria().orOperator(
                    Criteria.where("canonicalType").exists(false),
                    Criteria.where("canonicalType").ne("UNKNOWN_CEX")
            )
    );

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final LinkingDataGateService linkingDataGateService;
    private final MongoOperations mongoOperations;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineStateService sessionPipelineStateService;
    private final SessionPipelineActivityService sessionPipelineActivityService;

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
        if (session == null || session.getId() == null || targetCount(session) == 0) {
            return null;
        }
        var freshActivity = sessionPipelineActivityService.latestFreshActivity(session.getId(), RUNNING_STATE_STALE_AFTER);
        if (freshActivity.isPresent()) {
            SessionPipelineActivityService.ActivitySnapshot snapshot = freshActivity.get();
            log.info(
                    "Session pipeline resume deferred by in-memory activity: sessionId={}, stage={}, heartbeatAt={}",
                    session.getId(),
                    snapshot.stage(),
                    snapshot.heartbeatAt()
            );
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
        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        List<String> addresses = scope.memberRefs();
        boolean pendingRaw = hasPendingRaw(addresses);
        boolean hasNormalized = hasNormalizedRows(addresses);
        if (pendingRaw || !hasNormalized) {
            int targetCount = targetCount(session);
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                    "pending-raw-or-empty-normalized",
                    new SessionBackfillCompletedEvent(session.getId(), session.getWallets().size(), targetCount)
            );
        }
        boolean pendingClarification = hasPendingClarification(addresses);
        if (pendingClarification) {
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    "pending-clarification",
                    new OnChainNormalizationCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        boolean pendingBybit = hasPendingBybitWork(session);
        if (pendingBybit) {
            return new ResumeAction(
                    UserSession.PipelineStage.BYBIT_NORMALIZATION,
                    "raw-bybit-or-unmatched-rematch",
                    new BybitNormalizationRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        boolean pendingLinking = linkingDataGateService.hasPendingLinking(session.getId());
        boolean pendingPrice = hasPendingPrice(addresses);
        if (pendingLinking && !isReplayAlreadyComplete(session)) {
            return new ResumeAction(
                    UserSession.PipelineStage.LINKING,
                    "pending-linking",
                    new LinkingRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        if (pendingPrice) {
            return new ResumeAction(
                    UserSession.PipelineStage.PRICING,
                    "pending-price",
                    new PricingRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        boolean pendingStat = hasPendingStat(addresses);
        boolean replayBootstrapRequired = requiresReplayBootstrap(scope);
        if (pendingStat || replayBootstrapRequired) {
            return new ResumeAction(
                    UserSession.PipelineStage.ACCOUNTING_REPLAY,
                    "pending-stat-or-empty-ledger",
                    new PricingCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        if (shouldHealReplayCompletion(
                session,
                scope,
                pendingRaw,
                hasNormalized,
                pendingClarification,
                pendingBybit,
                pendingLinking,
                pendingPrice,
                pendingStat,
                replayBootstrapRequired
        )) {
            sessionPipelineStateService.markStageComplete(
                    session.getId(),
                    UserSession.PipelineStage.ACCOUNTING_REPLAY,
                    "Accounting replay complete"
            );
        }
        return null;
    }

    private boolean allBackfillTargetsComplete(UserSession session) {
        if (!allOnChainBackfillTargetsComplete(session)) {
            return false;
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            if (!isIntegrationBackfillComplete(integration)) {
                return false;
            }
        }
        return true;
    }

    private boolean allOnChainBackfillTargetsComplete(UserSession session) {
        if (session.getWallets() == null || session.getWallets().isEmpty()) {
            return true;
        }
        List<String> addresses = trackedAddresses(session);
        Map<String, SyncStatus> syncStatusByPair = syncStatusRepository.findByWalletAddressIn(addresses).stream()
                .filter(status -> status.getSourceKind() == null || status.getSourceKind() == SyncStatus.SourceKind.ONCHAIN)
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
        if (addresses.isEmpty()) {
            return false;
        }
        Query pendingRawQuery = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("normalizationStatus").is("PENDING")
        ));
        return mongoOperations.exists(pendingRawQuery, RawTransaction.class);
    }

    private boolean hasPendingBybitWork(UserSession session) {
        Query rawExtractedQuery = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(session.getId()),
                IN_SCOPE_BYBIT_RAW_CRITERIA
        ));
        if (mongoOperations.exists(rawExtractedQuery, BybitExtractedEvent.class)) {
            return true;
        }

        Query rawBybitQuery = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(session.getId()),
                IN_SCOPE_BYBIT_RAW_CRITERIA
        ));
        return mongoOperations.exists(rawBybitQuery, ExternalLedgerRaw.class);
    }

    private boolean hasPendingClarification(List<String> addresses) {
        if (addresses.isEmpty()) {
            return false;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_CLARIFICATION")
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean hasPendingPrice(List<String> addresses) {
        if (addresses.isEmpty()) {
            return false;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_PRICE"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean hasPendingStat(List<String> addresses) {
        if (addresses.isEmpty()) {
            return false;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_STAT"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean hasNormalizedRows(List<String> addresses) {
        if (addresses.isEmpty()) {
            return false;
        }
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

    private boolean isReplayAlreadyComplete(UserSession session) {
        UserSession.PipelineState pipelineState = session.getPipelineState();
        return pipelineState != null
                && pipelineState.getStage() == UserSession.PipelineStage.ACCOUNTING_REPLAY
                && pipelineState.getStatus() == UserSession.PipelineStatus.COMPLETE;
    }

    private boolean shouldHealReplayCompletion(
            UserSession session,
            AccountingUniverseService.AccountingUniverseScope scope,
            boolean pendingRaw,
            boolean hasNormalized,
            boolean pendingClarification,
            boolean pendingBybit,
            boolean pendingLinking,
            boolean pendingPrice,
            boolean pendingStat,
            boolean replayBootstrapRequired
    ) {
        UserSession.PipelineState pipelineState = session.getPipelineState();
        if (pipelineState == null
                || pipelineState.getStage() != UserSession.PipelineStage.ACCOUNTING_REPLAY
                || pipelineState.getStatus() != UserSession.PipelineStatus.RUNNING
                || hasFreshRunningState(session)) {
            return false;
        }
        if (pendingRaw
                || !hasNormalized
                || pendingClarification
                || pendingBybit
                || pendingLinking
                || pendingPrice
                || pendingStat
                || replayBootstrapRequired) {
            return false;
        }
        List<String> addresses = scope.memberRefs();
        List<String> onChainWalletRefs = scope.onChainWalletRefs();
        return hasAssetLedgerRows(scope.accountingUniverseId(), addresses)
                && hasOnChainBalanceRows(session.getId(), onChainWalletRefs);
    }

    private boolean hasAssetLedgerRows(List<String> addresses) {
        if (addresses.isEmpty()) {
            return false;
        }
        Query query = new Query(Criteria.where("walletAddress").in(addresses));
        return mongoOperations.exists(query, "asset_ledger_points");
    }

    private boolean hasAssetLedgerRows(String accountingUniverseId, List<String> addresses) {
        if (accountingUniverseId != null && !accountingUniverseId.isBlank()) {
            Query query = new Query(Criteria.where("accountingUniverseId").is(accountingUniverseId));
            return mongoOperations.exists(query, "asset_ledger_points");
        }
        return hasAssetLedgerRows(addresses);
    }

    private boolean hasOnChainBalanceRows(String sessionId, List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return true;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(sessionId),
                Criteria.where("walletAddress").in(addresses)
        ));
        return mongoOperations.exists(query, "on_chain_balances");
    }

    private boolean requiresReplayBootstrap(AccountingUniverseService.AccountingUniverseScope scope) {
        List<String> addresses = scope.memberRefs();
        if (addresses.isEmpty()) {
            return false;
        }
        Query confirmedQuery = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        if (!mongoOperations.exists(confirmedQuery, NormalizedTransaction.class)) {
            return false;
        }
        return !hasAssetLedgerRows(scope.accountingUniverseId(), addresses);
    }

    private List<String> trackedAddresses(UserSession session) {
        if (session.getWallets() == null || session.getWallets().isEmpty()) {
            return List.of();
        }
        return session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();
    }

    private int targetCount(UserSession session) {
        int walletTargets = session.getWallets() == null ? 0 : session.getWallets().stream()
                .mapToInt(wallet -> wallet.getNetworks() == null ? 0 : wallet.getNetworks().size())
                .sum();
        return walletTargets + enabledIntegrations(session).size();
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(integration -> integration != null
                        && integration.getStatus() != UserSession.IntegrationStatus.DISABLED
                        && integration.getIntegrationId() != null
                        && !integration.getIntegrationId().isBlank())
                .toList();
    }

    private boolean isIntegrationBackfillComplete(UserSession.SessionIntegration integration) {
        SyncStatus integrationStatus = syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()).orElse(null);
        if (integrationStatus != null) {
            return integrationStatus.isBackfillComplete();
        }
        long totalSegments = backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
        if (totalSegments <= 0) {
            return integration.getStatus() == UserSession.IntegrationStatus.READY;
        }
        long completedSegments = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.COMPLETE
        );
        return completedSegments >= totalSegments;
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
