package com.walletradar.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.domain.PriceResolutionContext;
import com.walletradar.pricing.resolver.event.EventLocalPriceResolverChain;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
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
        NormalizedTransaction priced = pricingResultMapper.copy(original);
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

            Optional<PriceQuote> externalQuote = externalSources.resolve(request);
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
}
