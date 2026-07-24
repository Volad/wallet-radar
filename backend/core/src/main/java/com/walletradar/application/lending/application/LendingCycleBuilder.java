package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.support.WalletAddressReadScope;
import com.walletradar.application.lending.persistence.LendingGroupRefreshState;
import com.walletradar.application.lending.persistence.LendingGroupRefreshStateRepository;
import com.walletradar.application.lending.persistence.LendingHealthFactorSnapshot;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshot;
import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.lending.view.*;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
class LendingCycleBuilder {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal CYCLE_DUST_TOLERANCE = new BigDecimal("0.0001");
    // Marker suffix for BORROW positions reconstructed from cycle accounting (no live debt-token
    // balance on chain). Used to bubble the synthesized debt up into group borrowUsd/positions.
    private static final String SYNTHETIC_BORROW_ID_SUFFIX = ":synthetic-borrow";
    // Marker suffix for SUPPLY (collateral) positions reconstructed from cycle accounting on
    // receipt-less networks (Solana/TON) that expose no on-chain supply-receipt-token balance.
    // Jupiter Lend / Kamino loops deposit SOL as collateral without minting a fungible receipt we
    // can snapshot, so the collateral (and therefore health factor + net exposure) was invisible.
    private static final String SYNTHETIC_SUPPLY_ID_SUFFIX = ":synthetic-supply";
    private static final String ACCOUNTING_ESTIMATE_SOURCE = "ACCOUNTING_ESTIMATE";
    private static final Duration LOOP_GROUP_WINDOW = Duration.ofHours(24);
    private static final Duration HISTORICAL_STABLE_PRICE_WINDOW = Duration.ofHours(36);
    private static final Duration HISTORICAL_VOLATILE_PRICE_WINDOW = Duration.ofMinutes(5);
    private static final Duration HISTORICAL_WRAPPER_PRICE_WINDOW = Duration.ofDays(90);
    private static final EnumSet<NormalizedTransactionType> LENDING_TYPES = EnumSet.of(
            NormalizedTransactionType.LENDING_DEPOSIT,
            NormalizedTransactionType.LENDING_WITHDRAW,
            NormalizedTransactionType.LENDING_LOOP_OPEN,
            NormalizedTransactionType.LENDING_LOOP_REBALANCE,
            NormalizedTransactionType.LENDING_LOOP_DECREASE,
            NormalizedTransactionType.LENDING_LOOP_CLOSE,
            NormalizedTransactionType.BORROW,
            NormalizedTransactionType.REPAY
    );

    private final LendingMarketMetricEstimator metricEstimator;
    private final LendingMarketRateSnapshotService marketRateSnapshotService;
    private final LendingHealthFactorSnapshotService healthFactorSnapshotService;
    private final LendingLivePositionSnapshotService livePositionSnapshotService;
    private final LendingGroupRefreshStateRepository lendingGroupRefreshStateRepository;
    private final LendingMarketKeyResolver marketKeyResolver;
    private final LendingReceiptIdentityService receiptIdentityService;

    List<LendingGroupView> buildGroupViews(
            String sessionId,
            List<NormalizedTransaction> history,
            List<NormalizedTransaction> cashExitSwaps,
            List<AssetLedgerPoint> ledgerPoints,
            List<OnChainBalance> balances,
            Map<String, BigDecimal> prices,
            Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
    ) {
        Map<BucketKey, AssetLedgerPoint> latestLendingPointByBucket = latestLendingPointByBucket(ledgerPoints);
        Map<GroupKey, GroupAccumulator> groups = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : history) {
            receiptIdentityService.indexTransaction(transaction);
            GroupKey key = groupKey(transaction);
            groups.computeIfAbsent(key, groupKey -> new GroupAccumulator(sessionId, groupKey))
                    .addHistory(transaction, prices, historicalPrices, marketKey(transaction));
        }
        groups.values().forEach(group -> group.addLinkedCashExitSwaps(cashExitSwaps));

