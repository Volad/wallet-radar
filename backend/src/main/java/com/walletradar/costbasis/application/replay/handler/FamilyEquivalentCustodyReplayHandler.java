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
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class FamilyEquivalentCustodyReplayHandler {

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory pendingTransferKeyFactory;

    public FamilyEquivalentCustodyReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory pendingTransferKeyFactory
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.pendingTransferKeyFactory = pendingTransferKeyFactory;
    }

    public SimpleFamilyCustodySelection selectFlows(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()
                || !isSimpleFamilyEquivalentCustodyType(transaction.getType())
                || pendingTransferKeyFactory.usesCompositeContinuityBucket(transaction)) {
            return SimpleFamilyCustodySelection.empty();
        }

        Map<String, List<IndexedFlow>> flowsByFamily = new LinkedHashMap<>();
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (!isPrincipalCandidate(flow)) {
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
        for (Map.Entry<String, List<IndexedFlow>> entry : flowsByFamily.entrySet()) {
            String familyIdentity = entry.getKey();
            List<IndexedFlow> familyFlows = entry.getValue();
            List<IndexedFlow> outboundFlows = familyFlows.stream()
                    .filter(flow -> flow.flow().getQuantityDelta().signum() < 0)
                    .toList();
            List<IndexedFlow> inboundFlows = familyFlows.stream()
                    .filter(flow -> flow.flow().getQuantityDelta().signum() > 0)
                    .toList();
            if (outboundFlows.isEmpty() || inboundFlows.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "FAMILY_CUSTODY_PAIR_SKIPPED txId={} type={} family={} outboundCount={} inboundCount={}",
                            transaction.getId(),
                            transaction.getType(),
                            familyIdentity,
                            outboundFlows.size(),
                            inboundFlows.size()
                    );
                }
                continue;
            }

            IndexedFlow outbound;
            if (outboundFlows.size() == 1) {
                outbound = outboundFlows.getFirst();
            } else {
                // Cycle/9 S6: multi-outbound shapes (e.g., Aave V3 withdraw via WETHGateway
                // where WETH is briefly received then unwrapped; or supply where a tiny refund
                // emits a same-asset inbound). Select the outbound whose net delta over the
                // tx is strictly negative — the asset that actually left the wallet. If no
                // single asset dominates, fall back to the largest-absolute-quantity outbound
                // so basis carry still proceeds rather than zeroing.
                outbound = selectPrincipalOutbound(transaction, outboundFlows, inboundFlows);
                if (outbound == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "FAMILY_CUSTODY_NO_PRINCIPAL_OUTBOUND txId={} type={} family={} outboundCount={} inboundCount={}",
                                transaction.getId(),
                                transaction.getType(),
                                familyIdentity,
                                outboundFlows.size(),
                                inboundFlows.size()
                        );
                    }
                    continue;
                }
            }

            IndexedFlow inbound = selectPrincipalInbound(transaction, outbound, inboundFlows);
            if (inbound == null) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "FAMILY_CUSTODY_NO_DISTINCT_INBOUND txId={} type={} family={} outboundAsset={}",
                            transaction.getId(),
                            transaction.getType(),
                            familyIdentity,
                            outbound.flow().getAssetSymbol()
                    );
                }
                continue;
            }
            if (selectedByIndex.containsKey(outbound.index()) || selectedByIndex.containsKey(inbound.index())) {
                continue;
            }
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

    /**
     * Cycle/9 S6: when multiple outbound legs share the same family (e.g. an Aave V3 withdraw
     * where the underlying briefly returns to the wallet then is forwarded to a gateway), pick
     * the asset whose net signed quantity over the transaction is the most negative — i.e. the
     * one that actually left the wallet. Among legs of that winning asset, pick the largest
     * absolute leg to act as the IndexedFlow handle for downstream carry. Returns {@code null}
     * if no single asset has net &lt; 0 (e.g., all outbound flows are perfectly cancelled by
     * equal inbound flows of the same asset), in which case the handler defers to generic
     * replay.
     */
    private IndexedFlow selectPrincipalOutbound(
            NormalizedTransaction transaction,
            List<IndexedFlow> outboundFlows,
            List<IndexedFlow> inboundFlows
    ) {
        Map<String, BigDecimal> netByAsset = new LinkedHashMap<>();
        for (IndexedFlow flow : outboundFlows) {
            String identity = assetSupport.assetIdentity(transaction, flow.flow());
            if (identity == null) {
                continue;
            }
            netByAsset.merge(identity, flow.flow().getQuantityDelta(), BigDecimal::add);
        }
        for (IndexedFlow flow : inboundFlows) {
            String identity = assetSupport.assetIdentity(transaction, flow.flow());
            if (identity == null) {
                continue;
            }
            netByAsset.merge(identity, flow.flow().getQuantityDelta(), BigDecimal::add);
        }

        String dominantOutboundAsset = netByAsset.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() < 0)
                .min(Comparator.comparing(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (dominantOutboundAsset == null) {
            return null;
        }
        return outboundFlows.stream()
                .filter(flow -> Objects.equals(
                        assetSupport.assetIdentity(transaction, flow.flow()),
                        dominantOutboundAsset
                ))
                .max(Comparator.comparing(flow -> flow.flow().getQuantityDelta().abs()))
                .orElse(null);
    }

    private IndexedFlow selectPrincipalInbound(
            NormalizedTransaction transaction,
            IndexedFlow outbound,
            List<IndexedFlow> inboundFlows
    ) {
        List<IndexedFlow> distinctAssetInbound = inboundFlows.stream()
                .filter(inbound -> !Objects.equals(
                        assetSupport.assetIdentity(transaction, outbound.flow()),
                        assetSupport.assetIdentity(transaction, inbound.flow())
                ))
                .toList();
        if (distinctAssetInbound.isEmpty()) {
            return null;
        }
        // Cycle/9 S6: pick the inbound with the largest absolute quantity rather than the
        // first TRANSFER-role match. When there are multiple distinct-asset TRANSFER legs
        // (e.g. an Aave V3 withdraw that emits a tiny gateway dust refund alongside the
        // principal repayment), the dust used to win by encounter order and the principal
        // basis carry was lost.
        return distinctAssetInbound.stream()
                .max(Comparator.comparing(
                        (IndexedFlow flow) -> flow.flow().getQuantityDelta().abs())
                        .thenComparing(flow -> flow.flow().getRole() == NormalizedLegRole.TRANSFER ? 1 : 0))
                .orElse(null);
    }

    private boolean isPrincipalCandidate(NormalizedTransaction.Flow flow) {
        if (flow == null
                || flow.getRole() == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        if (flow.getRole() == NormalizedLegRole.TRANSFER) {
            return true;
        }
        return (flow.getRole() == NormalizedLegRole.SELL && flow.getQuantityDelta().signum() < 0)
                || (flow.getRole() == NormalizedLegRole.BUY && flow.getQuantityDelta().signum() > 0);
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
