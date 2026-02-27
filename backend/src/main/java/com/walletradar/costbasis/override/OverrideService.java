package com.walletradar.costbasis.override;

import com.walletradar.domain.CostBasisOverride;
import com.walletradar.domain.CostBasisOverrideRepository;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RecalcJob;
import com.walletradar.domain.RecalcJobRepository;
import com.walletradar.costbasis.event.OverrideSavedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * T-017: PUT/DELETE override → upsert/deactivate in cost_basis_overrides; create RecalcJob (PENDING);
 * publish OverrideSavedEvent. On-chain events only; manual compensating events are not overridden (AC-09).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverrideService {

    public static final String EVENT_NOT_FOUND = "EVENT_NOT_FOUND";
    public static final String OVERRIDE_EXISTS = "OVERRIDE_EXISTS";

    private final EconomicEventRepository economicEventRepository;
    private final CostBasisOverrideRepository costBasisOverrideRepository;
    private final RecalcJobRepository recalcJobRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Set manual price override for an on-chain event. Upserts override (isActive=true), creates RecalcJob,
     * publishes OverrideSavedEvent. Returns jobId for polling.
     *
     * @throws OverrideServiceException EVENT_NOT_FOUND if event missing or manual; OVERRIDE_EXISTS if active override already present
     */
    public String setOverride(String eventId, BigDecimal priceUsd, String note) {
        EconomicEvent event = economicEventRepository.findById(eventId)
                .orElseThrow(() -> new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId));
        if (event.getEventType() == EconomicEventType.MANUAL_COMPENSATING) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Override only for on-chain events");
        }
        if (costBasisOverrideRepository.findByEconomicEventIdAndActiveTrue(eventId).isPresent()) {
            throw new OverrideServiceException(OVERRIDE_EXISTS, "Active override already exists for event: " + eventId);
        }

        CostBasisOverride override = costBasisOverrideRepository.findFirstByEconomicEventId(eventId)
                .orElse(new CostBasisOverride());
        override.setEconomicEventId(eventId);
        override.setPriceUsd(priceUsd);
        override.setNote(note);
        override.setActive(true);
        override.setCreatedAt(Instant.now());
        costBasisOverrideRepository.save(override);

        String jobId = createRecalcJobAndPublish(event);
        log.info("Override set for event {}; recalc job {}", eventId, jobId);
        return jobId;
    }

    /**
     * Revert override for an event (deactivate). Creates RecalcJob and publishes OverrideSavedEvent.
     *
     * @throws OverrideServiceException EVENT_NOT_FOUND if event not found
     */
    public String revertOverride(String eventId) {
        EconomicEvent event = economicEventRepository.findById(eventId)
                .orElseThrow(() -> new OverrideServiceException(EVENT_NOT_FOUND, "Event not found: " + eventId));
        if (event.getEventType() == EconomicEventType.MANUAL_COMPENSATING) {
            throw new OverrideServiceException(EVENT_NOT_FOUND, "Override only for on-chain events");
        }

        costBasisOverrideRepository.findFirstByEconomicEventId(eventId).ifPresent(o -> {
            o.setActive(false);
            costBasisOverrideRepository.save(o);
        });

        String jobId = createRecalcJobAndPublish(event);
        log.info("Override reverted for event {}; recalc job {}", eventId, jobId);
        return jobId;
    }

    private String createRecalcJobAndPublish(EconomicEvent event) {
        RecalcJob job = new RecalcJob();
        job.setStatus(RecalcJob.RecalcStatus.PENDING);
        job.setWalletAddress(event.getWalletAddress());
        NetworkId networkId = event.getNetworkId();
        job.setNetworkId(networkId != null ? networkId.name() : null);
        job.setAssetContract(event.getAssetContract());
        job.setAssetSymbol(event.getAssetSymbol());
        job.setCreatedAt(Instant.now());
        job = recalcJobRepository.save(job);
        applicationEventPublisher.publishEvent(new OverrideSavedEvent(this, job.getId()));
        return job.getId();
    }
}
