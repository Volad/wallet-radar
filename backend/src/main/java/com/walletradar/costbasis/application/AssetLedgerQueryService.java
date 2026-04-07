package com.walletradar.costbasis.application;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.support.AssetLedgerSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.session.application.AccountingUniverseService;
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

/**
 * Session-scoped read service for immutable asset-ledger history.
 */
@Service
@RequiredArgsConstructor
public class AssetLedgerQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final UserSessionRepository userSessionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final MongoOperations mongoOperations;

    public Optional<SessionAssetLedgerView> findSessionFamilyLedger(String sessionId, String familyIdentity) {
        if (sessionId == null || sessionId.isBlank() || familyIdentity == null || familyIdentity.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> toView(session, familyIdentity.trim()));
    }

    private SessionAssetLedgerView toView(UserSession session, String familyIdentity) {
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<AssetLedgerPoint> points = universeScope.memberRefs().isEmpty()
                ? List.of()
                : assetLedgerPointRepository
                .findAllByWalletAddressInAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                        universeScope.memberRefs(),
                        familyIdentity
                );
        Map<String, NormalizedTransaction> normalizedById = findNormalizedTransactions(points);

        List<LedgerPointView> rawPoints = points.stream()
                .map(this::toRawPoint)
                .toList();

        List<EventAccumulator> groupedEvents = groupPoints(points, normalizedById);
        AggregatedState state = new AggregatedState();
        List<TimelineEntryView> timeline = new ArrayList<>();
        List<EventOverlayView> overlays = new ArrayList<>();
        for (EventAccumulator accumulator : groupedEvents) {
            state.apply(accumulator);
            timeline.add(new TimelineEntryView(
                    accumulator.blockTimestamp,
                    accumulator.txHash,
                    accumulator.normalizedTransactionId,
                    accumulator.normalizedType,
                    accumulator.protocolName,
                    accumulator.lifecycleKind,
                    accumulator.lifecycleStage,
                    List.copyOf(accumulator.basisEffects),
                    accumulator.quantityDelta,
                    accumulator.costBasisDeltaUsd,
                    accumulator.realisedPnlDeltaUsd,
                    accumulator.gasDeltaUsd,
                    state.quantity,
                    state.coveredQuantity(),
                    state.uncoveredQuantity,
                    state.totalCostBasisUsd,
                    state.avco()
            ));
            overlays.add(new EventOverlayView(
                    accumulator.normalizedTransactionId,
                    accumulator.txHash,
                    accumulator.blockTimestamp,
                    accumulator.normalizedType,
                    accumulator.protocolName,
                    accumulator.lifecycleKind,
                    List.copyOf(accumulator.walletAddresses),
                    List.copyOf(accumulator.networkIds),
                    List.copyOf(accumulator.flows)
            ));
        }

        CurrentStateView currentState = currentStateView(
                session,
                familyIdentity,
                points,
                state.totalRealisedPnlUsd,
                state.totalGasPaidUsd
        );

        return new SessionAssetLedgerView(
                session.getId(),
                familyIdentity,
                currentState,
                timeline,
                overlays,
                rawPoints
        );
    }

    private CurrentStateView currentStateView(
            UserSession session,
            String familyIdentity,
            List<AssetLedgerPoint> universePoints,
            BigDecimal realisedPnlUsd,
            BigDecimal gasPaidUsd
    ) {
        AllowedScope allowedScope = AllowedScope.from(session.getWallets());
        List<OnChainBalance> scopedBalances = loadOnChainBalances(allowedScope.walletAddresses()).stream()
                .filter(balance -> allowedScope.includes(balance.getWalletAddress(), balance.getNetworkId()))
                .toList();
        Map<BucketKey, OnChainBalance> latestBalances = latestBalanceByBucket(scopedBalances);
        Map<BucketKey, AssetLedgerPoint> latestPointByBucket = latestLedgerPointByBucket(universePoints);

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal coveredQuantity = BigDecimal.ZERO;
        BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
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
        }
        BigDecimal uncoveredQuantity = quantity.subtract(coveredQuantity, MC).max(BigDecimal.ZERO);
        BigDecimal avcoUsd = coveredQuantity.signum() <= 0
                ? null
                : totalCostBasisUsd.divide(coveredQuantity, MC);
        return new CurrentStateView(
                quantity,
                coveredQuantity,
                uncoveredQuantity,
                totalCostBasisUsd,
                avcoUsd,
                realisedPnlUsd,
                gasPaidUsd
        );
    }

    private LedgerPointView toRawPoint(AssetLedgerPoint point) {
        return new LedgerPointView(
                point.getWalletAddress(),
                point.getNetworkId() == null ? null : point.getNetworkId().name(),
                point.getAccountingAssetIdentity(),
                point.getAccountingFamilyIdentity(),
                point.getFamilyDisplaySymbol(),
                point.getAssetSymbol(),
                point.getAssetContract(),
                point.getNormalizedTransactionId(),
                point.getTxHash(),
                point.getCorrelationId(),
                point.getLifecycleChainId(),
                point.getMatchedCounterparty(),
                point.getBlockTimestamp(),
                point.getReplaySequence(),
                point.getNormalizedType(),
                point.getLifecycleKind() == null ? null : point.getLifecycleKind().name(),
                point.getLifecycleStage() == null ? null : point.getLifecycleStage().name(),
                point.getBasisEffect() == null ? null : point.getBasisEffect().name(),
                point.getProtocolName(),
                point.getQuantityDelta(),
                point.getCostBasisDeltaUsd(),
                point.getRealisedPnlDeltaUsd(),
                point.getGasDeltaUsd(),
                point.getQuantityBefore(),
                point.getQuantityAfter(),
                point.getTotalCostBasisBeforeUsd(),
                point.getTotalCostBasisAfterUsd(),
                point.getAvcoBeforeUsd(),
                point.getAvcoAfterUsd(),
                point.getBasisBackedQuantityAfter(),
                point.getUncoveredQuantityDelta(),
                point.getQuantityShortfallAfter(),
                point.getUncoveredQuantityAfter(),
                point.getHasIncompleteHistoryAfter(),
                point.getHasUnresolvedFlagsAfter(),
                point.getUnresolvedFlagCountAfter()
        );
    }

    private Map<String, NormalizedTransaction> findNormalizedTransactions(List<AssetLedgerPoint> points) {
        List<String> normalizedIds = points.stream()
                .map(AssetLedgerPoint::getNormalizedTransactionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, NormalizedTransaction> normalizedById = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : normalizedTransactionRepository.findAllById(normalizedIds)) {
            normalizedById.put(transaction.getId(), transaction);
        }
        return normalizedById;
    }

    private List<OnChainBalance> loadOnChainBalances(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("walletAddress").in(walletAddresses))
                .with(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("assetContract"),
                        Sort.Order.asc("capturedAt")
                ));
        return mongoOperations.find(query, OnChainBalance.class);
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

    private List<EventAccumulator> groupPoints(
            List<AssetLedgerPoint> points,
            Map<String, NormalizedTransaction> normalizedById
    ) {
        Map<String, EventAccumulator> grouped = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            String key = point.getNormalizedTransactionId();
            EventAccumulator accumulator = grouped.computeIfAbsent(
                    key,
                    ignored -> new EventAccumulator(point, normalizedById.get(key))
            );
            accumulator.add(point);
        }
        return List.copyOf(grouped.values());
    }

    public record SessionAssetLedgerView(
            String sessionId,
            String familyIdentity,
            CurrentStateView current,
            List<TimelineEntryView> timeline,
            List<EventOverlayView> events,
            List<LedgerPointView> ledgerPoints
    ) {
    }

    public record CurrentStateView(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal avcoUsd,
            BigDecimal realisedPnlUsd,
            BigDecimal gasPaidUsd
    ) {
    }

    public record TimelineEntryView(
            Instant blockTimestamp,
            String txHash,
            String normalizedTransactionId,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            String lifecycleStage,
            List<String> basisEffects,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal realisedPnlDeltaUsd,
            BigDecimal gasDeltaUsd,
            BigDecimal quantityAfter,
            BigDecimal coveredQuantityAfter,
            BigDecimal uncoveredQuantityAfter,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoAfterUsd
    ) {
    }

    public record EventOverlayView(
            String normalizedTransactionId,
            String txHash,
            Instant blockTimestamp,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            List<String> walletAddresses,
            List<String> networkIds,
            List<EventFlowView> flows
    ) {
    }

    public record EventFlowView(
            String role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            BigDecimal valueUsd,
            String priceSource,
            Integer logIndex
    ) {
    }

    public record LedgerPointView(
            String walletAddress,
            String networkId,
            String accountingAssetIdentity,
            String accountingFamilyIdentity,
            String familyDisplaySymbol,
            String assetSymbol,
            String assetContract,
            String normalizedTransactionId,
            String txHash,
            String correlationId,
            String lifecycleChainId,
            String matchedCounterparty,
            Instant blockTimestamp,
            Long replaySequence,
            String normalizedType,
            String lifecycleKind,
            String lifecycleStage,
            String basisEffect,
            String protocolName,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal realisedPnlDeltaUsd,
            BigDecimal gasDeltaUsd,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal totalCostBasisBeforeUsd,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoBeforeUsd,
            BigDecimal avcoAfterUsd,
            BigDecimal basisBackedQuantityAfter,
            BigDecimal uncoveredQuantityDelta,
            BigDecimal quantityShortfallAfter,
            BigDecimal uncoveredQuantityAfter,
            Boolean hasIncompleteHistoryAfter,
            Boolean hasUnresolvedFlagsAfter,
            Integer unresolvedFlagCountAfter
    ) {
    }

    private record BucketKey(
            String walletAddress,
            NetworkId networkId,
            String accountingAssetIdentity
    ) {
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

    private static final class AggregatedState {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        private BigDecimal totalGasPaidUsd = BigDecimal.ZERO;

        private void apply(EventAccumulator accumulator) {
            quantity = quantity.add(accumulator.quantityDelta, MC);
            uncoveredQuantity = uncoveredQuantity.add(accumulator.uncoveredQuantityDelta, MC);
            totalCostBasisUsd = totalCostBasisUsd.add(accumulator.costBasisDeltaUsd, MC);
            totalRealisedPnlUsd = totalRealisedPnlUsd.add(accumulator.realisedPnlDeltaUsd, MC);
            totalGasPaidUsd = totalGasPaidUsd.add(accumulator.gasDeltaUsd, MC);
            if (quantity.signum() < 0) {
                quantity = BigDecimal.ZERO;
            }
            if (uncoveredQuantity.signum() < 0) {
                uncoveredQuantity = BigDecimal.ZERO;
            }
            if (uncoveredQuantity.compareTo(quantity) > 0) {
                uncoveredQuantity = quantity;
            }
            if (totalCostBasisUsd.signum() < 0) {
                totalCostBasisUsd = BigDecimal.ZERO;
            }
        }

        private BigDecimal coveredQuantity() {
            return quantity.subtract(uncoveredQuantity, MC).max(BigDecimal.ZERO);
        }

        private BigDecimal avco() {
            BigDecimal coveredQuantity = coveredQuantity();
            return coveredQuantity.signum() <= 0 ? null : totalCostBasisUsd.divide(coveredQuantity, MC);
        }
    }

    private static final class EventAccumulator {
        private final String normalizedTransactionId;
        private final String txHash;
        private final Instant blockTimestamp;
        private final String normalizedType;
        private final String protocolName;
        private final String lifecycleKind;
        private final String lifecycleStage;
        private final LinkedHashSet<String> basisEffects = new LinkedHashSet<>();
        private final LinkedHashSet<String> walletAddresses = new LinkedHashSet<>();
        private final LinkedHashSet<String> networkIds = new LinkedHashSet<>();
        private final List<EventFlowView> flows;
        private BigDecimal quantityDelta = BigDecimal.ZERO;
        private BigDecimal costBasisDeltaUsd = BigDecimal.ZERO;
        private BigDecimal realisedPnlDeltaUsd = BigDecimal.ZERO;
        private BigDecimal gasDeltaUsd = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantityDelta = BigDecimal.ZERO;

        private EventAccumulator(AssetLedgerPoint seed, NormalizedTransaction transaction) {
            this.normalizedTransactionId = seed.getNormalizedTransactionId();
            this.txHash = seed.getTxHash();
            this.blockTimestamp = seed.getBlockTimestamp();
            this.normalizedType = seed.getNormalizedType();
            this.protocolName = protocolName(seed, transaction);
            this.lifecycleKind = seed.getLifecycleKind() == null ? null : seed.getLifecycleKind().name();
            this.lifecycleStage = seed.getLifecycleStage() == null ? null : seed.getLifecycleStage().name();
            this.flows = eventFlows(transaction);
        }

        private void add(AssetLedgerPoint point) {
            if (point.getBasisEffect() != null) {
                basisEffects.add(point.getBasisEffect().name());
            }
            if (point.getWalletAddress() != null) {
                walletAddresses.add(point.getWalletAddress());
            }
            if (point.getNetworkId() != null) {
                networkIds.add(point.getNetworkId().name());
            }
            quantityDelta = quantityDelta.add(zeroIfNull(point.getQuantityDelta()), MC);
            costBasisDeltaUsd = costBasisDeltaUsd.add(zeroIfNull(point.getCostBasisDeltaUsd()), MC);
            realisedPnlDeltaUsd = realisedPnlDeltaUsd.add(zeroIfNull(point.getRealisedPnlDeltaUsd()), MC);
            gasDeltaUsd = gasDeltaUsd.add(zeroIfNull(point.getGasDeltaUsd()), MC);
            uncoveredQuantityDelta = uncoveredQuantityDelta.add(zeroIfNull(point.getUncoveredQuantityDelta()), MC);
        }
    }

    private static String protocolName(AssetLedgerPoint point, NormalizedTransaction transaction) {
        if (transaction != null && transaction.getProtocolName() != null && !transaction.getProtocolName().isBlank()) {
            return transaction.getProtocolName();
        }
        return point.getProtocolName();
    }

    private static List<EventFlowView> eventFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .map(flow -> new EventFlowView(
                        flow.getRole() == null ? null : flow.getRole().name(),
                        flow.getAssetContract(),
                        flow.getAssetSymbol(),
                        flow.getQuantityDelta(),
                        flow.getUnitPriceUsd(),
                        flow.getValueUsd(),
                        flow.getPriceSource() == null ? null : flow.getPriceSource().name(),
                        flow.getLogIndex()
                ))
                .toList();
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
