import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { LendingData } from '../models/lending.models';
import {
  SessionLendingAssetDeltasResponse,
  SessionLendingHistoryEntryResponse,
  SessionLendingPnlAssetBreakdownResponse,
  SessionLendingPositionResponse,
  SessionLendingResponse,
} from '../models/wallet-api.models';
import { WalletApiService } from './wallet-api.service';

@Injectable({ providedIn: 'root' })
export class LendingDataService {
  constructor(private readonly walletApiService: WalletApiService) {}

  getSessionLending(sessionId: string): Observable<LendingData> {
    return this.walletApiService.getSessionLending(sessionId).pipe(
      map((response) => this.toLendingData(response))
    );
  }

  mapSessionResponse(response: SessionLendingResponse): LendingData {
    return this.toLendingData(response);
  }

  private toLendingData(response: SessionLendingResponse): LendingData {
    return {
      sessionId: response.sessionId,
      summary: {
        totalSuppliedUsd: response.summary.totalSuppliedUsd ?? 0,
        totalBorrowedUsd: response.summary.totalBorrowedUsd ?? 0,
        netExposureUsd: response.summary.netExposureUsd ?? 0,
        openGroups: response.summary.openGroups ?? 0,
        closedGroups: response.summary.closedGroups ?? 0,
        protocols: response.summary.protocols ?? 0,
      },
      groups: response.groups.map((group) => ({
        id: group.id,
        protocol: group.protocol,
        networkId: group.networkId,
        walletAddress: group.walletAddress.toLowerCase(),
        status: group.status,
        healthFactor: group.healthFactor ?? 0,
        healthLabel: group.healthLabel ?? 'Unavailable',
        healthProgress: group.healthProgress ?? 0,
        healthStatus: group.healthStatus ?? 'UNKNOWN',
        healthSource: group.healthSource ?? 'UNKNOWN',
        healthStale: group.healthStale ?? false,
        lastRefreshedAt: group.lastRefreshedAt ?? null,
        supplyUsd: group.supplyUsd ?? 0,
        borrowUsd: group.borrowUsd ?? 0,
        netExposureUsd: group.netExposureUsd ?? 0,
        positions: group.positions.map((position) => this.toPosition(position)),
        cycles: (group.cycles ?? []).map((cycle) => ({
          id: cycle.id,
          marketKey: cycle.marketKey,
          marketLabel: cycle.marketLabel,
          status: cycle.status,
          startTimestamp: cycle.startTimestamp,
          closeTimestamp: cycle.closeTimestamp,
          startTxHash: cycle.startTxHash,
          closeTxHash: cycle.closeTxHash,
          statusDetail: cycle.statusDetail,
          warningReason: cycle.warningReason,
          assetDenominatedPnlByAsset: this.toNumberRecord(cycle.assetDenominatedPnlByAsset ?? {}),
          assetDenominatedPrecisionByAsset: this.toStringRecord(cycle.assetDenominatedPrecisionByAsset ?? {}),
          assetDenominatedReasonByAsset: this.toStringRecord(cycle.assetDenominatedReasonByAsset ?? {}),
          primaryAssetPnlSummary: cycle.primaryAssetPnlSummary,
          largePnlReason: cycle.largePnlReason,
          largePnlReasons: cycle.largePnlReasons ?? [],
          primaryLargePnlReason: cycle.primaryLargePnlReason,
          assetDeltas: this.toAssetDeltas(cycle.assetDeltas),
          realizedPnl: {
            valueUsd: cycle.realizedPnl.valueUsd,
            precision: cycle.realizedPnl.precision ?? 'UNAVAILABLE',
            method: cycle.realizedPnl.method ?? 'unknown',
          },
          unrealizedPnl: {
            valueUsd: cycle.unrealizedPnl.valueUsd,
            precision: cycle.unrealizedPnl.precision ?? 'UNAVAILABLE',
            method: cycle.unrealizedPnl.method ?? 'unknown',
          },
          pnlBreakdown: {
            interestEarnedUsd: cycle.pnlBreakdown?.interestEarnedUsd ?? null,
            interestPaidUsd: cycle.pnlBreakdown?.interestPaidUsd ?? null,
            gasUsd: cycle.pnlBreakdown?.gasUsd ?? 0,
            netPnlUsd: cycle.pnlBreakdown?.netPnlUsd ?? null,
            precision: cycle.pnlBreakdown?.precision ?? 'UNAVAILABLE',
            method: cycle.pnlBreakdown?.method ?? 'unknown',
            reason: cycle.pnlBreakdown?.reason ?? null,
          },
          pnlAssetBreakdown: this.toPnlAssetBreakdown(cycle.pnlAssetBreakdown),
          factualApy: {
            factualSupplyAprByAsset: this.toNumberRecord(cycle.factualApy?.factualSupplyAprByAsset ?? {}),
            factualSupplyApyByAsset: this.toNumberRecord(cycle.factualApy?.factualSupplyApyByAsset ?? {}),
            factualBorrowAprByAsset: this.toNumberRecord(cycle.factualApy?.factualBorrowAprByAsset ?? {}),
            factualBorrowApyByAsset: this.toNumberRecord(cycle.factualApy?.factualBorrowApyByAsset ?? {}),
            netStrategyAprPct: cycle.factualApy?.netStrategyAprPct ?? null,
            netStrategyApyPct: cycle.factualApy?.netStrategyApyPct ?? null,
            apyPrecision: cycle.factualApy?.apyPrecision ?? 'UNAVAILABLE',
            apyMethod: cycle.factualApy?.apyMethod ?? 'unknown',
            apyUnavailableReason: cycle.factualApy?.apyUnavailableReason ?? null,
            apyConvention: cycle.factualApy?.apyConvention ?? null,
          },
          totalValuation: {
            principalInUsd: cycle.totalValuation?.principalInUsd ?? 0,
            principalOutUsd: cycle.totalValuation?.principalOutUsd ?? 0,
            borrowedUsd: cycle.totalValuation?.borrowedUsd ?? 0,
            repaidUsd: cycle.totalValuation?.repaidUsd ?? 0,
            rewardsUsd: cycle.totalValuation?.rewardsUsd ?? 0,
            feesUsd: cycle.totalValuation?.feesUsd ?? 0,
            gasUsd: cycle.totalValuation?.gasUsd ?? 0,
            totalUsdPnl: cycle.totalValuation?.totalUsdPnl ?? null,
            currentUsdValue: cycle.totalValuation?.currentUsdValue ?? null,
            unrealizedTotalUsdPnl: cycle.totalValuation?.unrealizedTotalUsdPnl ?? null,
            totalUsdPnlPrecision: cycle.totalValuation?.totalUsdPnlPrecision ?? 'UNAVAILABLE',
            yieldOnlyPnl: cycle.totalValuation?.yieldOnlyPnl ?? null,
            yieldOnlyPnlPrecision: cycle.totalValuation?.yieldOnlyPnlPrecision ?? 'UNAVAILABLE',
            valuationMethod: cycle.totalValuation?.valuationMethod ?? 'unknown',
            unavailableReason: cycle.totalValuation?.unavailableReason ?? null,
          },
          observedFlowsByAsset: this.toObservedFlows(cycle.observedFlowsByAsset ?? {}),
          peakSupplyUsd: cycle.peakSupplyUsd ?? 0,
          peakBorrowUsd: cycle.peakBorrowUsd ?? 0,
          durationDays: cycle.durationDays ?? null,
          positions: cycle.positions.map((position) => this.toPosition(position)),
          events: cycle.events.map((history) => this.toHistory(history)),
          txGroups: (cycle.txGroups ?? []).map((group) => ({
            id: group.id,
            type: group.type,
            timestamp: group.timestamp,
            dateLabel: group.dateLabel,
            loopSteps: group.loopSteps,
            loopAssetIn: group.loopAssetIn,
            loopAssetOut: group.loopAssetOut,
            items: group.items.map((item) => ({
              id: item.id,
              type: item.type,
              label: item.label,
              assetSymbol: item.assetSymbol,
              quantity: item.quantity ?? 0,
              valueUsd: item.valueUsd ?? 0,
              txHash: item.txHash,
              blockTimestamp: item.blockTimestamp,
            })),
          })),
        })),
        history: group.history.map((history) => this.toHistory(history)),
      })),
    };
  }

