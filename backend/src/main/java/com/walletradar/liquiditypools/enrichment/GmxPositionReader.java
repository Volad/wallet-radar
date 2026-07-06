package com.walletradar.liquiditypools.enrichment;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshotRepository;
import com.walletradar.pricing.application.GmxProtocolSnapshotValuationService;
import com.walletradar.pricing.domain.PriceQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class GmxPositionReader implements LpPositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final GmxProtocolSnapshotValuationService gmxValuation;
    private final LpRpcSupport rpc;
    private final GmxCollectedFeesReader collectedFeesReader;
    private final LpPositionSnapshotRepository snapshotRepository;

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
        // Build a display symbol from the market slug. GLV slugs are prefixed with "glv-"
        // (e.g. "glv-weth-usdc") so they display as "GLV [WETH-USDC]"; GM slugs (e.g.
        // "weth-usdc") display as "GM:WETH-USDC". isGmxSymbol() accepts both prefixes.
        String slug = context.marketSlug() != null ? context.marketSlug() : "?";
        String symbol;
        if (slug.startsWith("glv-")) {
            symbol = "GLV [" + slug.substring(4).toUpperCase(java.util.Locale.ROOT) + "]";
        } else {
            symbol = "GM:" + slug.toUpperCase(java.util.Locale.ROOT);
        }

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

        // Populate fee APR from GMX protocol API. GMX fees accrue into the pool token price
        // (no separate LP_FEE_CLAIM events), so the protocol-reported fee APR is the only
        // source of this metric. We store it as both feeAprPct (specific field) and aprNow
        // (generic snapshot field used by buildApr in SessionLpQueryService).
        gmxValuation.resolveMarketFeeApr(networkId, marketToken).ifPresent(feeApr -> {
            snapshot.setFeeAprPct(feeApr);
            snapshot.setAprNow(feeApr);
        });

        // Populate cumulative fee accumulators for earned-fee calculation.
        // The entry accumulator is seeded once from the Subsquid GraphQL at the LP entry time
        // and preserved across subsequent refreshes. The current accumulator is refreshed every time.
        populateFeeAccumulators(context, marketToken, snapshot);

        return Optional.of(snapshot);
    }

    private void populateFeeAccumulators(
            LpPositionContext context,
            String marketToken,
            LpPositionSnapshot snapshot
    ) {
        NetworkId networkId = context.networkId();

        // Always refresh the current accumulator.
        Optional<BigDecimal> currentFee = collectedFeesReader.getCumulativeFeePerPoolValue(
                networkId, marketToken, Instant.now());
        currentFee.ifPresent(snapshot::setCurrentFeePerPoolValue);

        // The entry accumulator is only set once — carry it forward from the persisted snapshot if present.
        LpPositionSnapshot existing = snapshotRepository.findByCorrelationId(context.correlationId()).orElse(null);
        if (existing != null && existing.getEntryFeePerPoolValue() != null) {
            snapshot.setEntryFeePerPoolValue(existing.getEntryFeePerPoolValue());
        } else if (context.entryAt() != null) {
            // First snapshot for this position: seed entry accumulator at deposit time.
            collectedFeesReader.getCumulativeFeePerPoolValue(networkId, marketToken, context.entryAt())
                    .ifPresent(entryFee -> {
                        snapshot.setEntryFeePerPoolValue(entryFee);
                        log.info("GMX earned-fee baseline seeded: correlationId={} entryAt={} entryFeePerPoolValue={}",
                                context.correlationId(), context.entryAt(), entryFee);
                    });
        }
    }

    static LpPositionSnapshot baseSnapshot(LpPositionContext context) {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(context.correlationId());
        snapshot.setUniverseId(context.universeId());
        snapshot.setNetworkId(context.networkId().name());
        snapshot.setWalletAddress(context.walletAddress());
        snapshot.setProtocol(context.protocol() != null ? context.protocol() : "GMX");
        // Use the family inferred from the correlationId by LpPositionRefreshService (GMX_LP or GLV_LP).
        String family = context.family() != null ? context.family() : "GMX_LP";
        snapshot.setFamily(family);
        snapshot.setStaked(context.staked());
        snapshot.setSnapshotAt(Instant.now());
        snapshot.setSnapshotStale(false);
        return snapshot;
    }
}
