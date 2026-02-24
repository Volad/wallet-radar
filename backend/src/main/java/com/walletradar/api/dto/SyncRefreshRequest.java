package com.walletradar.api.dto;

import java.util.List;

/**
 * POST /api/v1/sync/refresh request body.
 */
public record SyncRefreshRequest(List<String> wallets, List<String> networks) {
}
