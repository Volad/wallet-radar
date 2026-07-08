package com.walletradar.platform.networks.evm.rpc;

/**
 * Represents a single JSON-RPC request inside a batch.
 */
public record RpcRequest(String method, Object params) {}
