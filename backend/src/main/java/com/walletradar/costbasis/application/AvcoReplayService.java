package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.dispatch.ReplayDispatcher;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.planning.PassThroughCorridorPlanner;
import com.walletradar.costbasis.application.replay.query.ConfirmedReplayQueryService;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.costbasis.application.replay.state.CounterpartyBasisPoolReplayContext;
import com.walletradar.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.costbasis.domain.AccountingShortfallAudit;
import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.costbasis.domain.BorrowLiability;
import com.walletradar.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolKey;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final CounterpartyBasisPoolService counterpartyBasisPoolService;
    private final LpReceiptBasisPoolService lpReceiptBasisPoolService;
    private final BorrowLiabilityTracker borrowLiabilityTracker;
    private final AccountingShortfallAuditService accountingShortfallAuditService;
    private final AccountingUniverseService accountingUniverseService;

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
        String universeId = normalizedAccountingUniverseId(accountingUniverseId);
        LedgerPointCollector ledgerPointCollector = new LedgerPointCollector(
                universeId,
                ledgerPoints,
                Instant.now()
        );
        boolean bindUniverse = !"GLOBAL".equals(universeId);
        if (bindUniverse) {
            accountingUniverseService.bindUniverse(universeId);
        }
        Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> counterpartyPools =
                counterpartyBasisPoolService.loadAllForUniverse(universeId);
        Set<CounterpartyBasisPoolKey> dirtyCounterpartyPools = new HashSet<>();
        CounterpartyBasisPoolReplayContext poolContext = new CounterpartyBasisPoolReplayContext(
                universeId,
                counterpartyPools,
                dirtyCounterpartyPools
        );
        Map<String, BorrowLiability> borrowLiabilities = borrowLiabilityTracker.loadAllForUniverse(universeId);
        Set<String> dirtyBorrowLiabilities = new HashSet<>();
        BorrowLiabilityReplayContext borrowContext = new BorrowLiabilityReplayContext(
                universeId,
                borrowLiabilities,
                dirtyBorrowLiabilities
        );
        Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> lpReceiptPools =
                lpReceiptBasisPoolService.loadAllForUniverse(universeId);
        Set<LpReceiptBasisPoolKey> dirtyLpReceiptPools = new HashSet<>();
        LpReceiptBasisPoolReplayContext lpReceiptContext = new LpReceiptBasisPoolReplayContext(
                universeId,
                lpReceiptPools,
                dirtyLpReceiptPools
        );
        ReplayExecutionState replayState = new ReplayExecutionState(
                passThroughCorridorPlan,
                ledgerPointCollector,
                poolContext,
                borrowContext,
                lpReceiptContext
        );
        Instant replayStartedAt = Instant.now();

        try {
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
            counterpartyBasisPoolService.replaceUniversePools(universeId, counterpartyPools);
            lpReceiptBasisPoolService.replaceUniversePools(universeId, lpReceiptPools);
            borrowLiabilityTracker.replaceUniverseLiabilities(universeId, borrowLiabilities);
            List<AccountingShortfallAudit> shortfallAudits =
                    accountingShortfallAuditService.collectFromLedgerPoints(ledgerPoints, replayStartedAt);
            accountingShortfallAuditService.replaceUniverseAudits(universeId, shortfallAudits);
            normalizedTransactionRepository.saveAll(updatedTransactions);
        } finally {
            if (bindUniverse) {
                accountingUniverseService.clearUniverseBinding();
            }
        }
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
