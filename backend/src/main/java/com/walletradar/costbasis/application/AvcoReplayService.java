package com.walletradar.costbasis.application;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.accounting.support.BridgeAssetFamilySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.costbasis.support.AssetLedgerSupport;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic AVCO replay over confirmed canonical transactions only.
 */
@Service
@RequiredArgsConstructor
public class AvcoReplayService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal CORRELATED_TRANSFER_RELATIVE_TOLERANCE = new BigDecimal("0.000001");
    private static final BigDecimal CORRELATED_TRANSFER_ABSOLUTE_TOLERANCE = new BigDecimal("0.00000001");
    private static final BigDecimal CORRELATED_TRANSFER_MAX_TOLERANCE = new BigDecimal("0.0001");
    private static final Map<String, String> CORRELATED_TRANSFER_SYMBOL_ALIASES = Map.ofEntries(
            Map.entry("USDT0", "USDT"),
            Map.entry("USD₮0", "USDT")
    );

    private final ConfirmedReplayQueryService confirmedReplayQueryService;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;

    public int replayConfirmed() {
        return replayConfirmed(null, null);
    }

    public int replayConfirmed(String accountingUniverseId, Collection<String> memberRefs) {
        List<NormalizedTransaction> ordered = memberRefs == null || memberRefs.isEmpty()
                ? confirmedReplayQueryService.loadOrderedConfirmed()
                : confirmedReplayQueryService.loadOrderedConfirmed(memberRefs);
        PassThroughCorridorPlan passThroughCorridorPlan = buildPassThroughCorridorPlan(ordered);
        Map<AssetKey, PositionState> positions = new LinkedHashMap<>();
        Map<ContinuityKey, ContinuityBucket> continuityBuckets = new LinkedHashMap<>();
        Map<String, Deque<CarryTransfer>> pendingTransfers = new LinkedHashMap<>();
        Map<String, CarryTransfer> reservedPassThroughCarries = new LinkedHashMap<>();
        Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets = new LinkedHashMap<>();
        Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets = new LinkedHashMap<>();
        List<NormalizedTransaction> updatedTransactions = new ArrayList<>();
        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        Instant now = Instant.now();
        LedgerPointCollector ledgerPointCollector = new LedgerPointCollector(
                normalizedAccountingUniverseId(accountingUniverseId),
                ledgerPoints,
                now
        );

        for (NormalizedTransaction transaction : ordered) {
            NormalizedTransaction replayed = copyTransaction(transaction);
            if (replayed.getType() == NormalizedTransactionType.LENDING_LOOP_REBALANCE) {
                applyEulerLoopRebalance(replayed, positions, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (isGmxLpEntryRequest(replayed)) {
                applyGmxLpEntryRequest(replayed, positions, asyncLifecycleBuckets, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (isGmxLpEntrySettlement(replayed)) {
                applyGmxLpEntrySettlement(replayed, positions, asyncLifecycleBuckets, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (replayed.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT) {
                applyAsyncLpExitSettlement(replayed, positions, asyncLifecycleBuckets, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (isPositionScopedLpExit(replayed)) {
                applyPositionScopedLpExit(replayed, positions, asyncLifecycleBuckets, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (isLiquidStakingContinuityTransaction(replayed)) {
                applyLiquidStakingConversion(
                        replayed,
                        positions,
                        continuityBuckets,
                        pendingTransfers,
                        passThroughCorridorPlan,
                        reservedPassThroughCarries,
                        asyncLifecycleBuckets,
                        asyncSpotOrderBuckets,
                        ledgerPointCollector
                );
                updatedTransactions.add(replayed);
                continue;
            }
            SimpleFamilyCustodySelection familyCustodySelection = selectSimpleFamilyEquivalentCustodyFlows(replayed);
            if (!familyCustodySelection.pairs().isEmpty()) {
                applySimpleFamilyEquivalentCustodyFlows(
                        replayed,
                        familyCustodySelection,
                        positions,
                        continuityBuckets,
                        pendingTransfers,
                        passThroughCorridorPlan,
                        reservedPassThroughCarries,
                        asyncLifecycleBuckets,
                        asyncSpotOrderBuckets,
                        ledgerPointCollector
                );
                updatedTransactions.add(replayed);
                continue;
            }
            for (int flowIndex = 0; flowIndex < replayed.getFlows().size(); flowIndex++) {
                NormalizedTransaction.Flow flow = replayed.getFlows().get(flowIndex);
                applyFlow(
                        replayed,
                        flow,
                        flowIndex,
                        positions,
                        continuityBuckets,
                        pendingTransfers,
                        passThroughCorridorPlan,
                        reservedPassThroughCarries,
                        asyncLifecycleBuckets,
                        asyncSpotOrderBuckets,
                        ledgerPointCollector
                );
            }
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
        return updatedTransactions.size();
    }

    private String normalizedAccountingUniverseId(String accountingUniverseId) {
        return accountingUniverseId == null || accountingUniverseId.isBlank()
                ? "GLOBAL"
                : accountingUniverseId.trim();
    }

    private boolean isLiquidStakingContinuityTransaction(NormalizedTransaction transaction) {
        LiquidStakingFlowSelection selection = selectLiquidStakingPrincipalFlows(transaction);
        return !selection.outbound().isEmpty() && !selection.inbound().isEmpty();
    }

    private void applyLiquidStakingConversion(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            Map<ContinuityKey, ContinuityBucket> continuityBuckets,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        LiquidStakingFlowSelection selection = selectLiquidStakingPrincipalFlows(transaction);
        if (selection.outbound().isEmpty() || selection.inbound().isEmpty()) {
            for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
                applyFlow(
                        transaction,
                        indexedFlow.flow(),
                        indexedFlow.index(),
                        positions,
                        continuityBuckets,
                        pendingTransfers,
                        passThroughCorridorPlan,
                        reservedPassThroughCarries,
                        asyncLifecycleBuckets,
                        asyncSpotOrderBuckets,
                        ledgerPointCollector
                );
            }
            return;
        }

        Map<Integer, IndexedFlow> principalByIndex = new LinkedHashMap<>();
        for (IndexedFlow indexedFlow : selection.outbound()) {
            principalByIndex.put(indexedFlow.index(), indexedFlow);
        }
        for (IndexedFlow indexedFlow : selection.inbound()) {
            principalByIndex.put(indexedFlow.index(), indexedFlow);
        }

        LiquidStakingCarry carry = new LiquidStakingCarry();
        for (IndexedFlow indexedFlow : selection.outbound()) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);

            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            CarryTransfer removedCarry = removeFromPosition(flow, position);
            carry.add(removedCarry);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
        }

        allocateLiquidStakingInbound(
                transaction,
                selection.inbound(),
                positions,
                ledgerPointCollector,
                carry
        );

        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            if (principalByIndex.containsKey(indexedFlow.index())) {
                continue;
            }
            applyFlow(
                    transaction,
                    indexedFlow.flow(),
                    indexedFlow.index(),
                    positions,
                    continuityBuckets,
                    pendingTransfers,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries,
                    asyncLifecycleBuckets,
                    asyncSpotOrderBuckets,
                    ledgerPointCollector
            );
        }
    }

    private void allocateLiquidStakingInbound(
            NormalizedTransaction transaction,
            List<IndexedFlow> inboundFlows,
            Map<AssetKey, PositionState> positions,
            LedgerPointCollector ledgerPointCollector,
            LiquidStakingCarry carry
    ) {
        if (carry.totalSourceQuantity().signum() <= 0) {
            for (IndexedFlow indexedFlow : inboundFlows) {
                applyFallbackSettlementFlow(transaction, indexedFlow.flow(), positions, ledgerPointCollector);
            }
            return;
        }

        BigDecimal coveredRatio = safeDivide(carry.totalCoveredQuantity(), carry.totalSourceQuantity());
        if (coveredRatio == null) {
            coveredRatio = BigDecimal.ZERO;
        }
        if (coveredRatio.signum() < 0) {
            coveredRatio = BigDecimal.ZERO;
        }
        if (coveredRatio.compareTo(BigDecimal.ONE) > 0) {
            coveredRatio = BigDecimal.ONE;
        }

        BigDecimal totalInboundWeight = totalInboundWeight(inboundFlows);
        BigDecimal remainingCost = carry.totalCostBasisUsd();
        for (int index = 0; index < inboundFlows.size(); index++) {
            IndexedFlow indexedFlow = inboundFlows.get(index);
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);

            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);

            BigDecimal quantity = flow.getQuantityDelta().abs();
            BigDecimal allocatedCost = index == inboundFlows.size() - 1
                    ? remainingCost
                    : carry.totalCostBasisUsd().multiply(
                    inboundWeight(indexedFlow).divide(totalInboundWeight, MC),
                    MC
            );
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            BigDecimal coveredQuantity = quantity.multiply(coveredRatio, MC);
            BigDecimal uncoveredQuantity = nonNegative(quantity.subtract(coveredQuantity, MC));
            BigDecimal avco = coveredQuantity.signum() > 0
                    ? safeDivide(allocatedCost, coveredQuantity)
                    : null;
            restoreToPosition(quantity, position, allocatedCost, uncoveredQuantity, avco);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
        }
    }

    private BigDecimal totalInboundWeight(List<IndexedFlow> inboundFlows) {
        if (allHaveKnownPrices(inboundFlows.stream().map(IndexedFlow::flow).toList())) {
            BigDecimal total = BigDecimal.ZERO;
            for (IndexedFlow inboundFlow : inboundFlows) {
                total = total.add(inboundWeight(inboundFlow), MC);
            }
            if (total.signum() > 0) {
                return total;
            }
        }
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow inboundFlow : inboundFlows) {
            totalQuantity = totalQuantity.add(inboundFlow.flow().getQuantityDelta().abs(), MC);
        }
        return totalQuantity.signum() > 0 ? totalQuantity : BigDecimal.ONE;
    }

    private BigDecimal inboundWeight(IndexedFlow inboundFlow) {
        NormalizedTransaction.Flow flow = inboundFlow.flow();
        if (hasKnownPrice(flow)) {
            BigDecimal value = flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
            if (value.signum() > 0) {
                return value;
            }
        }
        return flow.getQuantityDelta().abs();
    }

    private LiquidStakingFlowSelection selectLiquidStakingPrincipalFlows(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()
                || (transaction.getType() != NormalizedTransactionType.STAKING_DEPOSIT
                && transaction.getType() != NormalizedTransactionType.STAKING_WITHDRAW)) {
            return LiquidStakingFlowSelection.empty();
        }

        Map<String, List<IndexedFlow>> flowsByFamily = new LinkedHashMap<>();
        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
            if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
                continue;
            }
            flowsByFamily.computeIfAbsent(continuityIdentity, ignored -> new ArrayList<>()).add(indexedFlow);
        }

        List<IndexedFlow> outbound = new ArrayList<>();
        List<IndexedFlow> inbound = new ArrayList<>();
        for (List<IndexedFlow> familyFlows : flowsByFamily.values()) {
            boolean hasOutbound = familyFlows.stream().anyMatch(flow -> flow.flow().getQuantityDelta().signum() < 0);
            boolean hasInbound = familyFlows.stream().anyMatch(flow -> flow.flow().getQuantityDelta().signum() > 0);
            long distinctAssets = familyFlows.stream()
                    .map(flow -> assetIdentity(transaction, flow.flow()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            if (!hasOutbound || !hasInbound || distinctAssets < 2) {
                continue;
            }
            for (IndexedFlow familyFlow : familyFlows) {
                if (familyFlow.flow().getQuantityDelta().signum() < 0) {
                    outbound.add(familyFlow);
                } else {
                    inbound.add(familyFlow);
                }
            }
        }
        if (outbound.isEmpty() || inbound.isEmpty()) {
            return LiquidStakingFlowSelection.empty();
        }
        outbound.sort(java.util.Comparator.comparingInt(IndexedFlow::index));
        inbound.sort(java.util.Comparator.comparingInt(IndexedFlow::index));
        return new LiquidStakingFlowSelection(outbound, inbound);
    }

    private SimpleFamilyCustodySelection selectSimpleFamilyEquivalentCustodyFlows(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()
                || !isSimpleFamilyEquivalentCustodyType(transaction.getType())) {
            return SimpleFamilyCustodySelection.empty();
        }

        Map<String, List<IndexedFlow>> flowsByFamily = new LinkedHashMap<>();
        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
            if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
                continue;
            }
            flowsByFamily.computeIfAbsent(continuityIdentity, ignored -> new ArrayList<>()).add(indexedFlow);
        }

        List<SimpleFamilyCustodyPair> pairs = new ArrayList<>();
        Map<Integer, IndexedFlow> selectedByIndex = new LinkedHashMap<>();
        for (List<IndexedFlow> familyFlows : flowsByFamily.values()) {
            if (familyFlows.size() != 2) {
                continue;
            }
            IndexedFlow first = familyFlows.get(0);
            IndexedFlow second = familyFlows.get(1);
            if (first.flow().getQuantityDelta().signum() == second.flow().getQuantityDelta().signum()) {
                continue;
            }
            if (Objects.equals(assetIdentity(transaction, first.flow()), assetIdentity(transaction, second.flow()))) {
                continue;
            }
            IndexedFlow outbound = first.flow().getQuantityDelta().signum() < 0 ? first : second;
            IndexedFlow inbound = outbound == first ? second : first;
            pairs.add(new SimpleFamilyCustodyPair(outbound, inbound));
            selectedByIndex.put(outbound.index(), outbound);
            selectedByIndex.put(inbound.index(), inbound);
        }
        if (pairs.isEmpty()) {
            return SimpleFamilyCustodySelection.empty();
        }
        pairs.sort(java.util.Comparator.comparingInt(pair -> Math.min(pair.outbound().index(), pair.inbound().index())));
        return new SimpleFamilyCustodySelection(pairs, selectedByIndex);
    }

    private boolean isSimpleFamilyEquivalentCustodyType(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    WRAP,
                    UNWRAP,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW -> true;
            default -> false;
        };
    }

    private void applySimpleFamilyEquivalentCustodyFlows(
            NormalizedTransaction transaction,
            SimpleFamilyCustodySelection selection,
            Map<AssetKey, PositionState> positions,
            Map<ContinuityKey, ContinuityBucket> continuityBuckets,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        for (SimpleFamilyCustodyPair pair : selection.pairs()) {
            IndexedFlow outbound = pair.outbound();
            IndexedFlow inbound = pair.inbound();

            AssetKey outboundAssetKey = assetKey(transaction, outbound.flow());
            PositionState outboundPosition = positions.computeIfAbsent(outboundAssetKey, ignored -> new PositionState(outboundAssetKey));
            outboundPosition.lastEventTimestamp = laterOf(outboundPosition.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot outboundBefore = snapshot(outboundPosition);
            CarryTransfer carry = removeTransferCarry(
                    transaction,
                    outbound.flow(),
                    outbound.index(),
                    outboundPosition,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries
            );
            ledgerPointCollector.record(
                    transaction,
                    outbound.flow(),
                    outbound.index(),
                    outboundPosition.assetKey,
                    outboundBefore,
                    outboundPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );

            AssetKey inboundAssetKey = assetKey(transaction, inbound.flow());
            PositionState inboundPosition = positions.computeIfAbsent(inboundAssetKey, ignored -> new PositionState(inboundAssetKey));
            inboundPosition.lastEventTimestamp = laterOf(inboundPosition.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot inboundBefore = snapshot(inboundPosition);
            CarryTransfer effectiveCarry = bridgeInboundCarry(carry, inbound.flow().getQuantityDelta().abs(), inboundPosition.assetKey);
            restoreToPosition(
                    inbound.flow().getQuantityDelta().abs(),
                    inboundPosition,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            ledgerPointCollector.record(
                    transaction,
                    inbound.flow(),
                    inbound.index(),
                    inboundPosition.assetKey,
                    inboundBefore,
                    inboundPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
        }

        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            if (selection.selectedByIndex().containsKey(indexedFlow.index())) {
                continue;
            }
            applyFlow(
                    transaction,
                    indexedFlow.flow(),
                    indexedFlow.index(),
                    positions,
                    continuityBuckets,
                    pendingTransfers,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries,
                    asyncLifecycleBuckets,
                    asyncSpotOrderBuckets,
                    ledgerPointCollector
            );
        }
    }

    private List<IndexedFlow> indexedFlows(NormalizedTransaction transaction) {
        List<IndexedFlow> indexed = new ArrayList<>();
        if (transaction == null || transaction.getFlows() == null) {
            return indexed;
        }
        for (int index = 0; index < transaction.getFlows().size(); index++) {
            indexed.add(new IndexedFlow(index, transaction.getFlows().get(index)));
        }
        return indexed;
    }

    private void applyFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            Map<AssetKey, PositionState> positions,
            Map<ContinuityKey, ContinuityBucket> continuityBuckets,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        if (isAsyncSpotOrderRequestSell(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            applyAsyncSpotOrderRequest(transaction, flow, position, asyncSpotOrderBuckets);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
            return;
        }
        if (isAsyncSpotOrderSettlementBuy(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            applyAsyncSpotOrderSettlement(transaction, flow, position, asyncSpotOrderBuckets);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.ACQUIRE
            );
            return;
        }
        if (isAsyncLifecycleRequestOutbound(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            applyAsyncLifecycleRequest(transaction, flow, position, asyncLifecycleBuckets);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
            return;
        }
        if (isAsyncLifecycleSettlementInbound(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            applyAsyncLifecycleSettlement(transaction, flow, position, asyncLifecycleBuckets);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
            return;
        }
        if (isPositionScopedLpEntryOutbound(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            applyAsyncLifecycleRequest(transaction, flow, position, asyncLifecycleBuckets, continuityIdentity(transaction, flow));
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
            return;
        }
        if (shouldIgnoreLpReceiptMarker(transaction, flow)) {
            return;
        }
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
        PositionSnapshot before = snapshot(position);
        if (isSponsoredGasIn(transaction, flow)) {
            applySponsoredGasIn(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.ACQUIRE
            );
            return;
        }

        if (shouldTreatAsContinuityTransfer(transaction, flow)) {
            AssetLedgerPoint.BasisEffect basisEffect = applyTransfer(
                    transaction,
                    asTransferFlow(flow),
                    flowIndex,
                    position,
                    positions,
                    continuityBuckets,
                    pendingTransfers,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries,
                    ledgerPointCollector
            );
            ledgerPointCollector.record(transaction, flow, flowIndex, position.assetKey, before, position, basisEffect);
            return;
        }

        switch (flow.getRole()) {
            case BUY -> applyBuy(flow, position);
            case SELL -> applySell(flow, position);
            case FEE -> applyFee(flow, position);
            case TRANSFER -> {
                AssetLedgerPoint.BasisEffect basisEffect = applyTransfer(
                        transaction,
                        flow,
                        flowIndex,
                        position,
                        positions,
                        continuityBuckets,
                        pendingTransfers,
                        passThroughCorridorPlan,
                        reservedPassThroughCarries,
                        ledgerPointCollector
                );
                ledgerPointCollector.record(transaction, flow, flowIndex, position.assetKey, before, position, basisEffect);
                return;
            }
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                position.assetKey,
                before,
                position,
                defaultBasisEffect(flow)
        );
    }

    private boolean shouldIgnoreLpReceiptMarker(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (transaction == null || flow == null || flow.getRole() != NormalizedLegRole.TRANSFER) {
            return false;
        }
        if (transaction.getType() == NormalizedTransactionType.LP_ENTRY) {
            return flow.getQuantityDelta().signum() > 0;
        }
        return isLpExitType(transaction.getType()) && flow.getQuantityDelta().signum() < 0;
    }

    private boolean shouldTreatAsContinuityTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && ((transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank())
                || (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()))
                && (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                || transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    private boolean isAsyncLifecycleRequestOutbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta().signum() < 0
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && (transaction.getType() == NormalizedTransactionType.LP_ENTRY_REQUEST
                || transaction.getType() == NormalizedTransactionType.LP_EXIT_REQUEST
                || transaction.getType() == NormalizedTransactionType.STAKING_WITHDRAW_REQUEST);
    }

    private boolean isAsyncLifecycleSettlementInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta().signum() > 0
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && (transaction.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                || transaction.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT
                || transaction.getType() == NormalizedTransactionType.STAKING_WITHDRAW);
    }

    private boolean isGmxLpEntryRequest(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY_REQUEST
                && "GMX".equalsIgnoreCase(transaction.getProtocolName())
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && supportsGmxLpEntryRequestPattern(transaction);
    }

    private boolean isGmxLpEntrySettlement(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                && "GMX".equalsIgnoreCase(transaction.getProtocolName())
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && supportsGmxLpEntrySettlementPattern(transaction);
    }

    private boolean supportsGmxLpEntryRequestPattern(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean hasPrincipalOutbound = false;
        boolean hasNativeExecutionReserve = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() >= 0) {
                return false;
            }
            if (isGmxExecutionFeeReserveFlow(transaction, flow)) {
                hasNativeExecutionReserve = true;
            } else {
                hasPrincipalOutbound = true;
            }
        }
        return hasPrincipalOutbound && hasNativeExecutionReserve;
    }

    private boolean supportsGmxLpEntrySettlementPattern(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean hasShareInflows = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() <= 0) {
                return false;
            }
            if (isGmxShareLikeSymbol(flow.getAssetSymbol())) {
                hasShareInflows = true;
            }
        }
        return hasShareInflows;
    }

    private void applyGmxLpEntryRequest(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        AsyncLifecycleBucket bucket = asyncLifecycleBuckets.computeIfAbsent(
                transaction.getCorrelationId(),
                ignored -> new AsyncLifecycleBucket()
        );

        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);

            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFee(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }

            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() >= 0) {
                applyUnknownTransfer(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                );
                continue;
            }

            CarryTransfer carry = removeFromPosition(flow, position);
            String assetIdentity = assetKey.assetIdentity();
            if (isGmxExecutionFeeReserveFlow(transaction, flow)) {
                bucket.addExecutionFeeReserve(assetIdentity, carry);
            } else {
                bucket.add(assetIdentity, carry);
            }
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
        }
    }

    private void applyGmxLpEntrySettlement(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        AsyncLifecycleBucket bucket = asyncLifecycleBuckets.computeIfAbsent(
                transaction.getCorrelationId(),
                ignored -> new AsyncLifecycleBucket()
        );
        List<IndexedFlow> shareInflows = new ArrayList<>();

        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);

            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFee(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }

            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() <= 0) {
                applyUnknownTransfer(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                );
                continue;
            }

            if (isGmxShareLikeSymbol(flow.getAssetSymbol())) {
                shareInflows.add(indexedFlow);
                continue;
            }

            CarryTransfer refundCarry = bucket.takeExecutionFeeReserve(
                    assetKey.assetIdentity(),
                    flow.getQuantityDelta().abs(),
                    position.assetKey
            );
            if (refundCarry != null) {
                restoreToPosition(
                        refundCarry.quantity(),
                        position,
                        refundCarry.costBasisUsd(),
                        refundCarry.uncoveredQuantity(),
                        refundCarry.avco()
                );
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(refundCarry.quantity(), MC);
                if (residualQuantity.signum() > 0) {
                    PositionSnapshot residualBefore = snapshot(position);
                    applyUnknownTransfer(copyFlowWithQuantity(flow, residualQuantity), position);
                    ledgerPointCollector.record(
                            transaction,
                            copyFlowWithQuantity(flow, residualQuantity),
                            indexedFlow.index(),
                            position.assetKey,
                            residualBefore,
                            position,
                            AssetLedgerPoint.BasisEffect.UNKNOWN
                    );
                }
                continue;
            }

            CarryTransfer principalCarry = bucket.takeSameAssetCarry(
                    assetKey.assetIdentity(),
                    flow.getQuantityDelta().abs(),
                    position.assetKey
            );
            if (principalCarry != null) {
                restoreToPosition(
                        principalCarry.quantity(),
                        position,
                        principalCarry.costBasisUsd(),
                        principalCarry.uncoveredQuantity(),
                        principalCarry.avco()
                );
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(principalCarry.quantity(), MC);
                if (residualQuantity.signum() > 0) {
                    PositionSnapshot residualBefore = snapshot(position);
                    applyUnknownTransfer(copyFlowWithQuantity(flow, residualQuantity), position);
                    ledgerPointCollector.record(
                            transaction,
                            copyFlowWithQuantity(flow, residualQuantity),
                            indexedFlow.index(),
                            position.assetKey,
                            residualBefore,
                            position,
                            AssetLedgerPoint.BasisEffect.UNKNOWN
                    );
                }
                continue;
            }

            applyUnknownTransfer(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }

        if (!shareInflows.isEmpty()) {
            BigDecimal remainingPrincipalCostBasis = bucket.remainingCostBasisUsd();
            BigDecimal remainingExecutionFeeCostBasis = bucket.remainingExecutionFeeReserveCostBasisUsd();
            BigDecimal totalAllocatedCostBasis = remainingPrincipalCostBasis.add(remainingExecutionFeeCostBasis, MC);
            if (remainingPrincipalCostBasis.signum() > 0 && bucket.remainingUncoveredQuantity().signum() <= 0) {
                if (shareInflows.size() == 1) {
                    restoreSettlementPosition(transaction, shareInflows.getFirst(), positions, totalAllocatedCostBasis, ledgerPointCollector);
                } else if (allHaveKnownPrices(shareInflows.stream().map(IndexedFlow::flow).toList())) {
                    allocateIndexedSettlementByKnownValue(
                            transaction,
                            shareInflows,
                            positions,
                            totalAllocatedCostBasis,
                            ledgerPointCollector
                    );
                } else if (allSameAsset(shareInflows.stream().map(IndexedFlow::flow).toList(), transaction)) {
                    allocateIndexedSettlementByQuantity(
                            transaction,
                            shareInflows,
                            positions,
                            totalAllocatedCostBasis,
                            ledgerPointCollector
                    );
                } else {
                    applyUnknownGmxShareInflows(transaction, shareInflows, positions, ledgerPointCollector);
                }
            } else {
                applyUnknownGmxShareInflows(transaction, shareInflows, positions, ledgerPointCollector);
            }
        }

        bucket.clearAll();
        asyncLifecycleBuckets.remove(transaction.getCorrelationId());
    }

    private void applyUnknownGmxShareInflows(
            NormalizedTransaction transaction,
            List<IndexedFlow> shareInflows,
            Map<AssetKey, PositionState> positions,
            LedgerPointCollector ledgerPointCollector
    ) {
        for (IndexedFlow indexedFlow : shareInflows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);
            applyUnknownTransfer(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }
    }

    private boolean isGmxExecutionFeeReserveFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
            return false;
        }
        if (flow.getRole() != NormalizedLegRole.TRANSFER) {
            return false;
        }
        String assetIdentity = assetKey(transaction, flow).assetIdentity();
        if (assetIdentity == null || !assetIdentity.startsWith("NATIVE:")) {
            return false;
        }
        return transaction.getFlows().stream()
                .filter(candidate -> candidate != null
                        && candidate.getRole() == NormalizedLegRole.TRANSFER
                        && candidate.getQuantityDelta() != null
                        && candidate.getQuantityDelta().signum() < 0)
                .map(candidate -> assetKey(transaction, candidate).assetIdentity())
                .filter(Objects::nonNull)
                .anyMatch(candidateIdentity -> !candidateIdentity.equals(assetIdentity));
    }

    private boolean isGmxShareLikeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return symbol.regionMatches(true, 0, "GM:", 0, 3)
                || symbol.regionMatches(true, 0, "GLV", 0, 3);
    }

    private void applyAsyncLpExitSettlement(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        if (transaction.getCorrelationId() == null || transaction.getCorrelationId().isBlank()) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                applyFallbackSettlementFlow(transaction, flow, positions, ledgerPointCollector);
            }
            return;
        }

        AsyncLifecycleBucket bucket = asyncLifecycleBuckets.computeIfAbsent(
                transaction.getCorrelationId(),
                ignored -> new AsyncLifecycleBucket()
        );

        List<IndexedFlow> principalInflows = new ArrayList<>();
        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);

            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFee(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        flowIndex(transaction, flow),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.TRANSFER && flow.getQuantityDelta().signum() > 0) {
                CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(
                        assetKey.assetIdentity(),
                        flow.getQuantityDelta().abs(),
                        position.assetKey
                );
                if (sameAssetCarry != null) {
                    restoreToPosition(
                            sameAssetCarry.quantity(),
                        position,
                        sameAssetCarry.costBasisUsd(),
                        sameAssetCarry.uncoveredQuantity(),
                        sameAssetCarry.avco()
                    );
                    ledgerPointCollector.record(
                            transaction,
                            flow,
                            indexedFlow.index(),
                            position.assetKey,
                            before,
                            position,
                            AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                    );
                    BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(sameAssetCarry.quantity(), MC);
                    if (residualQuantity.signum() > 0) {
                        principalInflows.add(new IndexedFlow(
                                indexedFlow.index(),
                                copyFlowWithQuantity(flow, residualQuantity)
                        ));
                    }
                } else {
                    principalInflows.add(indexedFlow);
                }
                continue;
            }
            applyUnknownTransfer(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }

        if (principalInflows.isEmpty()) {
            if (bucket.isEmpty()) {
                asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            }
            return;
        }

        BigDecimal remainingCostBasis = bucket.remainingCostBasisUsd();
        if (remainingCostBasis.signum() <= 0) {
            for (IndexedFlow indexedFlow : principalInflows) {
                NormalizedTransaction.Flow flow = indexedFlow.flow();
                AssetKey assetKey = assetKey(transaction, flow);
                PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
                PositionSnapshot before = snapshot(position);
                applyUnknownTransfer(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                    );
            }
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }
        if (bucket.remainingUncoveredQuantity().signum() > 0) {
            for (IndexedFlow indexedFlow : principalInflows) {
                NormalizedTransaction.Flow flow = indexedFlow.flow();
                AssetKey assetKey = assetKey(transaction, flow);
                PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
                PositionSnapshot before = snapshot(position);
                applyUnknownTransfer(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                );
            }
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        if (allSameAsset(principalInflows.stream().map(IndexedFlow::flow).toList(), transaction)) {
            allocateIndexedSettlementByQuantity(transaction, principalInflows, positions, remainingCostBasis, ledgerPointCollector);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        if (allHaveKnownPrices(principalInflows.stream().map(IndexedFlow::flow).toList())) {
            allocateIndexedSettlementByKnownValue(transaction, principalInflows, positions, remainingCostBasis, ledgerPointCollector);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        for (IndexedFlow indexedFlow : principalInflows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            PositionSnapshot before = snapshot(position);
            applyUnknownTransfer(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }
        bucket.clearAll();
        asyncLifecycleBuckets.remove(transaction.getCorrelationId());
    }

    private void applyPositionScopedLpExit(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            LedgerPointCollector ledgerPointCollector
    ) {
        AsyncLifecycleBucket bucket = asyncLifecycleBuckets.computeIfAbsent(
                transaction.getCorrelationId(),
                ignored -> new AsyncLifecycleBucket()
        );
        List<IndexedFlow> residualPrincipalInflows = new ArrayList<>();
        List<IndexedFlow> deferredCrossAssetInflows = new ArrayList<>();
        Set<String> eligibleIdentities = bucket.knownAssetIdentities();
        boolean touchedEligiblePrincipal = false;

        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (shouldIgnoreLpReceiptMarker(transaction, flow)) {
                continue;
            }

            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot before = snapshot(position);

            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFee(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() < 0) {
                applyUnknownTransfer(flow, position);
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                );
                continue;
            }

            String bucketIdentity = continuityIdentity(transaction, flow);
            if (!eligibleIdentities.contains(bucketIdentity)) {
                deferredCrossAssetInflows.add(indexedFlow);
                continue;
            }

            touchedEligiblePrincipal = true;
            CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(bucketIdentity, flow.getQuantityDelta().abs(), position.assetKey);
            if (sameAssetCarry != null) {
                restoreToPosition(
                        sameAssetCarry.quantity(),
                        position,
                        sameAssetCarry.costBasisUsd(),
                        sameAssetCarry.uncoveredQuantity(),
                        sameAssetCarry.avco()
                );
                ledgerPointCollector.record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey,
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(sameAssetCarry.quantity(), MC);
                if (residualQuantity.signum() > 0) {
                    residualPrincipalInflows.add(new IndexedFlow(
                            indexedFlow.index(),
                            copyFlowWithQuantity(flow, residualQuantity)
                    ));
                }
                continue;
            }

            residualPrincipalInflows.add(indexedFlow);
        }

        if (residualPrincipalInflows.isEmpty() && deferredCrossAssetInflows.isEmpty()) {
            if (bucket.isEmpty()) {
                asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            }
            return;
        }

        if (!touchedEligiblePrincipal && !deferredCrossAssetInflows.isEmpty()) {
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, deferredCrossAssetInflows);
            if (bucket.isEmpty()) {
                asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            }
            return;
        }

        List<IndexedFlow> allocatableInflows = residualPrincipalInflows.isEmpty()
                ? deferredCrossAssetInflows
                : residualPrincipalInflows;
        List<IndexedFlow> unknownOnlyInflows = residualPrincipalInflows.isEmpty()
                ? List.of()
                : deferredCrossAssetInflows;

        BigDecimal remainingCostBasis = bucket.remainingCostBasisUsd();
        if (remainingCostBasis.signum() <= 0) {
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, allocatableInflows);
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, unknownOnlyInflows);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        List<NormalizedTransaction.Flow> residualFlows = allocatableInflows.stream()
                .map(IndexedFlow::flow)
                .toList();
        if (allSameAsset(residualFlows, transaction)) {
            allocateIndexedSettlementByQuantity(transaction, allocatableInflows, positions, remainingCostBasis, ledgerPointCollector);
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, unknownOnlyInflows);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }
        if (allHaveKnownReplayPrices(transaction, allocatableInflows)) {
            allocateIndexedSettlementByReplayKnownValue(
                    transaction,
                    allocatableInflows,
                    positions,
                    remainingCostBasis,
                    ledgerPointCollector
            );
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, unknownOnlyInflows);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }
        if (bucket.remainingUncoveredQuantity().signum() > 0) {
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, allocatableInflows);
            recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, unknownOnlyInflows);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, allocatableInflows);
        recordUnknownLpExitInflows(transaction, positions, ledgerPointCollector, unknownOnlyInflows);
        bucket.clearAll();
        asyncLifecycleBuckets.remove(transaction.getCorrelationId());
    }

    private void recordUnknownLpExitInflows(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            LedgerPointCollector ledgerPointCollector,
            List<IndexedFlow> flows
    ) {
        for (IndexedFlow indexedFlow : flows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            PositionSnapshot before = snapshot(position);
            applyUnknownTransfer(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }
    }

    private boolean isAsyncSpotOrderRequestSell(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.SELL
                && flow.getQuantityDelta().signum() < 0
                && transaction.getType() == NormalizedTransactionType.DEX_ORDER_REQUEST
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    private boolean isAsyncSpotOrderSettlementBuy(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.BUY
                && flow.getQuantityDelta().signum() > 0
                && transaction.getType() == NormalizedTransactionType.DEX_ORDER_SETTLEMENT
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    private NormalizedTransaction.Flow asTransferFlow(NormalizedTransaction.Flow original) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetContract(original.getAssetContract());
        flow.setAssetSymbol(original.getAssetSymbol());
        flow.setQuantityDelta(original.getQuantityDelta());
        flow.setUnitPriceUsd(original.getUnitPriceUsd());
        flow.setValueUsd(original.getValueUsd());
        flow.setPriceSource(original.getPriceSource());
        flow.setIsInferred(original.getIsInferred());
        flow.setInferenceReason(original.getInferenceReason());
        flow.setConfidence(original.getConfidence());
        flow.setLogIndex(original.getLogIndex());
        return flow;
    }

    private void applyAsyncLifecycleRequest(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets
    ) {
        applyAsyncLifecycleRequest(transaction, flow, position, asyncLifecycleBuckets, assetKey(transaction, flow).assetIdentity());
    }

    private void applyAsyncLifecycleRequest(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            String bucketIdentity
    ) {
        CarryTransfer carry = removeFromPosition(flow, position);
        asyncLifecycleBuckets
                .computeIfAbsent(transaction.getCorrelationId(), ignored -> new AsyncLifecycleBucket())
                .add(bucketIdentity, carry);
    }

    private void applyAsyncLifecycleSettlement(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets
    ) {
        AsyncLifecycleBucket bucket = asyncLifecycleBuckets.computeIfAbsent(
                transaction.getCorrelationId(),
                ignored -> new AsyncLifecycleBucket()
        );
        String assetIdentity = assetKey(transaction, flow).assetIdentity();
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(assetIdentity, requestedQuantity, position.assetKey);
        if (sameAssetCarry != null) {
            restoreToPosition(
                    sameAssetCarry.quantity(),
                    position,
                    sameAssetCarry.costBasisUsd(),
                    sameAssetCarry.uncoveredQuantity(),
                    sameAssetCarry.avco()
            );
            BigDecimal residualQuantity = requestedQuantity.subtract(sameAssetCarry.quantity(), MC);
            if (residualQuantity.signum() <= 0) {
                if (bucket.isEmpty()) {
                    asyncLifecycleBuckets.remove(transaction.getCorrelationId());
                }
                return;
            }
            requestedQuantity = residualQuantity;
        }

        BigDecimal remainingCost = bucket.remainingCostBasisUsd();
        if (remainingCost.signum() <= 0) {
            applyUnknownTransfer(copyFlowWithQuantity(flow, requestedQuantity), position);
            return;
        }
        if (bucket.remainingUncoveredQuantity().signum() > 0) {
            applyUnknownTransfer(copyFlowWithQuantity(flow, requestedQuantity), position);
            return;
        }
        BigDecimal avco = safeDivide(remainingCost, requestedQuantity);
        bucket.clearAll();
        restoreToPosition(requestedQuantity, position, remainingCost, BigDecimal.ZERO, avco);
        asyncLifecycleBuckets.remove(transaction.getCorrelationId());
    }

    private void applyAsyncSpotOrderRequest(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets
    ) {
        CarryTransfer carry = removeFromPosition(flow, position);
        asyncSpotOrderBuckets
                .computeIfAbsent(transaction.getCorrelationId(), ignored -> new AsyncSpotOrderBucket())
                .add(carry, flow);
    }

    private void applyAsyncSpotOrderSettlement(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets
    ) {
        AsyncSpotOrderBucket bucket = asyncSpotOrderBuckets.get(transaction.getCorrelationId());
        if (bucket == null || bucket.isEmpty() || !hasKnownPrice(flow)) {
            applyBuy(flow, position);
            return;
        }

        BigDecimal totalSourceCost = bucket.totalCostBasisUsd();
        BigDecimal totalSourceQuantity = bucket.totalQuantity();
        BigDecimal settlementQuantity = flow.getQuantityDelta().abs();
        BigDecimal settlementValueUsd = settlementQuantity.multiply(flow.getUnitPriceUsd(), MC);
        restoreToPosition(flow, position, flow.getUnitPriceUsd(), settlementValueUsd);

        BigDecimal remainingProceeds = settlementValueUsd;
        BigDecimal remainingQuantity = totalSourceQuantity;
        List<AsyncSpotOrderCarry> carries = bucket.drainAll();
        for (int index = 0; index < carries.size(); index++) {
            AsyncSpotOrderCarry entry = carries.get(index);
            NormalizedTransaction.Flow requestFlow = entry.requestFlow();
            CarryTransfer carry = entry.carry();
            if (requestFlow == null || carry == null) {
                continue;
            }
            BigDecimal allocatedProceeds;
            if (index == carries.size() - 1 || remainingQuantity == null || remainingQuantity.signum() <= 0) {
                allocatedProceeds = remainingProceeds;
            } else {
                BigDecimal ratio = safeDivide(carry.quantity(), remainingQuantity);
                allocatedProceeds = ratio == null
                        ? BigDecimal.ZERO
                        : settlementValueUsd.multiply(ratio, MC);
                remainingProceeds = remainingProceeds.subtract(allocatedProceeds, MC);
                remainingQuantity = remainingQuantity.subtract(carry.quantity(), MC);
            }
            if (carry.avco() != null) {
                requestFlow.setAvcoAtTimeOfSale(carry.avco());
                requestFlow.setRealisedPnlUsd(allocatedProceeds.subtract(carry.costBasisUsd(), MC));
            } else {
                requestFlow.setAvcoAtTimeOfSale(null);
                requestFlow.setRealisedPnlUsd(null);
            }
        }
        asyncSpotOrderBuckets.remove(transaction.getCorrelationId());
    }

    private void applyEulerLoopRebalance(
            NormalizedTransaction transaction,
            Map<AssetKey, PositionState> positions,
            LedgerPointCollector ledgerPointCollector
    ) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return;
        }

        NormalizedTransaction.Flow sourceFlow = null;
        NormalizedTransaction.Flow replacementFlow = null;
        List<NormalizedTransaction.Flow> sameAssetRefunds = new ArrayList<>();
        List<NormalizedTransaction.Flow> fees = new ArrayList<>();

        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                fees.add(flow);
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                applyEulerRebalanceFlowAsUnknown(transaction, flow, positions, ledgerPointCollector);
                continue;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                sourceFlow = flow;
                continue;
            }
            if (sourceFlow != null && sameAssetIdentity(transaction, sourceFlow, flow)) {
                sameAssetRefunds.add(flow);
                continue;
            }
            replacementFlow = flow;
        }

        if (sourceFlow == null || replacementFlow == null) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                applyEulerRebalanceFlowAsUnknown(transaction, flow, positions, ledgerPointCollector);
            }
            return;
        }

        AssetKey sourceAssetKey = assetKey(transaction, sourceFlow);
        PositionState sourcePosition = positions.computeIfAbsent(sourceAssetKey, ignored -> new PositionState(sourceAssetKey));
        sourcePosition.lastEventTimestamp = laterOf(sourcePosition.lastEventTimestamp, transaction.getBlockTimestamp());
        PositionSnapshot sourceBefore = snapshot(sourcePosition);
        CarryTransfer carry = removeFromPosition(sourceFlow, sourcePosition);
        ledgerPointCollector.record(
                transaction,
                sourceFlow,
                flowIndex(transaction, sourceFlow),
                sourcePosition.assetKey,
                sourceBefore,
                sourcePosition,
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
        );

        CarryTransfer remainingCarry = carry;
        for (NormalizedTransaction.Flow refund : sameAssetRefunds) {
            sourcePosition.lastEventTimestamp = laterOf(sourcePosition.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot refundBefore = snapshot(sourcePosition);
            remainingCarry = restoreEulerRebalanceRefund(refund, sourcePosition, remainingCarry);
            ledgerPointCollector.record(
                    transaction,
                    refund,
                    flowIndex(transaction, refund),
                    sourcePosition.assetKey,
                    refundBefore,
                    sourcePosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
        }

        AssetKey replacementAssetKey = assetKey(transaction, replacementFlow);
        PositionState replacementPosition = positions.computeIfAbsent(replacementAssetKey, ignored -> new PositionState(replacementAssetKey));
        replacementPosition.lastEventTimestamp = laterOf(replacementPosition.lastEventTimestamp, transaction.getBlockTimestamp());
        PositionSnapshot replacementBefore = snapshot(replacementPosition);
        restoreEulerRebalanceReplacement(replacementFlow, replacementPosition, remainingCarry);
        ledgerPointCollector.record(
                transaction,
                replacementFlow,
                flowIndex(transaction, replacementFlow),
                replacementPosition.assetKey,
                replacementBefore,
                replacementPosition,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );

        for (NormalizedTransaction.Flow fee : fees) {
            AssetKey feeAssetKey = assetKey(transaction, fee);
            PositionState feePosition = positions.computeIfAbsent(feeAssetKey, ignored -> new PositionState(feeAssetKey));
            feePosition.lastEventTimestamp = laterOf(feePosition.lastEventTimestamp, transaction.getBlockTimestamp());
            PositionSnapshot feeBefore = snapshot(feePosition);
            applyFee(fee, feePosition);
            ledgerPointCollector.record(
                    transaction,
                    fee,
                    flowIndex(transaction, fee),
                    feePosition.assetKey,
                    feeBefore,
                    feePosition,
                    AssetLedgerPoint.BasisEffect.GAS_ONLY
            );
        }
    }

    private void applyEulerRebalanceFlowAsUnknown(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions,
            LedgerPointCollector ledgerPointCollector
    ) {
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
        PositionSnapshot before = snapshot(position);
        if (flow.getRole() == NormalizedLegRole.FEE) {
            applyFee(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex(transaction, flow),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.GAS_ONLY
            );
            return;
        }
        applyUnknownTransfer(flow, position);
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex(transaction, flow),
                position.assetKey,
                before,
                position,
                AssetLedgerPoint.BasisEffect.UNKNOWN
        );
    }

    private CarryTransfer restoreEulerRebalanceRefund(
            NormalizedTransaction.Flow refundFlow,
            PositionState position,
            CarryTransfer carry
    ) {
        if (carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            applyUnknownTransfer(refundFlow, position);
            return carry;
        }
        BigDecimal refundQuantity = refundFlow.getQuantityDelta().abs();
        BigDecimal effectiveQuantity = refundQuantity.min(carry.quantity());
        if (effectiveQuantity.signum() <= 0) {
            return carry;
        }
        BigDecimal coveredRefundQuantity = effectiveQuantity.min(carry.coveredQuantity());
        BigDecimal uncoveredRefundQuantity = nonNegative(effectiveQuantity.subtract(coveredRefundQuantity, MC));
        BigDecimal avco = carry.avco();
        BigDecimal refundCost = avco == null ? BigDecimal.ZERO : coveredRefundQuantity.multiply(avco, MC);
        restoreToPosition(effectiveQuantity, position, refundCost, uncoveredRefundQuantity, avco);
        return new CarryTransfer(
                nonNegative(carry.quantity().subtract(effectiveQuantity, MC)),
                nonNegative(carry.coveredQuantity().subtract(coveredRefundQuantity, MC)),
                nonNegative(carry.uncoveredQuantity().subtract(uncoveredRefundQuantity, MC)),
                nonNegative(carry.costBasisUsd().subtract(refundCost, MC)),
                avco,
                false,
                carry.assetKey()
        );
    }

    private void restoreEulerRebalanceReplacement(
            NormalizedTransaction.Flow replacementFlow,
            PositionState position,
            CarryTransfer carry
    ) {
        if (carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            applyUnknownTransfer(replacementFlow, position);
            return;
        }
        BigDecimal quantity = replacementFlow.getQuantityDelta().abs();
        BigDecimal coveredQuantity = quantity.min(carry.coveredQuantity());
        BigDecimal uncoveredQuantity = nonNegative(quantity.subtract(coveredQuantity, MC));
        BigDecimal avco = coveredQuantity.signum() == 0
                ? null
                : safeDivide(carry.costBasisUsd(), coveredQuantity);
        restoreToPosition(quantity, position, carry.costBasisUsd(), uncoveredQuantity, avco);
    }

    private void applyBuy(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (hasKnownPrice(flow)) {
            BigDecimal acquisitionCost = quantity.multiply(flow.getUnitPriceUsd(), MC);
            position.totalCostBasisUsd = position.totalCostBasisUsd.add(acquisitionCost);
            position.quantity = position.quantity.add(quantity);
            recomputePerWalletAvco(position);
            return;
        }
        position.quantity = position.quantity.add(quantity);
        position.uncoveredQuantity = position.uncoveredQuantity.add(quantity);
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    private void applySell(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfSale = position.perWalletAvco;
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal soldCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (avcoAtTimeOfSale != null && soldCoveredQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(avcoAtTimeOfSale);
            BigDecimal relievedCost = soldCoveredQuantity.multiply(avcoAtTimeOfSale, MC);
            position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(relievedCost, MC));
            if (hasKnownPrice(flow)
                    && consumption.externalShortfallQuantity().signum() == 0
                    && consumption.uncoveredQuantity().signum() == 0) {
                BigDecimal realised = flow.getUnitPriceUsd().subtract(avcoAtTimeOfSale, MC).multiply(soldCoveredQuantity, MC);
                flow.setRealisedPnlUsd(realised);
                position.totalRealisedPnlUsd = position.totalRealisedPnlUsd.add(realised);
            } else {
                flow.setAvcoAtTimeOfSale(null);
                flow.setRealisedPnlUsd(null);
                markUnresolved(position);
            }
        } else if (requestedQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);
            markUnresolved(position);
        }
        recomputePerWalletAvco(position);
    }

    private void applyFee(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfCharge = position.perWalletAvco;
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal chargedCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (hasKnownPrice(flow)) {
            BigDecimal feeCost = requestedQuantity.multiply(flow.getUnitPriceUsd(), MC);
            position.totalGasPaidUsd = position.totalGasPaidUsd.add(feeCost);
            if (avcoAtTimeOfCharge != null && chargedCoveredQuantity.signum() > 0) {
                position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(
                        chargedCoveredQuantity.multiply(avcoAtTimeOfCharge, MC),
                        MC
                ));
            }
        } else {
            markUnresolved(position);
        }
        if (consumption.uncoveredQuantity().signum() > 0 || consumption.externalShortfallQuantity().signum() > 0) {
            markUnresolved(position);
        }
        recomputePerWalletAvco(position);
    }

    private AssetLedgerPoint.BasisEffect applyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            Map<AssetKey, PositionState> positions,
            Map<ContinuityKey, ContinuityBucket> continuityBuckets,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries,
            LedgerPointCollector ledgerPointCollector
    ) {
        if (isLinkedBridgeContinuityTransfer(transaction, flow)) {
            return applyLinkedBridgeTransfer(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    positions,
                    pendingTransfers,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries,
                    ledgerPointCollector
            );
        }
        if (isLinkedBridgeSettlementTransfer(transaction, flow)) {
            return applyLinkedBridgeSettlementTransfer(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    positions,
                    pendingTransfers,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries,
                    ledgerPointCollector
            );
        }
        if (isFamilyEquivalentCustodyTransfer(transaction, flow)) {
            ContinuityBucket bucket = continuityBuckets.computeIfAbsent(
                    continuityKey(transaction, flow),
                    ignored -> new ContinuityBucket()
            );
            if (flow.getQuantityDelta().signum() < 0) {
                moveToContinuityBucket(
                        removeTransferCarry(
                                transaction,
                                flow,
                                flowIndex,
                                position,
                                passThroughCorridorPlan,
                                reservedPassThroughCarries
                        ),
                        bucket
                );
                return AssetLedgerPoint.BasisEffect.REALLOCATE_OUT;
            }
            restoreFromContinuityBucket(flow, position, bucket);
            return AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
        }
        if (isBucketOutbound(transaction, flow)) {
            moveToContinuityBucket(
                    removeTransferCarry(
                            transaction,
                            flow,
                            flowIndex,
                            position,
                            passThroughCorridorPlan,
                            reservedPassThroughCarries
                    ),
                    continuityBuckets.computeIfAbsent(continuityKey(transaction, flow), ignored -> new ContinuityBucket())
            );
            return AssetLedgerPoint.BasisEffect.REALLOCATE_OUT;
        }
        if (isBucketInbound(transaction, flow)) {
            restoreFromContinuityBucket(
                    flow,
                    position,
                    continuityBuckets.computeIfAbsent(continuityKey(transaction, flow), ignored -> new ContinuityBucket())
            );
            return AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
        }

        String transferKey = transferKey(transaction, flow);
        if (transferKey == null) {
            applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries
            );
            Deque<CarryTransfer> queue = pendingTransfers.computeIfAbsent(transferKey, ignored -> new ArrayDeque<>());
            int pendingInboundIndex = findUniqueCompatibleQueueIndex(queue, true, carry.quantity());
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = removeQueueElement(queue, pendingInboundIndex);
                attachLateCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        positions,
                        pendingInbound,
                        carry,
                        ledgerPointCollector
                );
                if (queue.isEmpty()) {
                    pendingTransfers.remove(transferKey);
                }
                return continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = pendingTransfers.get(transferKey);
        int carryIndex = findUniqueCompatibleQueueIndex(queue, false, flow.getQuantityDelta().abs());
        if (carryIndex >= 0) {
            CarryTransfer carry = removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = sliceCarryTransfer(carry, flow.getQuantityDelta().abs(), position.assetKey);
            restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries
            );
            if (queue.isEmpty()) {
                pendingTransfers.remove(transferKey);
            }
            return continuityBasisEffect(transaction, flow);
        }

        materializePendingInbound(flow, position);
        pendingTransfers.computeIfAbsent(transferKey, ignored -> new ArrayDeque<>())
                .addLast(CarryTransfer.pendingInbound(flow.getQuantityDelta().abs(), position.assetKey));
        return continuityBasisEffect(transaction, flow);
    }

    private AssetLedgerPoint.BasisEffect applyLinkedBridgeTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            Map<AssetKey, PositionState> positions,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries,
            LedgerPointCollector ledgerPointCollector
    ) {
        String bridgeTransferKey = bridgeTransferKey(transaction, flow);
        if (bridgeTransferKey == null) {
            applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries
            );
            Deque<CarryTransfer> queue = pendingTransfers.computeIfAbsent(bridgeTransferKey, ignored -> new ArrayDeque<>());
            int pendingInboundIndex = findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = removeQueueElement(queue, pendingInboundIndex);
                attachLateBridgeCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        positions,
                        pendingInbound,
                        carry,
                        ledgerPointCollector
                );
                if (queue.isEmpty()) {
                    pendingTransfers.remove(bridgeTransferKey);
                }
                return continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = pendingTransfers.get(bridgeTransferKey);
        int carryIndex = findUniqueBridgeQueueIndex(queue, false);
        if (carryIndex >= 0) {
            CarryTransfer carry = removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = bridgeInboundCarry(carry, flow.getQuantityDelta().abs(), position.assetKey);
            restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries
            );
            if (queue.isEmpty()) {
                pendingTransfers.remove(bridgeTransferKey);
            }
            return continuityBasisEffect(transaction, flow);
        }

        materializePendingInbound(flow, position);
        pendingTransfers.computeIfAbsent(bridgeTransferKey, ignored -> new ArrayDeque<>())
                .addLast(CarryTransfer.pendingInbound(flow.getQuantityDelta().abs(), position.assetKey));
        return continuityBasisEffect(transaction, flow);
    }

    private AssetLedgerPoint.BasisEffect applyLinkedBridgeSettlementTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            Map<AssetKey, PositionState> positions,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries,
            LedgerPointCollector ledgerPointCollector
    ) {
        String settlementKey = bridgeSettlementKey(transaction, flow);
        if (settlementKey == null) {
            applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    passThroughCorridorPlan,
                    reservedPassThroughCarries
            );
            Deque<CarryTransfer> queue = pendingTransfers.computeIfAbsent(settlementKey, ignored -> new ArrayDeque<>());
            int pendingInboundIndex = findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = removeQueueElement(queue, pendingInboundIndex);
                attachLateBridgeSettlementCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        positions,
                        pendingInbound,
                        carry,
                        ledgerPointCollector
                );
                if (queue.isEmpty()) {
                    pendingTransfers.remove(settlementKey);
                }
                return routeSettlementBasisEffect(flow);
            }
            queue.addLast(carry);
            return routeSettlementBasisEffect(flow);
        }

        Deque<CarryTransfer> queue = pendingTransfers.get(settlementKey);
        int carryIndex = findUniqueBridgeQueueIndex(queue, false);
        if (carryIndex >= 0) {
            CarryTransfer carry = removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = bridgeSettlementInboundCarry(carry, flow.getQuantityDelta().abs(), position.assetKey);
            restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            if (queue.isEmpty()) {
                pendingTransfers.remove(settlementKey);
            }
            return routeSettlementBasisEffect(flow);
        }

        materializePendingInbound(flow, position);
        pendingTransfers.computeIfAbsent(settlementKey, ignored -> new ArrayDeque<>())
                .addLast(CarryTransfer.pendingInbound(flow.getQuantityDelta().abs(), position.assetKey));
        return routeSettlementBasisEffect(flow);
    }

    private void moveToContinuityBucket(
            CarryTransfer carry,
            ContinuityBucket bucket
    ) {
        bucket.add(carry);
    }

    private void restoreFromContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (bucket.quantity.signum() == 0) {
            applyUnknownTransfer(flow, position);
            return;
        }
        CarryTransfer carry = bucket.take(quantity, position.assetKey);
        restoreToPosition(
                quantity,
                position,
                carry.costBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    private CarryTransfer removeFromPosition(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avco = position.perWalletAvco;
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal cost = avco == null
                ? BigDecimal.ZERO
                : consumption.coveredQuantity().multiply(avco, MC);
        position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(cost, MC));
        recomputePerWalletAvco(position);
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (avco == null && consumption.coveredQuantity().signum() > 0) {
            markUnresolved(position);
        }
        return new CarryTransfer(
                requestedQuantity,
                consumption.coveredQuantity(),
                requestedQuantity.subtract(consumption.coveredQuantity(), MC),
                cost,
                avco,
                false,
                position.assetKey
        );
    }

    private CarryTransfer removeTransferCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries
    ) {
        String flowRef = flowRef(transaction, flowIndex);
        CarryTransfer reservedCarry = reservedPassThroughCarries.remove(flowRef);
        if (reservedCarry == null) {
            return removeFromPosition(flow, position);
        }
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        CarryTransfer reservedPortion = sliceCarryTransfer(reservedCarry, requestedQuantity, position.assetKey);
        consumeReservedCarry(position, reservedPortion);
        BigDecimal remainingQuantity = nonNegative(requestedQuantity.subtract(reservedPortion.quantity(), MC));
        if (remainingQuantity.signum() == 0) {
            return reservedPortion;
        }
        CarryTransfer pooledRemainder = removeFromPosition(copyFlowWithQuantity(flow, flow.getQuantityDelta().signum() < 0
                ? remainingQuantity.negate()
                : remainingQuantity), position);
        return mergeCarryTransfers(position.assetKey, reservedPortion, pooledRemainder);
    }

    private void reservePassThroughCarryIfPlanned(
            NormalizedTransaction transaction,
            int flowIndex,
            CarryTransfer carry,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<String, CarryTransfer> reservedPassThroughCarries
    ) {
        PassThroughCorridor corridor = passThroughCorridorPlan.byInboundFlowRef().get(flowRef(transaction, flowIndex));
        if (corridor == null || carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            return;
        }
        reservedPassThroughCarries.put(
                corridor.outboundFlowRef(),
                sliceCarryTransfer(carry, corridor.reservedQuantity(), carry.assetKey())
        );
    }

    private CarryTransfer sliceCarryTransfer(
            CarryTransfer carry,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (carry == null || requestedQuantity == null || requestedQuantity.signum() <= 0) {
            return new CarryTransfer(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, false, assetKey);
        }
        BigDecimal effectiveQuantity = requestedQuantity.min(carry.quantity());
        BigDecimal effectiveCoveredQuantity = effectiveQuantity.min(carry.coveredQuantity());
        BigDecimal effectiveUncoveredQuantity = nonNegative(effectiveQuantity.subtract(effectiveCoveredQuantity, MC));
        BigDecimal effectiveAvco = carry.avco();
        BigDecimal effectiveCost = effectiveAvco == null
                ? BigDecimal.ZERO
                : effectiveCoveredQuantity.multiply(effectiveAvco, MC);
        return new CarryTransfer(
                effectiveQuantity,
                effectiveCoveredQuantity,
                effectiveUncoveredQuantity,
                effectiveCost,
                effectiveAvco,
                false,
                assetKey
        );
    }

    private CarryTransfer bridgeInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return new CarryTransfer(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, false, assetKey);
        }
        BigDecimal coveredQuantity = destinationQuantity.min(sourceCarry.coveredQuantity());
        BigDecimal uncoveredQuantity = nonNegative(destinationQuantity.subtract(coveredQuantity, MC));
        BigDecimal costBasisUsd = sourceCarry.costBasisUsd();
        BigDecimal avco = coveredQuantity.signum() <= 0
                ? null
                : safeDivide(costBasisUsd, coveredQuantity);
        return new CarryTransfer(
                destinationQuantity,
                coveredQuantity,
                uncoveredQuantity,
                costBasisUsd,
                avco,
                false,
                assetKey
        );
    }

    private CarryTransfer bridgeSettlementInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return new CarryTransfer(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, false, assetKey);
        }
        BigDecimal sourceQuantity = sourceCarry.quantity();
        if (sourceQuantity == null || sourceQuantity.signum() <= 0) {
            return new CarryTransfer(
                    destinationQuantity,
                    BigDecimal.ZERO,
                    destinationQuantity,
                    BigDecimal.ZERO,
                    null,
                    false,
                    assetKey
            );
        }
        BigDecimal sourceCoveredQuantity = sourceCarry.coveredQuantity().min(sourceQuantity);
        BigDecimal coverageRatio = safeDivide(sourceCoveredQuantity, sourceQuantity);
        BigDecimal coveredQuantity = coverageRatio == null
                ? BigDecimal.ZERO
                : destinationQuantity.multiply(coverageRatio, MC).min(destinationQuantity);
        BigDecimal uncoveredQuantity = nonNegative(destinationQuantity.subtract(coveredQuantity, MC));
        BigDecimal costBasisUsd = sourceCarry.costBasisUsd();
        BigDecimal avco = coveredQuantity.signum() <= 0
                ? null
                : safeDivide(costBasisUsd, coveredQuantity);
        return new CarryTransfer(
                destinationQuantity,
                coveredQuantity,
                uncoveredQuantity,
                costBasisUsd,
                avco,
                false,
                assetKey
        );
    }

    private void consumeReservedCarry(PositionState position, CarryTransfer carry) {
        if (carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            return;
        }
        position.quantity = nonNegative(position.quantity.subtract(carry.quantity(), MC));
        position.uncoveredQuantity = nonNegative(position.uncoveredQuantity.subtract(carry.uncoveredQuantity(), MC));
        position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(carry.costBasisUsd(), MC));
        recomputePerWalletAvco(position);
    }

    private CarryTransfer mergeCarryTransfers(
            AssetKey assetKey,
            CarryTransfer left,
            CarryTransfer right
    ) {
        BigDecimal quantity = safeAdd(left == null ? null : left.quantity(), right == null ? null : right.quantity());
        BigDecimal coveredQuantity = safeAdd(left == null ? null : left.coveredQuantity(), right == null ? null : right.coveredQuantity());
        BigDecimal uncoveredQuantity = safeAdd(left == null ? null : left.uncoveredQuantity(), right == null ? null : right.uncoveredQuantity());
        BigDecimal costBasisUsd = safeAdd(left == null ? null : left.costBasisUsd(), right == null ? null : right.costBasisUsd());
        BigDecimal avco = coveredQuantity.signum() <= 0 ? null : safeDivide(costBasisUsd, coveredQuantity);
        return new CarryTransfer(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco, false, assetKey);
    }

    private BigDecimal safeAdd(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left).add(right == null ? BigDecimal.ZERO : right, MC);
    }

    private NormalizedTransaction.Flow copyFlowWithQuantity(
            NormalizedTransaction.Flow original,
            BigDecimal quantityDelta
    ) {
        NormalizedTransaction.Flow copy = new NormalizedTransaction.Flow();
        copy.setRole(original.getRole());
        copy.setAssetContract(original.getAssetContract());
        copy.setAssetSymbol(original.getAssetSymbol());
        copy.setQuantityDelta(quantityDelta);
        copy.setUnitPriceUsd(original.getUnitPriceUsd());
        copy.setValueUsd(original.getValueUsd());
        copy.setPriceSource(original.getPriceSource());
        copy.setIsInferred(original.getIsInferred());
        copy.setInferenceReason(original.getInferenceReason());
        copy.setConfidence(original.getConfidence());
        copy.setAvcoAtTimeOfSale(original.getAvcoAtTimeOfSale());
        copy.setRealisedPnlUsd(original.getRealisedPnlUsd());
        copy.setLogIndex(original.getLogIndex());
        return copy;
    }

    private void restoreToPosition(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal avco,
            BigDecimal cost
    ) {
        restoreToPosition(flow.getQuantityDelta().abs(), position, cost, BigDecimal.ZERO, avco);
    }

    private void restoreToPosition(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco
    ) {
        position.quantity = position.quantity.add(quantity);
        position.uncoveredQuantity = position.uncoveredQuantity.add(uncoveredQuantity);
        position.totalCostBasisUsd = position.totalCostBasisUsd.add(cost);
        recomputePerWalletAvco(position);
        if (uncoveredQuantity.signum() > 0 || avco == null) {
            markUnresolved(position);
        }
    }

    private void applyUnknownTransfer(NormalizedTransaction.Flow flow, PositionState position) {
        if (flow.getQuantityDelta().signum() > 0) {
            position.quantity = position.quantity.add(flow.getQuantityDelta().abs());
            position.uncoveredQuantity = position.uncoveredQuantity.add(flow.getQuantityDelta().abs());
            markUnresolved(position);
            recomputePerWalletAvco(position);
            return;
        }
        QuantityConsumption consumption = consumeQuantity(position, flow.getQuantityDelta().abs());
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    private boolean isSponsoredGasIn(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && transaction.getType() == NormalizedTransactionType.SPONSORED_GAS_IN
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0;
    }

    private void applySponsoredGasIn(NormalizedTransaction.Flow flow, PositionState position) {
        restoreToPosition(flow.getQuantityDelta().abs(), position, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void materializePendingInbound(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        position.quantity = position.quantity.add(quantity);
        position.uncoveredQuantity = position.uncoveredQuantity.add(quantity);
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    private void attachLateCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            Map<AssetKey, PositionState> positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.computeIfAbsent(
                pendingInbound.assetKey(),
                ignored -> new PositionState(pendingInbound.assetKey())
        );
        PositionSnapshot before = snapshot(destination);
        CarryTransfer effectiveCarry = sliceCarryTransfer(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.uncoveredQuantity = nonNegative(destination.uncoveredQuantity.subtract(coveredResolvedQuantity, MC));
        destination.totalCostBasisUsd = destination.totalCostBasisUsd.add(effectiveCarry.costBasisUsd());
        recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && effectiveCarry.uncoveredQuantity().signum() == 0) {
            resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey,
                before,
                destination,
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    private void attachLateBridgeCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            Map<AssetKey, PositionState> positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.computeIfAbsent(
                pendingInbound.assetKey(),
                ignored -> new PositionState(pendingInbound.assetKey())
        );
        PositionSnapshot before = snapshot(destination);
        CarryTransfer effectiveCarry = bridgeInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.uncoveredQuantity = nonNegative(destination.uncoveredQuantity.subtract(coveredResolvedQuantity, MC));
        destination.totalCostBasisUsd = destination.totalCostBasisUsd.add(effectiveCarry.costBasisUsd());
        recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity.signum() == 0) {
            resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey,
                before,
                destination,
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    private void attachLateBridgeSettlementCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            Map<AssetKey, PositionState> positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.computeIfAbsent(
                pendingInbound.assetKey(),
                ignored -> new PositionState(pendingInbound.assetKey())
        );
        PositionSnapshot before = snapshot(destination);
        CarryTransfer effectiveCarry = bridgeSettlementInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.uncoveredQuantity = nonNegative(destination.uncoveredQuantity.subtract(coveredResolvedQuantity, MC));
        destination.totalCostBasisUsd = destination.totalCostBasisUsd.add(effectiveCarry.costBasisUsd());
        recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity.signum() == 0) {
            resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey,
                before,
                destination,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    private void markUnresolved(PositionState position) {
        position.hasIncompleteHistory = true;
        position.hasUnresolvedFlags = true;
        position.unresolvedFlagCount++;
    }

    private void recordQuantityShortfall(PositionState position, BigDecimal quantityShortfall) {
        if (quantityShortfall == null || quantityShortfall.signum() <= 0) {
            return;
        }
        position.quantityShortfall = position.quantityShortfall.add(quantityShortfall);
        markUnresolved(position);
    }

    private QuantityConsumption consumeQuantity(PositionState position, BigDecimal requestedQuantity) {
        BigDecimal availableQuantity = position.quantity == null ? BigDecimal.ZERO : position.quantity;
        BigDecimal availableUncovered = position.uncoveredQuantity == null ? BigDecimal.ZERO : position.uncoveredQuantity;
        BigDecimal availableCovered = nonNegative(availableQuantity.subtract(availableUncovered, MC));
        BigDecimal appliedQuantity = requestedQuantity.min(availableQuantity);
        BigDecimal coveredQuantity = appliedQuantity.min(availableCovered);
        BigDecimal uncoveredQuantity = nonNegative(appliedQuantity.subtract(coveredQuantity, MC));
        BigDecimal externalShortfallQuantity = nonNegative(requestedQuantity.subtract(appliedQuantity, MC));
        position.quantity = nonNegative(availableQuantity.subtract(appliedQuantity, MC));
        position.uncoveredQuantity = nonNegative(availableUncovered.subtract(uncoveredQuantity, MC));
        return new QuantityConsumption(appliedQuantity, coveredQuantity, uncoveredQuantity, externalShortfallQuantity);
    }

    private void recomputePerWalletAvco(PositionState position) {
        BigDecimal coveredQuantity = nonNegative(position.quantity.subtract(position.uncoveredQuantity, MC));
        position.perWalletAvco = coveredQuantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd, coveredQuantity);
    }

    private void resolveTemporaryUnresolved(PositionState position) {
        if (position.unresolvedFlagCount > 0) {
            position.unresolvedFlagCount--;
        }
        if (position.unresolvedFlagCount == 0) {
            position.hasIncompleteHistory = false;
            position.hasUnresolvedFlags = false;
        }
    }

    private void applyFallbackSettlementFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions,
            LedgerPointCollector ledgerPointCollector
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
        PositionSnapshot before = snapshot(position);
        if (flow.getRole() == NormalizedLegRole.FEE) {
            applyFee(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex(transaction, flow),
                    position.assetKey,
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.GAS_ONLY
            );
            return;
        }
        applyUnknownTransfer(flow, position);
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex(transaction, flow),
                position.assetKey,
                before,
                position,
                AssetLedgerPoint.BasisEffect.UNKNOWN
        );
    }

    private boolean allSameAsset(
            List<NormalizedTransaction.Flow> flows,
            NormalizedTransaction transaction
    ) {
        if (flows.isEmpty()) {
            return true;
        }
        String first = continuityIdentity(transaction, flows.getFirst());
        for (int index = 1; index < flows.size(); index++) {
            if (!first.equals(continuityIdentity(transaction, flows.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private boolean isFamilyEquivalentCustodyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
            return false;
        }
        return switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    STAKING_DEPOSIT,
                    STAKING_WITHDRAW,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW -> true;
            default -> false;
        };
    }

    private boolean allHaveKnownPrices(List<NormalizedTransaction.Flow> flows) {
        for (NormalizedTransaction.Flow flow : flows) {
            if (!hasKnownPrice(flow)) {
                return false;
            }
        }
        return true;
    }

    private boolean allHaveKnownReplayPrices(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows
    ) {
        for (IndexedFlow indexedFlow : flows) {
            if (replayUnitPriceUsd(transaction, indexedFlow.flow()) == null) {
                return false;
            }
        }
        return !flows.isEmpty();
    }

    private BigDecimal replayUnitPriceUsd(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow == null) {
            return null;
        }
        if (hasKnownPrice(flow)) {
            return flow.getUnitPriceUsd();
        }
        if (transaction != null && CanonicalAssetCatalog.isUsdStablecoin(
                transaction.getNetworkId(),
                flow.getAssetContract(),
                flow.getAssetSymbol(),
                transaction.getSource()
        )) {
            return BigDecimal.ONE;
        }
        return null;
    }

    private void allocateSettlementByQuantity(
            NormalizedTransaction transaction,
            List<NormalizedTransaction.Flow> flows,
            Map<AssetKey, PositionState> positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow flow : flows) {
            totalQuantity = totalQuantity.add(flow.getQuantityDelta().abs(), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        BigDecimal remainingQuantity = totalQuantity;
        for (int index = 0; index < flows.size(); index++) {
            NormalizedTransaction.Flow flow = flows.get(index);
            BigDecimal quantity = flow.getQuantityDelta().abs();
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(safeDivide(quantity, totalQuantity), MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            remainingQuantity = remainingQuantity.subtract(quantity, MC);
            restoreSettlementPosition(transaction, flow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private void allocateIndexedSettlementByQuantity(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            Map<AssetKey, PositionState> positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            totalQuantity = totalQuantity.add(indexedFlow.flow().getQuantityDelta().abs(), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            BigDecimal quantity = indexedFlow.flow().getQuantityDelta().abs();
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(safeDivide(quantity, totalQuantity), MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private void allocateSettlementByKnownValue(
            NormalizedTransaction transaction,
            List<NormalizedTransaction.Flow> flows,
            Map<AssetKey, PositionState> positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalValueUsd = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow flow : flows) {
            totalValueUsd = totalValueUsd.add(flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            NormalizedTransaction.Flow flow = flows.get(index);
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(
                    safeDivide(flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC), totalValueUsd),
                    MC
            );
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, flow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private void allocateIndexedSettlementByKnownValue(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            Map<AssetKey, PositionState> positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalValueUsd = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            totalValueUsd = totalValueUsd.add(flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(
                    safeDivide(flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC), totalValueUsd),
                    MC
            );
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private void allocateIndexedSettlementByReplayKnownValue(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            Map<AssetKey, PositionState> positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalValueUsd = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            BigDecimal replayUnitPrice = replayUnitPriceUsd(transaction, indexedFlow.flow());
            if (replayUnitPrice == null) {
                throw new IllegalArgumentException("Replay-known-value allocation requires deterministic unit prices");
            }
            totalValueUsd = totalValueUsd.add(
                    indexedFlow.flow().getQuantityDelta().abs().multiply(replayUnitPrice, MC),
                    MC
            );
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            BigDecimal replayUnitPrice = replayUnitPriceUsd(transaction, flow);
            BigDecimal flowValueUsd = flow.getQuantityDelta().abs().multiply(replayUnitPrice, MC);
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(safeDivide(flowValueUsd, totalValueUsd), MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private void restoreSettlementPosition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions,
            BigDecimal allocatedCost,
            LedgerPointCollector ledgerPointCollector
    ) {
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        PositionSnapshot before = snapshot(position);
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(allocatedCost, quantity);
        restoreToPosition(flow, position, avco, allocatedCost);
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex(transaction, flow),
                position.assetKey,
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    private void restoreSettlementPosition(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            Map<AssetKey, PositionState> positions,
            BigDecimal allocatedCost,
            LedgerPointCollector ledgerPointCollector
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        PositionSnapshot before = snapshot(position);
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(allocatedCost, quantity);
        restoreToPosition(flow, position, avco, allocatedCost);
        ledgerPointCollector.record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey,
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    private boolean isPositionScopedLpEntryOutbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta().signum() < 0
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    private boolean isPositionScopedLpExit(NormalizedTransaction transaction) {
        return transaction != null
                && isLpExitType(transaction.getType())
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    private boolean isBucketOutbound(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return flow.getQuantityDelta().signum() < 0 && switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    LENDING_DEPOSIT,
                    STAKING_DEPOSIT,
                    VAULT_DEPOSIT,
                    LP_ENTRY -> true;
            default -> false;
        };
    }

    private boolean isBucketInbound(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return flow.getQuantityDelta().signum() > 0 && switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_WITHDRAW,
                    STAKING_WITHDRAW,
                    VAULT_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
    }

    private boolean isLpExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private String transferKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
            String assetKey = correlatedTransferIdentity(transaction, flow);
            if (Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
                return "corr-family:" + transaction.getCorrelationId() + ":" + assetKey;
            }
        }

        String quantityKey = flow.getQuantityDelta().abs().stripTrailingZeros().toPlainString();
        String assetKey = continuityIdentity(transaction, flow);
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
            return "corr:" + transaction.getCorrelationId() + ":" + correlatedTransferIdentity(transaction, flow) + ":" + quantityKey;
        }
        if (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()) {
            return "tx:" + transaction.getTxHash() + ":" + assetKey + ":" + quantityKey;
        }
        return null;
    }

    private String bridgeTransferKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String bridgeFamilyIdentity = bridgeFamilyIdentity(transaction, flow);
        if (bridgeFamilyIdentity == null
                || transaction.getCorrelationId() == null
                || transaction.getCorrelationId().isBlank()) {
            return null;
        }
        return "bridge:" + transaction.getCorrelationId() + ":" + bridgeFamilyIdentity;
    }

    private String bridgeFamilyIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || !Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return null;
        }
        if (transaction.getType() != NormalizedTransactionType.BRIDGE_OUT
                && transaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return null;
        }
        return BridgeAssetFamilySupport.continuityIdentity(flow);
    }

    private String continuityIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String identity = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (identity != null) {
            return identity;
        }
        return assetKey(transaction, flow).assetIdentity();
    }

    private String correlatedTransferIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String identity = continuityIdentity(transaction, flow);
        if (identity == null || identity.isBlank()) {
            return identity;
        }
        if (identity.startsWith("FAMILY:")) {
            return identity;
        }
        String canonicalSymbol = canonicalCorrelatedTransferSymbol(flow == null ? null : flow.getAssetSymbol());
        if (canonicalSymbol == null || canonicalSymbol.isBlank()) {
            return identity;
        }
        return "SYMBOL:" + canonicalSymbol;
    }

    private String canonicalCorrelatedTransferSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return null;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return CORRELATED_TRANSFER_SYMBOL_ALIASES.getOrDefault(normalized, normalized);
    }

    private PassThroughCorridorPlan buildPassThroughCorridorPlan(List<NormalizedTransaction> ordered) {
        Map<PassThroughScopeKey, Deque<PassThroughCandidate>> openInboundCandidates = new LinkedHashMap<>();
        Map<String, PassThroughCorridor> byInboundFlowRef = new LinkedHashMap<>();
        Map<String, PassThroughCorridor> byOutboundFlowRef = new LinkedHashMap<>();

        for (NormalizedTransaction transaction : ordered) {
            Set<PassThroughScopeKey> touchedScopeKeys = touchedPassThroughScopeKeys(transaction);
            PassThroughCandidate outboundCandidate = selectPassThroughOutboundCandidate(transaction);
            if (outboundCandidate != null) {
                PassThroughScopeKey scopeKey = outboundCandidate.scopeKey();
                Deque<PassThroughCandidate> queue = openInboundCandidates.get(scopeKey);
                if (queue != null && queue.size() == 1) {
                    PassThroughCandidate inboundCandidate = queue.peekFirst();
                    if (quantitiesCompatible(inboundCandidate.quantity(), outboundCandidate.quantity())) {
                        queue.removeFirst();
                        PassThroughCorridor corridor = new PassThroughCorridor(
                                inboundCandidate.flowRef(),
                                outboundCandidate.flowRef(),
                                outboundCandidate.quantity()
                        );
                        byInboundFlowRef.put(inboundCandidate.flowRef(), corridor);
                        byOutboundFlowRef.put(outboundCandidate.flowRef(), corridor);
                    } else {
                        queue.clear();
                    }
                } else if (queue != null) {
                    queue.clear();
                }
                if (queue != null && queue.isEmpty()) {
                    openInboundCandidates.remove(scopeKey);
                }
            }

            for (PassThroughScopeKey scopeKey : touchedScopeKeys) {
                Deque<PassThroughCandidate> queue = openInboundCandidates.get(scopeKey);
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                queue.clear();
                openInboundCandidates.remove(scopeKey);
            }

            PassThroughCandidate inboundCandidate = selectPassThroughInboundCandidate(transaction);
            if (inboundCandidate != null) {
                openInboundCandidates.computeIfAbsent(inboundCandidate.scopeKey(), ignored -> new ArrayDeque<>())
                        .addLast(inboundCandidate);
            }
        }
        return new PassThroughCorridorPlan(byInboundFlowRef, byOutboundFlowRef);
    }

    private PassThroughCandidate selectPassThroughInboundCandidate(NormalizedTransaction transaction) {
        if (transaction == null
                || !Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || !isPassThroughInboundType(transaction.getType())) {
            return null;
        }
        List<IndexedFlow> positivePrincipalFlows = indexedFlows(transaction).stream()
                .filter(indexedFlow -> isPrincipalFlow(indexedFlow.flow()) && indexedFlow.flow().getQuantityDelta().signum() > 0)
                .toList();
        if (positivePrincipalFlows.size() != 1) {
            return null;
        }
        IndexedFlow inboundFlow = positivePrincipalFlows.getFirst();
        AssetKey assetKey = assetKey(transaction, inboundFlow.flow());
        return new PassThroughCandidate(
                flowRef(transaction, inboundFlow.index()),
                new PassThroughScopeKey(passThroughScope(transaction), assetKey.assetIdentity()),
                inboundFlow.flow().getQuantityDelta().abs()
        );
    }

    private PassThroughCandidate selectPassThroughOutboundCandidate(NormalizedTransaction transaction) {
        if (transaction == null) {
            return null;
        }
        if (!isPassThroughOutboundType(transaction)) {
            return null;
        }
        List<IndexedFlow> negativePrincipalFlows = indexedFlows(transaction).stream()
                .filter(indexedFlow -> isPrincipalFlow(indexedFlow.flow()) && indexedFlow.flow().getQuantityDelta().signum() < 0)
                .toList();
        if (negativePrincipalFlows.size() != 1) {
            return null;
        }
        IndexedFlow outboundFlow = negativePrincipalFlows.getFirst();
        AssetKey assetKey = assetKey(transaction, outboundFlow.flow());
        return new PassThroughCandidate(
                flowRef(transaction, outboundFlow.index()),
                new PassThroughScopeKey(passThroughScope(transaction), assetKey.assetIdentity()),
                outboundFlow.flow().getQuantityDelta().abs()
        );
    }

    private boolean isPrincipalFlow(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0;
    }

    private boolean isPassThroughInboundType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || type == NormalizedTransactionType.BRIDGE_IN
                || type == NormalizedTransactionType.INTERNAL_TRANSFER;
    }

    private boolean isPassThroughOutboundType(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER)) {
            return true;
        }
        return switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    LENDING_DEPOSIT,
                    STAKING_DEPOSIT,
                    VAULT_DEPOSIT,
                    LP_ENTRY -> true;
            default -> false;
        };
    }

    private Set<PassThroughScopeKey> touchedPassThroughScopeKeys(NormalizedTransaction transaction) {
        if (transaction == null) {
            return Set.of();
        }
        String scope = passThroughScope(transaction);
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        Set<PassThroughScopeKey> touched = new LinkedHashSet<>();
        for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
            if (!isPrincipalFlow(indexedFlow.flow())) {
                continue;
            }
            BigDecimal quantityDelta = indexedFlow.flow().getQuantityDelta();
            if (quantityDelta == null || quantityDelta.signum() == 0) {
                continue;
            }
            touched.add(new PassThroughScopeKey(
                    scope,
                    assetKey(transaction, indexedFlow.flow()).assetIdentity()
            ));
        }
        return touched;
    }

    private String passThroughScope(NormalizedTransaction transaction) {
        if (transaction.getWalletAddress() != null && transaction.getWalletAddress().startsWith("BYBIT:")) {
            return "VENUE:" + transaction.getWalletAddress();
        }
        return "WALLET_NETWORK:" + transaction.getWalletAddress() + ":" + Objects.toString(transaction.getNetworkId(), "BYBIT");
    }

    private boolean quantitiesCompatible(BigDecimal inboundQuantity, BigDecimal outboundQuantity) {
        if (inboundQuantity == null
                || outboundQuantity == null
                || inboundQuantity.signum() <= 0
                || outboundQuantity.signum() <= 0) {
            return false;
        }
        BigDecimal tolerance = inboundQuantity.multiply(new BigDecimal("0.002"), MC).max(new BigDecimal("0.000001"));
        return outboundQuantity.compareTo(inboundQuantity.add(tolerance, MC)) <= 0;
    }

    private int findUniqueCompatibleQueueIndex(
            Deque<CarryTransfer> queue,
            boolean pendingInbound,
            BigDecimal targetQuantity
    ) {
        if (queue == null || queue.isEmpty() || targetQuantity == null || targetQuantity.signum() <= 0) {
            return -1;
        }
        int matchedIndex = -1;
        int index = 0;
        for (CarryTransfer candidate : queue) {
            if (candidate == null || candidate.pendingInbound() != pendingInbound) {
                index++;
                continue;
            }
            if (!correlatedTransferQuantitiesCompatible(candidate.quantity(), targetQuantity)) {
                index++;
                continue;
            }
            if (matchedIndex >= 0) {
                return -1;
            }
            matchedIndex = index;
            index++;
        }
        return matchedIndex;
    }

    private int findUniqueBridgeQueueIndex(
            Deque<CarryTransfer> queue,
            boolean pendingInbound
    ) {
        if (queue == null || queue.isEmpty()) {
            return -1;
        }
        int matchedIndex = -1;
        int index = 0;
        for (CarryTransfer candidate : queue) {
            if (candidate == null || candidate.pendingInbound() != pendingInbound) {
                index++;
                continue;
            }
            if (matchedIndex >= 0) {
                return -1;
            }
            matchedIndex = index;
            index++;
        }
        return matchedIndex;
    }

    private CarryTransfer removeQueueElement(Deque<CarryTransfer> queue, int index) {
        if (queue == null || index < 0 || index >= queue.size()) {
            return null;
        }
        int currentIndex = 0;
        for (var iterator = queue.iterator(); iterator.hasNext(); ) {
            CarryTransfer candidate = iterator.next();
            if (currentIndex == index) {
                iterator.remove();
                return candidate;
            }
            currentIndex++;
        }
        return null;
    }

    private boolean correlatedTransferQuantitiesCompatible(
            BigDecimal leftQuantity,
            BigDecimal rightQuantity
    ) {
        if (leftQuantity == null
                || rightQuantity == null
                || leftQuantity.signum() <= 0
                || rightQuantity.signum() <= 0) {
            return false;
        }
        BigDecimal delta = leftQuantity.subtract(rightQuantity, MC).abs();
        BigDecimal baseline = leftQuantity.max(rightQuantity);
        BigDecimal tolerance = baseline.multiply(CORRELATED_TRANSFER_RELATIVE_TOLERANCE, MC)
                .max(CORRELATED_TRANSFER_ABSOLUTE_TOLERANCE)
                .min(CORRELATED_TRANSFER_MAX_TOLERANCE);
        return delta.compareTo(tolerance) <= 0;
    }

    private String flowRef(NormalizedTransaction transaction, int flowIndex) {
        return transaction.getId() + ":" + flowIndex;
    }

    private boolean isLinkedBridgeContinuityTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0
                && bridgeTransferKey(transaction, flow) != null;
    }

    private boolean isLinkedBridgeSettlementTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0
                && bridgeSettlementKey(transaction, flow) != null;
    }

    private String bridgeSettlementKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || transaction.getType() == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0
                || transaction.getCorrelationId() == null
                || transaction.getCorrelationId().isBlank()
                || transaction.getMatchedCounterparty() == null
                || transaction.getMatchedCounterparty().isBlank()
                || Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || !hasSinglePrincipalTransferFlow(transaction)) {
            return null;
        }
        if (transaction.getType() != NormalizedTransactionType.BRIDGE_OUT
                && transaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return null;
        }
        return "bridge-settlement:" + transaction.getCorrelationId();
    }

    private boolean hasSinglePrincipalTransferFlow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        long principalTransfers = transaction.getFlows().stream()
                .filter(flow -> flow != null
                        && flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0)
                .count();
        return principalTransfers == 1;
    }

    private ContinuityKey continuityKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return new ContinuityKey(
                transaction.getWalletAddress(),
                transaction.getNetworkId(),
                continuityIdentity(transaction, flow)
        );
    }

    private boolean sameAssetIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow left,
            NormalizedTransaction.Flow right
    ) {
        return continuityIdentity(transaction, left).equals(continuityIdentity(transaction, right));
    }

    private static final class AsyncLifecycleBucket {

        private final Map<String, Deque<CarryTransfer>> carriesByAsset = new LinkedHashMap<>();
        private final Map<String, Deque<CarryTransfer>> executionFeeReservesByAsset = new LinkedHashMap<>();

        private void add(String assetIdentity, CarryTransfer carry) {
            carriesByAsset.computeIfAbsent(assetIdentity, ignored -> new ArrayDeque<>()).addLast(carry);
        }

        private void addExecutionFeeReserve(String assetIdentity, CarryTransfer carry) {
            executionFeeReservesByAsset.computeIfAbsent(assetIdentity, ignored -> new ArrayDeque<>()).addLast(carry);
        }

        private Set<String> knownAssetIdentities() {
            return Set.copyOf(carriesByAsset.keySet());
        }

        private CarryTransfer takeSameAssetCarry(
                String assetIdentity,
                BigDecimal requestedQuantity,
                AssetKey restoreAssetKey
        ) {
            return takeCarry(carriesByAsset, assetIdentity, requestedQuantity, restoreAssetKey);
        }

        private CarryTransfer takeExecutionFeeReserve(
                String assetIdentity,
                BigDecimal requestedQuantity,
                AssetKey restoreAssetKey
        ) {
            return takeCarry(executionFeeReservesByAsset, assetIdentity, requestedQuantity, restoreAssetKey);
        }

        private CarryTransfer takeCarry(
                Map<String, Deque<CarryTransfer>> carries,
                String assetIdentity,
                BigDecimal requestedQuantity,
                AssetKey restoreAssetKey
        ) {
            Deque<CarryTransfer> queue = carries.get(assetIdentity);
            if (queue == null || queue.isEmpty()) {
                return null;
            }
            BigDecimal remainingRequested = requestedQuantity;
            BigDecimal appliedQuantity = BigDecimal.ZERO;
            BigDecimal appliedCoveredQuantity = BigDecimal.ZERO;
            BigDecimal appliedUncoveredQuantity = BigDecimal.ZERO;
            BigDecimal appliedCost = BigDecimal.ZERO;

            while (remainingRequested.signum() > 0 && queue != null && !queue.isEmpty()) {
                CarryTransfer carry = queue.removeFirst();
                BigDecimal carryQuantity = carry.quantity();
                BigDecimal usedQuantity = carryQuantity.min(remainingRequested);
                BigDecimal usedCoveredQuantity = usedQuantity.min(carry.coveredQuantity());
                BigDecimal usedUncoveredQuantity = nonNegative(usedQuantity.subtract(usedCoveredQuantity, MC));
                BigDecimal usedCost = carry.avco() == null
                        ? BigDecimal.ZERO
                        : usedCoveredQuantity.multiply(carry.avco(), MC);

                appliedQuantity = appliedQuantity.add(usedQuantity, MC);
                appliedCoveredQuantity = appliedCoveredQuantity.add(usedCoveredQuantity, MC);
                appliedUncoveredQuantity = appliedUncoveredQuantity.add(usedUncoveredQuantity, MC);
                appliedCost = appliedCost.add(usedCost, MC);

                if (carryQuantity.compareTo(usedQuantity) > 0) {
                    BigDecimal remainingQuantity = carryQuantity.subtract(usedQuantity, MC);
                    BigDecimal remainingCoveredQuantity =
                            nonNegative(carry.coveredQuantity().subtract(usedCoveredQuantity, MC));
                    BigDecimal remainingUncoveredQuantity =
                            nonNegative(carry.uncoveredQuantity().subtract(usedUncoveredQuantity, MC));
                    BigDecimal remainingCost = nonNegative(carry.costBasisUsd().subtract(usedCost, MC));
                    BigDecimal remainingAvco = remainingCoveredQuantity.signum() <= 0
                            ? null
                            : safeDivide(remainingCost, remainingCoveredQuantity);
                    queue.addFirst(new CarryTransfer(
                            remainingQuantity,
                            remainingCoveredQuantity,
                            remainingUncoveredQuantity,
                            remainingCost,
                            remainingAvco,
                            carry.pendingInbound(),
                            carry.assetKey()
                    ));
                }
                remainingRequested = remainingRequested.subtract(usedQuantity, MC);
                if (queue.isEmpty()) {
                    carries.remove(assetIdentity);
                }
                queue = carries.get(assetIdentity);
            }
            if (appliedQuantity.signum() <= 0) {
                return null;
            }
            BigDecimal appliedAvco = appliedCoveredQuantity.signum() <= 0
                    ? null
                    : safeDivide(appliedCost, appliedCoveredQuantity);
            return new CarryTransfer(
                    appliedQuantity,
                    appliedCoveredQuantity,
                    appliedUncoveredQuantity,
                    appliedCost,
                    appliedAvco,
                    false,
                    restoreAssetKey
            );
        }

        private BigDecimal remainingCostBasisUsd() {
            BigDecimal total = BigDecimal.ZERO;
            for (Deque<CarryTransfer> queue : carriesByAsset.values()) {
                for (CarryTransfer carry : queue) {
                    total = total.add(carry.costBasisUsd());
                }
            }
            return total;
        }

        private BigDecimal remainingExecutionFeeReserveCostBasisUsd() {
            BigDecimal total = BigDecimal.ZERO;
            for (Deque<CarryTransfer> queue : executionFeeReservesByAsset.values()) {
                for (CarryTransfer carry : queue) {
                    total = total.add(carry.costBasisUsd());
                }
            }
            return total;
        }

        private BigDecimal remainingUncoveredQuantity() {
            BigDecimal total = BigDecimal.ZERO;
            for (Deque<CarryTransfer> queue : carriesByAsset.values()) {
                for (CarryTransfer carry : queue) {
                    total = total.add(carry.uncoveredQuantity(), MC);
                }
            }
            return total;
        }

        private void clearAll() {
            carriesByAsset.clear();
            executionFeeReservesByAsset.clear();
        }

        private boolean isEmpty() {
            return carriesByAsset.isEmpty() && executionFeeReservesByAsset.isEmpty();
        }
    }

    private static final class AsyncSpotOrderBucket {

        private final Deque<AsyncSpotOrderCarry> carries = new ArrayDeque<>();

        private void add(CarryTransfer carry, NormalizedTransaction.Flow requestFlow) {
            carries.addLast(new AsyncSpotOrderCarry(carry, requestFlow));
        }

        private BigDecimal totalCostBasisUsd() {
            BigDecimal total = BigDecimal.ZERO;
            for (AsyncSpotOrderCarry entry : carries) {
                total = total.add(entry.carry().costBasisUsd());
            }
            return total;
        }

        private BigDecimal totalQuantity() {
            BigDecimal total = BigDecimal.ZERO;
            for (AsyncSpotOrderCarry entry : carries) {
                total = total.add(entry.carry().quantity());
            }
            return total;
        }

        private List<AsyncSpotOrderCarry> drainAll() {
            List<AsyncSpotOrderCarry> drained = new ArrayList<>(carries);
            carries.clear();
            return drained;
        }

        private boolean isEmpty() {
            return carries.isEmpty();
        }
    }

    private AssetKey assetKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        String assetContract = AccountingAssetIdentitySupport.positionAssetIdentity(transaction, flow);
        String assetSymbol = normalizeSymbol(flow.getAssetSymbol());
        String assetIdentity = assetContract != null ? assetContract : "SYMBOL:" + assetSymbol;
        return new AssetKey(
                transaction.getWalletAddress(),
                AccountingAssetIdentitySupport.positionNetwork(transaction),
                assetContract != null ? assetContract : assetIdentity,
                assetSymbol,
                assetIdentity
        );
    }

    private String assetIdentity(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return assetKey(transaction, flow).assetIdentity();
    }

    private NormalizedTransaction copyTransaction(NormalizedTransaction transaction) {
        NormalizedTransaction copy = new NormalizedTransaction();
        copy.setId(transaction.getId());
        copy.setTxHash(transaction.getTxHash());
        copy.setNetworkId(transaction.getNetworkId());
        copy.setWalletAddress(transaction.getWalletAddress());
        copy.setSource(transaction.getSource());
        copy.setBlockTimestamp(transaction.getBlockTimestamp());
        copy.setTransactionIndex(transaction.getTransactionIndex());
        copy.setType(transaction.getType());
        copy.setStatus(transaction.getStatus());
        copy.setClassifiedBy(transaction.getClassifiedBy());
        copy.setConfidence(transaction.getConfidence());
        copy.setCorrelationId(transaction.getCorrelationId());
       copy.setContinuityCandidate(transaction.getContinuityCandidate());
        copy.setMatchedCounterparty(transaction.getMatchedCounterparty());
        copy.setExcludedFromAccounting(transaction.getExcludedFromAccounting());
        copy.setAccountingExclusionReason(transaction.getAccountingExclusionReason());
        copy.setProtocolName(transaction.getProtocolName());
        copy.setProtocolVersion(transaction.getProtocolVersion());
        copy.setClarificationAttempts(transaction.getClarificationAttempts());
        copy.setFullReceiptClarificationAttempts(transaction.getFullReceiptClarificationAttempts());
        copy.setPricingAttempts(transaction.getPricingAttempts());
        copy.setStatAttempts(transaction.getStatAttempts());
        copy.setCreatedAt(transaction.getCreatedAt());
        copy.setUpdatedAt(transaction.getUpdatedAt());
        copy.setConfirmedAt(transaction.getConfirmedAt());
        copy.setClientId(transaction.getClientId());
        copy.setMissingDataReasons(transaction.getMissingDataReasons() == null
                ? List.of()
                : new ArrayList<>(transaction.getMissingDataReasons()));

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (NormalizedTransaction.Flow originalFlow : transaction.getFlows()) {
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setRole(originalFlow.getRole());
            flow.setAssetContract(originalFlow.getAssetContract());
            flow.setAssetSymbol(originalFlow.getAssetSymbol());
            flow.setQuantityDelta(originalFlow.getQuantityDelta());
            flow.setUnitPriceUsd(originalFlow.getUnitPriceUsd());
            flow.setValueUsd(originalFlow.getValueUsd());
            flow.setPriceSource(originalFlow.getPriceSource());
            flow.setIsInferred(originalFlow.getIsInferred());
            flow.setInferenceReason(originalFlow.getInferenceReason());
            flow.setConfidence(originalFlow.getConfidence());
            flow.setAvcoAtTimeOfSale(originalFlow.getAvcoAtTimeOfSale());
            flow.setRealisedPnlUsd(originalFlow.getRealisedPnlUsd());
            flow.setLogIndex(originalFlow.getLogIndex());
            flows.add(flow);
        }
        copy.setFlows(flows);
        return copy;
    }

    private boolean hasKnownPrice(NormalizedTransaction.Flow flow) {
        return flow.getUnitPriceUsd() != null
                && flow.getPriceSource() != null
                && flow.getPriceSource() != PriceSource.UNKNOWN;
    }

    private String normalizeContract(String contract) {
        return contract == null || contract.isBlank() ? null : contract.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    private PositionSnapshot snapshot(PositionState position) {
        return new PositionSnapshot(
                position.quantity,
                position.perWalletAvco,
                position.totalCostBasisUsd,
                position.totalGasPaidUsd,
                position.totalRealisedPnlUsd,
                position.quantityShortfall,
                position.uncoveredQuantity,
                position.hasIncompleteHistory,
                position.hasUnresolvedFlags,
                position.unresolvedFlagCount
        );
    }

    private int flowIndex(NormalizedTransaction transaction, NormalizedTransaction.Flow target) {
        if (transaction == null || transaction.getFlows() == null || target == null) {
            return 0;
        }
        for (int index = 0; index < transaction.getFlows().size(); index++) {
            if (transaction.getFlows().get(index) == target) {
                return index;
            }
        }
        return 0;
    }

    private AssetLedgerPoint.BasisEffect defaultBasisEffect(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getRole() == null) {
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }
        return switch (flow.getRole()) {
            case BUY -> AssetLedgerPoint.BasisEffect.ACQUIRE;
            case SELL -> AssetLedgerPoint.BasisEffect.DISPOSE;
            case FEE -> AssetLedgerPoint.BasisEffect.GAS_ONLY;
            case TRANSFER -> AssetLedgerPoint.BasisEffect.UNKNOWN;
        };
    }

    private AssetLedgerPoint.BasisEffect continuityBasisEffect(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }
        return switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    STAKING_DEPOSIT,
                    STAKING_WITHDRAW,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW,
                    LP_ENTRY,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> flow.getQuantityDelta().signum() < 0
                    ? AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                    : AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
            default -> flow.getQuantityDelta().signum() < 0
                    ? AssetLedgerPoint.BasisEffect.CARRY_OUT
                    : AssetLedgerPoint.BasisEffect.CARRY_IN;
        };
    }

    private AssetLedgerPoint.BasisEffect routeSettlementBasisEffect(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }
        return flow.getQuantityDelta().signum() < 0
                ? AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                : AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private Instant laterOf(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return current.isAfter(candidate) ? current : candidate;
    }

    private record AssetKey(
            String walletAddress,
            com.walletradar.domain.common.NetworkId networkId,
            String assetContract,
            String assetSymbol,
            String assetIdentity
    ) {
        String id() {
            return walletAddress + ":" + Objects.toString(networkId, "BYBIT") + ":" + assetContract;
        }
    }

    private record ContinuityKey(
            String walletAddress,
            com.walletradar.domain.common.NetworkId networkId,
            String continuityIdentity
    ) {
    }

    private record PassThroughScopeKey(
            String scope,
            String assetIdentity
    ) {
    }

    private record PassThroughCandidate(
            String flowRef,
            PassThroughScopeKey scopeKey,
            BigDecimal quantity
    ) {
    }

    private record PassThroughCorridor(
            String inboundFlowRef,
            String outboundFlowRef,
            BigDecimal reservedQuantity
    ) {
    }

    private record PassThroughCorridorPlan(
            Map<String, PassThroughCorridor> byInboundFlowRef,
            Map<String, PassThroughCorridor> byOutboundFlowRef
    ) {
    }

    private record IndexedFlow(
            int index,
            NormalizedTransaction.Flow flow
    ) {
    }

    private record PositionSnapshot(
            BigDecimal quantity,
            BigDecimal perWalletAvco,
            BigDecimal totalCostBasisUsd,
            BigDecimal totalGasPaidUsd,
            BigDecimal totalRealisedPnlUsd,
            BigDecimal quantityShortfall,
            BigDecimal uncoveredQuantity,
            boolean hasIncompleteHistory,
            boolean hasUnresolvedFlags,
            int unresolvedFlagCount
    ) {
        private boolean sameAs(PositionState state) {
            return sameDecimal(quantity, state.quantity)
                    && sameDecimal(perWalletAvco, state.perWalletAvco)
                    && sameDecimal(totalCostBasisUsd, state.totalCostBasisUsd)
                    && sameDecimal(totalGasPaidUsd, state.totalGasPaidUsd)
                    && sameDecimal(totalRealisedPnlUsd, state.totalRealisedPnlUsd)
                    && sameDecimal(quantityShortfall, state.quantityShortfall)
                    && sameDecimal(uncoveredQuantity, state.uncoveredQuantity)
                    && hasIncompleteHistory == state.hasIncompleteHistory
                    && hasUnresolvedFlags == state.hasUnresolvedFlags
                    && unresolvedFlagCount == state.unresolvedFlagCount;
        }
    }

    private static final class PositionState {
        private final AssetKey assetKey;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal perWalletAvco;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal totalGasPaidUsd = BigDecimal.ZERO;
        private BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        private BigDecimal quantityShortfall = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantity = BigDecimal.ZERO;
        private boolean hasIncompleteHistory;
        private boolean hasUnresolvedFlags;
        private int unresolvedFlagCount;
        private Instant lastEventTimestamp;

        private PositionState(AssetKey assetKey) {
            this.assetKey = assetKey;
        }
    }

    private static final class LedgerPointCollector {
        private long replaySequence;
        private final String accountingUniverseId;
        private final List<AssetLedgerPoint> points;
        private final Instant createdAt;

        private LedgerPointCollector(String accountingUniverseId, List<AssetLedgerPoint> points, Instant createdAt) {
            this.accountingUniverseId = accountingUniverseId;
            this.points = points;
            this.createdAt = createdAt;
        }

        private void record(
                NormalizedTransaction transaction,
                NormalizedTransaction.Flow flow,
                int flowIndex,
                AssetKey assetKey,
                PositionSnapshot before,
                PositionState after,
                AssetLedgerPoint.BasisEffect basisEffect
        ) {
            if (transaction == null || flow == null || assetKey == null || before == null || after == null || before.sameAs(after)) {
                return;
            }
            long sequence = replaySequence++;
            AssetLedgerPoint point = new AssetLedgerPoint();
            point.setId(accountingUniverseId + ":" + transaction.getId() + ":" + flowIndex + ":" + sequence);
            point.setAccountingUniverseId(accountingUniverseId);
            point.setWalletAddress(assetKey.walletAddress());
            point.setNetworkId(assetKey.networkId());
            point.setAccountingAssetIdentity(assetKey.assetIdentity());
            String familyIdentity = AssetLedgerSupport.accountingFamilyIdentity(transaction, flow);
            point.setAccountingFamilyIdentity(familyIdentity);
            point.setFamilyDisplaySymbol(AssetLedgerSupport.familyDisplaySymbol(familyIdentity, assetKey.assetSymbol()));
            point.setAssetSymbol(assetKey.assetSymbol());
            point.setAssetContract(assetKey.assetContract());
            point.setNormalizedTransactionId(transaction.getId());
            point.setTxHash(transaction.getTxHash());
            point.setCorrelationId(transaction.getCorrelationId());
            point.setLifecycleChainId(transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()
                    ? transaction.getCorrelationId()
                    : transaction.getTxHash());
            point.setMatchedCounterparty(transaction.getMatchedCounterparty());
            point.setContinuityCandidate(transaction.getContinuityCandidate());
            point.setBlockTimestamp(transaction.getBlockTimestamp());
            point.setTransactionIndex(transaction.getTransactionIndex());
            point.setFlowIndex(flowIndex);
            point.setReplaySequence(sequence);
            point.setNormalizedType(transaction.getType() == null ? null : transaction.getType().name());
            point.setLifecycleKind(AssetLedgerSupport.lifecycleKind(transaction.getType()));
            point.setLifecycleStage(AssetLedgerSupport.lifecycleStage(transaction.getType()));
            point.setBasisEffect(basisEffect);
            point.setProtocolName(transaction.getProtocolName());
            point.setQuantityDelta(delta(after.quantity, before.quantity));
            point.setCostBasisDeltaUsd(delta(after.totalCostBasisUsd, before.totalCostBasisUsd));
            point.setRealisedPnlDeltaUsd(delta(after.totalRealisedPnlUsd, before.totalRealisedPnlUsd));
            point.setGasDeltaUsd(delta(after.totalGasPaidUsd, before.totalGasPaidUsd));
            point.setQuantityShortfallDelta(delta(after.quantityShortfall, before.quantityShortfall));
            point.setUncoveredQuantityDelta(delta(after.uncoveredQuantity, before.uncoveredQuantity));
            point.setQuantityBefore(before.quantity);
            point.setQuantityAfter(after.quantity);
            point.setTotalCostBasisBeforeUsd(before.totalCostBasisUsd);
            point.setTotalCostBasisAfterUsd(after.totalCostBasisUsd);
            point.setAvcoBeforeUsd(before.perWalletAvco);
            point.setAvcoAfterUsd(after.perWalletAvco);
            point.setQuantityShortfallAfter(after.quantityShortfall);
            point.setUncoveredQuantityAfter(after.uncoveredQuantity);
            point.setBasisBackedQuantityAfter(nonNegativeStatic(after.quantity.subtract(after.uncoveredQuantity, MC)));
            point.setHasIncompleteHistoryAfter(after.hasIncompleteHistory);
            point.setHasUnresolvedFlagsAfter(after.hasUnresolvedFlags);
            point.setUnresolvedFlagCountAfter(after.unresolvedFlagCount);
            point.setCreatedAt(createdAt);
            points.add(point);
        }

        private static BigDecimal delta(BigDecimal after, BigDecimal before) {
            BigDecimal left = after == null ? BigDecimal.ZERO : after;
            BigDecimal right = before == null ? BigDecimal.ZERO : before;
            return left.subtract(right, MC);
        }

        private static BigDecimal nonNegativeStatic(BigDecimal value) {
            return value.signum() < 0 ? BigDecimal.ZERO : value;
        }
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        BigDecimal a = left == null ? BigDecimal.ZERO : left;
        BigDecimal b = right == null ? BigDecimal.ZERO : right;
        return a.compareTo(b) == 0;
    }

    private static final class ContinuityBucket {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantity = BigDecimal.ZERO;

        private void add(CarryTransfer carry) {
            quantity = quantity.add(carry.quantity());
            totalCostBasisUsd = totalCostBasisUsd.add(carry.costBasisUsd());
            uncoveredQuantity = uncoveredQuantity.add(carry.uncoveredQuantity());
        }

        private CarryTransfer take(BigDecimal requestedQuantity, AssetKey assetKey) {
            BigDecimal availableQuantity = quantity;
            BigDecimal availableUncovered = uncoveredQuantity;
            BigDecimal availableCovered = nonNegative(availableQuantity.subtract(availableUncovered, MC));
            BigDecimal appliedQuantity = requestedQuantity.min(availableQuantity);
            BigDecimal coveredQuantity = appliedQuantity.min(availableCovered);
            BigDecimal uncoveredQuantityToApply = nonNegative(requestedQuantity.subtract(coveredQuantity, MC));
            BigDecimal avco = availableCovered.signum() <= 0
                    ? null
                    : safeDivide(totalCostBasisUsd, availableCovered);
            BigDecimal cost = avco == null
                    ? BigDecimal.ZERO
                    : coveredQuantity.multiply(avco, MC);
            quantity = nonNegative(quantity.subtract(appliedQuantity, MC));
            totalCostBasisUsd = nonNegative(totalCostBasisUsd.subtract(cost, MC));
            uncoveredQuantity = nonNegative(availableUncovered.subtract(nonNegative(appliedQuantity.subtract(coveredQuantity, MC)), MC));
            return new CarryTransfer(
                    requestedQuantity,
                    coveredQuantity,
                    uncoveredQuantityToApply,
                    cost,
                    avco,
                    false,
                    assetKey
            );
        }
    }

    private record CarryTransfer(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            boolean pendingInbound,
            AssetKey assetKey
    ) {
        private static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey) {
            return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, true, assetKey);
        }
    }

    private record LiquidStakingFlowSelection(
            List<IndexedFlow> outbound,
            List<IndexedFlow> inbound
    ) {
        private static LiquidStakingFlowSelection empty() {
            return new LiquidStakingFlowSelection(List.of(), List.of());
        }
    }

    private record SimpleFamilyCustodyPair(
            IndexedFlow outbound,
            IndexedFlow inbound
    ) {
    }

    private record SimpleFamilyCustodySelection(
            List<SimpleFamilyCustodyPair> pairs,
            Map<Integer, IndexedFlow> selectedByIndex
    ) {
        private static SimpleFamilyCustodySelection empty() {
            return new SimpleFamilyCustodySelection(List.of(), Map.of());
        }
    }

    private static final class LiquidStakingCarry {
        private BigDecimal totalSourceQuantity = BigDecimal.ZERO;
        private BigDecimal totalCoveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalUncoveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;

        private void add(CarryTransfer carry) {
            if (carry == null) {
                return;
            }
            totalSourceQuantity = totalSourceQuantity.add(carry.quantity(), MC);
            totalCoveredQuantity = totalCoveredQuantity.add(carry.coveredQuantity(), MC);
            totalUncoveredQuantity = totalUncoveredQuantity.add(carry.uncoveredQuantity(), MC);
            totalCostBasisUsd = totalCostBasisUsd.add(carry.costBasisUsd(), MC);
        }

        private BigDecimal totalSourceQuantity() {
            return totalSourceQuantity;
        }

        private BigDecimal totalCoveredQuantity() {
            return totalCoveredQuantity;
        }

        private BigDecimal totalUncoveredQuantity() {
            return totalUncoveredQuantity;
        }

        private BigDecimal totalCostBasisUsd() {
            return totalCostBasisUsd;
        }
    }

    private record AsyncSpotOrderCarry(
            CarryTransfer carry,
            NormalizedTransaction.Flow requestFlow
    ) {
    }

    private record QuantityConsumption(
            BigDecimal appliedQuantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal externalShortfallQuantity
    ) {
    }
}
