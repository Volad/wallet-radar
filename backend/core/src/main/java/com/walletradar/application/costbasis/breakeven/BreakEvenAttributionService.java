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

    private final Map<String, String> sourceToTarget;

    public BreakEvenAttributionService(BreakEvenAttributionLoader loader) {
        this.sourceToTarget = loader.loadFromClasspath().sourceToTarget();
        log.info("Loaded break-even attribution: {} source->target mappings", sourceToTarget.size());
    }

    /**
     * ADR-062 §2: (1) an explicit {@code FAMILY:*} source mapping wins; (2) else the symbol's
     * normalization cluster mapping applies when its target differs from the family; (3) else the
     * family maps to itself. Null/blank {@code familyIdentity} is returned unchanged.
     */
    public String resolveTarget(String familyIdentity, String representativeSymbol) {
        if (familyIdentity == null || familyIdentity.isBlank()) {
            return familyIdentity;
        }
        String explicit = sourceToTarget.get(familyIdentity);
        if (explicit != null) {
            return explicit;
        }
        String cluster = AccountingAssetClassificationSupport.normalizationClusterForSymbol(representativeSymbol);
        if (cluster != null) {
            String target = sourceToTarget.get(cluster);
            if (target != null && !target.equals(familyIdentity)) {
                return target;
            }
        }
        return familyIdentity;
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
