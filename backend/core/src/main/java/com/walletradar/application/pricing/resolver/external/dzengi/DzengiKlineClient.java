package com.walletradar.application.pricing.resolver.external.dzengi;

import com.walletradar.application.cex.acquisition.venue.dzengi.DzengiApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Dzengi public kline client for venue-native FX quotes (e.g. USD/BYN).
 */
@Component
@RequiredArgsConstructor
public class DzengiKlineClient {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final DzengiApiClient dzengiApiClient;

    public Optional<DzengiKline> fetchUsdPerByn(Instant occurredAt) {
        DzengiApiClient.OptionalKline kline = dzengiApiClient.fetchKline("USD/BYN", occurredAt);
        if (!kline.isPresent()) {
            return Optional.empty();
        }
        BigDecimal bynPerUsd = kline.closePrice();
        if (bynPerUsd == null || bynPerUsd.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal usdPerByn = BigDecimal.ONE.divide(bynPerUsd, MC);
        return Optional.of(new DzengiKline(
                "USD/BYN",
                kline.openTime() == null ? occurredAt : kline.openTime(),
                usdPerByn
        ));
    }

    /**
     * Venue-native USD quote. Resolution order per ADR-050/051:
     * 1. {@code SYMBOL.} — tokenized equity / commodity kline (preferred for equities).
     * 2. {@code SYMBOL/USD} — spot crypto kline (fallback for crypto assets).
     *
     * <p>Equity instruments on Dzengi use the trailing-dot notation (e.g. {@code TSLA.}, {@code GOOGL.}).
     * Crypto instruments use the slash pair notation (e.g. {@code BTC/USD}).
     */
    public Optional<DzengiKline> fetchUsdPerUnit(String assetSymbol, Instant occurredAt) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return Optional.empty();
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        if ("BYN".equals(normalized)) {
            return fetchUsdPerByn(occurredAt);
        }
        if ("USD".equals(normalized) || "USDT".equals(normalized) || "USDC".equals(normalized)) {
            return Optional.of(new DzengiKline(normalized, occurredAt, BigDecimal.ONE));
        }
        // Try equity/commodity instrument first (SYMBOL.), then crypto spot (SYMBOL/USD).
        Optional<DzengiKline> equity = fetchKlineClose(normalized + ".", occurredAt);
        if (equity.isPresent()) {
            return equity;
        }
        return fetchKlineClose(normalized + "/USD", occurredAt);
    }

    private Optional<DzengiKline> fetchKlineClose(String klineSymbol, Instant occurredAt) {
        DzengiApiClient.OptionalKline kline = dzengiApiClient.fetchKline(klineSymbol, occurredAt);
        if (!kline.isPresent()) {
            return Optional.empty();
        }
        BigDecimal close = kline.closePrice();
        if (close == null || close.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new DzengiKline(
                klineSymbol,
                kline.openTime() == null ? occurredAt : kline.openTime(),
                close
        ));
    }

    public record DzengiKline(
            String symbol,
            Instant openTime,
            BigDecimal usdPrice
    ) {
    }
}
