package com.walletradar.application.liquiditypools.persistence;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Document(collection = "lp_pool_depth_cache")
@NoArgsConstructor
@Getter
@Setter
public class LpPoolDepthCache {

    @Id
    private String poolKey;

    private String networkId;
    private String poolAddress;
    private int tickSpacing;
    private BigInteger poolLiquidity;
    private int currentTick;
    private Map<Integer, BigInteger> liquidityNetByTick = new LinkedHashMap<>();
    private Instant capturedAt;
}
