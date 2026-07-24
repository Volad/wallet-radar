package com.walletradar.application.lending.application;

import com.walletradar.application.lending.application.LendingActiveMarketDiscoveryService.ActiveMarket;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshot;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Second active-market discovery source (WS-3) for <strong>receipt-less</strong> lending positions
 * (Solana Jupiter Lend / Kamino, TON), complementing the EVM receipt-token scan in
 * {@link LendingActiveMarketDiscoveryService}.
 *
 * <p>Receipt-less collateral is native (e.g. SOL, {@code contract = NATIVE:SOLANA}) and the debt is a
 * synthetic liability with no debt-token balance, so no {@code on_chain_balances} row qualifies and the
 * receipt-token scan emits nothing. The authoritative record of the CURRENT open position is instead the
 * single-authority live snapshot ({@code lending_live_position_snapshots}, ADR-071) written by the
 * background lending refresh / on-demand refresh. This source reads the freshest snapshot per
 * {@code (session, protocol, network, wallet)} group and emits one {@link ActiveMarket} per collateral
 * leg (SUPPLY) and per debt leg (BORROW).</p>
 *
 * <p>Network-agnostic: there is no per-network branch and no hardcoded wallet/token. A protocol/network
 * plugs into rate refresh purely by writing a live snapshot and having a {@code LendingMarketRateReader}
 * whose {@code supports(...)} matches — this source does not name any protocol.</p>
 */
@Component
@RequiredArgsConstructor
public class LendingReceiptLessActiveMarketSource {

    /**
     * A snapshot counts as an active market when captured within this window. It must comfortably
     * exceed the health-factor refresh cadence (10 min, {@code walletradar.lending.health-factor
     * .refresh-interval-ms}) so an OPEN position — re-snapshotted every cycle — never ages out between
     * background market-rate refreshes; a CLOSED position stops being re-snapshotted and drops out
     * within one window, mirroring how an {@code on_chain_balances} row disappears on close.
     */
    static final Duration ACTIVE_WINDOW = Duration.ofMinutes(30);

    private static final String SUPPLY = "SUPPLY";
    private static final String BORROW = "BORROW";
    private static final String MARKET_ASSET = "ACCOUNT-POOL";

    private final LendingLivePositionSnapshotRepository livePositionSnapshotRepository;

    public List<ActiveMarket> discover(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<LendingLivePositionSnapshot> recent = livePositionSnapshotRepository
                .findBySessionIdInAndCapturedAtGreaterThanEqual(sessionIds, Instant.now().minus(ACTIVE_WINDOW));
        if (recent.isEmpty()) {
            return List.of();
        }
        Map<String, LendingLivePositionSnapshot> latestByGroup = latestByGroup(recent);

        Map<String, ActiveMarket> markets = new LinkedHashMap<>();
        for (LendingLivePositionSnapshot snapshot : latestByGroup.values()) {
            emitLegs(markets, snapshot, snapshot.getCollateral(), SUPPLY);
            emitLegs(markets, snapshot, snapshot.getDebt(), BORROW);
        }
        return new ArrayList<>(markets.values());
    }

    private static Map<String, LendingLivePositionSnapshot> latestByGroup(List<LendingLivePositionSnapshot> recent) {
        Map<String, LendingLivePositionSnapshot> latest = new LinkedHashMap<>();
        recent.stream()
                .sorted(Comparator.comparing(
                        LendingLivePositionSnapshot::getCapturedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .forEach(snapshot -> latest.putIfAbsent(groupKey(snapshot), snapshot));
        return latest;
    }

    private void emitLegs(
            Map<String, ActiveMarket> markets,
            LendingLivePositionSnapshot snapshot,
            List<LendingLivePositionSnapshot.Leg> legs,
            String side
    ) {
        if (legs == null) {
            return;
        }
        String protocol = snapshot.getProtocolKey();
        String networkId = snapshot.getNetworkId();
        if (isBlank(protocol) || isBlank(networkId)) {
            return;
        }
        String marketKey = protocol + ":" + networkId + ":" + MARKET_ASSET;
        for (LendingLivePositionSnapshot.Leg leg : legs) {
            if (leg == null || leg.getQuantity() == null || leg.getQuantity().signum() <= 0) {
                continue;
            }
            String underlying = underlyingSymbol(leg.getAssetSymbol());
            if (underlying == null || underlying.isBlank()) {
                continue;
            }
            String key = String.join(":",
                    nullToEmpty(snapshot.getSessionId()),
                    protocol,
                    networkId,
                    marketKey,
                    underlying,
                    side,
                    nullToEmpty(leg.getAssetContract())
            ).toLowerCase(Locale.ROOT);
            markets.putIfAbsent(key, new ActiveMarket(
                    snapshot.getSessionId(),
                    protocol,
                    networkId,
                    snapshot.getWalletAddress(),
                    marketKey,
                    side,
                    underlying,
                    underlying,
                    leg.getAssetContract()
            ));
        }
    }

    /**
     * Normalize a leg symbol to the same canonical form the built lending position carries
     * ({@code cycleStateAsset}), so the rate snapshot's {@code underlyingSymbol} matches the position
     * lookup key exactly.
     */
    private static String underlyingSymbol(String legSymbol) {
        if (legSymbol == null || legSymbol.isBlank()) {
            return null;
        }
        return LendingAssetSymbolSupport.displaySymbol(LendingAssetSymbolSupport.lifecycleAsset(legSymbol));
    }

    private static String groupKey(LendingLivePositionSnapshot snapshot) {
        return String.join(":",
                nullToEmpty(snapshot.getSessionId()),
                nullToEmpty(snapshot.getProtocolKey()),
                nullToEmpty(snapshot.getNetworkId()),
                nullToEmpty(snapshot.getWalletAddress())
        ).toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
