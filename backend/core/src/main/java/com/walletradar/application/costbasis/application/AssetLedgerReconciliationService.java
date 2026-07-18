package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.support.CexUmbrellaSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
class AssetLedgerReconciliationService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final MongoOperations mongoOperations;
    private final CexLiveBalancePort cexLiveBalancePort;

    AssetLedgerQueryService.FullSessionCurrentView fullSessionCurrentView(
            List<AssetLedgerPoint> points,
            String familyIdentity
    ) {
        Map<BucketKey, AssetLedgerPoint> latestPointByBucket = latestLedgerPointByBucket(points);
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal coveredQuantity = BigDecimal.ZERO;
        BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        for (AssetLedgerPoint point : latestPointByBucket.values()) {
            String pointFamily = resolvedFamilyIdentity(
                    point,
                    point.getNetworkId(),
                    point.getAssetSymbol(),
                    point.getAssetContract()
            );
            if (!Objects.equals(pointFamily, familyIdentity)) {
                continue;
            }
            BigDecimal qty = zeroIfNull(point.getQuantityAfter());
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal covered = zeroIfNull(point.getBasisBackedQuantityAfter()).min(qty);
            quantity = quantity.add(qty, MC);
            coveredQuantity = coveredQuantity.add(covered, MC);
            if (point.getAvcoAfterUsd() != null && covered.signum() > 0) {
                totalCostBasisUsd = totalCostBasisUsd.add(point.getAvcoAfterUsd().multiply(covered, MC), MC);
            }
            if (point.getNetAvcoAfterUsd() != null && covered.signum() > 0) {
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(point.getNetAvcoAfterUsd().multiply(covered, MC), MC);
            } else if (point.getAvcoAfterUsd() != null && covered.signum() > 0) {
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(point.getAvcoAfterUsd().multiply(covered, MC), MC);
            }
        }
        BigDecimal uncoveredQuantity = quantity.subtract(coveredQuantity, MC).max(BigDecimal.ZERO);
        BigDecimal avcoUsd = coveredQuantity.signum() <= 0 ? null : totalCostBasisUsd.divide(coveredQuantity, MC);
        BigDecimal netAvcoUsd = coveredQuantity.signum() <= 0 ? null : netTotalCostBasisUsd.divide(coveredQuantity, MC);
        return new AssetLedgerQueryService.FullSessionCurrentView(
                quantity, coveredQuantity, uncoveredQuantity,
                totalCostBasisUsd, avcoUsd, netTotalCostBasisUsd, netAvcoUsd
        );
    }

    AssetLedgerQueryService.CurrentStateView currentStateView(
            UserSession session,
            String familyIdentity,
            List<AssetLedgerPoint> universePoints,
            BigDecimal realisedPnlUsd,
            BigDecimal netRealisedPnlUsd,
            BigDecimal gasPaidUsd
    ) {
        AllowedScope allowedScope = AllowedScope.from(session.getWallets());
        List<OnChainBalance> scopedBalances = loadOnChainBalances(session.getId(), allowedScope.walletAddresses()).stream()
                .filter(balance -> allowedScope.includes(balance.getWalletAddress(), balance.getNetworkId()))
                .toList();
        Map<BucketKey, OnChainBalance> latestBalances = latestBalanceByBucket(scopedBalances);
        Map<BucketKey, AssetLedgerPoint> latestPointByBucket = latestLedgerPointByBucket(universePoints);

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal coveredQuantity = BigDecimal.ZERO;
        BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        List<AssetLedgerQueryService.UncoveredBucketView> uncoveredBuckets = new ArrayList<>();

        for (OnChainBalance balance : latestBalances.values()) {
            if (!matchesFamily(balance, familyIdentity, latestPointByBucket)) {
                continue;
            }
            BigDecimal currentQuantity = zeroIfNull(balance.getQuantity());
            if (currentQuantity.signum() <= 0) {
                continue;
            }
            quantity = quantity.add(currentQuantity, MC);

            BucketKey bucketKey = bucketKey(balance);
            AssetLedgerPoint latestPoint = latestPointByBucket.get(bucketKey);
            BigDecimal exactCoveredQuantity = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(currentQuantity);
            coveredQuantity = coveredQuantity.add(exactCoveredQuantity, MC);
            if (latestPoint != null && latestPoint.getAvcoAfterUsd() != null && exactCoveredQuantity.signum() > 0) {
                totalCostBasisUsd = totalCostBasisUsd.add(
                        latestPoint.getAvcoAfterUsd().multiply(exactCoveredQuantity, MC),
                        MC
                );
            }
            if (latestPoint != null && exactCoveredQuantity.signum() > 0) {
                BigDecimal netAvcoPoint = latestPoint.getNetAvcoAfterUsd() != null
                        ? latestPoint.getNetAvcoAfterUsd()
                        : latestPoint.getAvcoAfterUsd();
                if (netAvcoPoint != null) {
                    netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                            netAvcoPoint.multiply(exactCoveredQuantity, MC),
                            MC
                    );
                }
            }
            BigDecimal exactUncoveredQuantity = currentQuantity.subtract(exactCoveredQuantity, MC).max(BigDecimal.ZERO);
            if (exactUncoveredQuantity.signum() > 0) {
                uncoveredBuckets.add(new AssetLedgerQueryService.UncoveredBucketView(
                        balance.getWalletAddress(),
                        balance.getNetworkId() == null ? null : balance.getNetworkId().name(),
                        balance.getAssetSymbol(),
                        balance.getAssetContract(),
                        currentQuantity,
                        exactCoveredQuantity,
                        exactUncoveredQuantity,
                        uncoveredReason(latestPoint, currentQuantity, exactCoveredQuantity),
                        latestPoint == null ? null : latestPoint.getTxHash(),
                        latestPoint == null ? null : latestPoint.getNormalizedType(),
                        latestPoint == null || latestPoint.getBasisEffect() == null ? null : latestPoint.getBasisEffect().name(),
                        latestPoint == null ? null : latestPoint.getProtocolName(),
                        latestPoint != null && Boolean.TRUE.equals(latestPoint.getHasIncompleteHistoryAfter()),
                        latestPoint != null && Boolean.TRUE.equals(latestPoint.getHasUnresolvedFlagsAfter()),
                        unresolvedFlagCountAfter(latestPoint)
                ));
            }
        }

        LinkedHashSet<String> enabledCexVenueRefs = new LinkedHashSet<>(CexUmbrellaSupport.enabledCexAccountRefs(session));
        if (!enabledCexVenueRefs.isEmpty()) {
            Map<String, CexFamilyUmbrellaAccumulator> umbrellas = new LinkedHashMap<>();
            for (Map.Entry<BucketKey, AssetLedgerPoint> ledgerEntry : latestPointByBucket.entrySet()) {
                BucketKey bucketKey = ledgerEntry.getKey();
                AssetLedgerPoint latestPoint = ledgerEntry.getValue();
                if (!CexUmbrellaSupport.cexLedgerMatchesEnabledVenue(bucketKey.walletAddress(), enabledCexVenueRefs)) {
                    continue;
                }
                String pointFamilyIdentity = resolvedFamilyIdentity(
                        latestPoint,
                        bucketKey.networkId(),
                        latestPoint.getAssetSymbol(),
                        latestPoint.getAssetContract()
                );
                if (!Objects.equals(pointFamilyIdentity, familyIdentity)) {
                    continue;
                }
                BigDecimal currentQuantity = CexUmbrellaSupport.cexRawQuantityAfter(latestPoint);
                if (currentQuantity.signum() <= 0) {
                    continue;
                }
                String aggregatedWallet = CexUmbrellaSupport.ledgerWalletKeyForAggregation(
                        bucketKey.walletAddress(),
                        enabledCexVenueRefs
                );
                umbrellas.computeIfAbsent(aggregatedWallet, ignored -> new CexFamilyUmbrellaAccumulator())
                        .addVenue(latestPoint.getWalletAddress(), currentQuantity, latestPoint);
            }

            Map<String, Map<String, BigDecimal>> liveByCexAccountRef = loadLiveCexBalances(session);
            for (Map.Entry<String, CexFamilyUmbrellaAccumulator> umbrellaEntry : umbrellas.entrySet()) {
                String umbrellaWallet = umbrellaEntry.getKey();
                CexFamilyUmbrellaAccumulator umbrella = umbrellaEntry.getValue();
                Map<String, BigDecimal> live = liveByCexAccountRef.get(umbrellaWallet);
                BigDecimal liveQty = live == null
                        ? BigDecimal.ZERO
                        : CexUmbrellaSupport.liveQuantityForCandidates(
                        live,
                        umbrella.priceLookupCandidates(),
                        umbrella.fallbackSymbol()
                );
                CexUmbrellaSupport.ScaledUmbrellaTotals scaled = CexUmbrellaSupport.scaleUmbrellaToLive(
                        umbrella.quantity(),
                        umbrella.coveredQuantity(),
                        umbrella.totalCostBasisUsd(),
                        liveQty
                );
                if (scaled.dropped() || scaled.quantity().signum() <= 0) {
                    continue;
                }
                BigDecimal quantityScale = umbrella.quantity().signum() <= 0
                        ? BigDecimal.ONE
                        : scaled.quantity().divide(umbrella.quantity(), MC);
                BigDecimal ledgerScale = scaled.ledgerScale() == null ? BigDecimal.ONE : scaled.ledgerScale();
                quantity = quantity.add(scaled.quantity(), MC);
                coveredQuantity = coveredQuantity.add(scaled.coveredQuantity(), MC);
                totalCostBasisUsd = totalCostBasisUsd.add(scaled.totalCostBasisUsd(), MC);
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                        umbrella.netTotalCostBasisUsd().multiply(ledgerScale, MC),
                        MC
                );
                for (VenuePositionSlice venue : umbrella.venueSlices()) {
                    BigDecimal venueQty = venue.quantity().multiply(quantityScale.min(BigDecimal.ONE), MC);
                    BigDecimal venueCovered = venue.coveredQuantity().multiply(ledgerScale, MC);
                    BigDecimal venueUncovered = venueQty.subtract(venueCovered, MC).max(BigDecimal.ZERO);
                    if (venueUncovered.signum() <= 0) {
                        continue;
                    }
                    uncoveredBuckets.add(new AssetLedgerQueryService.UncoveredBucketView(
                            venue.walletAddress(),
                            null,
                            venue.assetSymbol(),
                            venue.assetContract(),
                            venueQty,
                            venueCovered,
                            venueUncovered,
                            uncoveredReason(venue.latestPoint(), venueQty, venueCovered),
                            venue.latestPoint() == null ? null : venue.latestPoint().getTxHash(),
                            venue.latestPoint() == null ? null : venue.latestPoint().getNormalizedType(),
                            venue.latestPoint() == null || venue.latestPoint().getBasisEffect() == null
                                    ? null
                                    : venue.latestPoint().getBasisEffect().name(),
                            venue.latestPoint() == null ? null : venue.latestPoint().getProtocolName(),
                            venue.latestPoint() != null && Boolean.TRUE.equals(venue.latestPoint().getHasIncompleteHistoryAfter()),
                            venue.latestPoint() != null && Boolean.TRUE.equals(venue.latestPoint().getHasUnresolvedFlagsAfter()),
                            unresolvedFlagCountAfter(venue.latestPoint())
                    ));
                }
            }
        }

        BigDecimal uncoveredQuantity = quantity.subtract(coveredQuantity, MC).max(BigDecimal.ZERO);
        BigDecimal avcoUsd = coveredQuantity.signum() <= 0
                ? null
                : totalCostBasisUsd.divide(coveredQuantity, MC);
        BigDecimal netAvcoUsd = coveredQuantity.signum() <= 0
                ? null
                : netTotalCostBasisUsd.divide(coveredQuantity, MC);
        uncoveredBuckets.sort((left, right) -> right.uncoveredQuantity().compareTo(left.uncoveredQuantity()));
        List<AssetLedgerQueryService.ShortfallSourceView> shortfallSources = familyShortfallSources(universePoints);
        return new AssetLedgerQueryService.CurrentStateView(
                quantity,
                coveredQuantity,
                uncoveredQuantity,
                totalCostBasisUsd,
                avcoUsd,
                netTotalCostBasisUsd,
                netAvcoUsd,
                realisedPnlUsd,
                netRealisedPnlUsd,
                gasPaidUsd,
                List.copyOf(uncoveredBuckets),
                shortfallSources,
                null,
                null,
                null,
                null,
                List.of()
        );
    }

    private List<AssetLedgerQueryService.ShortfallSourceView> familyShortfallSources(List<AssetLedgerPoint> universePoints) {
        Map<ShortfallSourceKey, ShortfallSourceAccumulator> grouped = new LinkedHashMap<>();
        for (AssetLedgerPoint point : universePoints) {
            BigDecimal quantityShortfallDelta = zeroIfNull(point.getQuantityShortfallDelta());
            if (quantityShortfallDelta.signum() <= 0) {
                continue;
            }
            ShortfallSourceKey key = new ShortfallSourceKey(
                    point.getWalletAddress(),
                    point.getNetworkId() == null ? null : point.getNetworkId().name(),
                    point.getTxHash(),
                    point.getNormalizedType(),
                    point.getProtocolName()
            );
            grouped.computeIfAbsent(key, ignored -> new ShortfallSourceAccumulator(point))
                    .add(quantityShortfallDelta);
        }
        return grouped.values().stream()
                .sorted((left, right) -> {
                    int byShortfall = right.quantityShortfall().compareTo(left.quantityShortfall());
                    if (byShortfall != 0) {
                        return byShortfall;
                    }
                    if (left.blockTimestamp() == null && right.blockTimestamp() == null) {
                        return 0;
                    }
                    if (left.blockTimestamp() == null) {
                        return 1;
                    }
                    if (right.blockTimestamp() == null) {
                        return -1;
                    }
                    return left.blockTimestamp().compareTo(right.blockTimestamp());
                })
                .limit(20)
                .map(ShortfallSourceAccumulator::toView)
                .toList();
    }

    private List<OnChainBalance> loadOnChainBalances(String sessionId, List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("sessionId").is(sessionId),
                        Criteria.where("walletAddress").in(walletAddresses)
                ))
                .with(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("assetContract"),
                        Sort.Order.asc("capturedAt")
                ));
        return mongoOperations.find(query, OnChainBalance.class);
    }

    private Map<String, Map<String, BigDecimal>> loadLiveCexBalances(UserSession session) {
        Map<String, Map<String, BigDecimal>> liveByAccountRef = new LinkedHashMap<>();
        if (session.getIntegrations() == null) {
            return liveByAccountRef;
        }
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null
                    || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                    || integration.getAccountRef() == null
                    || integration.getIntegrationId() == null) {
                continue;
            }
            WalletRef ref = WalletRef.parse(integration.getAccountRef());
            if (ref.domain() != WalletDomainKind.CEX) {
                continue;
            }
            Optional<CexLiveBalancePort.SnapshotView> snapshotView =
                    cexLiveBalancePort.getSnapshotView(integration.getIntegrationId());
            if (snapshotView.isEmpty()) {
                continue;
            }
            CexLiveBalancePort.SnapshotView view = snapshotView.get();
            if (view.availability() == CexLiveBalancePort.Availability.UNKNOWN) {
                continue;
            }
            liveByAccountRef.put(
                    CexUmbrellaSupport.normalizeAddress(integration.getAccountRef()),
                    view.umbrella()
            );
        }
        return liveByAccountRef;
    }

    private Map<BucketKey, OnChainBalance> latestBalanceByBucket(List<OnChainBalance> balances) {
        Map<BucketKey, OnChainBalance> latest = new LinkedHashMap<>();
        for (OnChainBalance balance : balances) {
            latest.put(bucketKey(balance), balance);
        }
        return latest;
    }

    private Map<BucketKey, AssetLedgerPoint> latestLedgerPointByBucket(List<AssetLedgerPoint> points) {
        Map<BucketKey, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            latest.put(new BucketKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingAssetIdentity()
            ), point);
        }
        return latest;
    }

    private boolean matchesFamily(
            OnChainBalance balance,
            String familyIdentity,
            Map<BucketKey, AssetLedgerPoint> latestPointByBucket
    ) {
        AssetLedgerPoint latestPoint = latestPointByBucket.get(bucketKey(balance));
        String resolvedFamilyIdentity = resolvedFamilyIdentity(
                latestPoint,
                balance.getNetworkId(),
                balance.getAssetSymbol(),
                balance.getAssetContract()
        );
        return Objects.equals(resolvedFamilyIdentity, familyIdentity);
    }

    private BucketKey bucketKey(OnChainBalance balance) {
        return new BucketKey(
                normalizeAddress(balance.getWalletAddress()),
                balance.getNetworkId(),
                AccountingAssetIdentitySupport.positionAssetIdentity(
                        balance.getNetworkId(),
                        balance.getAssetSymbol(),
                        balance.getAssetContract()
                )
        );
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String fallbackFamilyIdentity(NetworkId networkId, String assetSymbol, String assetContract) {
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(assetSymbol, assetContract);
        if (familyIdentity != null && familyIdentity.startsWith("FAMILY:")) {
            return familyIdentity;
        }
        String symbol = normalizeSymbol(assetSymbol);
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(networkId, assetSymbol, assetContract);
        if (!blank(identity)) {
            return identity;
        }
        return symbol.isBlank() ? null : "SYMBOL:" + symbol;
    }

    private static String resolvedFamilyIdentity(
            AssetLedgerPoint latestPoint,
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
        String fallbackFamilyIdentity = fallbackFamilyIdentity(networkId, assetSymbol, assetContract);
        if (latestPoint == null || blank(latestPoint.getAccountingFamilyIdentity())) {
            return fallbackFamilyIdentity;
        }
        String latestFamilyIdentity = latestPoint.getAccountingFamilyIdentity();
        if (!latestFamilyIdentity.startsWith("FAMILY:")
                && fallbackFamilyIdentity != null
                && fallbackFamilyIdentity.startsWith("FAMILY:")) {
            return fallbackFamilyIdentity;
        }
        return latestFamilyIdentity;
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static int unresolvedFlagCountAfter(AssetLedgerPoint latestPoint) {
        if (latestPoint == null || latestPoint.getUnresolvedFlagCountAfter() == null) {
            return 0;
        }
        return latestPoint.getUnresolvedFlagCountAfter();
    }

    private static String uncoveredReason(
            AssetLedgerPoint latestPoint,
            BigDecimal currentQuantity,
            BigDecimal coveredQuantity
    ) {
        if (latestPoint == null) {
            return "missing_replay_point";
        }
        boolean uncoveredQuantity = coveredQuantity == null || currentQuantity == null
                ? true
                : coveredQuantity.compareTo(currentQuantity) < 0;
        boolean incompleteHistory = Boolean.TRUE.equals(latestPoint.getHasIncompleteHistoryAfter());
        boolean unresolvedFlags = Boolean.TRUE.equals(latestPoint.getHasUnresolvedFlagsAfter());
        if (uncoveredQuantity && !incompleteHistory && !unresolvedFlags && isYieldAccrualCandidate(latestPoint)) {
            return "yield_accrual";
        }
        if (uncoveredQuantity) {
            return "coverage_gap";
        }
        if (incompleteHistory || unresolvedFlags) {
            return "history_flags";
        }
        return null;
    }

    private static boolean isYieldAccrualCandidate(AssetLedgerPoint latestPoint) {
        if (latestPoint == null || latestPoint.getBasisEffect() != AssetLedgerPoint.BasisEffect.REALLOCATE_IN) {
            return false;
        }
        return latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.LENDING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.STAKING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.VAULT;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record BucketKey(String walletAddress, NetworkId networkId, String accountingAssetIdentity) {}

    private record ShortfallSourceKey(String walletAddress, String networkId, String txHash, String normalizedType, String protocolName) {}

    private static final class ShortfallSourceAccumulator {
        private final String walletAddress;
        private final String networkId;
        private final String txHash;
        private final Instant blockTimestamp;
        private final String normalizedType;
        private final String protocolName;
        private BigDecimal quantityShortfall = BigDecimal.ZERO;

        private ShortfallSourceAccumulator(AssetLedgerPoint seed) {
            this.walletAddress = seed.getWalletAddress();
            this.networkId = seed.getNetworkId() == null ? null : seed.getNetworkId().name();
            this.txHash = seed.getTxHash();
            this.blockTimestamp = seed.getBlockTimestamp();
            this.normalizedType = seed.getNormalizedType();
            this.protocolName = seed.getProtocolName();
        }

        private void add(BigDecimal delta) {
            quantityShortfall = quantityShortfall.add(zeroIfNull(delta), MC);
        }

        private BigDecimal quantityShortfall() {
            return quantityShortfall;
        }

        private Instant blockTimestamp() {
            return blockTimestamp;
        }

        private AssetLedgerQueryService.ShortfallSourceView toView() {
            return new AssetLedgerQueryService.ShortfallSourceView(
                    walletAddress,
                    networkId,
                    txHash,
                    blockTimestamp,
                    normalizedType,
                    protocolName,
                    quantityShortfall
            );
        }
    }

    private record AllowedScope(Map<String, Set<NetworkId>> networksByAddress) {
        private static AllowedScope from(List<UserSession.SessionWallet> wallets) {
            Map<String, Set<NetworkId>> mapping = new LinkedHashMap<>();
            for (UserSession.SessionWallet wallet : wallets) {
                String address = normalizeAddress(wallet.getAddress());
                if (address.isBlank()) {
                    continue;
                }
                mapping.computeIfAbsent(address, ignored -> new LinkedHashSet<>())
                        .addAll(wallet.getNetworks() == null ? List.of() : wallet.getNetworks());
            }
            return new AllowedScope(mapping);
        }

        private List<String> walletAddresses() {
            return List.copyOf(networksByAddress.keySet());
        }

        private boolean includes(String walletAddress, NetworkId networkId) {
            if (networkId == null) {
                return false;
            }
            Set<NetworkId> allowedNetworks = networksByAddress.get(normalizeAddress(walletAddress));
            return allowedNetworks != null && allowedNetworks.contains(networkId);
        }
    }

    private static final class CexFamilyUmbrellaAccumulator {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal coveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        private final LinkedHashSet<String> priceLookupCandidates = new LinkedHashSet<>();
        private String fallbackSymbol;
        private final List<VenuePositionSlice> venueSlices = new ArrayList<>();

        private void addVenue(String walletAddress, BigDecimal currentQuantity, AssetLedgerPoint latestPoint) {
            BigDecimal exactCoveredQuantity = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(currentQuantity);
            quantity = quantity.add(currentQuantity, MC);
            coveredQuantity = coveredQuantity.add(exactCoveredQuantity, MC);
            if (latestPoint != null && latestPoint.getAvcoAfterUsd() != null && exactCoveredQuantity.signum() > 0) {
                totalCostBasisUsd = totalCostBasisUsd.add(
                        latestPoint.getAvcoAfterUsd().multiply(exactCoveredQuantity, MC),
                        MC
                );
            }
            if (latestPoint != null && exactCoveredQuantity.signum() > 0) {
                BigDecimal netAvcoPoint = latestPoint.getNetAvcoAfterUsd() != null
                        ? latestPoint.getNetAvcoAfterUsd()
                        : latestPoint.getAvcoAfterUsd();
                if (netAvcoPoint != null) {
                    netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                            netAvcoPoint.multiply(exactCoveredQuantity, MC),
                            MC
                    );
                }
            }
            if (latestPoint != null && latestPoint.getAssetSymbol() != null) {
                priceLookupCandidates.addAll(CexUmbrellaSupport.priceLookupCandidates(latestPoint.getAssetSymbol()));
                if (fallbackSymbol == null) {
                    fallbackSymbol = latestPoint.getAssetSymbol();
                }
            }
            venueSlices.add(new VenuePositionSlice(
                    walletAddress,
                    latestPoint == null ? null : latestPoint.getAssetSymbol(),
                    latestPoint == null ? null : latestPoint.getAssetContract(),
                    currentQuantity,
                    exactCoveredQuantity,
                    latestPoint
            ));
        }

        private BigDecimal quantity() {
            return quantity;
        }

        private BigDecimal coveredQuantity() {
            return coveredQuantity;
        }

        private BigDecimal totalCostBasisUsd() {
            return totalCostBasisUsd;
        }

        private BigDecimal netTotalCostBasisUsd() {
            return netTotalCostBasisUsd;
        }

        private List<String> priceLookupCandidates() {
            return List.copyOf(priceLookupCandidates);
        }

        private String fallbackSymbol() {
            return fallbackSymbol;
        }

        private List<VenuePositionSlice> venueSlices() {
            return venueSlices;
        }
    }

    private record VenuePositionSlice(
            String walletAddress,
            String assetSymbol,
            String assetContract,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            AssetLedgerPoint latestPoint
    ) {
    }
}
