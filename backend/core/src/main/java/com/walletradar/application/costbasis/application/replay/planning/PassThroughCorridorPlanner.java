package com.walletradar.application.costbasis.application.replay.planning;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.FlowRef;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCandidate;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridor;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PassThroughScopeKey;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Component
public class PassThroughCorridorPlanner {

    public PassThroughCorridorPlan buildPlan(
            List<NormalizedTransaction> ordered,
            BiFunction<NormalizedTransaction, NormalizedTransaction.Flow, AssetKey> assetKeyFactory
    ) {
        Map<FlowRef, PassThroughCorridor> byInboundFlowRef = new LinkedHashMap<>();
        Map<FlowRef, PassThroughCorridor> byOutboundFlowRef = new LinkedHashMap<>();
        Map<PassThroughScopeKey, List<PassThroughCandidate>> pendingInboundByScope = new LinkedHashMap<>();

        for (NormalizedTransaction transaction : ordered) {
            if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
                continue;
            }
            for (IndexedFlow indexedFlow : indexedFlows(transaction)) {
                NormalizedTransaction.Flow flow = indexedFlow.flow();
                if (flow == null
                        || isNotTransferLike(flow)
                        || flow.getQuantityDelta() == null
                        || flow.getQuantityDelta().signum() == 0) {
                    continue;
                }

                AssetKey assetKey = assetKeyFactory.apply(transaction, flow);
                if (assetKey == null || assetKey.assetIdentity() == null) {
                    continue;
                }

                FlowRef flowRef = FlowRef.of(transaction.getId() + ":" + indexedFlow.index());
                BigDecimal quantity = flow.getQuantityDelta().abs();

                if (flow.getQuantityDelta().signum() > 0) {
                    if (transaction.getMatchedCounterparty() == null || transaction.getMatchedCounterparty().isBlank()) {
                        continue;
                    }
                    PassThroughScopeKey scopeKey = new PassThroughScopeKey(
                            transaction.getWalletAddress() + "->" + transaction.getMatchedCounterparty(),
                            assetKey.assetIdentity()
                    );
                    pendingInboundByScope.computeIfAbsent(scopeKey, ignored -> new ArrayList<>())
                            .add(new PassThroughCandidate(flowRef, scopeKey, quantity, networkId(transaction)));
                    continue;
                }

                PassThroughCandidate inbound = null;
                if (transaction.getMatchedCounterparty() != null && !transaction.getMatchedCounterparty().isBlank()) {
                    PassThroughScopeKey scopeKey = new PassThroughScopeKey(
                            transaction.getWalletAddress() + "->" + transaction.getMatchedCounterparty(),
                            assetKey.assetIdentity()
                    );
                    inbound = selectPassThroughInboundCandidate(
                            pendingInboundByScope.get(scopeKey),
                            quantity
                    );
                }
                if (inbound == null && isWalletScopedOutboundCandidate(transaction)) {
                    inbound = selectWalletScopedInboundCandidate(
                            pendingInboundByScope,
                            transaction.getWalletAddress(),
                            networkId(transaction),
                            assetKey.assetIdentity(),
                            quantity
                    );
                }
                if (inbound == null) {
                    continue;
                }
                PassThroughCorridor corridor = new PassThroughCorridor(
                        inbound.flowRef(),
                        flowRef,
                        inbound.quantity().min(quantity)
                );
                byInboundFlowRef.put(inbound.flowRef(), corridor);
                byOutboundFlowRef.put(flowRef, corridor);
            }
        }

        return new PassThroughCorridorPlan(byInboundFlowRef, byOutboundFlowRef);
    }

    private List<IndexedFlow> indexedFlows(NormalizedTransaction transaction) {
        List<IndexedFlow> indexed = new ArrayList<>();
        for (int index = 0; index < transaction.getFlows().size(); index++) {
            indexed.add(new IndexedFlow(index, transaction.getFlows().get(index)));
        }
        return indexed;
    }

    private PassThroughCandidate selectPassThroughInboundCandidate(
            List<PassThroughCandidate> candidates,
            BigDecimal quantity
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (int index = 0; index < candidates.size(); index++) {
            PassThroughCandidate candidate = candidates.get(index);
            if (candidate.quantity().compareTo(quantity) == 0) {
                candidates.remove(index);
                return candidate;
            }
        }
        return null;
    }

    /**
     * Searches for a wallet-scoped inbound candidate whose network matches the outbound transaction.
     * Only candidates registered on {@code outboundNetworkId} are eligible, preventing accidental
     * cross-network corridor pairings (ADR-020, P0-b defensive guard).
     */
    private PassThroughCandidate selectWalletScopedInboundCandidate(
            Map<PassThroughScopeKey, List<PassThroughCandidate>> pendingInboundByScope,
            String walletAddress,
            String outboundNetworkId,
            String assetIdentity,
            BigDecimal quantity
    ) {
        if (pendingInboundByScope == null || pendingInboundByScope.isEmpty() || walletAddress == null || assetIdentity == null) {
            return null;
        }
        PassThroughScopeKey matchedScope = null;
        Integer matchedIndex = null;
        PassThroughCandidate matchedCandidate = null;

        for (Map.Entry<PassThroughScopeKey, List<PassThroughCandidate>> entry : pendingInboundByScope.entrySet()) {
            PassThroughScopeKey scopeKey = entry.getKey();
            if (scopeKey == null
                    || !assetIdentity.equals(scopeKey.assetIdentity())
                    || scopeKey.scope() == null
                    || !scopeKey.scope().startsWith(walletAddress + "->")) {
                continue;
            }
            List<PassThroughCandidate> candidates = entry.getValue();
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            for (int index = 0; index < candidates.size(); index++) {
                PassThroughCandidate candidate = candidates.get(index);
                if (candidate.quantity().compareTo(quantity) != 0) {
                    continue;
                }
                // Reject cross-network pairings: a LENDING_DEPOSIT on ZKSYNC must only pair with
                // a BRIDGE_IN that also arrived on ZKSYNC (ADR-020, P0-b).
                // Skip the check if either side has an unknown/null networkId.
                if (outboundNetworkId != null
                        && candidate.networkId() != null
                        && !outboundNetworkId.equals(candidate.networkId())) {
                    continue;
                }
                if (matchedCandidate != null) {
                    return null;
                }
                matchedScope = scopeKey;
                matchedIndex = index;
                matchedCandidate = candidate;
            }
        }

        if (matchedCandidate == null || matchedScope == null || matchedIndex == null) {
            return null;
        }
        List<PassThroughCandidate> candidates = pendingInboundByScope.get(matchedScope);
        if (candidates == null || matchedIndex < 0 || matchedIndex >= candidates.size()) {
            return null;
        }
        return candidates.remove((int) matchedIndex);
    }

    private boolean isWalletScopedOutboundCandidate(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        return switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    LENDING_DEPOSIT,
                    STAKING_DEPOSIT,
                    VAULT_DEPOSIT,
                    LP_ENTRY -> true;
            default -> false;
        };
    }

    private boolean isNotTransferLike(NormalizedTransaction.Flow flow) {
        return flow.getRole() == null || flow.getRole() == NormalizedLegRole.FEE;
    }

    private String networkId(NormalizedTransaction transaction) {
        return transaction.getNetworkId() != null ? transaction.getNetworkId().name() : null;
    }
}
