package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.SimpleFamilyCustodyPair;
import com.walletradar.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class FamilyEquivalentCustodyReplayHandler {

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;

    public FamilyEquivalentCustodyReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
    }

    public SimpleFamilyCustodySelection selectFlows(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()
                || !isSimpleFamilyEquivalentCustodyType(transaction.getType())) {
            return SimpleFamilyCustodySelection.empty();
        }

        Map<String, List<IndexedFlow>> flowsByFamily = new LinkedHashMap<>();
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
            if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
                continue;
            }
            flowsByFamily.computeIfAbsent(continuityIdentity, ignored -> new ArrayList<>()).add(indexedFlow);
        }

        List<SimpleFamilyCustodyPair> pairs = new ArrayList<>();
        Map<Integer, IndexedFlow> selectedByIndex = new LinkedHashMap<>();
        for (List<IndexedFlow> familyFlows : flowsByFamily.values()) {
            if (familyFlows.size() != 2) {
                continue;
            }
            IndexedFlow first = familyFlows.get(0);
            IndexedFlow second = familyFlows.get(1);
            if (first.flow().getQuantityDelta().signum() == second.flow().getQuantityDelta().signum()) {
                continue;
            }
            if (Objects.equals(assetSupport.assetIdentity(transaction, first.flow()), assetSupport.assetIdentity(transaction, second.flow()))) {
                continue;
            }
            IndexedFlow outbound = first.flow().getQuantityDelta().signum() < 0 ? first : second;
            IndexedFlow inbound = outbound == first ? second : first;
            pairs.add(new SimpleFamilyCustodyPair(outbound, inbound));
            selectedByIndex.put(outbound.index(), outbound);
            selectedByIndex.put(inbound.index(), inbound);
        }
        if (pairs.isEmpty()) {
            return SimpleFamilyCustodySelection.empty();
        }
        pairs.sort(java.util.Comparator.comparingInt(pair -> Math.min(pair.outbound().index(), pair.inbound().index())));
        return new SimpleFamilyCustodySelection(pairs, selectedByIndex);
    }

    public void applySelected(
            NormalizedTransaction transaction,
            SimpleFamilyCustodySelection selection,
            ReplayExecutionState replayState
    ) {
        for (SimpleFamilyCustodyPair pair : selection.pairs()) {
            IndexedFlow outbound = pair.outbound();
            IndexedFlow inbound = pair.inbound();

            AssetKey outboundAssetKey = assetSupport.assetKey(transaction, outbound.flow());
            PositionState outboundPosition = replayState.position(outboundAssetKey);
            outboundPosition.setLastEventTimestamp(flowSupport.laterOf(outboundPosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot outboundBefore = flowSupport.snapshot(outboundPosition);
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    outbound.flow(),
                    outbound.index(),
                    outboundPosition,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            replayState.ledgerPointCollector().record(
                    transaction,
                    outbound.flow(),
                    outbound.index(),
                    outboundPosition.assetKey(),
                    outboundBefore,
                    outboundPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );

            AssetKey inboundAssetKey = assetSupport.assetKey(transaction, inbound.flow());
            PositionState inboundPosition = replayState.position(inboundAssetKey);
            inboundPosition.setLastEventTimestamp(flowSupport.laterOf(inboundPosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot inboundBefore = flowSupport.snapshot(inboundPosition);
            CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(
                    carry,
                    inbound.flow().getQuantityDelta().abs(),
                    inboundPosition.assetKey()
            );
            flowSupport.restoreToPosition(
                    inbound.flow().getQuantityDelta().abs(),
                    inboundPosition,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            replayState.ledgerPointCollector().record(
                    transaction,
                    inbound.flow(),
                    inbound.index(),
                    inboundPosition.assetKey(),
                    inboundBefore,
                    inboundPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
        }
    }

    private boolean isSimpleFamilyEquivalentCustodyType(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    WRAP,
                    UNWRAP,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW -> true;
            default -> false;
        };
    }
}
