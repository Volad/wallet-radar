package com.walletradar.costbasis.override;

import com.walletradar.costbasis.engine.AvcoEngine;
import com.walletradar.costbasis.event.OverrideSavedEvent;
import com.walletradar.costbasis.event.RecalcCompleteEvent;
import com.walletradar.domain.accounting.AssetPositionRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.accounting.RecalcJob;
import com.walletradar.domain.accounting.RecalcJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * T-017: Consumes OverrideSavedEvent (and later manual-tx events); runs AvcoEngine.replayFromBeginning async on
 * recalc-executor; sets RecalcJob COMPLETE/FAILED; publishes RecalcCompleteEvent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecalcJobService {

    private final RecalcJobRepository recalcJobRepository;
    private final AvcoEngine avcoEngine;
    private final AssetPositionRepository assetPositionRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @EventListener
    @Async("recalc-executor")
    public void onOverrideSaved(OverrideSavedEvent event) {
        long startedAt = System.currentTimeMillis();
        String jobId = event.getJobId();
        RecalcJob job = recalcJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("RecalcJob not found: {}", jobId);
            return;
        }
        if (job.getStatus() != RecalcJob.RecalcStatus.PENDING) {
            log.debug("RecalcJob {} already processed: {}", jobId, job.getStatus());
            return;
        }

        job.setStatus(RecalcJob.RecalcStatus.RUNNING);
        recalcJobRepository.save(job);
        log.info("RecalcJob {} started", jobId);

        String walletAddress = job.getWalletAddress();
        String networkIdStr = job.getNetworkId();
        String assetContract = job.getAssetContract();
        if (walletAddress == null || networkIdStr == null || assetContract == null) {
            markFailed(job, "Missing wallet, network or asset");
            applicationEventPublisher.publishEvent(new RecalcCompleteEvent(this, jobId, "FAILED"));
            return;
        }

        try {
            NetworkId networkId = NetworkId.valueOf(networkIdStr);
            avcoEngine.replayFromBeginning(walletAddress, networkId, assetContract);

            BigDecimal newAvco = assetPositionRepository
                    .findByWalletAddressAndNetworkIdAndAssetContract(walletAddress, networkIdStr, assetContract)
                    .map(p -> p.getPerWalletAvco())
                    .orElse(null);

            job.setStatus(RecalcJob.RecalcStatus.COMPLETE);
            job.setCompletedAt(Instant.now());
            job.setNewPerWalletAvco(newAvco);
            recalcJobRepository.save(job);
            applicationEventPublisher.publishEvent(new RecalcCompleteEvent(this, jobId, "COMPLETE"));
            log.info("RecalcJob {} COMPLETE for {} / {} / {}: durationMs={}",
                    jobId, walletAddress, networkIdStr, assetContract, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("RecalcJob {} failed: durationMs={}, message={}",
                    jobId, System.currentTimeMillis() - startedAt, e.getMessage(), e);
            markFailed(job, e.getMessage());
            applicationEventPublisher.publishEvent(new RecalcCompleteEvent(this, jobId, "FAILED"));
        }
    }

    private void markFailed(RecalcJob job, String reason) {
        job.setStatus(RecalcJob.RecalcStatus.FAILED);
        job.setCompletedAt(Instant.now());
        recalcJobRepository.save(job);
        log.warn("RecalcJob {} FAILED: {}", job.getId(), reason);
    }

    /** Optional TTL cleanup: delete COMPLETE/FAILED jobs older than 24h. Runs every hour. */
    @Scheduled(fixedRate = 3600_000)
    public void deleteExpiredJobs() {
        long startedAt = System.currentTimeMillis();
        log.info("RecalcJobCleanupJob started");
        try {
            Instant cutoff = Instant.now().minusSeconds(24 * 3600);
            recalcJobRepository.deleteByStatusInAndCompletedAtBefore(
                    Set.of(RecalcJob.RecalcStatus.COMPLETE, RecalcJob.RecalcStatus.FAILED), cutoff);
            log.info("RecalcJobCleanupJob finished: cutoff={}, durationMs={}", cutoff, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("RecalcJobCleanupJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }
}
