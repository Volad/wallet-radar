package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.application.liquiditypools.persistence.LpPoolDepthCache;
import com.walletradar.application.liquiditypools.persistence.LpPoolDepthCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LpPoolDepthCacheService {

    private final LiquidityPoolsProperties properties;
    private final LpPoolDepthCacheRepository repository;

    public Optional<PoolLiquidityDepthState> getFresh(String networkId, String poolAddress) {
        String poolKey = poolKey(networkId, poolAddress);
        Instant cutoff = Instant.now().minus(Duration.ofMillis(Math.max(1L, properties.getDepthIntervalMs())));

        return repository.findById(poolKey)
                .filter(doc -> doc.getCapturedAt() != null && !doc.getCapturedAt().isBefore(cutoff))
                .map(LpPoolDepthCacheService::toState);
    }

    public void put(String networkId, String poolAddress, PoolLiquidityDepthState state) {
        if (state == null) {
            return;
        }
        String poolKey = poolKey(networkId, poolAddress);

        LpPoolDepthCache doc = new LpPoolDepthCache();
        doc.setPoolKey(poolKey);
        doc.setNetworkId(normalizeNetwork(networkId));
        doc.setPoolAddress(normalizePool(poolAddress));
        doc.setTickSpacing(state.tickSpacing());
        doc.setPoolLiquidity(state.poolLiquidity());
        doc.setCurrentTick(state.currentTick());
        doc.setLiquidityNetByTick(new LinkedHashMap<>(state.liquidityNetByTick()));
        doc.setCapturedAt(state.capturedAt());
        repository.save(doc);
    }

    static String poolKey(String networkId, String poolAddress) {
        return normalizeNetwork(networkId) + ":" + normalizePool(poolAddress);
    }

    private static String normalizeNetwork(String networkId) {
        return networkId == null ? "" : networkId.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePool(String poolAddress) {
        return poolAddress == null ? "" : poolAddress.trim().toLowerCase(Locale.ROOT);
    }

    private static PoolLiquidityDepthState toState(LpPoolDepthCache doc) {
        Map<Integer, BigInteger> liquidityNetByTick = new LinkedHashMap<>();
        if (doc.getLiquidityNetByTick() != null) {
            doc.getLiquidityNetByTick().forEach(liquidityNetByTick::put);
        }
        return new PoolLiquidityDepthState(
                doc.getTickSpacing(),
                doc.getPoolLiquidity(),
                doc.getCurrentTick(),
                Map.copyOf(liquidityNetByTick),
                doc.getCapturedAt()
        );
    }
}
