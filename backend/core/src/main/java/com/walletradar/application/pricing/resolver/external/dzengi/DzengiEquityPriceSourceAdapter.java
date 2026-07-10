package com.walletradar.application.pricing.resolver.external.dzengi;

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
 * Dzengi venue-native equity and crypto price adapter.
 *
 * <p>Resolution order per symbol: {@code SYMBOL.} (tokenized equity kline) first,
 * then {@code SYMBOL/USD} (spot crypto kline) — matching Dzengi's two-tier instrument model.
 * Results are cached in {@code historical_prices} via {@link
 * com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator}.
 *
 * <p>Covers all DZENGI-sourced transactions except BYN (handled by
 * {@link DzengiFxPriceSourceAdapter}) and stablecoins priced at $1 by the engine.
 */
@Component
@Order(1)
public class DzengiEquityPriceSourceAdapter implements ExternalPriceSource {

    private static final Set<String> EXCLUDED_SYMBOLS = Set.of("BYN", "USD", "USDT", "USDC", "USDE", "USDS");

    private final DzengiKlineClient klineClient;

    public DzengiEquityPriceSourceAdapter(DzengiKlineClient klineClient) {
        this.klineClient = klineClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.DZENGI;
    }

    @Override
    public boolean supports(PriceRequest request) {
        if (request.transactionSource() != NormalizedTransactionSource.DZENGI) {
            return false;
        }
        String symbol = CanonicalAssetCatalog.normalizeSymbol(request.assetSymbol());
        return symbol != null && !EXCLUDED_SYMBOLS.contains(symbol.toUpperCase(Locale.ROOT));
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        String symbol = CanonicalAssetCatalog.normalizeSymbol(request.assetSymbol());
        if (symbol == null || EXCLUDED_SYMBOLS.contains(symbol.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Optional<DzengiKlineClient.DzengiKline> kline = klineClient.fetchUsdPerUnit(symbol, request.occurredAt());
        if (kline.isEmpty()) {
            return Optional.empty();
        }
        DzengiKlineClient.DzengiKline quote = kline.orElseThrow();
        // Determine which Dzengi instrument pair was resolved (SYMBOL. or SYMBOL/USD).
        String pairHint = symbol.toUpperCase(Locale.ROOT) + "/USD";
        return Optional.of(new PriceQuote(
                quote.usdPrice(),
                PriceSource.DZENGI,
                quote.openTime(),
                "USD",
                pairHint
        ));
    }
}
