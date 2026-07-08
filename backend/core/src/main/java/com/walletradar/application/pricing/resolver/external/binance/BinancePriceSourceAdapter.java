package com.walletradar.application.pricing.resolver.external.binance;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.resolver.external.ExternalPriceSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Binance historical market-data adapter.
 */
@Component
@Order(0)
public class BinancePriceSourceAdapter implements ExternalPriceSource {

    private final BinanceSymbolMapper symbolMapper;
    private final BinanceKlineClient klineClient;

    public BinancePriceSourceAdapter(
            BinanceSymbolMapper symbolMapper,
            BinanceKlineClient klineClient
    ) {
        this.symbolMapper = symbolMapper;
        this.klineClient = klineClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.BINANCE;
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        List<String> candidates = symbolMapper.candidateSymbols(request);
        for (String candidate : candidates) {
            Optional<BinanceKlineClient.BinanceKline> kline = klineClient.fetchKline(candidate, request.occurredAt());
            if (kline.isEmpty()) {
                continue;
            }
            return Optional.of(new PriceQuote(
                    kline.orElseThrow().openPriceUsd(),
                    PriceSource.BINANCE,
                    kline.orElseThrow().openTime(),
                    "USD",
                    candidate
            ));
        }
        return Optional.empty();
    }
}
