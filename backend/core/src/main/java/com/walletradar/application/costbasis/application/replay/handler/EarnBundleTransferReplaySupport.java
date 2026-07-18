package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayToleranceSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.application.replay.support.TransferEarnPrincipalReplaySupport;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class EarnBundleTransferReplaySupport {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal EARN_PRINCIPAL_DUST_BASIS_THRESHOLD =
            TransferEarnPrincipalReplaySupport.EARN_PRINCIPAL_DUST_BASIS_THRESHOLD;
    private static final BigDecimal EARN_CORRIDOR_INFLATED_BASIS_MULTIPLIER =
            TransferEarnPrincipalReplaySupport.EARN_CORRIDOR_INFLATED_BASIS_MULTIPLIER;
    private static final BigDecimal EARN_BUNDLE_PARTIAL_LEG_THRESHOLD =
            TransferEarnPrincipalReplaySupport.EARN_BUNDLE_PARTIAL_LEG_THRESHOLD;

    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayPendingTransferMatcher matcher;
    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayTransferClassifier classifier;
    private final ReplayMarketAuthority replayMarketAuthority;
    private final BridgeTransferReplaySupport bridgeTransferReplaySupport;

    public AssetLedgerPoint.BasisEffect applyBybitMultiLegBundleTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        TransferPendingKey transferKey = keyFactory.transferKey(transaction, flow);
        if (transferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries(),
                    true
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                boolean isPartialLeg = carry.quantity() != null
                        && pendingInbound.quantity() != null
                        && carry.quantity().compareTo(pendingInbound.quantity()) < 0
                        && carry.quantity().compareTo(EARN_BUNDLE_PARTIAL_LEG_THRESHOLD) > 0;
                if (isPartialLeg) {
                    BigDecimal fullProvisional = pendingInbound.provisionalBasisUsd() != null
                            ? pendingInbound.provisionalBasisUsd() : BigDecimal.ZERO;
                    BigDecimal scaledProvisional = pendingInbound.quantity().signum() > 0
                            ? fullProvisional.multiply(
                            carry.quantity().divide(pendingInbound.quantity(), MC), MC)
                            : BigDecimal.ZERO;
                    BigDecimal remainingProvisional = fullProvisional.subtract(scaledProvisional, MC);
                    CarryTransfer slicedForAttach = new CarryTransfer(
                            carry.quantity(), BigDecimal.ZERO, carry.quantity(),
                            BigDecimal.ZERO, null, null, null,
                            true, pendingInbound.assetKey(), scaledProvisional,
                            pendingInbound.sourceFlowRef(), pendingInbound.materialized()
                    );
                    bridgeTransferReplaySupport.attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            slicedForAttach,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                    BigDecimal remainingQty = pendingInbound.quantity().subtract(carry.quantity(), MC);
                    queue.addFirst(
                            pendingInbound.withReducedQuantityAndProvisional(remainingQty, remainingProvisional));
                } else {
                    bridgeTransferReplaySupport.attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            pendingInbound,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                }
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(transferKey);
        BigDecimal remaining = flow.getQuantityDelta().abs();
        boolean restoredAny = false;
        while (remaining.signum() > 0) {
            int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
            if (carryIndex < 0) {
                break;
            }
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            BigDecimal takeQty = remaining.min(carry.quantity());
            CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(
                    carry,
                    takeQty,
                    position.assetKey()
            );
            flowSupport.restoreToPosition(effectiveCarry, position);
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            remaining = remaining.subtract(takeQty, MC);
            restoredAny = true;
        }
        if (queue != null && queue.isEmpty()) {
            replayState.pendingTransfers().remove(transferKey);
        }
        if (remaining.signum() > 0) {
            TransferPendingKey fifoKey = keyFactory.earnCarryFifoKey(transaction, flow);
            Deque<CarryTransfer> fifoQueue = fifoKey != null
                    ? replayState.pendingTransfers().find(fifoKey) : null;
            while (fifoQueue != null && remaining.signum() > 0) {
                int carryIndex = matcher.findUniqueBridgeQueueIndex(fifoQueue, false);
                if (carryIndex < 0) {
                    break;
                }
                CarryTransfer carry = matcher.removeQueueElement(fifoQueue, carryIndex);
                BigDecimal takeQty = remaining.min(carry.quantity());
                CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(
                        carry, takeQty, position.assetKey());
                flowSupport.restoreToPosition(effectiveCarry, position);
                continuityCarryService.reservePassThroughCarryIfPlanned(
                        transaction,
                        flowIndex,
                        effectiveCarry,
                        replayState.passThroughCorridorPlan(),
                        replayState.reservedPassThroughCarries()
                );
                remaining = remaining.subtract(takeQty, MC);
                restoredAny = true;
            }
            if (fifoKey != null && fifoQueue != null && fifoQueue.isEmpty()) {
                replayState.pendingTransfers().remove(fifoKey);
            }
        }
        if (remaining.signum() > 0) {
            NormalizedTransaction.Flow remainderFlow = flow;
            if (restoredAny) {
                remainderFlow = flowWithQuantity(flow, remaining);
            }
            BigDecimal provisionalBasis = flowSupport.materializePendingInbound(remainderFlow, position);
            replayState.pendingTransfers().queue(transferKey)
                    .addLast(CarryTransfer.pendingInbound(remaining, position.assetKey(), provisionalBasis));
        }
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    public CarryTransfer normalizeBybitEarnProductCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CarryTransfer carry,
            ReplayExecutionState replayState,
            PositionState carrySourcePosition,
            AssetKey assetKey,
            BigDecimal preResolvedAvco
    ) {
        if (!classifier.usesBybitVenueInternalCarryQueue(transaction)
                && !TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction)) {
            return carry;
        }
        BigDecimal requested = flow.getQuantityDelta().abs();
        if (carry != null && carry.quantity() != null && carry.quantity().signum() > 0) {
            BigDecimal carryCost = carry.costBasisUsd() == null ? BigDecimal.ZERO : carry.costBasisUsd();
            if (carry.coveredQuantity() != null
                    && carry.coveredQuantity().signum() > 0
                    && carryCost.signum() > 0) {
                return carry;
            }
            requested = carry.quantity();
        }
        if (requested.signum() <= 0) {
            return carry;
        }
        BigDecimal fallbackAvco = preResolvedAvco;
        if (fallbackAvco == null || fallbackAvco.signum() <= 0) {
            fallbackAvco = resolveEarnPrincipalFallbackAvco(
                    transaction,
                    flow,
                    carrySourcePosition,
                    replayState
            );
        }
        return continuityCarryService.syntheticBybitEarnProductCarry(
                flow,
                requested,
                assetKey,
                fallbackAvco
        );
    }

    public CarryTransfer applyEarnPrincipalLotCarryOverride(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CarryTransfer carry,
            PositionState carrySource,
            ReplayExecutionState replayState
    ) {
        if (!TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction) || flow.getQuantityDelta().signum() >= 0) {
            return carry;
        }
        BigDecimal currentCost = carry == null || carry.costBasisUsd() == null
                ? BigDecimal.ZERO
                : carry.costBasisUsd();
        if (currentCost.compareTo(EARN_PRINCIPAL_DUST_BASIS_THRESHOLD) >= 0) {
            return carry;
        }
        BigDecimal movedQty = flow.getQuantityDelta().abs();
        if (movedQty == null || movedQty.signum() <= 0) {
            return carry;
        }
        BigDecimal marketAvco = replayMarketAuthority.resolveFromCacheOrCatalog(transaction, flow)
                .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                .orElse(null);
        if (marketAvco == null || marketAvco.signum() <= 0) {
            return carry;
        }
        BigDecimal lotBasis = movedQty.multiply(marketAvco, MC);
        if (lotBasis.compareTo(currentCost) <= 0) {
            return carry;
        }
        return continuityCarryService.buildExplicitCarryTransfer(movedQty, lotBasis, carrySource.assetKey());
    }

    public CarryTransfer backfillEarnPrincipalInboundCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            BigDecimal inboundQuantity,
            PositionState position,
            CarryTransfer effectiveCarry,
            ReplayExecutionState replayState
    ) {
        if (!TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction)
                || effectiveCarry == null
                || inboundQuantity == null
                || inboundQuantity.signum() <= 0) {
            return effectiveCarry;
        }
        BigDecimal fallbackAvco = resolveEarnPrincipalFallbackAvco(transaction, flow, position, replayState);
        if (fallbackAvco == null || fallbackAvco.signum() <= 0) {
            return effectiveCarry;
        }
        BigDecimal authoritativeBasis = inboundQuantity.multiply(fallbackAvco, MC);
        BigDecimal cost = effectiveCarry.costBasisUsd() == null ? BigDecimal.ZERO : effectiveCarry.costBasisUsd();
        if (cost.signum() <= 0) {
            return continuityCarryService.syntheticBybitEarnProductCarry(
                    flow,
                    inboundQuantity,
                    position.assetKey(),
                    fallbackAvco
            );
        }
        if (flow != null
                && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())
                && cost.compareTo(authoritativeBasis.multiply(EARN_CORRIDOR_INFLATED_BASIS_MULTIPLIER, MC)) > 0) {
            return continuityCarryService.buildExplicitCarryTransfer(
                    inboundQuantity,
                    authoritativeBasis,
                    position.assetKey()
            );
        }
        return effectiveCarry;
    }

    public static BigDecimal derivePositionAvco(PositionState position) {
        if (position == null) {
            return null;
        }
        BigDecimal avco = position.perWalletAvco();
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        BigDecimal quantity = position.quantity();
        BigDecimal basis = position.totalCostBasisUsd();
        if (quantity == null || basis == null || quantity.signum() <= 0 || basis.signum() <= 0) {
            return avco;
        }
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal covered = quantity.subtract(uncovered, MC);
        if (covered.signum() <= 0) {
            return basis.divide(quantity, MC);
        }
        return basis.divide(covered, MC);
    }

    public static NormalizedTransaction.Flow flowWithQuantity(
            NormalizedTransaction.Flow source,
            BigDecimal quantity
    ) {
        NormalizedTransaction.Flow copy = new NormalizedTransaction.Flow();
        copy.setRole(source.getRole());
        copy.setAssetSymbol(source.getAssetSymbol());
        copy.setAssetContract(source.getAssetContract());
        copy.setQuantityDelta(quantity);
        copy.setCounterpartyAddress(source.getCounterpartyAddress());
        copy.setCounterpartyType(source.getCounterpartyType());
        copy.setAccountRef(source.getAccountRef());
        return copy;
    }

    public BigDecimal earnPrincipalPartialMatchThreshold() {
        return ReplayToleranceSupport.QUANTITY_DUST_THRESHOLD;
    }

    /**
     * Drains {@code sliceQty} from a single planned position and returns the carry it released,
     * applying ADR-019 Rule 1 proportional/explicit basis against THAT position.
     */
    public CarryTransfer drainCarrySlice(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState drainedPosition,
            BigDecimal sliceQty,
            PositionState flowPosition,
            boolean corridorTransfer,
            boolean venueInternal,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow sliceFlow = sliceQty != null
                && sliceQty.compareTo(flow.getQuantityDelta().abs()) == 0
                ? flow
                : flowSupport.copyFlowWithQuantity(flow, sliceQty);
        BigDecimal movedQty = sliceFlow.getQuantityDelta().abs();
        PositionSnapshot before = flowSupport.snapshot(drainedPosition);

        BigDecimal corridorOutboundSliceAvco = corridorTransfer
                ? derivePositionAvco(drainedPosition)
                : null;
        BigDecimal preDrainTotalBasis = drainedPosition.totalCostBasisUsd();
        BigDecimal preDrainNetTotalBasis = drainedPosition.netTotalCostBasisUsd();
        BigDecimal preDrainTotalQty = drainedPosition.quantity();
        BigDecimal preDrainAvco = derivePositionAvco(drainedPosition);
        CarryTransfer carry = continuityCarryService.removeTransferCarry(
                transaction,
                sliceFlow,
                flowIndex,
                drainedPosition,
                replayState.passThroughCorridorPlan(),
                replayState.reservedPassThroughCarries(),
                venueInternal || TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction)
        );
        carry = normalizeBybitEarnProductCarry(
                transaction,
                sliceFlow,
                carry,
                replayState,
                drainedPosition,
                drainedPosition.assetKey(),
                preDrainAvco
        );
        carry = applyEarnPrincipalLotCarryOverride(
                transaction,
                sliceFlow,
                carry,
                drainedPosition,
                replayState
        );
        if (corridorOutboundSliceAvco != null && corridorOutboundSliceAvco.signum() > 0) {
            BigDecimal corridorCarryBasis;
            BigDecimal corridorNetCarryBasis;
            if (preDrainTotalQty != null && preDrainTotalQty.signum() > 0
                    && preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
                corridorCarryBasis = preDrainTotalBasis
                        .multiply(movedQty, MC)
                        .divide(preDrainTotalQty, MC);
                BigDecimal effectiveNetBasis = preDrainNetTotalBasis != null
                        ? preDrainNetTotalBasis : preDrainTotalBasis;
                corridorNetCarryBasis = effectiveNetBasis
                        .multiply(movedQty, MC)
                        .divide(preDrainTotalQty, MC);
            } else {
                corridorCarryBasis = movedQty.multiply(corridorOutboundSliceAvco, MC);
                corridorNetCarryBasis = corridorCarryBasis;
            }
            if (preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
                corridorCarryBasis = corridorCarryBasis.min(preDrainTotalBasis);
                corridorNetCarryBasis = corridorNetCarryBasis.min(corridorCarryBasis);
            }
            carry = continuityCarryService.buildExplicitCarryTransfer(
                    movedQty, corridorCarryBasis, corridorNetCarryBasis, drainedPosition.assetKey()
            );
        }
        if (drainedPosition != flowPosition
                && !drainedPosition.assetKey().equals(flowPosition.assetKey())) {
            replayState.ledgerPointCollector().record(
                    transaction,
                    sliceFlow,
                    flowIndex,
                    drainedPosition.assetKey(),
                    before,
                    drainedPosition,
                    flowSupport.continuityBasisEffect(transaction, sliceFlow)
            );
        }
        return carry;
    }

    private BigDecimal resolveEarnPrincipalFallbackAvco(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState primaryPosition,
            ReplayExecutionState replayState
    ) {
        BigDecimal avco = derivePositionAvco(primaryPosition);
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        if (TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction) && replayState != null && transaction != null) {
            AssetKey flowKey = primaryPosition == null ? null : primaryPosition.assetKey();
            if (flowKey != null) {
                String wallet = transaction.getWalletAddress();
                if (wallet != null) {
                    String uid = extractBybitUid(wallet);
                    if (uid != null) {
                        AssetKey umbrellaKey = new AssetKey(
                                CorrelationContract.VENUE_BYBIT + ":" + uid,
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(umbrellaKey));
                        if (avco != null) {
                            return avco;
                        }
                        AssetKey earnKey = new AssetKey(
                                CorrelationContract.VENUE_BYBIT + ":" + uid + CorrelationContract.WALLET_SUFFIX_EARN,
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(earnKey));
                        if (avco != null) {
                            return avco;
                        }
                        AssetKey fundKey = new AssetKey(
                                CorrelationContract.VENUE_BYBIT + ":" + uid + CorrelationContract.WALLET_SUFFIX_FUND,
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(fundKey));
                        if (avco != null) {
                            return avco;
                        }
                    }
                }
            }
        }
        if (flow != null
                && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())
                && replayState != null) {
            BigDecimal ethFamilyAvco = resolveBybitOutboundEthFamilyAvco(transaction, replayState);
            if (ethFamilyAvco != null && ethFamilyAvco.signum() > 0) {
                return ethFamilyAvco;
            }
        }
        return replayMarketAuthority.resolve(transaction, flow)
                .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                .orElse(null);
    }

    private BigDecimal resolveBybitOutboundEthFamilyAvco(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || replayState == null) {
            return null;
        }
        String uid = extractBybitUid(transaction.getWalletAddress());
        if (uid == null) {
            return null;
        }
        String[] walletSuffixes = {"", CorrelationContract.WALLET_SUFFIX_UTA, CorrelationContract.WALLET_SUFFIX_FUND};
        String[] symbols = {"ETH", "WETH"};
        for (String suffix : walletSuffixes) {
            String wallet = CorrelationContract.VENUE_BYBIT + ":" + uid + suffix;
            for (String symbol : symbols) {
                AssetKey symbolKey = new AssetKey(wallet, null, null, symbol, "SYMBOL:" + symbol);
                BigDecimal avco = firstPositiveAvco(replayState.position(symbolKey));
                if (avco != null) {
                    return avco;
                }
            }
        }
        return null;
    }

    private static BigDecimal firstPositiveAvco(PositionState position) {
        BigDecimal avco = derivePositionAvco(position);
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        return null;
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null) {
            return null;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() != WalletDomainKind.CEX || ref.uid().isBlank()) {
            return null;
        }
        return ref.uid();
    }
}
