package com.walletradar.application.lending.application;

import com.walletradar.application.lending.persistence.LendingLivePositionSnapshot;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshotRepository;
import com.walletradar.application.lending.spi.LiveLendingAssetAmount;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persist + read gateway for {@link LendingLivePositionSnapshot} (WS-3). The refresh services write
 * the single-authority live position; GET/read paths read the freshest snapshot (snapshot-first).
 */
@Service
@RequiredArgsConstructor
public class LendingLivePositionSnapshotService {

    /**
     * Freshness window for consuming a live position on read paths. Longer than the HF window
     * (collateral/debt move slowly), but bounded so a clearly-stale reader falls back to the guarded
     * synthesized supply rather than showing a wrong live figure indefinitely.
     */
    static final Duration FRESH_WINDOW = Duration.ofMinutes(30);

    private final LendingLivePositionSnapshotRepository repository;

    public LendingLivePositionSnapshot save(LivePositionRequest request, LiveLendingPosition position, Instant capturedAt) {
        String networkKey = normalizeNetwork(request.networkId());
        LendingLivePositionSnapshot snapshot = new LendingLivePositionSnapshot()
                .setId(snapshotId(request, networkKey, capturedAt))
                .setSessionId(request.sessionId())
                .setProtocolKey(request.protocolKey())
                .setNetworkId(networkKey)
                .setWalletAddress(request.walletAddress())
                .setHealthFactor(position.healthFactor())
                .setLiquidationThreshold(position.liquidationThreshold())
                .setLoanToValue(position.loanToValue())
                .setSource(position.source())
                .setCapturedAt(capturedAt)
                .setRawRef(position.rawRef())
                .setCollateral(legs(position.collateral()))
                .setDebt(legs(position.debt()));
        return repository.save(snapshot);
    }

    public Optional<LendingLivePositionSnapshot> latestFresh(
            String sessionId, String protocolKey, String networkId, String walletAddress) {
        if (sessionId == null || protocolKey == null || networkId == null || walletAddress == null) {
            return Optional.empty();
        }
        return repository
                .findFirstBySessionIdAndProtocolKeyAndNetworkIdAndWalletAddressAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
                        sessionId, protocolKey, normalizeNetwork(networkId), walletAddress,
                        Instant.now().minus(FRESH_WINDOW));
    }

    public Optional<LendingLivePositionSnapshot> latestFreshForWallet(String walletAddress, String networkId) {
        if (walletAddress == null || networkId == null) {
            return Optional.empty();
        }
        return repository.findFirstByWalletAddressAndNetworkIdAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
                walletAddress, normalizeNetwork(networkId), Instant.now().minus(FRESH_WINDOW));
    }

    private static List<LendingLivePositionSnapshot.Leg> legs(List<LiveLendingAssetAmount> amounts) {
        if (amounts == null) {
            return List.of();
        }
        return amounts.stream().map(amount -> new LendingLivePositionSnapshot.Leg()
                .setAssetSymbol(amount.assetSymbol())
                .setAssetContract(amount.assetContract())
                .setDecimals(amount.decimals())
                .setQuantity(amount.quantity())
                .setMarketValueUsd(amount.marketValueUsd())).toList();
    }

    private static String snapshotId(LivePositionRequest request, String networkKey, Instant capturedAt) {
        return String.join(":",
                nullToUnknown(request.sessionId()),
                nullToUnknown(request.protocolKey()),
                nullToUnknown(networkKey),
                nullToUnknown(request.walletAddress()),
                String.valueOf(capturedAt.toEpochMilli())
        ).toLowerCase(Locale.ROOT);
    }

    private static String normalizeNetwork(String networkId) {
        return networkId == null ? "" : networkId.trim().toUpperCase(Locale.ROOT);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
