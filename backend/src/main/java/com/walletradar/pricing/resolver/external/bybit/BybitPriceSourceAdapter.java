package com.walletradar.pricing.resolver.external.bybit;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Bybit market-data adapter used only for canonical Bybit rows.
 */
@Component
@Order(0)
public class BybitPriceSourceAdapter implements ExternalPriceSource {

    private final BybitSymbolMapper symbolMapper;
    private final BybitKlineClient klineClient;

    public BybitPriceSourceAdapter(
            BybitSymbolMapper symbolMapper,
            BybitKlineClient klineClient
    ) {
        this.symbolMapper = symbolMapper;
        this.klineClient = klineClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.BYBIT;
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        List<String> candidates = symbolMapper.candidateSymbols(request);
        for (String candidate : candidates) {
            Optional<BybitKlineClient.BybitKline> kline = klineClient.fetchKline(candidate, request.occurredAt());
            if (kline.isEmpty()) {
                continue;
            }
            return Optional.of(new PriceQuote(
                    kline.orElseThrow().openPriceUsd(),
                    PriceSource.BYBIT,
                    kline.orElseThrow().openTime(),
                    "USD",
                    candidate
            ));
        }
        return Optional.empty();
    }
}
