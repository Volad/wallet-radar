package com.walletradar.ingestion.pipeline.clarification;

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
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
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
        verify(rawTransactionRepository, never()).save(any());
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