        for (OnChainBalance balance : balances) {
            BigDecimal quantity = zeroIfNull(balance.getQuantity());
            if (quantity.signum() <= 0) {
                continue;
            }
            if (!isLendingPositionSymbol(balance.getNetworkId(), balance.getAssetContract(), balance.getAssetSymbol())) {
                continue;
            }
            if (isLiveDebtTokenBalance(balance)) {
                Optional<LendingReceiptIdentity> debtIdentity = receiptIdentityService.resolve(
                        balance.getNetworkId(),
                        balance.getAssetContract(),
                        balance.getAssetSymbol()
                );
                String protocol = debtIdentity.map(LendingReceiptIdentity::protocol)
                        .orElseGet(() -> LendingProtocolNameSupport.protocolFromAssetSymbol(balance.getAssetSymbol()).orElse("Unknown"));
                GroupKey key = new GroupKey(
                        LendingProtocolNameSupport.displayProtocol(protocol, balance.getAssetSymbol()),
                        balance.getNetworkId(),
                        normalizeAddress(balance.getWalletAddress())
                );
                String marketAsset = marketKeyResolver.marketAssetFromBalance(protocol, balance.getNetworkId(), balance);
                groups.computeIfAbsent(key, groupKey -> new GroupAccumulator(sessionId, groupKey))
                        .addLiveDebtPosition(
                                balance,
                                prices,
                                metricEstimator,
                                marketKey(protocol, balance.getNetworkId(), marketAsset)
                        );
                continue;
            }
            BucketKey bucketKey = new BucketKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    AccountingAssetIdentitySupport.positionAssetIdentity(
                            balance.getNetworkId(),
                            balance.getAssetSymbol(),
                            balance.getAssetContract()
                    )
            );
            AssetLedgerPoint latestPoint = latestLendingPointByBucket.get(bucketKey);
            if (latestPoint == null) {
                continue;
            }
            GroupKey key = new GroupKey(
                    LendingProtocolNameSupport.displayProtocol(
                            latestPoint.getProtocolName(),
                            latestPoint.getAssetSymbol()
                    ),
                    balance.getNetworkId(),
                    normalizeAddress(balance.getWalletAddress())
            );
            groups.computeIfAbsent(key, groupKey -> new GroupAccumulator(sessionId, groupKey)).addPosition(
                    balance,
                    latestPoint,
                    prices,
                    metricEstimator,
                    marketKey(key.protocol(), balance.getNetworkId(), marketKeyResolver.marketAssetFromBalance(
                            key.protocol(),
                            balance.getNetworkId(),
                            balance
                    ))
            );
        }

        return groups.values().stream()
                .map(accumulator -> accumulator.toView(metricEstimator))
                .filter(group -> !group.history().isEmpty() || !group.positions().isEmpty())
                .sorted(Comparator.comparing(LendingGroupView::status).reversed()
                        .thenComparing(LendingGroupView::protocol)
                        .thenComparing(LendingGroupView::networkId))
                .toList();
    }

    private Map<BucketKey, AssetLedgerPoint> latestLendingPointByBucket(List<AssetLedgerPoint> points) {
        Map<BucketKey, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            if (point.getBasisEffect() == AssetLedgerPoint.BasisEffect.GAS_ONLY) {
                continue;
            }
            BucketKey key = new BucketKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingAssetIdentity()
            );
            latest.put(key, point);
        }
        return latest;
    }


    private BigDecimal resolvePrice(
            Map<String, BigDecimal> prices,
            Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
            String assetSymbol,
            Instant timestamp
    ) {
        String underlying = LendingAssetSymbolSupport.underlyingSymbol(assetSymbol);
        BigDecimal historical = resolveHistoricalPrice(historicalPrices, underlying, timestamp);
        if (historical == null) {
            historical = resolveHistoricalPrice(
                    historicalPrices,
                    CanonicalAssetCatalog.canonicalMarketSymbol(underlying),
                    timestamp
            );
        }
        if (historical != null) {
            return historical;
        }
        BigDecimal direct = prices.get(LendingAssetSymbolSupport.normalizeSymbol(underlying));
        if (direct != null) {
            return direct;
        }
        BigDecimal canonical = prices.get(CanonicalAssetCatalog.canonicalMarketSymbol(underlying));
        return canonical == null ? BigDecimal.ZERO : canonical;
    }

    private BigDecimal resolvePrice(Map<String, BigDecimal> prices, String assetSymbol) {
        return resolvePrice(prices, Map.of(), assetSymbol, null);
    }

    private BigDecimal resolveHistoricalPrice(
            Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
            String assetSymbol,
            Instant timestamp
    ) {
        if (timestamp == null || historicalPrices.isEmpty()) {
            return null;
        }
        String symbol = LendingAssetSymbolSupport.normalizeSymbol(assetSymbol);
        NavigableMap<Instant, BigDecimal> byTime = historicalPrices.get(symbol);
        if (byTime == null || byTime.isEmpty()) {
            return null;
        }
        Instant bucket = timestamp.truncatedTo(ChronoUnit.MINUTES);
        Map.Entry<Instant, BigDecimal> floor = byTime.floorEntry(bucket);
        Map.Entry<Instant, BigDecimal> ceiling = byTime.ceilingEntry(bucket);
        Map.Entry<Instant, BigDecimal> nearest = nearestPrice(bucket, floor, ceiling);
        if (nearest == null) {
            return null;
        }
        Duration maxAge = isWrapperMarketPricedSymbol(symbol)
                ? HISTORICAL_WRAPPER_PRICE_WINDOW
                : LendingAssetSymbolSupport.isStable(symbol)
                ? HISTORICAL_STABLE_PRICE_WINDOW
                : HISTORICAL_VOLATILE_PRICE_WINDOW;
        Duration age = Duration.between(nearest.getKey(), bucket).abs();
        return age.compareTo(maxAge) <= 0 ? nearest.getValue() : null;
    }

    private boolean isWrapperMarketPricedSymbol(String symbol) {
        String normalized = LendingAssetSymbolSupport.normalizeSymbol(symbol);
        return normalized.equals("WSTETH") || normalized.equals("WSTUSR");
    }

    private Map.Entry<Instant, BigDecimal> nearestPrice(
            Instant target,
            Map.Entry<Instant, BigDecimal> left,
            Map.Entry<Instant, BigDecimal> right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        Duration leftDistance = Duration.between(left.getKey(), target).abs();
        Duration rightDistance = Duration.between(right.getKey(), target).abs();
        return leftDistance.compareTo(rightDistance) <= 0 ? left : right;
    }

    private GroupKey groupKey(NormalizedTransaction transaction) {
        return new GroupKey(
                LendingProtocolNameSupport.resolveProtocol(transaction),
                transaction.getNetworkId(),
                normalizeAddress(transaction.getWalletAddress())
        );
    }

    private String marketKey(NormalizedTransaction transaction) {
        String protocol = LendingProtocolNameSupport.resolveProtocol(transaction);
        return marketKey(protocol, transaction.getNetworkId(), marketAsset(transaction, protocol));
    }

    private String marketKey(String protocol, NetworkId networkId, String assetSymbol) {
        String protocolPart = protocol == null || protocol.isBlank() ? "Unknown" : protocol.trim();
        String networkPart = networkId == null ? "UNKNOWN" : networkId.name();
        String assetPart = assetSymbol == null || assetSymbol.isBlank()
                ? "account-pool"
                : LendingAssetSymbolSupport.displaySymbol(assetSymbol);
        return protocolPart + ":" + networkPart + ":" + assetPart;
    }

    private String marketAsset(NormalizedTransaction transaction, String protocol) {
        return marketKeyResolver.marketAssetFromTransaction(transaction, protocol);
    }

    private boolean isLiveDebtTokenBalance(OnChainBalance balance) {
        if (balance == null || balance.getAssetSymbol() == null) {
            return false;
        }
        if (LendingAssetSymbolSupport.isBorrowSymbol(balance.getAssetSymbol())) {
            return true;
        }
        return receiptIdentityService.resolve(
                        balance.getNetworkId(),
                        balance.getAssetContract(),
                        balance.getAssetSymbol()
                )
                .map(identity -> "BORROW".equals(identity.side()))
                .orElse(false);
    }

    private static boolean isBalanceSnapshotStale(Instant capturedAt) {
        if (capturedAt == null) {
            return true;
        }
        return Duration.between(capturedAt, Instant.now()).compareTo(Duration.ofHours(24)) > 0;
    }

    boolean isLendingHistoryRow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        if (LENDING_TYPES.contains(transaction.getType())
                && !com.walletradar.application.normalization.pipeline.classification.support.PricingReadinessSupport.hasNonFeeMovement(
                safeFlows(transaction))) {
            return false;
        }
        if (LENDING_TYPES.contains(transaction.getType())) {
            return true;
        }
        if (transaction.getType() == NormalizedTransactionType.REWARD_CLAIM) {
            return LendingProtocolNameSupport.isKnownLendingProtocol(LendingProtocolNameSupport.resolveProtocol(transaction));
        }
        if (transaction.getType() == NormalizedTransactionType.VAULT_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW) {
            if (LendingProtocolNameSupport.isKnownLendingProtocol(LendingProtocolNameSupport.resolveProtocol(transaction))) {
                return true;
            }
            return safeFlows(transaction).stream()
                    .map(NormalizedTransaction.Flow::getAssetSymbol)
                    .anyMatch(LendingAssetSymbolSupport::isLendingPositionSymbol);
        }
        return false;
    }

    private static String normalizeAddress(String address) {
        // Family-aware: EVM/CEX lowercased (legacy), Solana case-preserved, TON preferred member ref.
        // Blind lowercasing corrupted base58 Solana/TON addresses; keeping them intact keeps lending
        // group ids stable and aligned with the dashboard / move-basis read scopes.
        return WalletAddressReadScope.normalize(address);
    }

    /**
     * True when any event's lending collateral is receipt-less — the normalized-row capability flag
     * ({@link NormalizedTransaction#getReceiptBearingCollateral()}) is stamped {@code false} for
     * networks whose lending protocols expose no fungible receipt token that we snapshot as an
     * {@code on_chain_balance} (Solana, TON). For such rows a live lending position cannot be inferred
     * from {@code marketHasOpenPosition}, so the cycle open/close state must be derived from the
     * accounting {@link CycleState} instead. WS-8: reads the stamped capability rather than
     * re-deriving {@code NetworkAddressFormat.isEvm(networkId)} on the consumption plane (ADR-073).
     */
    private static boolean isReceiptLessLendingNetwork(List<LendingHistoryEntryView> events) {
        return events.stream()
                .anyMatch(event -> Boolean.FALSE.equals(event.receiptBearingCollateral()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static List<NormalizedTransaction.Flow> safeFlows(NormalizedTransaction transaction) {
        return transaction.getFlows() == null ? List.of() : transaction.getFlows();
    }


    private record GroupKey(String protocol, NetworkId networkId, String walletAddress) {
        private String id() {
            return (protocol + ":" + networkId + ":" + walletAddress).toLowerCase(Locale.ROOT);
        }
    }

    private record BucketKey(String walletAddress, NetworkId networkId, String accountingAssetIdentity) {
    }

    private record HealthMetricSnapshot(
            BigDecimal healthFactor,
            String healthLabel,
            BigDecimal healthProgress,
            String status,
            String source,
            Boolean stale,
            Instant lastRefreshedAt
    ) {
    }

    private record PositionRateMetric(
            BigDecimal aliasApyPct,
            String metricStatus,
            String metricSource,
            BigDecimal protocolSupplyApyPct,
            BigDecimal protocolBorrowApyPct,
            BigDecimal rewardAprPct,
            BigDecimal netProtocolApyPct,
            String protocolApyStatus,
            String protocolApySource,
            Instant protocolApyCapturedAt,
            Boolean protocolApyStale,
            String rewardAprStatus,
            String rewardAprUnavailableReason,
            String apyConvention
    ) {
    }

    private final class GroupAccumulator {
        private final String sessionId;
        private final GroupKey key;
        private final List<LendingPositionView> positions = new ArrayList<>();
        private final List<LendingHistoryEntryView> history = new ArrayList<>();
        private BigDecimal supplyUsd = BigDecimal.ZERO;
        private BigDecimal borrowUsd = BigDecimal.ZERO;
        private BigDecimal closedEarnedUsd = BigDecimal.ZERO;
        // Current prices captured from history ingestion, reused when valuing accounting-derived
        // (synthesized) collateral on receipt-less networks at live market value.
        private Map<String, BigDecimal> currentPrices = Map.of();

        private GroupAccumulator(String sessionId, GroupKey key) {
            this.sessionId = sessionId;
            this.key = key;
        }

        private void addPosition(
                OnChainBalance balance,
                AssetLedgerPoint latestPoint,
                Map<String, BigDecimal> prices,
                LendingMarketMetricEstimator estimator,
                String marketKey
        ) {
            String side = isBorrowPosition(balance, latestPoint) ? "BORROW" : "SUPPLY";
            BigDecimal quantity = zeroIfNull(balance.getQuantity());
            String underlyingSymbol = resolvedUnderlying(
                    balance.getNetworkId(),
                    balance.getAssetContract(),
                    balance.getAssetSymbol()
            );
            BigDecimal covered = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(quantity);
            if (covered.signum() <= 0) {
                covered = estimateCoveredQuantity(marketKey, balance.getAssetSymbol()).min(quantity);
            }
            BigDecimal price = resolvePrice(prices, "BORROW".equals(side)
                    ? underlyingSymbol
                    : balance.getAssetSymbol());
            BigDecimal valueUsd = quantity.multiply(price, MC);
            BigDecimal earnedUsd = "SUPPLY".equals(side)
                    ? quantity.subtract(covered, MC).max(BigDecimal.ZERO).multiply(price, MC)
                    : BigDecimal.ZERO;
            if ("BORROW".equals(side)) {
                borrowUsd = borrowUsd.add(valueUsd, MC);
            } else {
                supplyUsd = supplyUsd.add(valueUsd, MC);
            }
            LendingMarketMetricEstimator.MetricSnapshot metric = estimator.estimate(
                    key.protocol(),
                    side,
                    underlyingSymbol,
                    supplyUsd,
                    borrowUsd
            );
            PositionRateMetric rateMetric = positionRateMetric(
                    key.protocol(),
                    key.networkId(),
                    marketKey,
                    side,
                    underlyingSymbol,
                    metric
            );
            positions.add(new LendingPositionView(
                    key.id() + ":" + side.toLowerCase(Locale.ROOT) + ":" + balance.getAssetContract(),
                    marketKey,
                    side,
                    LendingAssetSymbolSupport.displaySymbol(balance.getAssetSymbol()),
                    underlyingSymbol,
                    balance.getAssetContract(),
                    quantity,
                    covered,
                    valueUsd,
                    earnedUsd,
                    rateMetric.aliasApyPct(),
                    rateMetric.metricStatus(),
                    rateMetric.metricSource(),
                    rateMetric.protocolSupplyApyPct(),
                    rateMetric.protocolBorrowApyPct(),
                    rateMetric.rewardAprPct(),
                    rateMetric.netProtocolApyPct(),
                    rateMetric.protocolApyStatus(),
                    rateMetric.protocolApySource(),
                    rateMetric.protocolApyCapturedAt(),
                    rateMetric.protocolApyStale(),
                    rateMetric.rewardAprStatus(),
                    rateMetric.rewardAprUnavailableReason(),
                    rateMetric.apyConvention()
            ));
        }

        private void addLiveDebtPosition(
                OnChainBalance balance,
                Map<String, BigDecimal> prices,
                LendingMarketMetricEstimator estimator,
                String marketKey
        ) {
            BigDecimal quantity = zeroIfNull(balance.getQuantity());
            String underlyingSymbol = resolvedUnderlying(
                    balance.getNetworkId(),
                    balance.getAssetContract(),
                    balance.getAssetSymbol()
            );
            BigDecimal price = resolvePrice(prices, underlyingSymbol);
            BigDecimal valueUsd = quantity.multiply(price, MC);
            if (valueUsd.signum() == 0 && LendingAssetSymbolSupport.isStable(underlyingSymbol)) {
                valueUsd = quantity;
            }
            borrowUsd = borrowUsd.add(valueUsd, MC);
            boolean stale = isBalanceSnapshotStale(balance.getCapturedAt());
            LendingMarketMetricEstimator.MetricSnapshot metric = estimator.estimate(
                    key.protocol(),
                    "BORROW",
                    underlyingSymbol,
                    supplyUsd,
                    borrowUsd
            );
            PositionRateMetric rateMetric = positionRateMetric(
                    key.protocol(),
                    key.networkId(),
                    marketKey,
                    "BORROW",
                    underlyingSymbol,
                    metric
            );
            if (stale && rateMetric.protocolApyStale() != null && !rateMetric.protocolApyStale()) {
                rateMetric = new PositionRateMetric(
                        rateMetric.aliasApyPct(),
                        rateMetric.metricStatus(),
                        rateMetric.metricSource(),
                        rateMetric.protocolSupplyApyPct(),
                        rateMetric.protocolBorrowApyPct(),
                        rateMetric.rewardAprPct(),
                        rateMetric.netProtocolApyPct(),
                        rateMetric.protocolApyStatus(),
                        rateMetric.protocolApySource(),
                        rateMetric.protocolApyCapturedAt(),
                        true,
                        rateMetric.rewardAprStatus(),
                        rateMetric.rewardAprUnavailableReason(),
                        rateMetric.apyConvention()
                );
            }
            positions.add(new LendingPositionView(
                    key.id() + ":borrow:" + balance.getAssetContract(),
                    marketKey,
                    "BORROW",
                    LendingAssetSymbolSupport.displaySymbol(balance.getAssetSymbol()),
                    underlyingSymbol,
                    balance.getAssetContract(),
                    quantity,
                    BigDecimal.ZERO,
                    valueUsd,
                    BigDecimal.ZERO,
                    rateMetric.aliasApyPct(),
                    stale ? LendingMarketRateStatus.FALLBACK_ESTIMATE : metric.status(),
                    stale ? ACCOUNTING_ESTIMATE_SOURCE : metric.source(),
                    rateMetric.protocolSupplyApyPct(),
                    rateMetric.protocolBorrowApyPct(),
                    rateMetric.rewardAprPct(),
                    rateMetric.netProtocolApyPct(),
                    rateMetric.protocolApyStatus(),
                    rateMetric.protocolApySource(),
                    rateMetric.protocolApyCapturedAt(),
                    stale || Boolean.TRUE.equals(rateMetric.protocolApyStale()),
                    rateMetric.rewardAprStatus(),
                    rateMetric.rewardAprUnavailableReason(),
                    rateMetric.apyConvention()
            ));
        }

        private PositionRateMetric positionRateMetric(
                String protocol,
                NetworkId networkId,
                String marketKey,
                String side,
                String underlyingSymbol,
                LendingMarketMetricEstimator.MetricSnapshot fallback
        ) {
            if (marketRateSnapshotService == null) {
                return fallbackRateMetric(side, fallback);
            }
            String network = networkId == null ? null : networkId.name();
            Optional<LendingMarketRateSnapshot> snapshot = marketRateSnapshotService.latestFresh(
                    sessionId,
                    protocol,
                    network,
                    marketKey,
                    underlyingSymbol,
                    side
            );
            if (snapshot.isPresent() && isLiveRateStatus(snapshot.get().getRateStatus())) {
                LendingMarketRateSnapshot value = snapshot.get();
                BigDecimal protocolSideApy = "BORROW".equals(side) ? value.getBorrowApyPct() : value.getSupplyApyPct();
                BigDecimal netProtocolApy = "BORROW".equals(side) ? value.getNetBorrowApyPct() : value.getNetSupplyApyPct();
                return new PositionRateMetric(
                        protocolSideApy,
                        value.getRateStatus(),
                        value.getRateSource(),
                        value.getSupplyApyPct(),
                        value.getBorrowApyPct(),
                        value.getRewardAprPct(),
                        netProtocolApy,
                        value.getRateStatus(),
                        value.getRateSource(),
                        value.getCapturedAt(),
                        false,
                        value.getRewardAprStatus(),
                        value.getRewardAprUnavailableReason(),
                        value.getApyConvention()
                );
            }
            return fallbackRateMetric(side, fallback);
        }

        // A live protocol rate is either an on-chain read (Aave, PROTOCOL_SNAPSHOT) or the venue's own
        // rate API (Jupiter Lend, API_SNAPSHOT). Both are authoritative live rates, so both drive the
        // protocol supply/borrow APY (and therefore Net APY); only estimates fall back.
        private static boolean isLiveRateStatus(String rateStatus) {
            return LendingMarketRateStatus.PROTOCOL_SNAPSHOT.equals(rateStatus)
                    || LendingMarketRateStatus.API_SNAPSHOT.equals(rateStatus);
        }

        private PositionRateMetric fallbackRateMetric(
                String side,
                LendingMarketMetricEstimator.MetricSnapshot fallback
        ) {
            BigDecimal fallbackApy = fallback.apyPct();
            return new PositionRateMetric(
                    fallbackApy,
                    fallback.status(),
                    fallback.source(),
                    null,
                    null,
                    null,
                    null,
                    LendingMarketRateStatus.FALLBACK_ESTIMATE,
                    fallback.source(),
                    null,
                    false,
                    LendingMarketRateStatus.UNAVAILABLE,
                    LendingMarketRateStatus.REWARDS_COLLECTOR_NOT_IMPLEMENTED,
                    null
            );
        }

        private void addHistory(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
                String marketKey
        ) {
            if (prices != null && !prices.isEmpty()) {
                this.currentPrices = prices;
            }
            List<HistoryAmount> amounts = historyAmounts(transaction, prices, historicalPrices);
            for (int index = 0; index < amounts.size(); index++) {
                HistoryAmount amount = amounts.get(index);
                history.add(new LendingHistoryEntryView(
                        transaction.getId() + (amount.syntheticIndex() == null ? "" : ":" + amount.syntheticIndex()),
                        transaction.getTxHash(),
                        marketKey,
                        null,
                        transaction.getNetworkId() == null ? null : transaction.getNetworkId().name(),
                        normalizeAddress(transaction.getWalletAddress()),
                        transaction.getBlockTimestamp(),
                        amount.type(),
                        amount.eventSubtype(),
                        amount.displayType(),
                        amount.assetSymbol(),
                        amount.quantity(),
                        amount.valueUsd(),
                        index == 0 ? amount.feeUsd() : BigDecimal.ZERO,
                        index == 0 ? amount.feeQuantityByAsset() : Map.of(),
                        loopId(transaction),
                        amount.withdrawYieldByAsset(),
                        transaction.getReceiptBearingCollateral()
                ));
                if (index != 0) {
                    continue;
                }
                if (transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                        || transaction.getType() == NormalizedTransactionType.LENDING_LOOP_CLOSE
                        || transaction.getType() == NormalizedTransactionType.LENDING_LOOP_DECREASE
                        || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW) {
                    closedEarnedUsd = closedEarnedUsd.add(positiveBuyValue(transaction), MC);
                } else if (transaction.getType() == NormalizedTransactionType.REWARD_CLAIM) {
                    closedEarnedUsd = closedEarnedUsd.add(amount.valueUsd(), MC);
                }
            }
        }

        private void addLinkedCashExitSwaps(List<NormalizedTransaction> cashExitSwaps) {
            if (cashExitSwaps.isEmpty() || history.isEmpty() || !isEulerProtocol()) {
                return;
            }
            Set<String> usedSwapIds = new LinkedHashSet<>();
            List<LendingHistoryEntryView> additions = new ArrayList<>();
            List<LendingHistoryEntryView> orderedHistory = history.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList();
            for (LendingHistoryEntryView exit : orderedHistory) {
                if (!isEulerCashExitSource(exit)) {
                    continue;
                }
                Optional<NormalizedTransaction> linkedSwap = cashExitSwaps.stream()
                        .filter(swap -> !usedSwapIds.contains(swap.getId()))
                        .filter(swap -> sameWalletNetwork(exit, swap))
                        .filter(swap -> isAfterWithin(exit.blockTimestamp(), swap.getBlockTimestamp(), Duration.ofMinutes(15)))
                        .filter(this::isStableToStableCashExitSwap)
                        .findFirst();
                if (linkedSwap.isEmpty()) {
                    continue;
                }
                Optional<NormalizedTransaction.Flow> cashBuy = stableBuyFlow(linkedSwap.get());
                if (cashBuy.isEmpty()) {
                    continue;
                }
                usedSwapIds.add(linkedSwap.get().getId());
                NormalizedTransaction.Flow flow = cashBuy.get();
                BigDecimal quantity = zeroIfNull(flow.getQuantityDelta()).abs();
                additions.add(new LendingHistoryEntryView(
                        linkedSwap.get().getId() + ":lending-cash-exit",
                        linkedSwap.get().getTxHash(),
                        exit.marketKey(),
                        null,
                        linkedSwap.get().getNetworkId() == null ? exit.networkId() : linkedSwap.get().getNetworkId().name(),
                        normalizeAddress(linkedSwap.get().getWalletAddress()),
                        linkedSwap.get().getBlockTimestamp(),
                        "LENDING_CASH_EXIT",
                        "LINKED_POST_EXIT_SWAP",
                        "Cash exit",
                        LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()),
                        quantity,
                        flow.getValueUsd() == null ? quantity : flow.getValueUsd().abs(),
                        BigDecimal.ZERO,
                        Map.of(),
                        exit.loopId(),
                        Map.of(),
                        exit.receiptBearingCollateral()
                ));
            }
            history.addAll(additions);
        }

        private boolean isEulerCashExitSource(LendingHistoryEntryView event) {
            return event != null
                    && ("LENDING_LOOP_CLOSE".equals(event.type()) || "LENDING_LOOP_DECREASE".equals(event.type()))
                    && event.valueUsd() != null
                    && event.valueUsd().signum() > 0;
        }

        private boolean isEulerProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("EULER");
        }

        private boolean isInternalReceiptExit(LendingHistoryEntryView event) {
            return event != null
                    && ("LENDING_WITHDRAW".equals(event.type())
                    || "VAULT_WITHDRAW".equals(event.type())
                    || "LENDING_LOOP_CLOSE".equals(event.type())
                    || "LENDING_LOOP_DECREASE".equals(event.type()))
                    && LendingAssetSymbolSupport.isLendingPositionSymbol(event.assetSymbol());
        }

        private boolean sameWalletNetwork(LendingHistoryEntryView exit, NormalizedTransaction swap) {
            return Objects.equals(exit.networkId(), swap.getNetworkId() == null ? null : swap.getNetworkId().name())
                    && Objects.equals(exit.walletAddress(), normalizeAddress(swap.getWalletAddress()));
        }

        private boolean isAfterWithin(Instant source, Instant candidate, Duration window) {
            if (source == null || candidate == null || candidate.isBefore(source)) {
                return false;
            }
            return !Duration.between(source, candidate).minus(window).isPositive();
        }

        private boolean swapConsumesExitValue(NormalizedTransaction swap, BigDecimal exitValueUsd) {
            return safeFlows(swap).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.SELL)
                    .filter(flow -> flow.getValueUsd() != null || flow.getQuantityDelta() != null)
                    .anyMatch(flow -> valuesMatch(exitValueUsd, flow.getValueUsd(), flow.getQuantityDelta()));
        }

        private boolean isStableToStableCashExitSwap(NormalizedTransaction swap) {
            return safeFlows(swap).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.SELL)
                    .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0)
                    .anyMatch(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    && stableBuyFlow(swap).isPresent();
        }

        private boolean valuesMatch(BigDecimal expected, BigDecimal valueUsd, BigDecimal quantity) {
            BigDecimal actual = valueUsd != null ? valueUsd.abs() : zeroIfNull(quantity).abs();
            return actual.subtract(expected, MC).abs().compareTo(new BigDecimal("0.000001")) <= 0;
        }

        private Optional<NormalizedTransaction.Flow> stableBuyFlow(NormalizedTransaction swap) {
            return safeFlows(swap).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.BUY)
                    .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0)
                    .filter(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
        }

        private LendingGroupView toView(LendingMarketMetricEstimator estimator) {
            boolean open = positions.stream().anyMatch(position -> position.quantity().signum() > 0);
            List<LendingPositionView> livePositions = sortedPositions(positions);
            List<LendingHistoryEntryView> cycleHistory = cycleBuildingHistory();
            List<LendingCycleView> cycles = buildCycles(livePositions, cycleHistory);
            // Receipt-less networks (Solana/TON) expose no on-chain receipt-token balance to mark the
            // group open, so an open Jupiter Lend / Kamino loop would otherwise be classified CLOSED
            // and hidden. Promote the group to OPEN when any reconstructed cycle is still open there.
            if (!open && isReceiptLessLendingNetwork(cycleHistory)) {
                open = cycles.stream().anyMatch(cycle -> "OPEN".equals(cycle.status()));
            }
            // Bubble reconstructed outstanding-debt BORROW positions (no live debt-token balance)
            // up to the group so borrowUsd, net exposure, and summary totals include them.
            List<LendingPositionView> synthesizedBorrows = cycles.stream()
                    .filter(cycle -> "OPEN".equals(cycle.status()))
                    .flatMap(cycle -> cycle.positions().stream())
                    .filter(LendingCycleBuilder::isSynthesizedBorrow)
                    .toList();
            BigDecimal synthesizedBorrowUsd = synthesizedBorrows.stream()
                    .map(position -> zeroIfNull(position.valueUsd()))
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
            borrowUsd = borrowUsd.add(synthesizedBorrowUsd, MC);
            // Symmetric to synthesized borrows: bubble reconstructed collateral SUPPLY positions (no
            // live receipt-token balance on Solana/TON) into supplyUsd, net exposure, and totals.
            List<LendingPositionView> synthesizedSupplies = cycles.stream()
                    .filter(cycle -> "OPEN".equals(cycle.status()))
                    .flatMap(cycle -> cycle.positions().stream())
                    .filter(LendingCycleBuilder::isSynthesizedSupply)
                    .toList();
            BigDecimal synthesizedSupplyUsd = synthesizedSupplies.stream()
                    .map(position -> zeroIfNull(position.valueUsd()))
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
            supplyUsd = supplyUsd.add(synthesizedSupplyUsd, MC);
            List<LendingPositionView> synthesizedPositions = concatenate(synthesizedBorrows, synthesizedSupplies);
            List<LendingPositionView> groupPositions = synthesizedPositions.isEmpty()
                    ? livePositions
                    : sortedPositions(concatenate(positions, synthesizedPositions));
            HealthMetricSnapshot healthMetric = resolveHealthMetric(estimator);
            List<LendingHistoryEntryView> sortedHistory = cycles.stream()
                    .flatMap(cycle -> cycle.events().stream())
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp).reversed())
                    .toList();
            return new LendingGroupView(
                    key.id(),
                    key.protocol(),
                    key.networkId() == null ? null : key.networkId().name(),
                    key.walletAddress(),
                    open ? "OPEN" : "CLOSED",
                    healthMetric.healthFactor(),
                    healthMetric.healthLabel(),
                    healthMetric.healthProgress(),
                    healthMetric.status(),
                    healthMetric.source(),
                    healthMetric.stale(),
                    healthMetric.lastRefreshedAt(),
                    supplyUsd.add(closedEarnedUsd, MC),
                    borrowUsd,
                    supplyUsd.subtract(borrowUsd, MC).add(closedEarnedUsd, MC),
                    groupPositions,
                    cycles,
                    sortedHistory
            );
        }

        private List<LendingPositionView> sortedPositions(List<LendingPositionView> source) {
            return source.stream()
                    .sorted(Comparator.comparing(LendingPositionView::side).reversed()
                            .thenComparing(LendingPositionView::underlyingSymbol))
                    .toList();
        }

        private List<LendingPositionView> concatenate(
                List<LendingPositionView> first,
                List<LendingPositionView> second
        ) {
            List<LendingPositionView> combined = new ArrayList<>(first);
            combined.addAll(second);
            return combined;
        }

        private List<LendingHistoryEntryView> cycleBuildingHistory() {
            if (!isMorphoProtocol()) {
                return history;
            }
            List<LendingHistoryEntryView> ordered = history.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList();
            List<LendingHistoryEntryView> result = new ArrayList<>();
            for (LendingHistoryEntryView event : ordered) {
                result.add(retargetMorphoLinkedCollateralOut(event, result));
            }
            return result;
        }

        private LendingHistoryEntryView retargetMorphoLinkedCollateralOut(
                LendingHistoryEntryView event,
                List<LendingHistoryEntryView> previousEvents
        ) {
            if (!isMorphoLinkedCollateralOut(event)) {
                return event;
            }
            return previousEvents.stream()
                    .filter(candidate -> isMorphoBundlerUnwindAnchor(candidate, event))
                    .max(Comparator.comparing(LendingHistoryEntryView::blockTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(anchor -> withMarketKey(event, anchor.marketKey()))
                    .orElse(event);
        }

        private boolean isMorphoLinkedCollateralOut(LendingHistoryEntryView event) {
            if (event == null || event.eventSubtype() == null || !isClosingEvent(event)) {
                return false;
            }
            String subtype = event.eventSubtype().toUpperCase(Locale.ROOT);
            return subtype.contains("MORPHO_BUNDLER_COLLATERAL_OUT")
                    && !LendingAssetSymbolSupport.isStable(event.assetSymbol());
        }

        private boolean isMorphoBundlerUnwindAnchor(
                LendingHistoryEntryView candidate,
                LendingHistoryEntryView linkedOutput
        ) {
            if (candidate == null
                    || linkedOutput == null
                    || candidate.eventSubtype() == null
                    || !isClosingEvent(candidate)
                    || Objects.equals(candidate.marketKey(), linkedOutput.marketKey())) {
                return false;
            }
            String subtype = candidate.eventSubtype().toUpperCase(Locale.ROOT);
            return subtype.contains("MORPHO_BUNDLER_COLLATERAL_OUT")
                    && LendingAssetSymbolSupport.isStable(candidate.assetSymbol())
                    && Objects.equals(candidate.walletAddress(), linkedOutput.walletAddress())
                    && Objects.equals(candidate.networkId(), linkedOutput.networkId())
                    && isAfterWithin(candidate.blockTimestamp(), linkedOutput.blockTimestamp(), Duration.ofMinutes(15));
        }

        private LendingHistoryEntryView withMarketKey(LendingHistoryEntryView event, String marketKey) {
            return new LendingHistoryEntryView(
                    event.id(),
                    event.txHash(),
                    marketKey,
                    event.cycleId(),
                    event.networkId(),
                    event.walletAddress(),
                    event.blockTimestamp(),
                    event.type(),
                    event.eventSubtype(),
                    event.displayType(),
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.feeUsd(),
                    event.feeQuantityByAsset(),
                    event.loopId(),
                    event.withdrawYieldByAsset(),
                    event.receiptBearingCollateral()
            );
        }

        private List<LendingCycleView> buildCycles(
                List<LendingPositionView> sortedPositions,
                List<LendingHistoryEntryView> cycleHistory
        ) {
            Map<String, List<LendingPositionView>> positionsByMarket = sortedPositions.stream()
                    .collect(Collectors.groupingBy(
                            LendingPositionView::marketKey,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            Map<String, List<LendingHistoryEntryView>> historyByMarket = cycleHistory.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .collect(Collectors.groupingBy(
                            LendingHistoryEntryView::marketKey,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            LinkedHashSet<String> marketKeys = new LinkedHashSet<>();
            marketKeys.addAll(historyByMarket.keySet());
            marketKeys.addAll(positionsByMarket.keySet());

            List<LendingCycleView> cycles = new ArrayList<>();
            for (String marketKey : marketKeys) {
                List<LendingHistoryEntryView> marketHistory = historyByMarket.getOrDefault(marketKey, List.of());
                List<LendingPositionView> marketPositions = positionsByMarket.getOrDefault(marketKey, List.of());
                cycles.addAll(buildMarketCycles(marketKey, marketHistory, marketPositions));
            }
            return cycles.stream()
                    .sorted(Comparator.comparing(LendingCycleView::status).reversed()
                            .thenComparing(LendingCycleView::startTimestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }

        private List<LendingCycleView> buildMarketCycles(
                String marketKey,
                List<LendingHistoryEntryView> marketHistory,
                List<LendingPositionView> marketPositions
        ) {
            if (usesConcurrentAssetCycles()) {
                return buildConcurrentAssetCycles(marketKey, marketHistory, marketPositions);
            }
            if (marketHistory.isEmpty() && !marketPositions.isEmpty()) {
                String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-1";
                return List.of(toCycle(cycleId, marketKey, "OPEN", List.of(), marketPositions, new DeltaAccumulator()));
            }

            List<LendingCycleView> result = new ArrayList<>();
            List<LendingHistoryEntryView> currentEvents = new ArrayList<>();
            DeltaAccumulator deltas = new DeltaAccumulator();
            CycleState cycleState = new CycleState();
            int cycleIndex = 1;
            boolean marketHasOpenPosition = marketPositions.stream().anyMatch(position -> position.quantity().signum() > 0);

            for (int index = 0; index < marketHistory.size(); index++) {
                LendingHistoryEntryView event = effectiveSequentialEvent(cycleState, marketHistory.get(index));
                if (currentEvents.isEmpty() && !isOpeningEvent(event)) {
                    if (attachPostCloseEventToPreviousCycle(result, event)) {
                        continue;
                    }
                    if ("REWARD_CLAIM".equals(event.type())) {
                        continue;
                    }
                    if (addOrphanCycle(result, marketKey, event, cycleIndex++)) {
                        continue;
                    }
                }
                if (!currentEvents.isEmpty() && isUnmatchedClosingEvent(cycleState, event)) {
                    if (addOrphanCycle(result, marketKey, event, cycleIndex++)) {
                        continue;
                    }
                }
                if (currentEvents.isEmpty()) {
                    deltas = new DeltaAccumulator();
                    cycleState = new CycleState();
                }
                currentEvents.add(event);
                deltas.add(event);
                cycleState.add(event);

                boolean closeNow = cycleState.isFlat() && isClosingEvent(event);
                if (closeNow) {
                    deltas.markCycleClosedEconomically();
                    String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex++;
                    result.add(toCycle(cycleId, marketKey, "CLOSED", currentEvents, List.of(), deltas));
                    currentEvents = new ArrayList<>();
                }
            }

            if (!currentEvents.isEmpty()) {
                String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex;
                // On EVM an on-chain receipt-token balance (marketHasOpenPosition) is the source of
                // truth for a live supply/borrow position. Solana/TON lending protocols (e.g. Jupiter
                // Lend) expose no fungible receipt token that we snapshot as an on_chain_balance, so a
                // genuinely open loop would always read as CLOSED and be hidden by the default
                // "hide closed" UI filter. For those receipt-less networks, fall back to the
                // accounting state (remaining supply/debt) so open loops with unrepaid debt stay OPEN.
                boolean accountingOpen = isReceiptLessLendingNetwork(currentEvents) && !cycleState.isFlat();
                String status = (marketHasOpenPosition || accountingOpen) ? "OPEN" : "CLOSED";
                if ("CLOSED".equals(status) && cycleState.isFlat()) {
                    deltas.markCycleClosedEconomically();
                }
                result.add(toCycle(cycleId, marketKey, status, currentEvents, marketPositions, deltas));
            }
            return result;
        }

        private boolean addOrphanCycle(
                List<LendingCycleView> result,
                String marketKey,
                LendingHistoryEntryView event,
                int orphanIndex
        ) {
            if (orphanAlreadyExists(result, marketKey, event.txHash())) {
                return true;
            }
            DeltaAccumulator orphanDeltas = new DeltaAccumulator();
            orphanDeltas.add(event);
            String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":orphan-" + orphanIndex;
            result.add(toCycle(cycleId, marketKey, "AMBIGUOUS_NEEDS_REVIEW", List.of(event), List.of(), orphanDeltas));
            return true;
        }

        private boolean orphanAlreadyExists(
                List<LendingCycleView> result,
                String marketKey,
                String startTxHash
        ) {
            return result.stream().anyMatch(cycle ->
                    "AMBIGUOUS_NEEDS_REVIEW".equals(cycle.status())
                            && Objects.equals(cycle.marketKey(), marketKey)
                            && Objects.equals(cycle.startTxHash(), startTxHash));
        }

        private boolean attachPostCloseEventToPreviousCycle(
                List<LendingCycleView> result,
                LendingHistoryEntryView event
        ) {
            if (!(isCompoundProtocol() || isEulerProtocol())
                    || result.isEmpty()
                    || event == null
                    || (!isClosingEvent(event)
                    && !"REWARD_CLAIM".equals(event.type())
                    && !"LENDING_CASH_EXIT".equals(event.type()))) {
                return false;
            }
            if (!"LENDING_CASH_EXIT".equals(event.type())
                    && !isCompoundProtocol()
                    && isEulerProtocol()) {
                return false;
            }
            if (!"LENDING_CASH_EXIT".equals(event.type())
                    && isClosingEvent(event)
                    && zeroIfNull(event.quantity()).compareTo(CYCLE_DUST_TOLERANCE) > 0) {
                return false;
            }
            LendingCycleView previous = result.get(result.size() - 1);
            if (!"CLOSED".equals(previous.status())) {
                return false;
            }
            List<LendingHistoryEntryView> events = new ArrayList<>(previous.events());
            events.add(event);
            List<LendingHistoryEntryView> ascending = events.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList();
            DeltaAccumulator deltas = new DeltaAccumulator();
            ascending.forEach(deltas::add);
            result.set(result.size() - 1, toCycle(
                    previous.id(),
                    previous.marketKey(),
                    previous.status(),
                    ascending,
                    previous.positions(),
                    deltas
            ));
            return true;
        }

        private HealthMetricSnapshot resolveHealthMetric(LendingMarketMetricEstimator estimator) {
            LendingMarketMetricEstimator.MetricSnapshot fallback = estimator.estimate(
                    key.protocol(),
                    "GROUP",
                    "",
                    supplyUsd,
                    borrowUsd
            );
            Instant lastRefreshedAt = resolveLastRefreshedAt();
            // WS-3: no hardcoded protocol gate — any protocol with a fresh LIVE_PROTOCOL snapshot
            // (Aave EVM, Jupiter Lend Solana, …) uses it; others fall back to the accounting estimate.
            if (healthFactorSnapshotService == null
                    || borrowUsd == null
                    || borrowUsd.signum() <= 0
                    || key.protocol() == null) {
                return new HealthMetricSnapshot(
                        fallback.healthFactor(),
                        fallback.healthLabel(),
                        fallback.healthProgress(),
                        fallback.status(),
                        fallback.source(),
                        false,
                        lastRefreshedAt
                );
            }
            String networkId = key.networkId() == null ? null : key.networkId().name();
            return healthFactorSnapshotService.latestFresh(
                    sessionId,
                    key.protocol(),
                    networkId,
                    key.walletAddress()
            ).map(snapshot -> new HealthMetricSnapshot(
                    snapshot.getHealthFactor(),
                    healthLabel(snapshot.getHealthFactor()),
                    healthProgress(snapshot.getHealthFactor()),
                    fallback.status(),
                    LendingHealthFactorSnapshotService.LIVE_PROTOCOL,
                    false,
                    snapshot.getCapturedAt() != null ? snapshot.getCapturedAt() : lastRefreshedAt
            )).orElseGet(() -> new HealthMetricSnapshot(
                    fallback.healthFactor(),
                    fallback.healthLabel(),
                    fallback.healthProgress(),
                    fallback.status(),
                    "ACCOUNTING_ESTIMATE",
                    borrowUsd.signum() > 0,
                    lastRefreshedAt
            ));
        }

        private Instant resolveLastRefreshedAt() {
            Instant fromHealth = null;
            if (healthFactorSnapshotService != null
                    && key.protocol() != null
                    && key.networkId() != null
                    && key.walletAddress() != null) {
                fromHealth = healthFactorSnapshotService.latest(
                        sessionId,
                        key.protocol(),
                        key.networkId().name(),
                        key.walletAddress()
                ).map(LendingHealthFactorSnapshot::getCapturedAt).orElse(null);
            }
            Instant fromRefreshState = lendingGroupRefreshStateRepository.findById(key.id())
                    .map(LendingGroupRefreshState::getLastSyncedAt)
                    .orElse(null);
            Instant fromMarketRateSnapshot = null;
            if (key.protocol() != null && key.networkId() != null && key.walletAddress() != null) {
                fromMarketRateSnapshot = marketRateSnapshotService.latestCapturedAt(
                        sessionId,
                        key.protocol(),
                        key.networkId().name(),
                        key.walletAddress()
                ).orElse(null);
            }
            return maxInstant(fromHealth, maxInstant(fromRefreshState, fromMarketRateSnapshot));
        }

        private static Instant maxInstant(Instant left, Instant right) {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return left.isAfter(right) ? left : right;
        }

        private String healthLabel(BigDecimal healthFactor) {
            if (healthFactor == null) {
                return "Unavailable";
            }
            if (healthFactor.compareTo(BigDecimal.valueOf(10)) >= 0) {
                return "No debt";
            }
            if (healthFactor.compareTo(BigDecimal.valueOf(2)) >= 0) {
                return "Safe";
            }
            if (healthFactor.compareTo(BigDecimal.valueOf(1.5)) >= 0) {
                return "Moderate";
            }
            if (healthFactor.compareTo(BigDecimal.valueOf(1.1)) >= 0) {
                return "At risk";
            }
            return "Liquidation risk";
        }

        private BigDecimal healthProgress(BigDecimal healthFactor) {
            if (healthFactor == null || healthFactor.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal capped = healthFactor.min(BigDecimal.valueOf(3));
            return capped.divide(BigDecimal.valueOf(3), MC).multiply(BigDecimal.valueOf(100), MC)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }

        private LendingHistoryEntryView effectiveSequentialEvent(CycleState cycleState, LendingHistoryEntryView event) {
            if (!isCompoundProtocol()
                    || event == null
                    || !"LENDING_DEPOSIT".equals(event.type())
                    || !LendingAssetSymbolSupport.isStable(event.assetSymbol())
                    || cycleState == null
                    || !cycleState.hasAnyDebt()) {
                return event;
            }
            return new LendingHistoryEntryView(
                    event.id(),
                    event.txHash(),
                    event.marketKey(),
                    event.cycleId(),
                    event.networkId(),
                    event.walletAddress(),
                    event.blockTimestamp(),
                    "REPAY",
                    "COMET_BASE_REPAY",
                    "Repay",
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.feeUsd(),
                    event.feeQuantityByAsset(),
                    event.loopId(),
                    event.withdrawYieldByAsset(),
                    event.receiptBearingCollateral()
            );
        }

        private boolean usesConcurrentAssetCycles() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("AAVE");
        }

        private List<LendingCycleView> buildConcurrentAssetCycles(
                String marketKey,
                List<LendingHistoryEntryView> marketHistory,
                List<LendingPositionView> marketPositions
        ) {
            if (marketHistory.isEmpty() && !marketPositions.isEmpty()) {
                return positionsOnlyCycles(marketKey, marketPositions);
            }

            List<LendingCycleView> result = new ArrayList<>();
            List<MutableCycle> openCycles = new ArrayList<>();
            int cycleIndex = 1;
            int orphanIndex = 1;

            for (LendingHistoryEntryView event : marketHistory) {
                MutableCycle target = selectCycle(openCycles, event);
                if (target == null) {
                    if ("REWARD_CLAIM".equals(event.type())) {
                        continue;
                    }
                    if (isOpeningEvent(event)) {
                        target = new MutableCycle(marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex++);
                        openCycles.add(target);
                    } else {
                        if (addOrphanCycle(result, marketKey, event, orphanIndex++)) {
                            continue;
                        }
                        continue;
                    }
                }

                target.add(event);
                if (isClosingEvent(event) && target.isFlat()) {
                    result.add(toCycle(target.id(), marketKey, "CLOSED", target.events(), List.of(), target.deltas()));
                    openCycles.remove(target);
                }
            }

            LinkedHashSet<LendingPositionView> assignedPositions = new LinkedHashSet<>();
            for (MutableCycle cycle : openCycles) {
                List<LendingPositionView> cyclePositions = positionsForCycle(cycle.state(), marketPositions);
                assignedPositions.addAll(cyclePositions);
                String status = cyclePositions.stream().anyMatch(position -> position.quantity().signum() > 0)
                        ? "OPEN"
                        : "CLOSED";
                result.add(toCycle(
                        cycle.id(),
                        marketKey,
                        status,
                        cycle.events(),
                        "OPEN".equals(status) ? cyclePositions : List.of(),
                        cycle.deltas()
                ));
            }

            List<LendingPositionView> unassignedPositions = marketPositions.stream()
                    .filter(position -> !assignedPositions.contains(position))
                    .toList();
            if (!unassignedPositions.isEmpty()) {
                result.addAll(positionsOnlyCycles(marketKey, unassignedPositions, cycleIndex));
            }

            return result;
        }

        private List<LendingCycleView> positionsOnlyCycles(
                String marketKey,
                List<LendingPositionView> marketPositions
        ) {
            return positionsOnlyCycles(marketKey, marketPositions, 1);
        }

        private List<LendingCycleView> positionsOnlyCycles(
                String marketKey,
                List<LendingPositionView> marketPositions,
                int firstCycleIndex
        ) {
            Map<String, List<LendingPositionView>> positionsByAsset = marketPositions.stream()
                    .collect(Collectors.groupingBy(
                            position -> cycleStateAsset(position.assetSymbol()),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            List<LendingCycleView> cycles = new ArrayList<>();
            int cycleIndex = firstCycleIndex;
            for (List<LendingPositionView> positions : positionsByAsset.values()) {
                String cycleId = marketKey.toLowerCase(Locale.ROOT) + ":cycle-" + cycleIndex++;
                cycles.add(toCycle(cycleId, marketKey, "OPEN", List.of(), positions, new DeltaAccumulator()));
            }
            return cycles;
        }

        private MutableCycle selectCycle(List<MutableCycle> openCycles, LendingHistoryEntryView event) {
            if (openCycles.isEmpty()) {
                return null;
            }
            String asset = cycleStateAsset(event.assetSymbol());
            if (isOpeningEvent(event)) {
                return latestBorrowFollowedBySupply(openCycles, event)
                        .or(() -> latestWithPositiveSupply(openCycles, asset))
                        .orElse(null);
            }
            if ("BORROW".equals(event.type())) {
                return latestWithPositiveDebt(openCycles, asset)
                        .or(() -> latestRecentlyOpened(openCycles, event))
                        .or(() -> latestWithAnySupply(openCycles))
                        .orElse(null);
            }
            if ("REPAY".equals(event.type())) {
                return latestWithPositiveDebt(openCycles, asset)
                        .or(() -> latestWithAnyDebt(openCycles))
                        .or(() -> latestWithAnySupply(openCycles))
                        .orElse(null);
            }
            if (isClosingEvent(event)) {
                return latestWithPositiveSupply(openCycles, asset).orElse(null);
            }
            if ("REWARD_CLAIM".equals(event.type()) && openCycles.size() == 1) {
                return openCycles.get(0);
            }
            return null;
        }

        private Optional<MutableCycle> latestBorrowFollowedBySupply(
                List<MutableCycle> openCycles,
                LendingHistoryEntryView supply
        ) {
            return openCycles.stream()
                    .filter(cycle -> cycle.lastEvent().map(last -> "BORROW".equals(last.type())).orElse(false))
                    .filter(cycle -> isWithinLoopWindow(cycle.lastTimestamp(), supply.blockTimestamp()))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestRecentlyOpened(
                List<MutableCycle> openCycles,
                LendingHistoryEntryView event
        ) {
            return openCycles.stream()
                    .filter(cycle -> cycle.lastEvent().map(this::isOpeningEvent).orElse(false))
                    .filter(cycle -> isWithinLoopWindow(cycle.lastTimestamp(), event.blockTimestamp()))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithPositiveSupply(List<MutableCycle> openCycles, String asset) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasPositiveSupply(asset))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithPositiveDebt(List<MutableCycle> openCycles, String asset) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasPositiveDebt(asset))
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithAnySupply(List<MutableCycle> openCycles) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasAnySupply())
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private Optional<MutableCycle> latestWithAnyDebt(List<MutableCycle> openCycles) {
            return openCycles.stream()
                    .filter(cycle -> cycle.state().hasAnyDebt())
                    .max(Comparator.comparing(MutableCycle::lastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        private boolean isWithinLoopWindow(Instant left, Instant right) {
            if (left == null || right == null) {
                return false;
            }
            return !Duration.between(left, right).abs().minus(LOOP_GROUP_WINDOW).isPositive();
        }

        private List<LendingPositionView> positionsForCycle(CycleState state, List<LendingPositionView> positions) {
            return positions.stream()
                    .filter(position -> {
                        String asset = cycleStateAsset(position.assetSymbol());
                        if ("BORROW".equals(position.side())) {
                            return state.hasPositiveDebt(asset);
                        }
                        return state.hasPositiveSupply(asset);
                    })
                    .toList();
        }

        private BigDecimal estimateCoveredQuantity(String marketKey, String assetSymbol) {
            String targetAsset = cycleStateAsset(assetSymbol);
            CycleState cycleState = new CycleState();
            boolean active = false;
            for (LendingHistoryEntryView event : history.stream()
                    .filter(candidate -> Objects.equals(candidate.marketKey(), marketKey))
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList()) {
                if (!active && !isOpeningEvent(event)) {
                    continue;
                }
                active = true;
                cycleState.add(event);
                if (isClosingEvent(event) && cycleState.isFlat()) {
                    cycleState = new CycleState();
                    active = false;
                }
            }
            return cycleState.supplyAmount(targetAsset).max(BigDecimal.ZERO);
        }

        private LendingCycleView toCycle(
                String cycleId,
                String marketKey,
                String status,
                List<LendingHistoryEntryView> events,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            List<LendingHistoryEntryView> cycleEvents = events.stream()
                    .map(event -> eventWithCycle(event, cycleId))
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp).reversed())
                    .toList();
            LendingHistoryEntryView start = events.isEmpty() ? null : events.get(0);
            LendingHistoryEntryView close = "CLOSED".equals(status) && !events.isEmpty()
                    ? events.stream().filter(this::isClosingEvent).reduce((first, second) -> second).orElse(events.get(events.size() - 1))
                    : null;
            // Build display positions FIRST: the synthesized outstanding borrow (trued-up to the live
            // debt) and — on receipt-less Solana/TON — the reconstructed collateral SUPPLY. Valuation
            // must use these, otherwise the current collateral/debt value is silently zero when the
            // network exposes no live receipt-token balance, producing a bogus large "running PnL"
            // (e.g. −$790 that ignored the ~$421 SOL collateral entirely).
            List<LendingPositionView> displayPositions = withSynthesizedOutstandingBorrows(
                    cycleId, marketKey, status, cyclePositions, deltas);
            displayPositions = withSynthesizedOutstandingSupply(
                    cycleId, marketKey, status, displayPositions, deltas);
            BigDecimal unrealizedUsd = displayPositions.stream()
                    .filter(position -> "SUPPLY".equals(position.side()))
                    .map(LendingPositionView::valueUsd)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
            BigDecimal liveOutstandingBorrowUsd = displayPositions.stream()
                    .filter(position -> "BORROW".equals(position.side()))
                    .map(LendingPositionView::valueUsd)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
            LendingPnlBreakdownView pnlBreakdown = pnlBreakdown(status, displayPositions, deltas);
            LendingTotalValuationView totalValuation = totalValuation(
                    status, unrealizedUsd, liveOutstandingBorrowUsd, pnlBreakdown, deltas);
            LendingPnlAssetBreakdownView pnlAssetBreakdown = pnlAssetBreakdown(status, displayPositions, deltas, totalValuation);
            LendingFactualApyView factualApy = factualApy(status, start, close, totalValuation, displayPositions, deltas);
            List<String> largePnlReasons = largePnlReasons(status, pnlBreakdown, totalValuation, pnlAssetBreakdown, deltas);
            String primaryLargePnlReason = largePnlReasons.isEmpty() ? null : largePnlReasons.get(0);
            CyclePeakView peaks = cyclePeaks(events);
            return new LendingCycleView(
                    cycleId,
                    marketKey,
                    marketLabel(marketKey),
                    status,
                    start == null ? null : start.blockTimestamp(),
                    close == null ? null : close.blockTimestamp(),
                    start == null ? null : start.txHash(),
                    close == null ? null : close.txHash(),
                    statusDetail(status, deltas),
                    warningReason(status, deltas),
                    pnlAssetBreakdown.netIncomeByAsset(),
                    pnlAssetBreakdown.precisionByAsset(),
                    pnlAssetBreakdown.reasonByAsset(),
                    primaryAssetPnlSummary(pnlAssetBreakdown),
                    primaryLargePnlReason,
                    largePnlReasons,
                    primaryLargePnlReason,
                    deltas.toView(),
                    new LendingPnlView(pnlBreakdown.netPnlUsd(), pnlBreakdown.precision(), pnlBreakdown.method()),
                    new LendingPnlView(
                            "OPEN".equals(status) ? unrealizedUsd : null,
                            "OPEN".equals(status) ? "ESTIMATED" : "UNAVAILABLE",
                            "OPEN".equals(status) ? "current-balance-current-quote" : "closed-cycle"
                    ),
                    pnlBreakdown,
                    pnlAssetBreakdown,
                    factualApy,
                    totalValuation,
                    observedFlowsByAsset(status, deltas),
                    peaks.peakSupplyUsd(),
                    peaks.peakBorrowUsd(),
                    durationDays(start, close),
                    displayPositions,
                    cycleEvents,
                    txGroups(cycleId, events)
            );
        }

        /**
         * Reconstruct BORROW positions for outstanding debt (borrowed - repaid) that is NOT backed
         * by a live on-chain debt-token balance. Some protocols do not surface a held debt token,
         * so the only evidence of open debt is the cycle's borrow/repay accounting. We value the
         * synthesized debt with the same USD pricing already applied to the borrow/repay legs and
         * mark its metric status honestly (ACCOUNTING_ESTIMATE, or FALLBACK_ESTIMATE when a reliable
         * USD price is unavailable). To avoid double-counting we skip any asset that already has a
         * live BORROW position in this cycle.
         */
        private List<LendingPositionView> withSynthesizedOutstandingBorrows(
                String cycleId,
                String marketKey,
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            if (!"OPEN".equals(status)) {
                return cyclePositions;
            }
            Map<String, BigDecimal> borrowed = deltas.borrowedByAsset();
            if (borrowed.isEmpty()) {
                return cyclePositions;
            }
            Map<String, BigDecimal> repaid = deltas.repaidByAsset();
            Map<String, BigDecimal> borrowedUsd = deltas.borrowedUsdByAsset();
            Map<String, BigDecimal> repaidUsd = deltas.repaidUsdByAsset();
            // WS-3 single-authority: when the live reader has a fresh debt snapshot, true up the
            // synthesized debt to the on-chain outstanding amount (e.g. 233.39 USDT incl. accrued
            // interest instead of the 210 borrow principal). Absent a fresh snapshot the reader is
            // treated as unreachable and the accounting borrowed−repaid figure remains the fallback.
            Map<String, BigDecimal> liveDebtByAsset = liveDebtByAsset();
            List<LendingPositionView> synthesized = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : borrowed.entrySet()) {
                String asset = entry.getKey();
                BigDecimal outstandingQty = zeroIfNull(entry.getValue())
                        .subtract(repaid.getOrDefault(asset, BigDecimal.ZERO), MC);
                BigDecimal liveQty = liveDebtByAsset.get(asset);
                if (liveQty != null && liveQty.signum() > 0) {
                    outstandingQty = liveQty;
                }
                if (outstandingQty.compareTo(CYCLE_DUST_TOLERANCE) <= 0) {
                    continue;
                }
                if (hasLiveBorrowPosition(cyclePositions, asset)) {
                    continue;
                }
                synthesized.add(synthesizedBorrowPosition(
                        cycleId, marketKey, asset, outstandingQty,
                        zeroIfNull(entry.getValue()),
                        borrowedUsd.getOrDefault(asset, BigDecimal.ZERO),
                        repaidUsd.getOrDefault(asset, BigDecimal.ZERO)));
            }
            if (synthesized.isEmpty()) {
                return cyclePositions;
            }
            List<LendingPositionView> combined = new ArrayList<>(cyclePositions);
            combined.addAll(synthesized);
            return List.copyOf(combined);
        }

        private boolean hasLiveBorrowPosition(List<LendingPositionView> cyclePositions, String asset) {
            return cyclePositions.stream()
                    .filter(position -> "BORROW".equals(position.side()))
                    .filter(position -> zeroIfNull(position.quantity()).compareTo(CYCLE_DUST_TOLERANCE) > 0)
                    .anyMatch(position -> Objects.equals(cycleStateAsset(position.underlyingSymbol()), asset));
        }

        private LendingPositionView synthesizedBorrowPosition(
                String cycleId,
                String marketKey,
                String asset,
                BigDecimal outstandingQty,
                BigDecimal borrowedQty,
                BigDecimal borrowedUsd,
                BigDecimal repaidUsd
        ) {
            // Prefer marking the outstanding debt at the current market price so a live-trued-up
            // quantity (e.g. 233.39 USDT incl. accrued interest) is valued consistently — for a USD
            // stable this pins to the outstanding quantity. Fall back to net borrowed−repaid basis,
            // then to the borrow-time unit price applied to the remaining quantity.
            BigDecimal currentPrice = resolvePrice(currentPrices, asset);
            BigDecimal outstandingUsd;
            String metricStatus;
            if (currentPrice != null && currentPrice.signum() > 0) {
                outstandingUsd = outstandingQty.multiply(currentPrice, MC);
                metricStatus = ACCOUNTING_ESTIMATE_SOURCE;
            } else {
                BigDecimal netBorrowedUsd = borrowedUsd.subtract(repaidUsd, MC);
                if (netBorrowedUsd.signum() > 0) {
                    outstandingUsd = netBorrowedUsd;
                    metricStatus = ACCOUNTING_ESTIMATE_SOURCE;
                } else if (borrowedQty.signum() > 0 && borrowedUsd.signum() > 0) {
                    outstandingUsd = outstandingQty.multiply(borrowedUsd.divide(borrowedQty, MC), MC);
                    metricStatus = LendingMarketRateStatus.FALLBACK_ESTIMATE;
                } else {
                    outstandingUsd = BigDecimal.ZERO;
                    metricStatus = LendingMarketRateStatus.FALLBACK_ESTIMATE;
                }
            }
            LendingMarketMetricEstimator.MetricSnapshot fallback = metricEstimator.estimate(
                    key.protocol(),
                    "BORROW",
                    asset,
                    supplyUsd,
                    borrowUsd
            );
            PositionRateMetric rateMetric = positionRateMetric(
                    key.protocol(),
                    key.networkId(),
                    marketKey,
                    "BORROW",
                    asset,
                    fallback
            );
            return new LendingPositionView(
                    cycleId + ":borrow:" + asset.toLowerCase(Locale.ROOT) + SYNTHETIC_BORROW_ID_SUFFIX,
                    marketKey,
                    "BORROW",
                    asset,
                    asset,
                    null,
                    outstandingQty,
                    BigDecimal.ZERO,
                    outstandingUsd,
                    BigDecimal.ZERO,
                    rateMetric.aliasApyPct(),
                    metricStatus,
                    ACCOUNTING_ESTIMATE_SOURCE,
                    rateMetric.protocolSupplyApyPct(),
                    rateMetric.protocolBorrowApyPct(),
                    rateMetric.rewardAprPct(),
                    rateMetric.netProtocolApyPct(),
                    rateMetric.protocolApyStatus(),
                    rateMetric.protocolApySource(),
                    rateMetric.protocolApyCapturedAt(),
                    rateMetric.protocolApyStale(),
                    rateMetric.rewardAprStatus(),
                    rateMetric.rewardAprUnavailableReason(),
                    rateMetric.apyConvention()
            );
        }

        /**
         * Reconstruct SUPPLY (collateral) positions for outstanding supply (deposited − withdrawn)
         * that is NOT backed by a live on-chain supply-receipt-token balance. Receipt-less networks
         * (Solana/TON) — e.g. Jupiter Lend and Kamino — deposit collateral without minting a fungible
         * receipt we can snapshot as an on_chain_balance, so the only evidence of open collateral is
         * the cycle's deposit/withdraw accounting. Without this, a loop shows borrow but no collateral,
         * driving supplyUsd=0, a bogus 0.0 health factor ("Liquidation risk"), and wrong net exposure.
         *
         * <p>Strictly guarded to non-EVM networks so EVM protocols (which DO surface aToken/cToken
         * supply balances) are never double-counted, and skips any asset that already has a live
         * SUPPLY position in this cycle.
         */
        private List<LendingPositionView> withSynthesizedOutstandingSupply(
                String cycleId,
                String marketKey,
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            if (!"OPEN".equals(status)) {
                return cyclePositions;
            }
            // EVM supply is represented by a live receipt-token balance; synthesizing there would
            // double-count. Only receipt-less networks need the accounting-derived collateral.
            if (!isReceiptLessLendingNetwork(history)) {
                return cyclePositions;
            }
            Map<String, BigDecimal> supplied = deltas.principalInByAsset();
            if (supplied.isEmpty()) {
                return cyclePositions;
            }
            Map<String, BigDecimal> withdrawn = deltas.principalOutByAsset();
            Map<String, BigDecimal> suppliedUsd = deltas.principalInUsdByAsset();
            Map<String, BigDecimal> withdrawnUsd = deltas.principalOutCashUsdByAsset();
            // WS-3 single-authority: when the live reader has a fresh collateral snapshot, it trues
            // up the synthesized collateral to the on-chain amount (e.g. 5.42 SOL instead of the
            // deltas-derived ~10 SOL that double-counts loop redeposits). Absent a fresh snapshot the
            // reader is treated as unreachable and the deltas figure remains a clearly-stale fallback.
            Map<String, BigDecimal> liveCollateralByAsset = liveCollateralByAsset();
            List<LendingPositionView> synthesized = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : supplied.entrySet()) {
                String asset = entry.getKey();
                // Net deposited principal (deposits − withdrawals). Includes loop redeposits, so on a
                // leveraged loop this is the whole collateral the wallet moved in. The accrued supply
                // yield is (live collateral − this principal), NOT the whole live collateral.
                BigDecimal accountingNetQty = zeroIfNull(entry.getValue())
                        .subtract(withdrawn.getOrDefault(asset, BigDecimal.ZERO), MC);
                BigDecimal outstandingQty = accountingNetQty;
                BigDecimal liveQty = liveCollateralByAsset.get(asset);
                if (liveQty != null && liveQty.signum() > 0) {
                    outstandingQty = liveQty;
                }
                if (outstandingQty.compareTo(CYCLE_DUST_TOLERANCE) <= 0) {
                    continue;
                }
                if (hasLiveSupplyPosition(cyclePositions, asset)) {
                    continue;
                }
                synthesized.add(synthesizedSupplyPosition(
                        cycleId, marketKey, asset, outstandingQty,
                        accountingNetQty.max(BigDecimal.ZERO),
                        zeroIfNull(entry.getValue()),
                        suppliedUsd.getOrDefault(asset, BigDecimal.ZERO),
                        withdrawnUsd.getOrDefault(asset, BigDecimal.ZERO)));
            }
            if (synthesized.isEmpty()) {
                return cyclePositions;
            }
            List<LendingPositionView> combined = new ArrayList<>(cyclePositions);
            combined.addAll(synthesized);
            return List.copyOf(combined);
        }

        /**
         * WS-3: fresh live collateral quantities keyed by cycle-state asset, or empty when no fresh
         * live snapshot exists (reader unreachable/stale → keep the guarded deltas-derived fallback).
         */
        private Map<String, BigDecimal> liveCollateralByAsset() {
            return liveLegsByAsset(LendingLivePositionSnapshot::getCollateral);
        }

        /**
         * WS-3: fresh live outstanding-debt quantities keyed by cycle-state asset (incl. accrued
         * interest, e.g. 233.39 USDT rather than the 210 borrow principal), or empty when no fresh
         * live snapshot exists. Symmetric to {@link #liveCollateralByAsset()} so the displayed borrow
         * and the group borrowUsd / health factor all reflect the same single-authority live debt.
         */
        private Map<String, BigDecimal> liveDebtByAsset() {
            return liveLegsByAsset(LendingLivePositionSnapshot::getDebt);
        }

        private Map<String, BigDecimal> liveLegsByAsset(
                java.util.function.Function<LendingLivePositionSnapshot, List<LendingLivePositionSnapshot.Leg>> legs
        ) {
            if (livePositionSnapshotService == null
                    || key.protocol() == null
                    || key.networkId() == null
                    || key.walletAddress() == null) {
                return Map.of();
            }
            return livePositionSnapshotService.latestFresh(
                            sessionId, key.protocol(), key.networkId().name(), key.walletAddress())
                    .map(snapshot -> {
                        Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
                        List<LendingLivePositionSnapshot.Leg> legList = legs.apply(snapshot);
                        if (legList != null) {
                            for (LendingLivePositionSnapshot.Leg leg : legList) {
                                if (leg.getAssetSymbol() == null || leg.getQuantity() == null) {
                                    continue;
                                }
                                byAsset.merge(cycleStateAsset(leg.getAssetSymbol()), leg.getQuantity(), BigDecimal::add);
                            }
                        }
                        return byAsset;
                    })
                    .orElse(Map.of());
        }

        private boolean hasLiveSupplyPosition(List<LendingPositionView> cyclePositions, String asset) {
            return cyclePositions.stream()
                    .filter(position -> "SUPPLY".equals(position.side()))
                    .filter(position -> zeroIfNull(position.quantity()).compareTo(CYCLE_DUST_TOLERANCE) > 0)
                    .anyMatch(position -> Objects.equals(cycleStateAsset(position.underlyingSymbol()), asset));
        }

        private LendingPositionView synthesizedSupplyPosition(
                String cycleId,
                String marketKey,
                String asset,
                BigDecimal outstandingQty,
                BigDecimal principalQty,
                BigDecimal suppliedQty,
                BigDecimal suppliedUsd,
                BigDecimal withdrawnUsd
        ) {
            // Prefer marking the outstanding collateral at the current market price so the health
            // factor and net exposure reflect live value; fall back to net deposit basis, then to
            // deposit-time unit price applied to the remaining quantity.
            BigDecimal currentPrice = resolvePrice(currentPrices, asset);
            BigDecimal outstandingUsd;
            String metricStatus;
            if (currentPrice != null && currentPrice.signum() > 0) {
                outstandingUsd = outstandingQty.multiply(currentPrice, MC);
                metricStatus = ACCOUNTING_ESTIMATE_SOURCE;
            } else {
                BigDecimal netSuppliedUsd = suppliedUsd.subtract(withdrawnUsd, MC);
                if (netSuppliedUsd.signum() > 0) {
                    outstandingUsd = netSuppliedUsd;
                    metricStatus = ACCOUNTING_ESTIMATE_SOURCE;
                } else if (suppliedQty.signum() > 0 && suppliedUsd.signum() > 0) {
                    outstandingUsd = outstandingQty.multiply(suppliedUsd.divide(suppliedQty, MC), MC);
                    metricStatus = LendingMarketRateStatus.FALLBACK_ESTIMATE;
                } else {
                    outstandingUsd = BigDecimal.ZERO;
                    metricStatus = LendingMarketRateStatus.FALLBACK_ESTIMATE;
                }
            }
            LendingMarketMetricEstimator.MetricSnapshot fallback = metricEstimator.estimate(
                    key.protocol(),
                    "SUPPLY",
                    asset,
                    supplyUsd,
                    borrowUsd
            );
            PositionRateMetric rateMetric = positionRateMetric(
                    key.protocol(),
                    key.networkId(),
                    marketKey,
                    "SUPPLY",
                    asset,
                    fallback
            );
            return new LendingPositionView(
                    cycleId + ":supply:" + asset.toLowerCase(Locale.ROOT) + SYNTHETIC_SUPPLY_ID_SUFFIX,
                    marketKey,
                    "SUPPLY",
                    asset,
                    asset,
                    null,
                    outstandingQty,
                    // Principal (net deposited) portion; supply yield = quantity − coveredQuantity, so
                    // only the accrued amount (not the whole leveraged collateral) drives factual APR.
                    principalQty.min(outstandingQty),
                    outstandingUsd,
                    BigDecimal.ZERO,
                    rateMetric.aliasApyPct(),
                    metricStatus,
                    ACCOUNTING_ESTIMATE_SOURCE,
                    rateMetric.protocolSupplyApyPct(),
                    rateMetric.protocolBorrowApyPct(),
                    rateMetric.rewardAprPct(),
                    rateMetric.netProtocolApyPct(),
                    rateMetric.protocolApyStatus(),
                    rateMetric.protocolApySource(),
                    rateMetric.protocolApyCapturedAt(),
                    rateMetric.protocolApyStale(),
                    rateMetric.rewardAprStatus(),
                    rateMetric.rewardAprUnavailableReason(),
                    rateMetric.apyConvention()
            );
        }

        private LendingFactualApyView factualApy(
                String status,
                LendingHistoryEntryView start,
                LendingHistoryEntryView close,
                LendingTotalValuationView totalValuation,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            Instant startTimestamp = start == null ? null : start.blockTimestamp();
            Instant endTimestamp = "OPEN".equals(status)
                    ? Instant.now()
                    : close == null ? null : close.blockTimestamp();
            Map<String, BigDecimal> currentDebtByAsset = cyclePositions.stream()
                    .filter(position -> "BORROW".equals(position.side()))
                    .collect(Collectors.toMap(
                            position -> cycleStateAsset(position.underlyingSymbol()),
                            LendingPositionView::quantity,
                            (left, right) -> left.add(right, MC),
                            LinkedHashMap::new
                    ));
            Map<String, BigDecimal> yieldEvidence = "OPEN".equals(status)
                    ? openSupplyIncomeByAsset(cyclePositions)
                    : resolvedSupplyYieldByAsset(deltas);
            LendingFactualApyView computed = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                    status,
                    startTimestamp,
                    endTimestamp,
                    deltas.timeWeightedSupplyPrincipalByAsset(startTimestamp, endTimestamp),
                    deltas.principalInByAsset(),
                    yieldEvidence,
                    deltas.principalOutCashByAsset(),
                    deltas.internalReceiptMovementByAsset(),
                    deltas.timeWeightedBorrowPrincipalByAsset(startTimestamp, endTimestamp),
                    deltas.borrowedByAsset(),
                    deltas.repaidByAsset(),
                    currentDebtByAsset,
                    null,
                    null
            ));
            if ("UNAVAILABLE".equals(computed.apyPrecision())) {
                return computed;
            }
            List<LendingFactualApyNetStrategySupport.PositionExposure> exposures = cyclePositions.stream()
                    .map(position -> new LendingFactualApyNetStrategySupport.PositionExposure(
                            position.underlyingSymbol(),
                            position.side(),
                            position.valueUsd()
                    ))
                    .toList();
            LendingFactualApyNetStrategySupport.NetStrategyRates netStrategy =
                    LendingFactualApyNetStrategySupport.blend(
                            computed.factualSupplyAprByAsset(),
                            computed.factualSupplyApyByAsset(),
                            computed.factualBorrowAprByAsset(),
                            computed.factualBorrowApyByAsset(),
                            exposures,
                            LendingCycleBuilder::cycleStateAsset
                    );
            return new LendingFactualApyView(
                    computed.factualSupplyAprByAsset(),
                    computed.factualSupplyApyByAsset(),
                    computed.factualBorrowAprByAsset(),
                    computed.factualBorrowApyByAsset(),
                    netStrategy.netStrategyAprPct(),
                    netStrategy.netStrategyApyPct(),
                    computed.apyPrecision(),
                    computed.apyMethod(),
                    computed.apyUnavailableReason(),
                    computed.apyConvention()
            );
        }

        private LendingTotalValuationView totalValuation(
                String status,
                BigDecimal currentUsdValue,
                BigDecimal liveOutstandingBorrowUsd,
                LendingPnlBreakdownView yieldOnlyPnl,
                DeltaAccumulator deltas
        ) {
            return LendingCycleValuationCalculator.calculate(new LendingCycleValuationCalculator.Input(
                    status,
                    deltas.principalInUsd(),
                    deltas.principalOutCashUsd(),
                    deltas.borrowedUsd(),
                    deltas.repaidUsd(),
                    deltas.rewardUsd(),
                    deltas.feesUsd(),
                    deltas.feesUsd(),
                    currentUsdValue,
                    liveOutstandingBorrowUsd,
                    yieldOnlyPnl.netPnlUsd(),
                    yieldOnlyPnl.precision(),
                    deltas.hasWrapperOrShareExposure(),
                    deltas.hasMissingEventValuation(),
                    deltas.hasMissingGasUsdValuation(),
                    "CLOSED".equals(status) && deltas.hasUnresolvedPrincipalExitByAsset()
            ));
        }

        private String primaryAssetPnlSummary(LendingPnlAssetBreakdownView pnlAssetBreakdown) {
            return pnlAssetBreakdown.netIncomeByAsset().entrySet().stream()
                    .filter(entry -> entry.getValue() != null && entry.getValue().signum() != 0)
                    .max(Comparator.comparing(entry -> entry.getValue().abs()))
                    .map(entry -> formatAssetQuantity(entry.getValue()) + " " + entry.getKey())
                    .orElse(null);
        }

        private String formatAssetQuantity(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.signum() > 0 ? "+" + stripped.toPlainString() : stripped.toPlainString();
        }

        private List<String> largePnlReasons(
                String status,
                LendingPnlBreakdownView pnlBreakdown,
                LendingTotalValuationView totalValuation,
                LendingPnlAssetBreakdownView pnlAssetBreakdown,
                DeltaAccumulator deltas
        ) {
            if (!"CLOSED".equals(status)
                    || totalValuation.totalUsdPnl() == null
                    || totalValuation.totalUsdPnl().compareTo(new BigDecimal("-100")) >= 0) {
                return List.of();
            }
            LinkedHashSet<String> reasons = new LinkedHashSet<>();
            if (totalValuation.unavailableReason() != null) {
                reasons.add("MISSING_PRICE");
            }
            if (deltas.hasUnresolvedPrincipalExitByAsset()) {
                reasons.add("LIFECYCLE_LINKING_GAP");
            }
            if (deltas.hasInternalReceiptMovement()) {
                reasons.add("SHARE_RATE_EFFECT");
            } else if (deltas.hasNonStablePrincipalExposure()) {
                reasons.add("REALIZED_MARKET_MOVE");
            }
            if (pnlBreakdown.interestPaidUsd() != null
                    && pnlBreakdown.interestPaidUsd().compareTo(BigDecimal.ZERO) > 0) {
                reasons.add("BORROW_COST");
            }
            if (pnlBreakdown.gasUsd() != null && pnlBreakdown.gasUsd().compareTo(BigDecimal.ZERO) > 0
                    || pnlAssetBreakdown.gasByAsset().values().stream().anyMatch(value -> value.signum() > 0)) {
                reasons.add("GAS_COST");
            }
            return reasons.stream()
                    .sorted(Comparator.comparingInt(this::largePnlReasonPriority))
                    .toList();
        }

        private int largePnlReasonPriority(String reason) {
            return switch (reason) {
                case "LIFECYCLE_LINKING_GAP" -> 1;
                case "MIGRATION_UNRESOLVED" -> 2;
                case "MISSING_PRICE" -> 3;
                case "SHARE_RATE_UNAVAILABLE" -> 4;
                case "SHARE_RATE_EFFECT" -> 5;
                case "REALIZED_MARKET_MOVE" -> 6;
                case "BORROW_COST" -> 7;
                case "GAS_COST" -> 8;
                default -> 100;
            };
        }

        private String statusDetail(String status, DeltaAccumulator deltas) {
            String warning = warningReason(status, deltas);
            if ("CLOSED".equals(status) && warning != null) {
                return "closed/current-state-zero";
            }
            return status == null ? null : status.toLowerCase(Locale.ROOT);
        }

        private String warningReason(String status, DeltaAccumulator deltas) {
            if (!"CLOSED".equals(status)) {
                return null;
            }
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            if (normalizedProtocol.startsWith("FLUID") && key.networkId() == NetworkId.PLASMA) {
                if (!deltas.hasFluidLogOperateEvidence()) {
                    return "pnl_unavailable_missing_full_receipt_logs";
                }
                if (deltas.hasWstUsrExposure()) {
                    return "pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy";
                }
                return null;
            }
            boolean currentStateZeroClosureCanMissPrincipalExit = normalizedProtocol.startsWith("COMPOUND")
                    || normalizedProtocol.startsWith("FLUID")
                    || normalizedProtocol.startsWith("EULER")
                    || normalizedProtocol.startsWith("MORPHO");
            if (!currentStateZeroClosureCanMissPrincipalExit) {
                return null;
            }
            return deltas.hasUnresolvedPrincipalExitByAsset() ? "unresolved_principal_exit" : null;
        }

        private String pnlUnavailableReason(String status, DeltaAccumulator deltas) {
            if ("AMBIGUOUS_NEEDS_REVIEW".equals(status)) {
                return "unavailable:unresolved lifecycle";
            }
            String warning = warningReason(status, deltas);
            if ("pnl_unavailable_missing_full_receipt_logs".equals(warning)) {
                return "unavailable:pnl_unavailable_missing_full_receipt_logs";
            }
            if ("pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy".equals(warning)) {
                return "unavailable:pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy";
            }
            if ("CLOSED".equals(status) && deltas.hasIncompletePrincipalExit()) {
                return "unavailable:unresolved_principal_exit";
            }
            if (containsShareOrVaultAsset(deltas)) {
                if (resolvedSupplyYieldByAsset(deltas).isEmpty()) {
                    return "unavailable:missing share-rate or historical price evidence";
                }
            }
            if (deltas.hasNonStablePrincipalExposure()) {
                return "unavailable:missing yield-only valuation evidence";
            }
            if ("CLOSED".equals(status) && resolvedSupplyYieldByAsset(deltas).isEmpty()) {
                return "unavailable:missing yield-only valuation evidence";
            }
            return "asset-delta-only";
        }

        private Map<String, BigDecimal> resolvedSupplyYieldByAsset(DeltaAccumulator deltas) {
            Map<String, BigDecimal> merged = new LinkedHashMap<>(deltas.withdrawYieldByAsset());
            for (Map.Entry<String, BigDecimal> entry : inferPlausibleShareYieldByAsset(deltas).entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return Map.copyOf(merged);
        }

        private Map<String, BigDecimal> inferPlausibleShareYieldByAsset(DeltaAccumulator deltas) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            BigDecimal maxYieldRatio = new BigDecimal("0.10");
            for (Map.Entry<String, BigDecimal> entry : deltas.principalInByAsset().entrySet()) {
                String asset = entry.getKey();
                BigDecimal existingYield = deltas.withdrawYieldByAsset().get(asset);
                if (existingYield != null && existingYield.signum() > 0) {
                    continue;
                }
                BigDecimal principalIn = zeroIfNull(entry.getValue());
                BigDecimal outCash = zeroIfNull(deltas.principalOutCashByAsset().get(asset));
                BigDecimal diff = outCash.subtract(principalIn, MC);
                if (principalIn.signum() <= 0 || diff.signum() <= 0) {
                    continue;
                }
                if (diff.divide(principalIn, MC).compareTo(maxYieldRatio) <= 0) {
                    result.put(asset, diff);
                }
            }
            return result;
        }

        private BigDecimal closedSupplyIncomeUsd(DeltaAccumulator deltas) {
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> entry : resolvedSupplyYieldByAsset(deltas).entrySet()) {
                BigDecimal quantity = entry.getValue();
                if (quantity == null || quantity.signum() <= 0) {
                    continue;
                }
                if (LendingAssetSymbolSupport.isStable(entry.getKey())) {
                    total = total.add(quantity, MC);
                }
            }
            return total;
        }

        private LendingPnlBreakdownView pnlBreakdown(
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas
        ) {
            BigDecimal openEarnedUsd = "OPEN".equals(status)
                    ? cyclePositions.stream()
                    .map(LendingPositionView::earnedUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC))
                    : BigDecimal.ZERO;
            BigDecimal closedEarnedUsd = "OPEN".equals(status)
                    ? BigDecimal.ZERO
                    : resolvedSupplyYieldByAsset(deltas).isEmpty()
                    ? BigDecimal.ZERO
                    : closedSupplyIncomeUsd(deltas).add(deltas.rewardUsd(), MC);
            BigDecimal interestEarnedUsd = openEarnedUsd.add(closedEarnedUsd, MC);
            BigDecimal interestPaidUsd = deltas.repaidUsd()
                    .subtract(deltas.borrowedUsd(), MC)
                    .max(BigDecimal.ZERO);
            BigDecimal gasUsd = deltas.feesUsd();
            String unavailableReason = pnlUnavailableReason(status, deltas);
            boolean unavailable = unavailableReason.startsWith("unavailable:");
            BigDecimal netPnlUsd = unavailable
                    ? null
                    : interestEarnedUsd.subtract(interestPaidUsd, MC).subtract(gasUsd, MC);
            String precision = unavailable ? "UNAVAILABLE" : ("OPEN".equals(status) ? "ESTIMATED" : "ESTIMATED");
            String method = unavailable ? unavailableReason : "interest-earned-minus-paid-minus-gas";
            String reason = unavailable ? unavailableReason.substring("unavailable:".length()) : null;
            return new LendingPnlBreakdownView(
                    unavailable ? null : interestEarnedUsd,
                    unavailable ? null : interestPaidUsd,
                    gasUsd,
                    netPnlUsd,
                    precision,
                    method,
                    reason
            );
        }

        private LendingPnlAssetBreakdownView pnlAssetBreakdown(
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas,
                LendingTotalValuationView totalValuation
        ) {
            boolean assetPnlUnavailable = "AMBIGUOUS_NEEDS_REVIEW".equals(status)
                    || "CLOSED".equals(status) && (
                    deltas.hasUnresolvedPrincipalExitByAsset()
                            || "pnl_unavailable_missing_full_receipt_logs".equals(warningReason(status, deltas))
            );
            Map<String, BigDecimal> supplyIncomeByAsset = "OPEN".equals(status)
                    ? openSupplyIncomeByAsset(cyclePositions)
                    : assetPnlUnavailable
                    ? Map.of()
                    : positiveEntries(resolvedSupplyYieldByAsset(deltas));
            Map<String, BigDecimal> borrowCostByAsset = positiveEntries(subtractMaps(
                    deltas.repaidByAsset(),
                    deltas.borrowedByAsset()
            ));
            Map<String, BigDecimal> rewardsByAsset = positiveEntries(deltas.rewardByAsset());
            Map<String, BigDecimal> gasByAsset = positiveEntries(deltas.gasByAsset());
            Map<String, BigDecimal> netIncomeByAsset = netIncomeByAsset(
                    supplyIncomeByAsset,
                    borrowCostByAsset,
                    rewardsByAsset,
                    gasByAsset
            );
            Map<String, String> precisionByAsset = precisionByAsset(status, deltas, netIncomeByAsset.keySet());
            Map<String, String> reasonByAsset = reasonByAsset(status, deltas, precisionByAsset.keySet());
            UsdAssetBreakdown usd = pnlAssetUsdBreakdown(status, cyclePositions, deltas, totalValuation);
            return new LendingPnlAssetBreakdownView(
                    Map.copyOf(supplyIncomeByAsset),
                    Map.copyOf(borrowCostByAsset),
                    Map.copyOf(rewardsByAsset),
                    Map.copyOf(gasByAsset),
                    Map.copyOf(netIncomeByAsset),
                    Map.copyOf(precisionByAsset),
                    Map.copyOf(reasonByAsset),
                    usd.supplyPnlUsdByAsset(),
                    usd.borrowPnlUsdByAsset(),
                    usd.rewardsUsdByAsset(),
                    usd.gasUsdByAsset(),
                    usd.netIncomeUsdByAsset(),
                    usd.usdPrecisionByAsset()
            );
        }

        /**
         * Per-asset USD P&L via cashflow decomposition, reconciling EXACTLY to the cycle's
         * published total (LendingCycleValuationCalculator):
         * <pre>
         * CLOSED: net[a] = (principalOutCashUsd[a] - principalInUsd[a])   // supply leg
         *               + (borrowedUsd[a]        - repaidUsd[a])          // borrow leg (negative = net interest cost)
         *               +  rewardUsd[a]                                   // rewards
         *               -  gasUsd[a]                                      // per-asset gas
         * OPEN:  adds the current position USD value per asset (supply/borrow side) so that
         *        Sigma net[a] == unrealizedTotalUsdPnl (= totalUsdPnl + currentUsdValue).
         * </pre>
         * The supply leg uses {@code principalOutCashUsd} (not gross principalOut) because the
         * published total is built from cash exits only; mirroring it keeps the cross-foot exact.
         * Returns empty maps when the cycle total is UNAVAILABLE (never emits $0 placeholders).
         */
        private UsdAssetBreakdown pnlAssetUsdBreakdown(
                String status,
                List<LendingPositionView> cyclePositions,
                DeltaAccumulator deltas,
                LendingTotalValuationView totalValuation
        ) {
            boolean open = "OPEN".equals(status);
            BigDecimal cycleTotal = open
                    ? totalValuation.unrealizedTotalUsdPnl()
                    : totalValuation.totalUsdPnl();
            if (cycleTotal == null) {
                return UsdAssetBreakdown.empty();
            }
            Map<String, BigDecimal> principalInUsd = deltas.principalInUsdByAsset();
            Map<String, BigDecimal> principalOutCashUsd = deltas.principalOutCashUsdByAsset();
            Map<String, BigDecimal> borrowedUsd = deltas.borrowedUsdByAsset();
            Map<String, BigDecimal> repaidUsd = deltas.repaidUsdByAsset();
            Map<String, BigDecimal> rewardUsd = deltas.rewardUsdByAsset();
            Map<String, BigDecimal> gasUsd = deltas.gasUsdByAsset();
            Map<String, BigDecimal> currentSupplyUsd = open
                    ? currentPositionUsdByAsset(cyclePositions, "SUPPLY")
                    : Map.of();
            Map<String, BigDecimal> currentBorrowUsd = open
                    ? currentPositionUsdByAsset(cyclePositions, "BORROW")
                    : Map.of();

            Map<String, BigDecimal> supplyPnlUsd = new LinkedHashMap<>();
            for (String asset : unionKeys(currentSupplyUsd, principalOutCashUsd, principalInUsd)) {
                BigDecimal value = currentSupplyUsd.getOrDefault(asset, BigDecimal.ZERO)
                        .add(principalOutCashUsd.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .subtract(principalInUsd.getOrDefault(asset, BigDecimal.ZERO), MC);
                if (value.signum() != 0) {
                    supplyPnlUsd.put(asset, value);
                }
            }
            Map<String, BigDecimal> borrowPnlUsd = new LinkedHashMap<>();
            if (open) {
                for (String asset : currentBorrowUsd.keySet()) {
                    BigDecimal outstanding = borrowedUsd.getOrDefault(asset, BigDecimal.ZERO)
                            .subtract(repaidUsd.getOrDefault(asset, BigDecimal.ZERO), MC)
                            .max(BigDecimal.ZERO);
                    BigDecimal interest = outstanding.subtract(currentBorrowUsd.get(asset), MC);
                    if (interest.signum() != 0) {
                        borrowPnlUsd.put(asset, interest);
                    }
                }
            } else {
                for (String asset : unionKeys(currentBorrowUsd, borrowedUsd, repaidUsd)) {
                    BigDecimal value = currentBorrowUsd.getOrDefault(asset, BigDecimal.ZERO)
                            .add(borrowedUsd.getOrDefault(asset, BigDecimal.ZERO), MC)
                            .subtract(repaidUsd.getOrDefault(asset, BigDecimal.ZERO), MC);
                    if (value.signum() != 0) {
                        borrowPnlUsd.put(asset, value);
                    }
                }
            }
            Map<String, BigDecimal> rewardsUsd = nonZeroEntries(rewardUsd);
            Map<String, BigDecimal> gasUsdOut = nonZeroEntries(gasUsd);

            Map<String, BigDecimal> netIncomeUsd = new LinkedHashMap<>();
            for (String asset : unionKeys(supplyPnlUsd, borrowPnlUsd, rewardsUsd, gasUsdOut)) {
                BigDecimal value = supplyPnlUsd.getOrDefault(asset, BigDecimal.ZERO)
                        .add(borrowPnlUsd.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .add(rewardsUsd.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .subtract(gasUsdOut.getOrDefault(asset, BigDecimal.ZERO), MC);
                if (value.signum() != 0) {
                    netIncomeUsd.put(asset, value);
                }
            }
            String basePrecision = totalValuation.totalUsdPnlPrecision();
            Set<String> degraded = deltas.usdMissingValuationAssets();
            Map<String, String> usdPrecision = new LinkedHashMap<>();
            for (String asset : netIncomeUsd.keySet()) {
                usdPrecision.put(asset, degraded.contains(asset) ? "ESTIMATED" : basePrecision);
            }
            return new UsdAssetBreakdown(
                    Map.copyOf(supplyPnlUsd),
                    Map.copyOf(borrowPnlUsd),
                    Map.copyOf(rewardsUsd),
                    Map.copyOf(gasUsdOut),
                    Map.copyOf(netIncomeUsd),
                    Map.copyOf(usdPrecision)
            );
        }

        private Map<String, BigDecimal> currentPositionUsdByAsset(
                List<LendingPositionView> positions,
                String side
        ) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (LendingPositionView position : positions) {
                if (!side.equals(position.side())) {
                    continue;
                }
                BigDecimal value = zeroIfNull(position.valueUsd());
                if (value.signum() == 0) {
                    continue;
                }
                String asset = cycleStateAsset(position.underlyingSymbol());
                result.merge(asset, value, (left, right) -> left.add(right, MC));
            }
            return result;
        }

        private Map<String, BigDecimal> nonZeroEntries(Map<String, BigDecimal> source) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
                if (entry.getValue() != null && entry.getValue().signum() != 0) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }

        private Map<String, List<LendingObservedFlowView>> observedFlowsByAsset(
                String status,
                DeltaAccumulator deltas
        ) {
            String reason = pnlUnavailableReason(status, deltas);
            if (!reason.startsWith("unavailable:")) {
                return Map.of();
            }
            return deltas.observedFlowsByAsset(reason.substring("unavailable:".length()));
        }

        private Map<String, BigDecimal> openSupplyIncomeByAsset(List<LendingPositionView> cyclePositions) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (LendingPositionView position : cyclePositions) {
                if (!"SUPPLY".equals(position.side())) {
                    continue;
                }
                BigDecimal income = zeroIfNull(position.quantity())
                        .subtract(zeroIfNull(position.coveredQuantity()), MC);
                if (income.signum() <= 0) {
                    continue;
                }
                String asset = cycleStateAsset(position.underlyingSymbol());
                result.merge(asset, income, (left, right) -> left.add(right, MC));
            }
            return result;
        }

        private Map<String, BigDecimal> subtractMaps(
                Map<String, BigDecimal> left,
                Map<String, BigDecimal> right
        ) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (String asset : unionKeys(left, right)) {
                BigDecimal value = left.getOrDefault(asset, BigDecimal.ZERO)
                        .subtract(right.getOrDefault(asset, BigDecimal.ZERO), MC);
                if (value.signum() != 0) {
                    result.put(asset, value);
                }
            }
            return result;
        }

        private Map<String, BigDecimal> positiveEntries(Map<String, BigDecimal> source) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
                if (entry.getValue() != null && entry.getValue().signum() > 0) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }

        private Map<String, BigDecimal> netIncomeByAsset(
                Map<String, BigDecimal> supplyIncomeByAsset,
                Map<String, BigDecimal> borrowCostByAsset,
                Map<String, BigDecimal> rewardsByAsset,
                Map<String, BigDecimal> gasByAsset
        ) {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (String asset : unionKeys(supplyIncomeByAsset, borrowCostByAsset, rewardsByAsset, gasByAsset)) {
                BigDecimal value = supplyIncomeByAsset.getOrDefault(asset, BigDecimal.ZERO)
                        .add(rewardsByAsset.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .subtract(borrowCostByAsset.getOrDefault(asset, BigDecimal.ZERO), MC)
                        .subtract(gasByAsset.getOrDefault(asset, BigDecimal.ZERO), MC);
                if (value.signum() != 0) {
                    result.put(asset, value);
                }
            }
            return result;
        }

        @SafeVarargs
        private final Set<String> unionKeys(Map<String, BigDecimal>... sources) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Map<String, BigDecimal> source : sources) {
                keys.addAll(source.keySet());
            }
            return keys;
        }

        private Map<String, String> precisionByAsset(
                String status,
                DeltaAccumulator deltas,
                Set<String> derivedAssets
        ) {
            LinkedHashSet<String> assets = new LinkedHashSet<>(derivedAssets);
            assets.addAll(deltas.incompletePrincipalExitAssets());
            assets.addAll(deltas.openingDepositByAsset().keySet());
            Set<String> inferredYieldAssets = inferredSupplyYieldAssets(deltas);
            Map<String, String> precision = new LinkedHashMap<>();
            for (String asset : assets) {
                if (assetPnlUnavailableReason(status, deltas, asset) != null) {
                    precision.put(asset, "UNAVAILABLE");
                } else if ("OPEN".equals(status)) {
                    precision.put(asset, "ESTIMATED");
                } else if (inferredYieldAssets.contains(asset)) {
                    // C-2: CLOSED supply yield resolved from an unreconciled (inferred,
                    // not directly observed) withdrawYield flow is ESTIMATED, not EXACT.
                    precision.put(asset, "ESTIMATED");
                } else {
                    precision.put(asset, "EXACT");
                }
            }
            return precision;
        }

        /**
         * CLOSED assets whose supply yield was resolved only via the heuristic
         * {@code inferPlausibleShareYieldByAsset} path (not backed by a directly observed
         * withdraw-yield BUY flow). Such yield is unreconciled and must not be labeled EXACT.
         */
        private Set<String> inferredSupplyYieldAssets(DeltaAccumulator deltas) {
            Set<String> inferred = new LinkedHashSet<>(resolvedSupplyYieldByAsset(deltas).keySet());
            inferred.removeAll(deltas.withdrawYieldByAsset().keySet());
            return inferred;
        }

        private Map<String, String> reasonByAsset(
                String status,
                DeltaAccumulator deltas,
                Set<String> assets
        ) {
            Map<String, String> reasons = new LinkedHashMap<>();
            for (String asset : assets) {
                String reason = assetPnlUnavailableReason(status, deltas, asset);
                if (reason != null) {
                    reasons.put(asset, reason);
                }
            }
            return reasons;
        }

        private String assetPnlUnavailableReason(String status, DeltaAccumulator deltas, String asset) {
            if ("AMBIGUOUS_NEEDS_REVIEW".equals(status)) {
                return "unresolved lifecycle";
            }
            if ("CLOSED".equals(status)
                    && "pnl_unavailable_missing_full_receipt_logs".equals(warningReason(status, deltas))) {
                return "pnl_unavailable_missing_full_receipt_logs";
            }
            if ("CLOSED".equals(status)
                    && "pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy".equals(warningReason(status, deltas))) {
                return "pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy";
            }
            if ("CLOSED".equals(status) && deltas.incompletePrincipalExitAssets().contains(asset)) {
                return "unresolved_principal_exit";
            }
            if ("CLOSED".equals(status)
                    && deltas.openingDepositByAsset().containsKey(asset)
                    && !resolvedSupplyYieldByAsset(deltas).containsKey(asset)) {
                return LendingFactualApyCalculator.NO_YIELD_FLOW_EVIDENCE;
            }
            return null;
        }

        private CyclePeakView cyclePeaks(List<LendingHistoryEntryView> events) {
            BigDecimal currentSupply = BigDecimal.ZERO;
            BigDecimal currentBorrow = BigDecimal.ZERO;
            BigDecimal peakSupply = BigDecimal.ZERO;
            BigDecimal peakBorrow = BigDecimal.ZERO;
            for (LendingHistoryEntryView event : events) {
                BigDecimal valueUsd = zeroIfNull(event.valueUsd());
                switch (event.type()) {
                    case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN" ->
                            currentSupply = currentSupply.add(valueUsd, MC);
                    case "LENDING_WITHDRAW", "VAULT_WITHDRAW", "LENDING_LOOP_DECREASE", "LENDING_LOOP_CLOSE" ->
                            currentSupply = currentSupply.subtract(valueUsd, MC).max(BigDecimal.ZERO);
                    case "BORROW" -> currentBorrow = currentBorrow.add(valueUsd, MC);
                    case "REPAY" -> currentBorrow = currentBorrow.subtract(valueUsd, MC).max(BigDecimal.ZERO);
                    default -> {
                    }
                }
                peakSupply = peakSupply.max(currentSupply);
                peakBorrow = peakBorrow.max(currentBorrow);
            }
            return new CyclePeakView(peakSupply, peakBorrow);
        }

        private Long durationDays(LendingHistoryEntryView start, LendingHistoryEntryView close) {
            if (start == null || start.blockTimestamp() == null) {
                return null;
            }
            Instant end = close == null || close.blockTimestamp() == null ? Instant.now() : close.blockTimestamp();
            return Math.max(0L, Duration.between(start.blockTimestamp(), end).toDays());
        }

        private List<LendingTxGroupView> txGroups(String cycleId, List<LendingHistoryEntryView> events) {
            List<LendingHistoryEntryView> ordered = deduplicateDisplayEvents(events.stream()
                    .sorted(Comparator.comparing(LendingHistoryEntryView::blockTimestamp))
                    .toList());
            List<LendingTxGroupView> groups = new ArrayList<>();
            int index = 0;
            int groupIndex = 1;
            while (index < ordered.size()) {
                LendingHistoryEntryView event = ordered.get(index);
                if (isLoopStart(ordered, index)) {
                    List<LendingHistoryEntryView> loopEvents = new ArrayList<>();
                    while (index < ordered.size()) {
                        LendingHistoryEntryView candidate = ordered.get(index);
                        if (candidate.type().startsWith("LENDING_LOOP")) {
                            loopEvents.add(candidate);
                            index++;
                            continue;
                        }
                        if (isBorrowSupplyPair(ordered, index)) {
                            loopEvents.add(ordered.get(index));
                            loopEvents.add(ordered.get(index + 1));
                            index += 2;
                            continue;
                        }
                        break;
                    }
                    groups.add(txGroup(cycleId, groupIndex++, "loop", loopEvents));
                    continue;
                }
                groups.add(txGroup(cycleId, groupIndex++, groupType(event), List.of(event)));
                index++;
            }
            return groups;
        }

        private boolean isLoopStart(List<LendingHistoryEntryView> events, int index) {
            LendingHistoryEntryView event = events.get(index);
            return event.type().startsWith("LENDING_LOOP") || isBorrowSupplyPair(events, index);
        }

        private boolean isBorrowSupplyPair(List<LendingHistoryEntryView> events, int index) {
            if (index >= events.size() - 1) {
                return false;
            }
            LendingHistoryEntryView borrow = events.get(index);
            LendingHistoryEntryView supply = events.get(index + 1);
            if (!"BORROW".equals(borrow.type()) || !isOpeningEvent(supply)) {
                return false;
            }
            if (borrow.blockTimestamp() == null || supply.blockTimestamp() == null) {
                return false;
            }
            Duration gap = Duration.between(borrow.blockTimestamp(), supply.blockTimestamp()).abs();
            return !gap.minus(LOOP_GROUP_WINDOW).isPositive();
        }

        private LendingTxGroupView txGroup(
                String cycleId,
                int groupIndex,
                String type,
                List<LendingHistoryEntryView> events
        ) {
            List<LendingTxItemView> items = deduplicateDisplayEvents(events).stream().map(this::txItem).toList();
            Instant timestamp = events.isEmpty() ? null : events.get(0).blockTimestamp();
            return new LendingTxGroupView(
                    cycleId + ":group-" + groupIndex,
                    type,
                    timestamp,
                    timestamp == null ? null : timestamp.toString(),
                    "loop".equals(type) ? loopSteps(events) : null,
                    "loop".equals(type) ? loopAsset(events, false) : null,
                    "loop".equals(type) ? loopAsset(events, true) : null,
                    items
            );
        }

        private List<LendingHistoryEntryView> deduplicateDisplayEvents(List<LendingHistoryEntryView> events) {
            if (!isFluidProtocol()) {
                return events;
            }
            LinkedHashMap<String, LendingHistoryEntryView> unique = new LinkedHashMap<>();
            for (LendingHistoryEntryView event : events) {
                String key = "REPAY".equals(event.type())
                        ? String.join("|",
                        nullToEmpty(event.txHash()),
                        nullToEmpty(event.marketKey()),
                        nullToEmpty(cycleStateAsset(event.assetSymbol())),
                        zeroIfNull(event.quantity()).stripTrailingZeros().toPlainString())
                        : event.id();
                unique.putIfAbsent(key, event);
            }
            return new ArrayList<>(unique.values());
        }

        private LendingTxItemView txItem(LendingHistoryEntryView event) {
            return new LendingTxItemView(
                    event.id(),
                    event.type(),
                    event.displayType(),
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.txHash(),
                    event.blockTimestamp()
            );
        }

        private Integer loopSteps(List<LendingHistoryEntryView> events) {
            long borrowSteps = events.stream().filter(event -> "BORROW".equals(event.type())).count();
            if (borrowSteps > 0) {
                return Math.toIntExact(borrowSteps);
            }
            return Math.max(1, events.size());
        }

        private String loopAsset(List<LendingHistoryEntryView> events, boolean supplySide) {
            String label = events.stream()
                    .filter(event -> supplySide == isOpeningEvent(event))
                    .map(LendingHistoryEntryView::assetSymbol)
                    .map(LendingCycleBuilder::cycleStateAsset)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return label.isBlank() ? null : label;
        }

        private String groupType(LendingHistoryEntryView event) {
            if (isOpeningEvent(event)) {
                return "open";
            }
            if ("BORROW".equals(event.type())) {
                return "borrow";
            }
            if ("REWARD_CLAIM".equals(event.type())) {
                return "reward";
            }
            if (isClosingEvent(event)) {
                return "close";
            }
            return "mid";
        }

        private LendingHistoryEntryView eventWithCycle(LendingHistoryEntryView event, String cycleId) {
            return new LendingHistoryEntryView(
                    event.id(),
                    event.txHash(),
                    event.marketKey(),
                    cycleId,
                    event.networkId(),
                    event.walletAddress(),
                    event.blockTimestamp(),
                    event.type(),
                    event.eventSubtype(),
                    event.displayType(),
                    event.assetSymbol(),
                    event.quantity(),
                    event.valueUsd(),
                    event.feeUsd(),
                    event.feeQuantityByAsset(),
                    event.loopId(),
                    event.withdrawYieldByAsset(),
                    event.receiptBearingCollateral()
            );
        }

        private String marketLabel(String marketKey) {
            int index = marketKey == null ? -1 : marketKey.lastIndexOf(':');
            return index < 0 ? marketKey : marketKey.substring(index + 1);
        }

        private boolean isClosingEvent(LendingHistoryEntryView event) {
            return "LENDING_WITHDRAW".equals(event.type())
                    || "VAULT_WITHDRAW".equals(event.type())
                    || "REPAY".equals(event.type())
                    || "LENDING_LOOP_CLOSE".equals(event.type())
                    || "LENDING_LOOP_DECREASE".equals(event.type());
        }

        private boolean isUnmatchedClosingEvent(CycleState state, LendingHistoryEntryView event) {
            String asset = cycleStateAsset(event.assetSymbol());
            if (isFluidProtocol() && isClosingEvent(event) && (state.hasAnySupply() || state.hasAnyDebt())) {
                return false;
            }
            if (isEulerProtocol()
                    && isClosingEvent(event)
                    && LendingAssetSymbolSupport.isLendingPositionSymbol(event.assetSymbol())
                    && (state.hasAnySupply() || state.hasAnyDebt())) {
                return false;
            }
            if (isMorphoProtocol()
                    && isClosingEvent(event)
                    && (state.hasAnySupply() || state.hasAnyDebt())) {
                return false;
            }
            if ("REPAY".equals(event.type())) {
                return !state.hasPositiveDebt(asset) && !state.hasAnyDebt();
            }
            if ("LENDING_WITHDRAW".equals(event.type())
                    || "VAULT_WITHDRAW".equals(event.type())
                    || "LENDING_LOOP_CLOSE".equals(event.type())
                    || "LENDING_LOOP_DECREASE".equals(event.type())) {
                return !state.hasPositiveSupply(asset);
            }
            return false;
        }

        private boolean isFluidProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("FLUID");
        }

        private boolean isMorphoProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("MORPHO");
        }

        private boolean isCompoundProtocol() {
            String normalizedProtocol = key.protocol() == null ? "" : key.protocol().trim().toUpperCase(Locale.ROOT);
            return normalizedProtocol.startsWith("COMPOUND");
        }

        private boolean isOpeningEvent(LendingHistoryEntryView event) {
            return "LENDING_DEPOSIT".equals(event.type())
                    || "VAULT_DEPOSIT".equals(event.type())
                    || "LENDING_LOOP_OPEN".equals(event.type());
        }

        private boolean containsShareOrVaultAsset(DeltaAccumulator deltas) {
            return deltas.hasShareOrVaultAsset();
        }

        private boolean isBorrowPosition(OnChainBalance balance, AssetLedgerPoint point) {
            return LendingAssetSymbolSupport.isBorrowSymbol(balance.getAssetSymbol())
                    || point != null && (NormalizedTransactionType.BORROW.name().equals(point.getNormalizedType())
                    || NormalizedTransactionType.REPAY.name().equals(point.getNormalizedType()));
        }

        private List<HistoryAmount> historyAmounts(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
        ) {
            List<HistoryAmount> protocolChildLegAmounts = protocolChildLegAmounts(transaction, prices, historicalPrices);
            if (!protocolChildLegAmounts.isEmpty()) {
                return protocolChildLegAmounts;
            }
            List<HistoryAmount> compoundLoopAmounts = compoundLoopAmounts(transaction, prices, historicalPrices);
            if (!compoundLoopAmounts.isEmpty()) {
                return compoundLoopAmounts;
            }

            Optional<NormalizedTransaction.Flow> primary = preferredHistoryFlow(transaction)
                    .or(() -> safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                    .filter(flow -> flow.getQuantityDelta() != null)
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs())));
            String type = transaction.getType() == null ? "UNKNOWN" : transaction.getType().name();
            String eventSubtype = eventSubtype(transaction);
            if (primary.isEmpty()) {
                return List.of(new HistoryAmount(
                        null,
                        type,
                        eventSubtype,
                        displayType(transaction.getType(), eventSubtype),
                        "UNKNOWN",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        Map.of(),
                        Map.of()
                ));
            }
            NormalizedTransaction.Flow flow = primary.get();
            BigDecimal feeUsd = safeFlows(transaction).stream()
                    .filter(candidate -> candidate.getRole() == NormalizedLegRole.FEE)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
            Map<String, BigDecimal> feeQuantityByAsset = feeQuantityByAsset(transaction);
            return List.of(new HistoryAmount(
                    null,
                    type,
                    eventSubtype,
                    displayType(transaction.getType(), eventSubtype),
                    LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()),
                    flowQuantity(flow),
                    flowValueUsd(flow, prices, historicalPrices, transaction.getBlockTimestamp()),
                    feeUsd,
                    feeQuantityByAsset,
                    withdrawYieldByAsset(transaction)
            ));
        }

        private Map<String, BigDecimal> withdrawYieldByAsset(NormalizedTransaction transaction) {
            if (transaction == null || transaction.getType() != NormalizedTransactionType.LENDING_WITHDRAW) {
                return Map.of();
            }
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                if (flow.getRole() != NormalizedLegRole.BUY
                        || flow.getQuantityDelta() == null
                        || flow.getQuantityDelta().signum() <= 0
                        || LendingAssetSymbolSupport.isLendingPositionSymbol(flow.getAssetSymbol())) {
                    continue;
                }
                String asset = cycleStateAsset(LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()));
                result.merge(asset, flow.getQuantityDelta().abs(), (left, right) -> left.add(right, MC));
            }
            return Map.copyOf(result);
        }

        private List<HistoryAmount> protocolChildLegAmounts(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
        ) {
            if (transaction == null || (!isFluidProtocol() && !isMorphoProtocol()) || transaction.getMetadata() == null) {
                return List.of();
            }
            Object rawLegs = transaction.getMetadata().get("lendingChildLegs");
            if (!(rawLegs instanceof List<?> legs) || legs.isEmpty()) {
                return List.of();
            }
            BigDecimal feeUsd = safeFlows(transaction).stream()
                    .filter(candidate -> candidate.getRole() == NormalizedLegRole.FEE)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
            Map<String, BigDecimal> fees = feeQuantityByAsset(transaction);
            List<HistoryAmount> amounts = new ArrayList<>();
            int index = 0;
            for (Object rawLeg : legs) {
                if (!(rawLeg instanceof Document leg)) {
                    continue;
                }
                String type = stringValue(leg.get("type"));
                String assetSymbol = stringValue(leg.get("assetSymbol"));
                BigDecimal quantity = decimalValue(leg.get("quantity"));
                if (type == null || assetSymbol == null || quantity == null || quantity.signum() == 0) {
                    continue;
                }
                BigDecimal valueUsd = quantity.abs().multiply(resolvePrice(
                        prices,
                        historicalPrices,
                        assetSymbol,
                        transaction.getBlockTimestamp()
                ), MC);
                amounts.add(new HistoryAmount(
                        stringValue(leg.get("id")),
                        type,
                        stringValue(leg.get("eventSubtype")),
                        firstNonBlank(stringValue(leg.get("displayType")), displayType(type)),
                        LendingAssetSymbolSupport.displaySymbol(assetSymbol),
                        quantity.abs(),
                        valueUsd,
                        index == 0 ? feeUsd : BigDecimal.ZERO,
                        index == 0 ? fees : Map.of(),
                        "LENDING_WITHDRAW".equals(type) ? withdrawYieldByAsset(transaction) : Map.of()
                ));
                index++;
            }
            return amounts;
        }

        private List<HistoryAmount> compoundLoopAmounts(
                NormalizedTransaction transaction,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices
        ) {
            if (transaction == null || transaction.getType() == null || (!isCompoundProtocol() && !isFluidProtocol())) {
                return List.of();
            }
            if (transaction.getType() != NormalizedTransactionType.LENDING_LOOP_OPEN
                    && transaction.getType() != NormalizedTransactionType.LENDING_LOOP_DECREASE
                    && transaction.getType() != NormalizedTransactionType.LENDING_LOOP_CLOSE) {
                return List.of();
            }
            List<NormalizedTransaction.Flow> nonFeeFlows = safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                    .filter(flow -> flow.getQuantityDelta() != null)
                    .toList();
            Optional<NormalizedTransaction.Flow> stableIn = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() > 0)
                    .filter(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            Optional<NormalizedTransaction.Flow> stableOut = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() < 0)
                    .filter(flow -> LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            Optional<NormalizedTransaction.Flow> collateralIn = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() > 0)
                    .filter(flow -> !LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            Optional<NormalizedTransaction.Flow> collateralOut = nonFeeFlows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() < 0)
                    .filter(flow -> !LendingAssetSymbolSupport.isStable(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            BigDecimal feeUsd = safeFlows(transaction).stream()
                    .filter(candidate -> candidate.getRole() == NormalizedLegRole.FEE)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
            Map<String, BigDecimal> fees = feeQuantityByAsset(transaction);

            List<HistoryAmount> amounts = new ArrayList<>();
            if (transaction.getType() == NormalizedTransactionType.LENDING_LOOP_OPEN) {
                collateralOut.ifPresent(flow -> amounts.add(historyAmount(
                        "supply",
                        "LENDING_LOOP_OPEN",
                        "SUPPLY_COLLATERAL",
                        "Supply collateral",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        feeUsd,
                        fees
                )));
                stableIn.ifPresent(flow -> amounts.add(historyAmount(
                        "borrow",
                        "BORROW",
                        "BORROW",
                        "Borrow",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        BigDecimal.ZERO,
                        Map.of()
                )));
            } else {
                stableOut.ifPresent(flow -> amounts.add(historyAmount(
                        "repay",
                        "REPAY",
                        "REPAY",
                        "Repay",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        feeUsd,
                        fees
                )));
                collateralIn.ifPresent(flow -> amounts.add(historyAmount(
                        "collateral",
                        transaction.getType().name(),
                        "WITHDRAW_COLLATERAL",
                        transaction.getType() == NormalizedTransactionType.LENDING_LOOP_CLOSE
                                ? "Loop close"
                                : "Loop decrease",
                        flow,
                        prices,
                        historicalPrices,
                        transaction.getBlockTimestamp(),
                        BigDecimal.ZERO,
                        Map.of()
                )));
            }
            return amounts;
        }

        private HistoryAmount historyAmount(
                String syntheticIndex,
                String type,
                String eventSubtype,
                String displayType,
                NormalizedTransaction.Flow flow,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
                Instant timestamp,
                BigDecimal feeUsd,
                Map<String, BigDecimal> feeQuantityByAsset
        ) {
            return new HistoryAmount(
                    syntheticIndex,
                    type,
                    eventSubtype,
                    displayType,
                    LendingAssetSymbolSupport.displaySymbol(flow.getAssetSymbol()),
                    flowQuantity(flow),
                    flowValueUsd(flow, prices, historicalPrices, timestamp),
                    feeUsd,
                    feeQuantityByAsset,
                    Map.of()
            );
        }

        private String displayType(String type) {
            if (type == null) {
                return "Unknown";
            }
            try {
                return displayType(NormalizedTransactionType.valueOf(type), null);
            } catch (IllegalArgumentException ignored) {
                return type;
            }
        }

        private String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first;
        }

        private String stringValue(Object value) {
            return value == null ? null : value.toString();
        }

        private BigDecimal decimalValue(Object value) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            if (value == null || value.toString().isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private BigDecimal flowQuantity(NormalizedTransaction.Flow flow) {
            return zeroIfNull(flow.getQuantityDelta()).abs();
        }

        private BigDecimal flowValueUsd(
                NormalizedTransaction.Flow flow,
                Map<String, BigDecimal> prices,
                Map<String, NavigableMap<Instant, BigDecimal>> historicalPrices,
                Instant timestamp
        ) {
            BigDecimal quantity = flowQuantity(flow);
            return flow.getValueUsd() != null
                    ? flow.getValueUsd().abs()
                    : quantity.multiply(resolvePrice(
                            prices,
                            historicalPrices,
                            flow.getAssetSymbol(),
                            timestamp
                    ), MC);
        }

        private Map<String, BigDecimal> feeQuantityByAsset(NormalizedTransaction transaction) {
            Map<String, BigDecimal> fees = new LinkedHashMap<>();
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                if (flow.getRole() != NormalizedLegRole.FEE || flow.getQuantityDelta() == null) {
                    continue;
                }
                String asset = cycleStateAsset(flow.getAssetSymbol());
                if (asset.isBlank()) {
                    continue;
                }
                fees.merge(asset, flow.getQuantityDelta().abs(), (left, right) -> left.add(right, MC));
            }
            return Map.copyOf(fees);
        }

        private Optional<NormalizedTransaction.Flow> preferredHistoryFlow(NormalizedTransaction transaction) {
            if (transaction.getType() == null) {
                return Optional.empty();
            }
            List<NormalizedTransaction.Flow> nonFeeFlows = safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                    .filter(flow -> flow.getQuantityDelta() != null)
                    .toList();
            if (transaction.getType() == NormalizedTransactionType.REPAY
                    && "REPAY_WITH_ATOKENS".equals(eventSubtype(transaction))) {
                return nonFeeFlows.stream()
                        .filter(flow -> flow.getQuantityDelta().signum() < 0)
                        .filter(flow -> LendingAssetSymbolSupport.isBorrowSymbol(flow.getAssetSymbol()))
                        .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
            }
            if (transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                    || transaction.getType() == NormalizedTransactionType.VAULT_DEPOSIT
                    || transaction.getType() == NormalizedTransactionType.REPAY) {
                return largestSpotFlow(nonFeeFlows, -1);
            }
            if (transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                    || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                    || transaction.getType() == NormalizedTransactionType.BORROW
                    || transaction.getType() == NormalizedTransactionType.REWARD_CLAIM) {
                return largestSpotFlow(nonFeeFlows, 1);
            }
            return Optional.empty();
        }

        private Optional<NormalizedTransaction.Flow> largestSpotFlow(List<NormalizedTransaction.Flow> flows, int signum) {
            return flows.stream()
                    .filter(flow -> flow.getQuantityDelta().signum() == signum)
                    .filter(flow -> !LendingAssetSymbolSupport.isLendingPositionSymbol(flow.getAssetSymbol()))
                    .max(Comparator.comparing(flow -> flow.getQuantityDelta().abs()));
        }

        private String eventSubtype(NormalizedTransaction transaction) {
            if (transaction == null || transaction.getType() != NormalizedTransactionType.REPAY) {
                return transaction == null ? null : transaction.getEventSubtype();
            }
            if (transaction.getEventSubtype() != null && !transaction.getEventSubtype().isBlank()) {
                return transaction.getEventSubtype();
            }
            boolean debtBurn = false;
            boolean receiptBurn = false;
            for (NormalizedTransaction.Flow flow : safeFlows(transaction)) {
                if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
                    continue;
                }
                if (LendingAssetSymbolSupport.isBorrowSymbol(flow.getAssetSymbol())) {
                    debtBurn = true;
                } else if (isAaveReceiptSymbol(flow.getAssetSymbol())) {
                    receiptBurn = true;
                }
            }
            return debtBurn && receiptBurn ? "REPAY_WITH_ATOKENS" : null;
        }

        private boolean isAaveReceiptSymbol(String assetSymbol) {
            if (assetSymbol == null || assetSymbol.isBlank()) {
                return false;
            }
            String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
            return normalized.startsWith("A") && !LendingAssetSymbolSupport.isBorrowSymbol(normalized);
        }

        private BigDecimal positiveBuyValue(NormalizedTransaction transaction) {
            return safeFlows(transaction).stream()
                    .filter(flow -> flow.getRole() == NormalizedLegRole.BUY)
                    .map(NormalizedTransaction.Flow::getValueUsd)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right.abs(), MC));
        }

        private String displayType(NormalizedTransactionType type, String eventSubtype) {
            if (type == null) {
                return "Unknown";
            }
            if (type == NormalizedTransactionType.REPAY && "REPAY_WITH_ATOKENS".equals(eventSubtype)) {
                return "Repay with aTokens";
            }
            return switch (type) {
                case LENDING_DEPOSIT -> "Deposit";
                case LENDING_WITHDRAW -> "Withdraw";
                case VAULT_DEPOSIT -> "Deposit";
                case VAULT_WITHDRAW -> "Withdraw";
                case BORROW -> "Borrow";
                case REPAY -> "Repay";
                case REWARD_CLAIM -> "Reward";
                case LENDING_LOOP_OPEN -> "Loop open";
                case LENDING_LOOP_REBALANCE -> "Loop rebalance";
                case LENDING_LOOP_DECREASE -> "Loop decrease";
                case LENDING_LOOP_CLOSE -> "Loop close";
                default -> type.name();
            };
        }

        private String loopId(NormalizedTransaction transaction) {
            if (transaction.getType() == null || !transaction.getType().name().startsWith("LENDING_LOOP")) {
                return null;
            }
            if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
                return transaction.getCorrelationId();
            }
            return "loop:" + key.id() + ":" + transaction.getBlockTimestamp();
        }
    }

    private record HistoryAmount(
            String syntheticIndex,
            String type,
            String eventSubtype,
            String displayType,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            BigDecimal feeUsd,
            Map<String, BigDecimal> feeQuantityByAsset,
            Map<String, BigDecimal> withdrawYieldByAsset
    ) {
    }

    private record CyclePeakView(BigDecimal peakSupplyUsd, BigDecimal peakBorrowUsd) {
    }

    private record UsdAssetBreakdown(
            Map<String, BigDecimal> supplyPnlUsdByAsset,
            Map<String, BigDecimal> borrowPnlUsdByAsset,
            Map<String, BigDecimal> rewardsUsdByAsset,
            Map<String, BigDecimal> gasUsdByAsset,
            Map<String, BigDecimal> netIncomeUsdByAsset,
            Map<String, String> usdPrecisionByAsset
    ) {
        private static UsdAssetBreakdown empty() {
            return new UsdAssetBreakdown(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private static final class MutableCycle {
        private final String id;
        private final List<LendingHistoryEntryView> events = new ArrayList<>();
        private final DeltaAccumulator deltas = new DeltaAccumulator();
        private final CycleState state = new CycleState();
        private Instant lastTimestamp;

        private MutableCycle(String id) {
            this.id = id;
        }

        private void add(LendingHistoryEntryView event) {
            events.add(event);
            deltas.add(event);
            state.add(event);
            lastTimestamp = event.blockTimestamp();
        }

        private String id() {
            return id;
        }

        private List<LendingHistoryEntryView> events() {
            return events;
        }

        private DeltaAccumulator deltas() {
            return deltas;
        }

        private CycleState state() {
            return state;
        }

        private Instant lastTimestamp() {
            return lastTimestamp;
        }

        private Optional<LendingHistoryEntryView> lastEvent() {
            return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
        }

        private boolean isFlat() {
            return state.isFlat();
        }
    }

    /** A single signed principal delta on the cycle timeline (asset, when it happened, +/- quantity). */
    private record PrincipalTimelinePoint(String asset, Instant timestamp, BigDecimal signedQuantity) {
    }

    private static final class DeltaAccumulator {
        private final Map<String, BigDecimal> principalInByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> openingDepositByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> withdrawYieldByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> principalOutByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> principalOutCashByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> internalReceiptMovementByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> borrowedByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> repaidByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> withdrawnByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> rewardByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> feesByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> gasByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> netCashDeltaByAsset = new LinkedHashMap<>();
        // Additive per-asset USD legs. Each map is populated INLINE next to the matching
        // scalar USD accumulation below, with the same value (event.valueUsd()/event.feeUsd()),
        // so that Sigma_asset(map) == matching scalar by construction (deterministic cross-foot).
        private final Map<String, BigDecimal> principalInUsdByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> principalOutCashUsdByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> borrowedUsdByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> repaidUsdByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> rewardUsdByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> gasUsdByAsset = new LinkedHashMap<>();
        private final Set<String> usdMissingValuationAssets = new LinkedHashSet<>();
        private final Map<String, List<LendingObservedFlowView>> observedFlowsByAsset = new LinkedHashMap<>();
        // Chronological signed principal deltas (deposits +, principal reductions -) per SUPPLY asset,
        // and (borrows +, repays -) per BORROW asset. Integrated over the cycle window to derive the
        // time-weighted average principal used as the factual-APR exposure denominator. Order is NOT
        // assumed here (sorted defensively at read time by block timestamp).
        private final List<PrincipalTimelinePoint> supplyPrincipalTimeline = new ArrayList<>();
        private final List<PrincipalTimelinePoint> borrowPrincipalTimeline = new ArrayList<>();
        private boolean hasShareOrVaultAsset;
        private boolean hasFluidLogOperateEvidence;
        private boolean hasWstUsrExposure;
        private boolean hasMissingEventValuation;
        private boolean hasMissingGasUsdValuation;
        private boolean cycleClosedEconomically;
        private BigDecimal principalInUsd = BigDecimal.ZERO;
        private BigDecimal principalOutUsd = BigDecimal.ZERO;
        private BigDecimal principalOutCashUsd = BigDecimal.ZERO;
        private BigDecimal borrowedUsd = BigDecimal.ZERO;
        private BigDecimal repaidUsd = BigDecimal.ZERO;
        private BigDecimal rewardUsd = BigDecimal.ZERO;
        private BigDecimal feesUsd = BigDecimal.ZERO;
        private final Map<String, Set<String>> fluidRepayEvidenceSourcesByKey = new LinkedHashMap<>();

        private void add(LendingHistoryEntryView event) {
            String rawAsset = LendingAssetSymbolSupport.displaySymbol(event.assetSymbol());
            String asset = cycleStateAsset(rawAsset);
            if ("WSTUSR".equalsIgnoreCase(rawAsset)) {
                hasWstUsrExposure = true;
            }
            if (event.eventSubtype() != null && event.eventSubtype().contains("FLUID_LOG_OPERATE")) {
                hasFluidLogOperateEvidence = true;
            }
            if (LendingAssetSymbolSupport.isLendingPositionSymbol(rawAsset)
                    && !LendingAssetSymbolSupport.isBorrowSymbol(rawAsset)) {
                hasShareOrVaultAsset = true;
            }
            BigDecimal quantity = event.quantity() == null ? BigDecimal.ZERO : event.quantity().abs();
            if (quantity.signum() == 0) {
                return;
            }
            boolean aggregateRepay = !"REPAY".equals(event.type())
                    || !isDuplicateFluidRepayEvidence(event, asset, quantity);
            BigDecimal valueUsd = zeroIfNull(event.valueUsd());
            feesUsd = feesUsd.add(zeroIfNull(event.feeUsd()), MC);
            if (valueUsd.signum() == 0 && isValuableEconomicEvent(event)) {
                hasMissingEventValuation = true;
                usdMissingValuationAssets.add(asset);
            }
            if (event.feeUsd() != null && event.feeUsd().signum() > 0) {
                add(feesByAsset, "USD", event.feeUsd());
            }
            String nativeFeeAsset = null;
            for (Map.Entry<String, BigDecimal> feeEntry : event.feeQuantityByAsset().entrySet()) {
                if (feeEntry.getValue() != null && feeEntry.getValue().signum() > 0) {
                    add(gasByAsset, feeEntry.getKey(), feeEntry.getValue());
                    if (nativeFeeAsset == null) {
                        nativeFeeAsset = feeEntry.getKey();
                    }
                    if (event.feeUsd() == null || event.feeUsd().signum() <= 0) {
                        hasMissingGasUsdValuation = true;
                        usdMissingValuationAssets.add(feeEntry.getKey());
                    }
                }
            }
            // Attribute the whole event gas USD to the native fee asset (not the "USD" sentinel
            // key used by the quantity feesByAsset map), keeping Sigma(gasUsdByAsset) == feesUsd.
            if (nativeFeeAsset != null && event.feeUsd() != null && event.feeUsd().signum() > 0) {
                add(gasUsdByAsset, nativeFeeAsset, event.feeUsd());
            }
            switch (event.type()) {
                case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN" -> {
                    add(principalInByAsset, asset, quantity);
                    openingDepositByAsset.putIfAbsent(asset, quantity);
                    recordSupplyPrincipalPoint(event, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity.negate());
                    addObservedFlow(event, asset, quantity.negate());
                    principalInUsd = principalInUsd.add(valueUsd, MC);
                    addUsd(principalInUsdByAsset, asset, valueUsd);
                }
                case "BORROW" -> {
                    add(borrowedByAsset, asset, quantity);
                    recordBorrowPrincipalPoint(event, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    borrowedUsd = borrowedUsd.add(valueUsd, MC);
                    addUsd(borrowedUsdByAsset, asset, valueUsd);
                }
                case "REPAY" -> {
                    if (aggregateRepay) {
                        addObservedFlow(event, asset, quantity.negate());
                        add(repaidByAsset, asset, quantity);
                        recordBorrowPrincipalPoint(event, asset, quantity.negate());
                        add(netCashDeltaByAsset, asset, quantity.negate());
                        repaidUsd = repaidUsd.add(valueUsd, MC);
                        addUsd(repaidUsdByAsset, asset, valueUsd);
                    }
                }
                case "LENDING_WITHDRAW", "VAULT_WITHDRAW", "LENDING_LOOP_CLOSE", "LENDING_LOOP_DECREASE" -> {
                    if ("LENDING_WITHDRAW".equals(event.type())) {
                        for (Map.Entry<String, BigDecimal> yieldEntry : event.withdrawYieldByAsset().entrySet()) {
                            if (yieldEntry.getValue() != null && yieldEntry.getValue().signum() > 0) {
                                add(withdrawYieldByAsset, yieldEntry.getKey(), yieldEntry.getValue());
                            }
                        }
                    }
                    add(withdrawnByAsset, asset, quantity);
                    add(principalOutByAsset, asset, quantity);
                    recordSupplyPrincipalPoint(event, asset, quantity.negate());
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    principalOutUsd = principalOutUsd.add(valueUsd, MC);
                    if (isInternalReceiptMovement(event)) {
                        add(internalReceiptMovementByAsset, asset, quantity);
                    } else {
                        add(principalOutCashByAsset, asset, quantity);
                        principalOutCashUsd = principalOutCashUsd.add(valueUsd, MC);
                        addUsd(principalOutCashUsdByAsset, asset, valueUsd);
                    }
                }
                case "LENDING_CASH_EXIT" -> {
                    add(principalOutCashByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    principalOutCashUsd = principalOutCashUsd.add(valueUsd, MC);
                    addUsd(principalOutCashUsdByAsset, asset, valueUsd);
                }
                case "REWARD_CLAIM" -> {
                    add(rewardByAsset, asset, quantity);
                    add(netCashDeltaByAsset, asset, quantity);
                    addObservedFlow(event, asset, quantity);
                    rewardUsd = rewardUsd.add(valueUsd, MC);
                    addUsd(rewardUsdByAsset, asset, valueUsd);
                }
                default -> {
                    // Keep unknown lifecycle shapes visible through event rows.
                }
            }
        }

        private boolean isValuableEconomicEvent(LendingHistoryEntryView event) {
            return switch (event.type()) {
                case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN",
                     "BORROW", "REPAY", "LENDING_WITHDRAW", "VAULT_WITHDRAW",
                     "LENDING_LOOP_CLOSE", "LENDING_LOOP_DECREASE", "REWARD_CLAIM" -> true;
                default -> false;
            };
        }

        private LendingAssetDeltasView toView() {
            return new LendingAssetDeltasView(
                    Map.copyOf(principalInByAsset),
                    Map.copyOf(principalOutByAsset),
                    Map.copyOf(principalOutCashByAsset),
                    Map.copyOf(internalReceiptMovementByAsset),
                    Map.copyOf(borrowedByAsset),
                    Map.copyOf(repaidByAsset),
                    Map.copyOf(withdrawnByAsset),
                    Map.copyOf(rewardByAsset),
                    Map.copyOf(feesByAsset),
                    Map.copyOf(netCashDeltaByAsset)
            );
        }

        private void add(Map<String, BigDecimal> target, String asset, BigDecimal quantity) {
            target.merge(asset, quantity, (left, right) -> left.add(right, MC));
        }

        private void addUsd(Map<String, BigDecimal> target, String asset, BigDecimal valueUsd) {
            if (valueUsd == null || valueUsd.signum() == 0) {
                return;
            }
            target.merge(asset, valueUsd, (left, right) -> left.add(right, MC));
        }

        private void addObservedFlow(LendingHistoryEntryView event, String asset, BigDecimal signedQuantity) {
            if (event == null || signedQuantity == null || signedQuantity.signum() == 0) {
                return;
            }
            observedFlowsByAsset.computeIfAbsent(asset, ignored -> new ArrayList<>()).add(new LendingObservedFlowView(
                    event.assetSymbol(),
                    null,
                    signedQuantity,
                    event.txHash(),
                    observedSourceKind(event),
                    false,
                    null
            ));
        }

        private String observedSourceKind(LendingHistoryEntryView event) {
            if (event.eventSubtype() != null && event.eventSubtype().contains("LOG_OPERATE")) {
                return "DECODED_PROTOCOL_EVENT";
            }
            if (event.eventSubtype() != null && event.eventSubtype().contains("RECEIPT")) {
                return "RECEIPT_LOG";
            }
            return "WALLET_VISIBLE_TRANSFER";
        }

        private boolean isInternalReceiptMovement(LendingHistoryEntryView event) {
            if (event == null) {
                return false;
            }
            String symbol = event.assetSymbol();
            if (LendingAssetSymbolSupport.isLendingPositionSymbol(symbol)) {
                return true;
            }
            String subtype = event.eventSubtype() == null
                    ? ""
                    : event.eventSubtype().toUpperCase(Locale.ROOT);
            return subtype.contains("INTERNAL_RECEIPT")
                    || subtype.contains("INTERNAL_SHARE")
                    || subtype.contains("EVK_ACCOUNT")
                    || subtype.contains("EVK_SHARE");
        }

        private boolean isDuplicateFluidRepayEvidence(
                LendingHistoryEntryView event,
                String asset,
                BigDecimal quantity
        ) {
            String source = fluidRepayEvidenceSource(event);
            if (source == null) {
                return false;
            }
            String key = String.join("|",
                    nullToEmpty(event.txHash()),
                    nullToEmpty(event.marketKey()),
                    nullToEmpty(asset),
                    quantity.stripTrailingZeros().toPlainString()
            );
            Set<String> seenSources = fluidRepayEvidenceSourcesByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            boolean hasComplement = ("WALLET".equals(source) && seenSources.contains("LOG_OPERATE"))
                    || ("LOG_OPERATE".equals(source) && seenSources.contains("WALLET"));
            seenSources.add(source);
            return hasComplement;
        }

        private String fluidRepayEvidenceSource(LendingHistoryEntryView event) {
            String subtype = event == null || event.eventSubtype() == null
                    ? ""
                    : event.eventSubtype().toUpperCase(Locale.ROOT);
            if (subtype.contains("FLUID_LOG_OPERATE_REPAY")) {
                return "LOG_OPERATE";
            }
            if (subtype.contains("FLUID_WALLET_VISIBLE_REPAY")) {
                return "WALLET";
            }
            return null;
        }

        private Map<String, List<LendingObservedFlowView>> observedFlowsByAsset(String unavailableReason) {
            Map<String, List<LendingObservedFlowView>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<LendingObservedFlowView>> entry : observedFlowsByAsset.entrySet()) {
                result.put(entry.getKey(), entry.getValue().stream()
                        .map(flow -> new LendingObservedFlowView(
                                flow.assetSymbol(),
                                flow.assetContract(),
                                flow.quantity(),
                                flow.sourceTxHash(),
                                flow.sourceKind(),
                                false,
                                unavailableReason
                        ))
                        .toList());
            }
            return result;
        }

        private boolean hasAny(Map<String, BigDecimal> source) {
            return source.values().stream().anyMatch(value -> value != null && value.signum() > 0);
        }

        private boolean hasFluidLogOperateEvidence() {
            return hasFluidLogOperateEvidence;
        }

        private boolean hasWstUsrExposure() {
            return hasWstUsrExposure;
        }

        private boolean hasMissingEventValuation() {
            return hasMissingEventValuation;
        }

        private boolean hasMissingGasUsdValuation() {
            return hasMissingGasUsdValuation;
        }

        private boolean hasIncompletePrincipalExit() {
            return hasUnresolvedPrincipalExitByAsset();
        }

        private Map<String, BigDecimal> principalInByAsset() {
            return canonicalize(principalInByAsset);
        }

        private Map<String, BigDecimal> openingDepositByAsset() {
            return canonicalize(openingDepositByAsset);
        }

        private Map<String, BigDecimal> withdrawYieldByAsset() {
            return canonicalize(withdrawYieldByAsset);
        }

        private Map<String, BigDecimal> principalOutByAsset() {
            return canonicalize(principalOutByAsset);
        }

        private Map<String, BigDecimal> principalOutCashByAsset() {
            return canonicalize(principalOutCashByAsset);
        }

        private Map<String, BigDecimal> internalReceiptMovementByAsset() {
            return canonicalize(internalReceiptMovementByAsset);
        }

        private Map<String, BigDecimal> borrowedByAsset() {
            return canonicalize(borrowedByAsset);
        }

        private Map<String, BigDecimal> repaidByAsset() {
            return canonicalize(repaidByAsset);
        }

        private Map<String, BigDecimal> rewardByAsset() {
            return canonicalize(rewardByAsset);
        }

        private Map<String, BigDecimal> gasByAsset() {
            return canonicalize(gasByAsset);
        }

        private Map<String, BigDecimal> principalInUsdByAsset() {
            return canonicalize(principalInUsdByAsset);
        }

        private Map<String, BigDecimal> principalOutCashUsdByAsset() {
            return canonicalize(principalOutCashUsdByAsset);
        }

        private Map<String, BigDecimal> borrowedUsdByAsset() {
            return canonicalize(borrowedUsdByAsset);
        }

        private Map<String, BigDecimal> repaidUsdByAsset() {
            return canonicalize(repaidUsdByAsset);
        }

        private Map<String, BigDecimal> rewardUsdByAsset() {
            return canonicalize(rewardUsdByAsset);
        }

        private Map<String, BigDecimal> gasUsdByAsset() {
            return canonicalize(gasUsdByAsset);
        }

        private Set<String> usdMissingValuationAssets() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String asset : usdMissingValuationAssets) {
                result.add(cycleStateAsset(asset));
            }
            return result;
        }

        private BigDecimal principalInUsd() {
            return principalInUsd;
        }

        private BigDecimal principalOutUsd() {
            return principalOutUsd;
        }

        private BigDecimal principalOutCashUsd() {
            return principalOutCashUsd;
        }

        private BigDecimal borrowedUsd() {
            return borrowedUsd;
        }

        private BigDecimal repaidUsd() {
            return repaidUsd;
        }

        private BigDecimal rewardUsd() {
            return rewardUsd;
        }

        private BigDecimal feesUsd() {
            return feesUsd;
        }

        private void markCycleClosedEconomically() {
            cycleClosedEconomically = true;
        }

        private boolean hasUnresolvedPrincipalExitByAsset() {
            return !incompletePrincipalExitAssets().isEmpty();
        }

        private Set<String> incompletePrincipalExitAssets() {
            if (cycleClosedEconomically) {
                return Set.of();
            }
            Map<String, BigDecimal> suppliedByLifecycleAsset = principalInByAsset();
            Map<String, BigDecimal> exitedByLifecycleAsset = principalOutByAsset();
            Map<String, BigDecimal> internalByLifecycleAsset = internalReceiptMovementByAsset();
            LinkedHashSet<String> assets = new LinkedHashSet<>();
            for (Map.Entry<String, BigDecimal> entry : suppliedByLifecycleAsset.entrySet()) {
                BigDecimal supplied = entry.getValue();
                if (supplied == null || supplied.signum() <= 0) {
                    continue;
                }
                BigDecimal exited = exitedByLifecycleAsset.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                BigDecimal internal = internalByLifecycleAsset.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (exited.add(internal, MC).compareTo(CYCLE_DUST_TOLERANCE) <= 0) {
                    assets.add(entry.getKey());
                }
            }
            return assets;
        }

        private boolean hasNonStablePrincipalExposure() {
            return principalAssets().stream()
                    .anyMatch(asset -> !LendingAssetSymbolSupport.isStable(asset));
        }

        private boolean hasShareOrVaultAsset() {
            return hasShareOrVaultAsset;
        }

        private boolean hasInternalReceiptMovement() {
            return hasAny(internalReceiptMovementByAsset);
        }

        private boolean hasWrapperOrShareExposure() {
            return hasShareOrVaultAsset || hasWstUsrExposure;
        }

        private Set<String> principalAssets() {
            LinkedHashSet<String> assets = new LinkedHashSet<>();
            assets.addAll(canonicalize(principalInByAsset).keySet());
            assets.addAll(canonicalize(principalOutByAsset).keySet());
            assets.addAll(canonicalize(withdrawnByAsset).keySet());
            return assets;
        }

        private Map<String, BigDecimal> canonicalize(Map<String, BigDecimal> source) {
            Map<String, BigDecimal> canonical = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String asset = cycleStateAsset(entry.getKey());
                canonical.merge(asset, entry.getValue(), (left, right) -> left.add(right, MC));
            }
            return canonical;
        }

        private void recordSupplyPrincipalPoint(LendingHistoryEntryView event, String asset, BigDecimal signedQuantity) {
            recordPrincipalPoint(supplyPrincipalTimeline, event, asset, signedQuantity);
        }

        private void recordBorrowPrincipalPoint(LendingHistoryEntryView event, String asset, BigDecimal signedQuantity) {
            recordPrincipalPoint(borrowPrincipalTimeline, event, asset, signedQuantity);
        }

        private void recordPrincipalPoint(
                List<PrincipalTimelinePoint> timeline,
                LendingHistoryEntryView event,
                String asset,
                BigDecimal signedQuantity
        ) {
            if (event == null || event.blockTimestamp() == null || signedQuantity == null || signedQuantity.signum() == 0) {
                return;
            }
            timeline.add(new PrincipalTimelinePoint(cycleStateAsset(asset), event.blockTimestamp(), signedQuantity));
        }

        /**
         * Time-weighted average supply principal per asset over {@code [start, end]}: the running
         * principal balance integrated across chronological segments and divided by the total duration.
         * This is the factual-APR exposure denominator — income accrues on the whole outstanding
         * principal, not the first deposit. Falls back to the total deposited principal when the
         * timeline is empty or the duration is non-positive so nothing regresses to null.
         */
        private Map<String, BigDecimal> timeWeightedSupplyPrincipalByAsset(Instant start, Instant end) {
            return timeWeightedPrincipal(supplyPrincipalTimeline, principalInByAsset(), start, end);
        }

        /**
         * Time-weighted average outstanding borrow per asset over {@code [start, end]}. Falls back to the
         * total-ever-borrowed when the timeline is empty or the duration is non-positive.
         */
        private Map<String, BigDecimal> timeWeightedBorrowPrincipalByAsset(Instant start, Instant end) {
            return timeWeightedPrincipal(borrowPrincipalTimeline, borrowedByAsset(), start, end);
        }

        private Map<String, BigDecimal> timeWeightedPrincipal(
                List<PrincipalTimelinePoint> timeline,
                Map<String, BigDecimal> fallbackTotals,
                Instant start,
                Instant end
        ) {
            if (start == null || end == null || !end.isAfter(start) || timeline.isEmpty()) {
                return fallbackTotals;
            }
            BigDecimal totalSeconds = BigDecimal.valueOf(Duration.between(start, end).toSeconds());
            if (totalSeconds.signum() <= 0) {
                return fallbackTotals;
            }
            Map<String, List<PrincipalTimelinePoint>> byAsset = new LinkedHashMap<>();
            for (PrincipalTimelinePoint point : timeline) {
                byAsset.computeIfAbsent(point.asset(), ignored -> new ArrayList<>()).add(point);
            }
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<PrincipalTimelinePoint>> entry : byAsset.entrySet()) {
                List<PrincipalTimelinePoint> points = new ArrayList<>(entry.getValue());
                // Sort defensively by timestamp; do not assume accumulation order.
                points.sort(Comparator.comparing(PrincipalTimelinePoint::timestamp));
                BigDecimal running = BigDecimal.ZERO;
                BigDecimal weightedSum = BigDecimal.ZERO;
                Instant cursor = start;
                for (PrincipalTimelinePoint point : points) {
                    Instant clamped = clamp(point.timestamp(), start, end);
                    if (clamped.isAfter(cursor)) {
                        long segmentSeconds = Duration.between(cursor, clamped).toSeconds();
                        if (segmentSeconds > 0 && running.signum() != 0) {
                            weightedSum = weightedSum.add(running.multiply(BigDecimal.valueOf(segmentSeconds), MC), MC);
                        }
                        cursor = clamped;
                    }
                    running = running.add(point.signedQuantity(), MC);
                }
                if (end.isAfter(cursor)) {
                    long segmentSeconds = Duration.between(cursor, end).toSeconds();
                    if (segmentSeconds > 0 && running.signum() != 0) {
                        weightedSum = weightedSum.add(running.multiply(BigDecimal.valueOf(segmentSeconds), MC), MC);
                    }
                }
                BigDecimal average = weightedSum.divide(totalSeconds, MC);
                if (average.signum() > 0) {
                    result.put(entry.getKey(), average);
                } else if (fallbackTotals.containsKey(entry.getKey())) {
                    // A fully-exited asset time-weights to zero over the window; keep the total so the
                    // yield-flow evidence branch can still attribute income to it.
                    result.put(entry.getKey(), fallbackTotals.get(entry.getKey()));
                }
            }
            // Preserve any fallback assets that never produced a timeline point (defensive).
            for (Map.Entry<String, BigDecimal> total : fallbackTotals.entrySet()) {
                result.putIfAbsent(total.getKey(), total.getValue());
            }
            return result;
        }

        private static Instant clamp(Instant value, Instant start, Instant end) {
            if (value == null || value.isBefore(start)) {
                return start;
            }
            if (value.isAfter(end)) {
                return end;
            }
            return value;
        }

        private Set<String> allAssets() {
            LinkedHashSet<String> assets = new LinkedHashSet<>();
            assets.addAll(principalInByAsset.keySet());
            assets.addAll(principalOutByAsset.keySet());
            assets.addAll(borrowedByAsset.keySet());
            assets.addAll(repaidByAsset.keySet());
            assets.addAll(withdrawnByAsset.keySet());
            assets.addAll(rewardByAsset.keySet());
            assets.addAll(feesByAsset.keySet());
            assets.addAll(gasByAsset.keySet());
            assets.addAll(netCashDeltaByAsset.keySet());
            return assets;
        }
    }

    private static final class CycleState {
        private final Map<String, BigDecimal> supplyByAsset = new LinkedHashMap<>();
        private final Map<String, BigDecimal> debtByAsset = new LinkedHashMap<>();

        private void add(LendingHistoryEntryView event) {
            String asset = cycleStateAsset(event.assetSymbol());
            BigDecimal quantity = event.quantity() == null ? BigDecimal.ZERO : event.quantity().abs();
            if (quantity.signum() == 0) {
                return;
            }
            switch (event.type()) {
                case "LENDING_DEPOSIT", "VAULT_DEPOSIT", "LENDING_LOOP_OPEN" -> add(supplyByAsset, asset, quantity);
                case "LENDING_WITHDRAW", "VAULT_WITHDRAW", "LENDING_LOOP_DECREASE", "LENDING_LOOP_CLOSE" ->
                        subtract(supplyByAsset, asset, quantity);
                case "BORROW" -> add(debtByAsset, asset, quantity);
                case "REPAY" -> {
                    subtract(debtByAsset, asset, quantity);
                    // REPAY_WITH_ATOKENS: collateral aTokens are burned to settle the debt.
                    // The on-chain receipt burn reduces the actual supply, so we must also
                    // subtract from supplyByAsset so that isFlat() detects a fully-closed
                    // cycle (e.g. ETH leverage loop repaid via aWETH collateral burn).
                    if ("REPAY_WITH_ATOKENS".equals(event.eventSubtype())) {
                        subtract(supplyByAsset, asset, quantity);
                    }
                }
                default -> {
                    // Rewards and display-only rows do not change lifecycle open/close state.
                }
            }
        }

        private boolean isFlat() {
            return hasNoPositiveRemainder(supplyByAsset) && hasNoPositiveRemainder(debtByAsset);
        }

        private BigDecimal supplyAmount(String asset) {
            return supplyByAsset.getOrDefault(asset, BigDecimal.ZERO);
        }

        private boolean hasPositiveSupply(String asset) {
            return supplyByAsset.getOrDefault(asset, BigDecimal.ZERO).compareTo(CYCLE_DUST_TOLERANCE) > 0;
        }

        private boolean hasPositiveDebt(String asset) {
            return debtByAsset.getOrDefault(asset, BigDecimal.ZERO).compareTo(CYCLE_DUST_TOLERANCE) > 0;
        }

        private boolean hasAnySupply() {
            return hasPositiveRemainder(supplyByAsset);
        }

        private boolean hasAnyDebt() {
            return hasPositiveRemainder(debtByAsset);
        }

        private void add(Map<String, BigDecimal> target, String asset, BigDecimal quantity) {
            target.merge(asset, quantity, (left, right) -> left.add(right, MC));
        }

        private void subtract(Map<String, BigDecimal> target, String asset, BigDecimal quantity) {
            target.merge(asset, quantity.negate(), (left, right) -> left.add(right, MC));
        }

        private boolean hasNoPositiveRemainder(Map<String, BigDecimal> source) {
            return source.values().stream()
                    .filter(Objects::nonNull)
                    .allMatch(value -> value.compareTo(CYCLE_DUST_TOLERANCE) <= 0);
        }

        private boolean hasPositiveRemainder(Map<String, BigDecimal> source) {
            return source.values().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(value -> value.compareTo(CYCLE_DUST_TOLERANCE) > 0);
        }
    }

    private static String cycleStateAsset(String assetSymbol) {
        return LendingAssetSymbolSupport.displaySymbol(LendingAssetSymbolSupport.lifecycleAsset(assetSymbol));
    }

    private boolean isLendingPositionSymbol(NetworkId networkId, String contract, String symbol) {
        return receiptIdentityService.isLendingPositionSymbol(networkId, contract, symbol);
    }

    private String resolvedUnderlying(NetworkId networkId, String contract, String symbol) {
        return receiptIdentityService.underlyingSymbol(networkId, contract, symbol);
    }

    private static boolean isSynthesizedBorrow(LendingPositionView position) {
        return position != null && position.id() != null && position.id().endsWith(SYNTHETIC_BORROW_ID_SUFFIX);
    }

    private static boolean isSynthesizedSupply(LendingPositionView position) {
        return position != null && position.id() != null && position.id().endsWith(SYNTHETIC_SUPPLY_ID_SUFFIX);
    }

}
