package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void skipsRowsThatAlreadyCarryPersistedReceiptEvidence() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway
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
                receiptClarificationGateway
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
    void keepsEulerReviewRowsWhenOnlyReceiptLogsWerePersisted() {
        PendingReceiptClarificationQueryService service = new PendingReceiptClarificationQueryService(
                mongoOperations,
                rawTransactionRepository,
                receiptClarificationGateway
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
