package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.NativePoolReconciliationProperties;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ADR-044 D4 — end-of-replay native-pool reconciliation gate.
 *
 * <p>Modeled on {@link CorridorBasisConservationGuard} / {@code BybitEarnSubPoolConservationGuard}:
 * an end-of-replay sweep over the freshly-computed (not-yet-persisted) ledger points, a shared dust
 * epsilon, an out-of-scope carve-out, and a WARN-first severity with a one-line flip to
 * {@code HARD_FAIL} (here a YAML flag, see {@link NativePoolReconciliationProperties}).
 *
 * <p>Per {@code NATIVE:<chain>} {@code accountingAssetIdentity} that is not on a non-EVM carve-out
 * (SOL/TON), it asserts:
 * <ul>
 *   <li><b>Invariant (a)</b> — the single authoritative signal: terminal
 *       {@code |quantityAfter − onChainNative| ≤ NATIVE_DUST}, where {@code onChainNative} is read
 *       zero-RPC from {@code on_chain_balances} ({@code assetContract = "NATIVE:<chain>"}). Pools with
 *       no on-chain ground truth are carved out of the balance check (skipped, never breached on
 *       null). Audit {@code docs/tasks/audit-coverage-shortfall-avco-root-cause.md} proved that the
 *       lifetime-cumulative {@code quantityShortfallAfter} / sticky {@code hasIncompleteHistoryAfter}
 *       counters do NOT feed final holdings; they are carried here as informational diagnostic fields
 *       ONLY and never drive a breach.</li>
 *   <li><b>Invariant (b)</b>: no native {@code BRIDGE_OUT}/{@code CARRY_OUT} leg with null/zero
 *       basis while the source pool held covered basis at that timestamp — restricted to legs whose
 *       carried-out quantity is materially above {@code NATIVE_DUST} so dust-only gas legs do not
 *       spam breaches.</li>
 * </ul>
 * It does not hide any number behind the UI; a breach emits a structured diagnostic (WARN) or fails
 * the replay for that universe (HARD_FAIL). It complements — does not replace — the USD-level
 * {@code PortfolioConservationGate}.
 */
@Component
@Slf4j
public class NativePoolReconciliationGate {

    /** ADR-044: per-chain native-unit dust epsilon (~$0.20–0.40 for ETH-family). */
    static final BigDecimal NATIVE_DUST = new BigDecimal("0.0001");

    /** Source pool is considered basis-covered above this USD floor (Invariant b). */
    static final BigDecimal COVERED_BASIS_EPSILON_USD = new BigDecimal("1.00");

    /** A carry-out below this USD basis is treated as a $0 (ghost) carry-out (Invariant b). */
    static final BigDecimal ZERO_BASIS_EPSILON_USD = new BigDecimal("0.01");

    private static final String NATIVE_IDENTITY_PREFIX = "NATIVE:";
    private static final String NATIVE_CONTRACT_PREFIX = "NATIVE:";
    private static final String BRIDGE_OUT_TYPE = "BRIDGE_OUT";

    /** Non-EVM natives have no wrapped-native ground truth; always carved out (Q3). */
    private static final Set<NetworkId> NON_EVM_NATIVES = EnumSet.of(NetworkId.SOLANA, NetworkId.TON);

    public enum Severity {
        WARN,
        HARD_FAIL
    }

    private final MongoOperations mongoOperations;
    private final NativePoolReconciliationProperties properties;

    public NativePoolReconciliationGate(
            MongoOperations mongoOperations,
            NativePoolReconciliationProperties properties
    ) {
        this.mongoOperations = mongoOperations;
        this.properties = properties;
    }

    /**
     * Reconciles the freshly-computed in-memory ledger points against the persisted on-chain native
     * balances. The points MUST be the current replay's output (querying {@code asset_ledger_points}
     * here reads the previous run's state).
     */
    public NativePoolReconciliationResult evaluate(List<AssetLedgerPoint> ledgerPoints) {
        return evaluate(ledgerPoints, loadOnChainNativeQuantities());
    }

