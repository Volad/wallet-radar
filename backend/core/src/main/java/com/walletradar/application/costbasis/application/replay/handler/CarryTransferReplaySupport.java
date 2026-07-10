package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.AccountRefPositionResolver;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayToleranceSupport;
import com.walletradar.application.costbasis.application.replay.support.TransferEarnPrincipalReplaySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.Objects;

import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EARN_FLEXIBLE_SAVING;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LENDING_WITHDRAW;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LP_EXIT;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LP_EXIT_FINAL;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LP_EXIT_PARTIAL;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.STAKING_WITHDRAW;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.VAULT_WITHDRAW;

@Component
@RequiredArgsConstructor
@Slf4j
public class CarryTransferReplaySupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;

    public void restoreFromContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, flow.getQuantityDelta().abs(), position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                carry.costBasisUsd(),
                carry.netCostBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    public void restoreFromBucketLendingDepositUsdWeighted(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            restoreFromContinuityBucket(flow, position, bucket);
            return;
        }

        BigDecimal totalUsdWeight = BigDecimal.ZERO;
        BigDecimal currentFlowUsdWeight = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow f : transaction.getFlows()) {
            if (f == null
                    || f.getQuantityDelta() == null
                    || f.getQuantityDelta().signum() <= 0) {
                continue;
            }
            BigDecimal weight = BigDecimal.ZERO;
            if (f.getUnitPriceUsd() != null && f.getUnitPriceUsd().signum() > 0) {
                weight = f.getQuantityDelta().multiply(f.getUnitPriceUsd(), MC);
            }
            totalUsdWeight = totalUsdWeight.add(weight, MC);
            if (f == flow) {
                currentFlowUsdWeight = weight;
            }
        }

        if (totalUsdWeight.signum() == 0) {
            log.warn(
                    "LENDING_DEPOSIT_USD_WEIGHT_ZERO txId={} all inbound legs unpriced; basis left in source bucket",
                    transaction.getId()
            );
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }

        if (currentFlowUsdWeight.signum() == 0) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }

        BigDecimal weightFraction = currentFlowUsdWeight.divide(totalUsdWeight, MC);
        BigDecimal virtualQty = bucket.quantity().multiply(weightFraction, MC);
        CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, virtualQty, position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                carry.costBasisUsd(),
                carry.netCostBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    public void restoreFullBucket(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = continuityCarryService.drainFullBucket(bucket, position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        BigDecimal costBasisToRestore = carry.costBasisUsd();
        boolean isWithdraw = transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW;
        if (isWithdraw && carry.costBasisUsd() != null && carry.costBasisUsd().signum() > 0) {
            BigDecimal thisFlowUsd = computeFlowUsdValue(flow);
            BigDecimal totalReturnedUsd = computeTotalSameAssetInboundUsd(transaction, flow);
            if (thisFlowUsd != null && totalReturnedUsd != null && totalReturnedUsd.signum() > 0) {
                BigDecimal ratio = totalReturnedUsd
                        .divide(carry.costBasisUsd(), MathContext.DECIMAL64)
                        .min(BigDecimal.ONE);
                BigDecimal flowFraction = thisFlowUsd.divide(totalReturnedUsd, MathContext.DECIMAL64);
                costBasisToRestore = carry.costBasisUsd()
                        .multiply(ratio, MathContext.DECIMAL64)
                        .multiply(flowFraction, MathContext.DECIMAL64);
            } else if (transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                    && carry.quantity() != null && carry.quantity().signum() > 0) {
                costBasisToRestore = vaultWithdrawQuantityRatioBasis(carry, flow);
            }
        } else if (transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                && carry.quantity() != null && carry.quantity().signum() > 0) {
            costBasisToRestore = vaultWithdrawQuantityRatioBasis(carry, flow);
        }

        BigDecimal netCostBasisToRestore;
        if (carry.costBasisUsd() != null && carry.costBasisUsd().signum() > 0
                && costBasisToRestore.compareTo(carry.costBasisUsd()) != 0) {
            BigDecimal netSrc = carry.netCostBasisUsd() != null ? carry.netCostBasisUsd() : carry.costBasisUsd();
            BigDecimal ratio = costBasisToRestore.divide(carry.costBasisUsd(), MC);
            netCostBasisToRestore = netSrc.multiply(ratio, MC);
        } else {
            netCostBasisToRestore = carry.netCostBasisUsd() != null ? carry.netCostBasisUsd() : costBasisToRestore;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                costBasisToRestore,
                netCostBasisToRestore,
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    public PositionState resolveCarrySourcePosition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState flowPosition,
            ReplayExecutionState replayState,
            boolean isCorridorTransfer,
            BigDecimal outboundQuantity
    ) {
        if (transaction == null || flowPosition == null || replayState == null) {
            return flowPosition;
        }
        AssetKey accountRefKey = AccountRefPositionResolver.resolveInventoryBearingAccountRefKey(
                flowPosition.assetKey(),
                flow == null ? null : flow.getAccountRef(),
                replayState.positions().asMap(),
                outboundQuantity
        );
        if (!accountRefKey.equals(flowPosition.assetKey())) {
            return replayState.position(accountRefKey);
        }
        String wallet = transaction.getWalletAddress();
        if (wallet == null) {
            return flowPosition;
        }
        String walletUpper = wallet.toUpperCase(Locale.ROOT);
        WalletRef walletRef = WalletRef.parse(wallet);
        String walletSubAccount = walletRef.subAccount() != null ? walletRef.subAccount().toUpperCase(Locale.ROOT) : null;
        String correlationId = transaction.getCorrelationId();
        if (correlationId != null
                && correlationId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)
                && "EARN".equals(walletSubAccount)) {
            if (TransferEarnPrincipalReplaySupport.hasEarnPrincipalCarryBasis(flowPosition)) {
                return flowPosition;
            }
            return replayState.position(TransferEarnPrincipalReplaySupport.umbrellaKeyFor(flowPosition.assetKey(), walletRef.umbrellaKey()));
        }

        if (isCorridorTransfer
                && "FUND".equals(walletSubAccount)
                && !hasFundCarryInventory(flowPosition)) {
            return replayState.position(TransferEarnPrincipalReplaySupport.umbrellaKeyFor(flowPosition.assetKey(), walletRef.umbrellaKey()));
        }

        if (!isCorridorTransfer
                && "FUND".equals(walletSubAccount)
                && !positionCoversQuantity(flowPosition, outboundQuantity)) {
            AssetKey fundKey = TransferEarnPrincipalReplaySupport.umbrellaKeyFor(flowPosition.assetKey(), wallet.trim());
            if (!fundKey.equals(flowPosition.assetKey())) {
                PositionState fundPosition = replayState.position(fundKey);
                if (positionCoversQuantity(fundPosition, outboundQuantity)) {
                    return fundPosition;
                }
                if (outboundQuantity == null && hasFundCarryInventory(fundPosition)) {
                    return fundPosition;
                }
            }
        }
        return flowPosition;
    }

    public static BigDecimal inboundCoveredQuantity(BigDecimal inboundQuantity, CarryTransfer carry) {
        BigDecimal uncovered = carry == null || carry.uncoveredQuantity() == null
                ? BigDecimal.ZERO
                : carry.uncoveredQuantity();
        BigDecimal covered = inboundQuantity.subtract(uncovered, MC);
        return covered.signum() < 0 ? BigDecimal.ZERO : covered;
    }

    private BigDecimal vaultWithdrawQuantityRatioBasis(CarryTransfer carry, NormalizedTransaction.Flow flow) {
        BigDecimal qtyReturned = flow.getQuantityDelta().abs();
        BigDecimal qtyDeposited = carry.quantity();
        BigDecimal diff = qtyReturned.subtract(qtyDeposited, MC).abs();
        BigDecimal tolerance = qtyDeposited.multiply(ReplayToleranceSupport.VAULT_WITHDRAW_ROUND_TRIP_TOLERANCE, MC);
        if (diff.compareTo(tolerance) >= 0) {
            BigDecimal ratio = qtyReturned.divide(qtyDeposited, MathContext.DECIMAL64)
                    .min(BigDecimal.ONE);
            return carry.costBasisUsd().multiply(ratio, MathContext.DECIMAL64);
        }
        return carry.costBasisUsd();
    }

    private static BigDecimal computeFlowUsdValue(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        if (flow.getValueUsd() != null && flow.getValueUsd().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
        }
        return null;
    }

    private static String flowAssetMatchKey(NormalizedTransaction.Flow f) {
        if (f == null) {
            return null;
        }
        String contract = f.getAssetContract();
        return (contract != null && !contract.isBlank()) ? contract : f.getAssetSymbol();
    }

    private static BigDecimal computeTotalSameAssetInboundUsd(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow targetFlow
    ) {
        if (transaction.getFlows() == null) {
            return computeFlowUsdValue(targetFlow);
        }
        String targetKey = flowAssetMatchKey(targetFlow);
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow f : transaction.getFlows()) {
            if (f == null || f.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (f.getQuantityDelta() == null || f.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!Objects.equals(targetKey, flowAssetMatchKey(f))) {
                continue;
            }
            BigDecimal usdVal = computeFlowUsdValue(f);
            if (usdVal != null && usdVal.signum() > 0) {
                total = total.add(usdVal, MC);
            }
        }
        return total.signum() > 0 ? total : null;
    }

    private static boolean positionCoversQuantity(PositionState position, BigDecimal outboundQuantity) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        if (qty == null || qty.signum() <= 0) {
            return false;
        }
        if (outboundQuantity == null || outboundQuantity.signum() <= 0) {
            return true;
        }
        BigDecimal required = outboundQuantity.multiply(ReplayToleranceSupport.carrySourceCoverageRatio(), MC);
        return qty.compareTo(required) >= 0;
    }

    private static boolean hasFundCarryInventory(PositionState position) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        return qty != null && qty.signum() > 0;
    }

    public static boolean preserveBucketOutboundCoverage(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        return switch (transaction.getType()) {
            case VAULT_WITHDRAW,
                    LENDING_WITHDRAW,
                    EARN_FLEXIBLE_SAVING,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    STAKING_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
    }
}
