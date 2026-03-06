package com.walletradar.api.controller;

import com.walletradar.api.dto.BalanceRefreshRequest;
import com.walletradar.api.dto.BalanceRefreshResponse;
import com.walletradar.ingestion.sync.balance.BalanceRefreshService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BalanceRefreshResponse refresh(@RequestBody @Valid BalanceRefreshRequest request) {
        List<String> wallets = request.wallets().stream()
                .map(String::trim)
                .distinct()
                .toList();
        balanceRefreshService.refreshWalletsAsync(wallets, request.networks());
        return new BalanceRefreshResponse("Balance refresh triggered");
    }
}
