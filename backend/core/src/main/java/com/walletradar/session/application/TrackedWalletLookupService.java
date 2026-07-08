package com.walletradar.session.application;

import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read-only lookup for the installation-wide tracked wallet universe.
 */
@Service
@RequiredArgsConstructor
public class TrackedWalletLookupService {

    private final TrackedWalletRepository trackedWalletRepository;

    public boolean contains(String address) {
        String normalized = OnChainRawTransactionView.normalizeAddress(address);
        return normalized != null && trackedWalletRepository.existsById(normalized);
    }
}
