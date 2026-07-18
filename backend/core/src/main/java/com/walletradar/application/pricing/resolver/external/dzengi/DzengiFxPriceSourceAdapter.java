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

/**
 * Dzengi venue-native FX adapter for BYN via inverted USD/BYN kline.
 */
@Component
@Order(0)
public class DzengiFxPriceSourceAdapter implements ExternalPriceSource {

    private final DzengiKlineClient klineClient;

    public DzengiFxPriceSourceAdapter(DzengiKlineClient klineClient) {
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
        return "BYN".equals(symbol);
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        String symbol = CanonicalAssetCatalog.normalizeSymbol(request.assetSymbol());
        if (!"BYN".equals(symbol)) {
            return Optional.empty();
        }
        Optional<DzengiKlineClient.DzengiKline> kline = klineClient.fetchUsdPerByn(request.occurredAt());
        if (kline.isEmpty()) {
            return Optional.empty();
        }
        DzengiKlineClient.DzengiKline quote = kline.orElseThrow();
        return Optional.of(new PriceQuote(
                quote.usdPrice(),
                PriceSource.DZENGI,
                quote.openTime(),
                "USD",
                "USD/BYN".toUpperCase(Locale.ROOT)
        ));
    }
}
