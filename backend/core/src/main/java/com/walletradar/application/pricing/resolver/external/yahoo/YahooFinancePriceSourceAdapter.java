package com.walletradar.application.pricing.resolver.external.yahoo;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.resolver.external.ExternalPriceSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Yahoo Finance fallback price adapter for US equities not covered by Dzengi's kline API.
 *
 * <p>Handles DZENGI-sourced transactions for symbols that return "Invalid symbol" from
 * Dzengi's {@code /api/v1/klines} endpoint (e.g. GOOGL, NFLX, PYPL, SNAP, BABA).
 * Only activated after {@link
 * com.walletradar.application.pricing.resolver.external.dzengi.DzengiEquityPriceSourceAdapter}
 * fails (Order=1 → this Order=2).
 *
 * <p>Uses the unofficial Yahoo Finance v8 chart API — no API key required.
 */
@Component
@Order(2)
public class YahooFinancePriceSourceAdapter implements ExternalPriceSource {

    private static final Set<String> EXCLUDED_SYMBOLS =
            Set.of("BYN", "USD", "USDT", "USDC", "USDE", "USDS", "ETH", "BTC", "SOL",
                    "BNB", "DOGE", "XRP", "ADA", "DOT", "MATIC", "AVAX", "LINK",
                    "UNI", "AAVE", "COMP", "MKR", "SNX", "CRV", "BAL", "SUSHI",
                    "YFI", "1INCH", "LDO", "RPL", "FXS", "CVX", "PENDLE",
                    "WETH", "WBTC", "STETH", "RETH", "CBETH", "WEETH", "METH", "CMETH");

    private final YahooFinanceClient yahooFinanceClient;

    public YahooFinancePriceSourceAdapter(YahooFinanceClient yahooFinanceClient) {
        this.yahooFinanceClient = yahooFinanceClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.YAHOO_FINANCE;
    }

    @Override
    public boolean supports(PriceRequest request) {
        if (request.transactionSource() != NormalizedTransactionSource.DZENGI) {
            return false;
        }
        String symbol = CanonicalAssetCatalog.normalizeSymbol(request.assetSymbol());
        if (symbol == null) {
            return false;
        }
        String upper = symbol.toUpperCase(Locale.ROOT);
        // Only handle symbols that look like equity tickers (no slash, short name, not crypto/stablecoin)
        return !upper.contains("/")
                && !upper.endsWith(".")
                && !EXCLUDED_SYMBOLS.contains(upper);
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        String symbol = CanonicalAssetCatalog.normalizeSymbol(request.assetSymbol());
        if (symbol == null) {
            return Optional.empty();
        }
        Optional<YahooFinanceClient.YahooQuote> quote =
                yahooFinanceClient.fetchUsdClose(symbol, request.occurredAt());
        if (quote.isEmpty()) {
            return Optional.empty();
        }
        YahooFinanceClient.YahooQuote q = quote.get();
        return Optional.of(new PriceQuote(
                q.usdPrice(),
                PriceSource.YAHOO_FINANCE,
                q.closeTime(),
                "USD",
                symbol.toUpperCase(Locale.ROOT) + "/USD"
        ));
    }
}
