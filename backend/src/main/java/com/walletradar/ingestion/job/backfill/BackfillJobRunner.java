package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RecalculateWalletRequestEvent;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.domain.WalletAddedEvent;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.classifier.InternalTransferReclassifier;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.walletradar.domain.SyncStatus.SyncStatusValue;

/**
 * Orchestrator: queue management, worker loops, event listeners, scheduled retry, reclassify trigger.
 * Delegates per-network backfill to {@link BackfillNetworkExecutor} (ADR-014, ADR-017).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillJobRunner {

    private record BackfillWorkItem(String walletAddress, NetworkId networkId) {}

    private final BlockingQueue<BackfillWorkItem> backfillQueue = new LinkedBlockingQueue<>();
    private final Set<String> inFlightItems = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean workersStarted = new AtomicBoolean(false);

    private final List<NetworkAdapter> networkAdapters;
    private final List<BlockHeightResolver> blockHeightResolvers;
    private final List<BlockTimestampResolver> blockTimestampResolvers;
    private final BackfillNetworkExecutor backfillNetworkExecutor;
    private final BackfillProperties backfillProperties;
    private final SyncProgressTracker syncProgressTracker;
    private final InternalTransferReclassifier internalTransferReclassifier;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SyncStatusRepository syncStatusRepository;
    @Qualifier("backfill-coordinator-executor")
    private final Executor backfillCoordinatorExecutor;
    @Qualifier("backfill-executor")
    private final Executor backfillExecutor;

    @EventListener
    public void onWalletAdded(WalletAddedEvent event) {
        backfillCoordinatorExecutor.execute(() -> enqueueWork(event.walletAddress(), event.networks()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        startWorkersIfNeeded();
        List<SyncStatus> incomplete = syncStatusRepository.findByStatusIn(
                Set.of(SyncStatusValue.PENDING, SyncStatusValue.RUNNING, SyncStatusValue.FAILED));
        if (incomplete.isEmpty()) return;
        int enqueued = 0;
        for (SyncStatus s : incomplete) {
            if (s.getWalletAddress() == null || s.getNetworkId() == null) continue;
            try {
                BackfillWorkItem item = new BackfillWorkItem(s.getWalletAddress(), NetworkId.valueOf(s.getNetworkId()));
                if (enqueueItemIfSupported(item)) enqueued++;
            } catch (IllegalArgumentException ignored) { /* unknown network */ }
        }
        if (enqueued > 0) {
            log.info("Resuming backfill: {} work item(s) enqueued (PENDING/RUNNING/FAILED), workers will take next available", enqueued);
        }
    }

    void runBackfill(String walletAddress, List<NetworkId> networks) {
        enqueueWork(walletAddress, networks);
    }

    void enqueueWork(String walletAddress, List<NetworkId> networks) {
        for (NetworkId n : networks) {
            enqueueItemIfSupported(new BackfillWorkItem(walletAddress, n));
        }
    }

    private boolean enqueueItemIfSupported(BackfillWorkItem item) {
        String key = itemKey(item.walletAddress(), item.networkId());
        if (!inFlightItems.add(key)) {
            log.debug("Skipping enqueue for {} {} — already in-flight", item.walletAddress(), item.networkId());
            return false;
        }
        NetworkAdapter adapter = findAdapter(item.networkId());
        BlockHeightResolver heightResolver = findBlockHeightResolver(item.networkId());
        BlockTimestampResolver timestampResolver = findBlockTimestampResolver(item.networkId());
        if (adapter == null || heightResolver == null || timestampResolver == null) {
            log.info("Skipping backfill for {} (adapter/block resolver not available)", item.networkId());
            inFlightItems.remove(key);
            syncProgressTracker.setComplete(item.walletAddress(), item.networkId().name());
            return false;
        }
        backfillQueue.offer(item);
        return true;
    }

    private static String itemKey(String walletAddress, NetworkId networkId) {
        return walletAddress + ":" + networkId.name();
    }

    private void startWorkersIfNeeded() {
        if (!workersStarted.compareAndSet(false, true)) return;
        int n = Math.max(1, backfillProperties.getWorkerThreads());
        for (int i = 0; i < n; i++) {
            backfillExecutor.execute(this::workerLoop);
        }
        log.info("Backfill worker loops started: {}", n);
    }

    private void workerLoop() {
        while (true) {
            try {
                BackfillWorkItem item = backfillQueue.take();
                try {
                    NetworkAdapter adapter = findAdapter(item.networkId());
                    BlockHeightResolver heightResolver = findBlockHeightResolver(item.networkId());
                    BlockTimestampResolver timestampResolver = findBlockTimestampResolver(item.networkId());
                    if (adapter == null || heightResolver == null || timestampResolver == null) {
                        syncProgressTracker.setComplete(item.walletAddress(), item.networkId().name());
                        continue;
                    }
                    backfillNetworkExecutor.runBackfillForNetwork(
                            item.walletAddress(), item.networkId(), adapter, heightResolver, timestampResolver);
                } finally {
                    inFlightItems.remove(itemKey(item.walletAddress(), item.networkId()));
                    triggerReclassifyIfAllDone();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Backfill worker interrupted");
                break;
            }
        }
    }

    private void triggerReclassifyIfAllDone() {
        if (!backfillQueue.isEmpty() || !inFlightItems.isEmpty()) return;
        boolean hasActiveWork = !syncStatusRepository
                .findByStatusIn(Set.of(SyncStatusValue.PENDING, SyncStatusValue.RUNNING))
                .isEmpty();
        if (!hasActiveWork) {
            backfillCoordinatorExecutor.execute(this::runReclassifyAndRecalc);
        }
    }

    private void runReclassifyAndRecalc() {
        Set<String> sessionWallets = syncStatusRepository.findAll().stream()
                .map(SyncStatus::getWalletAddress)
                .collect(Collectors.toSet());
        List<EconomicEvent> reclassified = internalTransferReclassifier.reclassify(sessionWallets);
        Set<String> affectedWallets = reclassified.stream().map(EconomicEvent::getWalletAddress).collect(Collectors.toSet());
        for (String w : affectedWallets) {
            applicationEventPublisher.publishEvent(new RecalculateWalletRequestEvent(w));
        }
    }

    public boolean isIdle() {
        return backfillQueue.isEmpty() && inFlightItems.isEmpty();
    }

    @Scheduled(fixedDelayString = "${walletradar.ingestion.backfill.reclassify-schedule-interval-ms:300000}")
    public void scheduledReclassifyWhenIdle() {
        if (!isIdle()) return;
        backfillCoordinatorExecutor.execute(this::runReclassifyAndRecalc);
    }

    @Scheduled(fixedDelayString = "${walletradar.ingestion.backfill.retry-scheduler-interval-ms:120000}")
    public void retryFailedBackfills() {
        Instant now = Instant.now();
        int maxRetries = backfillProperties.getMaxRetries();

        List<SyncStatus> failed = syncStatusRepository.findByStatusIn(Set.of(SyncStatusValue.FAILED));
        int enqueued = 0;
        for (SyncStatus s : failed) {
            if (s.getWalletAddress() == null || s.getNetworkId() == null) continue;

            if (s.getRetryCount() >= maxRetries) {
                s.setStatus(SyncStatusValue.ABANDONED);
                s.setSyncBannerMessage("Abandoned after " + maxRetries + " retries");
                s.setUpdatedAt(now);
                syncStatusRepository.save(s);
                log.info("Backfill ABANDONED for {} on {} after {} retries", s.getWalletAddress(), s.getNetworkId(), maxRetries);
                continue;
            }

            if (s.getNextRetryAfter() != null && now.isBefore(s.getNextRetryAfter())) {
                continue;
            }

            try {
                NetworkId networkId = NetworkId.valueOf(s.getNetworkId());
                BackfillWorkItem item = new BackfillWorkItem(s.getWalletAddress(), networkId);
                if (enqueueItemIfSupported(item)) {
                    enqueued++;
                }
            } catch (IllegalArgumentException ignored) { /* unknown network */ }
        }
        if (enqueued > 0) {
            log.info("Retry scheduler: re-enqueued {} failed backfill(s)", enqueued);
        }
    }

    private NetworkAdapter findAdapter(NetworkId networkId) {
        return networkAdapters.stream().filter(a -> a.supports(networkId)).findFirst().orElse(null);
    }

    private BlockHeightResolver findBlockHeightResolver(NetworkId networkId) {
        return blockHeightResolvers.stream().filter(r -> r.supports(networkId)).findFirst().orElse(null);
    }

    private BlockTimestampResolver findBlockTimestampResolver(NetworkId networkId) {
        return blockTimestampResolvers.stream().filter(r -> r.supports(networkId)).findFirst().orElse(null);
    }
}
