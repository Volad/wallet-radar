package com.walletradar.api.dto;

import com.walletradar.domain.NetworkId;

import java.util.List;

/**
 * POST /api/v1/wallets request body.
 */
public record AddWalletRequest(String address, List<NetworkId> networks) {
}
