package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.domain.PriceResolutionContext;
import com.walletradar.application.pricing.resolver.event.EventLocalPriceResolverChain;
import com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves per-flow USD pricing for canonical normalized rows.
 */
@Service
public class PriceResolutionService {

    private final EventLocalPriceResolverChain eventLocalResolvers;
    private final PriceExternalSourceOrchestrator externalSources;
    private final PricingResultMapper pricingResultMapper;

    public PriceResolutionService(
            EventLocalPriceResolverChain eventLocalResolvers,
            PriceExternalSourceOrchestrator externalSources,
            PricingResultMapper pricingResultMapper
    ) {
        this.eventLocalResolvers = eventLocalResolvers;
        this.externalSources = externalSources;
        this.pricingResultMapper = pricingResultMapper;
    }

    public NormalizedTransaction resolve(NormalizedTransaction original, Instant now) {
        return resolve(original, now, this::resolveExternalQuote);
    }

    public NormalizedTransaction resolve(
            NormalizedTransaction original,
            Instant now,
            ExternalQuoteResolver externalQuoteResolver
    ) {
        NormalizedTransaction priced = pricingResultMapper.copy(original);
        stampPricingSkippedFlows(priced);
        Map<Integer, PriceQuote> resolvedQuotes = preloadResolvedQuotes(priced);
        Set<Integer> priceRequired = requiredFlowIndices(priced);
        boolean unresolvedRequired = false;

        resolveEventLocalUntilFixedPoint(priced, priceRequired, resolvedQuotes);

        for (Integer flowIndex : priceRequired) {
            if (resolvedQuotes.containsKey(flowIndex)) {
                continue;
            }
            Optional<PriceQuote> sameAssetQuote = sameAssetResolvedQuote(priced, flowIndex, resolvedQuotes);
            if (sameAssetQuote.isPresent()) {
                applyQuote(priced, flowIndex, sameAssetQuote.orElseThrow(), resolvedQuotes);
                resolveEventLocalUntilFixedPoint(priced, priceRequired, resolvedQuotes);
                continue;
            }

            PriceRequest request = new PriceResolutionContext(
                    priced,
                    priced.getFlows().get(flowIndex),
                    flowIndex,
                    resolvedQuotes
            ).toPriceRequest();

            Optional<PriceQuote> externalQuote = externalQuoteResolver.resolve(request);
            if (externalQuote.isPresent()) {
                applyQuote(priced, flowIndex, externalQuote.orElseThrow(), resolvedQuotes);
                resolveEventLocalUntilFixedPoint(priced, priceRequired, resolvedQuotes);
                continue;
            }

            pricingResultMapper.markUnknown(priced.getFlows().get(flowIndex));
            unresolvedRequired = true;
        }

        pricingResultMapper.finalizePricing(priced, unresolvedRequired, now);
        return priced;
    }

    public Optional<PriceQuote> resolveExternalQuote(PriceRequest request) {
        return externalSources.resolve(request);
    }

    /**
     * Cycle/9 S5: stamp {@link PriceSource#PRICING_SKIPPED} on flows whose asset symbol has no
     * resolvable historical USD source (per {@link CanonicalAssetCatalog#isPricingSkipped}).
     * This explicit marker keeps the dashboard/audit aware that the position was intentionally
     * left unpriced and prevents repeated resolution attempts in subsequent pricing batches.
     */
    private void stampPricingSkippedFlows(NormalizedTransaction priced) {
        if (priced == null || priced.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow flow : priced.getFlows()) {
            if (flow == null
                    || flow.getAssetSymbol() == null
                    || flow.getAssetSymbol().isBlank()
                    || flow.getRole() == NormalizedLegRole.TRANSFER) {
                continue;
            }
            if (!CanonicalAssetCatalog.isPricingSkipped(flow.getAssetSymbol())) {
                continue;
            }
            if (PriceableFlowPolicy.hasResolvedPrice(flow)) {
                continue;
            }
            flow.setPriceSource(PriceSource.PRICING_SKIPPED);
            flow.setUnitPriceUsd(null);
            flow.setValueUsd(null);
        }
    }

    private void resolveEventLocalUntilFixedPoint(
            NormalizedTransaction priced,
            Set<Integer> priceRequired,
            Map<Integer, PriceQuote> resolvedQuotes
    ) {
        boolean changed;
        do {
            changed = false;
            for (Integer flowIndex : priceRequired) {
                if (resolvedQuotes.containsKey(flowIndex)) {
                    continue;
                }
                NormalizedTransaction.Flow flow = priced.getFlows().get(flowIndex);
                PriceResolutionContext context = new PriceResolutionContext(
                        priced,
                        flow,
                        flowIndex,
                        resolvedQuotes
                );
                Optional<PriceQuote> quote = eventLocalResolvers.resolve(context);
                if (quote.isPresent()) {
                    applyQuote(priced, flowIndex, quote.orElseThrow(), resolvedQuotes);
                    changed = true;
                }
            }
        } while (changed);
    }

    private Map<Integer, PriceQuote> preloadResolvedQuotes(NormalizedTransaction priced) {
        Map<Integer, PriceQuote> resolved = new LinkedHashMap<>();
        for (int flowIndex = 0; flowIndex < priced.getFlows().size(); flowIndex++) {
            NormalizedTransaction.Flow flow = priced.getFlows().get(flowIndex);
            if (!PriceableFlowPolicy.hasResolvedPrice(flow)) {
                continue;
            }
            resolved.put(flowIndex, new PriceQuote(
                    flow.getUnitPriceUsd(),
                    flow.getPriceSource(),
                    priced.getBlockTimestamp(),
                    flow.getAssetSymbol(),
                    "existing-flow-price"
            ));
        }
        return resolved;
    }

    private Set<Integer> requiredFlowIndices(NormalizedTransaction priced) {
        Set<Integer> required = new LinkedHashSet<>();
        for (int flowIndex = 0; flowIndex < priced.getFlows().size(); flowIndex++) {
            if (PriceableFlowPolicy.requiresMarketPrice(priced, priced.getFlows().get(flowIndex))) {
                required.add(flowIndex);
            }
        }
        return required;
    }

    private Optional<PriceQuote> sameAssetResolvedQuote(
            NormalizedTransaction priced,
            int flowIndex,
            Map<Integer, PriceQuote> resolvedQuotes
    ) {
        PriceRequest request = new PriceResolutionContext(
                priced,
                priced.getFlows().get(flowIndex),
                flowIndex,
                resolvedQuotes
        ).toPriceRequest();
        for (Map.Entry<Integer, PriceQuote> entry : resolvedQuotes.entrySet()) {
            int siblingIndex = entry.getKey();
            if (siblingIndex == flowIndex) {
                continue;
            }
            NormalizedTransaction.Flow sibling = priced.getFlows().get(siblingIndex);
            PriceRequest siblingRequest = new PriceResolutionContext(
                    priced,
                    sibling,
                    siblingIndex,
                    resolvedQuotes
            ).toPriceRequest();
            if (request.assetKey().equals(siblingRequest.assetKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private void applyQuote(
            NormalizedTransaction priced,
            int flowIndex,
            PriceQuote quote,
            Map<Integer, PriceQuote> resolvedQuotes
    ) {
        pricingResultMapper.applyResolvedQuote(priced.getFlows().get(flowIndex), quote);
        resolvedQuotes.put(flowIndex, quote);
    }

    @FunctionalInterface
    public interface ExternalQuoteResolver {
        Optional<PriceQuote> resolve(PriceRequest request);
    }
}
