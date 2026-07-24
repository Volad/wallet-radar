package com.walletradar.application.lending.application;

import com.walletradar.application.lending.persistence.LendingHealthFactorSnapshot;
import com.walletradar.application.lending.spi.LendingLivePositionReader;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import com.walletradar.application.lending.view.LendingGroupView;
import com.walletradar.application.lending.view.SessionLendingView;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Refreshes live lending positions across every registered {@link LendingLivePositionReader},
 * dispatching per active borrow group by {@link LendingLivePositionReader#supports(String, String)}
 * (WS-3). The hardcoded {@code AAVE_PROTOCOL_KEY} filter was removed so any protocol/network reader
 * (Aave EVM, Jupiter Lend Solana, …) plugs in.
 *
 * <p>For each reachable position it persists: (1) a live health-factor snapshot
 * ({@code source=LIVE_PROTOCOL}, incl. liquidation threshold + LTV); (2) a single-authority live
 * position snapshot (collateral/debt) consumed by the dashboard balance contribution and lending
 * cycle builder; and (3) a live borrow-liability true-up (WS-4, SET/override) so receipt-less debt
 * reflects the outstanding on-chain amount incl. accrued interest.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LendingHealthFactorRefreshService {

    private final UserSessionRepository userSessionRepository;
    private final SessionLendingQueryService lendingQueryService;
    private final List<LendingLivePositionReader> positionReaders;
    private final LendingHealthFactorSnapshotService snapshotService;
    private final LendingLivePositionSnapshotService livePositionSnapshotService;
    private final LendingLiabilityLiveTrueUpService liabilityTrueUpService;
    private final AccountingUniverseService accountingUniverseService;

    public RefreshResult refreshActiveBorrowGroups() {
        int active = 0;
        int saved = 0;
        int skipped = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (UserSession session : userSessionRepository.findAll()) {
            Optional<SessionLendingView> lending = lendingView(session.getId());
            if (lending.isEmpty()) {
                continue;
            }
            String universeId = accountingUniverseService.resolveScope(session).accountingUniverseId();
            for (LendingGroupView group : lending.get().groups()) {
                if (!"OPEN".equals(group.status())
                        || group.borrowUsd() == null || group.borrowUsd().signum() <= 0
                        || group.walletAddress() == null || group.walletAddress().isBlank()) {
                    continue;
                }
                String networkId = group.networkId() == null ? "" : group.networkId().trim().toUpperCase(Locale.ROOT);
                LivePositionRequest request = new LivePositionRequest(
                        session.getId(), group.protocol(), networkId, group.walletAddress());
                String key = String.join(":", request.sessionId(), nullSafe(request.protocolKey()),
                        request.networkId(), request.walletAddress()).toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                active++;
                if (refreshGroup(request, universeId)) {
                    saved++;
                } else {
                    skipped++;
                }
            }
        }
        log.info("Lending live position refresh complete activeGroups={} saved={} skipped={}", active, saved, skipped);
        return new RefreshResult(active, saved, skipped);
    }

    private boolean refreshGroup(LivePositionRequest request, String universeId) {
        Optional<LiveLendingPosition> position = refreshPositionSnapshots(request);
        if (position.isEmpty()) {
            return false;
        }
        liabilityTrueUpService.trueUp(universeId, request, position.get());
        return true;
    }

    /**
     * Reader dispatch + snapshot persistence shared with the on-demand refresh path: finds the reader
     * supporting the group, reads the live position, and persists the HF + live-position snapshots.
     * Does NOT apply the borrow-liability true-up (that stays background-only via
     * {@link #refreshActiveBorrowGroups()}). Returns the live position when one was read.
     */
    public Optional<LiveLendingPosition> refreshPositionSnapshots(LivePositionRequest request) {
        LendingLivePositionReader reader = positionReaders.stream()
                .filter(candidate -> candidate.supports(request.protocolKey(), request.networkId()))
                .findFirst()
                .orElse(null);
        if (reader == null) {
            return Optional.empty();
        }
        Optional<LiveLendingPosition> position = reader.read(request);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        Instant capturedAt = Instant.now();
        livePositionSnapshotService.save(request, position.get(), capturedAt);
        if (position.get().healthFactor() != null) {
            snapshotService.save(healthSnapshot(request, position.get(), capturedAt));
        }
        return position;
    }

    private LendingHealthFactorSnapshot healthSnapshot(
            LivePositionRequest request, LiveLendingPosition position, Instant capturedAt) {
        return new LendingHealthFactorSnapshot()
                .setId(snapshotId(request, capturedAt))
                .setSessionId(request.sessionId())
                .setProtocolKey(request.protocolKey())
                .setNetworkId(request.networkId())
                .setWalletAddress(request.walletAddress())
                .setHealthFactor(position.healthFactor())
                .setLiquidationThreshold(position.liquidationThreshold())
                .setLoanToValue(position.loanToValue())
                .setSource(position.source() == null ? LendingHealthFactorSnapshotService.LIVE_PROTOCOL : position.source())
                .setCapturedAt(capturedAt)
                .setBlockNumber(position.blockNumber())
                .setRawSnapshotRef(position.rawRef());
    }

    private static String snapshotId(LivePositionRequest request, Instant capturedAt) {
        return String.join(":",
                nullSafe(request.sessionId()),
                nullSafe(request.protocolKey()),
                nullSafe(request.networkId()),
                nullSafe(request.walletAddress()),
                String.valueOf(capturedAt.toEpochMilli())
        ).toLowerCase(Locale.ROOT);
    }

    private Optional<SessionLendingView> lendingView(String sessionId) {
        return lendingQueryService.findSessionLending(sessionId);
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record RefreshResult(int activeGroups, int saved, int skipped) {
    }
}
