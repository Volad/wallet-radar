package com.walletradar.ingestion.job;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.PriceSource;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-backfill job that resolves prices for events marked PRICE_PENDING.
 * Groups events by (assetContract, date) to minimize CoinGecko API calls.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeferredPriceResolutionJob {

    private final EconomicEventRepository economicEventRepository;
    private final HistoricalPriceResolverChain historicalPriceResolverChain;

    /**
     * Resolve prices for all PRICE_PENDING events for a given wallet.
     * Called after backfill completes for a network, before AVCO recalculation.
     */
    public void resolveForWallet(String walletAddress) {
        List<EconomicEvent> pending = economicEventRepository
                .findByWalletAddressAndFlagCode(walletAddress, FlagCode.PRICE_PENDING);

        if (pending.isEmpty()) {
            log.debug("No PRICE_PENDING events for wallet {}", walletAddress);
            return;
        }

        log.info("Resolving prices for {} PRICE_PENDING events (wallet {})", pending.size(), walletAddress);

        Map<String, PriceResolutionResult> priceCache = new HashMap<>();
        int resolved = 0;

        for (EconomicEvent event : pending) {
            if (event.getPriceUsd() != null || isInlineResolved(event.getPriceSource())) {
                continue;
            }

            String cacheKey = buildCacheKey(event);

            PriceResolutionResult result = priceCache.computeIfAbsent(cacheKey, k -> {
                HistoricalPriceRequest req = new HistoricalPriceRequest();
                req.setAssetContract(event.getAssetContract());
                req.setNetworkId(event.getNetworkId());
                req.setBlockTimestamp(event.getBlockTimestamp());
                return historicalPriceResolverChain.resolve(req);
            });

            if (!result.isUnknown() && result.getPriceUsd().isPresent()) {
                event.setPriceUsd(result.getPriceUsd().get());
                event.setPriceSource(result.getPriceSource());
                event.setFlagCode(null);
                event.setFlagResolved(true);
                resolved++;
            } else {
                event.setFlagCode(FlagCode.PRICE_UNKNOWN);
                event.setFlagResolved(false);
            }
            economicEventRepository.save(event);
        }

        log.info("Price resolution complete for wallet {}: {}/{} resolved",
                walletAddress, resolved, pending.size());
    }

    private static boolean isInlineResolved(PriceSource source) {
        return source == PriceSource.STABLECOIN || source == PriceSource.SWAP_DERIVED;
    }

    private static String buildCacheKey(EconomicEvent event) {
        LocalDate date = event.getBlockTimestamp() != null
                ? event.getBlockTimestamp().atOffset(ZoneOffset.UTC).toLocalDate()
                : null;
        return event.getAssetContract() + ":" + date;
    }
}
