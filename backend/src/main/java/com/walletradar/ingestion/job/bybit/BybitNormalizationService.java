package com.walletradar.ingestion.job.bybit;

import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.ingestion.pipeline.bybit.BybitTradePairer;
import com.walletradar.ingestion.pipeline.bybit.PendingExternalLedgerRowQueryService;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Materializes canonical Bybit docs from immutable ledger rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BybitNormalizationService {

    private static final String EXTERNAL_CUSTODY_EXCLUSION_REASON = "EXTERNAL_CUSTODY_UNTRACKED_VENUE";

    private final PendingExternalLedgerRowQueryService pendingExternalLedgerRowQueryService;
    private final ExternalLedgerRawRepository externalLedgerRawRepository;
    private final BybitTradePairer bybitTradePairer;
    private final BybitCanonicalTransactionBuilder builder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final RawTransactionRepository rawTransactionRepository;
    private final TrackedWalletLookupService trackedWalletLookupService;

    public int processNextBatch(int batchSize) {
        List<ExternalLedgerRaw> batch = pendingExternalLedgerRowQueryService.loadNextBatch(batchSize);
        if (batch.isEmpty()) {
            batch = pendingExternalLedgerRowQueryService.loadNextBridgeRematchBatch(batchSize);
        }
        int processed = 0;
        for (ExternalLedgerRaw candidate : batch) {
            Optional<ExternalLedgerRaw> current = externalLedgerRawRepository.findById(candidate.getId());
            if (current.isEmpty() || !isProcessable(current.orElseThrow())) {
                continue;
            }
            if (normalize(current.orElseThrow(), Instant.now())) {
                processed++;
            }
        }
        return processed;
    }

    private boolean isProcessable(ExternalLedgerRaw row) {
        if (row.getStatus() == ExternalLedgerRawStatus.RAW) {
            return true;
        }
        return row.getStatus() == ExternalLedgerRawStatus.CONFIRMED
                && "withdraw_deposit".equals(normalize(row.getSourceFileType()))
                && row.getOnChainCorrelation() != null
                && "unmatched".equals(normalize(row.getOnChainCorrelation().getStatus()));
    }

    boolean normalize(ExternalLedgerRaw row, Instant now) {
        if (isTradeRow(row)) {
            return normalizeTradeRow(row, now);
        }
        if (isConvertRow(row)) {
            return normalizeConvertRow(row, now);
        }
        if (isEthStakingRow(row)) {
            return normalizeEthStakingRow(row, now);
        }
        if (isUnsafeLoanRow(row)) {
            NormalizedTransaction normalized = builder.buildExcludedReviewRow(row, now, "BYBIT_LOAN_SEMANTICS_UNSUPPORTED");
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            return true;
        }

        NormalizedTransaction normalized = builder.buildMappedRow(row, now);
        if (isBridgeCandidate(row, normalized)) {
            correlateBridge(row, normalized, now);
        }
        normalizedTransactionStore.upsert(normalized);
        markConfirmed(row);
        return true;
    }

    private boolean isTradeRow(ExternalLedgerRaw row) {
        return "uta_derivatives".equals(normalize(row.getSourceFileType()))
                && "SWAP".equalsIgnoreCase(normalize(row.getCanonicalType()));
    }

    private boolean isConvertRow(ExternalLedgerRaw row) {
        return "fund_asset_changes".equals(normalize(row.getSourceFileType()))
                && "swap".equals(normalize(row.getCanonicalType()))
                && "convert".equals(normalize(row.getBybitType()));
    }

    private boolean isEthStakingRow(ExternalLedgerRaw row) {
        return "fund_asset_changes".equals(normalize(row.getSourceFileType()))
                && "staking_deposit".equals(normalize(row.getCanonicalType()))
                && "eth 2.0".equals(normalize(row.getBybitType()));
    }

    private boolean isUnsafeLoanRow(ExternalLedgerRaw row) {
        if (!"fund_asset_changes".equals(normalize(row.getSourceFileType()))
                || !"loans".equals(normalize(row.getBybitType()))) {
            return false;
        }
        String type = normalize(row.getCanonicalType());
        return "borrow".equals(type) || "repay".equals(type);
    }

    private boolean normalizeTradeRow(ExternalLedgerRaw row, Instant now) {
        Optional<ExternalLedgerRaw> paired = bybitTradePairer.findOppositeLeg(row);
        if (paired.isPresent()) {
            ExternalLedgerRaw pair = paired.orElseThrow();
            NormalizedTransaction normalized = builder.buildTradePair(row, pair, now);
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            markConfirmed(pair);
            return true;
        }

        NormalizedTransaction orphan = builder.buildOrphanTrade(row, now);
        normalizedTransactionStore.upsert(orphan);
        markConfirmed(row);
        return true;
    }

    private boolean normalizeConvertRow(ExternalLedgerRaw row, Instant now) {
        List<ExternalLedgerRaw> cluster = new ArrayList<>(bybitTradePairer.loadConvertCluster(row));
        if (cluster.isEmpty()) {
            cluster = List.of(row);
        }
        boolean hasBuy = cluster.stream().anyMatch(candidate -> candidate.getQuantityRaw() != null && candidate.getQuantityRaw().signum() > 0);
        boolean hasSell = cluster.stream().anyMatch(candidate -> candidate.getQuantityRaw() != null && candidate.getQuantityRaw().signum() < 0);
        if (!hasBuy || !hasSell) {
            NormalizedTransaction review = builder.buildNeedsReviewRow(row, now, "BYBIT_CONVERT_CLUSTER_INCOMPLETE");
            normalizedTransactionStore.upsert(review);
            markConfirmed(row);
            return true;
        }

        NormalizedTransaction normalized = builder.buildConvertCluster(cluster, now);
        normalizedTransactionStore.upsert(normalized);
        cluster.forEach(this::markConfirmed);
        return true;
    }

    private boolean normalizeEthStakingRow(ExternalLedgerRaw row, Instant now) {
        Optional<ExternalLedgerRaw> paired = bybitTradePairer.findEthStakingCounterLeg(row);
        if (paired.isPresent()) {
            ExternalLedgerRaw pair = paired.orElseThrow();
            NormalizedTransaction normalized = builder.buildStakingPair(row, pair, now);
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            markConfirmed(pair);
            return true;
        }

        NormalizedTransaction review = builder.buildNeedsReviewRow(row, now, "BYBIT_ETH2_PAIR_NOT_FOUND");
        normalizedTransactionStore.upsert(review);
        markConfirmed(row);
        return true;
    }

    private void correlateBridge(
            ExternalLedgerRaw row,
            NormalizedTransaction bybitTransaction,
            Instant now
    ) {
        String txHash = row.getTxHash();
        if (txHash == null || txHash.isBlank() || row.getNetworkId() == null) {
            return;
        }

        List<RawTransaction> rawMatches = rawTransactionRepository.findAllByTxHashAndNetworkId(
                txHash,
                row.getNetworkId().name()
        );
        List<NormalizedTransaction> normalizedMatches = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                txHash,
                row.getNetworkId(),
                NormalizedTransactionSource.ON_CHAIN
        );
        if (rawMatches.isEmpty() || normalizedMatches.isEmpty()) {
            if (isExternalCustodyCandidate(row)) {
                markExternalCustody(row, bybitTransaction, now);
                return;
            }
            ensureCorrelation(row).setStatus("UNMATCHED");
            bybitTransaction.getMissingDataReasons().add("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
            return;
        }

        String correlationId = correlationId(row);
        ExternalLedgerRaw.OnChainCorrelation correlation = ensureCorrelation(row);
        correlation.setStatus("MATCHED");
        correlation.setMatchedDocId(rawMatches.get(0).getId());
        correlation.setCorrelationId(correlationId);

        NormalizedTransaction onChain = normalizedMatches.get(0);
        builder.markMatchedContinuityCandidate(
                bybitTransaction,
                correlationId,
                onChain.getWalletAddress(),
                now
        );
        builder.markMatchedContinuityCandidate(
                onChain,
                correlationId,
                bybitTransaction.getWalletAddress(),
                now
        );
        normalizedTransactionRepository.save(onChain);
    }

    private boolean isBridgeCandidate(ExternalLedgerRaw row, NormalizedTransaction normalized) {
        return "withdraw_deposit".equals(normalize(row.getSourceFileType()))
                && (normalized.getType() == com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || normalized.getType() == com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
    }

    private ExternalLedgerRaw.OnChainCorrelation ensureCorrelation(ExternalLedgerRaw row) {
        if (row.getOnChainCorrelation() == null) {
            row.setOnChainCorrelation(new ExternalLedgerRaw.OnChainCorrelation());
        }
        return row.getOnChainCorrelation();
    }

    private void markConfirmed(ExternalLedgerRaw row) {
        row.setStatus(ExternalLedgerRawStatus.CONFIRMED);
        externalLedgerRawRepository.save(row);
    }

    private String correlationId(ExternalLedgerRaw row) {
        return "BYBIT:" + row.getNetworkId().name() + ":" + row.getTxHash().toLowerCase(Locale.ROOT);
    }

    private boolean isExternalCustodyCandidate(ExternalLedgerRaw row) {
        if (row == null || row.getReceivedAddress() == null || row.getReceivedAddress().isBlank()) {
            return false;
        }
        return !trackedWalletLookupService.contains(row.getReceivedAddress());
    }

    private void markExternalCustody(
            ExternalLedgerRaw row,
            NormalizedTransaction bybitTransaction,
            Instant now
    ) {
        ExternalLedgerRaw.OnChainCorrelation correlation = ensureCorrelation(row);
        correlation.setStatus("EXTERNAL_CUSTODY");
        correlation.setCorrelationId(null);
        correlation.setMatchedDocId(null);
        builder.markExternalCustodyExcluded(bybitTransaction, now, EXTERNAL_CUSTODY_EXCLUSION_REASON);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
