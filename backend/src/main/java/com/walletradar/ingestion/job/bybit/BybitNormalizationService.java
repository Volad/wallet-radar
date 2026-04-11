package com.walletradar.ingestion.job.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.ingestion.pipeline.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.ingestion.pipeline.bybit.BybitTradePairer;
import com.walletradar.ingestion.pipeline.bybit.BybitTransferShadowPairer;
import com.walletradar.ingestion.pipeline.bybit.PendingExternalLedgerRowQueryService;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import com.walletradar.integration.bybit.BybitExtractedEventMapper;
import com.walletradar.integration.bybit.BybitExtractedTradePairer;
import com.walletradar.integration.bybit.BybitExtractedTransferShadowPairer;
import com.walletradar.integration.bybit.PendingBybitExtractedRowQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Materializes canonical Bybit docs from immutable ledger rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BybitNormalizationService {

    private static final String TRANSFER_SHADOW_EXCLUSION_REASON = "BYBIT_TRANSFER_SHADOW_ROW";
    private static final List<String> CONVERT_TYPES = List.of("convert", "currency_buy", "currency_sell");

    private final PendingBybitExtractedRowQueryService pendingBybitExtractedRowQueryService;
    private final BybitExtractedEventRepository bybitExtractedEventRepository;
    private final BybitExtractedTradePairer bybitExtractedTradePairer;
    private final BybitExtractedTransferShadowPairer bybitExtractedTransferShadowPairer;
    private final PendingExternalLedgerRowQueryService pendingExternalLedgerRowQueryService;
    private final ExternalLedgerRawRepository externalLedgerRawRepository;
    private final BybitTradePairer bybitTradePairer;
    private final BybitTransferShadowPairer bybitTransferShadowPairer;
    private final BybitExtractedEventMapper bybitExtractedEventMapper;
    private final BybitCanonicalTransactionBuilder builder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;

    public int processNextBatch(int batchSize) {
        List<BybitExtractedEvent> extractedBatch = safe(pendingBybitExtractedRowQueryService.loadNextBatch(batchSize));
        int processed = 0;
        for (BybitExtractedEvent candidate : extractedBatch) {
            Optional<BybitExtractedEvent> current = bybitExtractedEventRepository.findById(candidate.getId());
            if (current.isEmpty() || !isProcessable(current.orElseThrow())) {
                continue;
            }
            if (normalize(current.orElseThrow(), Instant.now())) {
                processed++;
            }
        }
        if (processed > 0 || !extractedBatch.isEmpty()) {
            return processed;
        }

        List<ExternalLedgerRaw> batch = safe(pendingExternalLedgerRowQueryService.loadNextBatch(batchSize));
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
        return row.getStatus() == ExternalLedgerRawStatus.RAW;
    }

    private boolean isProcessable(BybitExtractedEvent row) {
        return row.getStatus() == BybitExtractedEventStatus.RAW;
    }

    boolean normalize(ExternalLedgerRaw row, Instant now) {
        if (isTradeRow(row)) {
            return normalizeTradeRow(row, now);
        }
        if (isConvertRow(row)) {
            return normalizeConvertRow(row, now);
        }
        if (isLiquidStakingRow(row)) {
            return normalizeLiquidStakingRow(row, now);
        }
        if (isUnsafeLoanRow(row)) {
            NormalizedTransaction normalized = builder.buildExcludedReviewRow(row, now, "BYBIT_LOAN_SEMANTICS_UNSUPPORTED");
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            return true;
        }

        if (isTransferShadowRow(row)) {
            return normalizeTransferShadowRow(row, now);
        }

        NormalizedTransaction normalized = builder.buildMappedRow(row, now);
        normalizedTransactionStore.upsert(normalized);
        markConfirmed(row);
        return true;
    }

    boolean normalize(BybitExtractedEvent row, Instant now) {
        ExternalLedgerRaw mappedRow = bybitExtractedEventMapper.toLegacyRaw(row);
        if (isTradeRow(mappedRow)) {
            return normalizeTradeRow(row, mappedRow, now);
        }
        if (isConvertRow(mappedRow)) {
            return normalizeConvertRow(row, mappedRow, now);
        }
        if (isLiquidStakingRow(mappedRow)) {
            return normalizeLiquidStakingRow(row, mappedRow, now);
        }
        if (isUnsafeLoanRow(mappedRow)) {
            NormalizedTransaction normalized = builder.buildExcludedReviewRow(mappedRow, now, "BYBIT_LOAN_SEMANTICS_UNSUPPORTED");
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            return true;
        }
        if (isTransferShadowRow(mappedRow)) {
            return normalizeTransferShadowRow(row, mappedRow, now);
        }

        NormalizedTransaction normalized = builder.buildMappedRow(mappedRow, now);
        normalizedTransactionStore.upsert(normalized);
        markConfirmed(row);
        return true;
    }

    private boolean isTradeRow(ExternalLedgerRaw row) {
        return "uta_derivatives".equals(normalize(row.getSourceFileType()))
                && "SWAP".equalsIgnoreCase(normalize(row.getCanonicalType()))
                && !isConvertType(row.getBybitType())
                && isDirectionalTrade(row);
    }

    private boolean isConvertRow(ExternalLedgerRaw row) {
        return "swap".equals(normalize(row.getCanonicalType()))
                && isConvertType(row.getBybitType());
    }

    private boolean isLiquidStakingRow(ExternalLedgerRaw row) {
        return "fund_asset_changes".equals(normalize(row.getSourceFileType()))
                && "staking_deposit".equals(normalize(row.getCanonicalType()))
                && ("eth 2.0".equals(normalize(row.getBybitType()))
                || ("earn".equals(normalize(row.getBybitType()))
                && "on-chain earn subscription".equals(normalize(row.getBybitDescription()))));
    }

    private boolean isUnsafeLoanRow(ExternalLedgerRaw row) {
        if (!"fund_asset_changes".equals(normalize(row.getSourceFileType()))
                || !"loans".equals(normalize(row.getBybitType()))) {
            return false;
        }
        String type = normalize(row.getCanonicalType());
        return "borrow".equals(type) || "repay".equals(type);
    }

    private boolean isTransferShadowRow(ExternalLedgerRaw row) {
        if (!"fund_asset_changes".equals(normalize(row.getSourceFileType()))
                || !"bybit".equals(normalize(row.getChain()))
                || row.getTxHash() != null
                || row.getNetworkId() != null) {
            return false;
        }
        String canonicalType = normalize(row.getCanonicalType());
        if ("external_transfer_out".equals(canonicalType) && "withdraw".equals(normalize(row.getBybitType()))) {
            return true;
        }
        return ("external_transfer_in".equals(canonicalType) || "external_inbound".equals(canonicalType))
                && "deposit".equals(normalize(row.getBybitType()));
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

    private boolean normalizeTradeRow(BybitExtractedEvent row, ExternalLedgerRaw mappedRow, Instant now) {
        Optional<BybitExtractedEvent> paired = bybitExtractedTradePairer.findOppositeLeg(row);
        if (paired.isPresent()) {
            BybitExtractedEvent pair = paired.orElseThrow();
            NormalizedTransaction normalized = builder.buildTradePair(
                    mappedRow,
                    bybitExtractedEventMapper.toLegacyRaw(pair),
                    now
            );
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            markConfirmed(pair);
            return true;
        }

        NormalizedTransaction orphan = builder.buildOrphanTrade(mappedRow, now);
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

    private boolean normalizeConvertRow(BybitExtractedEvent row, ExternalLedgerRaw mappedRow, Instant now) {
        List<BybitExtractedEvent> cluster = new ArrayList<>(bybitExtractedTradePairer.loadConvertCluster(row));
        if (cluster.isEmpty()) {
            cluster = List.of(row);
        }
        boolean hasBuy = cluster.stream().anyMatch(candidate -> candidate.getQuantityRaw() != null && candidate.getQuantityRaw().signum() > 0);
        boolean hasSell = cluster.stream().anyMatch(candidate -> candidate.getQuantityRaw() != null && candidate.getQuantityRaw().signum() < 0);
        if (!hasBuy || !hasSell) {
            NormalizedTransaction review = builder.buildNeedsReviewRow(mappedRow, now, "BYBIT_CONVERT_CLUSTER_INCOMPLETE");
            normalizedTransactionStore.upsert(review);
            markConfirmed(row);
            return true;
        }

        NormalizedTransaction normalized = builder.buildConvertCluster(
                cluster.stream().map(bybitExtractedEventMapper::toLegacyRaw).toList(),
                now
        );
        normalizedTransactionStore.upsert(normalized);
        cluster.forEach(this::markConfirmed);
        return true;
    }

    private boolean normalizeLiquidStakingRow(ExternalLedgerRaw row, Instant now) {
        Optional<ExternalLedgerRaw> paired = bybitTradePairer.findLiquidStakingCounterLeg(row);
        if (paired.isPresent()) {
            ExternalLedgerRaw pair = paired.orElseThrow();
            NormalizedTransaction normalized = builder.buildStakingPair(row, pair, now);
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            markConfirmed(pair);
            return true;
        }

        NormalizedTransaction review = builder.buildNeedsReviewRow(row, now, "BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND");
        normalizedTransactionStore.upsert(review);
        markConfirmed(row);
        return true;
    }

    private boolean normalizeLiquidStakingRow(BybitExtractedEvent row, ExternalLedgerRaw mappedRow, Instant now) {
        Optional<BybitExtractedEvent> paired = bybitExtractedTradePairer.findLiquidStakingCounterLeg(row);
        if (paired.isPresent()) {
            BybitExtractedEvent pair = paired.orElseThrow();
            NormalizedTransaction normalized = builder.buildStakingPair(
                    mappedRow,
                    bybitExtractedEventMapper.toLegacyRaw(pair),
                    now
            );
            normalizedTransactionStore.upsert(normalized);
            markConfirmed(row);
            markConfirmed(pair);
            return true;
        }

        NormalizedTransaction review = builder.buildNeedsReviewRow(mappedRow, now, "BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND");
        normalizedTransactionStore.upsert(review);
        markConfirmed(row);
        return true;
    }

    private boolean normalizeTransferShadowRow(ExternalLedgerRaw row, Instant now) {
        NormalizedTransaction normalized = builder.buildMappedRow(row, now);
        if (bybitTransferShadowPairer.findChainAwareTransferSibling(row).isPresent()) {
            builder.markTransferShadowExcluded(normalized, now, TRANSFER_SHADOW_EXCLUSION_REASON);
        }
        normalizedTransactionStore.upsert(normalized);
        markConfirmed(row);
        return true;
    }

    private boolean normalizeTransferShadowRow(BybitExtractedEvent row, ExternalLedgerRaw mappedRow, Instant now) {
        NormalizedTransaction normalized = builder.buildMappedRow(mappedRow, now);
        if (bybitExtractedTransferShadowPairer.findChainAwareTransferSibling(row).isPresent()) {
            builder.markTransferShadowExcluded(normalized, now, TRANSFER_SHADOW_EXCLUSION_REASON);
        }
        normalizedTransactionStore.upsert(normalized);
        markConfirmed(row);
        return true;
    }

    private void markConfirmed(ExternalLedgerRaw row) {
        row.setStatus(ExternalLedgerRawStatus.CONFIRMED);
        externalLedgerRawRepository.save(row);
    }

    private void markConfirmed(BybitExtractedEvent row) {
        row.setStatus(BybitExtractedEventStatus.CONFIRMED);
        bybitExtractedEventRepository.save(row);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isConvertType(String bybitType) {
        return CONVERT_TYPES.contains(normalize(bybitType));
    }

    private boolean isDirectionalTrade(ExternalLedgerRaw row) {
        String direction = normalize(row.getUtaDirection());
        return "buy".equals(direction) || "sell".equals(direction);
    }

    private <T> List<T> safe(List<T> batch) {
        return batch == null ? List.of() : batch;
    }
}
