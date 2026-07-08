package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads on-chain Uniswap V3 / PancakeSwap V3 liquidity depth by scanning tickBitmap and
 * reconstructing active liquidity in each tick interval. Returns normalized bins suitable
 * for rendering a histogram on the LP position panel.
 *
 * <p>Pool-level RPC results are cached ({@link LpPoolDepthCacheService}) so manual refresh and
 * multi-position jobs do not re-scan the same pool on every click.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiquidityDepthReader {

    // Scan up to 50 bitmap words to cover the full position range + 15% context.
    // Each word covers 256 compressed ticks; for tickSpacing=1 that's 256 real ticks.
    // 50 words × 256 = 12,800 ticks ≈ enough for a wide ETH/USDC range.
    private static final int MAX_SCAN_WORDS = 50;
    private static final int MAX_BINS = 60;

    private static final String TICK_SPACING_SELECTOR = "0x" + EvmAbiSupport.selector("tickSpacing()");
    private static final String LIQUIDITY_SELECTOR = "0x" + EvmAbiSupport.selector("liquidity()");
    private static final String TICK_BITMAP_SELECTOR = "0x" + EvmAbiSupport.selector("tickBitmap(int16)");
    private static final String TICKS_SELECTOR = "0x" + EvmAbiSupport.selector("ticks(int24)");

    private final LpRpcSupport rpc;
    private final LpPoolDepthCacheService depthCacheService;

    /**
     * Read liquidity depth bins for a Uniswap V3-style CL pool.
     *
     * @return list of bins with normalized liquidity share; empty if reading fails or pool is not CL
     */
    public List<LpPositionSnapshot.LiquidityBin> readDepth(
            String network,
            String pool,
            int currentTick,
            int tickLower,
            int tickUpper,
            int decimals0,
            int decimals1
    ) {
        try {
            Optional<PoolLiquidityDepthState> cached = depthCacheService.getFresh(network, pool);
            if (cached.isPresent()) {
                log.debug("liquidity depth cache hit pool={} network={}", pool, network);
                return renderBins(cached.get(), currentTick, tickLower, tickUpper, decimals0, decimals1);
            }
            if (LpDepthFetchPolicy.skipRpcFetch()) {
                log.debug("liquidity depth RPC fetch skipped (policy) pool={} network={}", pool, network);
                return List.of();
            }
            Optional<PoolLiquidityDepthState> fetched = fetchPoolState(network, pool, currentTick);
            if (fetched.isEmpty()) {
                return List.of();
            }
            depthCacheService.put(network, pool, fetched.get());
            log.info("liquidity depth cache stored pool={} network={} ticks={}",
                    pool, network, fetched.get().liquidityNetByTick().size());
            return renderBins(fetched.get(), currentTick, tickLower, tickUpper, decimals0, decimals1);
        } catch (Exception e) {
            log.warn("liquidity depth read failed pool={} network={} reason={}", pool, network, e.getMessage());
            return List.of();
        }
    }

    private Optional<PoolLiquidityDepthState> fetchPoolState(String network, String pool, int currentTick) {
        int tickSpacing = rpc.call(network, pool, TICK_SPACING_SELECTOR)
                .map(h -> EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(h, 0)).intValue())
                .orElse(0);
        if (tickSpacing <= 0) {
            log.debug("tickSpacing unavailable for pool={} network={}", pool, network);
            return Optional.empty();
        }

        BigInteger poolLiquidity = rpc.call(network, pool, LIQUIDITY_SELECTOR)
                .map(h -> EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(h, 0)))
                .orElse(BigInteger.ZERO);

        int currentWordPos = Math.floorDiv(currentTick, tickSpacing) >> 8;
        int scanLow = currentWordPos - MAX_SCAN_WORDS;
        int scanHigh = currentWordPos + MAX_SCAN_WORDS;

        List<String> bitmapCallDatas = new ArrayList<>(scanHigh - scanLow + 1);
        for (int wordPos = scanLow; wordPos <= scanHigh; wordPos++) {
            bitmapCallDatas.add(TICK_BITMAP_SELECTOR + EvmAbiSupport.encodeInt16(wordPos));
        }
        List<Optional<String>> bitmapResults = rpc.callBatch(network, pool, bitmapCallDatas);

        List<Integer> initializedTicks = new ArrayList<>();
        for (int wi = 0; wi < bitmapResults.size(); wi++) {
            int wordPos = scanLow + wi;
            BigInteger bitmap = bitmapResults.get(wi)
                    .map(h -> EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(h, 0)))
                    .orElse(BigInteger.ZERO);
            if (bitmap.signum() == 0) {
                continue;
            }
            for (int bit = 0; bit < 256; bit++) {
                if (!bitmap.testBit(bit)) {
                    continue;
                }
                int tick = (wordPos * 256 + bit) * tickSpacing;
                initializedTicks.add(tick);
            }
        }

        TreeMap<Integer, BigInteger> liquidityNetByTick = new TreeMap<>();
        if (!initializedTicks.isEmpty()) {
            List<String> tickCallDatas = initializedTicks.stream()
                    .map(tick -> TICKS_SELECTOR + EvmAbiSupport.encodeInt24(tick))
                    .toList();
            List<Optional<String>> tickResults = rpc.callBatch(network, pool, tickCallDatas);
            for (int ti = 0; ti < initializedTicks.size(); ti++) {
                int tick = initializedTicks.get(ti);
                tickResults.get(ti).ifPresent(h -> {
                    BigInteger liquidityNet = EvmAbiSupport.signedInt128FromWord(EvmAbiSupport.wordAt(h, 1));
                    liquidityNetByTick.put(tick, liquidityNet);
                });
            }
        }

        if (liquidityNetByTick.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new PoolLiquidityDepthState(
                tickSpacing,
                poolLiquidity,
                currentTick,
                liquidityNetByTick,
                Instant.now()
        ));
    }

    private List<LpPositionSnapshot.LiquidityBin> renderBins(
            PoolLiquidityDepthState state,
            int currentTick,
            int tickLower,
            int tickUpper,
            int decimals0,
            int decimals1
    ) {
        TreeMap<Integer, BigInteger> liquidityNetByTick = new TreeMap<>(state.liquidityNetByTick());
        BigInteger poolLiquidity = state.poolLiquidity();

        if (liquidityNetByTick.isEmpty()) {
            return List.of();
        }

        int spread = tickUpper - tickLower;
        int contextTicks = Math.max(1, (int) (spread * 0.15));

        List<Integer> sortedTicks = new ArrayList<>(liquidityNetByTick.keySet());
        if (!liquidityNetByTick.containsKey(tickLower)) {
            sortedTicks.add(tickLower);
        }
        if (!liquidityNetByTick.containsKey(tickUpper)) {
            sortedTicks.add(tickUpper);
        }
        sortedTicks.sort(Integer::compareTo);

        int currentIntervalIdx = 0;
        for (int i = 0; i < sortedTicks.size(); i++) {
            if (sortedTicks.get(i) <= currentTick) {
                currentIntervalIdx = i;
            }
        }

        TreeMap<Integer, BigInteger> liquidityByIntervalStart = new TreeMap<>();
        liquidityByIntervalStart.put(sortedTicks.get(currentIntervalIdx), poolLiquidity);

        BigInteger l = poolLiquidity;
        for (int i = currentIntervalIdx + 1; i < sortedTicks.size(); i++) {
            int tick = sortedTicks.get(i);
            BigInteger net = liquidityNetByTick.getOrDefault(tick, BigInteger.ZERO);
            l = l.add(net);
            if (l.signum() < 0) {
                l = BigInteger.ZERO;
            }
            liquidityByIntervalStart.put(tick, l);
        }

        l = poolLiquidity;
        for (int i = currentIntervalIdx - 1; i >= 0; i--) {
            int boundaryTick = sortedTicks.get(i + 1);
            BigInteger net = liquidityNetByTick.getOrDefault(boundaryTick, BigInteger.ZERO);
            l = l.subtract(net);
            if (l.signum() < 0) {
                l = BigInteger.ZERO;
            }
            liquidityByIntervalStart.put(sortedTicks.get(i), l);
        }

        int dispLow = tickLower - contextTicks;
        int dispHigh = tickUpper + contextTicks;

        // Compute maxL only within the display window — far-OOR ticks can have astronomically large
        // liquidityNet values that would make all in-range bars appear near-zero if included in the max.
        BigInteger maxL = BigInteger.ONE;
        for (int i = 0; i < sortedTicks.size() - 1; i++) {
            int tLow = sortedTicks.get(i);
            int tHigh = sortedTicks.get(i + 1);
            if (tHigh <= dispLow || tLow >= dispHigh) {
                continue;
            }
            BigInteger intervalL = liquidityByIntervalStart.getOrDefault(tLow, BigInteger.ZERO);
            if (intervalL.compareTo(maxL) > 0) {
                maxL = intervalL;
            }
        }

        List<LpPositionSnapshot.LiquidityBin> bins = new ArrayList<>();
        for (int i = 0; i < sortedTicks.size() - 1; i++) {
            int tLow = sortedTicks.get(i);
            int tHigh = sortedTicks.get(i + 1);
            if (tHigh <= dispLow || tLow >= dispHigh) {
                continue;
            }

            BigInteger intervalL = liquidityByIntervalStart.getOrDefault(tLow, BigInteger.ZERO);
            double share = maxL.signum() > 0 ? intervalL.doubleValue() / maxL.doubleValue() : 0.0;

            BigDecimal priceLower = tickToPrice(tLow, decimals0, decimals1);
            BigDecimal priceUpper = tickToPrice(tHigh, decimals0, decimals1);

            LpPositionSnapshot.LiquidityBin bin = new LpPositionSnapshot.LiquidityBin();
            bin.setTickLower(tLow);
            bin.setTickUpper(tHigh);
            bin.setPriceLower(priceLower);
            bin.setPriceUpper(priceUpper);
            bin.setLiquidityShare(share);
            bins.add(bin);
        }

        return mergeBins(bins, MAX_BINS);
    }

    private static BigDecimal tickToPrice(int tick, int decimals0, int decimals1) {
        BigInteger sqrtRatio = LpLiquidityAmountsSupport.getSqrtRatioAtTick(tick);
        return LpLiquidityAmountsSupport.sqrtPriceX96ToPrice(sqrtRatio, decimals0, decimals1);
    }

    private static List<LpPositionSnapshot.LiquidityBin> mergeBins(
            List<LpPositionSnapshot.LiquidityBin> bins, int maxBins
    ) {
        if (bins.size() <= maxBins) {
            return bins;
        }
        int step = (int) Math.ceil((double) bins.size() / maxBins);
        List<LpPositionSnapshot.LiquidityBin> merged = new ArrayList<>();
        for (int i = 0; i < bins.size(); i += step) {
            int end = Math.min(i + step, bins.size());
            LpPositionSnapshot.LiquidityBin first = bins.get(i);
            LpPositionSnapshot.LiquidityBin last = bins.get(end - 1);
            double avgShare = 0;
            for (int j = i; j < end; j++) {
                avgShare += bins.get(j).getLiquidityShare();
            }
            avgShare /= (end - i);
            LpPositionSnapshot.LiquidityBin bin = new LpPositionSnapshot.LiquidityBin();
            bin.setTickLower(first.getTickLower());
            bin.setTickUpper(last.getTickUpper());
            bin.setPriceLower(first.getPriceLower());
            bin.setPriceUpper(last.getPriceUpper());
            bin.setLiquidityShare(avgShare);
            merged.add(bin);
        }
        return merged;
    }
}
