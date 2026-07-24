package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingReceiptClarificationQueryServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private com.walletradar.domain.transaction.raw.RawTransactionRepository rawTransactionRepository;
    @Mock
    private ReceiptClarificationGateway receiptClarificationGateway;
    @Mock
    private com.walletradar.platform.networks.descriptor.NetworkRegistry networkRegistry;

    @Test
    void loadsBlockingNeedsReviewRowsWithArbitraryReasonForReceiptRecovery() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = reviewCandidate();
        candidate.setMissingDataReasons(List.of("UNSUPPORTED_REVIEW_REASON"));
        RawTransaction rawTransaction = raw(candidate.getId(), candidate.getTxHash());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(candidate));
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true)).thenReturn(Optional.empty());

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(candidate.getId());
            assertThat(row.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
            assertThat(row.getMissingDataReasons()).containsExactly("UNSUPPORTED_REVIEW_REASON");
        });
    }

    @Test
    void needsReviewRecoveryQueryExcludesAccountingExcludedRows() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        service.loadNextBatch(1, 2, 120);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations, atLeastOnce()).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        String queryText = String.valueOf(queryCaptor.getAllValues().get(0).getQueryObject());
        assertThat(queryText).contains("NEEDS_REVIEW");
        assertThat(queryText).contains("excludedFromAccounting");
        assertThat(queryText).contains("$ne", "true");
    }

    @Test
    void needsReviewRecoveryQueryRestrictsSelectionToEvmFamilyNetworks() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );
        when(networkRegistry.evmWalletSupportedNetworks())
                .thenReturn(java.util.EnumSet.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM, NetworkId.BASE));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        service.loadNextBatch(1, 2, 120);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations, atLeastOnce()).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        String queryText = String.valueOf(queryCaptor.getAllValues().get(0).getQueryObject());
        assertThat(queryText).contains("networkId");
        assertThat(queryText).contains("ARBITRUM");
        assertThat(queryText).doesNotContain("SOLANA");
        assertThat(queryText).doesNotContain("TON");
    }

    @Test
    void skipsRowsThatAlreadyCarryPersistedReceiptEvidence() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = reviewCandidate();
        RawTransaction rawTransaction = raw(candidate.getId(), candidate.getTxHash());
        AtomicInteger findCalls = new AtomicInteger();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenAnswer(invocation -> findCalls.getAndIncrement() == 0 ? List.of(candidate) : List.of());
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(new Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                .append("tokenSymbol", "WETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0xpm")
                                .append("to", WALLET)
                                .append("value", "100000000000000000")),
                        List.of(),
                        null,
                        null
                )));

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).isEmpty();
        verify(receiptClarificationGateway).fromPersistedEvidence(rawTransaction, true);
    }

    @Test
    void loadsEulerPendingClarificationRowsForReceiptEnrichment() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = new NormalizedTransaction();
        candidate.setId("0xeuler:ARBITRUM:" + WALLET);
        candidate.setTxHash("0xeuler");
        candidate.setNetworkId(NetworkId.ARBITRUM);
        candidate.setWalletAddress(WALLET);
        candidate.setSource(NormalizedTransactionSource.ON_CHAIN);
        candidate.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        candidate.setTransactionIndex(1);
        candidate.setType(NormalizedTransactionType.UNKNOWN);
        candidate.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        candidate.setClassifiedBy(ClassificationSource.HEURISTIC);
        candidate.setProtocolName("Euler");
        candidate.setMissingDataReasons(List.of(ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code()));
        candidate.setFullReceiptClarificationAttempts(0);
        candidate.setUpdatedAt(Instant.parse("2026-03-22T10:00:00Z"));

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(candidate.getId());
        rawTransaction.setTxHash(candidate.getTxHash());
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("methodId", "0xc16ae7a4")
                .append("to", "0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(candidate));
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true)).thenReturn(Optional.empty());

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(candidate.getId());
            assertThat(row.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            assertThat(row.getProtocolName()).isEqualTo("Euler");
        });
    }

    @Test
    void confirmedFluidRecoveryOnlyLoadsRowsMissingFullLogEvidence() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        service.claimConfirmedFluidReceiptBatch(10, 2, 300, "worker-1", 300);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        String queryText = String.valueOf(queryCaptor.getValue().getQueryObject());
        assertThat(queryText).contains("CONFIRMED");
        assertThat(queryText).contains("PENDING_PRICE");
        assertThat(queryText).contains("Fluid");
        assertThat(queryText).contains("metadata.evidenceCompleteness");
        assertThat(queryText).contains("FULL_LOGS_PRESENT");
    }

    @Test
    void keepsEulerReviewRowsWhenOnlyReceiptLogsWerePersisted() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = new NormalizedTransaction();
        candidate.setId("0x305f37:AVALANCHE:" + WALLET);
        candidate.setTxHash("0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df");
        candidate.setNetworkId(NetworkId.AVALANCHE);
        candidate.setWalletAddress(WALLET);
        candidate.setSource(NormalizedTransactionSource.ON_CHAIN);
        candidate.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        candidate.setTransactionIndex(1);
        candidate.setType(NormalizedTransactionType.UNKNOWN);
        candidate.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        candidate.setClassifiedBy(ClassificationSource.HEURISTIC);
        candidate.setProtocolName("Euler");
        candidate.setMissingDataReasons(List.of(ClassificationReasonCode.CLASSIFICATION_FAILED.code()));
        candidate.setFullReceiptClarificationAttempts(0);
        candidate.setUpdatedAt(Instant.parse("2026-03-22T10:00:00Z"));

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(candidate.getId());
        rawTransaction.setTxHash(candidate.getTxHash());
        rawTransaction.setNetworkId(NetworkId.AVALANCHE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("methodId", "0xc16ae7a4")
                .append("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1"));
        rawTransaction.setClarificationEvidence(new Document("receipt", new Document("logs", List.of(
                new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                        .append("topics", List.of("0xborrow"))
        ))));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(candidate));
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true)).thenReturn(Optional.empty());

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(candidate.getId());
            assertThat(row.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
            assertThat(row.getProtocolName()).isEqualTo("Euler");
        });
    }

    @Test
    void loadsPendingClarificationLpExitRowsForNativeSettlementTransferRecovery() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = new NormalizedTransaction();
        candidate.setId("0x6b57:BASE:" + WALLET);
        candidate.setTxHash("0x6b57e6439d1bcde7faaff2f43498ef97be9e696f889aeef2b2cc68fa2a5a1cf3");
        candidate.setNetworkId(NetworkId.BASE);
        candidate.setWalletAddress(WALLET);
        candidate.setSource(NormalizedTransactionSource.ON_CHAIN);
        candidate.setBlockTimestamp(Instant.ofEpochSecond(1_763_366_333L));
        candidate.setTransactionIndex(84);
        candidate.setType(NormalizedTransactionType.LP_EXIT);
        candidate.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        candidate.setClassifiedBy(ClassificationSource.PROTOCOL_REGISTRY);
        candidate.setProtocolName("PancakeSwap");
        candidate.setMissingDataReasons(List.of(
                ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code()
        ));
        candidate.setFullReceiptClarificationAttempts(0);
        candidate.setUpdatedAt(Instant.parse("2026-04-09T10:00:00Z"));

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(candidate.getId());
        rawTransaction.setTxHash(candidate.getTxHash());
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(com.walletradar.domain.transaction.raw.RawSyncMethod.BLOCKSCOUT);
        rawTransaction.setRawData(new Document()
                .append("input", "0xac9650d80000000000000000000000000000000000000000000000000000000000000020")
                .append("methodId", "0x")
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                                .append("to", WALLET)
                                .append("value", "9948876")
                )).append("internalTransfers", List.of())));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(candidate));
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true)).thenReturn(Optional.empty());

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(candidate.getId());
            assertThat(row.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            assertThat(row.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        });
    }

    @Test
    void loadsPendingClarificationLpExitRowsForFeeSplitEvidenceRecovery() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = new NormalizedTransaction();
        candidate.setId("0xfeesplit:BASE:" + WALLET);
        candidate.setTxHash("0xfeesplit");
        candidate.setNetworkId(NetworkId.BASE);
        candidate.setWalletAddress(WALLET);
        candidate.setSource(NormalizedTransactionSource.ON_CHAIN);
        candidate.setBlockTimestamp(Instant.ofEpochSecond(1_763_366_333L));
        candidate.setTransactionIndex(84);
        candidate.setType(NormalizedTransactionType.LP_EXIT);
        candidate.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        candidate.setClassifiedBy(ClassificationSource.PROTOCOL_REGISTRY);
        candidate.setProtocolName("PancakeSwap");
        candidate.setMissingDataReasons(List.of(
                ClassificationReasonCode.LP_FEE_SPLIT_EVIDENCE_REQUIRED.code()
        ));
        candidate.setFullReceiptClarificationAttempts(0);
        candidate.setUpdatedAt(Instant.parse("2026-04-09T10:00:00Z"));

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(candidate.getId());
        rawTransaction.setTxHash(candidate.getTxHash());
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("methodId", "0xac9650d8")
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(candidate));
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true)).thenReturn(Optional.empty());

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(candidate.getId());
            assertThat(row.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            assertThat(row.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
            assertThat(row.getMissingDataReasons())
                    .contains(ClassificationReasonCode.LP_FEE_SPLIT_EVIDENCE_REQUIRED.code());
        });
    }

    @Test
    void feeSplitEvidenceRecoveryQueryMatchesLpExitFamilyAndReason() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        service.loadNextBatch(1, 2, 120);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations, atLeastOnce()).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        String queryText = String.valueOf(queryCaptor.getAllValues().get(0).getQueryObject());
        assertThat(queryText).contains("LP_FEE_SPLIT_EVIDENCE_REQUIRED");
        assertThat(queryText).contains("LP_EXIT");
    }

    @Test
    void loadsPendingClarificationLpEntryRowsForPositionCorrelationRecovery() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway,
                networkRegistry
        );

        NormalizedTransaction candidate = new NormalizedTransaction();
        candidate.setId("0xlp-mint:ARBITRUM:" + WALLET);
        candidate.setTxHash("0xlp-mint");
        candidate.setNetworkId(NetworkId.ARBITRUM);
        candidate.setWalletAddress(WALLET);
        candidate.setSource(NormalizedTransactionSource.ON_CHAIN);
        candidate.setBlockTimestamp(Instant.ofEpochSecond(1_762_700_000L));
        candidate.setTransactionIndex(17);
        candidate.setType(NormalizedTransactionType.LP_ENTRY);
        candidate.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        candidate.setClassifiedBy(ClassificationSource.PROTOCOL_REGISTRY);
        candidate.setProtocolName("PancakeSwap");
        candidate.setMissingDataReasons(List.of(
                ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code()
        ));
        candidate.setFullReceiptClarificationAttempts(0);
        candidate.setUpdatedAt(Instant.parse("2026-04-09T10:00:00Z"));

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(candidate.getId());
        rawTransaction.setTxHash(candidate.getTxHash());
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("methodId", "0x88316456")
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("input", "0x8831645600000000000000000000000082af49447d8a07e3bd95bd0d56f35241523fbab1"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(candidate));
        when(rawTransactionRepository.findAllById(List.of(candidate.getId()))).thenReturn(List.of(rawTransaction));
        when(receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true)).thenReturn(Optional.empty());

        List<NormalizedTransaction> batch = service.loadNextBatch(1, 2, 120);

        assertThat(batch).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(candidate.getId());
            assertThat(row.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            assertThat(row.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
            assertThat(row.getMissingDataReasons())
                    .contains(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code());
        });
    }

    private static NormalizedTransaction reviewCandidate() {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId("0xabc:BASE:" + WALLET);
        normalizedTransaction.setTxHash("0xabc");
        normalizedTransaction.setNetworkId(NetworkId.BASE);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(NormalizedTransactionType.UNKNOWN);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalizedTransaction.setMissingDataReasons(List.of(
                ClassificationReasonCode.ROUTER_METHOD_OVERLOAD_UNSUPPORTED.code()
        ));
        normalizedTransaction.setFullReceiptClarificationAttempts(0);
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        return normalizedTransaction;
    }

    private static RawTransaction raw(String id, String txHash) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(id);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        return rawTransaction;
    }
}
