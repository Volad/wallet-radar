package com.walletradar.liquiditypools.enrichment;

import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FungiblePoolReader implements LpPositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String GET_RESERVES_SELECTOR = "0x" + EvmAbiSupport.selector("getReserves()");
    private static final String TOTAL_SUPPLY_SELECTOR = "0x" + EvmAbiSupport.selector("totalSupply()");

    private final LpRpcSupport rpc;

    @Override
    public boolean supports(LpPositionContext context) {
        if (context == null || context.closed()) {
            return false;
        }
        String family = context.family();
        return context.lpTokenContract() != null
                && family != null
                && (family.contains("FUNGIBLE") || family.contains("CURVE") || family.contains("BALANCER"));
    }

    @Override
    public Optional<LpPositionSnapshot> read(LpPositionContext context) {
        String network = context.networkId().name();
        String lpToken = context.lpTokenContract();
        BigInteger balanceRaw = rpc.erc20Balance(network, lpToken, context.walletAddress()).orElse(BigInteger.ZERO);
        BigInteger totalSupply = rpc.call(network, lpToken, TOTAL_SUPPLY_SELECTOR)
                .map(EvmAbiSupport::uintFromWord).orElse(BigInteger.ZERO);
        if (totalSupply.signum() <= 0 || balanceRaw.signum() <= 0) {
            return Optional.empty();
        }

        String pool = context.poolContract() != null ? context.poolContract() : lpToken;
        String reservesHex = rpc.call(network, pool, GET_RESERVES_SELECTOR).orElse(null);
        if (reservesHex == null) {
            return Optional.empty();
        }

        BigInteger reserve0 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reservesHex, 0));
        BigInteger reserve1 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reservesHex, 1));
        BigDecimal share = new BigDecimal(balanceRaw).divide(new BigDecimal(totalSupply), MC);

        String token0 = context.nfpmContract();
        String token1 = context.poolContract();
        int decimals0 = token0 != null ? rpc.erc20Decimals(network, token0).orElse(18) : 18;
        int decimals1 = token1 != null ? rpc.erc20Decimals(network, token1).orElse(18) : 18;
        String sym0 = token0 != null ? rpc.erc20Symbol(network, token0).orElse("TOKEN0") : "TOKEN0";
        String sym1 = token1 != null ? rpc.erc20Symbol(network, token1).orElse("TOKEN1") : "TOKEN1";

        BigDecimal qty0 = LpLiquidityAmountsSupport.toHuman(
                reserve0.multiply(balanceRaw).divide(totalSupply), decimals0);
        BigDecimal qty1 = LpLiquidityAmountsSupport.toHuman(
                reserve1.multiply(balanceRaw).divide(totalSupply), decimals1);

        LpPositionSnapshot snapshot = GmxPositionReader.baseSnapshot(context);
        snapshot.setFamily(context.family() != null ? context.family() : "FUNGIBLE_LP");
        snapshot.setProtocol(context.protocol());
        snapshot.setStatus("in_range");

        LpPositionSnapshot.TokenSide side0 = new LpPositionSnapshot.TokenSide();
        side0.setSym(sym0);
        side0.setContract(token0);
        side0.setQty(qty0);
        LpPositionSnapshot.TokenSide side1 = new LpPositionSnapshot.TokenSide();
        side1.setSym(sym1);
        side1.setContract(token1);
        side1.setQty(qty1);
        snapshot.setToken0(side0);
        snapshot.setToken1(side1);
        return Optional.of(snapshot);
    }
}
