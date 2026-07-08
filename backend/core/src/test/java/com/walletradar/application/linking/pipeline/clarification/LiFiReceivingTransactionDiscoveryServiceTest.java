package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassifier;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.application.normalization.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiFiReceivingTransactionDiscoveryServiceTest {

    private static final String SOURCE_WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String DESTINATION_WALLET = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";

    @Mock
    private ReceiptClarificationGateway receiptClarificationGateway;
    @Mock
    private TrackedWalletRepository trackedWalletRepository;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private ProtocolRegistryService protocolRegistryService;
    @Mock
    private OnChainClassifier onChainClassifier;
    @Mock
    private OnChainNormalizedTransactionBuilder normalizedTransactionBuilder;
    @Mock
    private IdempotentNormalizedTransactionStore normalizedTransactionStore;
    @Mock
    private ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;

    private LiFiReceivingTransactionDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new LiFiReceivingTransactionDiscoveryService(
                receiptClarificationGateway,
                trackedWalletRepository,
                rawTransactionRepository,
                normalizedTransactionRepository,
                protocolRegistryService,
                onChainClassifier,
                normalizedTransactionBuilder,
                normalizedTransactionStore,
                explorerRawOrderingRepairGateway
        );
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("receiving tx discovery fetches destination on another tracked wallet and normalizes it immediately")
    void receivingTxDiscoveryFetchesDestinationOnAnotherTrackedWallet() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0xd7832186ea268ec19e4ebf263e372438bd8d87dafda1e4dfcafb27eb68250309",
                "0xbdd28dacd0c62925efbb32bc388cec5972af270dd2ea77637ed1ff8390cba70c",
                NetworkId.OPTIMISM,
                "DONE",
                "COMPLETED"
        );
        TrackedWallet sourceWallet = trackedWallet(SOURCE_WALLET);
        TrackedWallet destinationWallet = trackedWallet(DESTINATION_WALLET);
        RawTransaction rawTransaction = destinationRaw(status.receivingTxHash(), DESTINATION_WALLET);
        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                List.of(flow(NormalizedLegRole.BUY, "USDT0", "0x01bff41798a0bcf287b996046ca68b395dbc1071", "1.939183")),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );
        NormalizedTransaction normalized = normalizedDestination(status.receivingTxHash(), NetworkId.OPTIMISM, DESTINATION_WALLET);

        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                status.receivingTxHash(),
                NetworkId.OPTIMISM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(trackedWalletRepository.findAllByOrderByAddressAsc()).thenReturn(List.of(sourceWallet, destinationWallet));
        when(rawTransactionRepository.findById(status.receivingTxHash() + ":OPTIMISM:" + SOURCE_WALLET)).thenReturn(Optional.empty());
        when(rawTransactionRepository.findById(status.receivingTxHash() + ":OPTIMISM:" + DESTINATION_WALLET)).thenReturn(Optional.empty());
        when(receiptClarificationGateway.fetchRawTransactionByHash(
                status.receivingTxHash(),
                NetworkId.OPTIMISM,
                SOURCE_WALLET,
                null
        )).thenReturn(Optional.empty());
        when(receiptClarificationGateway.fetchRawTransactionByHash(
                status.receivingTxHash(),
                NetworkId.OPTIMISM,
                DESTINATION_WALLET,
                null
        )).thenReturn(Optional.of(rawTransaction));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classificationResult);
        when(normalizedTransactionBuilder.build(eq(rawTransaction), eq(classificationResult), any(Instant.class))).thenReturn(normalized);
        when(normalizedTransactionStore.upsert(normalized)).thenReturn(normalized);

        Optional<NormalizedTransaction> discovered = service.findOrDiscover(status);

        assertThat(discovered).contains(normalized);
        verify(receiptClarificationGateway).fetchRawTransactionByHash(
                status.receivingTxHash(),
                NetworkId.OPTIMISM,
                DESTINATION_WALLET,
                null
        );
        verify(rawTransactionRepository).save(rawTransaction);
        assertThat(rawTransaction.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
    }

    @Test
    @DisplayName("LayerZero execute302 calldata settlement is discovered for source wallet via LIFI_CALLDATA")
    void layerZeroCalldataSettlementIsDiscoveredForSourceWallet() {
        String sourceTx = "0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7";
        String destinationTx = "0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa";
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                sourceTx,
                destinationTx,
                NetworkId.BASE,
                "DONE",
                "COMPLETED"
        );
        TrackedWallet sourceWallet = trackedWallet(SOURCE_WALLET);
        RawTransaction destinationRaw = layerZeroExecute302Raw(destinationTx, SOURCE_WALLET);
        NormalizedTransaction sourceOutbound = sourceBridgeOut(sourceTx);
        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.BRIDGE_IN,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(flow(NormalizedLegRole.TRANSFER, "ETH", null, "0.080966355549794125")),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );
        NormalizedTransaction normalized = normalizedDestination(destinationTx, NetworkId.BASE, SOURCE_WALLET);
        normalized.setType(NormalizedTransactionType.BRIDGE_IN);

        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destinationTx,
                NetworkId.BASE,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(trackedWalletRepository.findAllByOrderByAddressAsc()).thenReturn(List.of(sourceWallet));
        when(rawTransactionRepository.findById(destinationTx + ":BASE:" + SOURCE_WALLET)).thenReturn(Optional.empty());
        when(receiptClarificationGateway.fetchRawTransactionByHash(
                destinationTx,
                NetworkId.BASE,
                SOURCE_WALLET,
                null
        )).thenReturn(Optional.of(destinationRaw));
        when(onChainClassifier.classify(destinationRaw)).thenReturn(classificationResult);
        when(normalizedTransactionBuilder.build(eq(destinationRaw), eq(classificationResult), any(Instant.class)))
                .thenReturn(normalized);
        when(normalizedTransactionStore.upsert(normalized)).thenReturn(normalized);

        Optional<NormalizedTransaction> discovered = service.findOrDiscover(status, sourceOutbound);

        assertThat(discovered).contains(normalized);
        @SuppressWarnings("unchecked")
        List<Document> internalTransfers = destinationRaw.getRawData()
                .get("explorer", Document.class)
                .get("internalTransfers", List.class);
        assertThat(internalTransfers).anySatisfy(transfer ->
                assertThat(transfer.getString("discoverySource"))
                        .isEqualTo(LiFiDestinationDiscoverySupport.LIFI_CORROBORATED_SETTLEMENT));
        assertThat(destinationRaw.getClarificationEvidence().getString("lifiDestinationDiscoveryPath"))
                .isEqualTo(LiFiDestinationDiscoveryPath.LIFI_CALLDATA.name());
    }

    @Test
    @DisplayName("same-tx LI.FI status echo is ignored before any receiving-tx discovery")
    void sameTxStatusEchoIsIgnored() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0xa0a3d70498e1425f7284e4881d3df46485c4f8b4ea2d1aa9c986f39ea6312a48",
                "0xa0a3d70498e1425f7284e4881d3df46485c4f8b4ea2d1aa9c986f39ea6312a48",
                NetworkId.ARBITRUM,
                "DONE",
                "COMPLETED"
        );

        Optional<NormalizedTransaction> discovered = service.findOrDiscover(status);

        assertThat(discovered).isEmpty();
        verify(receiptClarificationGateway, never()).fetchRawTransactionByHash(any(), any(), any(), any());
    }

    @Test
    @DisplayName("receiving tx discovery rejects targeted fetches that do not actually touch the tracked wallet")
    void receivingTxDiscoveryRejectsIrrelevantTargetedFetch() {
        LiFiBridgeStatus status = new LiFiBridgeStatus(
                "0x8b40041f0a7c916964105bba5f2f47edcfa60c35f7131713262f697f18290ae4",
                "0x884437719bfde86c0e77bcbc73915703c8f0f5b0ce723b345229c8d5d4ef8c1c",
                NetworkId.BASE,
                "DONE",
                "COMPLETED"
        );
        TrackedWallet sourceWallet = trackedWallet(SOURCE_WALLET);
        RawTransaction rawTransaction = destinationRaw(status.receivingTxHash(), SOURCE_WALLET);
        rawTransaction.getRawData().put("from", "0x699ee12a1d97437a4a1e87c71e5d882b3881e2e3");
        rawTransaction.getRawData().put("to", "0x09aea4b2242abc8bb4bb78d537a67a245a7bec64");
        rawTransaction.getRawData().put("explorer", new Document()
                .append("tokenTransfers", List.of(new Document()
                        .append("from", "0x699ee12a1d97437a4a1e87c71e5d882b3881e2e3")
                        .append("to", "0xcd74f91e4d2a49903462d58d6951136a527a5dea")
                        .append("tokenSymbol", "USDC")
                        .append("tokenAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                        .append("value", "3135637")
                        .append("tokenDecimal", "6")))
                .append("internalTransfers", List.of(new Document()
                        .append("from", "0x09aea4b2242abc8bb4bb78d537a67a245a7bec64")
                        .append("to", "0x6c99671b249af73b2847d92123d823cb3875e399")
                        .append("value", "0"))));

        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                status.receivingTxHash(),
                NetworkId.BASE,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(trackedWalletRepository.findAllByOrderByAddressAsc()).thenReturn(List.of(sourceWallet));
        when(rawTransactionRepository.findById(status.receivingTxHash() + ":BASE:" + SOURCE_WALLET)).thenReturn(Optional.empty());
        when(receiptClarificationGateway.fetchRawTransactionByHash(
                status.receivingTxHash(),
                NetworkId.BASE,
                SOURCE_WALLET,
                null
        )).thenReturn(Optional.of(rawTransaction));

        Optional<NormalizedTransaction> discovered = service.findOrDiscover(status);

        assertThat(discovered).isEmpty();
        verify(normalizedTransactionStore, never()).upsert(any());
        // Freshly-fetched tx is persisted to avoid repeated RPC calls on subsequent linking passes,
        // even when the wallet-relevance check fails.
        verify(rawTransactionRepository, atMostOnce()).save(any());
    }

    private NormalizedTransaction sourceBridgeOut(String sourceTx) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setTxHash(sourceTx);
        transaction.setWalletAddress(SOURCE_WALLET);
        transaction.setType(NormalizedTransactionType.BRIDGE_OUT);
        transaction.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "ETH", null, "-0.080966355549794125")
        ));
        return transaction;
    }

    private RawTransaction layerZeroExecute302Raw(String destinationTx, String walletAddress) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(destinationTx + ":BASE:" + walletAddress);
        rawTransaction.setTxHash(destinationTx);
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(walletAddress);
        rawTransaction.setRawData(new Document()
                .append("blockNumber", "46929654")
                .append("timeStamp", "1749112655")
                .append("transactionIndex", "131")
                .append("from", "0x7ddb0773e979cd36ef0dc8b6e594a6ebc876e654")
                .append("to", "0x2cca08ae69e0c44b18a57ab2a87644234daebaE4")
                .append("input", layerZeroExecute302Input())
                .append("methodId", "0xcfc32570")
                .append("functionName", "execute302")
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of())
                        .append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private String layerZeroExecute302Input() {
        return "0xcfc3257000000000000000000000000000000000000000000000000000000000000000200000000000000000000000005634c4a5fed09819e3c46d86a965dd9447d86e47000000000000000000000000000000000000000000000000000000000000759e00000000000000000000000019cfce47ed54a88614648dc3f19a5980097007dd000000000000000000000000000000000000000000000000000000000009301dc30104245c15b90406479816f3d0a46a7c01e46842db2650eabf1863727e8dad000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000001800000000000000000000000000000000000000000000000000000000000021291000000000000000000000000000000000000000000000000000000000000004c0200000000000000000000000000000000000000000000000000002d79883d2000000d0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f0000000000013c340000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
    }

    private TrackedWallet trackedWallet(String address) {
        TrackedWallet wallet = new TrackedWallet();
        wallet.setAddress(address);
        return wallet;
    }

    private RawTransaction destinationRaw(String txHash, String walletAddress) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":OPTIMISM:" + walletAddress);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.OPTIMISM.name());
        rawTransaction.setWalletAddress(walletAddress);
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setCreatedAt(Instant.parse("2026-03-31T10:00:00Z"));
        rawTransaction.setRawData(new Document()
                .append("blockNumber", "1")
                .append("timeStamp", "1711879200")
                .append("transactionIndex", "0")
                .append("from", "0x1111111111111111111111111111111111111111")
                .append("to", walletAddress)
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of(new Document()
                                .append("from", "0x2222222222222222222222222222222222222222")
                                .append("to", walletAddress)
                                .append("tokenSymbol", "USDT0")
                                .append("tokenAddress", "0x01bff41798a0bcf287b996046ca68b395dbc1071")
                                .append("value", "1939183")
                                .append("tokenDecimal", "6")))
                        .append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private NormalizedTransaction normalizedDestination(String txHash, NetworkId networkId, String walletAddress) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":" + networkId + ":" + walletAddress);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(networkId);
        transaction.setWalletAddress(walletAddress);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        transaction.setConfidence(ConfidenceLevel.LOW);
        transaction.setBlockTimestamp(Instant.parse("2026-03-31T10:00:00Z"));
        transaction.setTransactionIndex(1);
        transaction.setFlows(List.of(flow(NormalizedLegRole.BUY, "USDT0", "0x01bff41798a0bcf287b996046ca68b395dbc1071", "1.939183")));
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
