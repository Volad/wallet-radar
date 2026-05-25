package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cycle/15 Z3: LP receipt basis pool book for AVCO replay.
 */
@Service
@RequiredArgsConstructor
public class LpReceiptBasisPoolService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final LpReceiptBasisPoolRepository repository;

    public record DepositResult(
            BigDecimal depositedQty,
            BigDecimal depositedBasisUsd,
            BigDecimal depositedUncoveredQty
    ) {
    }

    public record WithdrawResult(
            BigDecimal withdrawnQty,
            BigDecimal withdrawnBasisUsd,
            BigDecimal withdrawnUncoveredQty,
            BigDecimal residualQty
    ) {
    }

    public Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> loadAllForUniverse(String universeId) {
        Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        for (LpReceiptBasisPool pool : repository.findByUniverseId(universeId)) {
            pools.put(toKey(pool), pool);
        }
        return pools;
    }

    public void replaceUniversePools(String universeId, Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools) {
        repository.deleteByUniverseId(universeId);
        if (pools != null && !pools.isEmpty()) {
            repository.saveAll(pools.values());
        }
    }

    public void persistDirty(
            Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools,
            Set<LpReceiptBasisPoolKey> dirtyKeys
    ) {
        if (dirtyKeys == null || dirtyKeys.isEmpty()) {
            return;
        }
        List<LpReceiptBasisPool> toSave = dirtyKeys.stream()
                .map(pools::get)
                .filter(pool -> pool != null)
                .toList();
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
    }

    public LpReceiptBasisPool lookupOrCreate(
            String universeId,
            String lpCorrelationId,
            String walletAddress,
            NetworkId networkId,
            String assetIdentity,
            String assetSymbol,
            String assetContract,
            Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools,
            Set<LpReceiptBasisPoolKey> dirtyKeys,
            Instant touchedAt
    ) {
        LpReceiptBasisPoolKey key = new LpReceiptBasisPoolKey(universeId, lpCorrelationId, assetIdentity);
        LpReceiptBasisPool pool = pools.get(key);
        if (pool == null) {
            pool = new LpReceiptBasisPool();
            pool.setId(key.universeId() + ":" + key.lpCorrelationId() + ":" + key.assetIdentity());
            pool.setUniverseId(universeId);
            pool.setLpCorrelationId(lpCorrelationId);
            pool.setWalletAddress(walletAddress);
            pool.setNetworkId(networkId);
            pool.setAssetIdentity(assetIdentity);
            pool.setAssetSymbol(assetSymbol);
            pool.setAssetContract(assetContract);
            pool.setQtyHeld(BigDecimal.ZERO);
            pool.setBasisHeldUsd(BigDecimal.ZERO);
            pool.setUncoveredQtyHeld(BigDecimal.ZERO);
            pool.setAvcoUsd(null);
            pool.setCreatedAt(touchedAt);
            pools.put(key, pool);
            dirtyKeys.add(key);
        }
        pool.setLastTouchedAt(touchedAt);
        return pool;
    }

    public DepositResult deposit(
            LpReceiptBasisPool pool,
            BigDecimal quantity,
            BigDecimal basisUsd,
            BigDecimal uncoveredQty
    ) {
        if (pool == null || quantity == null || quantity.signum() <= 0) {
            return new DepositResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal basis = basisUsd == null ? BigDecimal.ZERO : basisUsd;
        BigDecimal uncovered = uncoveredQty == null ? BigDecimal.ZERO : uncoveredQty;
        pool.setQtyHeld(zeroIfNull(pool.getQtyHeld()).add(quantity, MC));
        pool.setBasisHeldUsd(zeroIfNull(pool.getBasisHeldUsd()).add(basis, MC));
        pool.setUncoveredQtyHeld(zeroIfNull(pool.getUncoveredQtyHeld()).add(uncovered, MC));
        recomputeAvco(pool);
        return new DepositResult(quantity, basis, uncovered);
    }

    public WithdrawResult withdraw(LpReceiptBasisPool pool, BigDecimal requestedQuantity) {
        if (pool == null || requestedQuantity == null || requestedQuantity.signum() <= 0) {
            return new WithdrawResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, requestedQuantity);
        }
        BigDecimal held = zeroIfNull(pool.getQtyHeld());
        BigDecimal withdrawnQty = requestedQuantity.min(held);
        if (withdrawnQty.signum() <= 0) {
            return new WithdrawResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, requestedQuantity);
        }
        BigDecimal ratio = held.signum() == 0 ? BigDecimal.ZERO : withdrawnQty.divide(held, MC);
        BigDecimal basis = zeroIfNull(pool.getBasisHeldUsd()).multiply(ratio, MC);
        BigDecimal uncovered = zeroIfNull(pool.getUncoveredQtyHeld()).multiply(ratio, MC);
        pool.setQtyHeld(held.subtract(withdrawnQty, MC));
        pool.setBasisHeldUsd(zeroIfNull(pool.getBasisHeldUsd()).subtract(basis, MC));
        pool.setUncoveredQtyHeld(zeroIfNull(pool.getUncoveredQtyHeld()).subtract(uncovered, MC));
        recomputeAvco(pool);
        BigDecimal residual = requestedQuantity.subtract(withdrawnQty, MC);
        return new WithdrawResult(withdrawnQty, basis, uncovered, residual);
    }

    public static LpReceiptBasisPoolKey toKey(LpReceiptBasisPool pool) {
        return new LpReceiptBasisPoolKey(pool.getUniverseId(), pool.getLpCorrelationId(), pool.getAssetIdentity());
    }

    private static void recomputeAvco(LpReceiptBasisPool pool) {
        BigDecimal qty = zeroIfNull(pool.getQtyHeld());
        BigDecimal basis = zeroIfNull(pool.getBasisHeldUsd());
        if (qty.signum() > 0 && basis.signum() > 0) {
            pool.setAvcoUsd(basis.divide(qty, MC));
        } else {
            pool.setAvcoUsd(null);
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
