package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.application.pricing.domain.PriceQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies and mutates canonical normalized rows for pricing-stage writes.
 */
@Component
public class PricingResultMapper {

    public NormalizedTransaction copy(NormalizedTransaction transaction) {
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
        copy.setCounterpartyAddress(transaction.getCounterpartyAddress());
        copy.setCounterpartyType(transaction.getCounterpartyType());
        copy.setCounterpartyResolutionState(transaction.getCounterpartyResolutionState());
        copy.setCounterpartyResolutionEvidence(transaction.getCounterpartyResolutionEvidence());
        copy.setExcludedFromAccounting(transaction.getExcludedFromAccounting());
        copy.setAccountingExclusionReason(transaction.getAccountingExclusionReason());
        copy.setProtocolName(transaction.getProtocolName());
        copy.setProtocolVersion(transaction.getProtocolVersion());
        copy.setProtocolResolutionState(transaction.getProtocolResolutionState());
        copy.setProtocolResolutionEvidence(transaction.getProtocolResolutionEvidence());
        copy.setMetadata(copyDocument(transaction.getMetadata()));
        copy.setClarificationEvidence(copyDocument(transaction.getClarificationEvidence()));
        copy.setClarificationAttempts(transaction.getClarificationAttempts());
        copy.setFullReceiptClarificationAttempts(transaction.getFullReceiptClarificationAttempts());
        copy.setPricingAttempts(transaction.getPricingAttempts());
        copy.setStatAttempts(transaction.getStatAttempts());
        copy.setCreatedAt(transaction.getCreatedAt());
        copy.setUpdatedAt(transaction.getUpdatedAt());
        copy.setConfirmedAt(transaction.getConfirmedAt());
        copy.setClientId(transaction.getClientId());
        copy.setMissingDataReasons(new ArrayList<>(transaction.getMissingDataReasons() == null
                ? List.of()
                : transaction.getMissingDataReasons()));

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows() == null ? List.<NormalizedTransaction.Flow>of() : transaction.getFlows()) {
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
            flows.add(flowCopy);
        }
        copy.setFlows(flows);
        return copy;
    }

    private org.bson.Document copyDocument(org.bson.Document document) {
        return document == null ? null : new org.bson.Document(document);
    }

    public void applyResolvedQuote(
            NormalizedTransaction.Flow flow,
            PriceQuote quote
    ) {
        BigDecimal unitPriceUsd = Decimal128Support.normalize(quote.unitPriceUsd());
        flow.setUnitPriceUsd(unitPriceUsd);
        flow.setValueUsd(flowValue(flow.getQuantityDelta(), unitPriceUsd));
        flow.setPriceSource(quote.source());
    }

    public void markUnknown(NormalizedTransaction.Flow flow) {
        flow.setUnitPriceUsd(null);
        flow.setValueUsd(null);
        flow.setPriceSource(PriceSource.UNKNOWN);
    }

    public void finalizePricing(
            NormalizedTransaction transaction,
            boolean hasUnresolvedPrice,
            Instant now
    ) {
        boolean unresolvedPrice = hasUnresolvedPrice || PriceableFlowPolicy.hasReplayRelevantUnresolvedPrice(transaction);
        Set<String> reasons = new LinkedHashSet<>(transaction.getMissingDataReasons() == null
                ? List.of()
                : transaction.getMissingDataReasons());
        reasons.remove(PriceableFlowPolicy.PRICING_EXECUTION_FAILED_REASON);
        reasons.remove(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON);
        if (unresolvedPrice) {
            reasons.add(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON);
        }
        transaction.setMissingDataReasons(new ArrayList<>(reasons));
        transaction.setStatus(NormalizedTransactionStatus.PENDING_STAT);
        transaction.setUpdatedAt(now);
        transaction.setPricingAttempts(safeIncrement(transaction.getPricingAttempts()));
    }

    public void markFailedAttempt(NormalizedTransaction transaction, Instant now) {
        Set<String> reasons = new LinkedHashSet<>(transaction.getMissingDataReasons() == null
                ? List.of()
                : transaction.getMissingDataReasons());
        reasons.add(PriceableFlowPolicy.PRICING_EXECUTION_FAILED_REASON);
        transaction.setMissingDataReasons(new ArrayList<>(reasons));
        transaction.setPricingAttempts(safeIncrement(transaction.getPricingAttempts()));
        transaction.setUpdatedAt(now);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
    }

    private BigDecimal flowValue(BigDecimal quantityDelta, BigDecimal unitPriceUsd) {
        if (quantityDelta == null || unitPriceUsd == null) {
            return null;
        }
        return Decimal128Support.normalize(quantityDelta.abs().multiply(unitPriceUsd));
    }

    private int safeIncrement(Integer value) {
        return Math.max(0, value == null ? 0 : value) + 1;
    }
}
