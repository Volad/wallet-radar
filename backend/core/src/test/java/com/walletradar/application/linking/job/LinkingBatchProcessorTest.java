package com.walletradar.application.linking.job;

import com.walletradar.application.linking.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.application.linking.pipeline.clarification.BridgePairContinuityRepairService;
import com.walletradar.application.linking.pipeline.clarification.BybitTransferContinuityRepairService;
import com.walletradar.application.linking.pipeline.clarification.CowSwapEthFlowSettlementLinkService;
import com.walletradar.application.linking.pipeline.clarification.CrossNetworkBridgePairFallbackService;
import com.walletradar.application.linking.pipeline.clarification.OnChainInternalTransferPairRepairService;
import com.walletradar.application.linking.pipeline.clarification.InternalTransferPairLinkService;
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
import com.walletradar.application.linking.pipeline.clarification.GmxExitSettlementLinkService;
import com.walletradar.application.linking.pipeline.clarification.GmxV2RefundClassifier;
import com.walletradar.application.linking.pipeline.clarification.KnownBridgeRouterExternalTypeCorrectionService;
import com.walletradar.application.linking.pipeline.clarification.NftMintRetagger;
import com.walletradar.application.linking.pipeline.clarification.AaveVariableDebtTokenTagger;
import com.walletradar.application.linking.pipeline.clarification.ProtocolAttributionClassifier;
import com.walletradar.application.linking.pipeline.clarification.EulerEvkDebtTokenTagger;
import com.walletradar.application.linking.pipeline.clarification.ScamDisperseClonePhishingTagger;
import com.walletradar.application.linking.pipeline.clarification.SpoofTokenDetector;
import com.walletradar.application.linking.pipeline.clarification.TurtleVaultBurnRepairService;
import com.walletradar.application.linking.pipeline.clarification.UnmatchedBridgeInboundPricingFallbackService;
import com.walletradar.application.linking.pipeline.clarification.UnmatchedExternalTransferInPricingFallbackService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LinkingBatchProcessorTest {

    @Test
    void heartbeatRunsAfterEveryLinkingSubstep() {
        BybitBridgeLinkService bybitBridgeLinkService = mock(BybitBridgeLinkService.class);
        OnChainLifecycleLinkService onChainLifecycleLinkService = mock(OnChainLifecycleLinkService.class);
        LiFiBridgePairLinkService liFiBridgePairLinkService = mock(LiFiBridgePairLinkService.class);
        LiFiForeignDestinationReclassificationService liFiForeignDestinationReclassificationService =
                mock(LiFiForeignDestinationReclassificationService.class);
        MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService = mock(MayanCctpBridgePairLinkService.class);
        AcrossBridgePairLinkService acrossBridgePairLinkService = mock(AcrossBridgePairLinkService.class);
        CowSwapEthFlowSettlementLinkService cowSwapEthFlowSettlementLinkService =
                mock(CowSwapEthFlowSettlementLinkService.class);
        InternalTransferPairLinkService internalTransferPairLinkService = mock(InternalTransferPairLinkService.class);
        BybitTransferContinuityRepairService bybitTransferContinuityRepairService = mock(BybitTransferContinuityRepairService.class);
        UnmatchedBridgeInboundPricingFallbackService unmatchedBridgeInboundPricingFallbackService =
                mock(UnmatchedBridgeInboundPricingFallbackService.class);
        BybitInternalTransferOrphanFallbackService bybitInternalTransferOrphanFallbackService =
                mock(BybitInternalTransferOrphanFallbackService.class);
        BybitOnChainEarnOrphanRepairService bybitOnChainEarnOrphanRepairService =
                mock(BybitOnChainEarnOrphanRepairService.class);
        UnmatchedExternalTransferInPricingFallbackService unmatchedExternalTransferInPricingFallbackService =
                mock(UnmatchedExternalTransferInPricingFallbackService.class);
        BridgePairContinuityRepairService bridgePairContinuityRepairService = mock(BridgePairContinuityRepairService.class);
        OnChainInternalTransferPairRepairService onChainInternalTransferPairRepairService =
                mock(OnChainInternalTransferPairRepairService.class);
        BybitInternalTransferPairer bybitInternalTransferPairer = mock(BybitInternalTransferPairer.class);
        BybitInternalTransferExternalCpReclassifier bybitInternalTransferExternalCpReclassifier =
                mock(BybitInternalTransferExternalCpReclassifier.class);
        BybitStreamAuthorityCollapser bybitStreamAuthorityCollapser = mock(BybitStreamAuthorityCollapser.class);
        KnownBridgeRouterExternalTypeCorrectionService knownBridgeRouterExternalTypeCorrectionService =
                mock(KnownBridgeRouterExternalTypeCorrectionService.class);
        OwnWalletBridgeMistypeCorrectionService ownWalletBridgeMistypeCorrectionService =
                mock(OwnWalletBridgeMistypeCorrectionService.class);
        MultiCounterpartyCorrectionService multiCounterpartyCorrectionService =
                mock(MultiCounterpartyCorrectionService.class);
        CrossNetworkBridgePairFallbackService crossNetworkBridgePairFallbackService =
                mock(CrossNetworkBridgePairFallbackService.class);
        ProtocolAttributionClassifier protocolAttributionClassifier = mock(ProtocolAttributionClassifier.class);
        AddressPoisoningDetector addressPoisoningDetector = mock(AddressPoisoningDetector.class);
        SpoofTokenDetector spoofTokenDetector = mock(SpoofTokenDetector.class);
        ScamDisperseClonePhishingTagger scamDisperseClonePhishingTagger = mock(ScamDisperseClonePhishingTagger.class);
        AaveVariableDebtTokenTagger aaveVariableDebtTokenTagger = mock(AaveVariableDebtTokenTagger.class);
        EulerEvkDebtTokenTagger eulerEvkDebtTokenTagger = mock(EulerEvkDebtTokenTagger.class);
        GmxExitSettlementLinkService gmxExitSettlementLinkService = mock(GmxExitSettlementLinkService.class);
        GmxEntryRequestLinkService gmxEntryRequestLinkService = mock(GmxEntryRequestLinkService.class);
        GmxV2RefundClassifier gmxV2RefundClassifier = mock(GmxV2RefundClassifier.class);
        EtherFiOftBridgeInClassifier etherFiOftBridgeInClassifier = mock(EtherFiOftBridgeInClassifier.class);
        NftMintRetagger nftMintRetagger = mock(NftMintRetagger.class);
        TurtleVaultBurnRepairService turtleVaultBurnRepairService = mock(TurtleVaultBurnRepairService.class);

        when(bybitBridgeLinkService.reconcileOutstandingPairs(25)).thenReturn(2);
        when(onChainLifecycleLinkService.processNextBatch(25)).thenReturn(3);
        when(liFiBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(5);
        when(liFiForeignDestinationReclassificationService.reclassifyForeignDestinations(25)).thenReturn(45);
        when(mayanCctpBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(7);
        when(acrossBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(11);
        when(cowSwapEthFlowSettlementLinkService.linkOutstandingSettlements(25)).thenReturn(12);
        when(internalTransferPairLinkService.reconcileOutstandingPairs(25)).thenReturn(13);
        when(knownBridgeRouterExternalTypeCorrectionService.reclassifyKnownRouterExternals(25)).thenReturn(24);
        when(ownWalletBridgeMistypeCorrectionService.reclassifyOwnWalletBridgeMistypes(25)).thenReturn(36);
        when(ownWalletBridgeMistypeCorrectionService.reclassifyMultiCpOwnWalletTransfers(25)).thenReturn(41);
        when(multiCounterpartyCorrectionService.deMultiExternalTransfers(25)).thenReturn(42);
        when(multiCounterpartyCorrectionService.retypeAggregatorSwapMistypes(25)).thenReturn(43);
        when(bybitTransferContinuityRepairService.reconcileOutstandingPairs(25)).thenReturn(17);
        when(bybitInternalTransferExternalCpReclassifier.reclassifySameUidExternalToInternal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(18);
        when(bybitStreamAuthorityCollapser.suppressCorridorDepositStakeCycles()).thenReturn(44);
        when(protocolAttributionClassifier.classifyProtocolAttribution(25)).thenReturn(32);
        when(crossNetworkBridgePairFallbackService.reconcileOrphanInbounds(25)).thenReturn(20);
        when(addressPoisoningDetector.detectAndExclude(25)).thenReturn(25);
        when(spoofTokenDetector.detectAndExclude(25)).thenReturn(35);
        when(scamDisperseClonePhishingTagger.tagPhishingOutbounds(25)).thenReturn(26);
        when(eulerEvkDebtTokenTagger.tagDebtTokenInflows(25)).thenReturn(46);
        when(aaveVariableDebtTokenTagger.tagDebtTokenFlows(25)).thenReturn(47);
        when(gmxExitSettlementLinkService.linkOutstandingSettlements(25)).thenReturn(0);
        when(gmxEntryRequestLinkService.linkOutstandingRequests(25)).thenReturn(0);
        when(gmxV2RefundClassifier.classifyGmxRefunds(25)).thenReturn(27);
        when(etherFiOftBridgeInClassifier.reclassifyEtherFiOftInbounds(25)).thenReturn(28);
        when(nftMintRetagger.reclassifyNftMints(25)).thenReturn(30);
        when(unmatchedBridgeInboundPricingFallbackService.reconcileUnsupportedOutbounds()).thenReturn(19);
        when(unmatchedBridgeInboundPricingFallbackService.reconcileOrphanInbounds()).thenReturn(21);
        when(bybitInternalTransferOrphanFallbackService.reconcileOrphanInternals()).thenReturn(22);
        when(bybitOnChainEarnOrphanRepairService.repairOrphans()).thenReturn(34);
        when(bybitOnChainEarnOrphanRepairService.repairCorridorEarnDuplicates()).thenReturn(38);
        when(unmatchedExternalTransferInPricingFallbackService.reconcileOrphanInbounds()).thenReturn(23);
        when(bridgePairContinuityRepairService.reconcileLegacySealedPairs(25)).thenReturn(29);
        when(onChainInternalTransferPairRepairService.reconcileOrphanSameTxPairs(25)).thenReturn(31);
        when(turtleVaultBurnRepairService.repairMissingVaultTokenBurn(25)).thenReturn(33);

        LinkingBatchProcessor processor = new LinkingBatchProcessor(
                bybitBridgeLinkService,
                onChainLifecycleLinkService,
                liFiBridgePairLinkService,
                liFiForeignDestinationReclassificationService,
                mayanCctpBridgePairLinkService,
                acrossBridgePairLinkService,
                cowSwapEthFlowSettlementLinkService,
                internalTransferPairLinkService,
                bybitTransferContinuityRepairService,
                unmatchedBridgeInboundPricingFallbackService,
                bybitInternalTransferOrphanFallbackService,
                bybitOnChainEarnOrphanRepairService,
                bybitInternalTransferPairer,
                unmatchedExternalTransferInPricingFallbackService,
                bridgePairContinuityRepairService,
                onChainInternalTransferPairRepairService,
                bybitInternalTransferExternalCpReclassifier,
                bybitStreamAuthorityCollapser,
                knownBridgeRouterExternalTypeCorrectionService,
                ownWalletBridgeMistypeCorrectionService,
                multiCounterpartyCorrectionService,
                crossNetworkBridgePairFallbackService,
                turtleVaultBurnRepairService,
                protocolAttributionClassifier,
                addressPoisoningDetector,
                spoofTokenDetector,
                scamDisperseClonePhishingTagger,
                aaveVariableDebtTokenTagger,
                eulerEvkDebtTokenTagger,
                gmxExitSettlementLinkService,
                gmxEntryRequestLinkService,
                gmxV2RefundClassifier,
                etherFiOftBridgeInClassifier,
                nftMintRetagger
        );

        AtomicInteger heartbeatCount = new AtomicInteger();
        int processed = processor.processNextBatch(25, heartbeatCount::incrementAndGet);

        assertThat(processed).isEqualTo(946);
        assertThat(heartbeatCount).hasValue(42);
    }
}
