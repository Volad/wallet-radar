package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.port.BybitLiveBalanceReadPort;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WARN-only canary reconciling the ledger Bybit holding of each asset against
 * {@code bybit_live_balances}.
 *
 * <p>The position model collapses {@code :FUND}/{@code :UTA} onto the UID umbrella and keeps
 * {@code :EARN} scoped, so a per-sub-pool comparison would falsely flag every correctly
 * consolidated asset (ledger {@code :FUND}=0 vs a non-zero live fund balance). The real
 * conservation invariant is therefore measured at the <b>per-asset combined total</b>:
 * {@code sum(umbrella + :FUND + :EARN + :UTA)} ledger quantity vs {@code fund + earn + uta} live
 * quantity. Genuine phantoms (e.g. MNT/USDT/ETH {@code :EARN} over-accumulation) still breach the
 * combined total, while routing artefacts do not. A separate per-sub-pool basis-orphan check still
 * catches the {@code quantityDelta=0 / costBasisDelta>0} ghost signature that a quantity-only
 * total would miss.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitEarnSubPoolConservationGuard {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal BASIS_ORPHAN_TOLERANCE = new BigDecimal("1");

    /**
     * FIX C (ADR-043): the per-family quantity-conservation invariant. Both legs of every intra-Bybit
     * custody move (FUND↔UTA reallocation, EARN subscribe/redeem principal) are internal and offset,
     * so the signed sum of {@code quantityDelta} over these normalized types must net to zero per
     * {@code uid|symbol} — even for OPEN (unredeemed) positions, whose subscribe OUT + IN both fall in
     * this set. A non-zero sum means an internal leg's counterpart vanished (e.g. a queue-key
     * divergence dropped the paired inbound), which previously destroyed inventory silently. Cross-venue
     * deposits/withdrawals/bridges (EXTERNAL_TRANSFER_*, BRIDGE_*) and cross-asset staking are excluded
     * because their counterpart lives outside the family.
     */
    private static final Set<String> INTERNAL_CUSTODY_TYPES = Set.of(
            "INTERNAL_TRANSFER",
            "EARN_FLEXIBLE_SAVING",
            "LENDING_DEPOSIT",
            "LENDING_WITHDRAW"
    );

    private static final BigDecimal INTERNAL_QTY_IMBALANCE_TOLERANCE = new BigDecimal("0.01");

    private final BybitLiveBalanceReadPort bybitLiveBalanceReadPort;

    /**
     * Reconciles the freshly-computed in-memory ledger points (not yet persisted) against the live
     * Bybit balances. The points MUST be the current replay's output: querying {@code
     * asset_ledger_points} here reads the previous run's persisted state (the new points are saved
     * after the conservation sweep), which reports {@code ledgerQty=0} for every asset on a rebuild.
     */
    public void evaluate(List<AssetLedgerPoint> ledgerPoints) {
        if (ledgerPoints == null || ledgerPoints.isEmpty()) {
            return;
        }
        LedgerSnapshot snapshot = buildLedgerSnapshot(ledgerPoints);
        List<BybitLiveBalanceReadPort.Row> liveBalances = bybitLiveBalanceReadPort.findAll();
        for (BybitLiveBalanceReadPort.Row live : liveBalances) {
            if (live == null || live.integrationId() == null || live.assetSymbol() == null) {
                continue;
            }
            if (BybitLiveBalanceReadPort.EMPTY_UMBRELLA_SYMBOL.equals(live.assetSymbol())) {
                continue;
            }
            String uid = extractUidFromIntegrationId(live.integrationId());
            if (uid == null) {
                continue;
            }
            String symbol = live.assetSymbol().toUpperCase(Locale.ROOT);
            String assetKey = uid + "|" + symbol;

            BigDecimal ledgerTotal = zeroIfNull(snapshot.combinedQuantity().get(assetKey));
            BigDecimal liveTotal = sum(live.fundQty(), live.earnQty(), live.utaQty());
            warnTotalMismatch(assetKey, ledgerTotal, liveTotal);

            warnBasisOrphan(assetKey + "|FUND", snapshot.subPool().get(assetKey + "|FUND"), "FUND");
            warnBasisOrphan(assetKey + "|EARN", snapshot.subPool().get(assetKey + "|EARN"), "EARN");
        }

        // FIX C (ADR-043): per-family internal quantity conservation. Independent of the live-balance
        // reconciliation above, so a queue-key divergence that keeps live-parity by luck still surfaces.
        for (Map.Entry<String, BigDecimal> entry : snapshot.internalQtyDelta().entrySet()) {
            warnInternalQtyImbalance(entry.getKey(), entry.getValue());
        }
    }

    private LedgerSnapshot buildLedgerSnapshot(List<AssetLedgerPoint> points) {
        // Latest point per wallet+asset (drives both the combined total and the sub-pool view).
        Map<String, AssetLedgerPoint> latestByWallet = new HashMap<>();
        for (AssetLedgerPoint point : points) {
            if (point.getWalletAddress() == null || point.getAssetSymbol() == null) {
                continue;
            }
            String walletUpper = point.getWalletAddress().toUpperCase(Locale.ROOT);
            if (!walletUpper.startsWith("BYBIT:")) {
                continue;
            }
            String key = walletUpper + "|" + point.getAssetSymbol().toUpperCase(Locale.ROOT);
            latestByWallet.merge(key, point, (left, right) ->
                    left.getReplaySequence() >= right.getReplaySequence() ? left : right);
        }

        Map<String, BigDecimal> combined = new HashMap<>();
        Map<String, SubPoolTotals> subPool = new HashMap<>();
        for (AssetLedgerPoint point : latestByWallet.values()) {
            String uid = extractUidFromWallet(point.getWalletAddress());
            if (uid == null) {
                continue;
            }
            String symbol = point.getAssetSymbol().toUpperCase(Locale.ROOT);
            String assetKey = uid + "|" + symbol;
            combined.merge(assetKey, zeroIfNull(point.getQuantityAfter()), (a, b) -> a.add(b, MC));

            String walletUpper = point.getWalletAddress().toUpperCase(Locale.ROOT);
            if (walletUpper.endsWith(":FUND") || walletUpper.endsWith(":EARN")) {
                String subPoolName = walletUpper.endsWith(":FUND") ? "FUND" : "EARN";
                subPool.put(assetKey + "|" + subPoolName, new SubPoolTotals(
                        zeroIfNull(point.getQuantityAfter()),
                        zeroIfNull(point.getTotalCostBasisAfterUsd())
                ));
            }
        }

        // FIX C (ADR-043) + Issue 4 (ADR-043 amendment): sum the signed quantityDelta of every
        // intra-Bybit internal custody leg over ALL points (not just the latest per wallet). The
        // grouping key is the SESSION/MASTER (accountingUniverseId), NOT the per-sub-account UID.
        // A cross-sub internal reallocation legitimately spans two sub-UIDs of the SAME master (e.g.
        // BYBIT:33625378 ↔ BYBIT:421325298): keying by sub-UID made each side look one-sided and
        // raised a false CORRIDOR_QTY_IMBALANCE (DOGE +150.591 / −150.591 nets to 0 across subs).
        // Grouping every BYBIT:* sub-account under its accountingUniverseId lets those cross-sub
        // reallocations net out, while a genuine one-sided loss within the master still trips.
        // Fallback: when accountingUniverseId is absent (older points / unit fixtures) key by sub-UID
        // so single-sub conservation still holds.
        Map<String, BigDecimal> internalQtyDelta = new HashMap<>();
        for (AssetLedgerPoint point : points) {
            if (point.getWalletAddress() == null || point.getAssetSymbol() == null) {
                continue;
            }
            if (!point.getWalletAddress().toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
                continue;
            }
            if (point.getNormalizedType() == null
                    || !INTERNAL_CUSTODY_TYPES.contains(point.getNormalizedType().toUpperCase(Locale.ROOT))) {
                continue;
            }
            String sessionKey = internalConservationSessionKey(point);
            if (sessionKey == null) {
                continue;
            }
            String assetKey = sessionKey + "|" + point.getAssetSymbol().toUpperCase(Locale.ROOT);
            internalQtyDelta.merge(assetKey, zeroIfNull(point.getQuantityDelta()), (a, b) -> a.add(b, MC));
        }
        return new LedgerSnapshot(combined, subPool, internalQtyDelta);
    }

    private static void warnTotalMismatch(String assetKey, BigDecimal ledgerTotal, BigDecimal liveTotal) {
        BigDecimal delta = ledgerTotal.subtract(liveTotal, MC).abs();
        // Flag in BOTH directions: a phantom holding the live balance does not back, and an
        // under-materialised holding the live balance exceeds. Routing of FUND→umbrella no longer
        // produces false positives because the comparison is the per-asset combined total.
        if (delta.compareTo(TOLERANCE) > 0 && (ledgerTotal.signum() != 0 || liveTotal.signum() != 0)) {
            log.warn(
                    "BYBIT_ASSET_TOTAL_MISMATCH key={} ledgerQty={} liveQty={} delta={}",
                    assetKey,
                    ledgerTotal.setScale(8, RoundingMode.HALF_UP),
                    liveTotal.setScale(8, RoundingMode.HALF_UP),
                    delta.setScale(8, RoundingMode.HALF_UP)
            );
        }
    }

    private static void warnBasisOrphan(String key, SubPoolTotals ledger, String label) {
        if (ledger == null) {
            return;
        }
        BigDecimal ledgerQty = ledger.quantity();
        BigDecimal ledgerBasis = ledger.totalCostBasisUsd();
        // A near-zero quantity sub-pool that still carries material cost basis is the
        // quantityDelta=0 / costBasisDelta>0 ghost signature (e.g. principal basis injected with no
        // principal quantity). The combined-total check cannot see it, so keep it per sub-pool.
        if (ledgerQty.abs().compareTo(TOLERANCE) <= 0 && ledgerBasis.abs().compareTo(BASIS_ORPHAN_TOLERANCE) > 0) {
            log.warn(
                    "BYBIT_SUBPOOL_BASIS_ORPHAN key={} subPool={} ledgerQty={} ledgerBasisUsd={}",
                    key,
                    label,
                    ledgerQty.setScale(8, RoundingMode.HALF_UP),
                    ledgerBasis.setScale(2, RoundingMode.HALF_UP)
            );
        }
    }

    private static void warnInternalQtyImbalance(String assetKey, BigDecimal internalQtyDelta) {
        BigDecimal imbalance = zeroIfNull(internalQtyDelta).abs();
        if (imbalance.compareTo(INTERNAL_QTY_IMBALANCE_TOLERANCE) > 0) {
            log.warn(
                    "CORRIDOR_QTY_IMBALANCE key={} internalQtyDelta={} (Σ internal INTERNAL_TRANSFER + EARN_* Δqty ≠ 0)",
                    assetKey,
                    zeroIfNull(internalQtyDelta).setScale(8, RoundingMode.HALF_UP)
            );
        }
    }

    private static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(zeroIfNull(value), MC);
        }
        return total;
    }

    private static String extractUidFromIntegrationId(String integrationId) {
        if (integrationId == null || !integrationId.startsWith("BYBIT-")) {
            return null;
        }
        return integrationId.substring("BYBIT-".length());
    }

    /**
     * Issue 4 (ADR-043): the per-family internal-quantity conservation grouping key. Prefers the
     * SESSION/MASTER {@code accountingUniverseId} so all {@code BYBIT:*} sub-accounts of one master
     * net together (cross-sub reallocations offset). Falls back to the per-sub-account UID when the
     * universe id is absent, preserving single-sub conservation.
     */
    private static String internalConservationSessionKey(AssetLedgerPoint point) {
        String universeId = point.getAccountingUniverseId();
        if (universeId != null && !universeId.isBlank()) {
            return universeId;
        }
        return extractUidFromWallet(point.getWalletAddress());
    }

    private static String extractUidFromWallet(String wallet) {
        if (wallet == null || !wallet.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = wallet.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        return colon > 0 ? without.substring(0, colon) : without;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record LedgerSnapshot(
            Map<String, BigDecimal> combinedQuantity,
            Map<String, SubPoolTotals> subPool,
            Map<String, BigDecimal> internalQtyDelta
    ) {
    }

    private record SubPoolTotals(BigDecimal quantity, BigDecimal totalCostBasisUsd) {
    }
}
