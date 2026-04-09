package com.walletradar.ingestion.pipeline.clarification;

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
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
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
    @DisplayName("LI.FI source sweep seeds official receiving-tx evidence without requiring prior receipt clarification")
    void liFiSourceSweepSeedsOfficialReceivingTxEvidenceWithoutPriorReceiptClarification() {
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

        when(pendingLiFiBridgeSourceQueryService.loadNextBatch(500)).thenReturn(List.of(source));
        when(rawTransactionRepository.findById(source.getId())).thenReturn(Optional.of(sourceRaw));
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

        int changed = service.reconcileOutstandingSources(500);

        assertThat(changed).isEqualTo(1);
        verify(rawTransactionRepository).save(sourceRaw);
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
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
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
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
        when(liFiReceivingTransactionDiscoveryService.findOrDiscover(any()))
                .thenReturn(Optional.of(destination));

        service.link(sourceRaw, source);

        verify(liFiReceivingTransactionDiscoveryService).findOrDiscover(any());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
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
        when(liFiReceivingTransactionDiscoveryService.findOrDiscover(any())).thenReturn(Optional.empty());

        service.link(sourceRaw, source);

        verify(liFiReceivingTransactionDiscoveryService).findOrDiscover(any());
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getMatchedCounterparty()).isEqualTo(unknownDestination.getTxHash());
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

        verify(liFiReceivingTransactionDiscoveryService, never()).findOrDiscover(any());
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

        when(liFiStatusGateway.fetchBridgeStatus(source.getTxHash())).thenReturn(Optional.empty());
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
