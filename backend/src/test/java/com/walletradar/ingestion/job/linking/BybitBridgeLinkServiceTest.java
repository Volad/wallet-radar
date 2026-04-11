package com.walletradar.ingestion.job.linking;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import com.walletradar.integration.bybit.BybitExtractedEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitBridgeLinkServiceTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private com.walletradar.domain.transaction.bybit.BybitExtractedEventRepository bybitExtractedEventRepository;
    @Mock
    private com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository externalLedgerRawRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private TrackedWalletLookupService trackedWalletLookupService;

    @Test
    void matchedBridgeCorrelationMovesOutOfNormalizationAndIntoLinking() {
        ExternalLedgerRaw withdraw = bridgeRow("bridge-withdraw", "EXTERNAL_TRANSFER_OUT");
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        NormalizedTransaction bybitTx = builder.buildMappedRow(withdraw, Instant.parse("2026-04-10T10:00:00Z"));
        RawTransaction raw = new RawTransaction();
        raw.setId("raw-1");
        NormalizedTransaction onChain = onChainTx("on-chain-1", NormalizedTransactionType.EXTERNAL_TRANSFER_IN);

        when(normalizedTransactionRepository.findById(builder.canonicalId(withdraw))).thenReturn(Optional.of(bybitTx));
        when(rawTransactionRepository.findAllByTxHashAndNetworkId("0xbridge", "ARBITRUM")).thenReturn(List.of(raw));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xbridge",
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(onChain));

        BybitBridgeLinkService service = service();
        boolean changed = service.repair(withdraw, Instant.parse("2026-04-10T10:01:00Z"));

        assertThat(changed).isTrue();
        assertThat(withdraw.getOnChainCorrelation().getStatus()).isEqualTo("MATCHED");
        assertThat(withdraw.getOnChainCorrelation().getMatchedDocId()).isEqualTo("raw-1");
        assertThat(bybitTx.getMissingDataReasons()).doesNotContain(BybitBridgeLinkService.BRIDGE_MISSING_REASON);
        verify(externalLedgerRawRepository).save(withdraw);
        verify(normalizedTransactionRepository).save(bybitTx);
    }

    @Test
    void unmatchedTrackedWalletRowGetsBridgeGapDuringLinking() {
        ExternalLedgerRaw withdraw = bridgeRow("tracked-gap", "EXTERNAL_TRANSFER_OUT");
        withdraw.setReceivedAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        NormalizedTransaction bybitTx = builder.buildMappedRow(withdraw, Instant.parse("2026-04-10T10:00:00Z"));

        when(normalizedTransactionRepository.findById(builder.canonicalId(withdraw))).thenReturn(Optional.of(bybitTx));
        when(rawTransactionRepository.findAllByTxHashAndNetworkId("0xbridge", "ARBITRUM")).thenReturn(List.of());
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xbridge",
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(trackedWalletLookupService.contains("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")).thenReturn(true);

        BybitBridgeLinkService service = service();
        boolean changed = service.repair(withdraw, Instant.parse("2026-04-10T10:01:00Z"));

        assertThat(changed).isTrue();
        assertThat(withdraw.getOnChainCorrelation().getStatus()).isEqualTo("UNMATCHED");
        assertThat(bybitTx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(bybitTx.getMissingDataReasons()).contains(BybitBridgeLinkService.BRIDGE_MISSING_REASON);
        assertThat(bybitTx.getExcludedFromAccounting()).isFalse();
        verify(externalLedgerRawRepository).save(withdraw);
        verify(normalizedTransactionRepository).save(bybitTx);
    }

    @Test
    void unmatchedTrackedWalletRowIsIdempotentOnSecondLinkingPass() {
        ExternalLedgerRaw withdraw = bridgeRow("tracked-gap", "EXTERNAL_TRANSFER_OUT");
        withdraw.setReceivedAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        withdraw.getOnChainCorrelation().setStatus("UNMATCHED");
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        NormalizedTransaction bybitTx = builder.buildMappedRow(withdraw, Instant.parse("2026-04-10T10:00:00Z"));
        bybitTx.getMissingDataReasons().add(BybitBridgeLinkService.BRIDGE_MISSING_REASON);

        when(normalizedTransactionRepository.findById(builder.canonicalId(withdraw))).thenReturn(Optional.of(bybitTx));
        when(rawTransactionRepository.findAllByTxHashAndNetworkId("0xbridge", "ARBITRUM")).thenReturn(List.of());
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xbridge",
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(trackedWalletLookupService.contains("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")).thenReturn(true);

        BybitBridgeLinkService service = service();
        boolean changed = service.repair(withdraw, Instant.parse("2026-04-10T10:01:00Z"));

        assertThat(changed).isFalse();
        verify(externalLedgerRawRepository, never()).save(withdraw);
        verify(normalizedTransactionRepository, never()).save(bybitTx);
    }

    @Test
    void unmatchedExternalCustodyRowIsExcludedDuringLinking() {
        ExternalLedgerRaw deposit = bridgeRow("external-custody-in", "EXTERNAL_INBOUND");
        deposit.setReceivedAddress("0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d");
        BybitCanonicalTransactionBuilder builder = new BybitCanonicalTransactionBuilder();
        NormalizedTransaction bybitTx = builder.buildMappedRow(deposit, Instant.parse("2026-04-10T10:00:00Z"));

        when(normalizedTransactionRepository.findById(builder.canonicalId(deposit))).thenReturn(Optional.of(bybitTx));
        when(rawTransactionRepository.findAllByTxHashAndNetworkId("0xbridge", "ARBITRUM")).thenReturn(List.of());
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xbridge",
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(trackedWalletLookupService.contains("0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d")).thenReturn(false);

        BybitBridgeLinkService service = service();
        boolean changed = service.repair(deposit, Instant.parse("2026-04-10T10:01:00Z"));

        assertThat(changed).isTrue();
        assertThat(deposit.getOnChainCorrelation().getStatus()).isEqualTo("EXTERNAL_CUSTODY");
        assertThat(bybitTx.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(bybitTx.getExcludedFromAccounting()).isTrue();
        assertThat(bybitTx.getAccountingExclusionReason()).isEqualTo(BybitBridgeLinkService.EXTERNAL_CUSTODY_EXCLUSION_REASON);
        assertThat(bybitTx.getMissingDataReasons()).contains(BybitBridgeLinkService.EXTERNAL_CUSTODY_EXCLUSION_REASON);
        assertThat(bybitTx.getMissingDataReasons()).doesNotContain(BybitBridgeLinkService.BRIDGE_MISSING_REASON);
        assertThat(bybitTx.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.TRANSFER);
        verify(externalLedgerRawRepository).save(deposit);
        verify(normalizedTransactionRepository).save(bybitTx);
        verify(trackedWalletLookupService).contains("0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d");
    }

    @Test
    void nonBridgeRowsAreIgnored() {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("reward");
        row.setSourceFileType("fund_asset_changes");
        row.setCanonicalType("REWARD_CLAIM");
        row.setStatus(com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus.CONFIRMED);

        BybitBridgeLinkService service = service();
        boolean changed = service.repair(row, Instant.parse("2026-04-10T10:01:00Z"));

        assertThat(changed).isFalse();
        verify(externalLedgerRawRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(normalizedTransactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private BybitBridgeLinkService service() {
        return new BybitBridgeLinkService(
                mongoOperations,
                bybitExtractedEventRepository,
                externalLedgerRawRepository,
                new BybitExtractedEventMapper(),
                new BybitCanonicalTransactionBuilder(),
                normalizedTransactionRepository,
                rawTransactionRepository,
                trackedWalletLookupService
        );
    }

    private ExternalLedgerRaw bridgeRow(String id, String canonicalType) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("withdraw_deposit");
        row.setCanonicalType(canonicalType);
        row.setStatus(com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus.CONFIRMED);
        row.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        row.setAssetSymbol("USDC");
        row.setQuantityRaw("EXTERNAL_TRANSFER_OUT".equals(canonicalType)
                ? new BigDecimal("-100")
                : new BigDecimal("100"));
        row.setTxHash("0xbridge");
        row.setNetworkId(NetworkId.ARBITRUM);
        row.setBasisRelevant(true);
        return row;
    }

    private NormalizedTransaction onChainTx(String txHash, NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("on-chain");
        transaction.setTxHash(txHash);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        return transaction;
    }
}
