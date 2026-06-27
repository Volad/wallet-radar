package com.walletradar.liquiditypools.enrichment;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.pricing.application.GmxProtocolSnapshotValuationService;
import com.walletradar.pricing.domain.PriceQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GmxPositionReader implements LpPositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final GmxProtocolSnapshotValuationService gmxValuation;
    private final LpRpcSupport rpc;

    @Override
    public boolean supports(LpPositionContext context) {
        return context != null
                && !context.closed()
                && context.correlationId() != null
                && context.correlationId().startsWith("gmx-lp:");
    }

    @Override
    public Optional<LpPositionSnapshot> read(LpPositionContext context) {
        if (context.lpTokenContract() == null || context.lpTokenContract().isBlank()) {
            return Optional.empty();
        }
        NetworkId networkId = context.networkId();
        String marketToken = context.lpTokenContract();
        // marketSlug is the pair slug (e.g. "weth-usdc"), not a GMX symbol.
        // Prefix with "GM:" so isGmxSymbol() accepts it and the display label is clear.
        String symbol = "GM:" + (context.marketSlug() != null ? context.marketSlug().toUpperCase() : "?");

        Optional<PriceQuote> quote = gmxValuation.resolveMarketTokenQuote(
                networkId, marketToken, symbol, Instant.now());
        if (quote.isEmpty()) {
            return Optional.empty();
        }

        BigInteger balanceRaw = rpc.erc20Balance(networkId.name(), marketToken, context.walletAddress())
                .orElse(BigInteger.ZERO);
        int decimals = rpc.erc20Decimals(networkId.name(), marketToken).orElse(18);
        BigDecimal qty = new BigDecimal(balanceRaw).divide(BigDecimal.TEN.pow(decimals), MC);
        BigDecimal priceUsd = quote.get().unitPriceUsd();
        BigDecimal valueUsd = qty.multiply(priceUsd, MC);

        LpPositionSnapshot snapshot = baseSnapshot(context);
        LpPositionSnapshot.TokenSide side = new LpPositionSnapshot.TokenSide();
        side.setSym(symbol);
        side.setContract(marketToken);
        side.setQty(qty);
        side.setUsd(valueUsd);
        snapshot.setToken0(side);
        snapshot.setTvlUsd(valueUsd);
        // Zero balance means all GM/GLV tokens were transferred/burned — position is closed.
        snapshot.setStatus(qty.signum() == 0 ? "closed" : "in_range");
        return Optional.of(snapshot);
    }

    static LpPositionSnapshot baseSnapshot(LpPositionContext context) {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(context.correlationId());
        snapshot.setUniverseId(context.universeId());
        snapshot.setNetworkId(context.networkId().name());
        snapshot.setWalletAddress(context.walletAddress());
        snapshot.setProtocol(context.protocol() != null ? context.protocol() : "GMX");
        snapshot.setFamily("GMX_LP");
        snapshot.setStaked(context.staked());
        snapshot.setSnapshotAt(Instant.now());
        snapshot.setSnapshotStale(false);
        return snapshot;
    }
}
