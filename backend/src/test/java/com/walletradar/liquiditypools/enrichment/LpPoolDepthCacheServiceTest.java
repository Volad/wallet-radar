package com.walletradar.liquiditypools.enrichment;

import com.walletradar.liquiditypools.config.LiquidityPoolsProperties;
import com.walletradar.liquiditypools.persistence.LpPoolDepthCache;
import com.walletradar.liquiditypools.persistence.LpPoolDepthCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LpPoolDepthCacheServiceTest {

    @Mock
    private LpPoolDepthCacheRepository repository;

    private LiquidityPoolsProperties properties;
    private LpPoolDepthCacheService service;

    @BeforeEach
    void setUp() {
        properties = new LiquidityPoolsProperties();
        properties.setDepthIntervalMs(21_600_000L);
        service = new LpPoolDepthCacheService(properties, repository);
    }

    @Test
    void getFreshReturnsCachedStateWithinTtl() {
        Instant capturedAt = Instant.now().minusSeconds(60);
        LpPoolDepthCache doc = cacheDoc("BASE:0xpool", capturedAt, Map.of(100, BigInteger.TEN));
        when(repository.findById("BASE:0xpool")).thenReturn(Optional.of(doc));

        Optional<PoolLiquidityDepthState> fresh = service.getFresh("BASE", "0xPool");

        assertThat(fresh).isPresent();
        assertThat(fresh.get().liquidityNetByTick()).containsEntry(100, BigInteger.TEN);
        assertThat(fresh.get().capturedAt()).isEqualTo(capturedAt);
    }

    @Test
    void getFreshSkipsExpiredMongoEntry() {
        LpPoolDepthCache doc = cacheDoc("BASE:0xpool", Instant.now().minusSeconds(25_000), Map.of());
        when(repository.findById("BASE:0xpool")).thenReturn(Optional.of(doc));

        assertThat(service.getFresh("base", "0xpool")).isEmpty();
    }

    @Test
    void putNormalizesPoolKey() {
        service.put(" base ", " 0xAbC ", new PoolLiquidityDepthState(
                1, BigInteger.ONE, 0, Map.of(), Instant.now()));

        ArgumentCaptor<LpPoolDepthCache> captor = ArgumentCaptor.forClass(LpPoolDepthCache.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPoolKey()).isEqualTo("BASE:0xabc");
    }

    private static LpPoolDepthCache cacheDoc(
            String poolKey,
            Instant capturedAt,
            Map<Integer, BigInteger> liquidityNetByTick
    ) {
        LpPoolDepthCache doc = new LpPoolDepthCache();
        doc.setPoolKey(poolKey);
        doc.setNetworkId("BASE");
        doc.setPoolAddress("0xpool");
        doc.setTickSpacing(60);
        doc.setPoolLiquidity(BigInteger.TWO);
        doc.setCurrentTick(1000);
        doc.setLiquidityNetByTick(new LinkedHashMap<>(liquidityNetByTick));
        doc.setCapturedAt(capturedAt);
        return doc;
    }
}
