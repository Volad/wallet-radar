package com.walletradar.platform.networks.ton.price;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Free-tier STON.fi (TON) price client for jetton USD marks.
 *
 * <p>Best-effort by contract: implementations must never throw. Any venue error (rate limit,
 * timeout, 4xx/5xx, malformed body) resolves to an empty map so callers fall back gracefully
 * (jetton left unpriced).</p>
 */
public interface TonPriceClient {

    /**
     * Fetches current USD prices for every DEX-listed TON jetton in a single call.
     *
     * @return map of canonical jetton master key ({@code workchain:hex}, lowercase) → USD price;
     *         never null. Only positive prices are included.
     */
    Map<String, BigDecimal> fetchAllPrices();
}
