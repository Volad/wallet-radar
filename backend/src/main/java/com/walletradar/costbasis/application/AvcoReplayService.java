package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.dispatch.ReplayDispatcher;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.planning.PassThroughCorridorPlanner;
import com.walletradar.costbasis.application.replay.query.ConfirmedReplayQueryService;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Deterministic AVCO replay over confirmed canonical transactions only.
 */
@Service
@RequiredArgsConstructor
public class AvcoReplayService {

    private static final int HEARTBEAT_EVERY_TRANSACTIONS = 50;

    private final ConfirmedReplayQueryService confirmedReplayQueryService;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
    private final PassThroughCorridorPlanner passThroughCorridorPlanner;
    private final ReplayAssetSupport replayAssetSupport;
    private final ReplayFlowSupport replayFlowSupport;
    private final ReplayDispatcher replayDispatcher;

    public int replayConfirmed() {
        return replayConfirmed(null, null, null);
    }

    public int replayConfirmed(String accountingUniverseId, Collection<String> memberRefs) {
        return replayConfirmed(accountingUniverseId, memberRefs, null);
    }

    public int replayConfirmed(String accountingUniverseId, Collection<String> memberRefs, Runnable heartbeat) {
        List<NormalizedTransaction> ordered = memberRefs == null || memberRefs.isEmpty()
                ? confirmedReplayQueryService.loadOrderedConfirmed()
                : confirmedReplayQueryService.loadOrderedConfirmed(memberRefs);
        var passThroughCorridorPlan = passThroughCorridorPlanner.buildPlan(ordered, replayAssetSupport::assetKey);
        List<NormalizedTransaction> updatedTransactions = new ArrayList<>(ordered.size());
        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        LedgerPointCollector ledgerPointCollector = new LedgerPointCollector(
                normalizedAccountingUniverseId(accountingUniverseId),
                ledgerPoints,
                Instant.now()
        );
        ReplayExecutionState replayState = new ReplayExecutionState(passThroughCorridorPlan, ledgerPointCollector);

        for (int transactionIndex = 0; transactionIndex < ordered.size(); transactionIndex++) {
            if (heartbeat != null && transactionIndex % HEARTBEAT_EVERY_TRANSACTIONS == 0) {
                heartbeat.run();
            }
            NormalizedTransaction replayed = replayFlowSupport.copyTransaction(ordered.get(transactionIndex));
            replayDispatcher.dispatch(replayed, replayState);
            updatedTransactions.add(replayed);
        }

        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            assetLedgerPointRepository.deleteAll();
        } else {
            assetLedgerPointRepository.deleteAllByAccountingUniverseId(accountingUniverseId);
        }
        if (!ledgerPoints.isEmpty()) {
            assetLedgerPointRepository.saveAll(ledgerPoints);
        }
        normalizedTransactionRepository.saveAll(updatedTransactions);
        if (heartbeat != null) {
            heartbeat.run();
        }
        return updatedTransactions.size();
    }

    private String normalizedAccountingUniverseId(String accountingUniverseId) {
        return accountingUniverseId == null || accountingUniverseId.isBlank()
                ? "GLOBAL"
                : accountingUniverseId.trim();
    }
}
