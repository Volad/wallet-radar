package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawFetchCompleteEvent;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.pipeline.classification.ClassificationProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SyncStatusRepository syncStatusRepository;
    private final ClassificationProcessor classificationProcessor;
    private final ClassifierProperties classifierProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final List<BlockTimestampResolver> blockTimestampResolvers;

    @Scheduled(fixedDelayString = "${walletradar.ingestion.classifier.schedule-interval-ms:90000}")
    public void runScheduled() {
        log.debug("Classifier scheduled run triggered");
        runClassification();
    }

    @EventListener
    public void onRawFetchComplete(RawFetchCompleteEvent event) {
        log.info("Classifier triggered by RawFetchCompleteEvent for {} on {}", event.walletAddress(), event.networkId());
        runClassification();
    }

    public void runClassification() {
        Set<String> sessionWallets = syncStatusRepository.findAll().stream()
                .map(SyncStatus::getWalletAddress)
                .filter(a -> a != null && !a.isBlank())
                .collect(Collectors.toSet());

        List<SyncStatus> withRawComplete = syncStatusRepository.findAll().stream()
                .filter(s -> s.getWalletAddress() != null && s.getNetworkId() != null)
                .toList();

        if (withRawComplete.isEmpty()) {
            log.debug("Classifier: no sync_status with rawFetchComplete, skipping");
            return;
        }

        int batchSize = Math.max(100, classifierProperties.getBatchSize());
        int processed = 0;
        log.debug("Classifier run: {} wallet×network pair(s) with rawFetchComplete, batchSize={}", withRawComplete.size(), batchSize);

        for (SyncStatus s : withRawComplete) {
            String walletAddress = s.getWalletAddress();
            String networkIdStr = s.getNetworkId();
            NetworkId networkId;
            try {
                networkId = NetworkId.valueOf(networkIdStr);
            } catch (IllegalArgumentException e) {
                continue;
            }

            List<RawTransaction> pending = networkId == NetworkId.SOLANA
                    ? rawTransactionRepository.findByWalletAddressAndNetworkIdAndClassificationStatusOrderBySlotAsc(
                    walletAddress, networkIdStr, ClassificationStatus.PENDING, PageRequest.of(0, batchSize))
                    : rawTransactionRepository.findByWalletAddressAndNetworkIdAndClassificationStatusOrderByBlockNumberAsc(
                    walletAddress, networkIdStr, ClassificationStatus.PENDING, PageRequest.of(0, batchSize));

            if (pending.isEmpty()) continue;

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
                    estimator, nativePriceCache, nativeContract, sessionWallets);

            processed += pending.size();
            log.debug("Classifier batch complete for {} on {}: {} raw classified", walletAddress, networkIdStr, pending.size());
        }

        if (processed > 0) {
            log.info("Classifier run complete: {} raw transaction(s) processed across {} wallet×network pair(s)", processed, withRawComplete.size());
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
