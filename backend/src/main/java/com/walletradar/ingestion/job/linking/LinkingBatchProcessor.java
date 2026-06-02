package com.walletradar.ingestion.job.linking;

import com.walletradar.ingestion.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.BridgePairContinuityRepairService;
import com.walletradar.ingestion.pipeline.clarification.BybitTransferContinuityRepairService;
import com.walletradar.ingestion.pipeline.clarification.CowSwapEthFlowSettlementLinkService;
import com.walletradar.ingestion.pipeline.clarification.CrossNetworkBridgePairFallbackService;
import com.walletradar.ingestion.pipeline.clarification.InternalTransferPairLinkService;
import com.walletradar.ingestion.pipeline.clarification.OnChainInternalTransferPairRepairService;
import com.walletradar.ingestion.pipeline.clarification.LiFiBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.MayanCctpBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.OnChainLifecycleLinkService;
import com.walletradar.ingestion.pipeline.clarification.BybitInternalTransferOrphanFallbackService;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferExternalCpReclassifier;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferPairer;
import com.walletradar.ingestion.pipeline.clarification.AddressPoisoningDetector;
import com.walletradar.ingestion.pipeline.clarification.EtherFiOftBridgeInClassifier;
import com.walletradar.ingestion.pipeline.clarification.GmxV2RefundClassifier;
import com.walletradar.ingestion.pipeline.clarification.KnownBridgeRouterExternalTypeCorrectionService;
import com.walletradar.ingestion.pipeline.clarification.NftMintRetagger;
import com.walletradar.ingestion.pipeline.clarification.ProtocolAttributionClassifier;
import com.walletradar.ingestion.pipeline.clarification.ScamDisperseClonePhishingTagger;
import com.walletradar.ingestion.pipeline.clarification.TurtleVaultBurnRepairService;
import com.walletradar.ingestion.pipeline.clarification.UnmatchedBridgeInboundPricingFallbackService;
import com.walletradar.ingestion.pipeline.clarification.UnmatchedExternalTransferInPricingFallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Dedicated driver for deterministic cross-row and cross-source linking passes.
 */
@Component
@RequiredArgsConstructor
class LinkingBatchProcessor {

    private final BybitBridgeLinkService bybitBridgeLinkService;
    private final OnChainLifecycleLinkService onChainLifecycleLinkService;
    private final LiFiBridgePairLinkService liFiBridgePairLinkService;
    private final MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService;
    private final AcrossBridgePairLinkService acrossBridgePairLinkService;
    private final CowSwapEthFlowSettlementLinkService cowSwapEthFlowSettlementLinkService;
    private final InternalTransferPairLinkService internalTransferPairLinkService;
    private final BybitTransferContinuityRepairService bybitTransferContinuityRepairService;
    private final UnmatchedBridgeInboundPricingFallbackService unmatchedBridgeInboundPricingFallbackService;
    private final BybitInternalTransferOrphanFallbackService bybitInternalTransferOrphanFallbackService;
    private final BybitInternalTransferPairer bybitInternalTransferPairer;
    private final UnmatchedExternalTransferInPricingFallbackService unmatchedExternalTransferInPricingFallbackService;
    private final BridgePairContinuityRepairService bridgePairContinuityRepairService;
    private final OnChainInternalTransferPairRepairService onChainInternalTransferPairRepairService;
    private final BybitInternalTransferExternalCpReclassifier bybitInternalTransferExternalCpReclassifier;

    private final KnownBridgeRouterExternalTypeCorrectionService knownBridgeRouterExternalTypeCorrectionService;
    private final CrossNetworkBridgePairFallbackService crossNetworkBridgePairFallbackService;
    private final TurtleVaultBurnRepairService turtleVaultBurnRepairService;

    private final ProtocolAttributionClassifier protocolAttributionClassifier;
    private final AddressPoisoningDetector addressPoisoningDetector;
    private final ScamDisperseClonePhishingTagger scamDisperseClonePhishingTagger;
    private final GmxV2RefundClassifier gmxV2RefundClassifier;
    private final EtherFiOftBridgeInClassifier etherFiOftBridgeInClassifier;
    private final NftMintRetagger nftMintRetagger;

    int processNextBatch(int batchSize) {
        return processNextBatch(batchSize, () -> {
        });
    }

