package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Standalone normalization job (ADR-021/026). Processes PENDING raw transactions in batches.
 * Triggered by @Scheduled(90s).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RawTxNormalizationJob {

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final RawTransactionRepository rawTransactionRepository;
    private final ClassificationProcessor classificationProcessor;
    private final ClassifierProperties classifierProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final List<BlockTimestampResolver> blockTimestampResolvers;

    @Scheduled(fixedDelayString = "${walletradar.ingestion.classifier.schedule-interval-ms:90000}")
    public void runScheduled() {
        runNormalization("scheduled");
    }

    public void runNormalization() {
        runNormalization("manual");
    }

    private void runNormalization(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.warn("RawTxNormalizationJob skipped: already running, trigger={}", trigger);
            return;
        }

        long startedAt = System.currentTimeMillis();
        int processed = 0;
        int pairs = 0;
        boolean failed = false;
        log.info("RawTxNormalizationJob started: trigger={}", trigger);
        try {
            int batchSize = resolveBatchSize();
            int loops = 0;

            while (true) {
                List<RawTransaction> pendingBatch = loadPendingEvmBatch(batchSize);
                if (pendingBatch.isEmpty()) {
                    if (loops == 0) {
                        log.debug("RawTxNormalizationJob: no PENDING raw transactions, skipping");
                    }
                    break;
                }
                loops++;

                List<RawTransaction> validBatch = filterAndMarkInvalidIdentity(pendingBatch);
                Map<String, List<RawTransaction>> grouped = groupByWalletAndNetwork(validBatch);
                pairs += grouped.size();
                processed += processGroupedBatch(grouped);
            }
        } catch (Exception e) {
            failed = true;
            log.error("RawTxNormalizationJob failed: trigger={}, processed={}, durationMs={}",
                    trigger, processed, System.currentTimeMillis() - startedAt, e);
            throw e;
        } finally {
            running.set(false);
            log.info("RawTxNormalizationJob finished: trigger={}, status={}, processed={}, walletNetworkPairs={}, durationMs={}",
                    trigger, failed ? "FAILED" : "OK", processed, pairs, System.currentTimeMillis() - startedAt);
        }
    }

    private int resolveBatchSize() {
        return Math.max(100, classifierProperties.getBatchSize());
    }

    private List<RawTransaction> loadPendingEvmBatch(int batchSize) {
        return rawTransactionRepository.findByNormalizationStatusAndNetworkIdNot(
                NormalizationStatus.PENDING,
                NetworkId.SOLANA.name(),
                PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
    }

    private List<RawTransaction> filterAndMarkInvalidIdentity(List<RawTransaction> pendingBatch) {
        Map<Boolean, List<RawTransaction>> partitioned = pendingBatch.stream()
                .collect(Collectors.partitioningBy(this::hasInvalidIdentity));
        List<RawTransaction> invalid = partitioned.getOrDefault(true, List.of());
        for (RawTransaction tx : invalid) {
            markRetry(tx, "Invalid raw transaction identity");
            rawTransactionRepository.save(tx);
        }
        return partitioned.getOrDefault(false, List.of());
    }

    private boolean hasInvalidIdentity(RawTransaction tx) {
        return tx.getWalletAddress() == null
                || tx.getWalletAddress().isBlank()
                || tx.getNetworkId() == null
                || tx.getNetworkId().isBlank();
    }

    private Map<String, List<RawTransaction>> groupByWalletAndNetwork(List<RawTransaction> validBatch) {
        return validBatch.stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getWalletAddress() + "|" + tx.getNetworkId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private int processGroupedBatch(Map<String, List<RawTransaction>> grouped) {
        int processed = 0;
        for (List<RawTransaction> pending : grouped.values()) {
            if (pending.isEmpty()) {
                continue;
            }
            RawTransaction first = pending.getFirst();
            String walletAddress = first.getWalletAddress();
            String networkIdStr = first.getNetworkId();
            NetworkId networkId = parseSupportedNetworkId(networkIdStr);
            if (networkId == null) {
                markGroupRetry(pending, "Unsupported network for v2 normalization: " + networkIdStr);
                continue;
            }

            sortByBlockNumber(pending);
            log.info("RawTxNormalizationJob processing {} PENDING raw for {} on {}",
                    pending.size(), walletAddress, networkIdStr);

            EstimatingBlockTimestampResolver estimator = buildEstimator(networkId, networkIdStr, pending);
            classificationProcessor.processBatch(pending, walletAddress, networkId, estimator);

            processed += pending.size();
            log.debug("RawTxNormalizationJob batch complete for {} on {}: {} raw normalized",
                    walletAddress, networkIdStr, pending.size());
        }
        return processed;
    }

    private NetworkId parseSupportedNetworkId(String networkIdStr) {
        try {
            NetworkId networkId = NetworkId.valueOf(networkIdStr);
            return networkId == NetworkId.SOLANA ? null : networkId;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void markGroupRetry(List<RawTransaction> pending, String reason) {
        for (RawTransaction tx : pending) {
            markRetry(tx, reason);
            rawTransactionRepository.save(tx);
        }
    }

    private void sortByBlockNumber(List<RawTransaction> pending) {
        pending.sort(Comparator.comparing(
                this::resolveBlockNumber,
                Comparator.nullsLast(Long::compareTo)
        ));
    }

    private EstimatingBlockTimestampResolver buildEstimator(
            NetworkId networkId,
            String networkIdStr,
            List<RawTransaction> pending
    ) {
        BlockTimestampResolver timestampResolver = findBlockTimestampResolver(networkId);
        if (timestampResolver == null) {
            return null;
        }

        long fromBlock = pending.stream()
                .map(this::resolveBlockNumber)
                .filter(b -> b != null)
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);
        long toBlock = pending.stream()
                .map(this::resolveBlockNumber)
                .filter(b -> b != null)
                .mapToLong(Long::longValue)
                .max()
                .orElse(fromBlock);
        if (fromBlock > toBlock) {
            return null;
        }

        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        double fallback = getFallbackAvgBlockTime(networkIdStr);
        estimator.calibrate(networkId, fromBlock, toBlock, timestampResolver, fallback);
        return estimator;
    }

    private Long resolveBlockNumber(RawTransaction tx) {
        return tx.getBlockNumber() != null ? tx.getBlockNumber() : ClassificationProcessor.getBlockNumberFromRaw(tx);
    }

    private void markRetry(RawTransaction tx, String reason) {
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount((tx.getRetryCount() == null ? 0 : tx.getRetryCount()) + 1);
        tx.setLastError(reason);
        tx.setNextRetryAt(java.time.Instant.now().plusSeconds(60));
    }

    private BlockTimestampResolver findBlockTimestampResolver(NetworkId networkId) {
        return blockTimestampResolvers.stream()
                .filter(r -> r.supports(networkId))
                .findFirst()
                .orElse(null);
    }

    private double getFallbackAvgBlockTime(String networkIdStr) {
        var entry = ingestionNetworkProperties.getNetwork().get(networkIdStr);
        if (entry != null && entry.getAvgBlockTimeSeconds() != null && entry.getAvgBlockTimeSeconds() > 0) {
            return entry.getAvgBlockTimeSeconds();
        }
        return 12.0;
    }
}
