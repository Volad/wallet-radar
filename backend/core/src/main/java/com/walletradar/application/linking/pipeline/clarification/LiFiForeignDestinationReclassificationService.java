package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionWalletAdjacencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CB-3: terminal classification for a LI.FI/Jumper {@code BRIDGE_OUT} whose bridge-settlement
 * status has resolved to a definitively foreign (untracked) destination.
 *
 * <p>Classification-spec §6.4 ({@code BRIDGE-01}) only models "no matching {@code BRIDGE_IN} found
 * yet" (temporary backfill lag). It has no terminal state for "the bridge's own settlement API
 * confirms the destination, and it's definitively not us" — a genuine type-model gap, not merely a
 * missing linking correlation. This pass fills that gap: once LI.FI's status API reports
 * {@code status=DONE} and {@code substatus=COMPLETED} (a {@code DONE}+{@code PARTIAL} settlement is
 * a materially different, out-of-scope case) with a resolved {@code toAddress} that is proven to be
 * outside the active accounting universe, and the destination network has finished backfilling for
 * this session, the row is reclassified from {@code BRIDGE_OUT} to {@code EXTERNAL_TRANSFER_OUT}
 * with a market-priced {@code SELL} disposal — mirroring the reciprocal case already handled by
 * {@link OwnWalletBridgeMistypeCorrectionService} (same-owner bridge mistakenly typed as a bridge)
 * and the closest functional analog, {@code UnmatchedBridgeInboundPricingFallbackService} (orphan
 * inbound demoted to a market-priced ACQUIRE).</p>
 *
 * <p>Tracked-wallet membership reuses exactly the same mechanism as
 * {@link OwnWalletBridgeMistypeCorrectionService}: {@link AccountingUniverseService#shareUniverseMembers}
 * and {@link SessionWalletAdjacencyService#anySessionListsBothAddresses}. "Protocol=LI.FI" is
 * satisfied by construction: only rows carrying persisted LI.FI status evidence
 * ({@code provider=LIFI}, see {@link LiFiBridgeStatus#fromDocument}) can ever pass the status gate
 * below, and the {@code bridge:lifi:} correlation prefix used to pre-filter candidates is stamped
 * exclusively by the LI.FI bridge-pairing pipeline ({@link LiFiBridgePairLinkService}).</p>
 *
 * <p>Idempotent by construction: the candidate query only matches rows still {@code BRIDGE_OUT}, so
 * re-running over an already-reclassified row is a no-op.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiFiForeignDestinationReclassificationService {

    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x[a-f0-9]{40}$");
    private static final String LIFI_CORRELATION_PREFIX = "bridge:lifi:";
    // Existing on-chain bridge missing-data reason (shared with OwnWalletBridgeMistypeCorrectionService /
    // KnownBridgeRouterExternalTypeCorrectionService / CrossNetworkBridgePairFallbackService); the
    // classification-spec's "BRIDGE_DESTINATION_UNKNOWN" wording for BRIDGE-01 has never been backed by
    // this literal string in code, so this reuses the reason actually stamped on these rows.
    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    private static final String FOREIGN_DESTINATION_EVIDENCE = "LIFI_STATUS_API_SETTLEMENT_FOREIGN_DESTINATION";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final RawTransactionRepository rawTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final SessionWalletAdjacencyService sessionWalletAdjacencyService;
    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;

    public int reclassifyForeignDestinations(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("correlationId").regex("^" + LIFI_CORRELATION_PREFIX),
                Criteria.where("continuityCandidate").is(false),
                Criteria.where("walletAddress").regex("^0x[a-fA-F0-9]{40}$")
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (reclassifyIfForeignDestination(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("LIFI_FOREIGN_DESTINATION_RECLASSIFICATION candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    boolean reclassifyIfForeignDestination(NormalizedTransaction candidate, Instant now) {
        if (candidate == null
                || candidate.getType() != NormalizedTransactionType.BRIDGE_OUT
                || candidate.getId() == null
                || !hasHexAddress(candidate.getWalletAddress())) {
            return false;
        }
        LiFiBridgeStatus status = readStatus(candidate).orElse(null);
        // Not-yet-DONE+COMPLETED, or a legacy/incrementally-cached document predating toAddress:
        // both are "unresolved", never a false non-match.
        if (status == null || !status.isDoneAndCompleted() || !status.hasResolvedToAddress()) {
            return false;
        }
        String walletAddress = candidate.getWalletAddress();
        String toAddress = status.toAddress();
        if (isTrackedWalletMember(walletAddress, toAddress)) {
            // Same-owner destination: unaffected, still routes through the existing pass-through corridor.
            return false;
        }
        if (!isDestinationNetworkBackfillComplete(walletAddress, status.receivingNetworkId())) {
            return false;
        }
        applyForeignDestinationReclassification(candidate, toAddress, now);
        return true;
    }

    private Optional<LiFiBridgeStatus> readStatus(NormalizedTransaction candidate) {
        RawTransaction rawTransaction = rawTransactionRepository.findById(candidate.getId()).orElse(null);
        if (rawTransaction == null || rawTransaction.getClarificationEvidence() == null) {
            return Optional.empty();
        }
        return LiFiBridgeStatus.fromDocument(rawTransaction.getClarificationEvidence().get("protocolStatus", Document.class));
    }

    /**
     * Reuses exactly the same mechanism {@link OwnWalletBridgeMistypeCorrectionService} already uses
     * for the structurally identical "is this address ours" question — same-address short circuit
     * plus {@link AccountingUniverseService#shareUniverseMembers} / session adjacency — so tracked-wallet
     * membership is decided by a single consistent accessor across both services.
     */
    private boolean isTrackedWalletMember(String walletAddress, String toAddress) {
        if (walletAddress == null || toAddress == null) {
            return false;
        }
        if (walletAddress.trim().equalsIgnoreCase(toAddress.trim())) {
            return true;
        }
        return accountingUniverseService.shareUniverseMembers(walletAddress, toAddress)
                || sessionWalletAdjacencyService.anySessionListsBothAddresses(walletAddress, toAddress);
    }

    /**
     * Mirrors the wallet×network backfill-completeness check used by
     * {@code SessionBackfillCompletionPublisher}/{@code SessionPipelineResumeScheduler} to gate
     * session pipeline progression, scoped here to the single destination network. A session with no
     * wallet targeting the destination network at all has nothing pending on it and passes trivially;
     * a targeted wallet×network that has not finished backfill blocks reclassification until it does.
     */
    private boolean isDestinationNetworkBackfillComplete(String sourceWalletAddress, NetworkId destinationNetworkId) {
        if (sourceWalletAddress == null || destinationNetworkId == null) {
            return false;
        }
        List<UserSession> sessions = userSessionRepository.findAllByWalletsAddress(sourceWalletAddress);
        if (sessions.isEmpty()) {
            // No session context to gate against — do not reclassify without a definitive backfill signal.
            return false;
        }
        for (UserSession session : sessions) {
            if (session == null || session.getWallets() == null) {
                continue;
            }
            for (UserSession.SessionWallet wallet : session.getWallets()) {
                if (wallet == null
                        || wallet.getNetworks() == null
                        || !wallet.getNetworks().contains(destinationNetworkId)) {
                    continue;
                }
                SyncStatus status = syncStatusRepository
                        .findOnChainByWalletAddressAndNetworkId(
                                SyncStatus.SourceKind.ONCHAIN,
                                wallet.getAddress(),
                                destinationNetworkId.name()
                        )
                        .orElse(null);
                if (!isOnChainBackfillComplete(status)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isOnChainBackfillComplete(SyncStatus status) {
        return status != null
                && (status.isBackfillComplete() || status.getStatus() == SyncStatus.SyncStatusValue.COMPLETE);
    }

    private void applyForeignDestinationReclassification(
            NormalizedTransaction transaction,
            String toAddress,
            Instant now
    ) {
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        retagPrincipalFlowsAsForeignSell(transaction, toAddress);
        transaction.setCounterpartyAddress(toAddress);
        transaction.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        transaction.setCounterpartyResolutionState(MetadataResolutionState.RESOLVED_EXACT);
        transaction.setCounterpartyResolutionEvidence(FOREIGN_DESTINATION_EVIDENCE);
        removeMissingReason(transaction, BRIDGE_MISSING_REASON);
        if (transaction.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
            transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            transaction.setConfirmedAt(null);
        }
        transaction.setUpdatedAt(now);
    }

    /**
     * Reprices the principal outbound flow(s) as a market-at-timestamp SELL instead of a
     * basis-preserving bridge TRANSFER — the same reprice-and-defer-to-pricing-job convention
     * {@code UnmatchedBridgeInboundPricingFallbackService} already uses (clear the TRANSFER role,
     * leave price fields null, let the standard PENDING_PRICE pipeline resolve market price at
     * {@code blockTimestamp}; no bespoke pricing/market-lookup helper is introduced here).
     */
    private static void retagPrincipalFlowsAsForeignSell(NormalizedTransaction transaction, String toAddress) {
        if (transaction.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null
                    || flow.getRole() == NormalizedLegRole.FEE
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            flow.setRole(NormalizedLegRole.SELL);
            flow.setUnitPriceUsd(null);
            flow.setValueUsd(null);
            flow.setPriceSource(null);
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);
            flow.setCounterpartyAddress(toAddress);
            flow.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        }
        FlowCounterpartySupport.applyTransactionCounterparty(transaction);
    }

    private static void removeMissingReason(NormalizedTransaction transaction, String reason) {
        List<String> reasons = transaction.getMissingDataReasons();
        if (reasons == null || reasons.isEmpty()) {
            return;
        }
        reasons.removeIf(reason::equals);
    }

    private static boolean hasHexAddress(String value) {
        return value != null
                && !value.isBlank()
                && HEX_ADDRESS.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
