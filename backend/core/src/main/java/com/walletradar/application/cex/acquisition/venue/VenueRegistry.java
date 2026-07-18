package com.walletradar.application.cex.acquisition.venue;

import com.walletradar.application.cex.port.VenueDescriptor;
import com.walletradar.application.cex.port.VenueLiveBalanceCapability;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.domain.wallet.WalletRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Registry of all registered CEX venue descriptors.
 *
 * <p><b>INGESTION-PLANE ONLY.</b> This component is injected ONLY into:</p>
 * <ul>
 *   <li>Normalization (canonical builders / boundary-contract stamper)</li>
 *   <li>Backfill / extraction coordinators</li>
 *   <li>{@link RoutingCexLiveBalancePort} (live-balance routing)</li>
 * </ul>
 *
 * <p>It must NOT be injected into post-normalization consumers (costbasis, portfolio, pricing,
 * linking, api). Enforced by ArchUnit in {@code ModuleDependencyArchTest}.</p>
 */
@Component
@Slf4j
public class VenueRegistry {

    private final List<VenueDescriptor> descriptors;

    public VenueRegistry(ObjectProvider<VenueDescriptor> descriptorProvider) {
        this.descriptors = descriptorProvider.stream().toList();
        log.info("VenueRegistry initialized with {} venue descriptors: {}",
                descriptors.size(),
                descriptors.stream().map(d -> d.getClass().getSimpleName()).toList());
    }

    /**
     * Returns all registered venue descriptors.
     */
    public List<VenueDescriptor> all() {
        return descriptors;
    }

    /**
     * Finds the descriptor whose {@link com.walletradar.application.cex.port.VenueIdentity#venueId()}
     * matches the given venue slug (case-insensitive).
     */
    public Optional<VenueDescriptor> findByVenueId(String venueId) {
        if (venueId == null || venueId.isBlank()) {
            return Optional.empty();
        }
        return descriptors.stream()
                .filter(d -> d.venueId().equalsIgnoreCase(venueId.trim()))
                .findFirst();
    }

    /**
     * Finds the descriptor that owns the given account ref or wallet-ref string
     * (via {@link com.walletradar.application.cex.port.VenueIdentity#ownsRef}).
     */
    public Optional<VenueDescriptor> findByRef(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            return Optional.empty();
        }
        return descriptors.stream()
                .filter(d -> d.ownsRef(accountRef))
                .findFirst();
    }

    /**
     * Finds the descriptor that owns the given integration id
     * (via {@link com.walletradar.application.cex.port.VenueIdentity#ownsIntegrationId}).
     */
    public Optional<VenueDescriptor> findByIntegrationId(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        return descriptors.stream()
                .filter(d -> d.ownsIntegrationId(integrationId))
                .findFirst();
    }

    /**
     * Returns the live-balance port for the venue that owns {@code integrationId}, or empty
     * if no venue owns it or the venue does not support live balances.
     */
    public Optional<CexLiveBalancePort> liveBalancePortFor(String integrationId) {
        return findByIntegrationId(integrationId)
                .filter(d -> d instanceof VenueLiveBalanceCapability)
                .map(d -> (VenueLiveBalanceCapability) d)
                .flatMap(VenueLiveBalanceCapability::liveBalancePort);
    }

    /**
     * Resolves the descriptor for the given wallet address via {@link WalletRef} grammar.
     * Returns empty for on-chain addresses.
     */
    public Optional<VenueDescriptor> findByWalletAddress(String walletAddress) {
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.venueId() == null) {
            return Optional.empty();
        }
        return findByVenueId(ref.venueId());
    }
}
