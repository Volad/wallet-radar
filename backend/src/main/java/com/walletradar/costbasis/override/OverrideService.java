package com.walletradar.costbasis.override;

import com.walletradar.costbasis.event.OverrideSavedEvent;
import com.walletradar.domain.accounting.CostBasisOverride;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.accounting.RecalcJob;
import com.walletradar.domain.accounting.RecalcJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * T-017: PUT/DELETE override → upsert/deactivate in cost_basis_overrides; create RecalcJob (PENDING);
 * publish OverrideSavedEvent. On-chain normalized legs only; manual compensating legs are not overridden.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverrideService {

    public static final String EVENT_NOT_FOUND = "EVENT_NOT_FOUND";
    public static final String OVERRIDE_EXISTS = "OVERRIDE_EXISTS";

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final CostBasisOverrideRepository costBasisOverrideRepository;
    private final RecalcJobRepository recalcJobRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Set manual price override for normalized leg id (<normalizedTxId>:<legIndex>).
     * Returns jobId for polling.
     */
    public String setOverride(String eventId, BigDecimal priceUsd, String note) {
        FlowRef ref = resolveLeg(eventId);
        if (ref.tx().getType() == NormalizedTransactionType.MANUAL_COMPENSATING) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Override only for on-chain events");
        }
        if (costBasisOverrideRepository.findByNormalizedLegIdAndActiveTrue(eventId).isPresent()) {
            throw new OverrideServiceException(OVERRIDE_EXISTS, "Active override already exists for event: " + eventId);
        }

        CostBasisOverride override = costBasisOverrideRepository.findFirstByNormalizedLegId(eventId)
                .orElse(new CostBasisOverride());
        override.setNormalizedLegId(eventId);
        override.setPriceUsd(priceUsd);
        override.setNote(note);
        override.setActive(true);
        override.setCreatedAt(Instant.now());
        costBasisOverrideRepository.save(override);

        String jobId = createRecalcJobAndPublish(ref);
        log.info("Override set for event {}; recalc job {}", eventId, jobId);
        return jobId;
    }

    /**
     * Revert override for normalized leg id (<normalizedTxId>:<legIndex>).
     */
    public String revertOverride(String eventId) {
        FlowRef ref = resolveLeg(eventId);
        if (ref.tx().getType() == NormalizedTransactionType.MANUAL_COMPENSATING) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Override only for on-chain events");
        }

        costBasisOverrideRepository.findFirstByNormalizedLegId(eventId).ifPresent(o -> {
            o.setActive(false);
            costBasisOverrideRepository.save(o);
        });

        String jobId = createRecalcJobAndPublish(ref);
        log.info("Override reverted for event {}; recalc job {}", eventId, jobId);
        return jobId;
    }

    private FlowRef resolveLeg(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId);
        }
        int sep = eventId.lastIndexOf(':');
        if (sep <= 0 || sep == eventId.length() - 1) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId);
        }
        String txId = eventId.substring(0, sep);
        int legIndex;
        try {
            legIndex = Integer.parseInt(eventId.substring(sep + 1));
        } catch (NumberFormatException e) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId);
        }

        NormalizedTransaction tx = normalizedTransactionRepository.findById(txId)
                .orElseThrow(() -> new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId));
        if (tx.getFlows() == null || legIndex < 0 || legIndex >= tx.getFlows().size()) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId);
        }
        NormalizedTransaction.Flow leg = tx.getFlows().get(legIndex);
        return new FlowRef(tx, leg);
    }

    private String createRecalcJobAndPublish(FlowRef ref) {
        RecalcJob job = new RecalcJob();
        job.setStatus(RecalcJob.RecalcStatus.PENDING);
        job.setWalletAddress(ref.tx().getWalletAddress());
        NetworkId networkId = ref.tx().getNetworkId();
        job.setNetworkId(networkId != null ? networkId.name() : null);
        job.setAssetContract(ref.leg().getAssetContract());
        job.setAssetSymbol(ref.leg().getAssetSymbol());
        job.setCreatedAt(Instant.now());
        job = recalcJobRepository.save(job);
        applicationEventPublisher.publishEvent(new OverrideSavedEvent(this, job.getId()));
        return job.getId();
    }

    private record FlowRef(NormalizedTransaction tx, NormalizedTransaction.Flow leg) {
    }
}
