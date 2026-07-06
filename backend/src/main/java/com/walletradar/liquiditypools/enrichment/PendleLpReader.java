package com.walletradar.liquiditypools.enrichment;

import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PendleLpReader implements LpPositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final LpRpcSupport rpc;

    @Override
    public boolean supports(LpPositionContext context) {
        return context != null
                && !context.closed()
                && context.correlationId() != null
                && context.correlationId().startsWith("pendle-lp:");
    }

    @Override
    public Optional<LpPositionSnapshot> read(LpPositionContext context) {
        if (context.lpTokenContract() == null || context.lpTokenContract().isBlank()) {
            return Optional.empty();
        }
        String network = context.networkId().name();
        BigInteger balanceRaw = rpc.erc20Balance(network, context.lpTokenContract(), context.walletAddress())
                .orElse(BigInteger.ZERO);
        int decimals = rpc.erc20Decimals(network, context.lpTokenContract()).orElse(18);
        String symbol = rpc.erc20Symbol(network, context.lpTokenContract()).orElse("PENDLE-LPT");
        BigDecimal qty = new BigDecimal(balanceRaw).divide(BigDecimal.TEN.pow(decimals), MC);

        LpPositionSnapshot snapshot = GmxPositionReader.baseSnapshot(context);
        snapshot.setProtocol(context.protocol() != null ? context.protocol() : "Pendle");
        snapshot.setFamily("PENDLE_LP");

        LpPositionSnapshot.TokenSide side = new LpPositionSnapshot.TokenSide();
        side.setSym(symbol);
        side.setContract(context.lpTokenContract());
        side.setQty(qty);
        snapshot.setToken0(side);
        snapshot.setStatus("in_range");
        return Optional.of(snapshot);
    }
}
