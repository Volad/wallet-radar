package com.walletradar.platform.networks.ton;

import java.util.Map;

/**
 * HTTP client for the TON Center v3 REST API.
 *
 * <p>Implementations make authenticated (or unauthenticated) GET requests against
 * {@code https://toncenter.com/api/v3/}. All query-string parameters are encoded
 * from the supplied map; the base URL is owned by the implementation.</p>
 */
public interface TonRpcClient {

    /**
     * Executes a GET request to {@code {baseUrl}/{relativePath}} with the given query params.
     *
     * @param relativePath path relative to the configured base URL (e.g. {@code "transactions"})
     * @param queryParams  zero or more query parameters appended to the URL
     * @return raw JSON response body as a String
     * @throws com.walletradar.platform.networks.RpcException on HTTP error or timeout
     */
    String get(String relativePath, Map<String, String> queryParams);

    /**
     * Returns the current masterchain seqno (block-height equivalent for TON).
     *
     * @throws com.walletradar.platform.networks.RpcException on HTTP error or timeout
     */
    long getMasterchainSeqno();
}