  private toPosition(position: SessionLendingPositionResponse) {
    return {
      id: position.id,
      marketKey: position.marketKey ?? 'unknown',
      side: position.side,
      assetSymbol: position.assetSymbol,
      underlyingSymbol: position.underlyingSymbol,
      assetContract: position.assetContract,
      quantity: position.quantity ?? 0,
      coveredQuantity: position.coveredQuantity ?? 0,
      valueUsd: position.valueUsd ?? 0,
      earnedUsd: position.earnedUsd ?? 0,
      apyPct: position.apyPct ?? 0,
      metricStatus: position.metricStatus ?? 'UNKNOWN',
      metricSource: position.metricSource ?? 'UNKNOWN',
      protocolSupplyApyPct: position.protocolSupplyApyPct ?? null,
      protocolBorrowApyPct: position.protocolBorrowApyPct ?? null,
      rewardAprPct: position.rewardAprPct ?? null,
      netProtocolApyPct: position.netProtocolApyPct ?? null,
      protocolApyStatus: position.protocolApyStatus ?? position.metricStatus ?? 'UNKNOWN',
      protocolApySource: position.protocolApySource ?? position.metricSource ?? 'UNKNOWN',
      protocolApyCapturedAt: position.protocolApyCapturedAt ?? null,
      protocolApyStale: position.protocolApyStale ?? false,
      rewardAprStatus: position.rewardAprStatus ?? 'UNAVAILABLE',
      rewardAprUnavailableReason: position.rewardAprUnavailableReason ?? null,
      apyConvention: position.apyConvention ?? null,
    };
  }

