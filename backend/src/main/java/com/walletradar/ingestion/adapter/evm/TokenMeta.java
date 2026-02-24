package com.walletradar.ingestion.adapter.evm;

/**
 * Cached token metadata (decimals + symbol) per 02-architecture and ADR-005 tokenMetaCache.
 */
public record TokenMeta(int decimals, String symbol) {
}
