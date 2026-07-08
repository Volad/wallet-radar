package com.walletradar.application.linking.job;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.linking.query.LinkingPendingStatusQuery;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SessionPipelineActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Session-scoped readiness gate for the dedicated linking phase.
 */
@Service
@RequiredArgsConstructor
public class LinkingDataGateService implements LinkingPendingStatusQuery {

    private static final Duration CLASSIFICATION_ACTIVITY_STALE_AFTER = Duration.ofMinutes(2);
    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    private static final String EXTERNAL_CUSTODY_REASON = "EXTERNAL_CUSTODY_UNTRACKED_VENUE";

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
    private final AccountingUniverseService accountingUniverseService;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final MongoOperations mongoOperations;

    public LinkingGateSnapshot snapshot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new LinkingGateSnapshot(false, 0L, 0L, 0L, 0L, false);
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::snapshot)
                .orElseGet(() -> new LinkingGateSnapshot(false, 0L, 0L, 0L, 0L, false));
    }

    public boolean ready(String sessionId) {
        return snapshot(sessionId).ready();
    }

    @Override
    public boolean hasPendingLinking(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::hasPendingLinking)
                .orElse(false);
    }

    private LinkingGateSnapshot snapshot(UserSession session) {
        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        long pendingOnChainClassification = countPendingOnChainClassification(scope.memberRefs());
        long pendingClarification = countPendingClarification(scope.memberRefs());
        long pendingReclassification = countPendingReclassification(scope.memberRefs());
        long pendingBybitClassification = countPendingBybitClassification(session.getId());
        boolean classificationStillRunning = hasActiveClassificationActivity(session.getId())
                || hasFreshClassificationRunningState(session);
        return new LinkingGateSnapshot(
                pendingOnChainClassification == 0L
                        && pendingClarification == 0L
                        && pendingReclassification == 0L
                        && pendingBybitClassification == 0L
                        && !classificationStillRunning,
                pendingOnChainClassification,
                pendingClarification,
                pendingReclassification,
                pendingBybitClassification,
                classificationStillRunning
        );
    }

    private boolean hasPendingLinking(UserSession session) {
        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        List<String> memberRefs = scope.memberRefs();
        if (memberRefs == null || memberRefs.isEmpty()) {
            return false;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(memberRefs),
                ACTIVE_ACCOUNTING_CRITERIA,
                new Criteria().orOperator(
                        onChainLinkingCandidateCriteria(),
                        bybitLinkingCandidateCriteria(),
                        legacySealedBridgeRepairCriteria(),
                        onChainInternalTransferRepairCriteria()
                )
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    /** Cycle/14: sealed bridge pairs that need continuity re-evaluation. */
    private Criteria legacySealedBridgeRepairCriteria() {
        return new Criteria().andOperator(
                Criteria.where("type").is("BRIDGE_OUT"),
                Criteria.where("continuityCandidate").is(false),
                Criteria.where("correlationId").regex("^bridge:")
        );
    }

    /** Cycle/14: same-tx reciprocal INTERNAL_TRANSFER rows missing continuity metadata. */
    private Criteria onChainInternalTransferRepairCriteria() {
        return new Criteria().andOperator(
                Criteria.where("source").is("ON_CHAIN"),
                Criteria.where("type").is("INTERNAL_TRANSFER"),
                Criteria.where("continuityCandidate").is(false),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is("")
                )
        );
    }

    private Criteria onChainLinkingCandidateCriteria() {
        return new Criteria().andOperator(
                Criteria.where("source").is("ON_CHAIN"),
                Criteria.where("status").in("CONFIRMED", "PENDING_PRICE"),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("networkId").exists(true).ne(null),
                Criteria.where("type").in("BRIDGE_OUT", "EXTERNAL_TRANSFER_IN", "EXTERNAL_TRANSFER_OUT", "INTERNAL_TRANSFER"),
                missingPairingCriteria()
        );
    }

    private Criteria bybitLinkingCandidateCriteria() {
        return new Criteria().andOperator(
                Criteria.where("source").is("BYBIT"),
                Criteria.where("status").in("CONFIRMED", "PENDING_PRICE"),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("networkId").exists(true).ne(null),
                Criteria.where("type").in("EXTERNAL_TRANSFER_IN", "EXTERNAL_TRANSFER_OUT"),
                new Criteria().orOperator(
                        Criteria.where("missingDataReasons").exists(false),
                        Criteria.where("missingDataReasons").nin(BRIDGE_MISSING_REASON, EXTERNAL_CUSTODY_REASON)
                ),
                new Criteria().orOperator(
                        Criteria.where("accountingExclusionReason").exists(false),
                        Criteria.where("accountingExclusionReason").ne(EXTERNAL_CUSTODY_REASON)
                ),
                missingPairingCriteria()
        );
    }

    private Criteria missingPairingCriteria() {
        return new Criteria().orOperator(
                Criteria.where("correlationId").exists(false),
                Criteria.where("correlationId").is(null),
                Criteria.where("correlationId").is(""),
                Criteria.where("matchedCounterparty").exists(false),
                Criteria.where("matchedCounterparty").is(null),
                Criteria.where("matchedCounterparty").is("")
        );
    }

    private long countPendingClarification(List<String> memberRefs) {
        return countPendingStatus(memberRefs, "PENDING_CLARIFICATION");
    }

    private long countPendingReclassification(List<String> memberRefs) {
        return countPendingStatus(memberRefs, "PENDING_RECLASSIFICATION");
    }

    private long countPendingStatus(List<String> memberRefs, String status) {
        if (memberRefs == null || memberRefs.isEmpty()) {
            return 0L;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(memberRefs),
                Criteria.where("status").is(status)
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countPendingOnChainClassification(List<String> memberRefs) {
        if (memberRefs == null || memberRefs.isEmpty()) {
            return 0L;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(memberRefs),
                Criteria.where("normalizationStatus").is("PENDING")
        ));
        return mongoOperations.count(query, "raw_transactions");
    }

    private long countPendingBybitClassification(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        Query extractedRaw = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(sessionId),
                IN_SCOPE_BYBIT_RAW_CRITERIA
        ));
        long pending = mongoOperations.count(extractedRaw, BybitExtractedEvent.class);
        if (pending > 0L) {
            return pending;
        }

        Query legacyRaw = new Query(new Criteria().andOperator(
                Criteria.where("sessionId").is(sessionId),
                IN_SCOPE_BYBIT_RAW_CRITERIA
        ));
        pending += mongoOperations.count(legacyRaw, ExternalLedgerRaw.class);
        return pending;
    }

    private boolean hasActiveClassificationActivity(String sessionId) {
        return sessionPipelineActivityService.hasFreshActivity(
                sessionId,
                UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                CLASSIFICATION_ACTIVITY_STALE_AFTER
        ) || sessionPipelineActivityService.hasFreshActivity(
                sessionId,
                UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                CLASSIFICATION_ACTIVITY_STALE_AFTER
        ) || sessionPipelineActivityService.hasFreshActivity(
                sessionId,
                UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
                CLASSIFICATION_ACTIVITY_STALE_AFTER
        ) || sessionPipelineActivityService.hasFreshActivity(
                sessionId,
                UserSession.PipelineStage.BYBIT_NORMALIZATION,
                CLASSIFICATION_ACTIVITY_STALE_AFTER
        );
    }

    private boolean hasFreshClassificationRunningState(UserSession session) {
        if (session == null || session.getPipelineState() == null) {
            return false;
        }
        UserSession.PipelineState pipelineState = session.getPipelineState();
        if (pipelineState.getStatus() != UserSession.PipelineStatus.RUNNING || pipelineState.getStage() == null) {
            return false;
        }
        if (pipelineState.getUpdatedAt() == null
                || pipelineState.getUpdatedAt().isBefore(Instant.now().minus(CLASSIFICATION_ACTIVITY_STALE_AFTER))) {
            return false;
        }
        return switch (pipelineState.getStage()) {
            case ON_CHAIN_NORMALIZATION, ON_CHAIN_CLARIFICATION, ON_CHAIN_RECLASSIFICATION, BYBIT_NORMALIZATION -> true;
            default -> false;
        };
    }

    public record LinkingGateSnapshot(
            boolean ready,
            long pendingOnChainClassificationCount,
            long pendingClarificationCount,
            long pendingReclassificationCount,
            long pendingBybitClassificationCount,
            boolean classificationStillRunning
    ) {
    }
}
