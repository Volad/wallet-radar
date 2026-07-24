package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.ExternalCapitalBoundary;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.store.NormalizedTransactionPostProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * On-chain external-capital boundary stamper (RC-S5), analogous to
 * {@link com.walletradar.application.cex.acquisition.venue.CexBoundaryContractStamper} but for
 * on-chain Solana inflows.
 *
 * <p>Stamps {@link ExternalCapitalBoundary#INFLOW} on a Solana {@code EXTERNAL_TRANSFER_IN} whose
 * counterparty is an unknown external EOA ({@code UNKNOWN_EOA}) and which carries at least one
 * inbound non-fee economic leg. This lets
 * {@link com.walletradar.application.costbasis.application.replay.dispatch.ReplayDispatcher}
 * relabel the priced inflow {@code UNKNOWN → ACQUIRE} (external capital booked at market).</p>
 *
 * <p><b>Guarantees</b>: only {@code EXTERNAL_TRANSFER_IN} is ever stamped — outbound and internal
 * transfers are never marked INFLOW. Inflows from owned/CEX peers (promoted to
 * {@code INTERNAL_TRANSFER}, or classified {@code PERSONAL_WALLET}/{@code CEX}) are carry/continuity
 * and are left unstamped. Scoped to {@link NetworkId#SOLANA}; EVM rows are untouched. Idempotent.</p>
 */
@Component
public class OnChainBoundaryContractStamper implements NormalizedTransactionPostProcessor {

    @Override
    public void process(NormalizedTransaction candidate) {
        if (candidate == null || candidate.getNetworkId() != NetworkId.SOLANA) {
            return;
        }
        if (candidate.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return;
        }
        if (candidate.getExternalCapitalBoundary() != null) {
            return;
        }
        if (!CounterpartyType.UNKNOWN_EOA.equals(candidate.getCounterpartyType())) {
            return;
        }
        if (!hasInboundEconomicLeg(candidate)) {
            return;
        }
        candidate.setExternalCapitalBoundary(ExternalCapitalBoundary.INFLOW);
    }

    private boolean hasInboundEconomicLeg(NormalizedTransaction candidate) {
        if (candidate.getFlows() == null) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : candidate.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0) {
                return true;
            }
        }
        return false;
    }
}
