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
