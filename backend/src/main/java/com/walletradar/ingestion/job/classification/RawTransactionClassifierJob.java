package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawFetchCompleteEvent;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.pipeline.classification.ClassificationProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Standalone classifier job (ADR-021). Processes PENDING raw transactions in batches.
 * Triggered by RawFetchCompleteEvent and @Scheduled(90s).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RawTransactionClassifierJob {

    private static final Map<NetworkId, String> NATIVE_TOKEN_ADDRESS = Map.of(
            NetworkId.ETHEREUM,  "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.ARBITRUM,  "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.OPTIMISM,  "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.BASE,      "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.MANTLE,    "0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8",
            NetworkId.POLYGON,   "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270",
            NetworkId.BSC,       "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c",
            NetworkId.AVALANCHE, "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7",
            NetworkId.SOLANA,    "So11111111111111111111111111111111111111112"
    );

    private final RawTransactionRepository rawTransactionRepository;
    private final ClassificationProcessor classificationProcessor;
    private final ClassifierProperties classifierProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final List<BlockTimestampResolver> blockTimestampResolvers;

    @Scheduled(fixedDelayString = "${walletradar.ingestion.classifier.schedule-interval-ms:90000}")
    public void runScheduled() {
        runClassification("scheduled");
    }

    @EventListener
    public void onRawFetchComplete(RawFetchCompleteEvent event) {
        log.info("Classifier triggered by RawFetchCompleteEvent for {} on {}", event.walletAddress(), event.networkId());
        runClassification("event");
    }

    public void runClassification() {
        runClassification("manual");
    }

    private void runClassification(String trigger) {
        long startedAt = System.currentTimeMillis();
        int processed = 0;
        int pairs = 0;
        boolean failed = false;
        log.info("RawTransactionClassifierJob started: trigger={}", trigger);
        try {
            int batchSize = Math.max(100, classifierProperties.getBatchSize());
            int loops = 0;

            while (true) {
                List<RawTransaction> pendingBatch = rawTransactionRepository.findByClassificationStatus(
                        ClassificationStatus.PENDING,
                        PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "createdAt"))
                );
                if (pendingBatch.isEmpty()) {
                    if (loops == 0) {
                        log.debug("Classifier: no PENDING raw transactions, skipping");
                    }
                    break;
                }
                loops++;

                List<RawTransaction> invalid = pendingBatch.stream()
                        .filter(tx -> tx.getWalletAddress() == null || tx.getWalletAddress().isBlank()
                                || tx.getNetworkId() == null || tx.getNetworkId().isBlank())
                        .toList();
                for (RawTransaction tx : invalid) {
                    tx.setClassificationStatus(ClassificationStatus.FAILED);
                    rawTransactionRepository.save(tx);
                }

                Map<String, List<RawTransaction>> grouped = pendingBatch.stream()
                        .filter(tx -> tx.getWalletAddress() != null && !tx.getWalletAddress().isBlank())
                        .filter(tx -> tx.getNetworkId() != null && !tx.getNetworkId().isBlank())
                        .collect(Collectors.groupingBy(
                                tx -> tx.getWalletAddress() + "|" + tx.getNetworkId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
                pairs += grouped.size();

                for (List<RawTransaction> pending : grouped.values()) {
                    RawTransaction first = pending.get(0);
                    String walletAddress = first.getWalletAddress();
                    String networkIdStr = first.getNetworkId();
                    NetworkId networkId;
                    try {
                        networkId = NetworkId.valueOf(networkIdStr);
                    } catch (IllegalArgumentException e) {
                        for (RawTransaction tx : pending) {
                            tx.setClassificationStatus(ClassificationStatus.FAILED);
                            rawTransactionRepository.save(tx);
                        }
                        continue;
                    }

                    if (networkId == NetworkId.SOLANA) {
                        pending.sort(Comparator.comparing(
                                RawTransaction::getSlot,
                                Comparator.nullsLast(Long::compareTo)
                        ));
                    } else {
                        pending.sort(Comparator.comparing(
                                tx -> tx.getBlockNumber() != null ? tx.getBlockNumber() : ClassificationProcessor.getBlockNumberFromRaw(tx),
                                Comparator.nullsLast(Long::compareTo)
                        ));
                    }

                    log.info("Classifier processing {} PENDING raw for {} on {}", pending.size(), walletAddress, networkIdStr);

                    EstimatingBlockTimestampResolver estimator = null;
                    if (networkId != NetworkId.SOLANA) {
                        BlockTimestampResolver timestampResolver = findBlockTimestampResolver(networkId);
                        if (timestampResolver != null) {
                            estimator = new EstimatingBlockTimestampResolver();
                            long fromBlock = pending.stream()
                                    .map(r -> r.getBlockNumber() != null ? r.getBlockNumber() : ClassificationProcessor.getBlockNumberFromRaw(r))
                                    .filter(b -> b != null)
                                    .mapToLong(Long::longValue)
                                    .min()
                                    .orElse(0);
                            long toBlock = pending.stream()
                                    .map(r -> r.getBlockNumber() != null ? r.getBlockNumber() : ClassificationProcessor.getBlockNumberFromRaw(r))
                                    .filter(b -> b != null)
                                    .mapToLong(Long::longValue)
                                    .max()
                                    .orElse(fromBlock);
                            if (fromBlock <= toBlock) {
                                double fallback = getFallbackAvgBlockTime(networkIdStr);
                                estimator.calibrate(networkId, fromBlock, toBlock, timestampResolver, fallback);
                            }
                        }
                    }

                    Map<LocalDate, BigDecimal> nativePriceCache = new ConcurrentHashMap<>();
                    String nativeContract = NATIVE_TOKEN_ADDRESS.get(networkId);

                    classificationProcessor.processBatch(pending, walletAddress, networkId,
                            estimator, nativePriceCache, nativeContract);

                    processed += pending.size();
                    log.debug("Classifier batch complete for {} on {}: {} raw classified", walletAddress, networkIdStr, pending.size());
                }
            }
        } catch (Exception e) {
            failed = true;
            log.error("RawTransactionClassifierJob failed: trigger={}, processed={}, durationMs={}",
                    trigger, processed, System.currentTimeMillis() - startedAt, e);
            throw e;
        } finally {
            log.info("RawTransactionClassifierJob finished: trigger={}, status={}, processed={}, walletNetworkPairs={}, durationMs={}",
                    trigger, failed ? "FAILED" : "OK", processed, pairs, System.currentTimeMillis() - startedAt);
        }
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
