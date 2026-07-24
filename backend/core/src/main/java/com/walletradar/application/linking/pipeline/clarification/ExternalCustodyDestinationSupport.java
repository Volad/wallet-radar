package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Shared external-custody labeling applied by the per-family counterparty resolvers (WS-5, ADR-072).
 *
 * <p>When a resolved counterparty peer matches a user-designated external custody destination
 * (see {@link ExternalCustodyDestinationRegistry}), the flow and transaction counterparty are
 * relabeled as {@link CounterpartyType#EXTERNAL_CUSTODY} and the venue-neutral
 * {@code custodialOffChain} capability flag is stamped. The transaction <b>type is not changed</b>:
 * deposits remain {@code EXTERNAL_TRANSFER_OUT} and withdrawals {@code EXTERNAL_TRANSFER_IN} so
 * standard AVCO treatment applies (capital leaves scope on deposit; new capital at market on the
 * "count on exit" withdrawal). Because {@code EXTERNAL_CUSTODY} is not
 * {@link CounterpartyType#PERSONAL_WALLET}, the row is never promoted to {@code INTERNAL_TRANSFER},
 * and because the destination is never a universe member it produces no phantom balance — the
 * conservation gate is unaffected.</p>
 */
final class ExternalCustodyDestinationSupport {

    private ExternalCustodyDestinationSupport() {
    }

    static boolean applyCustodyLabel(NormalizedTransaction transaction, ExternalCustodyDestinationRegistry registry) {
        if (registry == null) {
            return false;
        }
        return applyCustodyLabel(transaction, registry::match);
    }

    /**
     * Generic custody labeling driven by an arbitrary peer→match lookup. The per-family resolvers use
     * this to consult multiple custody sources with identical relabel semantics: the per-session,
     * user-designated {@link ExternalCustodyDestinationRegistry} (ADR-072) and any global,
     * config-seeded operator registry (ADR-079). The lookup itself owns any family-specific
     * canonicalization — this method stays network-agnostic and TON/venue-neutral.
     */
    static boolean applyCustodyLabel(
            NormalizedTransaction transaction,
            BiFunction<String, NetworkId, Optional<ExternalCustodyDestinationRegistry.CustodyMatch>> lookup
    ) {
        if (transaction == null || lookup == null || transaction.getFlows() == null) {
            return false;
        }
        NetworkId network = transaction.getNetworkId();
        boolean changed = false;
        ExternalCustodyDestinationRegistry.CustodyMatch matched = null;

        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            String peer = flow.getCounterpartyAddress();
            if (peer == null || peer.isBlank()) {
                continue;
            }
            Optional<ExternalCustodyDestinationRegistry.CustodyMatch> match = lookup.apply(peer, network);
            if (match.isEmpty()) {
                continue;
            }
            matched = match.get();
            if (!CounterpartyType.EXTERNAL_CUSTODY.equals(flow.getCounterpartyType())) {
                flow.setCounterpartyType(CounterpartyType.EXTERNAL_CUSTODY);
                changed = true;
            }
        }

        if (matched == null) {
            return changed;
        }

        if (!CounterpartyType.EXTERNAL_CUSTODY.equals(transaction.getCounterpartyType())) {
            transaction.setCounterpartyType(CounterpartyType.EXTERNAL_CUSTODY);
            changed = true;
        }
        // Canonicalize the transaction-level counterparty only for single-counterparty rows (the
        // deposit/withdraw case) so a mixed MULTI row is never collapsed onto the custody venue.
        boolean singleCounterparty = !FlowCounterpartySupport.MULTI_COUNTERPARTY
                .equalsIgnoreCase(String.valueOf(transaction.getCounterpartyAddress()));
        if (singleCounterparty
                && matched.canonicalAddress() != null
                && !matched.canonicalAddress().isBlank()
                && !Objects.equals(transaction.getCounterpartyAddress(), matched.canonicalAddress())) {
            transaction.setCounterpartyAddress(matched.canonicalAddress());
            changed = true;
        }
        if (matched.label() != null && !matched.label().isBlank()
                && (transaction.getProtocolName() == null || transaction.getProtocolName().isBlank())) {
            transaction.setProtocolName(matched.label());
            changed = true;
        }
        if (!Boolean.TRUE.equals(transaction.getCustodialOffChain())) {
            transaction.setCustodialOffChain(true);
            changed = true;
        }
        return changed;
    }
}
