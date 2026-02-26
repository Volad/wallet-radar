package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only sync status for API (GET /wallets/{address}/status).
 */
@Service
@RequiredArgsConstructor
public class WalletSyncStatusService {

    private final SyncStatusRepository syncStatusRepository;

    public List<SyncStatus> findAllByWalletAddress(String walletAddress) {
        return syncStatusRepository.findByWalletAddress(walletAddress);
    }

    public Optional<SyncStatus> findByWalletAddressAndNetwork(String walletAddress, String networkId) {
        return syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId);
    }
}
