package com.walletradar.session.application;

import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.raw.RawTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final MongoOperations mongoOperations;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Scheduled(fixedDelayString = "${walletradar.pipeline.resume-interval-ms:60000}")
    public void resumeReadySessions() {
        for (UserSession session : userSessionRepository.findAll()) {
            if (!isEligible(session)) {
                continue;
            }
            int targetCount = targetCount(session);
            applicationEventPublisher.publishEvent(new SessionBackfillCompletedEvent(
                    session.getId(),
                    session.getWallets().size(),
                    targetCount
            ));
            log.info(
                    "Session pipeline resume published: sessionId={}, wallets={}, targets={}",
                    session.getId(),
                    session.getWallets().size(),
                    targetCount
            );
        }
    }

    private boolean isEligible(UserSession session) {
        if (session == null || session.getId() == null || session.getWallets() == null || session.getWallets().isEmpty()) {
            return false;
        }
        if (session.getPipelineState() != null
                && session.getPipelineState().getStatus() == UserSession.PipelineStatus.RUNNING) {
            return false;
        }
        if (!allBackfillTargetsComplete(session)) {
            return false;
        }
        return hasPendingPipelineWork(session);
    }

    private boolean allBackfillTargetsComplete(UserSession session) {
        List<String> addresses = session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();
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

    private boolean hasPendingPipelineWork(UserSession session) {
        List<String> addresses = session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();

        Query pendingRawQuery = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(addresses),
                Criteria.where("normalizationStatus").is("PENDING")
        ));
        if (mongoOperations.exists(pendingRawQuery, RawTransaction.class)) {
            return true;
        }

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
        if (mongoOperations.exists(bybitRematchQuery, ExternalLedgerRaw.class)) {
            return true;
        }

        Query normalizedQuery = new Query(Criteria.where("walletAddress").in(addresses));
        return !mongoOperations.exists(normalizedQuery, "normalized_transactions");
    }

    private int targetCount(UserSession session) {
        return session.getWallets().stream()
                .mapToInt(wallet -> wallet.getNetworks() == null ? 0 : wallet.getNetworks().size())
                .sum();
    }

    private static String pairKey(String walletAddress, String networkId) {
        return walletAddress + "|" + networkId;
    }
}
