package com.walletradar.api.controller;

import com.walletradar.api.dto.AddWalletRequest;
import com.walletradar.api.dto.AddWalletResponse;
import com.walletradar.api.dto.WalletStatusAllNetworksResponse;
import com.walletradar.api.dto.WalletStatusResponse;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.ingestion.wallet.command.WalletBackfillService;
import com.walletradar.ingestion.wallet.query.WalletSyncStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Wallet endpoints for backfill creation and status reads.
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletBackfillService walletBackfillService;
    private final WalletSyncStatusService walletSyncStatusService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AddWalletResponse addWallet(@RequestBody @Valid AddWalletRequest request) {
        String address = request.address().trim();
        var networks = request.networks();
        walletBackfillService.addWallet(address, networks);
        String networkLabel = (networks != null && !networks.isEmpty())
                ? networks.get(0).name()
                : "all";
        String syncId = "wallet-" + address.substring(0, Math.min(10, address.length())) + "-" + networkLabel;
        return new AddWalletResponse(syncId, "Backfill started");
    }

    @GetMapping(value = "/{address}/status", params = "network")
    public WalletStatusResponse getStatusByNetwork(
            @PathVariable String address,
            @RequestParam String network
    ) {
        String addr = normalizedAddress(address);
        if (network.isBlank()) {
            throw new ApiBadRequestException("INVALID_NETWORK", "Network is required");
        }
        SyncStatus status = walletSyncStatusService.findByWalletAddressAndNetwork(addr, network.trim())
                .orElseThrow(() -> new ApiNotFoundException("STATUS_NOT_FOUND", "Sync status not found"));
        return toSingleResponse(status);
    }

    @GetMapping("/{address}/status")
    public WalletStatusAllNetworksResponse getStatus(@PathVariable String address) {
        String addr = normalizedAddress(address);
        List<SyncStatus> list = walletSyncStatusService.findAllByWalletAddress(addr);
        if (list.isEmpty()) {
            throw new ApiNotFoundException("STATUS_NOT_FOUND", "Sync status not found");
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
        return new WalletStatusAllNetworksResponse(addr, entries);
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

    private static String normalizedAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new ApiBadRequestException("INVALID_ADDRESS", "Address is required");
        }
        return address.trim();
    }
}
