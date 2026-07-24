package com.walletradar.platform.networks.solana.helius;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Helius Enhanced Transactions REST API client.
 *
 * <p>Provides parsed DeFi transaction data (type, source, events, tokenTransfers, nativeTransfers)
 * needed for Solana normalization without raw instruction decoding.</p>
 */
public interface HeliusSolanaClient {

    /**
     * Fetches a page of parsed transactions for the given wallet address.
     *
     * @param address   base58 Solana wallet address
     * @param before    cursor signature (exclusive) for pagination; null for the first page
     * @param limit     max transactions to return per page (1–100)
     * @return list of parsed transaction objects; empty when no more pages
     */
    List<JsonNode> getTransactionHistory(String address, String before, int limit);

    /**
     * Parses a batch of raw signatures into enriched transaction objects.
     * Used when enriching signatures already fetched via {@code getSignaturesForAddress}.
     *
     * @param signatures list of base58 transaction signatures (max 100 per call)
     * @return list of enriched transaction objects (may be smaller than signatures if some fail)
     */
    List<JsonNode> parseTransactions(List<String> signatures);
}