    int processNextBatch(int batchSize, Runnable progressHeartbeat) {
        int processed = 0;

        processed += bybitBridgeLinkService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        processed += onChainLifecycleLinkService.processNextBatch(batchSize);
        progressHeartbeat.run();

        processed += liFiBridgePairLinkService.reconcileOutstandingSources(batchSize);
        progressHeartbeat.run();

        processed += mayanCctpBridgePairLinkService.reconcileOutstandingSources(batchSize);
        progressHeartbeat.run();

        processed += acrossBridgePairLinkService.reconcileOutstandingSources(batchSize);
        progressHeartbeat.run();

        processed += cowSwapEthFlowSettlementLinkService.linkOutstandingSettlements(batchSize);
        progressHeartbeat.run();

        processed += internalTransferPairLinkService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        processed += knownBridgeRouterExternalTypeCorrectionService.reclassifyKnownRouterExternals(batchSize);
        progressHeartbeat.run();

        processed += bybitTransferContinuityRepairService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        processed += bybitInternalTransferExternalCpReclassifier.reclassifySameUidExternalToInternal(Instant.now());
        progressHeartbeat.run();

        // R12 Fix 11: stamp known protocol counterparties before cross-network pairing
        processed += protocolAttributionClassifier.classifyProtocolAttribution(batchSize);
        progressHeartbeat.run();

        processed += crossNetworkBridgePairFallbackService.reconcileOrphanInbounds(batchSize);
        progressHeartbeat.run();

        // R11 Fix 5: exclude address-poisoning dust IN (vanity-prefix match)
        processed += addressPoisoningDetector.detectAndExclude(batchSize);
        progressHeartbeat.run();

        // R11 Fix 6: tag phishing OUT via known scam disperse-clone contracts
        processed += scamDisperseClonePhishingTagger.tagPhishingOutbounds(batchSize);
        progressHeartbeat.run();

        // R11 Fix 7: stamp GMX V2 execution-fee refunds with protocol attribution
        processed += gmxV2RefundClassifier.classifyGmxRefunds(batchSize);
        progressHeartbeat.run();

        // R11 Fix 8: reclassify EtherFi weETH OFT cross-chain mint IN as BRIDGE_IN
        processed += etherFiOftBridgeInClassifier.reclassifyEtherFiOftInbounds(batchSize);
        progressHeartbeat.run();

        // R11 Fix 9: reclassify NFT mint OUT (ETH out, no token in, mint selector)
        processed += nftMintRetagger.reclassifyNftMints(batchSize);
        progressHeartbeat.run();

        // Cycle/11 S1: reprice BRIDGE_OUT principals with no priced upstream inflow on the
        // source wallet/network so continuity carry can propagate basis to the paired IN leg.
        processed += unmatchedBridgeInboundPricingFallbackService.reconcileUnsupportedOutbounds();
        progressHeartbeat.run();

        // Cycle/8 S3: terminal pass — any BRIDGE_IN whose OUT partner never materialized in our
        // session gets demoted to a market-priced ACQUIRE so basis does not stay at $0.
        processed += unmatchedBridgeInboundPricingFallbackService.reconcileOrphanInbounds();
        progressHeartbeat.run();

        // Cycle/15 R3: second pairing pass after cross-batch Bybit normalization (qty drift / minute bucket).
        bybitInternalTransferPairer.repairAll();

        // Cycle/18 R9b: internal pairer treats BYBIT-CORRIDOR as a Bybit-only singleton and can
        // overwrite FA-001 deposit anchors. Re-run corridor repair so prices stay stripped.
        processed += bybitTransferContinuityRepairService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        // Cycle/12: demote Bybit INTERNAL_TRANSFER singletons that survived bundle/round-trip pairing.
        processed += bybitInternalTransferOrphanFallbackService.reconcileOrphanInternals();
        progressHeartbeat.run();

        // Cycle/15 R3: pair demoted bybit-econ-v1 EXTERNAL_TRANSFER orphans (qty drift / minute bucket).
        processed += bybitInternalTransferPairer.pairDemotedEconOrphans();
        progressHeartbeat.run();

        // Cycle/9 S5: same idea but for EXTERNAL_TRANSFER_IN orphans whose paired
        // EXTERNAL_TRANSFER_OUT is not in the session (sender wallet outside our universe).
        processed += unmatchedExternalTransferInPricingFallbackService.reconcileOrphanInbounds();
        progressHeartbeat.run();

        // Cycle/14: legacy sealed bridge OUT legs (already correlated, still cont=false).
        processed += bridgePairContinuityRepairService.reconcileLegacySealedPairs(batchSize);
        progressHeartbeat.run();

        // B-ZERO-5: discovered LI.FI/Across IN legs can miss flow counterparty metadata.
        processed += bridgePairContinuityRepairService.reconcilePairedInboundCounterparty(batchSize);
        progressHeartbeat.run();

        // B-BRIDGE-IN-ACQUIRE: BRIDGE_IN rows linked (correlationId + matchedCounterparty set) but
        // left with continuityCandidate=false — causing ACQUIRE instead of CARRY_IN in replay.
        processed += bridgePairContinuityRepairService.reconcileLegacySealedInbounds(batchSize);
        progressHeartbeat.run();

        // Cycle/14: same-tx on-chain INTERNAL_TRANSFER orphans across session wallets.
        processed += onChainInternalTransferPairRepairService.reconcileOrphanSameTxPairs(batchSize);
        progressHeartbeat.run();

        // B-VAULT-WITHDRAW: synthesize missing vault-token burn leg on Turtle Finance USDC Vault
        // VAULT_WITHDRAW transactions where ERC4626 redeem() does not emit an ERC20 burn event.
        processed += turtleVaultBurnRepairService.repairMissingVaultTokenBurn(batchSize);
        progressHeartbeat.run();

        return processed;
    }
}
