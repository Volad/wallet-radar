package com.walletradar.application.costbasis.breakeven;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ADR-062 §2 attribution resolution. Partitions each accounting family to exactly one target
 * family whose break-even receives the source's Market-lane realized P&amp;L. Read-model only.
 */
@Service
public class BreakEvenAttributionService {

    private static final Logger log = LoggerFactory.getLogger(BreakEvenAttributionService.class);
    private static final String FAMILY_PREFIX = "FAMILY:";
    private static final String CLUSTER_PREFIX = "CLUSTER:";
    /**
     * ADR-062 (Wave 3, AC-8): the intra-cluster loss-floor carve-out applies to attributions whose
     * source is a staking cluster. Cluster keys follow the {@code CLUSTER:*_STAKING} convention
     * (see {@link AccountingAssetClassificationSupport#normalizationClusterForSymbol(String)}).
     */
    private static final String STAKING_CLUSTER_SUFFIX = "_STAKING";

    private final Map<String, String> sourceToTarget;
    private final Set<String> foldHeldExposureSources;
    private final OffsetLane offsetLane;

    public BreakEvenAttributionService(BreakEvenAttributionLoader loader) {
        BreakEvenAttributionLoader.LoadedBreakEvenAttribution loaded = loader.loadFromClasspath();
        this.sourceToTarget = loaded.sourceToTarget();
        this.foldHeldExposureSources = loaded.foldHeldExposureSources();
        this.offsetLane = loaded.offsetLane();
        log.info("Loaded break-even attribution: {} source->target mappings, {} fold-held-exposure sources, offsetLane={}",
                sourceToTarget.size(), foldHeldExposureSources.size(), offsetLane);
    }

    /**
     * ADR-062 Wave 3 resolution result: the target family, plus whether the resolution went through
     * a staking cluster (AC-8 loss-floor carve-out) and whether the source's held exposure is folded
     * into the target's denominator/basis (AC-9 / D8).
     */
    public record Attribution(String target, boolean viaStakingCluster, boolean foldHeldExposure) {
    }

    /**
     * ADR-062 §2 (Wave 3): full attribution resolution. (1) an explicit {@code FAMILY:*} source
     * mapping wins; (2) else the symbol's normalization cluster mapping applies when its target
     * differs from the family; (3) else the family maps to itself. The returned
     * {@link Attribution#viaStakingCluster()} is {@code true} only when a {@code CLUSTER:*_STAKING}
     * source drove the resolution onto a different target (AC-8). {@link Attribution#foldHeldExposure()}
     * reflects the matched source's config flag (AC-9).
     */
    public Attribution resolve(String familyIdentity, String representativeSymbol) {
        if (familyIdentity == null || familyIdentity.isBlank()) {
            return new Attribution(familyIdentity, false, false);
        }
        String explicit = sourceToTarget.get(familyIdentity);
        if (explicit != null) {
            boolean redirected = !explicit.equals(familyIdentity);
            return new Attribution(
                    explicit,
                    redirected && isStakingCluster(familyIdentity),
                    redirected && foldHeldExposureSources.contains(familyIdentity));
        }
        String cluster = AccountingAssetClassificationSupport.normalizationClusterForSymbol(representativeSymbol);
        if (cluster != null) {
            String target = sourceToTarget.get(cluster);
            if (target != null && !target.equals(familyIdentity)) {
                return new Attribution(
                        target,
                        isStakingCluster(cluster),
                        foldHeldExposureSources.contains(cluster));
            }
        }
        return new Attribution(familyIdentity, false, false);
    }

    private static boolean isStakingCluster(String source) {
        return source != null && source.startsWith(CLUSTER_PREFIX) && source.endsWith(STAKING_CLUSTER_SUFFIX);
    }

    /**
     * ADR-062 (2026-07-21 amendment): the configured effective-cost offset lane
     * ({@link OffsetLane#NET} default, or {@link OffsetLane#MARKET}).
     */
    public OffsetLane offsetLane() {
        return offsetLane;
    }

    /**
     * ADR-062 §2: (1) an explicit {@code FAMILY:*} source mapping wins; (2) else the symbol's
     * normalization cluster mapping applies when its target differs from the family; (3) else the
     * family maps to itself. Null/blank {@code familyIdentity} is returned unchanged.
     */
    public String resolveTarget(String familyIdentity, String representativeSymbol) {
        return resolve(familyIdentity, representativeSymbol).target();
    }

    /**
     * Enumerates the accounting families that redirect their Market-lane realized P&amp;L into
     * {@code targetFamily} (i.e. resolve to it but are not it). Derived deterministically from the
     * static C1/C2 classification universe (ADR-054); used by the move-basis header to credit a
     * parent family with its children's realized P&amp;L when only the parent family is loaded.
     */
    public Set<String> resolveChildFamilies(String targetFamily) {
        if (targetFamily == null || targetFamily.isBlank()) {
            return Set.of();
        }
        Set<String> children = new LinkedHashSet<>();
        for (String symbol : classifiedSymbols()) {
            String family = AccountingAssetClassificationSupport.continuityFamilyIdentity(symbol, null);
            if (family == null || family.equals(targetFamily)) {
                continue;
            }
            if (targetFamily.equals(resolveTarget(family, symbol))) {
                children.add(family);
            }
        }
        return children;
    }

    private static Set<String> classifiedSymbols() {
        Set<String> symbols = new LinkedHashSet<>();
        symbols.addAll(AccountingAssetClassificationSupport.c1SameAssetSymbols());
        symbols.addAll(AccountingAssetClassificationSupport.c2DistinctAssetSymbols());
        return symbols;
    }

    /**
     * Representative asset symbol for a {@code FAMILY:<SYMBOL>} identity when no held-position symbol
     * is available (e.g. a fully-exited child family). Falls back to {@code null} for non-standard
     * identities.
     */
    public static String representativeSymbolFor(String familyIdentity, String knownSymbol) {
        if (knownSymbol != null && !knownSymbol.isBlank()) {
            return knownSymbol;
        }
        if (familyIdentity != null && familyIdentity.startsWith(FAMILY_PREFIX)) {
            return familyIdentity.substring(FAMILY_PREFIX.length());
        }
        return knownSymbol;
    }
}
