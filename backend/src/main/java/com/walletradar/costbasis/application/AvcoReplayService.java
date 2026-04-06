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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

    private final ConfirmedReplayQueryService confirmedReplayQueryService;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;

    public int replayConfirmed() {
        List<NormalizedTransaction> ordered = confirmedReplayQueryService.loadOrderedConfirmed();
        Map<AssetKey, PositionState> positions = new LinkedHashMap<>();
        Map<ContinuityKey, ContinuityBucket> continuityBuckets = new LinkedHashMap<>();
        Map<String, Deque<CarryTransfer>> pendingTransfers = new LinkedHashMap<>();
        Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets = new LinkedHashMap<>();
        Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets = new LinkedHashMap<>();
        List<NormalizedTransaction> updatedTransactions = new ArrayList<>();
        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        Instant now = Instant.now();
        LedgerPointCollector ledgerPointCollector = new LedgerPointCollector(ledgerPoints, now);

        for (NormalizedTransaction transaction : ordered) {
            NormalizedTransaction replayed = copyTransaction(transaction);
            if (replayed.getType() == NormalizedTransactionType.LENDING_LOOP_REBALANCE) {
                applyEulerLoopRebalance(replayed, positions, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (replayed.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT) {
                applyAsyncLpExitSettlement(replayed, positions, asyncLifecycleBuckets, ledgerPointCollector);
                updatedTransactions.add(replayed);
                continue;
            }
            if (isLiquidStakingContinuityTransaction(replayed)) {
                applyLiquidStakingConversion(
                        replayed,
                        positions,
                        continuityBuckets,
                        pendingTransfers,
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
                        asyncLifecycleBuckets,
                        asyncSpotOrderBuckets,
                        ledgerPointCollector
                );
            }
            updatedTransactions.add(replayed);
        }

        assetLedgerPointRepository.deleteAll();
        if (!ledgerPoints.isEmpty()) {
            assetLedgerPointRepository.saveAll(ledgerPoints);
        }
        normalizedTransactionRepository.saveAll(updatedTransactions);
        return updatedTransactions.size();
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
        if (shouldIgnoreLpReceiptMarker(transaction, flow)) {
            return;
        }
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
        PositionSnapshot before = snapshot(position);

        if (shouldTreatAsContinuityTransfer(transaction, flow)) {
            AssetLedgerPoint.BasisEffect basisEffect = applyTransfer(
                    transaction,
                    asTransferFlow(flow),
                    flowIndex,
                    position,
                    positions,
                    continuityBuckets,
                    pendingTransfers,
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

        List<NormalizedTransaction.Flow> principalInflows = new ArrayList<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
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
                CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(assetKey.assetIdentity(), flow.getQuantityDelta().abs());
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
                            flowIndex(transaction, flow),
                            position.assetKey,
                            before,
                            position,
                            AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                    );
                } else {
                    principalInflows.add(flow);
                }
                continue;
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

        if (principalInflows.isEmpty()) {
            if (bucket.isEmpty()) {
                asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            }
            return;
        }

        BigDecimal remainingCostBasis = bucket.remainingCostBasisUsd();
        if (remainingCostBasis.signum() <= 0) {
            for (NormalizedTransaction.Flow flow : principalInflows) {
                AssetKey assetKey = assetKey(transaction, flow);
                PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
                PositionSnapshot before = snapshot(position);
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
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        if (allSameAsset(principalInflows, transaction)) {
            allocateSettlementByQuantity(transaction, principalInflows, positions, remainingCostBasis, ledgerPointCollector);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        if (allHaveKnownPrices(principalInflows)) {
            allocateSettlementByKnownValue(transaction, principalInflows, positions, remainingCostBasis, ledgerPointCollector);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        for (NormalizedTransaction.Flow flow : principalInflows) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            PositionSnapshot before = snapshot(position);
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
        bucket.clearAll();
        asyncLifecycleBuckets.remove(transaction.getCorrelationId());
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
        CarryTransfer carry = removeFromPosition(flow, position);
        asyncLifecycleBuckets
                .computeIfAbsent(transaction.getCorrelationId(), ignored -> new AsyncLifecycleBucket())
                .add(assetKey(transaction, flow).assetIdentity(), carry);
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
        CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(assetIdentity, flow.getQuantityDelta().abs());
        if (sameAssetCarry != null) {
            restoreToPosition(
                    sameAssetCarry.quantity(),
                    position,
                    sameAssetCarry.costBasisUsd(),
                    sameAssetCarry.uncoveredQuantity(),
                    sameAssetCarry.avco()
            );
            if (bucket.isEmpty()) {
                asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            }
            return;
        }

        BigDecimal remainingCost = bucket.remainingCostBasisUsd();
        if (remainingCost.signum() <= 0) {
            applyUnknownTransfer(flow, position);
            return;
        }
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(remainingCost, quantity);
        bucket.clearAll();
        restoreToPosition(flow, position, avco, remainingCost);
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
            LedgerPointCollector ledgerPointCollector
    ) {
        if (isFamilyEquivalentCustodyTransfer(transaction, flow)) {
            ContinuityBucket bucket = continuityBuckets.computeIfAbsent(
                    continuityKey(transaction, flow),
                    ignored -> new ContinuityBucket()
            );
            if (flow.getQuantityDelta().signum() < 0) {
                moveToContinuityBucket(flow, position, bucket);
                return AssetLedgerPoint.BasisEffect.REALLOCATE_OUT;
            }
            restoreFromContinuityBucket(flow, position, bucket);
            return AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
        }
        if (isBucketOutbound(transaction, flow)) {
            moveToContinuityBucket(
                    flow,
                    position,
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
            CarryTransfer carry = removeFromPosition(flow, position);
            Deque<CarryTransfer> queue = pendingTransfers.computeIfAbsent(transferKey, ignored -> new ArrayDeque<>());
            if (!queue.isEmpty() && queue.peekFirst().pendingInbound()) {
                CarryTransfer pendingInbound = queue.removeFirst();
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
        if (queue != null && !queue.isEmpty() && !queue.peekFirst().pendingInbound()) {
            CarryTransfer carry = queue.removeFirst();
            restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    carry.costBasisUsd(),
                    carry.uncoveredQuantity(),
                    carry.avco()
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

    private void moveToContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = removeFromPosition(flow, position);
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
        BigDecimal coveredResolvedQuantity = carry.coveredQuantity().min(pendingInbound.quantity());
        destination.uncoveredQuantity = nonNegative(destination.uncoveredQuantity.subtract(coveredResolvedQuantity, MC));
        destination.totalCostBasisUsd = destination.totalCostBasisUsd.add(carry.costBasisUsd());
        recomputePerWalletAvco(destination);
        if (carry.avco() != null && carry.uncoveredQuantity().signum() == 0) {
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
            String bridgeFamilyIdentity = bridgeFamilyIdentity(transaction, flow);
            if (bridgeFamilyIdentity != null) {
                return "bridge-family:" + transaction.getCorrelationId() + ":" + bridgeFamilyIdentity;
            }
        }

        String quantityKey = flow.getQuantityDelta().abs().stripTrailingZeros().toPlainString();
        String assetKey = continuityIdentity(transaction, flow);
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
            return "corr:" + transaction.getCorrelationId() + ":" + assetKey + ":" + quantityKey;
        }
        if (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()) {
            return "tx:" + transaction.getTxHash() + ":" + assetKey + ":" + quantityKey;
        }
        return null;
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

    private record AsyncLifecycleBucket(Map<String, Deque<CarryTransfer>> carriesByAsset) {

        private AsyncLifecycleBucket() {
            this(new LinkedHashMap<>());
        }

        private void add(String assetIdentity, CarryTransfer carry) {
            carriesByAsset.computeIfAbsent(assetIdentity, ignored -> new ArrayDeque<>()).addLast(carry);
        }

        private CarryTransfer takeSameAssetCarry(String assetIdentity, BigDecimal requestedQuantity) {
            Deque<CarryTransfer> queue = carriesByAsset.get(assetIdentity);
            if (queue == null || queue.isEmpty()) {
                return null;
            }
            CarryTransfer carry = queue.removeFirst();
            BigDecimal carryQuantity = carry.quantity();
            if (carryQuantity.compareTo(requestedQuantity) > 0) {
                BigDecimal usedCoveredQuantity = requestedQuantity.min(carry.coveredQuantity());
                BigDecimal usedUncoveredQuantity = nonNegative(requestedQuantity.subtract(usedCoveredQuantity, MC));
                BigDecimal remainingCoveredQuantity = nonNegative(carry.coveredQuantity().subtract(usedCoveredQuantity, MC));
                BigDecimal remainingUncoveredQuantity = nonNegative(carry.uncoveredQuantity().subtract(usedUncoveredQuantity, MC));
                BigDecimal usedCost = carry.avco() == null
                        ? BigDecimal.ZERO
                        : usedCoveredQuantity.multiply(carry.avco(), MC);
                BigDecimal remainingQuantity = carryQuantity.subtract(requestedQuantity, MC);
                BigDecimal remainingCost = carry.costBasisUsd().subtract(usedCost, MC);
                queue.addFirst(new CarryTransfer(
                        remainingQuantity,
                        remainingCoveredQuantity,
                        remainingUncoveredQuantity,
                        remainingCost,
                        carry.avco(),
                        carry.pendingInbound(),
                        carry.assetKey()
                ));
                if (queue.isEmpty()) {
                    carriesByAsset.remove(assetIdentity);
                }
                return new CarryTransfer(
                        requestedQuantity,
                        usedCoveredQuantity,
                        usedUncoveredQuantity,
                        usedCost,
                        carry.avco(),
                        false,
                        carry.assetKey()
                );
            }
            if (queue.isEmpty()) {
                carriesByAsset.remove(assetIdentity);
            }
            return carry;
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

        private void clearAll() {
            carriesByAsset.clear();
        }

        private boolean isEmpty() {
            return carriesByAsset.isEmpty();
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
        private final List<AssetLedgerPoint> points;
        private final Instant createdAt;

        private LedgerPointCollector(List<AssetLedgerPoint> points, Instant createdAt) {
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
            point.setId(transaction.getId() + ":" + flowIndex + ":" + sequence);
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
