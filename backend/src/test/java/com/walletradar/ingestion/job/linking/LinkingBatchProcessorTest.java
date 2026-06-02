package com.walletradar.ingestion.job.linking;

import com.walletradar.ingestion.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.BridgePairContinuityRepairService;
import com.walletradar.ingestion.pipeline.clarification.BybitTransferContinuityRepairService;
import com.walletradar.ingestion.pipeline.clarification.CowSwapEthFlowSettlementLinkService;
import com.walletradar.ingestion.pipeline.clarification.CrossNetworkBridgePairFallbackService;
import com.walletradar.ingestion.pipeline.clarification.OnChainInternalTransferPairRepairService;
import com.walletradar.ingestion.pipeline.clarification.InternalTransferPairLinkService;
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
import com.walletradar.ingestion.pipeline.clarification.UnmatchedBridgeInboundPricingFallbackService;
import com.walletradar.ingestion.pipeline.clarification.UnmatchedExternalTransferInPricingFallbackService;
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
        UnmatchedExternalTransferInPricingFallbackService unmatchedExternalTransferInPricingFallbackService =
                mock(UnmatchedExternalTransferInPricingFallbackService.class);
        BridgePairContinuityRepairService bridgePairContinuityRepairService = mock(BridgePairContinuityRepairService.class);
        OnChainInternalTransferPairRepairService onChainInternalTransferPairRepairService =
                mock(OnChainInternalTransferPairRepairService.class);
        BybitInternalTransferPairer bybitInternalTransferPairer = mock(BybitInternalTransferPairer.class);
        BybitInternalTransferExternalCpReclassifier bybitInternalTransferExternalCpReclassifier =
                mock(BybitInternalTransferExternalCpReclassifier.class);
        KnownBridgeRouterExternalTypeCorrectionService knownBridgeRouterExternalTypeCorrectionService =
                mock(KnownBridgeRouterExternalTypeCorrectionService.class);
        CrossNetworkBridgePairFallbackService crossNetworkBridgePairFallbackService =
                mock(CrossNetworkBridgePairFallbackService.class);
        ProtocolAttributionClassifier protocolAttributionClassifier = mock(ProtocolAttributionClassifier.class);
        AddressPoisoningDetector addressPoisoningDetector = mock(AddressPoisoningDetector.class);
        ScamDisperseClonePhishingTagger scamDisperseClonePhishingTagger = mock(ScamDisperseClonePhishingTagger.class);
        GmxV2RefundClassifier gmxV2RefundClassifier = mock(GmxV2RefundClassifier.class);
        EtherFiOftBridgeInClassifier etherFiOftBridgeInClassifier = mock(EtherFiOftBridgeInClassifier.class);
        NftMintRetagger nftMintRetagger = mock(NftMintRetagger.class);

        when(bybitBridgeLinkService.reconcileOutstandingPairs(25)).thenReturn(2);
        when(onChainLifecycleLinkService.processNextBatch(25)).thenReturn(3);
        when(liFiBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(5);
        when(mayanCctpBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(7);
        when(acrossBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(11);
        when(cowSwapEthFlowSettlementLinkService.linkOutstandingSettlements(25)).thenReturn(12);
        when(internalTransferPairLinkService.reconcileOutstandingPairs(25)).thenReturn(13);
        when(knownBridgeRouterExternalTypeCorrectionService.reclassifyKnownRouterExternals(25)).thenReturn(24);
        when(bybitTransferContinuityRepairService.reconcileOutstandingPairs(25)).thenReturn(17);
        when(bybitInternalTransferExternalCpReclassifier.reclassifySameUidExternalToInternal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(18);
        when(protocolAttributionClassifier.classifyProtocolAttribution(25)).thenReturn(32);
        when(crossNetworkBridgePairFallbackService.reconcileOrphanInbounds(25)).thenReturn(20);
        when(addressPoisoningDetector.detectAndExclude(25)).thenReturn(25);
        when(scamDisperseClonePhishingTagger.tagPhishingOutbounds(25)).thenReturn(26);
        when(gmxV2RefundClassifier.classifyGmxRefunds(25)).thenReturn(27);
        when(etherFiOftBridgeInClassifier.reclassifyEtherFiOftInbounds(25)).thenReturn(28);
        when(nftMintRetagger.reclassifyNftMints(25)).thenReturn(30);
        when(unmatchedBridgeInboundPricingFallbackService.reconcileUnsupportedOutbounds()).thenReturn(19);
        when(unmatchedBridgeInboundPricingFallbackService.reconcileOrphanInbounds()).thenReturn(21);
        when(bybitInternalTransferOrphanFallbackService.reconcileOrphanInternals()).thenReturn(22);
        when(unmatchedExternalTransferInPricingFallbackService.reconcileOrphanInbounds()).thenReturn(23);
        when(bridgePairContinuityRepairService.reconcileLegacySealedPairs(25)).thenReturn(29);
        when(onChainInternalTransferPairRepairService.reconcileOrphanSameTxPairs(25)).thenReturn(31);

        LinkingBatchProcessor processor = new LinkingBatchProcessor(
                bybitBridgeLinkService,
                onChainLifecycleLinkService,
                liFiBridgePairLinkService,
                mayanCctpBridgePairLinkService,
                acrossBridgePairLinkService,
                cowSwapEthFlowSettlementLinkService,
                internalTransferPairLinkService,
                bybitTransferContinuityRepairService,
                unmatchedBridgeInboundPricingFallbackService,
                bybitInternalTransferOrphanFallbackService,
                bybitInternalTransferPairer,
                unmatchedExternalTransferInPricingFallbackService,
                bridgePairContinuityRepairService,
                onChainInternalTransferPairRepairService,
                bybitInternalTransferExternalCpReclassifier,
                knownBridgeRouterExternalTypeCorrectionService,
                crossNetworkBridgePairFallbackService,
                protocolAttributionClassifier,
                addressPoisoningDetector,
                scamDisperseClonePhishingTagger,
                gmxV2RefundClassifier,
                etherFiOftBridgeInClassifier,
                nftMintRetagger
        );

        AtomicInteger heartbeatCount = new AtomicInteger();
        int processed = processor.processNextBatch(25, heartbeatCount::incrementAndGet);

        assertThat(processed).isEqualTo(462);
        assertThat(heartbeatCount).hasValue(26);
    }
}
