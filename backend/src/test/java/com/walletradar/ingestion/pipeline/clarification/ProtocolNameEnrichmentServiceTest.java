package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProtocolNameEnrichmentServiceTest {

    @Mock
    private ProtocolNameEnrichmentQueryService queryService;
    @Mock
    private ProtocolNameResolutionService resolutionService;
    @Mock
    private ProtocolNameCanonicalizer protocolNameCanonicalizer;
    @Mock
    private com.walletradar.domain.transaction.raw.RawTransactionRepository rawTransactionRepository;
    @Mock
    private com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void processNextBatchPersistsResolvedProtocolName() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("0xwallet");

        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xabc:ARBITRUM:0xwallet");
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId("ARBITRUM");
        rawTransaction.setWalletAddress("0xwallet");
        rawTransaction.setRawData(new Document("timeStamp", "1700000000").append("transactionIndex", "1"));

        when(queryService.loadBatchAfterId(null, 50)).thenReturn(List.of(transaction));
        when(queryService.loadBatchAfterId("tx-1", 50)).thenReturn(List.of());
        when(rawTransactionRepository.findById("0xabc:ARBITRUM:0xwallet")).thenReturn(Optional.of(rawTransaction));
        when(resolutionService.resolve(transaction, rawTransaction))
                .thenReturn(Optional.of(new ProtocolNameResolutionService.ResolvedProtocolName("Uniswap", "V3", null)));
        when(protocolNameCanonicalizer.canonicalize(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(protocolNameCanonicalizer.canonicalize("Uniswap")).thenReturn("Uniswap");
        when(protocolNameCanonicalizer.needsCanonicalization(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(protocolNameCanonicalizer.needsCanonicalization(null)).thenReturn(false);

        ProtocolNameEnrichmentService service = new ProtocolNameEnrichmentService(
                queryService,
                resolutionService,
                protocolNameCanonicalizer,
                rawTransactionRepository,
                normalizedTransactionRepository
        );

        int updated = service.processNextBatch(50);

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getProtocolName()).isEqualTo("Uniswap");
        assertThat(captor.getValue().getProtocolVersion()).isEqualTo("V3");
    }

    @Test
    void enrichPersistsResolutionStateForRowsThatAlreadyHaveProtocolName() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setProtocolName("Aave");
        when(protocolNameCanonicalizer.canonicalize(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(protocolNameCanonicalizer.needsCanonicalization(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(protocolNameCanonicalizer.canonicalize("Aave")).thenReturn("Aave");
        when(protocolNameCanonicalizer.needsCanonicalization("Aave")).thenReturn(false);

        ProtocolNameEnrichmentService service = new ProtocolNameEnrichmentService(
                queryService,
                resolutionService,
                protocolNameCanonicalizer,
                rawTransactionRepository,
                normalizedTransactionRepository
        );

        boolean updated = service.enrich(transaction, null, Instant.parse("2026-04-08T12:00:00Z"));

        assertThat(updated).isTrue();
        verify(normalizedTransactionRepository).save(transaction);
        assertThat(transaction.getProtocolResolutionState()).isEqualTo(MetadataResolutionState.RESOLVED_FAMILY);
        assertThat(transaction.getProtocolResolutionEvidence()).isEqualTo("EXISTING_PROTOCOL_NAME_CANONICALIZED");
    }

    @Test
    void processNextBatchScansPastUnresolvedRowsAndCanonicalizesLegacyNames() {
        NormalizedTransaction unresolved = new NormalizedTransaction();
        unresolved.setId("a");
        unresolved.setTxHash("0xaaa");
        unresolved.setNetworkId(NetworkId.ARBITRUM);
        unresolved.setWalletAddress("0xwallet1");

        RawTransaction unresolvedRaw = new RawTransaction();
        unresolvedRaw.setId("0xaaa:ARBITRUM:0xwallet1");

        NormalizedTransaction legacy = new NormalizedTransaction();
        legacy.setId("b");
        legacy.setProtocolName("LiFi");
        legacy.setProtocolVersion("V1");

        NormalizedTransaction resolvable = new NormalizedTransaction();
        resolvable.setId("c");
        resolvable.setTxHash("0xccc");
        resolvable.setNetworkId(NetworkId.ARBITRUM);
        resolvable.setWalletAddress("0xwallet3");

        RawTransaction resolvableRaw = new RawTransaction();
        resolvableRaw.setId("0xccc:ARBITRUM:0xwallet3");

        when(queryService.loadBatchAfterId(null, 50)).thenReturn(List.of(unresolved, legacy));
        when(queryService.loadBatchAfterId("b", 50)).thenReturn(List.of(resolvable));
        when(queryService.loadBatchAfterId("c", 50)).thenReturn(List.of());
        when(rawTransactionRepository.findById("0xaaa:ARBITRUM:0xwallet1")).thenReturn(Optional.of(unresolvedRaw));
        when(rawTransactionRepository.findById("0xccc:ARBITRUM:0xwallet3")).thenReturn(Optional.of(resolvableRaw));
        when(resolutionService.resolve(unresolved, unresolvedRaw)).thenReturn(Optional.empty());
        when(resolutionService.resolve(resolvable, resolvableRaw))
                .thenReturn(Optional.of(new ProtocolNameResolutionService.ResolvedProtocolName("Paraswap", "V6.2", null)));
        when(protocolNameCanonicalizer.canonicalize(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(protocolNameCanonicalizer.needsCanonicalization(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(protocolNameCanonicalizer.needsCanonicalization(null)).thenReturn(false);
        when(protocolNameCanonicalizer.needsCanonicalization("LiFi")).thenReturn(true);
        when(protocolNameCanonicalizer.canonicalize("LiFi")).thenReturn("LI.FI");
        when(protocolNameCanonicalizer.canonicalize("Paraswap")).thenReturn("Velora/ParaSwap");

        ProtocolNameEnrichmentService service = new ProtocolNameEnrichmentService(
                queryService,
                resolutionService,
                protocolNameCanonicalizer,
                rawTransactionRepository,
                normalizedTransactionRepository
        );

        int updated = service.processNextBatch(50);

        assertThat(updated).isEqualTo(3);
        verify(normalizedTransactionRepository, org.mockito.Mockito.times(3)).save(org.mockito.ArgumentMatchers.any());
        assertThat(unresolved.getProtocolResolutionState()).isEqualTo(MetadataResolutionState.TERMINAL_METADATA_ONLY);
        assertThat(legacy.getProtocolResolutionState()).isEqualTo(MetadataResolutionState.RESOLVED_FAMILY);
        assertThat(resolvable.getProtocolResolutionState()).isEqualTo(MetadataResolutionState.RESOLVED_FAMILY);
    }
}
