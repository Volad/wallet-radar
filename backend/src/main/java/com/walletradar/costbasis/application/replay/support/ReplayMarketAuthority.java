package com.walletradar.costbasis.application.replay.support;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction.Flow;
import com.walletradar.pricing.application.PriceableFlowPolicy;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic replay-time market authority: flow quote, then cached historical prices,
 * then catalog stablecoin par. Never calls external APIs during replay.
 */
@Component
public class ReplayMarketAuthority {

    private final HistoricalPriceCacheService historicalPriceCacheService;
    private final PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;

    public ReplayMarketAuthority(
            HistoricalPriceCacheService historicalPriceCacheService,
            PriceExternalSourceOrchestrator priceExternalSourceOrchestrator
    ) {
        this.historicalPriceCacheService = Objects.requireNonNull(
                historicalPriceCacheService,
                "historicalPriceCacheService"
        );
        this.priceExternalSourceOrchestrator = Objects.requireNonNull(
                priceExternalSourceOrchestrator,
                "priceExternalSourceOrchestrator"
        );
    }

    /**
     * P0-A: Resolves market price from historical cache or catalog only — bypasses any unit price
     * embedded in {@code flow.getUnitPriceUsd()}. Use when the flow-embedded price is known to be
     * stale or incorrect (e.g., earn-principal CMETH normalization mis-assigned a $4 spot price
     * instead of the ~$2280 historical market price).
     */
    public Optional<ResolvedMarketPrice> resolveFromCacheOrCatalog(NormalizedTransaction transaction, Flow flow) {
        if (transaction == null || flow == null || flow.getAssetSymbol() == null) {
            return Optional.empty();
        }
        Instant occurredAt = transaction.getBlockTimestamp();
        if (occurredAt != null) {
            PriceRequest request = toPriceRequest(transaction, flow, occurredAt);
            for (PriceSource source : priceExternalSourceOrchestrator.prioritizedSources(request)) {
                Optional<PriceQuote> cached = historicalPriceCacheService.findQuote(request, source);
                if (cached.isPresent() && cached.get().unitPriceUsd() != null
                        && cached.get().unitPriceUsd().signum() > 0) {
                    return Optional.of(new ResolvedMarketPrice(
                            cached.get().unitPriceUsd(),
                            cached.get().source(),
                            ResolvedMarketPrice.Authority.HISTORICAL_CACHE
                    ));
                }
            }
            Optional<ResolvedMarketPrice> crossNetwork = resolveCanonicalCrossNetwork(transaction, flow, occurredAt);
            if (crossNetwork.isPresent()) {
                return crossNetwork;
            }
        }
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
            return Optional.of(new ResolvedMarketPrice(
                    BigDecimal.ONE,
                    PriceSource.STABLECOIN,
                    ResolvedMarketPrice.Authority.STABLECOIN_PAR
            ));
        }
        return Optional.empty();
    }

    public Optional<ResolvedMarketPrice> resolve(NormalizedTransaction transaction, Flow flow) {
        if (transaction == null || flow == null || flow.getAssetSymbol() == null) {
            return Optional.empty();
        }
        if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return Optional.empty();
        }
        if (PriceableFlowPolicy.hasResolvedPrice(flow)) {
            Optional<ResolvedMarketPrice> clampedBotLot = clampPreCoverageBotLot(transaction, flow);
            if (clampedBotLot.isPresent()) {
                return clampedBotLot;
            }
            return Optional.of(new ResolvedMarketPrice(
                    flow.getUnitPriceUsd(),
                    flow.getPriceSource(),
                    ResolvedMarketPrice.Authority.FLOW
            ));
        }
        Instant occurredAt = transaction.getBlockTimestamp();
        if (occurredAt != null) {
            PriceRequest request = toPriceRequest(transaction, flow, occurredAt);
            for (PriceSource source : priceExternalSourceOrchestrator.prioritizedSources(request)) {
                Optional<PriceQuote> cached = historicalPriceCacheService.findQuote(request, source);
                if (cached.isPresent() && cached.get().unitPriceUsd() != null
                        && cached.get().unitPriceUsd().signum() > 0) {
                    return Optional.of(new ResolvedMarketPrice(
                            cached.get().unitPriceUsd(),
                            cached.get().source(),
                            ResolvedMarketPrice.Authority.HISTORICAL_CACHE
                    ));
                }
            }
            Optional<ResolvedMarketPrice> crossNetwork = resolveCanonicalCrossNetwork(transaction, flow, occurredAt);
            if (crossNetwork.isPresent()) {
                return crossNetwork;
            }
        }
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
            return Optional.of(new ResolvedMarketPrice(
                    BigDecimal.ONE,
                    PriceSource.STABLECOIN,
                    ResolvedMarketPrice.Authority.STABLECOIN_PAR
            ));
        }
        return Optional.empty();
    }

    /**
     * RC-D (ADR-043) — clamp a genuinely pre-coverage Bybit trading-bot lot to the nearest valid
     * market bucket. A {@link PriceSource#BOT_LEDGER} lot is priced at normalization from net
     * stablecoin consumed (before historical prices exist) and marked CONFIRMED, so it never reaches
     * the pricing orchestrator's pre-coverage fallback; on a clean rebuild ({@code --clear-pricing-cache})
     * historical prices are also empty during normalization, so the derived unit price stands even when
     * it is out of range (e.g. the 2025-01 DOGE lot at $0.5766/unit vs the asset's first bucket at
     * $0.23246 in 2025-09). At replay time historical prices are fully populated, so this reuses the
     * orchestrator's bounded pre-coverage window to clamp ONLY lots whose event predates the asset's
     * first cached bucket. In-coverage bot lots keep their stablecoin-derived price untouched.
     */
    private Optional<ResolvedMarketPrice> clampPreCoverageBotLot(NormalizedTransaction transaction, Flow flow) {
        if (flow.getPriceSource() != PriceSource.BOT_LEDGER) {
            return Optional.empty();
        }
        Instant occurredAt = transaction.getBlockTimestamp();
        if (occurredAt == null) {
            return Optional.empty();
        }
        PriceRequest request = toPriceRequest(transaction, flow, occurredAt);
        Optional<PriceQuote> clamp = priceExternalSourceOrchestrator.resolvePreCoverageNearestBucket(request);
        if (clamp.isEmpty() || clamp.get().unitPriceUsd() == null || clamp.get().unitPriceUsd().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedMarketPrice(
                clamp.get().unitPriceUsd(),
                clamp.get().source(),
                ResolvedMarketPrice.Authority.HISTORICAL_CACHE
        ));
    }

    /**
     * F-5(a): cross-network market-at-timestamp fallback for fungible canonical assets. When the
     * network/contract-scoped cache misses (e.g. a zkSync ETH {@code BRIDGE_IN}, a Mantle MNT
     * borrow, or a Bybit collapsed-asset carry-in whose own chain/contract was never priced at that
     * minute), reuse a same-minute quote for the same canonical asset priced on any other network.
     * ETH/MNT/BTC and Bybit-traded majors carry one global USD price, so this conserves the true
     * market-at-time basis instead of diluting the pool with $0. Confusable lookalikes and unknown
     * low-cap symbols are excluded by {@link CanonicalAssetCatalog#isCrossNetworkPriceResolvable}.
     */
    private Optional<ResolvedMarketPrice> resolveCanonicalCrossNetwork(
            NormalizedTransaction transaction,
            Flow flow,
            Instant occurredAt
    ) {
        if (!CanonicalAssetCatalog.isCrossNetworkPriceResolvable(flow.getAssetSymbol())) {
            return Optional.empty();
        }
        List<String> candidateSymbols = CanonicalAssetCatalog.marketEquivalentSymbols(flow.getAssetSymbol());
        if (candidateSymbols.isEmpty()) {
            return Optional.empty();
        }
        PriceRequest request = toPriceRequest(transaction, flow, occurredAt);
        for (PriceSource source : priceExternalSourceOrchestrator.prioritizedSources(request)) {
            Optional<PriceQuote> cached = historicalPriceCacheService.findCanonicalQuote(
                    candidateSymbols,
                    occurredAt,
                    source
            );
            if (cached.isPresent() && cached.get().unitPriceUsd() != null
                    && cached.get().unitPriceUsd().signum() > 0) {
                return Optional.of(new ResolvedMarketPrice(
                        cached.get().unitPriceUsd(),
                        cached.get().source(),
                        ResolvedMarketPrice.Authority.HISTORICAL_CACHE
                ));
            }
        }
        return Optional.empty();
    }

    private static PriceRequest toPriceRequest(
            NormalizedTransaction transaction,
            Flow flow,
            Instant occurredAt
    ) {
        NormalizedTransactionSource source = transaction.getSource() == null
                ? NormalizedTransactionSource.ON_CHAIN
                : transaction.getSource();
        return new PriceRequest(
                transaction.getId(),
                source,
                transaction.getNetworkId(),
                flow.getAssetContract(),
                flow.getAssetSymbol(),
                occurredAt
        );
    }

    public record ResolvedMarketPrice(
            BigDecimal unitPriceUsd,
            PriceSource priceSource,
            Authority authority
    ) {
        public enum Authority {
            FLOW,
            HISTORICAL_CACHE,
            STABLECOIN_PAR
        }
    }
}
