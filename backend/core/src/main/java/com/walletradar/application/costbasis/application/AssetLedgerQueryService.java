package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.application.port.AssetLedgerReadPort;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetLedgerQueryService implements AssetLedgerReadPort {
    private static final BigDecimal GAS_ONLY_BASIS_THRESHOLD = new BigDecimal("0.00000001");
    private final UserSessionRepository userSessionRepository;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final AssetLedgerChartService chartService;
    private final AssetLedgerReconciliationService reconciliationService;

    @Override
    public Optional<SessionAssetLedgerView> findSessionFamilyLedger(String sessionId, String familyIdentity) {
        if (sessionId == null || sessionId.isBlank() || familyIdentity == null || familyIdentity.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim()).map(session -> toView(session, familyIdentity.trim()));
    }

    private SessionAssetLedgerView toView(UserSession session, String familyIdentity) {
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<AssetLedgerPoint> points = universeScope.accountingUniverseId() == null || universeScope.accountingUniverseId().isBlank()
                ? List.of()
                : assetLedgerPointRepository
                .findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                        universeScope.accountingUniverseId(),
                        familyIdentity
                );
        Map<String, NormalizedTransaction> normalizedById = findNormalizedTransactions(points);
        List<AssetLedgerPoint> timelinePoints = points.stream()
                .filter(point -> AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                        familyIdentity,
                        point.getAssetSymbol()
                ))
                .toList();
        AssetLedgerChartService.ChartProjection chartProjection =
                chartService.buildTimelineProjection(familyIdentity, timelinePoints, normalizedById);
        CurrentStateView currentState = reconciliationService.currentStateView(
                session,
                familyIdentity,
                points,
                chartProjection.totalRealisedPnlUsd(),
                chartProjection.totalNetRealisedPnlUsd(),
                chartProjection.totalGasPaidUsd()
        );
        FullSessionCurrentView fullSessionCurrent = reconciliationService.fullSessionCurrentView(points, familyIdentity);
        return new SessionAssetLedgerView(
                session.getId(),
                familyIdentity,
                currentState,
                fullSessionCurrent,
                chartProjection.timeline(),
                chartProjection.overlays(),
                chartService.mapRawPoints(points)
        );
    }

    private Map<String, NormalizedTransaction> findNormalizedTransactions(List<AssetLedgerPoint> points) {
        List<String> normalizedIds = points.stream()
                .map(AssetLedgerPoint::getNormalizedTransactionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) return Map.of();
        Map<String, NormalizedTransaction> normalizedById = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : normalizedTransactionRepository.findAllById(normalizedIds)) {
            normalizedById.put(transaction.getId(), transaction);
        }
        return normalizedById;
    }

    static BigDecimal gasOnlyAvcoAfter(AssetLedgerPoint point) {
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

    public record SessionAssetLedgerView(String sessionId, String familyIdentity, CurrentStateView current,
                                         FullSessionCurrentView fullSessionCurrent, List<TimelineEntryView> timeline,
                                         List<EventOverlayView> events, List<LedgerPointView> ledgerPoints) {}
    public record FullSessionCurrentView(BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal uncoveredQuantity,
                                         BigDecimal totalCostBasisUsd, BigDecimal avcoUsd,
                                         BigDecimal netTotalCostBasisUsd, BigDecimal netAvcoUsd) {}
    public record CurrentStateView(BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal uncoveredQuantity,
                                   BigDecimal totalCostBasisUsd, BigDecimal avcoUsd, BigDecimal netTotalCostBasisUsd,
                                   BigDecimal netAvcoUsd, BigDecimal realisedPnlUsd, BigDecimal netRealisedPnlUsd,
                                   BigDecimal gasPaidUsd, List<UncoveredBucketView> uncoveredBuckets,
                                   List<ShortfallSourceView> shortfallSources) {}
    public record UncoveredBucketView(String walletAddress, String networkId, String assetSymbol, String assetContract,
                                      BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal uncoveredQuantity,
                                      String uncoveredReason, String latestTxHash, String latestNormalizedType,
                                      String latestBasisEffect, String latestProtocolName, boolean hasIncompleteHistory,
                                      boolean hasUnresolvedFlags, Integer unresolvedFlagCount) {}
    public record ShortfallSourceView(String walletAddress, String networkId, String txHash, Instant blockTimestamp,
                                      String normalizedType, String protocolName, BigDecimal quantityShortfall) {}
    public record TimelineEntryView(Instant blockTimestamp, String txHash, String eventGroupId,
                                    String normalizedTransactionId, String normalizedType, String protocolName,
                                    String lifecycleKind, String lifecycleStage, List<String> basisEffects,
                                    BigDecimal quantityDelta, BigDecimal costBasisDeltaUsd,
                                    BigDecimal realisedPnlDeltaUsd, BigDecimal gasDeltaUsd, BigDecimal quantityAfter,
                                    BigDecimal coveredQuantityAfter, BigDecimal uncoveredQuantityAfter,
                                    BigDecimal totalCostBasisAfterUsd, BigDecimal avcoBeforeUsd, BigDecimal avcoAfterUsd,
                                    BigDecimal netTotalCostBasisAfterUsd, BigDecimal netAvcoBeforeUsd,
                                    BigDecimal netAvcoAfterUsd, String avcoKind, String fromAddress, String toAddress,
                                    List<String> memberNormalizedTransactionIds) {}
    public record EventOverlayView(String eventGroupId, String normalizedTransactionId, String txHash,
                                   Instant blockTimestamp, String normalizedType, String protocolName,
                                   String lifecycleKind, List<String> walletAddresses, List<String> networkIds,
                                   List<EventFlowView> flows, String fromAddress, String toAddress,
                                   List<String> memberNormalizedTransactionIds) {}
    public record EventFlowView(String role, String assetContract, String assetSymbol, BigDecimal quantityDelta,
                                BigDecimal unitPriceUsd, BigDecimal valueUsd, String priceSource, Integer logIndex) {}
    public record LedgerPointView(String walletAddress, String networkId, String accountingAssetIdentity,
                                  String accountingFamilyIdentity, String familyDisplaySymbol, String assetSymbol,
                                  String assetContract, String normalizedTransactionId, String txHash,
                                  String correlationId, String lifecycleChainId, String matchedCounterparty,
                                  Instant blockTimestamp, Long replaySequence, String normalizedType,
                                  String lifecycleKind, String lifecycleStage, String basisEffect, String protocolName,
                                  BigDecimal quantityDelta, BigDecimal costBasisDeltaUsd, BigDecimal realisedPnlDeltaUsd,
                                  BigDecimal gasDeltaUsd, BigDecimal quantityBefore, BigDecimal quantityAfter,
                                  BigDecimal totalCostBasisBeforeUsd, BigDecimal totalCostBasisAfterUsd,
                                  BigDecimal avcoBeforeUsd, BigDecimal avcoAfterUsd,
                                  BigDecimal netTotalCostBasisBeforeUsd, BigDecimal netTotalCostBasisAfterUsd,
                                  BigDecimal netAvcoBeforeUsd, BigDecimal netAvcoAfterUsd,
                                  BigDecimal netCostBasisDeltaUsd, BigDecimal netRealisedPnlDeltaUsd,
                                  BigDecimal basisBackedQuantityAfter, BigDecimal uncoveredQuantityDelta,
                                  BigDecimal quantityShortfallAfter, BigDecimal uncoveredQuantityAfter,
                                  Boolean hasIncompleteHistoryAfter, Boolean hasUnresolvedFlagsAfter,
                                  Integer unresolvedFlagCountAfter) {}
}
