package com.walletradar.application.pricing.domain;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Full canonical context used by the pricing stage to resolve one flow.
 */
public record PriceResolutionContext(
        NormalizedTransaction transaction,
        NormalizedTransaction.Flow flow,
        int flowIndex,
        Map<Integer, PriceQuote> resolvedQuotesByFlowIndex
) {

    public PriceResolutionContext {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(flow, "flow");
        resolvedQuotesByFlowIndex = Map.copyOf(Objects.requireNonNullElse(resolvedQuotesByFlowIndex, Map.of()));
    }

    public PriceRequest toPriceRequest() {
        return new PriceRequest(
                transaction.getId(),
                transaction.getSource(),
                transaction.getNetworkId(),
                flow.getAssetContract(),
                flow.getAssetSymbol(),
                transaction.getBlockTimestamp()
        );
    }

    public List<NormalizedTransaction.Flow> flows() {
        return transaction.getFlows() == null ? List.of() : transaction.getFlows();
    }

    public Optional<PriceQuote> resolvedQuote(int siblingFlowIndex) {
        return Optional.ofNullable(resolvedQuotesByFlowIndex.get(siblingFlowIndex));
    }
}
