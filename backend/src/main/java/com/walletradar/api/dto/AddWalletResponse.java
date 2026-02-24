package com.walletradar.api.dto;

/**
 * POST /api/v1/wallets 202 response.
 */
public record AddWalletResponse(String syncId, String message) {
}
