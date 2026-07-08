package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction.Flow;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * FA-001 D3: when the classifier labels an inbound receipt as {@link NormalizedTransactionType#VAULT_WITHDRAW}
 * but the interacted contract is registry-marked as a {@link NormalizedTransactionType#BRIDGE_IN} endpoint,
 * promote the canonical type so counterparty resolution and replay follow bridge semantics.
 */
@Service
@RequiredArgsConstructor
public class RegistryBridgeInboundTypeCorrectionService {

    private final ProtocolRegistryService protocolRegistryService;

    public void correctIfApplicable(NormalizedTransaction normalized, RawTransaction raw, Instant now) {
        if (normalized == null || raw == null || now == null) {
            return;
        }
        if (normalized.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            return;
        }
        if (normalized.getType() != NormalizedTransactionType.VAULT_WITHDRAW) {
            return;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        NetworkId networkId = view.networkId();
        if (networkId == null) {
            return;
        }
        String interacted = firstNonBlank(
                view.interactionToAddress(),
                view.toAddress(),
                view.contractAddress()
        );
        if (interacted == null) {
            return;
        }
        ProtocolRegistryEntry entry = protocolRegistryService.lookup(networkId, interacted).orElse(null);
        if (entry == null || entry.normalizedType() != NormalizedTransactionType.BRIDGE_IN) {
            return;
        }
        if (!hasNonFeePrincipalFlow(normalized)) {
            return;
        }
        normalized.setType(NormalizedTransactionType.BRIDGE_IN);
        normalized.setUpdatedAt(now);
        retagPrincipalFlowsForBridgeContinuity(normalized, now);
    }

    private static boolean hasNonFeePrincipalFlow(NormalizedTransaction normalized) {
        if (normalized.getFlows() == null) {
            return false;
        }
        return normalized.getFlows().stream()
                .filter(Objects::nonNull)
                .anyMatch(flow -> flow.getRole() != NormalizedLegRole.FEE
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0);
    }

    private static void retagPrincipalFlowsForBridgeContinuity(NormalizedTransaction normalized, Instant now) {
        if (normalized.getFlows() == null) {
            return;
        }
        boolean changed = false;
        for (Flow flow : normalized.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                flow.setRole(NormalizedLegRole.TRANSFER);
                changed = true;
            }
            if (flow.getUnitPriceUsd() != null) {
                flow.setUnitPriceUsd(null);
                changed = true;
            }
            if (flow.getValueUsd() != null) {
                flow.setValueUsd(null);
                changed = true;
            }
            if (flow.getPriceSource() != null) {
                flow.setPriceSource(null);
                changed = true;
            }
            if (flow.getAvcoAtTimeOfSale() != null) {
                flow.setAvcoAtTimeOfSale(null);
                changed = true;
            }
            if (flow.getRealisedPnlUsd() != null) {
                flow.setRealisedPnlUsd(null);
                changed = true;
            }
        }
        if (changed) {
            normalized.setUpdatedAt(now);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