    NativePoolReconciliationResult evaluate(
            List<AssetLedgerPoint> ledgerPoints,
            Map<String, BigDecimal> onChainNativeByPool
    ) {
        if (ledgerPoints == null || ledgerPoints.isEmpty()) {
            return NativePoolReconciliationResult.empty();
        }
        Map<String, AssetLedgerPoint> terminalByPool = terminalNativePointsByPool(ledgerPoints);

        List<NativePoolReconciliationResult.Breach> breaches = new ArrayList<>();
        for (AssetLedgerPoint terminal : terminalByPool.values()) {
            NetworkId networkId = terminal.getNetworkId();
            if (networkId == null || NON_EVM_NATIVES.contains(networkId)) {
                continue;
            }
            evaluateQuantityInvariants(terminal, onChainNativeByPool, breaches);
        }

        // Invariant (b): $0 native carry-out over a covered pool.
        for (AssetLedgerPoint point : ledgerPoints) {
            if (!isNativePoint(point) || point.getNetworkId() == null
                    || NON_EVM_NATIVES.contains(point.getNetworkId())) {
                continue;
            }
            if (isZeroBasisCarryOutOverCoveredPool(point)) {
                breaches.add(new NativePoolReconciliationResult.Breach(
                        point.getNetworkId(),
                        point.getWalletAddress(),
                        point.getAccountingAssetIdentity(),
                        NativePoolReconciliationResult.Kind.ZERO_BASIS_CARRY_OUT,
                        point.getQuantityDelta(),
                        null,
                        null,
                        Boolean.TRUE.equals(point.getHasIncompleteHistoryAfter()),
                        point.getTxHash()
                ));
            }
        }

        if (breaches.isEmpty()) {
            return NativePoolReconciliationResult.empty();
        }
        logBreaches(breaches);
        if (properties != null && properties.getSeverity() == Severity.HARD_FAIL) {
            throw new NativePoolReconciliationException(breaches.size());
        }
        return new NativePoolReconciliationResult(List.copyOf(breaches));
    }

    private void evaluateQuantityInvariants(
            AssetLedgerPoint terminal,
            Map<String, BigDecimal> onChainNativeByPool,
            List<NativePoolReconciliationResult.Breach> breaches
    ) {
        // Authoritative signal: terminal tracked quantity vs on-chain ground truth. The lifetime
        // shortfall / incomplete-history counters are carried as informational fields only and never
        // drive a breach (audit: they are monotonic and never feed final holdings).
        BigDecimal onChain = onChainNativeByPool.get(poolKey(terminal.getNetworkId(), terminal.getWalletAddress()));
        if (onChain == null) {
            // No on-chain ground truth for this pool → carve out of the balance check (skip, never
            // breach on null). Balances are captured during PORTFOLIO_SNAPSHOT_REFRESH after replay,
            // so a fresh post-reset replay legitimately sees no ground truth here.
            return;
        }
        BigDecimal tracked = zeroIfNull(terminal.getQuantityAfter());
        if (tracked.subtract(onChain).abs().compareTo(NATIVE_DUST) > 0) {
            breaches.add(breach(terminal,
                    NativePoolReconciliationResult.Kind.ON_CHAIN_BALANCE_MISMATCH, onChain));
        }
    }

    private static NativePoolReconciliationResult.Breach breach(
            AssetLedgerPoint terminal,
            NativePoolReconciliationResult.Kind kind,
            BigDecimal onChain
    ) {
        return new NativePoolReconciliationResult.Breach(
                terminal.getNetworkId(),
                terminal.getWalletAddress(),
                terminal.getAccountingAssetIdentity(),
                kind,
                terminal.getQuantityAfter(),
                onChain,
                abs(terminal.getQuantityShortfallAfter()),
                Boolean.TRUE.equals(terminal.getHasIncompleteHistoryAfter()),
                terminal.getTxHash()
        );
    }

    private static boolean isZeroBasisCarryOutOverCoveredPool(AssetLedgerPoint point) {
        boolean carryOut = point.getBasisEffect() == AssetLedgerPoint.BasisEffect.CARRY_OUT
                || BRIDGE_OUT_TYPE.equalsIgnoreCase(point.getNormalizedType());
        if (!carryOut) {
            return false;
        }
        if (point.getQuantityDelta() == null || point.getQuantityDelta().signum() >= 0) {
            return false;
        }
        // Suppress dust-only gas legs (trackedQty ~1e-7): only flag a materially-sized carry-out.
        if (abs(point.getQuantityDelta()).compareTo(NATIVE_DUST) <= 0) {
            return false;
        }
        BigDecimal basis = abs(point.getCostBasisDeltaUsd());
        boolean zeroBasis = point.getCostBasisDeltaUsd() == null
                || basis.compareTo(ZERO_BASIS_EPSILON_USD) <= 0;
        if (!zeroBasis) {
            return false;
        }
        BigDecimal poolBasisBefore = abs(point.getTotalCostBasisBeforeUsd());
        BigDecimal quantityBefore = zeroIfNull(point.getQuantityBefore());
        return poolBasisBefore.compareTo(COVERED_BASIS_EPSILON_USD) > 0 && quantityBefore.signum() > 0;
    }

