package com.walletradar;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces package boundaries for application runtime modules (Track A / A3).
 *
 * Core invariant: venue specificity ends at normalization.
 * Post-normalization packages (costbasis, portfolio, pricing, linking, api) must NOT
 * depend on the ingestion-plane venue package or VenueRegistry / venue descriptors.
 */
class ModuleDependencyArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void scan() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletradar");
    }

    @Test
    void domain_must_not_depend_on_runtime_layers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..config..", "..api..", "..backend..");
        rule.check(classes);
    }

    @Test
    void common_must_not_depend_on_other_app_modules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.walletradar.platform.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..domain..",
                        "com.walletradar.config..",
                        "..api..",
                        "..backend.."
                );
        rule.check(classes);
    }

    @Test
    void api_should_not_import_repository_classes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        rule.check(classes);
    }

    @Test
    void domain_slices_must_not_have_cycles() {
        ArchRule rule = slices()
                .matching("com.walletradar.domain.(*)..")
                .should().beFreeOfCycles();
        rule.check(classes);
    }

    @Test
    void backfill_wallet_command_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.backfill.wallet.command..")
                .should().dependOnClassesThat().resideInAPackage("..application..job..");
        rule.check(classes);
    }

    @Test
    void backfill_query_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.backfill.query..")
                .should().dependOnClassesThat().resideInAPackage("..application..job..");
        rule.check(classes);
    }

    @Test
    void backfill_sync_progress_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.backfill.sync.progress..")
                .should().dependOnClassesThat().resideInAPackage("..application..job..");
        rule.check(classes);
    }

    /**
     * Core invariant (Phase G): venue specificity ends at normalization.
     *
     * The multi-venue routing classes (VenueRegistry, VenueDescriptor interface, concrete
     * venue descriptors) must NOT be depended upon by post-normalization packages.
     * Venue-specific concrete classes (API clients, mappers) may still appear inside
     * venue-named adapter sub-packages (e.g. pricing.resolver.external.dzengi, linking.job
     * bybit-specific services) — these adapters are BY DESIGN single-venue and will never
     * be branched by venue name at their call site.
     *
     * The rule that MUST hold: no post-normalization class may depend on VenueRegistry,
     * VenueDescriptor, or concrete descriptor implementations.  This ensures adding a venue
     * never forces edits to the consumption plane.
     */
    @Test
    void post_normalization_packages_must_not_depend_on_VenueRegistry_or_descriptors() {
        // Matches VenueRegistry, VenueDescriptor interface, and any concrete *VenueDescriptor
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "..application.costbasis..",
                        "..application.portfolio..",
                        "..application.pricing..",
                        "..application.linking..",
                        "..api.."
                )
                .should().dependOnClassesThat()
                .haveNameMatching(".*\\.(VenueRegistry|VenueDescriptor|BybitVenueDescriptor|DzengiVenueDescriptor)$");
        rule.check(classes);
    }

    /**
     * WS-8 (ADR-073): network specificity ends at normalization — the generalization of the
     * ADR-052 venue invariant above from the venue axis to the network axis.
     *
     * <p>Post-normalization read/query view-assembly packages must NOT depend on
     * {@link com.walletradar.domain.common.NetworkAddressFormat} — the network-family classifier
     * whose {@code isEvm(...)} is the runtime "which chain family is this?" branch (the network
     * analogue of {@code VenueRegistry}/venue descriptors). Read paths must instead consume the
     * semantic capability flags stamped at normalization time
     * ({@code receiptBearingCollateral}, {@code lpConcentrated}, {@code custodialOffChain}).</p>
     *
     * <p>Scope note: this rule targets {@code NetworkAddressFormat} only, NOT {@link NetworkId}.
     * DTOs, keys, and records may freely <em>carry</em> {@code NetworkId} (grouping, labels,
     * serialization) — that is data, not a network-family branch. The single documented carve-out is
     * the WS-3 live-position true-up machinery ({@code *LiveTrueUpService}), which sits upstream of
     * the read plane and legitimately gates the live reader by network family (mirroring the venue
     * rule's single-venue adapter allowance). Ingestion/enrichment adapters (balance providers, LP
     * on-chain readers, normalization builders) are outside these packages by design.</p>
     */
    @Test
    void post_normalization_read_query_packages_must_not_depend_on_NetworkAddressFormat() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "..application.costbasis..",
                        "..application.portfolio..",
                        "..application.pricing..",
                        "..application.liquiditypools..",
                        "..application.lending..",
                        "..api.."
                )
                .and().haveSimpleNameNotEndingWith("LiveTrueUpService")
                .should().dependOnClassesThat()
                .haveNameMatching(".*\\.NetworkAddressFormat$");
        rule.check(classes);
    }

    /**
     * Supplementary check: the API layer must not expose venue-specific acquisition types
     * directly (e.g. BybitStreamSyncSnapshot).  The session settings query service must
     * translate to a neutral DTO before handing data to the controller.
     */
    @Test
    void api_layer_must_not_depend_on_cex_acquisition_venue_package() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage(
                        "..application.cex.acquisition.venue.."
                );
        rule.check(classes);
    }

    /**
     * A4 (RR-7a): The normalization module must NOT import from liquiditypools.enrichment.
     *
     * LP enrichment runs at a later pipeline stage after normalization.  Classification-time
     * CL math must live in {@code normalization..classification.support} (e.g. LpClMathSupport)
     * so the V4 fee reader never couples to the enrichment layer at classification time.
     */
    @Test
    void normalization_must_not_depend_on_liquiditypools_enrichment() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.normalization..")
                .should().dependOnClassesThat().resideInAPackage("..application.liquiditypools.enrichment..");
        rule.check(classes);
    }

    /**
     * A4 (RR-7b): The cost-basis replay must NOT import from platform.networks.evm.rpc.
     *
     * Archive-RPC calls are a classification-time concern (V4 fee reader, vault share rate).
     * Results must be persisted (v4_pool_state_cache, historical_prices) before replay runs.
     * A replay dependency on the RPC layer would mean live-node calls inside the accounting
     * engine — a non-determinism risk and an ordering violation.
     */
    @Test
    void costbasis_replay_must_not_depend_on_evm_rpc() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.costbasis..")
                .should().dependOnClassesThat().resideInAPackage("..platform.networks.evm.rpc..");
        rule.check(classes);
    }
}
