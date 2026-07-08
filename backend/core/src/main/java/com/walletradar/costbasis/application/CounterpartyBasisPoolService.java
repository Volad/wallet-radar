package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetFamilyResolver;
import com.walletradar.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolKey;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory counterparty basis pool book for AVCO replay (ADR-015 §D2, D5).
 */
@Service
@RequiredArgsConstructor
public class CounterpartyBasisPoolService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final CounterpartyBasisPoolRepository repository;
    private final AssetFamilyResolver assetFamilyResolver;
    private final AccountingUniverseService accountingUniverseService;

    public record PopResult(
            BigDecimal popQty,
            BigDecimal popBasisUsd,
            BigDecimal residualQty,
            BigDecimal residualBasisUsd
    ) {
    }

    public Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> loadAllForUniverse(String universeId) {
        Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> pools = new LinkedHashMap<>();
        for (CounterpartyBasisPool pool : repository.findByUniverseId(universeId)) {
            pools.put(toKey(pool), pool);
        }
        return pools;
    }

    public void persistDirty(
            String universeId,
            Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> pools,
            Set<CounterpartyBasisPoolKey> dirtyKeys
    ) {
        if (dirtyKeys == null || dirtyKeys.isEmpty()) {
            return;
        }
        List<CounterpartyBasisPool> toSave = dirtyKeys.stream()
                .map(pools::get)
                .filter(pool -> pool != null)
                .toList();
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
    }

    public void replaceUniversePools(String universeId, Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> pools) {
        repository.deleteByUniverseId(universeId);
        if (pools != null && !pools.isEmpty()) {
            repository.saveAll(pools.values());
        }
    }

    public boolean shouldTrackFlow(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        if (flow.getCounterpartyAddress() == null || flow.getCounterpartyAddress().isBlank()) {
            return false;
        }
        String counterpartyType = flow.getCounterpartyType();
        if (CounterpartyType.GENUINE_MISSING_SOURCE.equals(counterpartyType)
                || CounterpartyType.PROTOCOL.equals(counterpartyType)
                || CounterpartyType.BRIDGE.equals(counterpartyType)) {
            return false;
        }
        return true;
    }

    public CounterpartyBasisPool lookupOrCreate(
            String universeId,
            NetworkId networkId,
            NormalizedTransaction.Flow flow,
            Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> pools,
            Set<CounterpartyBasisPoolKey> dirtyKeys,
            Instant touchedAt
    ) {
        CounterpartyBasisPoolKey key = poolKey(universeId, networkId, flow);
        CounterpartyBasisPool pool = pools.get(key);
        if (pool == null) {
            pool = new CounterpartyBasisPool();
            pool.setId(key.documentId());
            pool.setUniverseId(universeId);
            pool.setCounterpartyAddress(key.counterpartyLower());
            pool.setNetworkId(networkId);
            pool.setAssetFamily(key.assetFamily());
            pool.setQtyHeld(BigDecimal.ZERO);
            pool.setBasisHeldUsd(BigDecimal.ZERO);
            pool.setAvcoUsd(BigDecimal.ZERO);
            pool.setLifetimeOutQty(BigDecimal.ZERO);
            pool.setLifetimeOutBasisUsd(BigDecimal.ZERO);
            pool.setLifetimeInQty(BigDecimal.ZERO);
            pool.setLifetimeInBasisUsd(BigDecimal.ZERO);
            pool.setNetCapitalDeltaUsd(BigDecimal.ZERO);
            pool.setCreatedAt(touchedAt);
            pools.put(key, pool);
        }
        touchMetadata(pool, flow, networkId, touchedAt);
        dirtyKeys.add(key);
        return pool;
    }

    public void pushOut(
            CounterpartyBasisPool pool,
            BigDecimal qty,
            BigDecimal outBasisUsd,
            NormalizedTransaction.Flow flow
    ) {
        if (pool == null || qty == null || qty.signum() <= 0) {
            return;
        }
        BigDecimal basis = outBasisUsd == null ? BigDecimal.ZERO : outBasisUsd;
        pool.setQtyHeld(safeAdd(pool.getQtyHeld(), qty));
        pool.setBasisHeldUsd(safeAdd(pool.getBasisHeldUsd(), basis));
        pool.setAvcoUsd(safeDivide(pool.getBasisHeldUsd(), pool.getQtyHeld()));
        pool.setLifetimeOutQty(safeAdd(pool.getLifetimeOutQty(), qty));
        pool.setLifetimeOutBasisUsd(safeAdd(pool.getLifetimeOutBasisUsd(), basis));
        pool.setNetCapitalDeltaUsd(safeSubtract(pool.getNetCapitalDeltaUsd(), basis));
        if (flow != null) {
            flow.setRealisedPnlUsd(BigDecimal.ZERO);
        }
    }

    public PopResult popIn(CounterpartyBasisPool pool, BigDecimal qty, BigDecimal unitPriceUsd) {
        if (pool == null || qty == null || qty.signum() <= 0) {
            return new PopResult(BigDecimal.ZERO, BigDecimal.ZERO, qty == null ? BigDecimal.ZERO : qty, BigDecimal.ZERO);
        }
        BigDecimal held = pool.getQtyHeld() == null ? BigDecimal.ZERO : pool.getQtyHeld();
        BigDecimal popQty = qty.min(held);
        BigDecimal poolAvco = pool.getAvcoUsd() == null ? BigDecimal.ZERO : pool.getAvcoUsd();
        BigDecimal popBasisUsd = popQty.multiply(poolAvco, MC);
        BigDecimal residualQty = qty.subtract(popQty, MC);
        BigDecimal residualBasisUsd = unitPriceUsd == null
                ? BigDecimal.ZERO
                : residualQty.multiply(unitPriceUsd, MC);

        pool.setQtyHeld(nonNegative(held.subtract(popQty, MC)));
        pool.setBasisHeldUsd(nonNegative(safeSubtract(pool.getBasisHeldUsd(), popBasisUsd)));
        pool.setAvcoUsd(pool.getQtyHeld().signum() == 0
                ? BigDecimal.ZERO
                : safeDivide(pool.getBasisHeldUsd(), pool.getQtyHeld()));
        pool.setLifetimeInQty(safeAdd(pool.getLifetimeInQty(), qty));
        BigDecimal inBasis = popBasisUsd.add(residualBasisUsd, MC);
        pool.setLifetimeInBasisUsd(safeAdd(pool.getLifetimeInBasisUsd(), inBasis));
        pool.setNetCapitalDeltaUsd(safeAdd(pool.getNetCapitalDeltaUsd(), inBasis));

        return new PopResult(popQty, popBasisUsd, residualQty, residualBasisUsd);
    }

    public CounterpartyBasisPoolKey poolKey(String universeId, NetworkId networkId, NormalizedTransaction.Flow flow) {
        String counterpartyLower = flow.getCounterpartyAddress().trim().toLowerCase(Locale.ROOT);
        String assetFamily = assetFamilyResolver.resolveFamily(flow.getAssetSymbol());
        return new CounterpartyBasisPoolKey(universeId, counterpartyLower, networkId, assetFamily);
    }

    private void touchMetadata(
            CounterpartyBasisPool pool,
            NormalizedTransaction.Flow flow,
            NetworkId networkId,
            Instant touchedAt
    ) {
        pool.setCounterpartyTypeAtLastTouch(flow.getCounterpartyType());
        boolean isMember = false;
        try {
            isMember = accountingUniverseService.classify(pool.getUniverseId(), flow.getCounterpartyAddress(), networkId)
                    .isMember();
        } catch (IllegalStateException ignored) {
            // Universe not bound — leave isMemberAtLastTouch unchanged when replay runs without binding.
        }
        pool.setIsMemberAtLastTouch(isMember);
        if (flow.getAssetSymbol() != null && !flow.getAssetSymbol().isBlank()) {
            String symbol = flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            if (!pool.observedAssetSymbols().contains(symbol)) {
                pool.observedAssetSymbols().add(symbol);
            }
        }
        pool.setLastTouchedAt(touchedAt);
    }

    private static CounterpartyBasisPoolKey toKey(CounterpartyBasisPool pool) {
        return new CounterpartyBasisPoolKey(
                pool.getUniverseId(),
                pool.getCounterpartyAddress(),
                pool.getNetworkId(),
                pool.getAssetFamily()
        );
    }

    private static BigDecimal safeAdd(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left).add(right == null ? BigDecimal.ZERO : right, MC);
    }

    private static BigDecimal safeSubtract(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left).subtract(right == null ? BigDecimal.ZERO : right, MC);
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, MC);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
