package com.walletradar.api.controller;

import com.walletradar.api.dto.AddWalletRequest;
import com.walletradar.api.dto.AddWalletResponse;
import com.walletradar.api.dto.WalletStatusAllNetworksResponse;
import com.walletradar.api.dto.WalletStatusResponse;
import com.walletradar.api.validation.AddressValidator;
import com.walletradar.domain.SyncStatus;
import com.walletradar.ingestion.job.WalletBackfillService;
import com.walletradar.ingestion.job.WalletSyncStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * POST /wallets, GET /wallets/{address}/status (T-023).
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final AddressValidator addressValidator;
    private final WalletBackfillService walletBackfillService;
    private final WalletSyncStatusService walletSyncStatusService;

    @PostMapping
    public ResponseEntity<?> addWallet(@RequestBody AddWalletRequest request) {
        if (request == null || request.address() == null) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_ADDRESS", "Address is required"));
        }
        String address = request.address().trim();
        if (!addressValidator.isValidAddress(address)) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_ADDRESS", "Invalid wallet address"));
        }
        if (request.networks() == null || request.networks().isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_NETWORK", "At least one network is required"));
        }
        if (!addressValidator.areValidNetworks(request.networks())) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_NETWORK", "Unsupported or invalid network"));
        }
        walletBackfillService.addWallet(address, request.networks());
        String syncId = "wallet-" + address.substring(0, Math.min(10, address.length())) + "-" + request.networks().get(0).name();
        return ResponseEntity.accepted().body(new AddWalletResponse(syncId, "Backfill started"));
    }

    @GetMapping("/{address}/status")
    public ResponseEntity<?> getStatus(@PathVariable String address, @RequestParam(required = false) String network) {
        if (address == null || address.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_ADDRESS", "Address is required"));
        }
        String addr = address.trim();
        if (network != null && !network.isBlank()) {
            return walletSyncStatusService.findByWalletAddressAndNetwork(addr, network.trim())
                    .map(s -> ResponseEntity.ok(toSingleResponse(s)))
                    .orElse(ResponseEntity.notFound().build());
        }
        List<SyncStatus> list = walletSyncStatusService.findAllByWalletAddress(addr);
        if (list.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<WalletStatusAllNetworksResponse.NetworkStatusEntry> entries = list.stream()
                .map(s -> new WalletStatusAllNetworksResponse.NetworkStatusEntry(
                        s.getNetworkId(),
                        s.getStatus() != null ? s.getStatus().name() : null,
                        s.getProgressPct(),
                        s.getLastBlockSynced(),
                        s.isBackfillComplete(),
                        s.getSyncBannerMessage()))
                .toList();
        return ResponseEntity.ok(new WalletStatusAllNetworksResponse(addr, entries));
    }

    private static WalletStatusResponse toSingleResponse(SyncStatus s) {
        return new WalletStatusResponse(
                s.getWalletAddress(),
                s.getNetworkId(),
                s.getStatus() != null ? s.getStatus().name() : null,
                s.getProgressPct(),
                s.getLastBlockSynced(),
                s.isBackfillComplete(),
                s.getSyncBannerMessage());
    }

    public record ErrorBody(String code, String message) {}
}
