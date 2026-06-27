package com.walletradar.liquiditypools.enrichment;

import com.walletradar.ingestion.adapter.evm.abi.EvmAbiSupport;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConcentratedLiquidityReader implements LpPositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String POSITIONS_SELECTOR = "0x" + EvmAbiSupport.selector("positions(uint256)");
    private static final String SLOT0_SELECTOR = "0x" + EvmAbiSupport.selector("slot0()");
    private static final String FEE_GROWTH0_SELECTOR = "0x" + EvmAbiSupport.selector("feeGrowthGlobal0X128()");
    private static final String FEE_GROWTH1_SELECTOR = "0x" + EvmAbiSupport.selector("feeGrowthGlobal1X128()");
    private static final String TICKS_SELECTOR = "0x" + EvmAbiSupport.selector("ticks(int24)");
    private static final String FACTORY_SELECTOR = "0x" + EvmAbiSupport.selector("factory()");
    private static final String GET_POOL_SELECTOR = "0x" + EvmAbiSupport.selector("getPool(address,address,uint24)");

    private final LpRpcSupport rpc;
    private final LiquidityDepthReader depthReader;

    @Override
    public boolean supports(LpPositionContext context) {
        if (context == null || context.closed()) {
            return false;
        }
        if (context.correlationId() == null
                || !context.correlationId().startsWith("lp-position:")
                || context.nfpmContract() == null
                || context.tokenId() == null) {
            return false;
        }
        // Reject non-numeric tokenIds (e.g. "vault") — those are vault-style positions,
        // not standard Uniswap V3 NFT positions parseable by this reader.
        try {
            new java.math.BigInteger(context.tokenId());
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    @Override
    public Optional<LpPositionSnapshot> read(LpPositionContext context) {
        String network = context.networkId().name();
        String positionsData = POSITIONS_SELECTOR + EvmAbiSupport.encodeUint256(new BigInteger(context.tokenId()));
        String positionsHex = rpc.call(network, context.nfpmContract(), positionsData).orElse(null);
        if (positionsHex == null) {
            return Optional.empty();
        }

        String token0 = EvmAbiSupport.addressFromWord(EvmAbiSupport.wordAt(positionsHex, 2));
        String token1 = EvmAbiSupport.addressFromWord(EvmAbiSupport.wordAt(positionsHex, 3));
        int fee = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(positionsHex, 4)).intValue();
        int tickLower = EvmAbiSupport.int24FromWord(EvmAbiSupport.wordAt(positionsHex, 5));
        int tickUpper = EvmAbiSupport.int24FromWord(EvmAbiSupport.wordAt(positionsHex, 6));
        BigInteger liquidity = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(positionsHex, 7));
        BigInteger feeGrowthInside0Last = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(positionsHex, 8));
        BigInteger feeGrowthInside1Last = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(positionsHex, 9));
        BigInteger tokensOwed0 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(positionsHex, 10));
        BigInteger tokensOwed1 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(positionsHex, 11));

        if (token0 == null || token1 == null) {
            return Optional.empty();
        }

        int decimals0 = rpc.erc20Decimals(network, token0).orElse(18);
        int decimals1 = rpc.erc20Decimals(network, token1).orElse(18);
        String sym0 = rpc.erc20Symbol(network, token0).orElse("TOKEN0");
        String sym1 = rpc.erc20Symbol(network, token1).orElse("TOKEN1");

        TokenPair pair = canonicalTokenPair(token0, token1, decimals0, decimals1, sym0, sym1);
        token0 = pair.token0();
        token1 = pair.token1();
        decimals0 = pair.decimals0();
        decimals1 = pair.decimals1();
        sym0 = pair.sym0();
        sym1 = pair.sym1();
        if (pair.swapped()) {
            int tmpTick = tickLower;
            tickLower = tickUpper;
            tickUpper = tmpTick;
            BigInteger tmpGrowth = feeGrowthInside0Last;
            feeGrowthInside0Last = feeGrowthInside1Last;
            feeGrowthInside1Last = tmpGrowth;
            BigInteger tmpOwed = tokensOwed0;
            tokensOwed0 = tokensOwed1;
            tokensOwed1 = tmpOwed;
        }

        if (liquidity.signum() == 0) {
            return Optional.of(closedSnapshot(context, network, sym0, token0, sym1, token1, fee));
        }

        String pool = resolvePool(context, network, token0, token1, fee).orElse(context.poolContract());
        if (pool == null) {
            return Optional.empty();
        }

        String slot0Hex = rpc.call(network, pool, SLOT0_SELECTOR).orElse(null);
        if (slot0Hex == null) {
            return Optional.empty();
        }
        BigInteger sqrtPriceX96 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(slot0Hex, 0));
        int currentTick = EvmAbiSupport.int24FromWord(EvmAbiSupport.wordAt(slot0Hex, 1));

        BigInteger sqrtLower = LpLiquidityAmountsSupport.getSqrtRatioAtTick(tickLower);
        BigInteger sqrtUpper = LpLiquidityAmountsSupport.getSqrtRatioAtTick(tickUpper);
        LpLiquidityAmountsSupport.Amounts amounts = LpLiquidityAmountsSupport.getAmountsForLiquidity(
                sqrtPriceX96, sqrtLower, sqrtUpper, liquidity);

        BigDecimal qty0 = LpLiquidityAmountsSupport.toHuman(amounts.amount0(), decimals0);
        BigDecimal qty1 = LpLiquidityAmountsSupport.toHuman(amounts.amount1(), decimals1);

        BigDecimal priceCurrent = LpLiquidityAmountsSupport.sqrtPriceX96ToPrice(sqrtPriceX96, decimals0, decimals1);
        BigDecimal priceLow = LpLiquidityAmountsSupport.sqrtPriceX96ToPrice(sqrtLower, decimals0, decimals1);
        BigDecimal priceHigh = LpLiquidityAmountsSupport.sqrtPriceX96ToPrice(sqrtUpper, decimals0, decimals1);

        boolean inRange = currentTick >= tickLower && currentTick < tickUpper;
        String status = inRange ? "in_range" : "out_of_range";

        BigDecimal unclaimed0 = LpLiquidityAmountsSupport.toHuman(tokensOwed0, decimals0);
        BigDecimal unclaimed1 = LpLiquidityAmountsSupport.toHuman(tokensOwed1, decimals1);
        BigDecimal[] growthFees = accumulateFeeGrowthUnclaimed(network, pool, tickLower, tickUpper, liquidity,
                feeGrowthInside0Last, feeGrowthInside1Last, decimals0, decimals1);
        unclaimed0 = unclaimed0.add(growthFees[0], MC);
        unclaimed1 = unclaimed1.add(growthFees[1], MC);

        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(context.correlationId());
        snapshot.setUniverseId(context.universeId());
        snapshot.setNetworkId(network);
        snapshot.setWalletAddress(context.walletAddress());
        snapshot.setProtocol(context.protocol());
        snapshot.setFamily(context.family() != null ? context.family() : "CL_NFT");
        snapshot.setStatus(status);
        snapshot.setStaked(context.staked());
        snapshot.setFeeTierPct(BigDecimal.valueOf(fee).divide(BigDecimal.valueOf(10_000), MC));
        snapshot.setPriceLow(priceLow);
        snapshot.setPriceHigh(priceHigh);
        snapshot.setPriceCurrent(priceCurrent);
        snapshot.setTickLower(tickLower);
        snapshot.setTickUpper(tickUpper);
        snapshot.setCurrentTick(currentTick);

        LpPositionSnapshot.TokenSide side0 = tokenSide(sym0, token0, qty0);
        LpPositionSnapshot.TokenSide side1 = tokenSide(sym1, token1, qty1);
        snapshot.setToken0(side0);
        snapshot.setToken1(side1);

        Map<String, BigDecimal> unclaimedByToken = new LinkedHashMap<>();
        unclaimedByToken.put(sym0, unclaimed0);
        unclaimedByToken.put(sym1, unclaimed1);
        snapshot.setUnclaimedFeesByToken(unclaimedByToken);
        snapshot.setUnclaimedFeesUsd(null);
        snapshot.setTvlUsd(null);
        snapshot.setSnapshotAt(Instant.now());
        snapshot.setSnapshotStale(false);

        // Read liquidity depth histogram for the distribution panel
        java.util.List<LpPositionSnapshot.LiquidityBin> depthBins =
                depthReader.readDepth(network, pool, currentTick, tickLower, tickUpper, decimals0, decimals1);
        if (!depthBins.isEmpty()) {
            snapshot.setLiquidityBins(depthBins);
            snapshot.setLiquidityBinsAt(Instant.now());
        }

        return Optional.of(snapshot);
    }

    private static LpPositionSnapshot closedSnapshot(
            LpPositionContext context,
            String network,
            String sym0,
            String token0,
            String sym1,
            String token1,
            int fee
    ) {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(context.correlationId());
        snapshot.setUniverseId(context.universeId());
        snapshot.setNetworkId(network);
        snapshot.setWalletAddress(context.walletAddress());
        snapshot.setProtocol(context.protocol());
        snapshot.setFamily(context.family() != null ? context.family() : "CL_NFT");
        snapshot.setStatus("closed");
        snapshot.setStaked(context.staked());
        snapshot.setFeeTierPct(BigDecimal.valueOf(fee).divide(BigDecimal.valueOf(10_000), MC));
        snapshot.setToken0(tokenSide(sym0, token0, BigDecimal.ZERO));
        snapshot.setToken1(tokenSide(sym1, token1, BigDecimal.ZERO));
        snapshot.setTvlUsd(BigDecimal.ZERO);
        snapshot.setUnclaimedFeesUsd(BigDecimal.ZERO);
        snapshot.setSnapshotAt(Instant.now());
        snapshot.setSnapshotStale(false);
        return snapshot;
    }

    private record TokenPair(
            String token0,
            String token1,
            int decimals0,
            int decimals1,
            String sym0,
            String sym1,
            boolean swapped
    ) {
    }

    private static TokenPair canonicalTokenPair(
            String token0,
            String token1,
            int decimals0,
            int decimals1,
            String sym0,
            String sym1
    ) {
        if (token0.compareToIgnoreCase(token1) <= 0) {
            return new TokenPair(token0, token1, decimals0, decimals1, sym0, sym1, false);
        }
        return new TokenPair(token1, token0, decimals1, decimals0, sym1, sym0, true);
    }

    private BigDecimal[] accumulateFeeGrowthUnclaimed(
            String network,
            String pool,
            int tickLower,
            int tickUpper,
            BigInteger liquidity,
            BigInteger feeGrowthInside0Last,
            BigInteger feeGrowthInside1Last,
            int decimals0,
            int decimals1
    ) {
        BigInteger feeGrowthGlobal0 = rpc.call(network, pool, FEE_GROWTH0_SELECTOR)
                .map(EvmAbiSupport::uintFromWord).orElse(BigInteger.ZERO);
        BigInteger feeGrowthGlobal1 = rpc.call(network, pool, FEE_GROWTH1_SELECTOR)
                .map(EvmAbiSupport::uintFromWord).orElse(BigInteger.ZERO);
        BigInteger lower0 = tickFeeGrowthOutside(network, pool, tickLower, 0);
        BigInteger lower1 = tickFeeGrowthOutside(network, pool, tickLower, 1);
        BigInteger upper0 = tickFeeGrowthOutside(network, pool, tickUpper, 0);
        BigInteger upper1 = tickFeeGrowthOutside(network, pool, tickUpper, 1);

        BigInteger inside0Now = feeGrowthGlobal0.subtract(lower0).subtract(upper0);
        BigInteger inside1Now = feeGrowthGlobal1.subtract(lower1).subtract(upper1);
        BigInteger delta0 = LpLiquidityAmountsSupport.feeGrowthDelta(inside0Now, feeGrowthInside0Last);
        BigInteger delta1 = LpLiquidityAmountsSupport.feeGrowthDelta(inside1Now, feeGrowthInside1Last);

        BigDecimal extra0 = LpLiquidityAmountsSupport.toHuman(
                delta0.multiply(liquidity).shiftRight(128), decimals0);
        BigDecimal extra1 = LpLiquidityAmountsSupport.toHuman(
                delta1.multiply(liquidity).shiftRight(128), decimals1);
        return new BigDecimal[]{extra0, extra1};
    }

    private BigInteger tickFeeGrowthOutside(String network, String pool, int tick, int tokenIndex) {
        String data = TICKS_SELECTOR + EvmAbiSupport.encodeInt24(tick);
        String hex = rpc.call(network, pool, data).orElse(null);
        if (hex == null) {
            return BigInteger.ZERO;
        }
        return EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(hex, tokenIndex + 2));
    }

    private Optional<String> resolvePool(LpPositionContext context, String network, String token0, String token1, int fee) {
        if (context.poolContract() != null && !context.poolContract().isBlank()) {
            return Optional.of(context.poolContract().toLowerCase(Locale.ROOT));
        }
        String factoryHex = rpc.call(network, context.nfpmContract(), FACTORY_SELECTOR).orElse(null);
        String factory = factoryHex == null ? null : EvmAbiSupport.addressFromWord(factoryHex);
        if (factory == null) {
            return Optional.empty();
        }
        String data = GET_POOL_SELECTOR
                + EvmAbiSupport.encodeAddress(token0)
                + EvmAbiSupport.encodeAddress(token1)
                + EvmAbiSupport.encodeUint256(BigInteger.valueOf(fee));
        return rpc.call(network, factory, data).map(EvmAbiSupport::addressFromWord);
    }

    private static LpPositionSnapshot.TokenSide tokenSide(String sym, String contract, BigDecimal qty) {
        LpPositionSnapshot.TokenSide side = new LpPositionSnapshot.TokenSide();
        side.setSym(sym);
        side.setContract(contract);
        side.setQty(qty);
        return side;
    }
}
