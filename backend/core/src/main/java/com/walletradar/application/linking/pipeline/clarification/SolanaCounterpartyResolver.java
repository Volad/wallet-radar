package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.solana.SolanaRawTransactionView;
import com.walletradar.application.session.application.AccountingUniverseService;
import org.bson.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Solana counterparty resolver (ADR-066, RC-S2b + RC-S3).
 *
 * <p>Resolution strategy, program-ID-first (matching {@link com.walletradar.application.normalization.pipeline.solana.SolanaTransactionClassifier}):
 * <ul>
 *   <li><b>Protocol rows</b> — the first {@code instructions[].programId} (including inner
 *       instructions) that matches a Solana registry entry becomes the counterparty:
 *       {@code counterpartyType = PROTOCOL} (or {@code BRIDGE}) and the program ID as the address.</li>
 *   <li><b>Transfer rows</b> — the non-wallet peer from {@code nativeTransfers}/{@code tokenTransfers}
 *       is classified via {@link AccountingUniverseService#classify} into
 *       {@code PERSONAL_WALLET} / {@code CEX} / {@code UNKNOWN_EOA}.</li>
 * </ul>
 * Solana addresses are case-sensitive base58 and are never lowercased or {@code 0x}-normalised.</p>
 */
@Component
public class SolanaCounterpartyResolver implements CounterpartyResolver {

    private static final String EVIDENCE_PROGRAM = "SOLANA_PROGRAM_ID_REGISTRY";
    private static final String EVIDENCE_PEER = "SOLANA_TRANSFER_PEER";
    private static final String EVIDENCE_INFERRED = "SOLANA_COUNTERPARTY_INFERRED";

    private final ProtocolRegistryService protocolRegistryService;
    private final AccountingUniverseService accountingUniverseService;
    private final ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    public SolanaCounterpartyResolver(
            ProtocolRegistryService protocolRegistryService,
            AccountingUniverseService accountingUniverseService,
            ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.accountingUniverseService = accountingUniverseService;
        this.externalCustodyDestinationRegistry = externalCustodyDestinationRegistry;
    }

    @Override
    public boolean supports(@Nullable NetworkId networkId) {
        return networkId == NetworkId.SOLANA;
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
        if (rawTransaction == null) {
            return terminalizeMissingRaw(transaction, now);
        }

        SolanaRawTransactionView view = SolanaRawTransactionView.wrap(rawTransaction);
        String walletAddress = present(view.walletAddress()) ? view.walletAddress() : transaction.getWalletAddress();
        String accountRef = FlowCounterpartySupport.onChainAccountRef(NetworkId.SOLANA, walletAddress);

        ProtocolRegistryEntry protocolEntry = firstRegistryProgram(view);
        String protocolProgramId = protocolEntry == null ? null : protocolEntry.contractAddress();
        boolean isProtocolRow = protocolProgramId != null;
        String protocolCounterpartyType = protocolEntry != null && isBridgeRole(protocolEntry.role())
                ? CounterpartyType.BRIDGE
                : CounterpartyType.PROTOCOL;

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

                String peer;
                String peerType;
                if (isProtocolRow) {
                    peer = protocolProgramId;
                    peerType = protocolCounterpartyType;
                } else {
                    peer = present(flow.getCounterpartyAddress())
                            ? flow.getCounterpartyAddress()
                            : resolveTransferPeer(view, walletAddress);
                    peerType = present(peer) ? classifyPeer(peer) : CounterpartyType.UNKNOWN_EOA;
                    if (!present(peer)) {
                        peer = "UNKNOWN:" + syntheticFlowKey(transaction, flow);
                    }
                }
                if (!present(flow.getCounterpartyAddress())) {
                    flow.setCounterpartyAddress(peer);
                    changed = true;
                }
                if (!present(flow.getCounterpartyType())) {
                    flow.setCounterpartyType(peerType);
                    changed = true;
                }
            }
        }

        // Reconcile transaction-level counterparty from enriched flows.
        FlowCounterpartySupport.applyTransactionCounterparty(transaction);

        if (isProtocolRow) {
            if (!present(transaction.getCounterpartyAddress())
                    || FlowCounterpartySupport.MULTI_COUNTERPARTY.equalsIgnoreCase(transaction.getCounterpartyAddress())) {
                if (!Objects.equals(transaction.getCounterpartyAddress(), protocolProgramId)) {
                    transaction.setCounterpartyAddress(protocolProgramId);
                    changed = true;
                }
            }
            if (!present(transaction.getCounterpartyType())
                    || FlowCounterpartySupport.MULTI_COUNTERPARTY_TYPE.equalsIgnoreCase(transaction.getCounterpartyType())) {
                transaction.setCounterpartyType(protocolCounterpartyType);
                changed = true;
            }
            if (protocolEntry != null
                    && present(protocolEntry.protocolName())
                    && !present(transaction.getProtocolName())) {
                transaction.setProtocolName(protocolEntry.protocolName());
                changed = true;
            }
            changed |= setResolutionState(transaction, MetadataResolutionState.RESOLVED_EXACT, EVIDENCE_PROGRAM);
        } else {
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
        }

        // WS-5 (ADR-072): relabel + stamp custodialOffChain when the peer is a user-designated
        // external custody destination. Protocol rows keep their program-derived counterparty.
        if (!isProtocolRow
                && ExternalCustodyDestinationSupport.applyCustodyLabel(transaction, externalCustodyDestinationRegistry)) {
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

    private ProtocolRegistryEntry firstRegistryProgram(SolanaRawTransactionView view) {
        if (protocolRegistryService == null) {
            return null;
        }
        for (String programId : view.programIds()) {
            Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(NetworkId.SOLANA, programId);
            if (entry.isPresent()) {
                return entry.get();
            }
        }
        return null;
    }

    private String resolveTransferPeer(SolanaRawTransactionView view, String walletAddress) {
        if (!present(walletAddress)) {
            return null;
        }
        for (Document transfer : view.nativeTransfers()) {
            String peer = peerFromTransfer(transfer, walletAddress);
            if (present(peer)) {
                return peer;
            }
        }
        for (Document transfer : view.tokenTransfers()) {
            String peer = peerFromTransfer(transfer, walletAddress);
            if (present(peer)) {
                return peer;
            }
        }
        return null;
    }

    private String peerFromTransfer(Document transfer, String walletAddress) {
        if (transfer == null) {
            return null;
        }
        String from = transfer.getString("fromUserAccount");
        String to = transfer.getString("toUserAccount");
        if (walletAddress.equals(to) && present(from) && !walletAddress.equals(from)) {
            return from;
        }
        if (walletAddress.equals(from) && present(to) && !walletAddress.equals(to)) {
            return to;
        }
        return null;
    }

    private String classifyPeer(String peer) {
        if (!present(peer) || accountingUniverseService == null) {
            return CounterpartyType.UNKNOWN_EOA;
        }
        try {
            AccountingUniverseService.OwnMembership membership =
                    accountingUniverseService.classify(peer, NetworkId.SOLANA);
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

    private boolean terminalizeMissingRaw(NormalizedTransaction transaction, Instant now) {
        boolean changed = false;
        if (!present(transaction.getCounterpartyType())) {
            transaction.setCounterpartyType(CounterpartyType.GENUINE_MISSING_SOURCE);
            changed = true;
        }
        changed |= setResolutionState(
                transaction,
                MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING,
                "RAW_TRANSACTION_MISSING"
        );
        FlowCounterpartySupport.syncFlowsFromTransaction(transaction);
        if (changed) {
            transaction.setUpdatedAt(now == null ? Instant.now() : now);
        }
        return changed;
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

    private boolean promoteExternalTransferToInternal(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null || transaction.getCounterpartyType() == null) {
            return false;
        }
        if (!CounterpartyType.PERSONAL_WALLET.equals(transaction.getCounterpartyType())) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && type != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        transaction.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            transaction.setExcludedFromAccounting(false);
            transaction.setAccountingExclusionReason(null);
        }
        return true;
    }

    private boolean isBridgeRole(ProtocolRegistryRole role) {
        return role == ProtocolRegistryRole.BRIDGE_ENTRY || role == ProtocolRegistryRole.BRIDGE_EXIT;
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
