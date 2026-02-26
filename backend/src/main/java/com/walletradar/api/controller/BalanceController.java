package com.walletradar.api.controller;

import com.walletradar.api.dto.BalanceRefreshRequest;
import com.walletradar.api.dto.BalanceRefreshResponse;
import com.walletradar.api.dto.ErrorBody;
import com.walletradar.ingestion.sync.balance.BalanceRefreshService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * POST /wallets/balances/refresh (T-014). Triggers immediate async balance refresh for selected wallets/networks.
 */
@RestController
@RequestMapping("/api/v1/wallets/balances")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceRefreshService balanceRefreshService;

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody @Valid BalanceRefreshRequest request) {
        if (request == null || request.wallets() == null || request.wallets().isEmpty()) {
            return ResponseEntity.badRequest().body(ErrorBody.of("INVALID_REQUEST", "wallets is required"));
        }
        List<String> wallets = request.wallets().stream()
                .filter(w -> w != null && !w.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (wallets.isEmpty()) {
            return ResponseEntity.badRequest().body(ErrorBody.of("INVALID_REQUEST", "wallets is required"));
        }
        balanceRefreshService.refreshWalletsAsync(wallets, request.networks());
        return ResponseEntity.accepted().body(new BalanceRefreshResponse("Balance refresh triggered"));
    }
}

