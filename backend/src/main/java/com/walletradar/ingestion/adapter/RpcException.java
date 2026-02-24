package com.walletradar.ingestion.adapter;

/**
 * Thrown when an RPC call fails (HTTP or JSON-RPC error).
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message);
    }

    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
