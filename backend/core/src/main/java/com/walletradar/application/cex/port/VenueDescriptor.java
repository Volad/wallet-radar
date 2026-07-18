package com.walletradar.application.cex.port;

/**
 * Composite venue descriptor — aggregates all ingestion-plane capabilities for a single CEX venue.
 *
 * <p>Implement this interface (plus optionally {@link VenueLiveBalanceCapability}) for each venue.
 * The {@link VenueRegistry} auto-discovers all {@code @Component} implementations.</p>
 *
 * <p>Relationship to existing SPI:
 * <ul>
 *   <li>{@link VenueIdentity} extends {@link CexVenueProfile} (Track B1 — backward-compatible).</li>
 *   <li>{@link VenueWalletModel} encapsulates sub-account topology.</li>
 *   <li>{@link VenueExternalCapitalPolicy} stamps the neutral boundary contract at normalization time.</li>
 *   <li>{@link VenueLiveBalanceCapability} is optional — implement if the venue supports live snapshots.</li>
 * </ul>
 *
 * <p><b>Ingestion-plane contract:</b> this type and all its sub-interfaces must NOT be referenced
 * from post-normalization packages (costbasis, portfolio, pricing, linking, api).
 * Enforced by ArchUnit in {@code ModuleDependencyArchTest}.</p>
 */
public interface VenueDescriptor extends VenueIdentity, VenueWalletModel, VenueExternalCapitalPolicy {
    // Composite marker — all capability methods are inherited from the constituent interfaces.
}
