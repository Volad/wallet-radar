package com.walletradar.ingestion.pipeline.onchain;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.reason.ClarificationPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Builds canonical on-chain normalized documents from classifier output.
 */
@Component
public class OnChainNormalizedTransactionBuilder {

    private final ClarificationPolicyService clarificationPolicyService;

    @Autowired
    public OnChainNormalizedTransactionBuilder(ClarificationPolicyService clarificationPolicyService) {
        this.clarificationPolicyService = clarificationPolicyService;
    }

    public OnChainNormalizedTransactionBuilder() {
        this(new ClarificationPolicyService());
    }

    public NormalizedTransaction build(
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NormalizedTransaction normalized = new NormalizedTransaction();
        applyCanonicalFields(normalized, view, classificationResult);
        normalized.setId(canonicalId(rawTransaction));
        normalized.setClarificationAttempts(clarificationAttemptBaseline(view));
        normalized.setFullReceiptClarificationAttempts(fullReceiptClarificationAttemptBaseline(view));
        normalized.setPricingAttempts(0);
        normalized.setStatAttempts(0);
        normalized.setCreatedAt(now);
        normalized.setUpdatedAt(now);
        if (classificationResult.status() == NormalizedTransactionStatus.CONFIRMED) {
            normalized.setConfirmedAt(now);
        }
        return normalized;
    }

    public NormalizedTransaction rebuildAfterClarification(
            NormalizedTransaction existing,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NormalizedTransaction normalized = new NormalizedTransaction();
        applyCanonicalFields(normalized, view, classificationResult);
        normalized.setId(existing.getId());
        normalized.setCreatedAt(existing.getCreatedAt());
        normalized.setUpdatedAt(now);
        normalized.setClarificationAttempts(clarificationAttemptBaseline(view));
        normalized.setFullReceiptClarificationAttempts(fullReceiptClarificationAttemptBaseline(view));
        normalized.setPricingAttempts(safeCounter(existing.getPricingAttempts()));
        normalized.setStatAttempts(safeCounter(existing.getStatAttempts()));
        if (normalized.getCorrelationId() == null || normalized.getCorrelationId().isBlank()) {
            normalized.setCorrelationId(existing.getCorrelationId());
        }
        if (normalized.getContinuityCandidate() == null) {
            normalized.setContinuityCandidate(existing.getContinuityCandidate());
        }
        if (normalized.getMatchedCounterparty() == null || normalized.getMatchedCounterparty().isBlank()) {
            normalized.setMatchedCounterparty(existing.getMatchedCounterparty());
        }
        if (normalized.getCounterpartyAddress() == null || normalized.getCounterpartyAddress().isBlank()) {
            normalized.setCounterpartyAddress(existing.getCounterpartyAddress());
        }
        normalized.setClientId(existing.getClientId());
        if (normalized.getStatus() == NormalizedTransactionStatus.CONFIRMED) {
            normalized.setConfirmedAt(existing.getConfirmedAt() != null ? existing.getConfirmedAt() : now);
        } else {
            normalized.setConfirmedAt(existing.getConfirmedAt());
        }
        return normalized;
    }

    public NormalizedTransaction rebuildAfterReclassification(
            NormalizedTransaction existing,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NormalizedTransaction normalized = new NormalizedTransaction();
        applyCanonicalFields(normalized, view, classificationResult);
        normalized.setId(existing.getId());
        normalized.setCreatedAt(existing.getCreatedAt());
        normalized.setUpdatedAt(now);
        normalized.setClarificationAttempts(clarificationAttemptBaseline(view));
        normalized.setFullReceiptClarificationAttempts(fullReceiptClarificationAttemptBaseline(view));
        normalized.setPricingAttempts(safeCounter(existing.getPricingAttempts()));
        normalized.setStatAttempts(safeCounter(existing.getStatAttempts()));
        if (normalized.getCorrelationId() == null || normalized.getCorrelationId().isBlank()) {
            normalized.setCorrelationId(existing.getCorrelationId());
        }
        if (normalized.getContinuityCandidate() == null) {
            normalized.setContinuityCandidate(existing.getContinuityCandidate());
        }
        if (normalized.getMatchedCounterparty() == null || normalized.getMatchedCounterparty().isBlank()) {
            normalized.setMatchedCounterparty(existing.getMatchedCounterparty());
        }
        if (normalized.getCounterpartyAddress() == null || normalized.getCounterpartyAddress().isBlank()) {
            normalized.setCounterpartyAddress(existing.getCounterpartyAddress());
        }
        normalized.setClientId(existing.getClientId());
        if (normalized.getStatus() == NormalizedTransactionStatus.CONFIRMED) {
            normalized.setConfirmedAt(existing.getConfirmedAt() != null ? existing.getConfirmedAt() : now);
        } else {
            normalized.setConfirmedAt(existing.getConfirmedAt());
        }
        return normalized;
    }

    public String canonicalId(RawTransaction rawTransaction) {
        return rawTransaction.getTxHash() + ":" + rawTransaction.getNetworkId() + ":" + rawTransaction.getWalletAddress();
    }

    private void applyCanonicalFields(
            NormalizedTransaction normalized,
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        normalized.setTxHash(view.txHash());
        normalized.setNetworkId(view.networkId());
        normalized.setWalletAddress(view.walletAddress());
        normalized.setSource(com.walletradar.domain.transaction.normalized.NormalizedTransactionSource.ON_CHAIN);
        normalized.setBlockTimestamp(view.blockTimestamp());
        normalized.setTransactionIndex(view.transactionIndex());
        normalized.setType(classificationResult.type());
        normalized.setStatus(classificationResult.status());
        normalized.setClassifiedBy(classificationResult.classifiedBy());
        normalized.setConfidence(classificationResult.confidence());
        normalized.setFlows(classificationResult.flows());
        List<String> missingDataReasons = classificationResult.missingDataReasons();
        if (classificationResult.status() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            missingDataReasons = clarificationPolicyService.mergeClassifierReasons(
                    view,
                    classificationResult.type(),
                    missingDataReasons
            );
        }
        normalized.setMissingDataReasons(missingDataReasons);
        normalized.setProtocolName(classificationResult.protocolName());
        normalized.setProtocolVersion(classificationResult.protocolVersion());
        normalized.setCorrelationId(classificationResult.correlationId());
        normalized.setContinuityCandidate(Boolean.TRUE.equals(classificationResult.continuityCandidate()));
        normalized.setMatchedCounterparty(classificationResult.matchedCounterparty());
        normalized.setExcludedFromAccounting(Boolean.TRUE.equals(classificationResult.excludedFromAccounting()));
        normalized.setAccountingExclusionReason(classificationResult.accountingExclusionReason());
    }

    private int clarificationAttemptBaseline(OnChainRawTransactionView view) {
        return view == null ? 0 : view.clarificationAttemptCount();
    }

    private int fullReceiptClarificationAttemptBaseline(OnChainRawTransactionView view) {
        return view == null ? 0 : view.fullReceiptClarificationAttemptCount();
    }

    private int safeCounter(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