    private Map<String, AssetLedgerPoint> terminalNativePointsByPool(List<AssetLedgerPoint> ledgerPoints) {
        Map<String, AssetLedgerPoint> terminalByPool = new HashMap<>();
        for (AssetLedgerPoint point : ledgerPoints) {
            if (!isNativePoint(point) || point.getNetworkId() == null || point.getWalletAddress() == null) {
                continue;
            }
            String key = poolKey(point.getNetworkId(), point.getWalletAddress())
                    + "|" + point.getAccountingAssetIdentity();
            terminalByPool.merge(key, point, NativePoolReconciliationGate::laterPoint);
        }
        return terminalByPool;
    }

    private static AssetLedgerPoint laterPoint(AssetLedgerPoint left, AssetLedgerPoint right) {
        long leftSeq = left.getReplaySequence() == null ? Long.MIN_VALUE : left.getReplaySequence();
        long rightSeq = right.getReplaySequence() == null ? Long.MIN_VALUE : right.getReplaySequence();
        return leftSeq >= rightSeq ? left : right;
    }

    private static boolean isNativePoint(AssetLedgerPoint point) {
        return point != null
                && point.getAccountingAssetIdentity() != null
                && point.getAccountingAssetIdentity().toUpperCase(Locale.ROOT).startsWith(NATIVE_IDENTITY_PREFIX);
    }

    private Map<String, BigDecimal> loadOnChainNativeQuantities() {
        Map<String, BigDecimal> byPool = new HashMap<>();
        Map<String, java.time.Instant> capturedAtByPool = new HashMap<>();
        Query query = new Query(Criteria.where("assetContract").regex("^" + NATIVE_CONTRACT_PREFIX));
        query.fields()
                .include("networkId")
                .include("walletAddress")
                .include("quantity")
                .include("capturedAt");
        for (OnChainBalance balance : mongoOperations.find(query, OnChainBalance.class)) {
            if (balance == null || balance.getNetworkId() == null
                    || balance.getWalletAddress() == null || balance.getQuantity() == null) {
                continue;
            }
            if (NON_EVM_NATIVES.contains(balance.getNetworkId())) {
                continue;
            }
            String key = poolKey(balance.getNetworkId(), balance.getWalletAddress());
            java.time.Instant capturedAt = balance.getCapturedAt();
            java.time.Instant existing = capturedAtByPool.get(key);
            if (existing == null || (capturedAt != null && capturedAt.isAfter(existing))) {
                byPool.put(key, balance.getQuantity());
                capturedAtByPool.put(key, capturedAt);
            }
        }
        return byPool;
    }

    private void logBreaches(List<NativePoolReconciliationResult.Breach> breaches) {
        Severity severity = properties == null ? Severity.WARN : properties.getSeverity();
        for (NativePoolReconciliationResult.Breach breach : breaches) {
            log.warn(
                    "NATIVE_POOL_RECONCILIATION_BREACH severity={} kind={} network={} wallet={} asset={} "
                            + "trackedQty={} onChainQty={} info.shortfallQty={} info.incompleteHistory={} "
                            + "firstOffendingTx={}",
                    severity,
                    breach.kind(),
                    breach.networkId(),
                    breach.walletAddress(),
                    breach.accountingAssetIdentity(),
                    breach.trackedQuantity(),
                    breach.onChainQuantity(),
                    breach.shortfallQuantity(),
                    breach.hasIncompleteHistory(),
                    breach.firstOffendingTxHash()
            );
        }
        log.warn("NATIVE_POOL_RECONCILIATION_SUMMARY severity={} breaches={}", severity, breaches.size());
    }

    private static String poolKey(NetworkId networkId, String walletAddress) {
        String wallet = walletAddress == null ? "" : walletAddress.trim().toLowerCase(Locale.ROOT);
        return networkId.name() + "|" + wallet;
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
