package com.walletradar.ingestion.adapter.evm;

/**
 * Represents a single JSON-RPC request inside a batch.
 */
public record RpcRequest(String method, Object params) {}
