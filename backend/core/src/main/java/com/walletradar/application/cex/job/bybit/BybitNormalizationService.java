package com.walletradar.application.cex.job.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import com.walletradar.domain.transaction.integration.IntegrationRawEventRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.cex.normalization.venue.bybit.BybitBotTransferCostBasisService;
import com.walletradar.application.cex.normalization.venue.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.application.cex.normalization.venue.bybit.BybitEarnPrincipalTransferPairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferExternalCpReclassifier;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferPairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitPrincipalEventExclusivityService;
import com.walletradar.application.cex.normalization.venue.bybit.BybitStakingConversionPairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapser;
import com.walletradar.application.cex.normalization.venue.bybit.BybitTradePairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitTransferShadowPairer;
import com.walletradar.application.cex.normalization.venue.bybit.PendingExternalLedgerRowQueryService;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitExtractionService;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitExtractedEventMapper;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitExtractedTradePairer;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitExtractedTransferShadowPairer;
import com.walletradar.application.cex.acquisition.venue.bybit.PendingBybitExtractedRowQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
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
    private static final String BYBIT_PREFIX = "BYBIT:";

    private final PendingBybitExtractedRowQueryService pendingBybitExtractedRowQueryService;
    private final BybitExtractedEventRepository bybitExtractedEventRepository;
    private final IntegrationRawEventRepository integrationRawEventRepository;
    private final BybitExtractedTradePairer bybitExtractedTradePairer;
    private final BybitExtractedTransferShadowPairer bybitExtractedTransferShadowPairer;
    private final PendingExternalLedgerRowQueryService pendingExternalLedgerRowQueryService;
    private final ExternalLedgerRawRepository externalLedgerRawRepository;
    private final BybitTradePairer bybitTradePairer;
    private final BybitTransferShadowPairer bybitTransferShadowPairer;
    private final BybitExtractedEventMapper bybitExtractedEventMapper;
    private final BybitExtractionService bybitExtractionService;
    private final BybitCanonicalTransactionBuilder builder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final AccountingUniverseService accountingUniverseService;
    private final BybitInternalTransferPairer bybitInternalTransferPairer;
    private final BybitEarnPrincipalTransferPairer bybitEarnPrincipalTransferPairer;
    private final BybitPrincipalEventExclusivityService bybitPrincipalEventExclusivityService;
    private final BybitInternalTransferExternalCpReclassifier bybitInternalTransferExternalCpReclassifier;
    private final BybitStreamAuthorityCollapser bybitStreamAuthorityCollapser;
    private final BybitStakingConversionPairer bybitStakingConversionPairer;
    private final BybitBotTransferCostBasisService bybitBotTransferCostBasisService;

    public int processNextBatch(int batchSize) {
        return processNextBatch(batchSize, null);
    }

    public int processNextBatch(int batchSize, String sessionId) {
        bindUniverseIfPresent(sessionId);
        try {
            int processed = processNextBatchInternal(batchSize);
            if (processed > 0) {
                int rewrites = bybitInternalTransferPairer.repairAll();
                if (rewrites > 0) {
                    log.info("BYBIT_INTERNAL_TRANSFER_PAIRER batchProcessed={} rewrites={}", processed, rewrites);
                }
                int collapsed = bybitStreamAuthorityCollapser.collapseMirrors();
                if (collapsed > 0) {
                    log.info("BYBIT_STREAM_AUTHORITY_COLLAPSER batchProcessed={} dirty={}", processed, collapsed);
                }
                int earnPaired = bybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers();
                if (earnPaired > 0) {
                    log.info("BYBIT_EARN_PRINCIPAL_PAIRER batchProcessed={} rewrites={}", processed, earnPaired);
                }
                int principalDeduped = bybitPrincipalEventExclusivityService.demoteDuplicatePrincipalEvents();
                if (principalDeduped > 0) {
                    log.info("BYBIT_PRINCIPAL_EXCLUSIVITY batchProcessed={} demoted={}", processed, principalDeduped);
                }
                int stakingPaired = bybitStakingConversionPairer.pairConversions();
                if (stakingPaired > 0) {
                    log.info("BYBIT_STAKING_CONVERSION_PAIRER batchProcessed={} pairs={}", processed, stakingPaired);
                }
                int botCostResolved = bybitBotTransferCostBasisService.computeBotCostBasis();
                if (botCostResolved > 0) {
                    log.info("BYBIT_BOT_COST_BASIS batchProcessed={} resolved={}", processed, botCostResolved);
                }
                if (sessionId != null && !sessionId.isBlank()) {
                    int reclassified = bybitInternalTransferExternalCpReclassifier.reclassify(sessionId.trim());
                    if (reclassified > 0) {
                        log.info("BYBIT_INTERNAL_TRANSFER_EXT_CP_RECLASSIFIER batchProcessed={} reclassified={}",
                                processed, reclassified);
                    }
                }
                int sameUidReclassified = bybitInternalTransferExternalCpReclassifier
                        .reclassifySameUidExternalToInternal(Instant.now());
                if (sameUidReclassified > 0) {
                    log.info("BYBIT_SAME_UID_EXT_TO_INTERNAL batchProcessed={} reclassified={}",
                            processed, sameUidReclassified);
                }
            }
            return processed;
        } finally {
            accountingUniverseService.clearUniverseBinding();
        }
    }

    private int processNextBatchInternal(int batchSize) {
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
        if (isFundingHistoryExecutionSpotDuplicate(row)) {
            return normalizeFundingHistorySpotDuplicate(row, now);
        }
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
        boolean basisRefreshed = bybitExtractionService.refreshBasisRelevantFromRaw(row);
        hydrateMissingTransferFields(row);
        boolean fundingHistoryHydrated = bybitExtractionService.hydrateFundingHistoryFromOnChainSibling(row);
        boolean walletRefUpdated = dimensionWalletRefIfMissing(row);
        if (walletRefUpdated || basisRefreshed || fundingHistoryHydrated) {
            bybitExtractedEventRepository.save(row);
        }
        ExternalLedgerRaw mappedRow = bybitExtractedEventMapper.toLegacyRaw(row);
        if (isFundingHistoryExecutionSpotDuplicate(mappedRow)) {
            return normalizeFundingHistorySpotDuplicate(row, mappedRow, now);
        }
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

    private boolean dimensionWalletRefIfMissing(BybitExtractedEvent row) {
        if (row == null) {
            return false;
        }
        String walletRef = row.getWalletRef();
        if (walletRef == null || walletRef.isBlank()) {
            return false;
        }
        String normalized = walletRef.trim();
        if (!normalized.toUpperCase().startsWith(BYBIT_PREFIX)) {
            return false;
        }
        // Already dimensioned: BYBIT:<uid>:UTA|FUND|EARN
        if (normalized.split(":").length >= 3) {
            return false;
        }
        String stream = normalize(row.getSourceStream());
        String bybitType = normalize(row.getBybitType());
        String suffix = inferSubAccountSuffix(stream, bybitType);
        row.setWalletRef(normalized + ":" + suffix);
        return true;
    }

    private String inferSubAccountSuffix(String sourceStream, String bybitType) {
        // Cycle/2 E2: split inventory by sub-account to avoid collapsing Earn.
        if ("EARN_FLEXIBLE_SAVING".equalsIgnoreCase(sourceStream)) {
            return "EARN";
        }
        if ("FUNDING_HISTORY".equalsIgnoreCase(sourceStream) && "earn".equals(bybitType)) {
            return "EARN";
        }
        if ("TRANSACTION_LOG".equalsIgnoreCase(sourceStream)) {
            return "UTA";
        }
        if (sourceStream != null && sourceStream.toUpperCase().startsWith("EXECUTION_")) {
            return "UTA";
        }
        if ("INTERNAL_TRANSFER".equalsIgnoreCase(sourceStream) || "UNIVERSAL_TRANSFER".equalsIgnoreCase(sourceStream)) {
            return "FUND";
        }
        return "FUND";
    }

    private void hydrateMissingTransferFields(BybitExtractedEvent row) {
        if (row == null || blank(row.getIntegrationRawEventId())) {
            return;
        }
        if (!blank(row.getSenderAddress()) && !blank(row.getReceivedAddress()) && !blank(row.getTxHash())) {
            return;
        }
        integrationRawEventRepository.findById(row.getIntegrationRawEventId()).ifPresent(rawEvent -> {
            Document payload = rawEvent.getPayload();
            if (payload == null) {
                return;
            }
            if (blank(row.getSenderAddress())) {
                row.setSenderAddress(text(payload, "fromAddress"));
            }
            if (blank(row.getReceivedAddress())) {
                row.setReceivedAddress(text(payload, "toAddress", "address"));
            }
            if (blank(row.getTxHash())) {
                row.setTxHash(text(payload, "txID", "txId"));
            }
        });
    }

    private boolean isTradeRow(ExternalLedgerRaw row) {
        // TRANSACTION_LOG TRADE duplicates EXECUTION_SPOT; never pair as execution legs (cycle/3 G13).
        if ("transaction_log".equals(normalize(row.getSourceFile()))) {
            return false;
        }
        if ("funding_history".equals(normalize(row.getSourceFile()))
                && row.getTradeOrderId() != null
                && !row.getTradeOrderId().isBlank()
                && fundingHistoryDuplicatesExecutionSpot(row)) {
            return false;
        }
        return "uta_derivatives".equals(normalize(row.getSourceFileType()))
                && "SWAP".equalsIgnoreCase(normalize(row.getCanonicalType()))
                && !isConvertType(row.getBybitType())
                && isDirectionalTrade(row);
    }

    private boolean isFundingHistoryExecutionSpotDuplicate(ExternalLedgerRaw row) {
        return "funding_history".equals(normalize(row.getSourceFile()))
                && fundingHistoryDuplicatesExecutionSpot(row);
    }

    private boolean normalizeFundingHistorySpotDuplicate(ExternalLedgerRaw row, Instant now) {
        row.setBasisRelevant(false);
        externalLedgerRawRepository.save(row);
        NormalizedTransaction excluded = builder.buildExcludedReviewRow(
                row,
                now,
                "BYBIT_FUNDING_HISTORY_EXECUTION_SPOT_DUPLICATE"
        );
        normalizedTransactionStore.upsert(excluded);
        markConfirmed(row);
        return true;
    }

    private boolean normalizeFundingHistorySpotDuplicate(
            BybitExtractedEvent row,
            ExternalLedgerRaw mappedRow,
            Instant now
    ) {
        row.setBasisRelevant(false);
        bybitExtractedEventRepository.save(row);
        mappedRow.setBasisRelevant(false);
        NormalizedTransaction excluded = builder.buildExcludedReviewRow(
                mappedRow,
                now,
                "BYBIT_FUNDING_HISTORY_EXECUTION_SPOT_DUPLICATE"
        );
        normalizedTransactionStore.upsert(excluded);
        markConfirmed(row);
        return true;
    }

    private boolean fundingHistoryDuplicatesExecutionSpot(ExternalLedgerRaw row) {
        if (row == null || row.getTradeOrderId() == null || row.getTradeOrderId().isBlank()) {
            return false;
        }
        BybitExtractedEvent probe = new BybitExtractedEvent();
        probe.setIntegrationId(integrationIdFromRow(row));
        probe.setSourceStream("FUNDING_HISTORY");
        probe.setTradeOrderId(row.getTradeOrderId());
        return bybitExtractionService.fundingHistoryDuplicatesExecutionSpot(probe);
    }

    private String integrationIdFromRow(ExternalLedgerRaw row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        int colon = row.getId().indexOf(':');
        return colon > 0 ? row.getId().substring(0, colon) : null;
    }

    private boolean isConvertRow(ExternalLedgerRaw row) {
        return "swap".equals(normalize(row.getCanonicalType()))
                && isConvertType(row.getBybitType());
    }

    private boolean isLiquidStakingRow(ExternalLedgerRaw row) {
        String canon = normalize(row.getCanonicalType());
        if ("internal_transfer".equals(canon) && "eth 2.0".equals(normalize(row.getBybitType()))) {
            String desc = normalize(row.getBybitDescription());
            return desc.contains("mint") || desc.contains("stake");
        }
        if ("internal_transfer".equals(canon)
                && "earn".equals(normalize(row.getBybitType()))
                && "on-chain earn subscription".equals(normalize(row.getBybitDescription()))) {
            return true;
        }
        return "fund_asset_changes".equals(normalize(row.getSourceFileType()))
                && "staking_deposit".equals(canon)
                && ("eth 2.0".equals(normalize(row.getBybitType()))
                || ("earn".equals(normalize(row.getBybitType()))
                && "on-chain earn subscription".equals(normalize(row.getBybitDescription()))));
    }

    private boolean isUnsafeLoanRow(ExternalLedgerRaw row) {
        String type = normalize(row.getCanonicalType());
        if (!"borrow".equals(type) && !"repay".equals(type)) {
            return false;
        }
        // API-sourced funding / transaction log loan semantics are classified in extraction (task 156).
        String sourceFile = normalize(row.getSourceFile());
        if ("funding_history".equals(sourceFile) || "transaction_log".equals(sourceFile)) {
            return false;
        }
        // Legacy CSV / ambiguous fund_asset_changes loan rows stay excluded until reviewed.
        return "fund_asset_changes".equals(normalize(row.getSourceFileType()))
                || "bybit".equals(normalize(row.getSourceFileType()))
                || "bybit".equals(normalize(row.getChain()));
    }

    /**
     * Cycle/5 N17: {@code FUNDING_HISTORY/Deposit} and {@code FUNDING_HISTORY/Withdraw} are the
     * canonical FUND-accounting anchors for external on-chain inflows/outflows. The chain-aware
     * {@code DEPOSIT_ONCHAIN} and {@code WITHDRAWAL} streams are emitted with
     * {@code basisRelevant=false} at extraction (see {@code extractChainDeposit} / {@code extractWithdrawal})
     * so the builder marks them excluded via the {@code BYBIT_BASIS_IRRELEVANT} rule and no double
     * counting occurs.
     *
     * <p>The previous behaviour treated FH/Deposit + FH/Withdraw as <i>shadows</i> of the chain-aware
     * rows and excluded them whenever a chain-aware sibling was found — leaving <b>no</b> row to
     * acquire basis for the on-chain crypto deposit/withdrawal. That silently leaked basis (all
     * 13 USDT crypto deposits = 13,307 USDT vanished; downstream disposals accumulated
     * {@code quantityShortfallAfter} = 14,106 on FUND / 6,453 on UTA instead of crystallising
     * realized PnL).</p>
     *
     * <p>This method now returns {@code false} unconditionally, retiring the shadow-pairing path.
     * The companion exclusion of the chain-aware row continues to come from the
     * {@code basisRelevant=false} flag set in extraction, applied uniformly by
     * {@code BybitCanonicalTransactionBuilder#buildMappedRow} (N1/N5). EXTERNAL_TRANSFER_IN /
     * EXTERNAL_TRANSFER_OUT flow roles are BUY/SELL (N16 part 2), so the FH anchor ACQUIREs at
     * market price and DISPOSEs against AVCO.</p>
     */
    private boolean isTransferShadowRow(ExternalLedgerRaw row) {
        return false;
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

    private boolean normalizeOrphanConvertRow(ExternalLedgerRaw row, Instant now, String reason) {
        log.warn("Bybit convert orphan (legacy path): id={}, reason={}", row.getId(), reason);
        row.setBasisRelevant(false);
        NormalizedTransaction excluded = builder.buildExcludedReviewRow(row, now, reason);
        normalizedTransactionStore.upsert(excluded);
        markConfirmed(row);
        return true;
    }

    private boolean normalizeOrphanConvertExtractedRow(
            BybitExtractedEvent row,
            ExternalLedgerRaw mappedRow,
            Instant now,
            String reason
    ) {
        log.warn("Bybit convert orphan: id={}, tradeOrderId={}, reason={}", row.getId(), row.getTradeOrderId(), reason);
        row.setBasisRelevant(false);
        mappedRow.setBasisRelevant(false);
        NormalizedTransaction excluded = builder.buildExcludedReviewRow(mappedRow, now, reason);
        normalizedTransactionStore.upsert(excluded);
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
        if (cluster.size() < 2 || !hasBuy || !hasSell) {
            return normalizeOrphanConvertRow(row, now, "BYBIT_CONVERT_CLUSTER_INCOMPLETE");
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
        if (cluster.size() < 2 || !hasBuy || !hasSell) {
            return normalizeOrphanConvertExtractedRow(row, mappedRow, now, "BYBIT_CONVERT_CLUSTER_INCOMPLETE");
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
            // Cycle/9 S4 (superseded): cross-sub-account liquid-staking pairs (e.g. FUND METH ↔
            // EARN CMETH) are ETH-family conversions. The former path emitted each leg as an
            // INTERNAL_TRANSFER under a shared correlationId, expecting the family-equivalent
            // continuity bucket to carry basis — but that bucket is keyed by the raw sub-account
            // walletAddress, so the two legs never shared a bucket and the source basis stranded
            // (phantom same-asset EARN credit). They are now fused into one umbrella-booked
            // STAKING_DEPOSIT so LiquidStakingReplayHandler carries the source family basis into
            // the received token — the same carrier as the same-sub-account ETH→METH control.
            NormalizedTransaction normalized = sameBybitSubAccount(row, pair)
                    ? builder.buildStakingPair(row, pair, now)
                    : builder.buildCrossSubAccountStakingPair(liquidStakingDebit(row, pair), liquidStakingCredit(row, pair), now);
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
            // Cycle/9 S4 (superseded): see legacy-path comment above. Cross-sub-account ETH-family
            // conversions are fused into one umbrella-booked STAKING_DEPOSIT so the source family
            // basis carries into the received liquid-staking token via LiquidStakingReplayHandler.
            ExternalLedgerRaw pairLegacy = bybitExtractedEventMapper.toLegacyRaw(pair);
            NormalizedTransaction normalized = sameBybitSubAccount(row, pair)
                    ? builder.buildStakingPair(mappedRow, pairLegacy, now)
                    : builder.buildCrossSubAccountStakingPair(
                            liquidStakingDebit(mappedRow, pairLegacy), liquidStakingCredit(mappedRow, pairLegacy), now);
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

    /**
     * The outflow (debit, negative signed quantity) leg of a liquid-staking conversion pair. Used
     * to anchor a cross-sub-account fused {@code STAKING_DEPOSIT} on the source sub-account.
     */
    private ExternalLedgerRaw liquidStakingDebit(ExternalLedgerRaw a, ExternalLedgerRaw b) {
        return isDebitLeg(a) ? a : b;
    }

    /**
     * The inflow (credit, positive signed quantity) leg of a liquid-staking conversion pair.
     */
    private ExternalLedgerRaw liquidStakingCredit(ExternalLedgerRaw a, ExternalLedgerRaw b) {
        return isDebitLeg(a) ? b : a;
    }

    private boolean isDebitLeg(ExternalLedgerRaw row) {
        return row != null && row.getQuantityRaw() != null && row.getQuantityRaw().signum() < 0;
    }

    /**
     * Cycle/5 N13: two Bybit liquid-staking legs are eligible for STAKING_DEPOSIT pairing only when
     * they target the same sub-account (FUND / UTA / EARN). Cross-sub-account pairs leak the debit leg
     * because {@code NormalizedTransaction} carries a single {@code walletAddress}.
     */
    private boolean sameBybitSubAccount(BybitExtractedEvent left, BybitExtractedEvent right) {
        String leftSub = resolveSubAccountSuffix(left);
        String rightSub = resolveSubAccountSuffix(right);
        return leftSub != null && leftSub.equalsIgnoreCase(rightSub);
    }

    private boolean sameBybitSubAccount(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        String leftSub = resolveSubAccountSuffix(left);
        String rightSub = resolveSubAccountSuffix(right);
        return leftSub != null && leftSub.equalsIgnoreCase(rightSub);
    }

    private String resolveSubAccountSuffix(BybitExtractedEvent row) {
        if (row == null) {
            return null;
        }
        String suffix = subAccountSuffixFromWalletRef(row.getWalletRef());
        if (suffix != null) {
            return suffix;
        }
        return inferSubAccountSuffix(normalize(row.getSourceStream()), normalize(row.getBybitType()));
    }

    private String resolveSubAccountSuffix(ExternalLedgerRaw row) {
        if (row == null) {
            return null;
        }
        String suffix = subAccountSuffixFromWalletRef(row.getWalletRef());
        if (suffix != null) {
            return suffix;
        }
        // Legacy CSV-derived rows are FUND-anchored (no dimensioning pass was run on import).
        return "FUND";
    }

    private String subAccountSuffixFromWalletRef(String walletRef) {
        if (walletRef == null || walletRef.isBlank()) {
            return null;
        }
        String[] parts = walletRef.trim().split(":");
        if (parts.length < 3 || parts[2] == null || parts[2].isBlank()) {
            return null;
        }
        return parts[2];
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

    private void bindUniverseIfPresent(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            accountingUniverseService.bindUniverse(sessionId.trim());
        }
    }

    private String text(Document payload, String... keys) {
        if (payload == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
