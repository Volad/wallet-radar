package com.walletradar.application.session.application;

import com.walletradar.domain.event.AccountingReplayCompletedEvent;
import com.walletradar.domain.event.BybitNormalizationRequestedEvent;
import com.walletradar.domain.event.DzengiNormalizationRequestedEvent;
import com.walletradar.domain.event.LinkingRequestedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.OnChainReclassificationRequestedEvent;
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
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.linking.job.LinkingDataGateService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Criteria IN_SCOPE_CEX_RAW_CRITERIA = new Criteria().andOperator(
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

    @Scheduled(fixedDelayString = "${walletradar.pipeline.jobs.resume-interval-ms:60000}")
    public void resumeReadySessions() {
        List<UserSession> sessions = userSessionRepository.findAll();
        ResumeGateSnapshot gateSnapshot = buildResumeGateSnapshot(sessions);
        for (UserSession session : sessions) {
            ResumeAction action = nextAction(session, gateSnapshot);
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

    private ResumeAction nextAction(UserSession session, ResumeGateSnapshot gateSnapshot) {
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
        boolean pendingRaw = gateSnapshot.hasPendingRaw(addresses);
        boolean hasNormalized = gateSnapshot.hasNormalizedRows(addresses);
        if (pendingRaw || !hasNormalized) {
            int targetCount = targetCount(session);
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                    "pending-raw-or-empty-normalized",
                    new SessionBackfillCompletedEvent(session.getId(), session.getWallets().size(), targetCount)
            );
        }
        boolean pendingClarification = gateSnapshot.hasPendingClarification(addresses);
        if (pendingClarification) {
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    "pending-clarification",
                    new OnChainNormalizationCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        boolean pendingReclassification = gateSnapshot.hasPendingReclassification(addresses);
        if (pendingReclassification) {
            return new ResumeAction(
                    UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
                    "pending-reclassification",
                    new OnChainReclassificationRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        boolean pendingBybit = gateSnapshot.hasPendingBybitWork(session.getId());
        if (pendingBybit) {
            return new ResumeAction(
                    UserSession.PipelineStage.BYBIT_NORMALIZATION,
                    "raw-bybit-or-unmatched-rematch",
                    new BybitNormalizationRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        boolean pendingDzengi = gateSnapshot.hasPendingDzengiWork(session.getId());
        if (pendingDzengi) {
            return new ResumeAction(
                    UserSession.PipelineStage.DZENGI_NORMALIZATION,
                    "raw-dzengi-extracted",
                    new DzengiNormalizationRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        boolean pendingLinking = linkingDataGateService.hasPendingLinking(session.getId());
        boolean pendingPrice = gateSnapshot.hasPendingPrice(addresses);
        // Cycle/14: linking (incl. legacy bridge repair) must run before snapshot refresh when
        // balances were wiped by replay-only reset — otherwise repair never executes.
        if (pendingLinking && !isPipelineAlreadyComplete(session)) {
            return new ResumeAction(
                    UserSession.PipelineStage.LINKING,
                    "pending-linking",
                    new LinkingRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        if (gateSnapshot.requiresBalanceSnapshotRefresh(session.getId(), addresses, isPortfolioSnapshotComplete(session))) {
            return new ResumeAction(
                    UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                    "portfolio-snapshot-refresh-missing-balances",
                    new AccountingReplayCompletedEvent(session.getId(), 0, "resume-watchdog")
            );
        }
        if (pendingPrice) {
            return new ResumeAction(
                    UserSession.PipelineStage.PRICING,
                    "pending-price",
                    new PricingRequestedEvent(session.getId(), "resume-watchdog")
            );
        }
        boolean pendingStat = gateSnapshot.hasPendingStat(addresses);
        boolean replayBootstrapRequired = gateSnapshot.requiresReplayBootstrap(scope);
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
                pendingReclassification,
                pendingBybit,
                pendingDzengi,
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
            return null;
        }
        if (gateSnapshot.requiresPortfolioSnapshotRefresh(scope, isPortfolioSnapshotComplete(session))) {
            return new ResumeAction(
                    UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                    "portfolio-snapshot-refresh",
                    new AccountingReplayCompletedEvent(session.getId(), 0, "resume-watchdog")
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
                if (!isOnChainBackfillComplete(status)) {
                    return false;
                }
                if (status != null && !status.isBackfillComplete()) {
                    log.warn(
                            "Backfill gate treating terminal sync_status as complete despite stale flag: "
                                    + "sessionId={}, wallet={}, network={}, status={}, progressPct={}",
                            session.getId(),
                            wallet.getAddress(),
                            network.name(),
                            status.getStatus(),
                            status.getProgressPct()
                    );
                }
            }
        }
        return true;
    }

    /**
     * Robustness net: a wallet×network counts as backfill-complete when its boolean flag is set OR the
     * sync_status reached terminal {@code COMPLETE} status. {@code COMPLETE} cannot coexist with
     * RUNNING/PENDING/FAILED segments, so a genuinely in-flight source is never advanced prematurely;
     * this only rescues sessions stranded by a stale completion boolean on an already-terminal source.
     */
    private static boolean isOnChainBackfillComplete(SyncStatus status) {
        if (status == null) {
            return false;
        }
        return status.isBackfillComplete() || status.getStatus() == SyncStatus.SyncStatusValue.COMPLETE;
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
                IN_SCOPE_CEX_RAW_CRITERIA
        ));
        if (mongoOperations.exists(rawExtractedQuery, BybitExtractedEvent.class)) {
            return true;
        }

        Query rawBybitQuery = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(session.getId()),
                IN_SCOPE_CEX_RAW_CRITERIA
        ));
        return mongoOperations.exists(rawBybitQuery, ExternalLedgerRaw.class);
    }

    private boolean hasPendingClarification(List<String> addresses) {
        if (addresses.isEmpty()) {
            return false;
        }
        java.util.Date now = new java.util.Date();
        // Only consider items whose clarification lease has expired (or was never set).
        // Items still held by an active lease are being processed (or were held by a crashed process
        // that will release them on the next clarification run). Firing the watchdog for those items
        // just produces empty clarification cycles and confuses the pipeline progress display.
        Criteria leaseFree = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is("PENDING_CLARIFICATION"),
                leaseFree
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean hasPendingReclassification(List<String> addresses) {
        return hasPendingStatus(addresses, "PENDING_RECLASSIFICATION");
    }

    private boolean hasPendingStatus(List<String> addresses, String status) {
        if (addresses.isEmpty()) {
            return false;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("status").is(status)
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

    private boolean isPipelineAlreadyComplete(UserSession session) {
        UserSession.PipelineState pipelineState = session.getPipelineState();
        return pipelineState != null
                && (pipelineState.getStage() == UserSession.PipelineStage.ACCOUNTING_REPLAY
                || pipelineState.getStage() == UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH)
                && pipelineState.getStatus() == UserSession.PipelineStatus.COMPLETE;
    }

    private boolean isPortfolioSnapshotComplete(UserSession session) {
        UserSession.PipelineState pipelineState = session.getPipelineState();
        return pipelineState != null
                && pipelineState.getStage() == UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH
                && pipelineState.getStatus() == UserSession.PipelineStatus.COMPLETE;
    }

    private boolean shouldHealReplayCompletion(
            UserSession session,
            AccountingUniverseService.AccountingUniverseScope scope,
            boolean pendingRaw,
            boolean hasNormalized,
            boolean pendingClarification,
            boolean pendingReclassification,
            boolean pendingBybit,
            boolean pendingDzengi,
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
                || pendingReclassification
                || pendingBybit
                || pendingDzengi
                || pendingLinking
                || pendingPrice
                || pendingStat
                || replayBootstrapRequired) {
            return false;
        }
        List<String> addresses = scope.memberRefs();
        return hasAssetLedgerRows(scope.accountingUniverseId(), addresses);
    }

    private ResumeGateSnapshot buildResumeGateSnapshot(List<UserSession> sessions) {
        if (sessions.isEmpty()) {
            return ResumeGateSnapshot.empty();
        }
        Map<String, List<String>> addressesBySession = sessions.stream()
                .filter(session -> session.getId() != null && !session.getId().isBlank())
                .collect(Collectors.toMap(
                        UserSession::getId,
                        session -> accountingUniverseService.resolveScope(session).memberRefs(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Set<String> allAddresses = addressesBySession.values().stream()
                .flatMap(List::stream)
                .filter(address -> address != null && !address.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        java.util.Date now = new java.util.Date();
        Criteria leaseFree = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );

        Set<String> pendingRawWallets = distinctWallets(new Criteria().andOperator(
                Criteria.where("walletAddress").in(allAddresses),
                Criteria.where("normalizationStatus").is("PENDING")
        ), RawTransaction.class);

        Set<String> normalizedWallets = distinctWallets(
                Criteria.where("walletAddress").in(allAddresses),
                "normalized_transactions"
        );

        Set<String> pendingClarificationWallets = distinctWallets(new Criteria().andOperator(
                Criteria.where("walletAddress").in(allAddresses),
                Criteria.where("status").is("PENDING_CLARIFICATION"),
                leaseFree
        ), NormalizedTransaction.class);

        Set<String> pendingReclassificationWallets = distinctWallets(new Criteria().andOperator(
                Criteria.where("walletAddress").in(allAddresses),
                Criteria.where("status").is("PENDING_RECLASSIFICATION")
        ), NormalizedTransaction.class);

        Set<String> pendingPriceWallets = distinctWallets(new Criteria().andOperator(
                Criteria.where("walletAddress").in(allAddresses),
                Criteria.where("status").is("PENDING_PRICE"),
                ACTIVE_ACCOUNTING_CRITERIA
        ), NormalizedTransaction.class);

        Set<String> pendingStatWallets = distinctWallets(new Criteria().andOperator(
                Criteria.where("walletAddress").in(allAddresses),
                Criteria.where("status").is("PENDING_STAT"),
                ACTIVE_ACCOUNTING_CRITERIA
        ), NormalizedTransaction.class);

        Set<String> confirmedWallets = distinctWallets(new Criteria().andOperator(
                Criteria.where("walletAddress").in(allAddresses),
                Criteria.where("status").is("CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA
        ), NormalizedTransaction.class);

        Set<String> ledgerWallets = distinctWallets(
                Criteria.where("walletAddress").in(allAddresses),
                "asset_ledger_points"
        );

        Set<String> balanceSessionIds = distinctSessionIds(new Criteria().andOperator(
                Criteria.where("sessionId").in(addressesBySession.keySet()),
                Criteria.where("walletAddress").in(allAddresses)
        ), "on_chain_balances", "sessionId");

        Set<String> bybitExtractedSessions = distinctSessionIds(new Criteria().andOperator(
                Criteria.where("sessionId").in(addressesBySession.keySet()),
                IN_SCOPE_CEX_RAW_CRITERIA
        ), BybitExtractedEvent.class, "sessionId");

        Set<String> bybitRawSessions = distinctSessionIds(new Criteria().andOperator(
                Criteria.where("sessionId").in(addressesBySession.keySet()),
                IN_SCOPE_CEX_RAW_CRITERIA
        ), ExternalLedgerRaw.class, "sessionId");

        Set<String> dzengiExtractedSessions = distinctSessionIds(new Criteria().andOperator(
                Criteria.where("sessionId").in(addressesBySession.keySet()),
                IN_SCOPE_CEX_RAW_CRITERIA
        ), DzengiExtractedEvent.class, "sessionId");

        Map<String, String> universeBySession = sessions.stream()
                .filter(session -> session.getId() != null && !session.getId().isBlank())
                .collect(Collectors.toMap(
                        UserSession::getId,
                        session -> accountingUniverseService.resolveScope(session).accountingUniverseId(),
                        (left, right) -> left
                ));
        Set<String> ledgerUniverses = distinctUniverses(
                Criteria.where("accountingUniverseId").in(new HashSet<>(universeBySession.values()))
        );

        return new ResumeGateSnapshot(
                pendingRawWallets,
                normalizedWallets,
                pendingClarificationWallets,
                pendingReclassificationWallets,
                pendingPriceWallets,
                pendingStatWallets,
                confirmedWallets,
                ledgerWallets,
                balanceSessionIds,
                bybitExtractedSessions,
                bybitRawSessions,
                dzengiExtractedSessions,
                ledgerUniverses,
                universeBySession
        );
    }

    private Set<String> distinctWallets(Criteria criteria, Class<?> entityClass) {
        if (criteria == null) {
            return Set.of();
        }
        return new HashSet<>(mongoOperations.findDistinct(new Query(criteria), "walletAddress", entityClass, String.class));
    }

    private Set<String> distinctWallets(Criteria criteria, String collectionName) {
        if (criteria == null) {
            return Set.of();
        }
        return new HashSet<>(mongoOperations.findDistinct(new Query(criteria), "walletAddress", collectionName, String.class));
    }

    private Set<String> distinctSessionIds(Criteria criteria, Class<?> entityClass, String fieldName) {
        if (criteria == null) {
            return Set.of();
        }
        return new HashSet<>(mongoOperations.findDistinct(new Query(criteria), fieldName, entityClass, String.class));
    }

    private Set<String> distinctSessionIds(Criteria criteria, String collectionName, String fieldName) {
        if (criteria == null) {
            return Set.of();
        }
        return new HashSet<>(mongoOperations.findDistinct(new Query(criteria), fieldName, collectionName, String.class));
    }

    private Set<String> distinctUniverses(Criteria criteria) {
        if (criteria == null) {
            return Set.of();
        }
        return new HashSet<>(mongoOperations.findDistinct(new Query(criteria), "accountingUniverseId", "asset_ledger_points", String.class));
    }

    private static boolean intersects(Set<String> values, List<String> addresses) {
        if (values.isEmpty() || addresses == null || addresses.isEmpty()) {
            return false;
        }
        for (String address : addresses) {
            if (address != null && values.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private record ResumeGateSnapshot(
            Set<String> pendingRawWallets,
            Set<String> normalizedWallets,
            Set<String> pendingClarificationWallets,
            Set<String> pendingReclassificationWallets,
            Set<String> pendingPriceWallets,
            Set<String> pendingStatWallets,
            Set<String> confirmedWallets,
            Set<String> ledgerWallets,
            Set<String> balanceSessionIds,
            Set<String> bybitExtractedSessions,
            Set<String> bybitRawSessions,
            Set<String> dzengiExtractedSessions,
            Set<String> ledgerUniverses,
            Map<String, String> universeBySession
    ) {
        boolean hasPendingRaw(List<String> addresses) {
            return intersects(pendingRawWallets, addresses);
        }

        boolean hasNormalizedRows(List<String> addresses) {
            return intersects(normalizedWallets, addresses);
        }

        boolean hasPendingClarification(List<String> addresses) {
            return intersects(pendingClarificationWallets, addresses);
        }

        boolean hasPendingReclassification(List<String> addresses) {
            return intersects(pendingReclassificationWallets, addresses);
        }

        boolean hasPendingPrice(List<String> addresses) {
            return intersects(pendingPriceWallets, addresses);
        }

        boolean hasPendingStat(List<String> addresses) {
            return intersects(pendingStatWallets, addresses);
        }

        boolean hasPendingBybitWork(String sessionId) {
            return bybitExtractedSessions.contains(sessionId) || bybitRawSessions.contains(sessionId);
        }

        boolean hasPendingDzengiWork(String sessionId) {
            return dzengiExtractedSessions.contains(sessionId);
        }

        boolean requiresBalanceSnapshotRefresh(String sessionId, List<String> addresses, boolean snapshotComplete) {
            if (snapshotComplete || addresses == null || addresses.isEmpty()) {
                return false;
            }
            return !balanceSessionIds.contains(sessionId);
        }

        boolean requiresReplayBootstrap(AccountingUniverseService.AccountingUniverseScope scope) {
            List<String> addresses = scope.memberRefs();
            if (addresses.isEmpty()) {
                return false;
            }
            if (!intersects(confirmedWallets, addresses)) {
                return false;
            }
            String universeId = scope.accountingUniverseId();
            if (universeId != null && !universeId.isBlank()) {
                return !ledgerUniverses.contains(universeId);
            }
            return !intersects(ledgerWallets, addresses);
        }

        boolean requiresPortfolioSnapshotRefresh(
                AccountingUniverseService.AccountingUniverseScope scope,
                boolean snapshotComplete
        ) {
            if (snapshotComplete) {
                return false;
            }
            List<String> addresses = scope.memberRefs();
            String universeId = scope.accountingUniverseId();
            if (universeId != null && !universeId.isBlank()) {
                return ledgerUniverses.contains(universeId);
            }
            return intersects(ledgerWallets, addresses);
        }

        static ResumeGateSnapshot empty() {
            return new ResumeGateSnapshot(
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Map.of()
            );
        }
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

    private boolean requiresBalanceSnapshotRefresh(UserSession session) {
        if (isPortfolioSnapshotComplete(session)) {
            return false;
        }
        List<String> addresses = trackedAddresses(session);
        return !addresses.isEmpty() && !hasOnChainBalanceRows(session.getId(), addresses);
    }

    private boolean requiresPortfolioSnapshotRefresh(
            UserSession session,
            AccountingUniverseService.AccountingUniverseScope scope
    ) {
        if (isPortfolioSnapshotComplete(session)) {
            return false;
        }
        if (!hasAssetLedgerRows(scope.accountingUniverseId(), scope.memberRefs())) {
            return false;
        }
        return true;
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
