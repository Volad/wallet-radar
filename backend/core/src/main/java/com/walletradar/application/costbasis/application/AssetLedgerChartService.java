package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
class AssetLedgerChartService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String AVCO_KIND_PRIMARY_FLOW = "PRIMARY_FLOW";
    private static final String AVCO_KIND_UNAVAILABLE = "UNAVAILABLE";
    private static final BigDecimal GAS_ONLY_BASIS_THRESHOLD = new BigDecimal("0.00000001");

    List<AssetLedgerQueryService.LedgerPointView> mapRawPoints(List<AssetLedgerPoint> points) {
        return points.stream().map(this::toRawPoint).toList();
    }

    ChartProjection buildTimelineProjection(
            String familyIdentity,
            List<AssetLedgerPoint> timelinePoints,
            Map<String, NormalizedTransaction> normalizedById
    ) {
        List<EventAccumulator> groupedEvents = groupPoints(timelinePoints, normalizedById);
        List<DisplayEventAccumulator> displayEvents = collapseDisplayEvents(groupedEvents);

        AggregatedState state = new AggregatedState();
        Map<BucketKey, BucketAvcoState> liveAvcoBuckets = new LinkedHashMap<>();
        BigDecimal previousAvcoAfterUsd = null;
        BigDecimal previousNetAvcoAfterUsd = null;
        List<AssetLedgerQueryService.TimelineEntryView> timeline = new ArrayList<>();
        List<AssetLedgerQueryService.EventOverlayView> overlays = new ArrayList<>();

        for (DisplayEventAccumulator accumulator : displayEvents) {
            state.apply(accumulator);
            applyMethodBBuckets(familyIdentity, accumulator.memberPoints(), liveAvcoBuckets);
            CoveredWeightedAvco series = coveredWeightedFamilyAvco(liveAvcoBuckets);
            BigDecimal avcoAfterUsd = series.marketAvco();
            BigDecimal netAvcoAfterUsd = series.netAvco();
            String avcoKind = avcoAfterUsd == null ? AVCO_KIND_UNAVAILABLE : AVCO_KIND_PRIMARY_FLOW;

            timeline.add(new AssetLedgerQueryService.TimelineEntryView(
                    accumulator.blockTimestamp,
                    accumulator.txHash,
                    accumulator.eventGroupId,
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
                    previousAvcoAfterUsd,
                    avcoAfterUsd,
                    state.netTotalCostBasisUsd,
                    previousNetAvcoAfterUsd,
                    netAvcoAfterUsd,
                    avcoKind,
                    accumulator.fromAddress,
                    accumulator.toAddress,
                    List.copyOf(accumulator.memberNormalizedTransactionIds)
            ));
            previousAvcoAfterUsd = avcoAfterUsd;
            previousNetAvcoAfterUsd = netAvcoAfterUsd;

            overlays.add(new AssetLedgerQueryService.EventOverlayView(
                    accumulator.eventGroupId,
                    accumulator.normalizedTransactionId,
                    accumulator.txHash,
                    accumulator.blockTimestamp,
                    accumulator.normalizedType,
                    accumulator.protocolName,
                    accumulator.lifecycleKind,
                    List.copyOf(accumulator.walletAddresses),
                    List.copyOf(accumulator.networkIds),
                    List.copyOf(accumulator.flows),
                    accumulator.fromAddress,
                    accumulator.toAddress,
                    List.copyOf(accumulator.memberNormalizedTransactionIds)
            ));
        }

        return new ChartProjection(
                List.copyOf(timeline),
                List.copyOf(overlays),
                state.totalRealisedPnlUsd,
                state.totalNetRealisedPnlUsd,
                state.totalGasPaidUsd
        );
    }

    record ChartProjection(
            List<AssetLedgerQueryService.TimelineEntryView> timeline,
            List<AssetLedgerQueryService.EventOverlayView> overlays,
            BigDecimal totalRealisedPnlUsd,
            BigDecimal totalNetRealisedPnlUsd,
            BigDecimal totalGasPaidUsd
    ) {
    }

    private AssetLedgerQueryService.LedgerPointView toRawPoint(AssetLedgerPoint point) {
        return new AssetLedgerQueryService.LedgerPointView(
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
                gasOnlyAvcoAfter(point),
                point.getNetTotalCostBasisBeforeUsd(),
                point.getNetTotalCostBasisAfterUsd(),
                point.getNetAvcoBeforeUsd(),
                point.getNetAvcoAfterUsd(),
                point.getNetCostBasisDeltaUsd(),
                point.getNetRealisedPnlDeltaUsd(),
                point.getBasisBackedQuantityAfter(),
                point.getUncoveredQuantityDelta(),
                point.getQuantityShortfallAfter(),
                point.getUncoveredQuantityAfter(),
                point.getHasIncompleteHistoryAfter(),
                point.getHasUnresolvedFlagsAfter(),
                point.getUnresolvedFlagCountAfter()
        );
    }

    private static BigDecimal gasOnlyAvcoAfter(AssetLedgerPoint point) {
        if (point == null) {
            return null;
        }
        BigDecimal basisBacked = point.getBasisBackedQuantityAfter();
        if (basisBacked == null || basisBacked.compareTo(GAS_ONLY_BASIS_THRESHOLD) >= 0) {
            return point.getAvcoAfterUsd();
        }
        AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
        String normalizedType = point.getNormalizedType();
        boolean isGasOnlyEvent = basisEffect == AssetLedgerPoint.BasisEffect.GAS_ONLY
                || "SPONSORED_GAS_IN".equals(normalizedType)
                || "REWARD_CLAIM".equals(normalizedType);
        return isGasOnlyEvent ? null : point.getAvcoAfterUsd();
    }

    private static void applyMethodBBuckets(
            String familyIdentity,
            List<AssetLedgerPoint> memberPoints,
            Map<BucketKey, BucketAvcoState> liveAvcoBuckets
    ) {
        if (memberPoints == null || memberPoints.isEmpty()) {
            return;
        }
        Map<BucketKey, AssetLedgerPoint> lastPointByBucket = new LinkedHashMap<>();
        memberPoints.stream()
                .filter(point -> AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                        familyIdentity,
                        point.getAssetSymbol()
                ))
                .sorted(Comparator.comparing(
                        AssetLedgerPoint::getReplaySequence,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .forEach(point -> lastPointByBucket.put(methodBBucketKey(point), point));

        for (Map.Entry<BucketKey, AssetLedgerPoint> entry : lastPointByBucket.entrySet()) {
            AssetLedgerPoint point = entry.getValue();
            BigDecimal quantityAfter = zeroIfNull(point.getQuantityAfter());
            BigDecimal covered = zeroIfNull(point.getBasisBackedQuantityAfter())
                    .min(quantityAfter)
                    .max(BigDecimal.ZERO);
            liveAvcoBuckets.put(
                    entry.getKey(),
                    new BucketAvcoState(point.getAvcoAfterUsd(), point.getNetAvcoAfterUsd(), covered)
            );
        }
    }

    private static CoveredWeightedAvco coveredWeightedFamilyAvco(Map<BucketKey, BucketAvcoState> liveAvcoBuckets) {
        BigDecimal coveredTotal = BigDecimal.ZERO;
        BigDecimal marketBasisTotal = BigDecimal.ZERO;
        BigDecimal netBasisTotal = BigDecimal.ZERO;
        for (BucketAvcoState bucket : liveAvcoBuckets.values()) {
            BigDecimal covered = bucket.coveredQuantity();
            if (covered == null || covered.signum() <= 0) {
                continue;
            }
            coveredTotal = coveredTotal.add(covered, MC);
            if (bucket.avcoAfterUsd() != null) {
                marketBasisTotal = marketBasisTotal.add(bucket.avcoAfterUsd().multiply(covered, MC), MC);
            }
            BigDecimal netAvco = bucket.netAvcoAfterUsd() != null
                    ? bucket.netAvcoAfterUsd()
                    : bucket.avcoAfterUsd();
            if (netAvco != null) {
                netBasisTotal = netBasisTotal.add(netAvco.multiply(covered, MC), MC);
            }
        }
        if (coveredTotal.signum() <= 0) {
            return new CoveredWeightedAvco(null, null);
        }
        return new CoveredWeightedAvco(
                marketBasisTotal.divide(coveredTotal, MC),
                netBasisTotal.divide(coveredTotal, MC)
        );
    }

    private static BucketKey methodBBucketKey(AssetLedgerPoint point) {
        return new BucketKey(
                normalizeAddress(point.getWalletAddress()),
                point.getNetworkId(),
                point.getAccountingAssetIdentity()
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

    private List<DisplayEventAccumulator> collapseDisplayEvents(List<EventAccumulator> rawEvents) {
        if (rawEvents.isEmpty()) {
            return List.of();
        }
        List<DisplayEventAccumulator> collapsed = new ArrayList<>();
        boolean[] consumed = new boolean[rawEvents.size()];
        for (int index = 0; index < rawEvents.size(); index++) {
            if (consumed[index]) {
                continue;
            }
            EventAccumulator current = rawEvents.get(index);
            int pairIndex = findInternalTransferPairIndex(index, rawEvents, consumed);
            if (pairIndex >= 0) {
                consumed[index] = true;
                consumed[pairIndex] = true;
                collapsed.add(DisplayEventAccumulator.internalTransfer(rawEvents.get(index), rawEvents.get(pairIndex)));
                continue;
            }
            consumed[index] = true;
            collapsed.add(DisplayEventAccumulator.single(current));
        }
        return List.copyOf(collapsed);
    }

    private int findInternalTransferPairIndex(int seedIndex, List<EventAccumulator> rawEvents, boolean[] consumed) {
        EventAccumulator seed = rawEvents.get(seedIndex);
        if (!isExternalTransferOut(seed)) {
            return -1;
        }
        for (int candidateIndex = seedIndex + 1; candidateIndex < rawEvents.size(); candidateIndex++) {
            if (consumed[candidateIndex]) {
                continue;
            }
            EventAccumulator candidate = rawEvents.get(candidateIndex);
            if (isInternalTransferPair(seed, candidate)) {
                return candidateIndex;
            }
        }
        return -1;
    }

    private boolean isInternalTransferPair(EventAccumulator outbound, EventAccumulator inbound) {
        if (!isExternalTransferOut(outbound) || !isExternalTransferIn(inbound)) {
            return false;
        }
        if (blank(outbound.txHash) || !Objects.equals(outbound.txHash, inbound.txHash)) {
            return false;
        }
        if (blank(outbound.primaryWalletAddress) || blank(inbound.primaryWalletAddress)) {
            return false;
        }
        if (!counterpartyCompatible(outbound, inbound)) {
            return false;
        }
        return networkCompatible(outbound, inbound);
    }

    private static boolean counterpartyCompatible(EventAccumulator outbound, EventAccumulator inbound) {
        boolean reciprocal = !blank(outbound.matchedCounterparty)
                && !blank(inbound.matchedCounterparty)
                && Objects.equals(normalizeAddress(outbound.primaryWalletAddress), normalizeAddress(inbound.matchedCounterparty))
                && Objects.equals(normalizeAddress(inbound.primaryWalletAddress), normalizeAddress(outbound.matchedCounterparty));
        if (reciprocal) {
            return true;
        }
        return !blank(outbound.matchedCounterparty)
                && Objects.equals(normalizeAddress(outbound.matchedCounterparty), normalizeAddress(inbound.primaryWalletAddress));
    }

    private static boolean networkCompatible(EventAccumulator left, EventAccumulator right) {
        if (left.networkIds.isEmpty() || right.networkIds.isEmpty()) {
            return true;
        }
        for (String networkId : left.networkIds) {
            if (right.networkIds.contains(networkId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExternalTransferOut(EventAccumulator event) {
        return "EXTERNAL_TRANSFER_OUT".equals(event.normalizedType)
                || "FIAT_EXIT".equals(event.normalizedType);
    }

    private static boolean isExternalTransferIn(EventAccumulator event) {
        return "EXTERNAL_TRANSFER_IN".equals(event.normalizedType);
    }

    private static String protocolName(AssetLedgerPoint point, NormalizedTransaction transaction) {
        if (transaction != null && transaction.getProtocolName() != null && !transaction.getProtocolName().isBlank()) {
            return transaction.getProtocolName();
        }
        return point.getProtocolName();
    }

    private static List<AssetLedgerQueryService.EventFlowView> eventFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .map(flow -> new AssetLedgerQueryService.EventFlowView(
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

    private static String inferredFromAddress(EventAccumulator event) {
        if (isExternalTransferOut(event)) {
            return event.primaryWalletAddress;
        }
        if (isExternalTransferIn(event)) {
            return firstNonBlank(event.matchedCounterparty, event.counterpartyAddress);
        }
        String counterparty = firstNonBlank(event.counterpartyAddress, event.matchedCounterparty);
        if (blank(counterparty) || blank(event.primaryWalletAddress)) {
            return null;
        }
        EventDirection direction = inferEventDirection(event);
        if (direction == EventDirection.INBOUND) {
            return counterparty;
        }
        if (direction == EventDirection.OUTBOUND || direction == EventDirection.MIXED) {
            return event.primaryWalletAddress;
        }
        return null;
    }

    private static String inferredToAddress(EventAccumulator event) {
        if (isExternalTransferOut(event)) {
            return firstNonBlank(event.matchedCounterparty, event.counterpartyAddress);
        }
        if (isExternalTransferIn(event)) {
            return event.primaryWalletAddress;
        }
        String counterparty = firstNonBlank(event.counterpartyAddress, event.matchedCounterparty);
        if (blank(counterparty) || blank(event.primaryWalletAddress)) {
            return null;
        }
        EventDirection direction = inferEventDirection(event);
        if (direction == EventDirection.INBOUND) {
            return event.primaryWalletAddress;
        }
        if (direction == EventDirection.OUTBOUND || direction == EventDirection.MIXED) {
            return counterparty;
        }
        return null;
    }

    private enum EventDirection {
        OUTBOUND,
        INBOUND,
        MIXED,
        UNKNOWN
    }

    private static EventDirection inferEventDirection(EventAccumulator event) {
        boolean outbound = event.quantityDelta.signum() < 0
                || event.basisEffects.contains("CARRY_OUT")
                || event.basisEffects.contains("DISPOSE")
                || event.basisEffects.contains("REALLOCATE_OUT")
                || event.basisEffects.contains("GAS_ONLY");
        boolean inbound = event.quantityDelta.signum() > 0
                || event.basisEffects.contains("CARRY_IN")
                || event.basisEffects.contains("ACQUIRE")
                || event.basisEffects.contains("REALLOCATE_IN");
        if (outbound && inbound) {
            return EventDirection.MIXED;
        }
        if (outbound) {
            return EventDirection.OUTBOUND;
        }
        if (inbound) {
            return EventDirection.INBOUND;
        }
        return EventDirection.UNKNOWN;
    }

    private static String internalTransferGroupId(EventAccumulator outbound, EventAccumulator inbound) {
        String txHash = blank(outbound.txHash) ? outbound.normalizedTransactionId : outbound.txHash;
        return txHash
                + ":internal:"
                + normalizeAddress(outbound.primaryWalletAddress)
                + ":"
                + normalizeAddress(inbound.primaryWalletAddress);
    }

    private static String firstNonBlank(String left, String right) {
        if (!blank(left)) {
            return left;
        }
        return blank(right) ? null : right;
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record BucketKey(String walletAddress, com.walletradar.domain.common.NetworkId networkId, String accountingAssetIdentity) {}

    private record BucketAvcoState(BigDecimal avcoAfterUsd, BigDecimal netAvcoAfterUsd, BigDecimal coveredQuantity) {}

    private record CoveredWeightedAvco(BigDecimal marketAvco, BigDecimal netAvco) {}

    private static final class AggregatedState {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        private BigDecimal totalNetRealisedPnlUsd = BigDecimal.ZERO;
        private BigDecimal totalGasPaidUsd = BigDecimal.ZERO;

        private void apply(DisplayEventAccumulator accumulator) {
            quantity = quantity.add(accumulator.quantityDelta, MC);
            uncoveredQuantity = uncoveredQuantity.add(accumulator.uncoveredQuantityDelta, MC);
            totalCostBasisUsd = totalCostBasisUsd.add(accumulator.costBasisDeltaUsd, MC);
            netTotalCostBasisUsd = netTotalCostBasisUsd.add(accumulator.netCostBasisDeltaUsd, MC);
            totalRealisedPnlUsd = totalRealisedPnlUsd.add(accumulator.realisedPnlDeltaUsd, MC);
            totalNetRealisedPnlUsd = totalNetRealisedPnlUsd.add(accumulator.netRealisedPnlDeltaUsd, MC);
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
            if (netTotalCostBasisUsd.signum() < 0) {
                netTotalCostBasisUsd = BigDecimal.ZERO;
            }
        }

        private BigDecimal coveredQuantity() {
            return quantity.subtract(uncoveredQuantity, MC).max(BigDecimal.ZERO);
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
        private final String primaryWalletAddress;
        private final String matchedCounterparty;
        private final String counterpartyAddress;
        private final LinkedHashSet<String> basisEffects = new LinkedHashSet<>();
        private final LinkedHashSet<String> walletAddresses = new LinkedHashSet<>();
        private final LinkedHashSet<String> networkIds = new LinkedHashSet<>();
        private final List<AssetLedgerQueryService.EventFlowView> flows;
        private BigDecimal quantityDelta = BigDecimal.ZERO;
        private BigDecimal costBasisDeltaUsd = BigDecimal.ZERO;
        private BigDecimal netCostBasisDeltaUsd = BigDecimal.ZERO;
        private BigDecimal realisedPnlDeltaUsd = BigDecimal.ZERO;
        private BigDecimal netRealisedPnlDeltaUsd = BigDecimal.ZERO;
        private BigDecimal gasDeltaUsd = BigDecimal.ZERO;
        private BigDecimal uncoveredQuantityDelta = BigDecimal.ZERO;
        private final List<AssetLedgerPoint> memberPoints = new ArrayList<>();

        private EventAccumulator(AssetLedgerPoint seed, NormalizedTransaction transaction) {
            this.normalizedTransactionId = seed.getNormalizedTransactionId();
            this.txHash = seed.getTxHash();
            this.blockTimestamp = seed.getBlockTimestamp();
            this.normalizedType = seed.getNormalizedType();
            this.protocolName = protocolName(seed, transaction);
            this.lifecycleKind = seed.getLifecycleKind() == null ? null : seed.getLifecycleKind().name();
            this.lifecycleStage = seed.getLifecycleStage() == null ? null : seed.getLifecycleStage().name();
            this.primaryWalletAddress = blank(seed.getWalletAddress())
                    ? transaction == null ? null : transaction.getWalletAddress()
                    : seed.getWalletAddress();
            this.matchedCounterparty = firstNonBlank(
                    seed.getMatchedCounterparty(),
                    transaction == null ? null : transaction.getMatchedCounterparty()
            );
            this.counterpartyAddress = transaction == null ? null : transaction.getCounterpartyAddress();
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
            netCostBasisDeltaUsd = netCostBasisDeltaUsd.add(
                    zeroIfNull(point.getNetCostBasisDeltaUsd() != null
                            ? point.getNetCostBasisDeltaUsd()
                            : point.getCostBasisDeltaUsd()),
                    MC
            );
            realisedPnlDeltaUsd = realisedPnlDeltaUsd.add(zeroIfNull(point.getRealisedPnlDeltaUsd()), MC);
            netRealisedPnlDeltaUsd = netRealisedPnlDeltaUsd.add(
                    zeroIfNull(point.getNetRealisedPnlDeltaUsd() != null
                            ? point.getNetRealisedPnlDeltaUsd()
                            : point.getRealisedPnlDeltaUsd()),
                    MC
            );
            gasDeltaUsd = gasDeltaUsd.add(zeroIfNull(point.getGasDeltaUsd()), MC);
            uncoveredQuantityDelta = uncoveredQuantityDelta.add(zeroIfNull(point.getUncoveredQuantityDelta()), MC);
            memberPoints.add(point);
        }

        private List<AssetLedgerPoint> memberPoints() {
            return List.copyOf(memberPoints);
        }
    }

    private static final class DisplayEventAccumulator {
        private final String eventGroupId;
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
        private final List<AssetLedgerQueryService.EventFlowView> flows;
        private final List<String> memberNormalizedTransactionIds;
        private final String fromAddress;
        private final String toAddress;
        private final BigDecimal quantityDelta;
        private final BigDecimal costBasisDeltaUsd;
        private final BigDecimal netCostBasisDeltaUsd;
        private final BigDecimal realisedPnlDeltaUsd;
        private final BigDecimal netRealisedPnlDeltaUsd;
        private final BigDecimal gasDeltaUsd;
        private final BigDecimal uncoveredQuantityDelta;
        private final List<AssetLedgerPoint> memberPoints;

        private DisplayEventAccumulator(
                String eventGroupId,
                String normalizedTransactionId,
                String txHash,
                Instant blockTimestamp,
                String normalizedType,
                String protocolName,
                String lifecycleKind,
                String lifecycleStage,
                Set<String> basisEffects,
                Set<String> walletAddresses,
                Set<String> networkIds,
                List<AssetLedgerQueryService.EventFlowView> flows,
                List<String> memberNormalizedTransactionIds,
                String fromAddress,
                String toAddress,
                BigDecimal quantityDelta,
                BigDecimal costBasisDeltaUsd,
                BigDecimal netCostBasisDeltaUsd,
                BigDecimal realisedPnlDeltaUsd,
                BigDecimal netRealisedPnlDeltaUsd,
                BigDecimal gasDeltaUsd,
                BigDecimal uncoveredQuantityDelta,
                List<AssetLedgerPoint> memberPoints
        ) {
            this.eventGroupId = eventGroupId;
            this.normalizedTransactionId = normalizedTransactionId;
            this.txHash = txHash;
            this.blockTimestamp = blockTimestamp;
            this.normalizedType = normalizedType;
            this.protocolName = protocolName;
            this.lifecycleKind = lifecycleKind;
            this.lifecycleStage = lifecycleStage;
            this.basisEffects.addAll(basisEffects);
            this.walletAddresses.addAll(walletAddresses);
            this.networkIds.addAll(networkIds);
            this.flows = List.copyOf(flows);
            this.memberNormalizedTransactionIds = List.copyOf(memberNormalizedTransactionIds);
            this.fromAddress = fromAddress;
            this.toAddress = toAddress;
            this.quantityDelta = quantityDelta;
            this.costBasisDeltaUsd = costBasisDeltaUsd;
            this.netCostBasisDeltaUsd = netCostBasisDeltaUsd;
            this.realisedPnlDeltaUsd = realisedPnlDeltaUsd;
            this.netRealisedPnlDeltaUsd = netRealisedPnlDeltaUsd;
            this.gasDeltaUsd = gasDeltaUsd;
            this.uncoveredQuantityDelta = uncoveredQuantityDelta;
            this.memberPoints = List.copyOf(memberPoints);
        }

        private List<AssetLedgerPoint> memberPoints() {
            return memberPoints;
        }

        private static DisplayEventAccumulator single(EventAccumulator event) {
            return new DisplayEventAccumulator(
                    event.normalizedTransactionId,
                    event.normalizedTransactionId,
                    event.txHash,
                    event.blockTimestamp,
                    event.normalizedType,
                    event.protocolName,
                    event.lifecycleKind,
                    event.lifecycleStage,
                    event.basisEffects,
                    event.walletAddresses,
                    event.networkIds,
                    event.flows,
                    List.of(event.normalizedTransactionId),
                    inferredFromAddress(event),
                    inferredToAddress(event),
                    event.quantityDelta,
                    event.costBasisDeltaUsd,
                    event.netCostBasisDeltaUsd,
                    event.realisedPnlDeltaUsd,
                    event.netRealisedPnlDeltaUsd,
                    event.gasDeltaUsd,
                    event.uncoveredQuantityDelta,
                    event.memberPoints()
            );
        }

        private static DisplayEventAccumulator internalTransfer(EventAccumulator outbound, EventAccumulator inbound) {
            LinkedHashSet<String> basisEffects = new LinkedHashSet<>();
            basisEffects.addAll(outbound.basisEffects);
            basisEffects.addAll(inbound.basisEffects);
            LinkedHashSet<String> walletAddresses = new LinkedHashSet<>();
            walletAddresses.addAll(outbound.walletAddresses);
            walletAddresses.addAll(inbound.walletAddresses);
            LinkedHashSet<String> networkIds = new LinkedHashSet<>();
            networkIds.addAll(outbound.networkIds);
            networkIds.addAll(inbound.networkIds);
            List<AssetLedgerQueryService.EventFlowView> flows = new ArrayList<>(outbound.flows);
            flows.addAll(inbound.flows);
            List<AssetLedgerPoint> memberPoints = new ArrayList<>(outbound.memberPoints());
            memberPoints.addAll(inbound.memberPoints());
            return new DisplayEventAccumulator(
                    internalTransferGroupId(outbound, inbound),
                    outbound.normalizedTransactionId,
                    outbound.txHash,
                    outbound.blockTimestamp,
                    "INTERNAL_TRANSFER",
                    firstNonBlank(outbound.protocolName, inbound.protocolName),
                    firstNonBlank(outbound.lifecycleKind, inbound.lifecycleKind),
                    firstNonBlank(outbound.lifecycleStage, inbound.lifecycleStage),
                    basisEffects,
                    walletAddresses,
                    networkIds,
                    flows,
                    List.of(outbound.normalizedTransactionId, inbound.normalizedTransactionId),
                    outbound.primaryWalletAddress,
                    inbound.primaryWalletAddress,
                    outbound.quantityDelta.add(inbound.quantityDelta, MC),
                    outbound.costBasisDeltaUsd.add(inbound.costBasisDeltaUsd, MC),
                    outbound.netCostBasisDeltaUsd.add(inbound.netCostBasisDeltaUsd, MC),
                    outbound.realisedPnlDeltaUsd.add(inbound.realisedPnlDeltaUsd, MC),
                    outbound.netRealisedPnlDeltaUsd.add(inbound.netRealisedPnlDeltaUsd, MC),
                    outbound.gasDeltaUsd.add(inbound.gasDeltaUsd, MC),
                    outbound.uncoveredQuantityDelta.add(inbound.uncoveredQuantityDelta, MC),
                    memberPoints
            );
        }
    }
}
