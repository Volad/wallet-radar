package com.walletradar.domain.common;

/**
 * Canonical source of USD pricing used by normalized transaction flows.
 */
public enum PriceSource {
    STABLECOIN,
    EXECUTION,
    SWAP_DERIVED,
    WRAPPER,
    ECB,
    /** Dzengi venue-native fiat FX and klines (e.g. USD/BYN). */
    DZENGI,
    /** Yahoo Finance unofficial v8 API — fallback for US equities not in Dzengi klines. */
    YAHOO_FINANCE,
    BYBIT,
    BINANCE,
    COINGECKO,
    DEFILLAMA,
    /** Jupiter aggregator (Solana): SPL latest USD price by mint (Price API v3). */
    JUPITER,
    /** STON.fi DEX (TON): jetton latest USD price by master contract (free /v1/assets feed). */
    STON_FI,
    PROTOCOL_SNAPSHOT,
    MANUAL,
    /** Matched crypto-loan principal disposal at borrow-time AVCO (ADR-012 §D3). */
    LIABILITY_MATCH,
    /**
     * Cycle/9 S5: asset has no resolvable historical USD price (no CoinGecko listing or
     * exchange pair). The flow is excluded from market-pricing requirements and from the
     * conservation coverage gate; AVCO basis stays at 0 and downstream PnL excludes the
     * position.
     */
    PRICING_SKIPPED,
    /**
     * Cycle/15 R5 F3: 1:1 pegged liquid-staking / restaking receipt (CMETH, METH, WEETH,
     * BBSOL, etc.). Used when a {@link com.walletradar.domain.transaction.normalized.NormalizedLegRole#TRANSFER}
     * flow with such a receipt arrives without resolvable upstream basis carry. The price
     * is resolved against the canonical underlying ({@code CanonicalAssetCatalog}) at the
     * event timestamp via standard external sources (Bybit / Binance / CoinGecko) and
     * stamped here to make the spot-basis fallback in the replay engine deterministic.
     */
    PEGGED_NATIVE,
    /**
     * Cost basis derived from bot-ledger investment analysis: net stablecoin consumed
     * divided by total non-stablecoin received within a single-asset bot session.
     */
    BOT_LEDGER,
    UNKNOWN
}
