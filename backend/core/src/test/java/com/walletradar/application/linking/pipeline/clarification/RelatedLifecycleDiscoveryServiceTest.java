package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassifier;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelatedLifecycleDiscoveryServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Mock
    private ReceiptClarificationGateway clarificationGateway;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private OnChainClassifier onChainClassifier;
    @Mock
    private IdempotentNormalizedTransactionStore normalizedTransactionStore;
    @Mock
    private ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;
    private RelatedLifecycleDiscoveryService service;

    @BeforeEach
    void setUp() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.getRelatedDiscovery().setEnabled(true);
        properties.getRelatedDiscovery().setForwardBlockWindow(1000);
        properties.getRelatedDiscovery().setMaxPages(2);
        service = new RelatedLifecycleDiscoveryService(
                properties,
                clarificationGateway,
                rawTransactionRepository,
                onChainClassifier,
                new OnChainNormalizedTransactionBuilder(),
                normalizedTransactionStore,
                explorerRawOrderingRepairGateway
        );
    }

    @Test
    @DisplayName("discovers and immediately normalizes missing GMX keeper tx")
    void discoversAndImmediatelyNormalizesMissingGmxKeeperTx() {
        RawTransaction anchor = raw(
                "0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7",
                314900000L
        );
        OnChainClassificationResult anchorClassification = classification(
                NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                "GMX"
        );
        RawTransaction discovered = raw(
                "0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8",
                314901000L
        );
        OnChainClassificationResult discoveredClassification = classification(
                NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
                "GMX"
        );

        when(clarificationGateway.findWalletRelatedTransactionHashes(
                WALLET,
                NetworkId.ARBITRUM,
                RawSyncMethod.ETHERSCAN,
                314900000L,
                314901000L,
                2
        )).thenReturn(List.of(discovered.getTxHash()));
        when(rawTransactionRepository.existsById(discovered.getId())).thenReturn(false);
        when(clarificationGateway.fetchRawTransactionByHash(
                discovered.getTxHash(),
                NetworkId.ARBITRUM,
                WALLET,
                RawSyncMethod.ETHERSCAN
        )).thenReturn(Optional.of(discovered));
        when(onChainClassifier.classify(discovered)).thenReturn(discoveredClassification);

        int discoveredCount = service.discoverAndNormalize(anchor, anchorClassification);

        assertThat(discoveredCount).isEqualTo(1);
        verify(normalizedTransactionStore).upsert(any());
        verify(rawTransactionRepository).save(discovered);
        assertThat(discovered.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
    }

    @Test
    @DisplayName("ignores unrelated discovered rows after classification")
    void ignoresUnrelatedDiscoveredRowsAfterClassification() {
        RawTransaction anchor = raw(
                "0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7",
                314900000L
        );
        OnChainClassificationResult anchorClassification = classification(
                NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                "GMX"
        );
        RawTransaction discovered = raw(
                "0xdeadbeef53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446",
                314901000L
        );
        OnChainClassificationResult discoveredClassification = classification(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                null
        );

        when(clarificationGateway.findWalletRelatedTransactionHashes(
                WALLET,
                NetworkId.ARBITRUM,
                RawSyncMethod.ETHERSCAN,
                314900000L,
                314901000L,
                2
        )).thenReturn(List.of(discovered.getTxHash()));
        when(rawTransactionRepository.existsById(discovered.getId())).thenReturn(false);
        when(clarificationGateway.fetchRawTransactionByHash(
                discovered.getTxHash(),
                NetworkId.ARBITRUM,
                WALLET,
                RawSyncMethod.ETHERSCAN
        )).thenReturn(Optional.of(discovered));
        when(onChainClassifier.classify(discovered)).thenReturn(discoveredClassification);

        int discoveredCount = service.discoverAndNormalize(anchor, anchorClassification);

        assertThat(discoveredCount).isZero();
        verify(normalizedTransactionStore, never()).upsert(any());
        verify(rawTransactionRepository, never()).save(discovered);
    }

    @Test
    @DisplayName("discovers and immediately normalizes missing GMX pool exit settlement tx")
    void discoversAndImmediatelyNormalizesMissingGmxPoolExitSettlementTx() {
        RawTransaction anchor = raw(
                "0x806ccd26c2f11ab6180c8f1f0448df2fda3917ffcc65dc7d661c1ae8d86426ec",
                314900000L
        );
        OnChainClassificationResult anchorClassification = classification(
                NormalizedTransactionType.LP_EXIT_REQUEST,
                "GMX"
        );
        RawTransaction discovered = raw(
                "0xf3581fb98799bb1d55ec08a72dfb6668ae4009f219434e734e8a9db0388ec374",
                314900500L
        );
        OnChainClassificationResult discoveredClassification = classification(
                NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                "GMX"
        );

        when(clarificationGateway.findWalletRelatedTransactionHashes(
                WALLET,
                NetworkId.ARBITRUM,
                RawSyncMethod.ETHERSCAN,
                314900000L,
                314901000L,
                2
        )).thenReturn(List.of(discovered.getTxHash()));
        when(rawTransactionRepository.existsById(discovered.getId())).thenReturn(false);
        when(clarificationGateway.fetchRawTransactionByHash(
                discovered.getTxHash(),
                NetworkId.ARBITRUM,
                WALLET,
                RawSyncMethod.ETHERSCAN
        )).thenReturn(Optional.of(discovered));
        when(onChainClassifier.classify(discovered)).thenReturn(discoveredClassification);

        int discoveredCount = service.discoverAndNormalize(anchor, anchorClassification);

        assertThat(discoveredCount).isEqualTo(1);
        verify(normalizedTransactionStore).upsert(any());
        verify(rawTransactionRepository).save(discovered);
        assertThat(discovered.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
    }

    private static RawTransaction raw(String txHash, long blockNumber) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + NetworkId.ARBITRUM.name() + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setBlockNumber(blockNumber);
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1741780460")
                .append("transactionIndex", "1"));
        return rawTransaction;
    }

    private static OnChainClassificationResult classification(
            NormalizedTransactionType type,
            String protocolName
    ) {
        return new OnChainClassificationResult(
                type,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                protocolName,
                "V2"
        );
    }
}