  private toHistory(history: SessionLendingHistoryEntryResponse) {
    return {
      id: history.id,
      txHash: history.txHash,
      marketKey: history.marketKey ?? 'unknown',
      cycleId: history.cycleId,
      networkId: history.networkId,
      walletAddress: history.walletAddress?.toLowerCase() ?? null,
      blockTimestamp: history.blockTimestamp,
      type: history.type,
      eventSubtype: history.eventSubtype,
      displayType: history.displayType,
      assetSymbol: history.assetSymbol,
      quantity: history.quantity ?? 0,
      valueUsd: history.valueUsd ?? 0,
      feeUsd: history.feeUsd ?? 0,
      loopId: history.loopId,
    };
  }

  private toAssetDeltas(response: SessionLendingAssetDeltasResponse) {
    return {
      principalInByAsset: this.toNumberRecord(response.principalInByAsset),
      principalOutByAsset: this.toNumberRecord(response.principalOutByAsset),
      principalOutCashByAsset: this.toNumberRecord(response.principalOutCashByAsset ?? response.principalOutByAsset),
      internalReceiptMovementByAsset: this.toNumberRecord(response.internalReceiptMovementByAsset ?? {}),
      borrowedByAsset: this.toNumberRecord(response.borrowedByAsset),
      repaidByAsset: this.toNumberRecord(response.repaidByAsset),
      withdrawnByAsset: this.toNumberRecord(response.withdrawnByAsset),
      rewardByAsset: this.toNumberRecord(response.rewardByAsset),
      feesByAsset: this.toNumberRecord(response.feesByAsset),
      netCashDeltaByAsset: this.toNumberRecord(response.netCashDeltaByAsset),
    };
  }

  private toPnlAssetBreakdown(response: SessionLendingPnlAssetBreakdownResponse | null | undefined) {
    return {
      supplyIncomeByAsset: this.toNumberRecord(response?.supplyIncomeByAsset ?? {}),
      borrowCostByAsset: this.toNumberRecord(response?.borrowCostByAsset ?? {}),
      rewardsByAsset: this.toNumberRecord(response?.rewardsByAsset ?? {}),
      gasByAsset: this.toNumberRecord(response?.gasByAsset ?? {}),
      netIncomeByAsset: this.toNumberRecord(response?.netIncomeByAsset ?? {}),
      precisionByAsset: this.toStringRecord(response?.precisionByAsset ?? {}),
      reasonByAsset: this.toStringRecord(response?.reasonByAsset ?? {}),
      supplyPnlUsdByAsset: this.toNumberRecord(response?.supplyPnlUsdByAsset ?? {}),
      borrowPnlUsdByAsset: this.toNumberRecord(response?.borrowPnlUsdByAsset ?? {}),
      rewardsUsdByAsset: this.toNumberRecord(response?.rewardsUsdByAsset ?? {}),
      gasUsdByAsset: this.toNumberRecord(response?.gasUsdByAsset ?? {}),
      netIncomeUsdByAsset: this.toNumberRecord(response?.netIncomeUsdByAsset ?? {}),
      usdPrecisionByAsset: this.toStringRecord(response?.usdPrecisionByAsset ?? {}),
    };
  }

  private toObservedFlows(
    source: Readonly<Record<string, ReadonlyArray<{
      readonly assetSymbol: string;
      readonly assetContract: string | null;
      readonly quantity: number | null;
      readonly sourceTxHash: string | null;
      readonly sourceKind: string | null;
      readonly isAuthoritativeForPnl: boolean | null;
      readonly unavailableReason: string | null;
    }>>>
  ) {
    return Object.fromEntries(Object.entries(source ?? {}).map(([asset, flows]) => [
      asset,
      (flows ?? []).map((flow) => ({
        assetSymbol: flow.assetSymbol,
        assetContract: flow.assetContract,
        quantity: flow.quantity ?? 0,
        sourceTxHash: flow.sourceTxHash,
        sourceKind: flow.sourceKind ?? 'WALLET_VISIBLE_TRANSFER',
        isAuthoritativeForPnl: flow.isAuthoritativeForPnl ?? false,
        unavailableReason: flow.unavailableReason,
      })),
    ]));
  }

  private toNumberRecord(source: Readonly<Record<string, number | null>>): Readonly<Record<string, number>> {
    return Object.fromEntries(Object.entries(source ?? {}).map(([key, value]) => [key, value ?? 0]));
  }

  private toStringRecord(source: Readonly<Record<string, string | null>>): Readonly<Record<string, string>> {
    return Object.fromEntries(Object.entries(source ?? {}).map(([key, value]) => [key, value ?? '']));
  }
}
