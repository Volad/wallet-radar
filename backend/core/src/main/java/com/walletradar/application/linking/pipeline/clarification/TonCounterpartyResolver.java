package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.session.application.AccountingUniverseService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * TON counterparty resolver (ADR-066, PR3 RC-T1.4).
 *
 * <p>The TON normalizer already sets each non-fee flow's {@code counterpartyAddress} to the peer
 * (raw {@code workchain:hex} form emitted by TON Center, or an owner address for jetton transfers).
 * This resolver canonicalises that peer through {@link TonAddressCanonicalizer} and classifies it
 * via {@link AccountingUniverseService#classify} into {@code PERSONAL_WALLET} / {@code CEX} /
 * {@code UNKNOWN_EOA}, then reconciles the transaction-level counterparty. TON addresses are
 * case-sensitive and are never lowercased or {@code 0x}-normalised.</p>
 *
 * <p>Only protocol-name + counterparty metadata is touched (consistent with the Solana step set in
 * {@link com.walletradar.application.normalization.pipeline.CanonicalMetadataEnricher}); economics
 * are owned by the builder.</p>
 */
@Component
public class TonCounterpartyResolver implements CounterpartyResolver {

    private static final String EVIDENCE_PEER = "TON_TRANSFER_PEER";
    private static final String EVIDENCE_INFERRED = "TON_COUNTERPARTY_INFERRED";

    private final AccountingUniverseService accountingUniverseService;
    private final ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    public TonCounterpartyResolver(
            AccountingUniverseService accountingUniverseService,
            ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry
    ) {
        this.accountingUniverseService = accountingUniverseService;
        this.externalCustodyDestinationRegistry = externalCustodyDestinationRegistry;
    }

    @Override
    public boolean supports(@Nullable NetworkId networkId) {
        return networkId == NetworkId.TON;
    }

    @Override
    public boolean enrichInPlace(
            NormalizedTransaction transaction,
            @Nullable RawTransaction rawTransaction,
            Instant now
    ) {
        if (transaction == null) {
            return false;
        }

        String walletAddress = transaction.getWalletAddress();
        String accountRef = FlowCounterpartySupport.onChainAccountRef(NetworkId.TON, walletAddress);

        boolean changed = false;
        if (transaction.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                if (present(accountRef) && !Objects.equals(flow.getAccountRef(), accountRef)) {
                    flow.setAccountRef(accountRef);
                    changed = true;
                }
                if (flow.getRole() == NormalizedLegRole.FEE) {
                    if (!present(flow.getCounterpartyAddress())) {
                        flow.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");
                        changed = true;
                    }
                    if (!present(flow.getCounterpartyType())) {
                        flow.setCounterpartyType(CounterpartyType.GENUINE_MISSING_SOURCE);
                        changed = true;
                    }
                    continue;
                }

                String peer = flow.getCounterpartyAddress();
                if (!present(peer)) {
                    flow.setCounterpartyAddress("UNKNOWN:" + syntheticFlowKey(transaction, flow));
                    changed = true;
                }
                if (!present(flow.getCounterpartyType())) {
                    flow.setCounterpartyType(present(peer) ? classifyPeer(peer) : CounterpartyType.UNKNOWN_EOA);
                    changed = true;
                }
            }
        }

        FlowCounterpartySupport.applyTransactionCounterparty(transaction);

        if (!present(transaction.getCounterpartyType())) {
            transaction.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
            changed = true;
        }
        boolean resolvedPeer = present(transaction.getCounterpartyAddress())
                && !transaction.getCounterpartyAddress().toUpperCase().startsWith("UNKNOWN:");
        changed |= setResolutionState(
                transaction,
                resolvedPeer ? MetadataResolutionState.RESOLVED_EXACT : MetadataResolutionState.TERMINAL_METADATA_ONLY,
                resolvedPeer ? EVIDENCE_PEER : EVIDENCE_INFERRED
        );

        // Custody attribution (runs before promotion so the EXTERNAL_CUSTODY label sticks and the
        // row is never promoted to INTERNAL_TRANSFER). Two deterministic sources with identical
        // relabel semantics, consulted together:
        //   1. WS-5 / ADR-072: per-session, user-designated external custody destinations.
        //   2. ADR-079: the global, config-seeded custodial-operator registry (e.g. Telegram Wallet).
        // A registry match relabels the peer EXTERNAL_CUSTODY + label + stamps custodialOffChain,
        // reusing ADR-072's model exactly. Operators absent from BOTH sources keep the peer type set
        // above by classifyPeer (Bybit -> CEX/EXCHANGE_ACCOUNT, unknown highload -> UNKNOWN_EOA); TON
        // DEX-router labeling from TonProtocolRegistry (normalization plane) is untouched. tonapi and
        // the highload interface are offline discovery aids for seeding the registry only — never a
        // runtime lookup here.
        if (ExternalCustodyDestinationSupport.applyCustodyLabel(transaction, externalCustodyDestinationRegistry)) {
            changed = true;
        }
        if (ExternalCustodyDestinationSupport.applyCustodyLabel(
                transaction, (peer, network) -> TonCustodialOperatorRegistry.match(peer))) {
            changed = true;
        }

        if (promoteExternalTransferToInternal(transaction)) {
            changed = true;
        }

        if (changed) {
            transaction.setUpdatedAt(now == null ? Instant.now() : now);
        }
        return changed;
    }

    private String classifyPeer(String peer) {
        if (!present(peer)) {
            return CounterpartyType.UNKNOWN_EOA;
        }
        try {
            AccountingUniverseService.OwnMembership membership =
                    accountingUniverseService.classify(peer, NetworkId.TON);
            if (membership.isMember()) {
                if (membership.memberType() == AccountingUniverse.MemberType.ON_CHAIN_WALLET) {
                    return CounterpartyType.PERSONAL_WALLET;
                }
                if (membership.memberType() == AccountingUniverse.MemberType.EXCHANGE_ACCOUNT) {
                    return CounterpartyType.CEX;
                }
            }
        } catch (IllegalStateException ignored) {
            // Universe not bound on this thread; fall through to unknown-external.
        }
        return CounterpartyType.UNKNOWN_EOA;
    }

    private boolean promoteExternalTransferToInternal(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null || transaction.getCounterpartyType() == null) {
            return false;
        }
        if (!CounterpartyType.PERSONAL_WALLET.equals(transaction.getCounterpartyType())) {
            return false;
        }
        com.walletradar.domain.transaction.normalized.NormalizedTransactionType type = transaction.getType();
        if (type != com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && type != com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        transaction.setType(com.walletradar.domain.transaction.normalized.NormalizedTransactionType.INTERNAL_TRANSFER);
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            transaction.setExcludedFromAccounting(false);
            transaction.setAccountingExclusionReason(null);
        }
        return true;
    }

    private boolean setResolutionState(NormalizedTransaction transaction, String state, String evidence) {
        boolean changed = false;
        if (!Objects.equals(transaction.getCounterpartyResolutionState(), state)) {
            transaction.setCounterpartyResolutionState(state);
            changed = true;
        }
        if (!Objects.equals(transaction.getCounterpartyResolutionEvidence(), evidence)) {
            transaction.setCounterpartyResolutionEvidence(evidence);
            changed = true;
        }
        return changed;
    }

    private String syntheticFlowKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return String.join(":",
                transaction.getId() == null ? "tx" : transaction.getId(),
                flow.getRole() == null ? "role" : flow.getRole().name(),
                flow.getAssetSymbol() == null ? "asset" : flow.getAssetSymbol());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
