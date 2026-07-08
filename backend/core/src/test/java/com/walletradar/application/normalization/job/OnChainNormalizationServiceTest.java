package com.walletradar.application.normalization.job;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassifier;
import com.walletradar.application.linking.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.RegistryBridgeInboundTypeCorrectionService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.lending.application.LendingReceiptIdentityService;
import com.walletradar.application.normalization.pipeline.onchain.PendingRawTransactionQueryService;
import com.walletradar.application.normalization.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.application.normalization.pipeline.onchain.repair.InternalTransferRawPeerRepairService;
import com.walletradar.application.normalization.pipeline.onchain.support.ResolvedRawOrderingMetadata;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import com.walletradar.session.application.AccountingUniverseService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainNormalizationServiceTest {

    @Mock
    private PendingRawTransactionQueryService pendingRawTransactionQueryService;
    @Mock
    private IdempotentNormalizedTransactionStore normalizedTransactionStore;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private OnChainClassifier onChainClassifier;
    @Mock
    private ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;
    @Mock
    private InternalTransferRawPeerRepairService internalTransferRawPeerRepairService;
    @Mock
    private ProtocolNameEnrichmentService protocolNameEnrichmentService;
    @Mock
    private CounterpartyEnrichmentService counterpartyEnrichmentService;
    @Mock
    private RegistryBridgeInboundTypeCorrectionService registryBridgeInboundTypeCorrectionService;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private LendingReceiptIdentityService lendingReceiptIdentityService;

    private OnChainNormalizationService service;

    @BeforeEach
    void setUp() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setBatchSize(10);
        properties.setRetryDelaySeconds(60);
        service = new OnChainNormalizationService(
                pendingRawTransactionQueryService,
                properties,
                onChainClassifier,
                new OnChainNormalizedTransactionBuilder(),
                normalizedTransactionStore,
                rawTransactionRepository,
                explorerRawOrderingRepairGateway,
                internalTransferRawPeerRepairService,
                protocolNameEnrichmentService,
                registryBridgeInboundTypeCorrectionService,
                counterpartyEnrichmentService,
                accountingUniverseService,
                lendingReceiptIdentityService
        );
    }

    @Test
    @DisplayName("processes pending raw in timestamp transactionIndex txHash order")
    void processesPendingRawInDeterministicOrder() {
        RawTransaction laterIndex = raw("0xccc", 1_700_000_001L, 7);
        RawTransaction earliest = raw("0xaaa", 1_700_000_000L, 3);
        RawTransaction sameTimestampLowerIndex = raw("0xbbb", 1_700_000_001L, 2);
        when(pendingRawTransactionQueryService.loadNextBatch(10))
                .thenReturn(List.of(laterIndex, sameTimestampLowerIndex, earliest));
        when(internalTransferRawPeerRepairService.repairMissingPeers(any())).thenReturn(0);

        service.processNextBatch();

        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository, org.mockito.Mockito.times(3)).save(rawCaptor.capture());
        assertThat(rawCaptor.getAllValues())
                .extracting(RawTransaction::getTxHash)
                .containsExactly("0xaaa", "0xbbb", "0xccc");
    }

    @Test
    @DisplayName("repairs ordering metadata before batch sorting")
    void repairsOrderingMetadataBeforeBatchSorting() {
        RawTransaction repairedEarlier = raw("0xccc", 1_700_000_001L, 0);
        repairedEarlier.getRawData().remove("transactionIndex");
        RawTransaction existingLater = raw("0xbbb", 1_700_000_001L, 7);
        when(pendingRawTransactionQueryService.loadNextBatch(10))
                .thenReturn(List.of(existingLater, repairedEarlier));
        when(internalTransferRawPeerRepairService.repairMissingPeers(any())).thenReturn(0);
        when(explorerRawOrderingRepairGateway.fetch("0xccc", NetworkId.ETHEREUM))
                .thenReturn(java.util.Optional.of(new ResolvedRawOrderingMetadata(1_700_000_001L, 2)));

        service.processNextBatch();

        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository, org.mockito.Mockito.times(2)).save(rawCaptor.capture());
        assertThat(rawCaptor.getAllValues())
                .extracting(RawTransaction::getTxHash)
                .containsExactly("0xccc", "0xbbb");
    }

    @Test
    @DisplayName("marks raw COMPLETE after canonical shell write")
    void marksRawCompleteAfterCanonicalShellWrite() {
        RawTransaction rawTransaction = raw("0xabc", 1_700_000_000L, 5);
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification());

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        verify(normalizedTransactionStore).upsert(any());
        verify(rawTransactionRepository).save(rawTransaction);
        assertThat(rawTransaction.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
        assertThat(rawTransaction.getRetryCount()).isZero();
        assertThat(rawTransaction.getLastError()).isNull();
        assertThat(rawTransaction.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("enriches canonical metadata before upsert for typed rows")
    void enrichesCanonicalMetadataBeforeUpsertForTypedRows() {
        RawTransaction rawTransaction = raw("0xabc", 1_700_000_000L, 5);
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification());

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        verify(protocolNameEnrichmentService).enrichInPlace(any(), org.mockito.Mockito.same(rawTransaction), any());
        verify(counterpartyEnrichmentService).enrichInPlace(any(), org.mockito.Mockito.same(rawTransaction), any());
    }

    @Test
    @DisplayName("skips metadata enrichment for unknown review rows")
    void skipsMetadataEnrichmentForUnknownReviewRows() {
        RawTransaction rawTransaction = raw("0xabc", null, 5);
        rawTransaction.setRawData(new Document("transactionIndex", "5"));

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        verify(protocolNameEnrichmentService, never()).enrichInPlace(any(), any(), any());
        verify(counterpartyEnrichmentService, never()).enrichInPlace(any(), any(), any());
    }

    @Test
    @DisplayName("canonicalizes recovered ordering metadata before classification")
    void canonicalizesRecoveredOrderingMetadataBeforeClassification() {
        RawTransaction rawTransaction = raw("0xabc", null, 0);
        rawTransaction.setRawData(new Document("explorer", new Document("tx", new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "0x5"))));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification());

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        assertThat(rawTransaction.getRawData().getString("timeStamp")).isEqualTo("1700000000");
        assertThat(rawTransaction.getRawData().getString("transactionIndex")).isEqualTo("5");
        assertThat(rawTransaction.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
    }

    @Test
    @DisplayName("repairs missing transactionIndex from explorer metadata before classification")
    void repairsMissingTransactionIndexFromExplorerMetadataBeforeClassification() {
        RawTransaction rawTransaction = raw("0xabc", 1_700_000_000L, 0);
        rawTransaction.getRawData().remove("transactionIndex");
        when(explorerRawOrderingRepairGateway.fetch("0xabc", NetworkId.ETHEREUM))
                .thenReturn(java.util.Optional.of(new ResolvedRawOrderingMetadata(1_700_000_000L, 9)));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification());

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        assertThat(rawTransaction.getRawData().getString("transactionIndex")).isEqualTo("9");
        verify(onChainClassifier).classify(rawTransaction);
    }

    @Test
    @DisplayName("invalid raw becomes UNKNOWN NEEDS_REVIEW and stops retrying")
    void invalidRawBecomesNeedsReview() {
        RawTransaction rawTransaction = raw("0xabc", null, 5);
        rawTransaction.setRawData(new Document("transactionIndex", "5"));

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        verify(normalizedTransactionStore).upsert(any());
        verify(rawTransactionRepository).save(rawTransaction);
        assertThat(rawTransaction.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
        assertThat(rawTransaction.getRetryCount()).isZero();
        assertThat(rawTransaction.getLastError()).isNull();
        assertThat(rawTransaction.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("irrecoverable validation failure becomes UNKNOWN NEEDS_REVIEW and stops retrying")
    void irrecoverableValidationFailureBecomesNeedsReviewAndStopsRetrying() {
        RawTransaction rawTransaction = raw("0xabc", null, 0);
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setRawData(new Document("explorer", new Document("internalTransfers", List.of(
                new Document("timeStamp", "1700000001").append("transactionIndex", "2"),
                new Document("timeStamp", "1700000002").append("transactionIndex", "3")
        ))));
        when(explorerRawOrderingRepairGateway.fetch("0xabc", NetworkId.ETHEREUM))
                .thenReturn(java.util.Optional.empty());

        boolean normalized = service.normalize(rawTransaction);

        assertThat(normalized).isTrue();
        ArgumentCaptor<com.walletradar.domain.transaction.normalized.NormalizedTransaction> normalizedCaptor =
                ArgumentCaptor.forClass(com.walletradar.domain.transaction.normalized.NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons())
                .contains("MISSING_BLOCK_TIMESTAMP", "MISSING_TRANSACTION_INDEX");
        assertThat(rawTransaction.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
        assertThat(rawTransaction.getRetryCount()).isZero();
        assertThat(rawTransaction.getLastError()).isNull();
        assertThat(rawTransaction.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("repairs missing raw peers before normalizing current batch")
    void repairsMissingRawPeersBeforeNormalizingCurrentBatch() {
        RawTransaction rawTransaction = raw("0xabc", 1_700_000_000L, 5);
        when(pendingRawTransactionQueryService.loadNextBatch(10)).thenReturn(List.of(rawTransaction));
        when(internalTransferRawPeerRepairService.repairMissingPeers(any())).thenReturn(1);

        service.processNextBatch();

        verify(internalTransferRawPeerRepairService).repairMissingPeers(any());
    }

    private static RawTransaction raw(String txHash, Long epochSeconds, int transactionIndex) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + NetworkId.ETHEREUM + ":0xwallet");
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress("0xwallet");
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        Document rawData = new Document("transactionIndex", Integer.toString(transactionIndex));
        if (epochSeconds != null) {
            rawData.put("timeStamp", Long.toString(epochSeconds));
        }
        rawTransaction.setRawData(rawData);
        return rawTransaction;
    }

    private static OnChainClassificationResult classification() {
        return new OnChainClassificationResult(
                NormalizedTransactionType.SWAP,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.METHOD_ID,
                com.walletradar.domain.common.ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );
    }
}
