package com.walletradar.application.linking.job;

import com.walletradar.application.linking.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.application.linking.pipeline.clarification.BridgePairContinuityRepairService;
import com.walletradar.application.linking.pipeline.clarification.BybitTransferContinuityRepairService;
import com.walletradar.application.linking.pipeline.clarification.CowSwapEthFlowSettlementLinkService;
import com.walletradar.application.linking.pipeline.clarification.CrossNetworkBridgePairFallbackService;
import com.walletradar.application.linking.pipeline.clarification.InternalTransferPairLinkService;
import com.walletradar.application.linking.pipeline.clarification.OnChainInternalTransferPairRepairService;
import com.walletradar.application.linking.pipeline.clarification.LiFiBridgePairLinkService;
import com.walletradar.application.linking.pipeline.clarification.MayanCctpBridgePairLinkService;
import com.walletradar.application.linking.pipeline.clarification.MultiCounterpartyCorrectionService;
import com.walletradar.application.linking.pipeline.clarification.OnChainLifecycleLinkService;
import com.walletradar.application.linking.pipeline.clarification.LiFiForeignDestinationReclassificationService;
import com.walletradar.application.linking.pipeline.clarification.OwnWalletBridgeMistypeCorrectionService;
import com.walletradar.application.linking.pipeline.clarification.BybitInternalTransferOrphanFallbackService;
import com.walletradar.application.linking.pipeline.clarification.BybitOnChainEarnOrphanRepairService;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferExternalCpReclassifier;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferPairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapser;
import com.walletradar.application.linking.pipeline.clarification.AddressPoisoningDetector;
import com.walletradar.application.linking.pipeline.clarification.EtherFiOftBridgeInClassifier;
import com.walletradar.application.linking.pipeline.clarification.GmxEntryRequestLinkService;
import com.walletradar.application.linking.pipeline.clarification.GmxExecutionFeeRefundBasisNeutralService;
import com.walletradar.application.linking.pipeline.clarification.GmxExitSettlementLinkService;
import com.walletradar.application.linking.pipeline.clarification.GmxV2RefundClassifier;
import com.walletradar.application.linking.pipeline.clarification.GmxWithdrawalSettlementLinkService;
import com.walletradar.application.linking.pipeline.clarification.KnownBridgeRouterExternalTypeCorrectionService;
import com.walletradar.application.linking.pipeline.clarification.LendingLoopOpenClosePairLinkService;
import com.walletradar.application.linking.pipeline.clarification.NftMintRetagger;
import com.walletradar.application.linking.pipeline.clarification.ProtocolAttributionClassifier;
import com.walletradar.application.linking.pipeline.clarification.AaveVariableDebtTokenTagger;
import com.walletradar.application.linking.pipeline.clarification.EulerEvkDebtTokenTagger;
import com.walletradar.application.linking.pipeline.clarification.ScamDisperseClonePhishingTagger;
import com.walletradar.application.linking.pipeline.clarification.SpoofTokenDetector;
import com.walletradar.application.linking.pipeline.clarification.TurtleVaultBurnRepairService;
import com.walletradar.application.linking.pipeline.clarification.UnmatchedBridgeInboundPricingFallbackService;
import com.walletradar.application.linking.pipeline.clarification.UnmatchedExternalTransferInPricingFallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Dedicated driver for deterministic cross-row and cross-source linking passes.
 *
 * <p>Passes are split into two groups:
 * <ol>
 *   <li><b>Convergent passes</b> ({@link #processConvergentPasses}) — take a {@code batchSize}
 *       argument and naturally terminate when there are no more candidates. Callers loop these
 *       until they return 0.</li>
 *   <li><b>Terminal passes</b> ({@link #runTerminalPasses}) — perform full-collection scans that
 *       are only meaningful once the convergent passes have converged. They are called once per
 *       convergence cycle, not on every batch iteration.</li>
 * </ol>
 *
 * <p>Each pass is wrapped in {@link #timedPass} which logs its duration at DEBUG level and emits
 * an INFO warning for any pass taking longer than {@value SLOW_PASS_THRESHOLD_MS} ms.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class LinkingBatchProcessor {

    /** Log a WARN if any single pass exceeds this duration (ms). */
    private static final long SLOW_PASS_THRESHOLD_MS = 500;

    private final BybitBridgeLinkService bybitBridgeLinkService;
    private final OnChainLifecycleLinkService onChainLifecycleLinkService;
    private final LiFiBridgePairLinkService liFiBridgePairLinkService;
    private final LiFiForeignDestinationReclassificationService liFiForeignDestinationReclassificationService;
    private final MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService;
    private final AcrossBridgePairLinkService acrossBridgePairLinkService;
    private final CowSwapEthFlowSettlementLinkService cowSwapEthFlowSettlementLinkService;
    private final InternalTransferPairLinkService internalTransferPairLinkService;
    private final BybitTransferContinuityRepairService bybitTransferContinuityRepairService;
    private final UnmatchedBridgeInboundPricingFallbackService unmatchedBridgeInboundPricingFallbackService;
    private final BybitInternalTransferOrphanFallbackService bybitInternalTransferOrphanFallbackService;
    private final BybitOnChainEarnOrphanRepairService bybitOnChainEarnOrphanRepairService;
    private final BybitInternalTransferPairer bybitInternalTransferPairer;
    private final UnmatchedExternalTransferInPricingFallbackService unmatchedExternalTransferInPricingFallbackService;
    private final BridgePairContinuityRepairService bridgePairContinuityRepairService;
    private final OnChainInternalTransferPairRepairService onChainInternalTransferPairRepairService;
    private final BybitInternalTransferExternalCpReclassifier bybitInternalTransferExternalCpReclassifier;
    private final BybitStreamAuthorityCollapser bybitStreamAuthorityCollapser;

    private final KnownBridgeRouterExternalTypeCorrectionService knownBridgeRouterExternalTypeCorrectionService;
    private final OwnWalletBridgeMistypeCorrectionService ownWalletBridgeMistypeCorrectionService;
    private final MultiCounterpartyCorrectionService multiCounterpartyCorrectionService;
    private final CrossNetworkBridgePairFallbackService crossNetworkBridgePairFallbackService;
    private final TurtleVaultBurnRepairService turtleVaultBurnRepairService;
    private final LendingLoopOpenClosePairLinkService lendingLoopOpenClosePairLinkService;

    private final ProtocolAttributionClassifier protocolAttributionClassifier;
    private final AddressPoisoningDetector addressPoisoningDetector;
    private final SpoofTokenDetector spoofTokenDetector;
    private final ScamDisperseClonePhishingTagger scamDisperseClonePhishingTagger;
    private final AaveVariableDebtTokenTagger aaveVariableDebtTokenTagger;
    private final EulerEvkDebtTokenTagger eulerEvkDebtTokenTagger;
    private final GmxExitSettlementLinkService gmxExitSettlementLinkService;
    private final GmxEntryRequestLinkService gmxEntryRequestLinkService;
    private final GmxV2RefundClassifier gmxV2RefundClassifier;
    private final GmxWithdrawalSettlementLinkService gmxWithdrawalSettlementLinkService;
    private final GmxExecutionFeeRefundBasisNeutralService gmxExecutionFeeRefundBasisNeutralService;
    private final EtherFiOftBridgeInClassifier etherFiOftBridgeInClassifier;
    private final NftMintRetagger nftMintRetagger;

    // ── Legacy entry-point kept for callers that do not use the split loop ──────

    int processNextBatch(int batchSize) {
        return processNextBatch(batchSize, () -> {
        });
    }

    int processNextBatch(int batchSize, Runnable progressHeartbeat) {
        int convergent = processConvergentPasses(batchSize, progressHeartbeat);
        int terminal   = runTerminalPasses(batchSize, progressHeartbeat);
        return convergent + terminal;
    }

    // ── Convergent passes ─────────────────────────────────────────────────────
    //   These use batchSize and naturally converge to 0 when all candidates
    //   have been processed.  Call in a loop until the return value is 0.

    int processConvergentPasses(int batchSize, Runnable progressHeartbeat) {
        int processed = 0;

        processed += timedPass("bybitBridgeLink",
                () -> bybitBridgeLinkService.reconcileOutstandingPairs(batchSize));
        progressHeartbeat.run();

        processed += timedPass("onChainLifecycleLink",
                () -> onChainLifecycleLinkService.processNextBatch(batchSize));
        progressHeartbeat.run();

        // GMX-EXIT-LINK: copy gmx-lp: correlationId from resolved LP_EXIT_REQUEST to LP_EXIT_SETTLEMENT
        processed += timedPass("gmxExitSettlementLink",
                () -> gmxExitSettlementLinkService.linkOutstandingSettlements(batchSize));
        progressHeartbeat.run();

        // GMX-ENTRY-LINK: copy gmx-lp: correlationId from LP_ENTRY_SETTLEMENT to LP_ENTRY_REQUEST
        processed += timedPass("gmxEntryRequestLink",
                () -> gmxEntryRequestLinkService.linkOutstandingRequests(batchSize));
        progressHeartbeat.run();

        processed += timedPass("liFiBridgePairLink",
                () -> liFiBridgePairLinkService.reconcileOutstandingSources(batchSize));
        progressHeartbeat.run();

        // CB-3: run immediately after liFiBridgePairLink so LI.FI status evidence (status/substatus/
        // toAddress) persisted by that pass is already available for the foreign-destination gate.
        processed += timedPass("liFiForeignDestinationReclassification",
                () -> liFiForeignDestinationReclassificationService.reclassifyForeignDestinations(batchSize));
        progressHeartbeat.run();

        processed += timedPass("mayanCctpBridgePairLink",
                () -> mayanCctpBridgePairLinkService.reconcileOutstandingSources(batchSize));
        progressHeartbeat.run();

        processed += timedPass("acrossBridgePairLink",
                () -> acrossBridgePairLinkService.reconcileOutstandingSources(batchSize));
        progressHeartbeat.run();

        processed += timedPass("cowSwapEthFlowSettlementLink",
                () -> cowSwapEthFlowSettlementLinkService.linkOutstandingSettlements(batchSize));
        progressHeartbeat.run();

        processed += timedPass("internalTransferPairLink",
                () -> internalTransferPairLinkService.reconcileOutstandingPairs(batchSize));
        progressHeartbeat.run();

        processed += timedPass("knownBridgeRouterExternalTypeCorrection",
                () -> knownBridgeRouterExternalTypeCorrectionService.reclassifyKnownRouterExternals(batchSize));
        progressHeartbeat.run();

        // BR-1: reclassify own-wallet bridge mistypes before bridge pairing
        processed += timedPass("ownWalletBridgeMistypeCorrection",
                () -> ownWalletBridgeMistypeCorrectionService.reclassifyOwnWalletBridgeMistypes(batchSize));
        progressHeartbeat.run();

        // WS-3b Phase A: EXTERNAL_TRANSFER + MULTI + own wallet → INTERNAL_TRANSFER
        processed += timedPass("ownWalletMultiCpCorrection",
                () -> ownWalletBridgeMistypeCorrectionService.reclassifyMultiCpOwnWalletTransfers(batchSize));
        progressHeartbeat.run();

        // WS-3b Phase B: stamp concrete counterparty on remaining MULTI externals
        processed += timedPass("deMultiExternalTransfers",
                () -> multiCounterpartyCorrectionService.deMultiExternalTransfers(batchSize));
        progressHeartbeat.run();

        // WS-3b Phase C: EXTERNAL_TRANSFER_OUT + known aggregator → SWAP
        processed += timedPass("retypeAggregatorSwapMistypes",
                () -> multiCounterpartyCorrectionService.retypeAggregatorSwapMistypes(batchSize));
        progressHeartbeat.run();

        processed += timedPass("bybitTransferContinuityRepair1",
                () -> bybitTransferContinuityRepairService.reconcileOutstandingPairs(batchSize));
        progressHeartbeat.run();

        processed += timedPass("bybitInternalTransferExternalCpReclassifier",
                () -> bybitInternalTransferExternalCpReclassifier.reclassifySameUidExternalToInternal(Instant.now()));
        progressHeartbeat.run();

        // R12 Fix 11: stamp known protocol counterparties before cross-network pairing
        processed += timedPass("protocolAttributionClassifier",
                () -> protocolAttributionClassifier.classifyProtocolAttribution(batchSize));
        progressHeartbeat.run();

        processed += timedPass("crossNetworkBridgePairFallback",
                () -> crossNetworkBridgePairFallbackService.reconcileOrphanInbounds(batchSize));
        progressHeartbeat.run();

        // R11 Fix 5: exclude address-poisoning dust IN (vanity-prefix match)
        processed += timedPass("addressPoisoningDetector",
                () -> addressPoisoningDetector.detectAndExclude(batchSize));
        progressHeartbeat.run();

        // SF-1(a): quarantine confusable-symbol spoof tokens
        processed += timedPass("spoofTokenDetector",
                () -> spoofTokenDetector.detectAndExclude(batchSize));
        progressHeartbeat.run();

        // R11 Fix 6: tag phishing OUT via known scam disperse-clone contracts
        processed += timedPass("scamDisperseClonePhishingTagger",
                () -> scamDisperseClonePhishingTagger.tagPhishingOutbounds(batchSize));
        progressHeartbeat.run();

        // BLOCKER-9 / ADR-057: exclude Euler Finance v2 EVK internal debt-token inflows
        processed += timedPass("eulerEvkDebtTokenTagger",
                () -> eulerEvkDebtTokenTagger.tagDebtTokenInflows(batchSize));
        progressHeartbeat.run();

        // RC-3: exclude Aave V3 internal variable-debt token flows on AVALANCHE
        processed += timedPass("aaveVariableDebtTokenTagger",
                () -> aaveVariableDebtTokenTagger.tagDebtTokenFlows(batchSize));
        progressHeartbeat.run();

        // R11 Fix 7: stamp GMX V2 execution-fee refunds
        processed += timedPass("gmxV2RefundClassifier",
                () -> gmxV2RefundClassifier.classifyGmxRefunds(batchSize));
        progressHeartbeat.run();

        // NEW-09: pair two-step GMX GLV/GM withdrawal settlements (fee-refund-stamped native
        // inflows) to their open LP_EXIT_REQUEST so the async carry drains instead of fabricating
        // a market ACQUIRE. Runs immediately after gmxV2RefundClassifier so candidates are stamped.
        processed += timedPass("gmxWithdrawalSettlementLink",
                () -> gmxWithdrawalSettlementLinkService.linkOutstandingWithdrawalSettlements(batchSize));
        progressHeartbeat.run();

        // NEW-13: demote residual GMX execution-fee refunds (no matching open LP_EXIT_REQUEST) to a
        // basis-neutral SPONSORED_GAS_IN. MUST run strictly after gmxWithdrawalSettlementLink so
        // genuine GLV/GM settlements are already LP_EXIT_SETTLEMENT (and thus excluded here),
        // preserving the NEW-09 guardrail while stopping return-of-capital gas dust from booking a
        // phantom market ACQUIRE.
        processed += timedPass("gmxExecutionFeeRefundBasisNeutral",
                () -> gmxExecutionFeeRefundBasisNeutralService.reclassifyResidualRefunds(batchSize));
        progressHeartbeat.run();

        // R11 Fix 8: reclassify EtherFi weETH OFT cross-chain mint IN as BRIDGE_IN
        processed += timedPass("etherFiOftBridgeInClassifier",
                () -> etherFiOftBridgeInClassifier.reclassifyEtherFiOftInbounds(batchSize));
        progressHeartbeat.run();

        // R11 Fix 9: reclassify NFT mint OUT
        processed += timedPass("nftMintRetagger",
                () -> nftMintRetagger.reclassifyNftMints(batchSize));
        progressHeartbeat.run();

        // Cycle/14: legacy sealed bridge OUT legs (already correlated, still cont=false)
        processed += timedPass("bridgePairContinuityRepairLegacySealed",
                () -> bridgePairContinuityRepairService.reconcileLegacySealedPairs(batchSize));
        progressHeartbeat.run();

        // B-ZERO-5: LI.FI/Across IN legs missing flow counterparty metadata
        processed += timedPass("bridgePairContinuityRepairPairedInbound",
                () -> bridgePairContinuityRepairService.reconcilePairedInboundCounterparty(batchSize));
        progressHeartbeat.run();

        // B-BRIDGE-IN-ACQUIRE: BRIDGE_IN linked but left with continuityCandidate=false
        processed += timedPass("bridgePairContinuityRepairLegacySealedInbounds",
                () -> bridgePairContinuityRepairService.reconcileLegacySealedInbounds(batchSize));
        progressHeartbeat.run();

        // Cycle/14: same-tx on-chain INTERNAL_TRANSFER orphans across session wallets
        processed += timedPass("onChainInternalTransferPairRepair",
                () -> onChainInternalTransferPairRepairService.reconcileOrphanSameTxPairs(batchSize));
        progressHeartbeat.run();

        // B-VAULT-WITHDRAW: synthesize missing vault-token burn leg on Turtle Finance
        processed += timedPass("turtleVaultBurnRepair",
                () -> turtleVaultBurnRepairService.repairMissingVaultTokenBurn(batchSize));
        progressHeartbeat.run();

        // B-ETH-02: link LENDING_LOOP_OPEN → its DECREASE/CLOSE legs (shared lending-loop: corrId)
        // so replay parks the collateral basis on open and restores it on close instead of
        // re-pricing the returned collateral at market. Converges to zero like GMX.
        processed += timedPass("lendingLoopOpenClosePairLink",
                () -> lendingLoopOpenClosePairLinkService.reconcileOutstandingLoops(batchSize));
        progressHeartbeat.run();

        return processed;
    }

    // ── Terminal passes ───────────────────────────────────────────────────────
    //   These do full-collection scans and are only meaningful once the
    //   convergent passes have converged to 0.  Call exactly once per
    //   convergence cycle.

    int runTerminalPasses(int batchSize, Runnable progressHeartbeat) {
        int processed = 0;

        // Cycle/11 S1: reprice BRIDGE_OUT principals with no priced upstream inflow
        processed += timedPass("unmatchedBridgeInboundPricingFallback.unsupportedOutbounds",
                unmatchedBridgeInboundPricingFallbackService::reconcileUnsupportedOutbounds);
        progressHeartbeat.run();

        // Cycle/8 S3: demote orphan BRIDGE_IN to market-priced ACQUIRE
        processed += timedPass("unmatchedBridgeInboundPricingFallback.orphanInbounds",
                unmatchedBridgeInboundPricingFallbackService::reconcileOrphanInbounds);
        progressHeartbeat.run();

        // Cycle/15 R3: second Bybit pairing pass after cross-batch normalization (qty drift)
        processed += timedPass("bybitInternalTransferPairer.repairAll",
                bybitInternalTransferPairer::repairAll);
        progressHeartbeat.run();

        // Cycle/18 R9b: re-run corridor repair after repairAll so prices stay stripped
        processed += timedPass("bybitTransferContinuityRepair2",
                () -> bybitTransferContinuityRepairService.reconcileOutstandingPairs(batchSize));
        progressHeartbeat.run();

        // Fix A.2: suppress corridor-deposit-and-stake cycle collapse duplicates. Runs HERE (after the
        // corridor projection has stamped the BYBIT-CORRIDOR: deposits) rather than inside
        // collapseMirrors(), where the corridor half of the double-count signature does not yet exist.
        processed += timedPass("bybitCorridorStakeCycleSuppression",
                bybitStreamAuthorityCollapser::suppressCorridorDepositStakeCycles);
        progressHeartbeat.run();

        // B-EARN-DEPOSIT-MISSING: synthesise missing EARN counterpart
        processed += timedPass("bybitOnChainEarnOrphanRepair",
                bybitOnChainEarnOrphanRepairService::repairOrphans);
        progressHeartbeat.run();

        // B-EARN-CORRIDOR-DEDUP: retroactively re-link corridor-funded FUND drains that were
        // incorrectly synthesised on a prior run, eliminating the phantom double-credit and the
        // resulting umbrella shortfall.
        processed += timedPass("bybitEarnCorridorDuplicateRepair",
                bybitOnChainEarnOrphanRepairService::repairCorridorEarnDuplicates);
        progressHeartbeat.run();

        // Cycle/12: demote Bybit INTERNAL_TRANSFER singletons
        processed += timedPass("bybitInternalTransferOrphanFallback",
                bybitInternalTransferOrphanFallbackService::reconcileOrphanInternals);
        progressHeartbeat.run();

        // Cycle/15 R3: pair demoted bybit-econ-v1 EXTERNAL_TRANSFER orphans
        processed += timedPass("bybitInternalTransferPairer.pairDemotedEconOrphans",
                bybitInternalTransferPairer::pairDemotedEconOrphans);
        progressHeartbeat.run();

        // Cycle/9 S5: EXTERNAL_TRANSFER_IN orphans whose paired OUT is outside our universe
        processed += timedPass("unmatchedExternalTransferInPricingFallback",
                unmatchedExternalTransferInPricingFallbackService::reconcileOrphanInbounds);
        progressHeartbeat.run();

        return processed;
    }

    // ── Timing helper ─────────────────────────────────────────────────────────

    private int timedPass(String passName, IntSupplier pass) {
        long startNs = System.nanoTime();
        int result = pass.getAsInt();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        if (durationMs >= SLOW_PASS_THRESHOLD_MS) {
            log.info("linking pass [{}] processed={} durationMs={}", passName, result, durationMs);
        } else {
            log.debug("linking pass [{}] processed={} durationMs={}", passName, result, durationMs);
        }
        return result;
    }
}
