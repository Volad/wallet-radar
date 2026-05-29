package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class ReplayFlowSupport {

    private final GenericFlowReplayEngine genericFlowReplayEngine;

    public ReplayFlowSupport(GenericFlowReplayEngine genericFlowReplayEngine) {
        this.genericFlowReplayEngine = genericFlowReplayEngine;
    }

    public List<IndexedFlow> indexedFlows(NormalizedTransaction transaction) {
        List<IndexedFlow> indexed = new ArrayList<>();
        if (transaction == null || transaction.getFlows() == null) {
            return indexed;
        }
        for (int index = 0; index < transaction.getFlows().size(); index++) {
            indexed.add(new IndexedFlow(index, transaction.getFlows().get(index)));
        }
        return indexed;
    }

    public void applyBuy(NormalizedTransaction.Flow flow, PositionState position) {
        genericFlowReplayEngine.applyBuy(flow, position);
    }

    public void applyBuyWithAcquisitionCost(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal acquisitionCostUsd
    ) {
        genericFlowReplayEngine.applyBuyWithAcquisitionCost(flow, position, acquisitionCostUsd);
    }

    public void applySell(NormalizedTransaction.Flow flow, PositionState position) {
        genericFlowReplayEngine.applySell(flow, position);
    }

    public void applyFee(NormalizedTransaction.Flow flow, PositionState position) {
        genericFlowReplayEngine.applyFee(flow, position);
    }

    public CarryTransfer removeFromPosition(NormalizedTransaction.Flow flow, PositionState position) {
        return genericFlowReplayEngine.removeFromPosition(flow, position);
    }

    public void restoreToPosition(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal avco,
            BigDecimal cost
    ) {
        genericFlowReplayEngine.restoreToPosition(flow, position, avco, cost);
    }

    public void restoreToPosition(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco
    ) {
        genericFlowReplayEngine.restoreToPosition(quantity, position, cost, uncoveredQuantity, avco);
    }

    public void applyUnknownTransfer(NormalizedTransaction.Flow flow, PositionState position) {
        genericFlowReplayEngine.applyUnknownTransfer(flow, position);
    }

    public void applyInboundShortfallSpotFallback(
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot before
    ) {
        genericFlowReplayEngine.applyInboundShortfallSpotFallback(flow, position, before);
    }

    public void applyAuthoritativeLateInboundCarryBasis(
            PositionState destination,
            BigDecimal provisionalBasisUsd,
            BigDecimal carryBasisUsd
    ) {
        genericFlowReplayEngine.applyAuthoritativeLateInboundCarryBasis(
                destination,
                provisionalBasisUsd,
                carryBasisUsd
        );
    }

    public BigDecimal materializePendingInbound(NormalizedTransaction.Flow flow, PositionState position) {
        return genericFlowReplayEngine.materializePendingInbound(flow, position);
    }

    public java.util.Optional<BigDecimal> materializePendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            boolean permitUncoveredFallback
    ) {
        return genericFlowReplayEngine.materializePendingInbound(
                transaction,
                flow,
                position,
                permitUncoveredFallback
        );
    }

    public void applyPeggedNativeSpotFallback(
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot before
    ) {
        genericFlowReplayEngine.applyPeggedNativeSpotFallback(flow, position, before);
    }

    public void applySponsoredGasIn(NormalizedTransaction.Flow flow, PositionState position) {
        genericFlowReplayEngine.applySponsoredGasIn(flow, position);
    }

    public void recomputePerWalletAvco(PositionState position) {
        genericFlowReplayEngine.recomputePerWalletAvco(position);
    }

    public void purgeOrphanBasisWhenEmpty(PositionState position) {
        genericFlowReplayEngine.purgeOrphanBasisWhenEmpty(position);
    }

    public void resolveTemporaryUnresolved(PositionState position) {
        genericFlowReplayEngine.resolveTemporaryUnresolved(position);
    }

    public PositionSnapshot snapshot(PositionState position) {
        return new PositionSnapshot(
                position.quantity(),
                position.perWalletAvco(),
                position.totalCostBasisUsd(),
                position.totalGasPaidUsd(),
                position.totalRealisedPnlUsd(),
                position.quantityShortfall(),
                position.uncoveredQuantity(),
                position.hasIncompleteHistory(),
                position.hasUnresolvedFlags(),
                position.unresolvedFlagCount()
        );
    }

    public Instant laterOf(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    public int flowIndex(NormalizedTransaction transaction, NormalizedTransaction.Flow target) {
        if (transaction == null || transaction.getFlows() == null || target == null) {
            return -1;
        }
        for (int index = 0; index < transaction.getFlows().size(); index++) {
            if (transaction.getFlows().get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    public FlowRef flowRef(NormalizedTransaction transaction, int flowIndex) {
        return FlowRef.of(transaction.getId() + ":" + flowIndex);
    }

    public NormalizedTransaction.Flow asTransferFlow(NormalizedTransaction.Flow original) {
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

    public NormalizedTransaction.Flow copyFlowWithQuantity(
            NormalizedTransaction.Flow original,
            BigDecimal quantity
    ) {
        NormalizedTransaction.Flow copy = new NormalizedTransaction.Flow();
        copy.setRole(original.getRole());
        copy.setAssetContract(original.getAssetContract());
        copy.setAssetSymbol(original.getAssetSymbol());
        copy.setQuantityDelta(original.getQuantityDelta().signum() < 0 ? quantity.negate() : quantity);
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

    public NormalizedTransaction copyTransaction(NormalizedTransaction transaction) {
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
        copy.setProtocolName(transaction.getProtocolName());
        copy.setProtocolVersion(transaction.getProtocolVersion());
        copy.setProtocolResolutionState(transaction.getProtocolResolutionState());
        copy.setProtocolResolutionEvidence(transaction.getProtocolResolutionEvidence());
        copy.setMetadata(copyDocument(transaction.getMetadata()));
        copy.setClarificationEvidence(copyDocument(transaction.getClarificationEvidence()));
        copy.setCorrelationId(transaction.getCorrelationId());
        copy.setMatchedCounterparty(transaction.getMatchedCounterparty());
        copy.setCounterpartyAddress(transaction.getCounterpartyAddress());
        copy.setCounterpartyType(transaction.getCounterpartyType());
        copy.setCounterpartyResolutionState(transaction.getCounterpartyResolutionState());
        copy.setCounterpartyResolutionEvidence(transaction.getCounterpartyResolutionEvidence());
        copy.setContinuityCandidate(transaction.getContinuityCandidate());
        copy.setExcludedFromAccounting(transaction.getExcludedFromAccounting());
        copy.setAccountingExclusionReason(transaction.getAccountingExclusionReason());
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
        copy.setFlows(new java.util.ArrayList<>());
        if (transaction.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                NormalizedTransaction.Flow flowCopy = new NormalizedTransaction.Flow();
                flowCopy.setRole(flow.getRole());
                flowCopy.setAssetContract(flow.getAssetContract());
                flowCopy.setAssetSymbol(flow.getAssetSymbol());
                flowCopy.setQuantityDelta(flow.getQuantityDelta());
                flowCopy.setUnitPriceUsd(flow.getUnitPriceUsd());
                flowCopy.setValueUsd(flow.getValueUsd());
                flowCopy.setPriceSource(flow.getPriceSource());
                flowCopy.setIsInferred(flow.getIsInferred());
                flowCopy.setInferenceReason(flow.getInferenceReason());
                flowCopy.setConfidence(flow.getConfidence());
                flowCopy.setAvcoAtTimeOfSale(flow.getAvcoAtTimeOfSale());
                flowCopy.setRealisedPnlUsd(flow.getRealisedPnlUsd());
                flowCopy.setLogIndex(flow.getLogIndex());
                flowCopy.setCounterpartyAddress(flow.getCounterpartyAddress());
                flowCopy.setCounterpartyType(flow.getCounterpartyType());
                flowCopy.setAccountRef(flow.getAccountRef());
                copy.getFlows().add(flowCopy);
            }
        }
        return copy;
    }

    private org.bson.Document copyDocument(org.bson.Document document) {
        return document == null ? null : new org.bson.Document(document);
    }

    public AssetLedgerPoint.BasisEffect defaultBasisEffect(NormalizedTransaction.Flow flow) {
        return switch (flow.getRole()) {
            case BUY -> AssetLedgerPoint.BasisEffect.ACQUIRE;
            case SELL -> AssetLedgerPoint.BasisEffect.DISPOSE;
            case FEE -> AssetLedgerPoint.BasisEffect.GAS_ONLY;
            case TRANSFER -> flow.getQuantityDelta().signum() < 0
                    ? AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                    : AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
        };
    }

    public AssetLedgerPoint.BasisEffect continuityBasisEffect(
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

    public AssetLedgerPoint.BasisEffect routeSettlementBasisEffect(NormalizedTransaction.Flow flow) {
        return flow.getQuantityDelta().signum() < 0
                ? AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                : AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
    }
}
