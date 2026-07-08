package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiFiBridgePairLinkServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Mock
    private LiFiStatusGateway liFiStatusGateway;
    @Mock
    private PendingLiFiBridgeSourceQueryService pendingLiFiBridgeSourceQueryService;
    @Mock
    private LiFiReceivingTransactionDiscoveryService liFiReceivingTransactionDiscoveryService;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private ProtocolRegistryService protocolRegistryService;

    private LiFiBridgePairLinkService service;

    @BeforeEach
    void setUp() {
        service = new LiFiBridgePairLinkService(
                liFiStatusGateway,
                pendingLiFiBridgeSourceQueryService,
                liFiReceivingTransactionDiscoveryService,
                new RawTransactionClarificationEnricher(),
                rawTransactionRepository,
                normalizedTransactionRepository,
                mongoOperations,
                protocolRegistryService
        );
        lenient().when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("route-proven LI.FI source promotes audited destination into BRIDGE_IN and enables family-equivalent continuity carry")
    void routeProvenLiFiSourcePromotesAuditedDestinationIntoBridgeInAndEnablesFamilyEquivalentContinuityCarry() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
                NetworkId.KATANA
        );
        NormalizedTransaction source = bridgeOut(
                "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
                NetworkId.KATANA,
                "vbUSDC",
                "0x203a662b0bd271a6ed5a60edfbd04bfce608fd36",
                "-28.997378"
        );
        NormalizedTransaction destination = externalTransferIn(
                "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "28.920966"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getClarificationEvidence().get("protocolStatus", Document.class))
                .containsEntry("provider", "LIFI")
                .containsEntry("receivingTxHash", destination.getTxHash())
                .containsEntry("receivingNetworkId", NetworkId.ARBITRUM.name());

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());

        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(source.getContinuityCandidate()).isTrue();

        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getContinuityCandidate()).isTrue();
        assertThat(destination.getFlows())
                .extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("explicit LI.FI source link seeds official receiving-tx evidence without requiring prior receipt clarification")
    void explicitLiFiSourceLinkSeedsOfficialReceivingTxEvidenceWithoutPriorReceiptClarification() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
                NetworkId.KATANA
        );
        NormalizedTransaction source = bridgeOut(
                "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
                NetworkId.KATANA,
                "vbUSDC",
                "0x203a662b0bd271a6ed5a60edfbd04bfce608fd36",
                "-28.997378"
        );
        NormalizedTransaction destination = externalTransferIn(
                "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "28.920966"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        verify(rawTransactionRepository).save(sourceRaw);
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
    }

    @Test
    @DisplayName("persisted LI.FI receivingTxHash materializes destination even without route tag")
    void exactReceivingTxHashMaterializesDestinationBridgeIn() {
        RawTransaction sourceRaw = new RawTransaction();
        sourceRaw.setId("0xsource:ETHEREUM:" + WALLET);
        sourceRaw.setTxHash("0xsource");
        sourceRaw.setNetworkId(NetworkId.ETHEREUM.name());
        sourceRaw.setWalletAddress(WALLET);
        sourceRaw.setRawData(new Document("input", "0x"));
        sourceRaw.setClarificationEvidence(new Document("protocolStatus", new Document("provider", "LIFI")
                .append("sendingTxHash", "0xsource")
                .append("receivingTxHash", "0xdestination")
                .append("receivingNetworkId", NetworkId.AVALANCHE.name())
                .append("status", "DONE")
                .append("substatus", "COMPLETED")));
        NormalizedTransaction source = bridgeOut(
                "0xsource",
                NetworkId.ETHEREUM,
                "USDC",
                "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                "-100"
        );
        NormalizedTransaction destination = externalTransferIn(
                "0xdestination",
                NetworkId.AVALANCHE,
                "USDC",
                "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e",
                "99.9"
        );

        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.AVALANCHE,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        verify(liFiStatusGateway, never()).fetchBridgeStatus(any());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
    }

    @Test
    @DisplayName("anchored LI.FI source sweep retries receiving-tx discovery without refetching status")
    void anchoredLiFiSourceSweepRetriesReceivingTxDiscoveryWithoutRefetchingStatus() {
        RawTransaction sourceRaw = new RawTransaction();
        sourceRaw.setId("0xsource:ARBITRUM:" + WALLET);
        sourceRaw.setTxHash("0xsource");
        sourceRaw.setNetworkId(NetworkId.ARBITRUM.name());
        sourceRaw.setWalletAddress(WALLET);
        sourceRaw.setRawData(new Document("input", "0x"));
        sourceRaw.setClarificationEvidence(new Document("protocolStatus", new Document("provider", "LIFI")
                .append("sendingTxHash", "0xsource")
                .append("receivingTxHash", "0xdestination")
                .append("receivingNetworkId", NetworkId.OPTIMISM.name())
                .append("status", "DONE")
                .append("substatus", "COMPLETED")));
        NormalizedTransaction source = bridgeOut(
                "0xsource",
                NetworkId.ARBITRUM,
                "ETH",
                "0x0000000000000000000000000000000000000000",
                "-0.000221"
        );
        source.setCorrelationId("bridge:lifi:0xsource");
        source.setMatchedCounterparty("0xdestination");
        NormalizedTransaction destination = externalTransferIn(
                "0xdestination",
                NetworkId.OPTIMISM,
                "ETH",
                "0x0000000000000000000000000000000000000000",
                "0.000220"
        );

        when(pendingLiFiBridgeSourceQueryService.loadNextBatch(25)).thenReturn(List.of());
        when(pendingLiFiBridgeSourceQueryService.loadAnchoredWithoutInboundBatch(25)).thenReturn(List.of(source));
        when(rawTransactionRepository.findById(source.getId())).thenReturn(Optional.of(sourceRaw));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xdestination",
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(liFiReceivingTransactionDiscoveryService.findOrDiscover(any(), any())).thenReturn(Optional.of(destination));

        int changed = service.reconcileOutstandingSources(25);

        assertThat(changed).isEqualTo(1);
        verify(liFiStatusGateway, never()).fetchBridgeStatus(any());
        verify(liFiReceivingTransactionDiscoveryService).findOrDiscover(any(), any());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
    }

    @Test
    @DisplayName("LI.FI source sweep records bounded status miss when local matching cannot resolve")
    void liFiSourceSweepRecordsBoundedStatusMissWhenLocalMatchingCannotResolve() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
                NetworkId.KATANA
        );
        NormalizedTransaction source = bridgeOut(
                "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
                NetworkId.KATANA,
                "vbUSDC",
                "0x203a662b0bd271a6ed5a60edfbd04bfce608fd36",
                "-28.997378"
        );

        when(pendingLiFiBridgeSourceQueryService.loadNextBatch(500)).thenReturn(List.of(source));
        when(rawTransactionRepository.findById(source.getId())).thenReturn(Optional.of(sourceRaw));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());
        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash())).thenReturn(Optional.empty());

        int changed = service.reconcileOutstandingSources(500);

        assertThat(changed).isZero();
        verify(liFiStatusGateway).fetchBridgeStatus(source.getTxHash());
        verify(rawTransactionRepository).save(sourceRaw);
        assertThat(sourceRaw.getClarificationEvidence().get("protocolStatus", Document.class))
                .containsEntry("provider", "LIFI")
                .containsEntry("apiStatus", "UNAVAILABLE");
        verify(normalizedTransactionRepository, never()).save(any());
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("route-proven LI.FI source keeps only the dominant stable inbound leg as bridge transfer")
    void routeProvenLiFiSourceKeepsOnlyDominantStableInboundLegAsBridgeTransfer() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x8b471042fca30390a7d9b4a41463c01c2059b37df2d064cecc588a564e2ee032",
                NetworkId.MANTLE
        );
        NormalizedTransaction source = bridgeOut(
                "0x8b471042fca30390a7d9b4a41463c01c2059b37df2d064cecc588a564e2ee032",
                NetworkId.MANTLE,
                "USDe",
                "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34",
                "-862.746015934355611461"
        );
        source.setBlockTimestamp(Instant.parse("2025-09-29T12:14:08Z"));

        NormalizedTransaction destination = tx(
                "0x826189720417ce31b983c2c7bb79f04ba4e330df80a0c016dab2bbee2fd61269",
                NetworkId.ARBITRUM,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USD₮0", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "862.833378"),
                flow(NormalizedLegRole.BUY, "ETH", null, "0.013689")
        );
        destination.setBlockTimestamp(Instant.parse("2025-09-29T12:19:30Z"));
        destination.setTransactionIndex(2);

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        assertThat(source.getContinuityCandidate()).isFalse();
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getContinuityCandidate()).isFalse();
        assertThat(destination.getFlows())
                .extracting(NormalizedTransaction.Flow::getRole, NormalizedTransaction.Flow::getAssetSymbol)
                .containsExactly(
                        tuple(NormalizedLegRole.TRANSFER, "USD₮0"),
                        tuple(NormalizedLegRole.BUY, "ETH")
                );
    }

    @Test
    @DisplayName("later LI.FI supplemental source anchors to already-paired destination without overwriting principal pair")
    void laterLiFiSupplementalSourceAnchorsToAlreadyPairedDestinationWithoutOverwritingPrincipalPair() {
        RawTransaction principalSourceRaw = sourceRawTransaction(
                "0x8b471042fca30390a7d9b4a41463c01c2059b37df2d064cecc588a564e2ee032",
                NetworkId.MANTLE
        );
        NormalizedTransaction principalSource = bridgeOut(
                "0x8b471042fca30390a7d9b4a41463c01c2059b37df2d064cecc588a564e2ee032",
                NetworkId.MANTLE,
                "USDe",
                "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34",
                "-862.746015934355611461"
        );
        principalSource.setBlockTimestamp(Instant.parse("2025-09-29T12:14:08Z"));
        principalSource.setTransactionIndex(1);

        RawTransaction topUpSourceRaw = sourceRawTransaction(
                "0x585aefbf6646c0b978a6ea4e1dc1dd411e28dd394fef7100932a61d24cf53a3b",
                NetworkId.MANTLE
        );
        NormalizedTransaction topUpSource = bridgeOut(
                "0x585aefbf6646c0b978a6ea4e1dc1dd411e28dd394fef7100932a61d24cf53a3b",
                NetworkId.MANTLE,
                "WETH",
                "0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111",
                "-0.01371"
        );
        topUpSource.setFlows(new java.util.ArrayList<>(List.of(
                topUpSource.getFlows().getFirst(),
                flow(NormalizedLegRole.TRANSFER, "MNT", null, "-0.015747975859123965")
        )));
        topUpSource.setBlockTimestamp(Instant.parse("2025-09-29T12:15:52Z"));
        topUpSource.setTransactionIndex(2);

        NormalizedTransaction destination = tx(
                "0x826189720417ce31b983c2c7bb79f04ba4e330df80a0c016dab2bbee2fd61269",
                NetworkId.ARBITRUM,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "USD₮0", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "862.833378"),
                flow(NormalizedLegRole.BUY, "ETH", null, "0.013689")
        );
        destination.setBlockTimestamp(Instant.parse("2025-09-29T12:19:30Z"));
        destination.setTransactionIndex(2);

        when(liFiStatusGateway.fetchBridgeStatus(principalSource.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        principalSource.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));
        when(liFiStatusGateway.fetchBridgeStatus(topUpSource.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        topUpSource.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));
        lenient().when(rawTransactionRepository.findById(topUpSource.getId())).thenReturn(Optional.of(topUpSourceRaw));

        service.link(principalSourceRaw, principalSource);
        service.link(topUpSourceRaw, topUpSource);

        assertThat(destination.getMatchedCounterparty()).isEqualTo(principalSource.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo("bridge:lifi:" + principalSource.getTxHash());
        assertThat(destination.getFlows())
                .extracting(NormalizedTransaction.Flow::getRole, NormalizedTransaction.Flow::getAssetSymbol)
                .containsExactly(
                        tuple(NormalizedLegRole.TRANSFER, "USD₮0"),
                        tuple(NormalizedLegRole.TRANSFER, "ETH")
                );
        assertThat(principalSource.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(topUpSource.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(topUpSource.getCorrelationId()).isEqualTo("bridge:lifi:" + topUpSource.getTxHash());
        assertThat(topUpSource.getContinuityCandidate()).isTrue();
        NormalizedTransaction.Flow ethInbound = destination.getFlows().stream()
                .filter(flow -> "ETH".equals(flow.getAssetSymbol()))
                .findFirst()
                .orElseThrow();
        assertThat(ethInbound.getCounterpartyAddress())
                .isEqualTo("LINKED:" + topUpSource.getTxHash().toLowerCase());
    }

    @Test
    @DisplayName("destination arrival links previously seeded LI.FI source and enables continuity only for plain same-asset carry")
    void destinationArrivalLinksPreviouslySeededLiFiSourceAndEnablesContinuityOnlyForPlainSameAssetCarry() {
        RawTransaction destinationRaw = new RawTransaction();
        destinationRaw.setId("0xdest:ARBITRUM:" + WALLET);
        destinationRaw.setTxHash("0xdest");
        destinationRaw.setNetworkId(NetworkId.ARBITRUM.name());
        destinationRaw.setWalletAddress(WALLET);
        destinationRaw.setRawData(new Document("explorer", new Document("tokenTransfers", List.of())));

        NormalizedTransaction source = bridgeOut("0xsource", NetworkId.BASE, "USDC", null, "-100");
        source.setMatchedCounterparty("0xdest");
        source.setCorrelationId("bridge:lifi:0xsource");
        source.setContinuityCandidate(false);

        NormalizedTransaction destination = externalTransferIn("0xdest", NetworkId.ARBITRUM, "USDC", null, "100");
        when(normalizedTransactionRepository.findAllByMatchedCounterpartyAndSource(
                "0xdest",
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(source));

        service.link(destinationRaw, destination);

        verify(liFiStatusGateway, never()).fetchBridgeStatus(any());
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder("0xsource", "0xdest");

        assertThat(source.getContinuityCandidate()).isTrue();
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getContinuityCandidate()).isTrue();
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getFlows())
                .extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("source sweep promotes destination on another tracked wallet into BRIDGE_IN")
    void sourceSweepPromotesCrossWalletDestination() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0xd7832186ea268ec19e4ebf263e372438bd8d87dafda1e4dfcafb27eb68250309",
                NetworkId.MANTLE
        );
        NormalizedTransaction source = bridgeOut(
                "0xd7832186ea268ec19e4ebf263e372438bd8d87dafda1e4dfcafb27eb68250309",
                NetworkId.MANTLE,
                "USDC",
                "0x09bc4e0d864854c6afb6eb9a9cdf58ac190d0df9",
                "-2"
        );
        NormalizedTransaction destination = externalTransferInForWallet(
                "0xbdd28dacd0c62925efbb32bc388cec5972af270dd2ea77637ed1ff8390cba70c",
                NetworkId.OPTIMISM,
                "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f",
                "USDT0",
                "0x01bff41798a0bcf287b996046ca68b395dbc1071",
                "1.939183"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.OPTIMISM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        // Fix A (BR-1): cross-asset 1:1 BRIDGE_OUT must NOT carry matchedCounterparty — USDC→USDT0
        // is a cross-asset pair (cc=false, single-flow each). Stamping it would route CARRY_OUT through
        // bridgeSettlementKey() while the BRIDGE_IN drains only "bridge:" keys, orphaning the carry.
        assertThat(source.getMatchedCounterparty()).isNull();
        assertThat(source.getContinuityCandidate()).isFalse();
    }

    @Test
    @DisplayName("source sweep can discover and normalize missing receiving tx before pair materialization")
    void sourceSweepDiscoversMissingReceivingTx() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-10"
        );
        NormalizedTransaction destination = externalTransferInForWallet(
                "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                NetworkId.OPTIMISM,
                WALLET,
                "USDC",
                "0x0b2c639c533813f4aa9d7837caf62653d097ff85",
                "9.99"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.OPTIMISM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(liFiReceivingTransactionDiscoveryService.findOrDiscover(any(), any()))
                .thenReturn(Optional.of(destination));

        service.link(sourceRaw, source);

        verify(liFiReceivingTransactionDiscoveryService).findOrDiscover(any(), any());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
    }

    @Test
    @DisplayName("official LI.FI status seeds source anchor and allows later destination materialization")
    void officialStatusWithoutMaterializedDestinationSeedsSourceAnchorForLaterRetry() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-10"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                        NetworkId.OPTIMISM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByMatchedCounterpartyAndSource(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(normalizedTransactionRepository.findAllByMatchedCounterpartyAndSource(
                "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(source));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(liFiReceivingTransactionDiscoveryService.findOrDiscover(any(), any())).thenReturn(Optional.empty());

        service.link(sourceRaw, source);

        verify(rawTransactionRepository).save(sourceRaw);
        verify(normalizedTransactionRepository).save(source);
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getMatchedCounterparty()).isEqualTo("0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4");
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(source.getContinuityCandidate()).isNull();

        RawTransaction destinationRaw = sourceRawTransaction(
                "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                NetworkId.OPTIMISM
        );
        NormalizedTransaction destination = externalTransferInForWallet(
                "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                NetworkId.OPTIMISM,
                WALLET,
                "USDC",
                "0x0b2c639c533813f4aa9d7837caf62653d097ff85",
                "9.99"
        );
        when(rawTransactionRepository.findById(destination.getId())).thenReturn(Optional.of(destinationRaw));

        service.link(destinationRaw, destination);

        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
    }

    @Test
    @DisplayName("existing LI.FI UNKNOWN destination artifacts are ignored and not materialized into bridge pairs")
    void existingUnknownDestinationArtifactsAreIgnored() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x8b40041f0a7c916964105bba5f2f47edcfa60c35f7131713262f697f18290ae4",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x8b40041f0a7c916964105bba5f2f47edcfa60c35f7131713262f697f18290ae4",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-3.144620"
        );
        NormalizedTransaction unknownDestination = tx(
                "0x884437719bfde86c0e77bcbc73915703c8f0f5b0ce723b345229c8d5d4ef8c1c",
                NetworkId.BASE,
                NormalizedTransactionType.UNKNOWN
        );
        unknownDestination.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        unknownDestination.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        unknownDestination.getTxHash(),
                        NetworkId.BASE,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                unknownDestination.getTxHash(),
                NetworkId.BASE,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(unknownDestination));
        when(liFiReceivingTransactionDiscoveryService.findOrDiscover(any(), any())).thenReturn(Optional.empty());

        service.link(sourceRaw, source);

        verify(rawTransactionRepository).save(sourceRaw);
        verify(liFiReceivingTransactionDiscoveryService).findOrDiscover(any(), any());
        verify(normalizedTransactionRepository).save(source);
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getMatchedCounterparty()).isEqualTo(unknownDestination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(source.getContinuityCandidate()).isNull();
    }

    @Test
    @DisplayName("same-tx LI.FI status echo never self-links the source row")
    void sameTxLiFiStatusEchoDoesNotSelfLinkSource() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0xa0a3d70498e1425f7284e4881d3df46485c4f8b4ea2d1aa9c986f39ea6312a48",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0xa0a3d70498e1425f7284e4881d3df46485c4f8b4ea2d1aa9c986f39ea6312a48",
                NetworkId.ARBITRUM,
                "USDT0",
                "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9",
                "-298.9738"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        source.getTxHash(),
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));

        service.link(sourceRaw, source);

        verify(liFiReceivingTransactionDiscoveryService, never()).findOrDiscover(any(), any());
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getMatchedCounterparty()).isNull();
        assertThat(source.getContinuityCandidate()).isNull();
    }

    @Test
    @DisplayName("official-status miss falls back to unique Across settlement evidence for LI.FI-routed source")
    void officialStatusMissFallsBackToUniqueAcrossSettlementEvidence() {
        RawTransaction sourceRaw = metaMaskLiFiSourceRawTransaction(
                "0x1a756dd5b8d6144d250f3f2a86d25a718e4ac0e3c2044042c1d749ecacda95f6",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x1a756dd5b8d6144d250f3f2a86d25a718e4ac0e3c2044042c1d749ecacda95f6",
                NetworkId.ARBITRUM,
                "ETH",
                null,
                "-0.003"
        );
        source.setProtocolName("MetaMask Bridge");
        source.setBlockTimestamp(Instant.parse("2025-02-07T10:50:41Z"));

        RawTransaction destinationRaw = settlementRawTransaction(
                "0x4a47ab3cad76be52416e660e044b983acc9837cd9f05b59eabad7560636aa0b2",
                NetworkId.ETHEREUM,
                "0x5c7bcd6e7de5423a257d81b442095a1a6ced35c5",
                "2746559320438498"
        );
        NormalizedTransaction destination = externalTransferIn(
                "0x4a47ab3cad76be52416e660e044b983acc9837cd9f05b59eabad7560636aa0b2",
                NetworkId.ETHEREUM,
                "ETH",
                null,
                "0.002746559320438498"
        );
        destination.setBlockTimestamp(Instant.parse("2025-02-07T10:50:59Z"));

        lenient().when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash())).thenReturn(Optional.empty());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(destination));
        when(rawTransactionRepository.findById(destination.getId())).thenReturn(Optional.of(destinationRaw));
        when(protocolRegistryService.lookup(NetworkId.ETHEREUM, "0x5c7bcd6e7de5423a257d81b442095a1a6ced35c5"))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        "0x5c7bcd6e7de5423a257d81b442095a1a6ced35c5",
                        Set.of(NetworkId.ETHEREUM),
                        ProtocolRegistryFamily.BRIDGE,
                        ProtocolRegistryRole.BRIDGE_ENTRY,
                        ProtocolRegistryEventType.BRIDGE_OUT,
                        ConfidenceLevel.HIGH,
                        "Across",
                        "V2",
                        false,
                        null
                )));

        service.link(sourceRaw, source);

        verify(liFiStatusGateway, never()).fetchBridgeStatus(source.getTxHash());
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getProtocolName()).isEqualTo("Across");
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("official-status miss falls back to unique Relay payout settlement for LI.FI source with canonical brand")
    void officialStatusMissFallsBackToUniqueRelayPayoutSettlement() {
        RawTransaction sourceRaw = new RawTransaction();
        sourceRaw.setId("0x4bd7:BASE:" + WALLET);
        sourceRaw.setTxHash("0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6");
        sourceRaw.setNetworkId(NetworkId.BASE.name());
        sourceRaw.setWalletAddress(WALLET);
        sourceRaw.setRawData(new Document("input", "").append("to", "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae"));

        NormalizedTransaction source = bridgeOut(
                "0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6",
                NetworkId.BASE,
                "ETH",
                null,
                "-0.0116"
        );
        source.setProtocolName("LI.FI");
        source.setProtocolVersion("V1");
        source.setBlockTimestamp(Instant.parse("2025-09-20T16:53:55Z"));

        RawTransaction destinationRaw = relayPayoutRawTransaction(
                "0x2108883281ea4cd12eb27e4a540f9f008e149c1e8fe7a1348e80311c1f4d9ff8",
                NetworkId.LINEA,
                "0xf70da97812cb96acdf810712aa562db8dfa3dbef",
                "11589601648149877"
        );
        NormalizedTransaction destination = externalTransferIn(
                "0x2108883281ea4cd12eb27e4a540f9f008e149c1e8fe7a1348e80311c1f4d9ff8",
                NetworkId.LINEA,
                "ETH",
                null,
                "0.011589601648149877"
        );
        destination.setBlockTimestamp(Instant.parse("2025-09-20T16:56:18Z"));

        lenient().when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash())).thenReturn(Optional.empty());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(destination));
        when(rawTransactionRepository.findById(destination.getId())).thenReturn(Optional.of(destinationRaw));
        when(protocolRegistryService.lookup(NetworkId.LINEA, "0xf70da97812cb96acdf810712aa562db8dfa3dbef"))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        "0xf70da97812cb96acdf810712aa562db8dfa3dbef",
                        Set.of(NetworkId.BASE, NetworkId.LINEA),
                        ProtocolRegistryFamily.AGGREGATOR,
                        ProtocolRegistryRole.GAS_PAYER,
                        null,
                        ConfidenceLevel.HIGH,
                        "Relay",
                        "Solver",
                        false,
                        null
                )));

        service.link(sourceRaw, source);

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getProtocolName()).isEqualTo("Relay");
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("official-status miss falls back to unique same-user bridge destination even without recognized settlement entry")
    void officialStatusMissFallsBackToUniqueSameUserDestinationWithoutSettlementEntry() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0xb9ad84bba02b46c1b0bf2f01d1a05f98d4c886bae36c5487411f80892f3f894a",
                NetworkId.BASE
        );
        NormalizedTransaction source = bridgeOut(
                "0xb9ad84bba02b46c1b0bf2f01d1a05f98d4c886bae36c5487411f80892f3f894a",
                NetworkId.BASE,
                "ETH",
                null,
                "-0.009"
        );
        source.setBlockTimestamp(Instant.parse("2026-01-31T21:16:59Z"));

        RawTransaction destinationRaw = new RawTransaction();
        destinationRaw.setId("0x1e793f25878c9c50d407565938190b49bb74f2456526ca7ba80d8d74ea0a3b99:ARBITRUM:" + WALLET);
        destinationRaw.setTxHash("0x1e793f25878c9c50d407565938190b49bb74f2456526ca7ba80d8d74ea0a3b99");
        destinationRaw.setNetworkId(NetworkId.ARBITRUM.name());
        destinationRaw.setWalletAddress(WALLET);
        destinationRaw.setRawData(new Document("input", "").append("explorer", new Document("tokenTransfers", List.of())));

        NormalizedTransaction destination = externalTransferIn(
                "0x1e793f25878c9c50d407565938190b49bb74f2456526ca7ba80d8d74ea0a3b99",
                NetworkId.ARBITRUM,
                "ETH",
                null,
                "0.00899936668456007"
        );
        destination.setBlockTimestamp(Instant.parse("2026-01-31T21:17:00Z"));
        destination.setTransactionIndex(3);

        lenient().when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash())).thenReturn(Optional.empty());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(destination));
        when(rawTransactionRepository.findById(destination.getId())).thenReturn(Optional.of(destinationRaw));

        service.link(sourceRaw, source);

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("official-status miss falls back to same-user family-equivalent destination within ninety seconds")
    void officialStatusMissFallsBackToSameUserFamilyEquivalentDestinationWithinNinetySeconds() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x257c2642e382832ff37bcfad14c1a2845a55bb52fbb1131b91337f1bd956929e",
                NetworkId.OPTIMISM
        );
        NormalizedTransaction source = bridgeOut(
                "0x257c2642e382832ff37bcfad14c1a2845a55bb52fbb1131b91337f1bd956929e",
                NetworkId.OPTIMISM,
                "USDT0",
                "0x01bff41798a0bcf287b996046ca68b395dbc1071",
                "-1.384096"
        );
        source.setBlockTimestamp(Instant.parse("2025-06-06T06:25:39Z"));
        source.setTransactionIndex(19);

        RawTransaction destinationRaw = new RawTransaction();
        destinationRaw.setId("0x3d34d0f2ff9005294dd9fff30117fe47fa33c93f1e5820570c8c7ce9a66724ff:UNICHAIN:" + WALLET);
        destinationRaw.setTxHash("0x3d34d0f2ff9005294dd9fff30117fe47fa33c93f1e5820570c8c7ce9a66724ff");
        destinationRaw.setNetworkId(NetworkId.UNICHAIN.name());
        destinationRaw.setWalletAddress(WALLET);
        destinationRaw.setRawData(new Document("input", "deprecated")
                .append("explorer", new Document("tokenTransfers", List.of())));

        NormalizedTransaction destination = externalTransferIn(
                "0x3d34d0f2ff9005294dd9fff30117fe47fa33c93f1e5820570c8c7ce9a66724ff",
                NetworkId.UNICHAIN,
                "USD₮0",
                "0x9151434b16b9763660705744891fa906f660ecc5",
                "1.383958"
        );
        destination.setBlockTimestamp(Instant.parse("2025-06-06T06:26:52Z"));
        destination.setTransactionIndex(1);

        lenient().when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash())).thenReturn(Optional.empty());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(destination));
        when(rawTransactionRepository.findById(destination.getId())).thenReturn(Optional.of(destinationRaw));

        service.link(sourceRaw, source);

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + source.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getContinuityCandidate()).isTrue();
    }

    private RawTransaction sourceRawTransaction(String txHash, NetworkId networkId) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document("input",
                "0xd7a08473" + "72656c61796465706f7369746f7279" + "6a756d7065722e65786368616e6765"));
        return rawTransaction;
    }

    private RawTransaction metaMaskLiFiSourceRawTransaction(String txHash, NetworkId networkId) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document("input",
                "0x3ce33bff" + "6c696669416461707465725632"));
        return rawTransaction;
    }

    private RawTransaction settlementRawTransaction(
            String txHash,
            NetworkId networkId,
            String settlementSender,
            String value
    ) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document("input", "")
                .append("explorer", new Document("tokenTransfers", List.of())
                        .append("internalTransfers", List.of(
                                new Document("from", settlementSender)
                                        .append("to", WALLET)
                                        .append("value", value)
                                        .append("isError", "0")
                        ))));
        return rawTransaction;
    }

    private RawTransaction relayPayoutRawTransaction(
            String txHash,
            NetworkId networkId,
            String settlementSender,
            String value
    ) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document("from", settlementSender)
                .append("to", WALLET)
                .append("input", "0xd8461cfc")
                .append("methodId", "0xd8461cfc")
                .append("functionName", "")
                .append("value", value)
                .append("explorer", new Document("tokenTransfers", List.of())
                        .append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private NormalizedTransaction bridgeOut(
            String txHash,
            NetworkId networkId,
            String symbol,
            String assetContract,
            String quantity
    ) {
        return tx(txHash, networkId, NormalizedTransactionType.BRIDGE_OUT, flow(NormalizedLegRole.TRANSFER, symbol, assetContract, quantity));
    }

    private NormalizedTransaction externalTransferIn(
            String txHash,
            NetworkId networkId,
            String symbol,
            String assetContract,
            String quantity
    ) {
        return tx(txHash, networkId, NormalizedTransactionType.EXTERNAL_TRANSFER_IN, flow(NormalizedLegRole.BUY, symbol, assetContract, quantity));
    }

    private NormalizedTransaction externalTransferInForWallet(
            String txHash,
            NetworkId networkId,
            String walletAddress,
            String symbol,
            String assetContract,
            String quantity
    ) {
        NormalizedTransaction transaction = externalTransferIn(txHash, networkId, symbol, assetContract, quantity);
        transaction.setId(txHash + ":" + networkId + ":" + walletAddress);
        transaction.setWalletAddress(walletAddress);
        return transaction;
    }

    private NormalizedTransaction tx(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":" + networkId + ":" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(networkId);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        transaction.setConfidence(ConfidenceLevel.LOW);
        transaction.setBlockTimestamp(Instant.parse("2026-03-31T10:00:00Z"));
        transaction.setTransactionIndex(1);
        transaction.setFlows(List.of(flows));
        transaction.setMissingDataReasons(List.of());
        return transaction;
    }

    @Test
    @DisplayName("multi-flow LI.FI BRIDGE_OUT does not retag secondary positive-qty flow as CARRY_IN on paired OUT leg")
    void multiFlowLiFiBridgeOutDoesNotProduceCarryInOnSecondaryPositiveFlow() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = tx(
                "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
                NetworkId.ARBITRUM,
                NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "-10"),
                flow(NormalizedLegRole.BUY, "ETH", null, "0.000025")
        );
        NormalizedTransaction destination = externalTransferInForWallet(
                "0x75d595a7a2e59b2c4f7f70f5114c56d7b271dbd639c09fdc9e7d078fd9b162e4",
                NetworkId.OPTIMISM,
                WALLET,
                "USDC",
                "0x0b2c639c533813f4aa9d7837caf62653d097ff85",
                "9.99"
        );

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.OPTIMISM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        assertThat(source.getContinuityCandidate()).isTrue();
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getFlows())
                .extracting(NormalizedTransaction.Flow::getRole, NormalizedTransaction.Flow::getAssetSymbol)
                .containsExactly(
                        tuple(NormalizedLegRole.TRANSFER, "USDC"),
                        tuple(NormalizedLegRole.BUY, "ETH")
                );
    }

    @Test
    @DisplayName("cross-asset LI.FI swap: BRIDGE_OUT source must NOT receive matchedCounterparty when continuityCandidate=false")
    void crossAssetLiFiSwapBridgeOutSourceMustNotReceiveMatchedCounterparty() {
        // ETH (ARBITRUM) → WBTC (OPTIMISM): different asset families → cc=false.
        // A stale matchedCounterparty on the BRIDGE_OUT would route its CARRY_OUT through
        // bridgeSettlementKey() ("bridge-settlement:") while the BRIDGE_IN drains only "bridge:"
        // keys — they never match, leaving the CARRY_OUT orphaned (~$968 guard breach).
        String sourceTxHash = "0xaaaaabbbbbcccccdddddeeeeefffffaaaaa00001111122222333334444455555666";
        String destinationTxHash = "0x7777788888999990000011111222223333344444555556666677777888889999900";

        RawTransaction sourceRaw = sourceRawTransaction(sourceTxHash, NetworkId.ARBITRUM);
        NormalizedTransaction source = bridgeOut(sourceTxHash, NetworkId.ARBITRUM, "ETH", null, "-1.0");
        NormalizedTransaction destination = externalTransferIn(
                destinationTxHash, NetworkId.OPTIMISM, "WBTC", "0x68f180fcce6836688e9084f035309e29bf0a2095", "0.016"
        );

        when(liFiStatusGateway.fetchBridgeStatus(sourceTxHash))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        sourceTxHash,
                        destinationTxHash,
                        NetworkId.OPTIMISM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destinationTxHash,
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        // BRIDGE_OUT must have null matchedCounterparty so CARRY_OUT uses bridgeTransferKey().
        assertThat(source.getMatchedCounterparty()).isNull();
        assertThat(source.getContinuityCandidate()).isFalse();
        assertThat(source.getCorrelationId()).isEqualTo("bridge:lifi:" + sourceTxHash);

        // BRIDGE_IN may retain counterparty for UI display — unaffected by the guard.
        assertThat(destination.getMatchedCounterparty()).isEqualTo(sourceTxHash);
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
    }

    // ──────────────────── T-03: multi-flow BRIDGE_IN role alignment ────────────────────────────

    @Test
    @DisplayName("multi-flow BRIDGE_IN (USD₮0 TRANSFER + ETH TRANSFER) linked pair: USD₮0 stays TRANSFER, ETH demoted to BUY")
    void multiFlowBridgeInLinkedPairAlignsRolesForBridgeSettlement() {
        // Scenario: USDe BRIDGE_OUT on Arbitrum, destination BRIDGE_IN receives USD₮0 + ETH refund
        String sourceTxHash = "0x826189abc000111122223333444455556666777788889999aaaabbbbccccddddeee";
        String destinationTxHash = "0x826189def000111122223333444455556666777788889999aaaabbbbccccddddfff";

        RawTransaction sourceRaw = sourceRawTransaction(sourceTxHash, NetworkId.ARBITRUM);
        NormalizedTransaction source = tx(
                sourceTxHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "USDe", "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "-862.75"),
                flow(NormalizedLegRole.FEE, "ETH", null, "-0.001")
        );

        // Destination BRIDGE_IN has two TRANSFER flows: primary USD₮0 + ETH gas refund
        NormalizedTransaction destination = new NormalizedTransaction();
        destination.setId(destinationTxHash + ":ARBITRUM:" + WALLET);
        destination.setTxHash(destinationTxHash);
        destination.setNetworkId(NetworkId.ARBITRUM);
        destination.setWalletAddress(WALLET);
        destination.setSource(NormalizedTransactionSource.ON_CHAIN);
        destination.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        destination.setFlows(new java.util.ArrayList<>(List.of(
                flow(NormalizedLegRole.TRANSFER, "USD\u20ae0", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "862.30"),
                flow(NormalizedLegRole.TRANSFER, "ETH", null, "0.013")
        )));

        when(liFiStatusGateway.fetchBridgeStatus(sourceTxHash))
                .thenReturn(Optional.of(new LiFiBridgeStatus(
                        sourceTxHash,
                        destinationTxHash,
                        NetworkId.ARBITRUM,
                        "DONE",
                        "COMPLETED"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destinationTxHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        // After alignment: USD₮0 remains TRANSFER (primary bridged asset), ETH demoted to BUY
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getFlows())
                .extracting(NormalizedTransaction.Flow::getRole, NormalizedTransaction.Flow::getAssetSymbol)
                .containsExactlyInAnyOrder(
                        tuple(NormalizedLegRole.TRANSFER, "USD\u20ae0"),
                        tuple(NormalizedLegRole.BUY, "ETH")
                );
        // Exactly 1 TRANSFER flow → hasSinglePrincipalTransferFlow=true → bridgeSettlementKey non-null
        long transferFlowCount = destination.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER && f.getQuantityDelta().signum() > 0)
                .count();
        assertThat(transferFlowCount).isEqualTo(1);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(sourceTxHash);
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String assetContract,
            String quantity
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(assetContract);
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }
}
