package com.walletradar.costbasis.application;

import com.walletradar.accounting.support.BridgeAssetFamilySupport;
import com.walletradar.costbasis.domain.AssetPosition;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.costbasis.domain.ReconciliationStatus;
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
    private final AssetPositionRepository assetPositionRepository;

    public int replayConfirmed() {
        List<NormalizedTransaction> ordered = confirmedReplayQueryService.loadOrderedConfirmed();
        Map<AssetKey, PositionState> positions = new LinkedHashMap<>();
        Map<AssetKey, ContinuityBucket> continuityBuckets = new LinkedHashMap<>();
        Map<String, Deque<CarryTransfer>> pendingTransfers = new LinkedHashMap<>();
        Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets = new LinkedHashMap<>();
        Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets = new LinkedHashMap<>();
        List<NormalizedTransaction> updatedTransactions = new ArrayList<>();
        Instant now = Instant.now();

        for (NormalizedTransaction transaction : ordered) {
            NormalizedTransaction replayed = copyTransaction(transaction);
            if (replayed.getType() == NormalizedTransactionType.LENDING_LOOP_REBALANCE) {
                applyEulerLoopRebalance(replayed, positions);
                updatedTransactions.add(replayed);
                continue;
            }
            if (replayed.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT) {
                applyAsyncLpExitSettlement(replayed, positions, asyncLifecycleBuckets);
                updatedTransactions.add(replayed);
                continue;
            }
            for (NormalizedTransaction.Flow flow : replayed.getFlows()) {
                applyFlow(
                        replayed,
                        flow,
                        positions,
                        continuityBuckets,
                        pendingTransfers,
                        asyncLifecycleBuckets,
                        asyncSpotOrderBuckets
                );
            }
            updatedTransactions.add(replayed);
        }

        resolveUnmatchedIncomingTransfers(positions, pendingTransfers);

        assetPositionRepository.deleteAll();
        assetPositionRepository.saveAll(materializePositions(positions, now));
        normalizedTransactionRepository.saveAll(updatedTransactions);
        return updatedTransactions.size();
    }

    private void applyFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions,
            Map<AssetKey, ContinuityBucket> continuityBuckets,
            Map<String, Deque<CarryTransfer>> pendingTransfers,
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets,
            Map<String, AsyncSpotOrderBucket> asyncSpotOrderBuckets
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        if (isAsyncSpotOrderRequestSell(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            applyAsyncSpotOrderRequest(transaction, flow, position, asyncSpotOrderBuckets);
            return;
        }
        if (isAsyncSpotOrderSettlementBuy(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            applyAsyncSpotOrderSettlement(transaction, flow, position, asyncSpotOrderBuckets);
            return;
        }
        if (isAsyncLifecycleRequestOutbound(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            applyAsyncLifecycleRequest(transaction, flow, position, asyncLifecycleBuckets);
            return;
        }
        if (isAsyncLifecycleSettlementInbound(transaction, flow)) {
            AssetKey assetKey = assetKey(transaction, flow);
            PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
            position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
            applyAsyncLifecycleSettlement(transaction, flow, position, asyncLifecycleBuckets);
            return;
        }
        if (shouldIgnoreLpReceiptMarker(transaction, flow)) {
            return;
        }
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());

        if (shouldTreatAsContinuityTransfer(transaction, flow)) {
            applyTransfer(transaction, asTransferFlow(flow), position, continuityBuckets, pendingTransfers);
            return;
        }

        switch (flow.getRole()) {
            case BUY -> applyBuy(flow, position);
            case SELL -> applySell(flow, position);
            case FEE -> applyFee(flow, position);
            case TRANSFER -> applyTransfer(transaction, flow, position, continuityBuckets, pendingTransfers);
        }
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
            Map<String, AsyncLifecycleBucket> asyncLifecycleBuckets
    ) {
        if (transaction.getCorrelationId() == null || transaction.getCorrelationId().isBlank()) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                applyFallbackSettlementFlow(transaction, flow, positions);
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

            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFee(flow, position);
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.TRANSFER && flow.getQuantityDelta().signum() > 0) {
                CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(assetKey.assetIdentity(), flow.getQuantityDelta().abs());
                if (sameAssetCarry != null) {
                    restoreToPosition(flow, position, sameAssetCarry.avco(), sameAssetCarry.costBasisUsd());
                } else {
                    principalInflows.add(flow);
                }
                continue;
            }
            applyUnknownTransfer(flow, position);
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
                PositionState position = positions.computeIfAbsent(assetKey(transaction, flow), ignored -> new PositionState(assetKey(transaction, flow)));
                applyUnknownTransfer(flow, position);
            }
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        if (allSameAsset(principalInflows, transaction)) {
            allocateSettlementByQuantity(transaction, principalInflows, positions, remainingCostBasis);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        if (allHaveKnownPrices(principalInflows)) {
            allocateSettlementByKnownValue(transaction, principalInflows, positions, remainingCostBasis);
            bucket.clearAll();
            asyncLifecycleBuckets.remove(transaction.getCorrelationId());
            return;
        }

        for (NormalizedTransaction.Flow flow : principalInflows) {
            PositionState position = positions.computeIfAbsent(assetKey(transaction, flow), ignored -> new PositionState(assetKey(transaction, flow)));
            applyUnknownTransfer(flow, position);
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
            restoreToPosition(flow, position, sameAssetCarry.avco(), sameAssetCarry.costBasisUsd());
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
            Map<AssetKey, PositionState> positions
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
                applyEulerRebalanceFlowAsUnknown(transaction, flow, positions);
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
                applyEulerRebalanceFlowAsUnknown(transaction, flow, positions);
            }
            return;
        }

        AssetKey sourceAssetKey = assetKey(transaction, sourceFlow);
        PositionState sourcePosition = positions.computeIfAbsent(sourceAssetKey, ignored -> new PositionState(sourceAssetKey));
        sourcePosition.lastEventTimestamp = laterOf(sourcePosition.lastEventTimestamp, transaction.getBlockTimestamp());
        CarryTransfer carry = removeFromPosition(sourceFlow, sourcePosition);

        CarryTransfer remainingCarry = carry;
        for (NormalizedTransaction.Flow refund : sameAssetRefunds) {
            sourcePosition.lastEventTimestamp = laterOf(sourcePosition.lastEventTimestamp, transaction.getBlockTimestamp());
            remainingCarry = restoreEulerRebalanceRefund(refund, sourcePosition, remainingCarry);
        }

        AssetKey replacementAssetKey = assetKey(transaction, replacementFlow);
        PositionState replacementPosition = positions.computeIfAbsent(replacementAssetKey, ignored -> new PositionState(replacementAssetKey));
        replacementPosition.lastEventTimestamp = laterOf(replacementPosition.lastEventTimestamp, transaction.getBlockTimestamp());
        restoreEulerRebalanceReplacement(replacementFlow, replacementPosition, remainingCarry);

        for (NormalizedTransaction.Flow fee : fees) {
            AssetKey feeAssetKey = assetKey(transaction, fee);
            PositionState feePosition = positions.computeIfAbsent(feeAssetKey, ignored -> new PositionState(feeAssetKey));
            feePosition.lastEventTimestamp = laterOf(feePosition.lastEventTimestamp, transaction.getBlockTimestamp());
            applyFee(fee, feePosition);
        }
    }

    private void applyEulerRebalanceFlowAsUnknown(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions
    ) {
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
        if (flow.getRole() == NormalizedLegRole.FEE) {
            applyFee(flow, position);
            return;
        }
        applyUnknownTransfer(flow, position);
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
        BigDecimal avco = carry.avco();
        BigDecimal refundCost = avco == null ? BigDecimal.ZERO : effectiveQuantity.multiply(avco, MC);
        restoreToPosition(refundFlow, position, avco, refundCost);
        return new CarryTransfer(
                nonNegative(carry.quantity().subtract(effectiveQuantity, MC)),
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
        if (carry == null || carry.costBasisUsd() == null || carry.costBasisUsd().signum() <= 0) {
            applyUnknownTransfer(replacementFlow, position);
            return;
        }
        BigDecimal quantity = replacementFlow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(carry.costBasisUsd(), quantity);
        restoreToPosition(replacementFlow, position, avco, carry.costBasisUsd());
    }

    private void applyBuy(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (hasKnownPrice(flow)) {
            BigDecimal acquisitionCost = quantity.multiply(flow.getUnitPriceUsd(), MC);
            position.totalCostBasisUsd = position.totalCostBasisUsd.add(acquisitionCost);
            position.quantity = position.quantity.add(quantity);
            position.perWalletAvco = safeDivide(position.totalCostBasisUsd, position.quantity);
            return;
        }
        position.quantity = position.quantity.add(quantity);
        position.hasIncompleteHistory = true;
        position.hasUnresolvedFlags = true;
        position.unresolvedFlagCount++;
    }

    private void applySell(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (position.perWalletAvco != null) {
            flow.setAvcoAtTimeOfSale(position.perWalletAvco);
            BigDecimal relievedCost = quantity.multiply(position.perWalletAvco, MC);
            position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(relievedCost, MC));
            if (hasKnownPrice(flow)) {
                BigDecimal realised = flow.getUnitPriceUsd().subtract(position.perWalletAvco, MC).multiply(quantity, MC);
                flow.setRealisedPnlUsd(realised);
                position.totalRealisedPnlUsd = position.totalRealisedPnlUsd.add(realised);
            } else {
                position.hasIncompleteHistory = true;
                position.hasUnresolvedFlags = true;
                position.unresolvedFlagCount++;
            }
        } else {
            position.hasIncompleteHistory = true;
            position.hasUnresolvedFlags = true;
            position.unresolvedFlagCount++;
        }
        position.quantity = nonNegative(position.quantity.subtract(quantity, MC));
        position.perWalletAvco = position.quantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd, position.quantity);
    }

    private void applyFee(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        position.quantity = nonNegative(position.quantity.subtract(quantity, MC));
        if (hasKnownPrice(flow)) {
            BigDecimal feeCost = quantity.multiply(flow.getUnitPriceUsd(), MC);
            position.totalGasPaidUsd = position.totalGasPaidUsd.add(feeCost);
            if (position.perWalletAvco != null) {
                position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(
                        quantity.multiply(position.perWalletAvco, MC),
                        MC
                ));
            }
        } else {
            position.hasIncompleteHistory = true;
            position.hasUnresolvedFlags = true;
            position.unresolvedFlagCount++;
        }
        position.perWalletAvco = position.quantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd, position.quantity);
    }

    private void applyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            Map<AssetKey, ContinuityBucket> continuityBuckets,
            Map<String, Deque<CarryTransfer>> pendingTransfers
    ) {
        if (isBucketOutbound(transaction, flow)) {
            moveToContinuityBucket(flow, position, continuityBuckets.computeIfAbsent(assetKey(transaction, flow), ignored -> new ContinuityBucket()));
            return;
        }
        if (isBucketInbound(transaction, flow)) {
            restoreFromContinuityBucket(flow, position, continuityBuckets.computeIfAbsent(assetKey(transaction, flow), ignored -> new ContinuityBucket()));
            return;
        }

        String transferKey = transferKey(transaction, flow);
        if (transferKey == null) {
            applyUnknownTransfer(flow, position);
            return;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = removeFromPosition(flow, position);
            pendingTransfers.computeIfAbsent(transferKey, ignored -> new ArrayDeque<>()).addLast(carry);
            return;
        }

        Deque<CarryTransfer> queue = pendingTransfers.get(transferKey);
        if (queue != null && !queue.isEmpty()) {
            CarryTransfer carry = queue.removeFirst();
            restoreToPosition(flow, position, carry.avco(), carry.costBasisUsd());
            if (queue.isEmpty()) {
                pendingTransfers.remove(transferKey);
            }
            return;
        }

        pendingTransfers.computeIfAbsent(transferKey, ignored -> new ArrayDeque<>())
                .addLast(CarryTransfer.pendingInbound(flow.getQuantityDelta().abs(), position.assetKey));
    }

    private void moveToContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = removeFromPosition(flow, position);
        bucket.quantity = bucket.quantity.add(carry.quantity());
        bucket.totalCostBasisUsd = bucket.totalCostBasisUsd.add(carry.costBasisUsd());
    }

    private void restoreFromContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (bucket.quantity.signum() == 0 || bucket.totalCostBasisUsd.signum() == 0) {
            applyUnknownTransfer(flow, position);
            return;
        }
        BigDecimal avco = safeDivide(bucket.totalCostBasisUsd, bucket.quantity);
        BigDecimal cost = quantity.multiply(avco, MC);
        bucket.quantity = nonNegative(bucket.quantity.subtract(quantity, MC));
        bucket.totalCostBasisUsd = nonNegative(bucket.totalCostBasisUsd.subtract(cost, MC));
        restoreToPosition(flow, position, avco, cost);
    }

    private CarryTransfer removeFromPosition(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = position.perWalletAvco;
        BigDecimal cost = avco == null ? BigDecimal.ZERO : quantity.multiply(avco, MC);
        position.quantity = nonNegative(position.quantity.subtract(quantity, MC));
        position.totalCostBasisUsd = nonNegative(position.totalCostBasisUsd.subtract(cost, MC));
        position.perWalletAvco = position.quantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd, position.quantity);
        if (avco == null) {
            position.hasIncompleteHistory = true;
            position.hasUnresolvedFlags = true;
            position.unresolvedFlagCount++;
        }
        return new CarryTransfer(quantity, cost, avco, false, position.assetKey);
    }

    private void restoreToPosition(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal avco,
            BigDecimal cost
    ) {
        position.quantity = position.quantity.add(flow.getQuantityDelta().abs());
        position.totalCostBasisUsd = position.totalCostBasisUsd.add(cost);
        position.perWalletAvco = avco != null
                ? safeDivide(position.totalCostBasisUsd, position.quantity)
                : position.perWalletAvco;
        if (avco == null) {
            position.hasIncompleteHistory = true;
            position.hasUnresolvedFlags = true;
            position.unresolvedFlagCount++;
        }
    }

    private void applyUnknownTransfer(NormalizedTransaction.Flow flow, PositionState position) {
        if (flow.getQuantityDelta().signum() > 0) {
            position.quantity = position.quantity.add(flow.getQuantityDelta().abs());
        } else {
            position.quantity = nonNegative(position.quantity.subtract(flow.getQuantityDelta().abs(), MC));
        }
        position.hasIncompleteHistory = true;
        position.hasUnresolvedFlags = true;
        position.unresolvedFlagCount++;
    }

    private void applyFallbackSettlementFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        position.lastEventTimestamp = laterOf(position.lastEventTimestamp, transaction.getBlockTimestamp());
        if (flow.getRole() == NormalizedLegRole.FEE) {
            applyFee(flow, position);
            return;
        }
        applyUnknownTransfer(flow, position);
    }

    private boolean allSameAsset(
            List<NormalizedTransaction.Flow> flows,
            NormalizedTransaction transaction
    ) {
        if (flows.isEmpty()) {
            return true;
        }
        String first = assetKey(transaction, flows.getFirst()).assetIdentity();
        for (int index = 1; index < flows.size(); index++) {
            if (!first.equals(assetKey(transaction, flows.get(index)).assetIdentity())) {
                return false;
            }
        }
        return true;
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
            BigDecimal totalCostBasisUsd
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
            restoreSettlementPosition(transaction, flow, positions, allocatedCost);
        }
    }

    private void allocateSettlementByKnownValue(
            NormalizedTransaction transaction,
            List<NormalizedTransaction.Flow> flows,
            Map<AssetKey, PositionState> positions,
            BigDecimal totalCostBasisUsd
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
            restoreSettlementPosition(transaction, flow, positions, allocatedCost);
        }
    }

    private void restoreSettlementPosition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions,
            BigDecimal allocatedCost
    ) {
        AssetKey assetKey = assetKey(transaction, flow);
        PositionState position = positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(allocatedCost, quantity);
        restoreToPosition(flow, position, avco, allocatedCost);
    }

    private void resolveUnmatchedIncomingTransfers(
            Map<AssetKey, PositionState> positions,
            Map<String, Deque<CarryTransfer>> pendingTransfers
    ) {
        for (Deque<CarryTransfer> queue : pendingTransfers.values()) {
            for (CarryTransfer carry : queue) {
                if (!carry.pendingInbound()) {
                    continue;
                }
                PositionState position = positions.computeIfAbsent(carry.assetKey(), ignored -> new PositionState(carry.assetKey()));
                position.quantity = position.quantity.add(carry.quantity());
                position.hasIncompleteHistory = true;
                position.hasUnresolvedFlags = true;
                position.unresolvedFlagCount++;
            }
        }
    }

    private List<AssetPosition> materializePositions(
            Map<AssetKey, PositionState> positions,
            Instant now
    ) {
        List<AssetPosition> assetPositions = new ArrayList<>();
        for (PositionState state : positions.values()) {
            AssetPosition position = new AssetPosition();
            position.setId(state.assetKey.id());
            position.setWalletAddress(state.assetKey.walletAddress());
            position.setNetworkId(state.assetKey.networkId());
            position.setAssetContract(state.assetKey.assetContract());
            position.setAssetSymbol(state.assetKey.assetSymbol());
            position.setQuantity(state.quantity);
            position.setPerWalletAvco(state.perWalletAvco);
            position.setTotalCostBasisUsd(state.totalCostBasisUsd);
            position.setTotalGasPaidUsd(state.totalGasPaidUsd);
            position.setTotalRealisedPnlUsd(state.totalRealisedPnlUsd);
            position.setHasIncompleteHistory(state.hasIncompleteHistory);
            position.setHasUnresolvedFlags(state.hasUnresolvedFlags);
            position.setUnresolvedFlagCount(state.unresolvedFlagCount);
            position.setLastEventTimestamp(state.lastEventTimestamp);
            position.setLastCalculatedAt(now);
            position.setReconciliationStatus(ReconciliationStatus.NOT_APPLICABLE);
            assetPositions.add(position);
        }
        return assetPositions;
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
        String assetKey = assetKey(transaction, flow).assetIdentity();
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

    private boolean sameAssetIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow left,
            NormalizedTransaction.Flow right
    ) {
        return assetKey(transaction, left).assetIdentity().equals(assetKey(transaction, right).assetIdentity());
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
                BigDecimal ratio = safeDivide(requestedQuantity, carryQuantity);
                BigDecimal usedCost = carry.costBasisUsd().multiply(ratio, MC);
                BigDecimal remainingQuantity = carryQuantity.subtract(requestedQuantity, MC);
                BigDecimal remainingCost = carry.costBasisUsd().subtract(usedCost, MC);
                queue.addFirst(new CarryTransfer(
                        remainingQuantity,
                        remainingCost,
                        carry.avco(),
                        carry.pendingInbound(),
                        carry.assetKey()
                ));
                if (queue.isEmpty()) {
                    carriesByAsset.remove(assetIdentity);
                }
                return new CarryTransfer(requestedQuantity, usedCost, carry.avco(), false, carry.assetKey());
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
        String assetContract = normalizeContract(flow.getAssetContract());
        String assetSymbol = normalizeSymbol(flow.getAssetSymbol());
        String assetIdentity = assetContract != null ? assetContract : "SYMBOL:" + assetSymbol;
        return new AssetKey(
                transaction.getWalletAddress(),
                transaction.getNetworkId(),
                assetContract != null ? assetContract : assetIdentity,
                assetSymbol,
                assetIdentity
        );
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

    private BigDecimal nonNegative(BigDecimal value) {
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

    private static final class PositionState {
        private final AssetKey assetKey;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal perWalletAvco;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal totalGasPaidUsd = BigDecimal.ZERO;
        private BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        private boolean hasIncompleteHistory;
        private boolean hasUnresolvedFlags;
        private int unresolvedFlagCount;
        private Instant lastEventTimestamp;

        private PositionState(AssetKey assetKey) {
            this.assetKey = assetKey;
        }
    }

    private static final class ContinuityBucket {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
    }

    private record CarryTransfer(
            BigDecimal quantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            boolean pendingInbound,
            AssetKey assetKey
    ) {
        private static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey) {
            return new CarryTransfer(quantity, BigDecimal.ZERO, null, true, assetKey);
        }
    }

    private record AsyncSpotOrderCarry(
            CarryTransfer carry,
            NormalizedTransaction.Flow requestFlow
    ) {
    }
}
