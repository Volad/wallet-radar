package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterpartyEnrichmentServiceTest {

    @Mock
    private CounterpartyEnrichmentQueryService queryService;
    @Mock
    private CounterpartyResolutionService resolutionService;
    @Mock
    private com.walletradar.domain.transaction.raw.RawTransactionRepository rawTransactionRepository;
    @Mock
    private com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void processNextBatchScansPastUnresolvedRowsUntilItFindsResolvableCounterparties() {
        NormalizedTransaction unresolved = new NormalizedTransaction();
        unresolved.setId("a");
        unresolved.setTxHash("0xaaa");
        unresolved.setNetworkId(NetworkId.ARBITRUM);
        unresolved.setWalletAddress("0xwallet1");

        RawTransaction unresolvedRaw = new RawTransaction();
        unresolvedRaw.setId("0xaaa:ARBITRUM:0xwallet1");
        unresolvedRaw.setRawData(new Document());

        NormalizedTransaction resolvable = new NormalizedTransaction();
        resolvable.setId("b");
        resolvable.setTxHash("0xbbb");
        resolvable.setNetworkId(NetworkId.ARBITRUM);
        resolvable.setWalletAddress("0xwallet2");

        RawTransaction resolvableRaw = new RawTransaction();
        resolvableRaw.setId("0xbbb:ARBITRUM:0xwallet2");
        resolvableRaw.setRawData(new Document());

        when(queryService.loadBatchAfterId(null, 50)).thenReturn(List.of(unresolved));
        when(queryService.loadBatchAfterId("a", 50)).thenReturn(List.of(resolvable));
        when(queryService.loadBatchAfterId("b", 50)).thenReturn(List.of());
        when(rawTransactionRepository.findById("0xaaa:ARBITRUM:0xwallet1")).thenReturn(Optional.of(unresolvedRaw));
        when(rawTransactionRepository.findById("0xbbb:ARBITRUM:0xwallet2")).thenReturn(Optional.of(resolvableRaw));
        when(resolutionService.resolveMetadata(unresolved, unresolvedRaw))
                .thenReturn(new CounterpartyResolutionService.ResolvedCounterparty(
                        null,
                        CounterpartyType.GENUINE_MISSING_SOURCE,
                        MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING,
                        "NO_UNIQUE_ROW_LOCAL_COUNTERPARTY"
                ));
        when(resolutionService.resolveMetadata(resolvable, resolvableRaw))
                .thenReturn(new CounterpartyResolutionService.ResolvedCounterparty(
                        "0x2222222222222222222222222222222222222222",
                        CounterpartyType.PROTOCOL,
                        MetadataResolutionState.RESOLVED_EXACT,
                        "ROW_LOCAL_RAW_OR_REGISTRY_EVIDENCE"
                ));

        CounterpartyEnrichmentService service = new CounterpartyEnrichmentService(
                queryService,
                resolutionService,
                rawTransactionRepository,
                normalizedTransactionRepository
        );

        int updated = service.processNextBatch(50);

        assertThat(updated).isEqualTo(2);
        verify(normalizedTransactionRepository).save(unresolved);
        verify(normalizedTransactionRepository).save(resolvable);
        assertThat(unresolved.getCounterpartyResolutionState()).isEqualTo(MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING);
        assertThat(resolvable.getCounterpartyAddress()).isEqualTo("0x2222222222222222222222222222222222222222");
        assertThat(resolvable.getCounterpartyType()).isEqualTo(CounterpartyType.PROTOCOL);
        assertThat(resolvable.getCounterpartyResolutionState()).isEqualTo(MetadataResolutionState.RESOLVED_EXACT);
    }

    @Test
    void enrichPromotesExternalTransferToInternalForUniverseMember() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        transaction.setExcludedFromAccounting(true);
        transaction.setAccountingExclusionReason("TEST");

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setRawData(new Document());
        when(resolutionService.resolveMetadata(transaction, rawTransaction))
                .thenReturn(new CounterpartyResolutionService.ResolvedCounterparty(
                        "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG",
                        CounterpartyType.PERSONAL_WALLET,
                        MetadataResolutionState.RESOLVED_EXACT,
                        "ACCOUNTING_UNIVERSE"
                ));

        CounterpartyEnrichmentService service = new CounterpartyEnrichmentService(
                queryService,
                resolutionService,
                rawTransactionRepository,
                normalizedTransactionRepository
        );

        boolean updated = service.enrichInPlace(transaction, rawTransaction, java.time.Instant.parse("2026-04-08T12:00:00Z"));

        assertThat(updated).isTrue();
        assertThat(transaction.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(transaction.getExcludedFromAccounting()).isFalse();
        assertThat(transaction.getAccountingExclusionReason()).isNull();
        assertThat(transaction.getCounterpartyType()).isEqualTo(CounterpartyType.PERSONAL_WALLET);
    }

    @Test
    void enrichTerminalizesRowsWhenRawEvidenceIsMissing() {
        NormalizedTransaction transaction = new NormalizedTransaction();

        CounterpartyEnrichmentService service = new CounterpartyEnrichmentService(
                queryService,
                resolutionService,
                rawTransactionRepository,
                normalizedTransactionRepository
        );

        boolean updated = service.enrich(transaction, null, java.time.Instant.parse("2026-04-08T12:00:00Z"));

        assertThat(updated).isTrue();
        verify(normalizedTransactionRepository).save(transaction);
        assertThat(transaction.getCounterpartyType()).isEqualTo(CounterpartyType.GENUINE_MISSING_SOURCE);
        assertThat(transaction.getCounterpartyResolutionState()).isEqualTo(MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING);
        assertThat(transaction.getCounterpartyResolutionEvidence()).isEqualTo("RAW_TRANSACTION_MISSING");
    }
}
