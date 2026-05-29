package com.walletradar.ingestion.job.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEventRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.bybit.BybitBotTransferCostBasisService;
import com.walletradar.ingestion.pipeline.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.ingestion.pipeline.bybit.BybitEarnPrincipalTransferPairer;
import com.walletradar.ingestion.pipeline.bybit.BybitPrincipalEventExclusivityService;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferExternalCpReclassifier;
import com.walletradar.ingestion.pipeline.bybit.BybitStakingConversionPairer;
import com.walletradar.ingestion.pipeline.bybit.BybitStreamAuthorityCollapser;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferPairer;
import com.walletradar.ingestion.pipeline.bybit.BybitTradePairer;
import com.walletradar.ingestion.pipeline.bybit.BybitTransferShadowPairer;
import com.walletradar.ingestion.pipeline.bybit.PendingExternalLedgerRowQueryService;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import com.walletradar.integration.bybit.BybitExtractionService;
import com.walletradar.integration.bybit.BybitExtractedEventMapper;
import com.walletradar.integration.bybit.BybitExtractedTradePairer;
import com.walletradar.integration.bybit.BybitExtractedTransferShadowPairer;
import com.walletradar.integration.bybit.PendingBybitExtractedRowQueryService;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitNormalizationServiceTest {

    @Mock
    private PendingBybitExtractedRowQueryService pendingBybitExtractedRowQueryService;
    @Mock
    private BybitExtractedEventRepository bybitExtractedEventRepository;
    @Mock
    private IntegrationRawEventRepository integrationRawEventRepository;
    @Mock
    private BybitExtractedTradePairer bybitExtractedTradePairer;
    @Mock
    private BybitExtractedTransferShadowPairer bybitExtractedTransferShadowPairer;
    @Mock
    private PendingExternalLedgerRowQueryService pendingExternalLedgerRowQueryService;
    @Mock
    private ExternalLedgerRawRepository externalLedgerRawRepository;
    @Mock
    private BybitTradePairer bybitTradePairer;
    @Mock
    private BybitTransferShadowPairer bybitTransferShadowPairer;
    @Mock
    private IdempotentNormalizedTransactionStore normalizedTransactionStore;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private TrackedWalletLookupService trackedWalletLookupService;
    @Mock
    private BybitExtractionService bybitExtractionService;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private BybitInternalTransferPairer bybitInternalTransferPairer;
    @Mock
    private BybitEarnPrincipalTransferPairer bybitEarnPrincipalTransferPairer;
    @Mock
    private BybitPrincipalEventExclusivityService bybitPrincipalEventExclusivityService;
    @Mock
    private BybitInternalTransferExternalCpReclassifier bybitInternalTransferExternalCpReclassifier;
    @Mock
    private BybitStreamAuthorityCollapser bybitStreamAuthorityCollapser;
    @Mock
    private BybitStakingConversionPairer bybitStakingConversionPairer;
    @Mock
    private BybitBotTransferCostBasisService bybitBotTransferCostBasisService;

    @Test
    void extractedTradeLaneIsProcessedBeforeLegacyRawRows() {
        BybitExtractedEvent extracted = extractedTradeRow("extracted-trade-1", "BUY");
        ExternalLedgerRaw legacy = tradeRow("legacy-trade-1", "BUY");

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(extracted));
        when(bybitExtractedEventRepository.findById(extracted.getId())).thenReturn(Optional.of(extracted));
        when(bybitExtractedTradePairer.findOppositeLeg(extracted)).thenReturn(Optional.empty());

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        verify(normalizedTransactionStore).upsert(org.mockito.ArgumentMatchers.any(NormalizedTransaction.class));
        verify(externalLedgerRawRepository, never()).findById(legacy.getId());
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(extracted);
        assertThat(extracted.getStatus()).isEqualTo(BybitExtractedEventStatus.CONFIRMED);
    }

    @Test
    void orphanLegFallsBackToExplicitUnmatchedCanonicalRow() {
        ExternalLedgerRaw orphan = tradeRow("trade-1", "BUY");
        when(pendingExternalLedgerRowQueryService.loadNextBatch(50)).thenReturn(List.of(orphan));
        when(externalLedgerRawRepository.findById(orphan.getId())).thenReturn(Optional.of(orphan));
        when(bybitTradePairer.findOppositeLeg(orphan)).thenReturn(Optional.empty());

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(50);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(saved.getMissingDataReasons()).contains("UTA_TRADE_PAIR_NOT_FOUND");
        assertThat(saved.getExcludedFromAccounting()).isTrue();
        assertThat(saved.getAccountingExclusionReason()).isEqualTo("UTA_TRADE_PAIR_NOT_FOUND");
        assertThat(saved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        verify(externalLedgerRawRepository).save(orphan);
        assertThat(orphan.getStatus()).isEqualTo(ExternalLedgerRawStatus.CONFIRMED);
    }

    @Test
    void legacyInboundCanonicalTypeMapsIntoExternalTransferIn() {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("legacy-inbound");
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("fund_asset_changes");
        row.setCanonicalType("EXTERNAL_INBOUND");
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        row.setAssetSymbol("USDT");
        row.setQuantityRaw(new BigDecimal("500"));
        row.setBasisRelevant(true);

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(row));
        when(externalLedgerRawRepository.findById(row.getId())).thenReturn(Optional.of(row));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        // Stablecoin inbound is USD-pegged at normalization time and confirmed immediately.
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(saved.getFlows().get(0).getValueUsd()).isEqualByComparingTo("500");
        assertThat(saved.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.BUY);
    }

    @Test
    void bridgeWithdrawNormalizationStaysSourceLocalUntilLinkingPhase() {
        ExternalLedgerRaw withdraw = bridgeRow("bridge-withdraw", "EXTERNAL_TRANSFER_OUT");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(withdraw));
        when(externalLedgerRawRepository.findById(withdraw.getId())).thenReturn(Optional.of(withdraw));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> bybitCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(bybitCaptor.capture());
        NormalizedTransaction bybitSaved = bybitCaptor.getValue();
        assertThat(bybitSaved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        // Cycle/5 N16: EXTERNAL_TRANSFER_OUT carries SELL role → must obtain market price before
        // AVCO replay (basis disposal / realized PnL computation). Pricing or linking will resolve
        // it in a later stage; what matters here is that the normalization phase doesn't try to
        // perform linking by itself.
        assertThat(bybitSaved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(bybitSaved.getCorrelationId()).isNull();
        assertThat(bybitSaved.getContinuityCandidate()).isNull();
        assertThat(bybitSaved.getMatchedCounterparty()).isNull();
        verify(normalizedTransactionRepository, never()).findAllByTxHashAndNetworkIdAndSource(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(rawTransactionRepository, never()).findAllByTxHashAndNetworkId(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(withdraw.getOnChainCorrelation().getStatus()).isNull();
    }

    @Test
    void bridgeDepositNormalizationStaysSourceLocalUntilLinkingPhase() {
        ExternalLedgerRaw deposit = bridgeRow("bridge-deposit", "EXTERNAL_INBOUND");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(deposit));
        when(externalLedgerRawRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> bybitCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(bybitCaptor.capture());
        NormalizedTransaction bybitSaved = bybitCaptor.getValue();
        assertThat(bybitSaved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(bybitSaved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(bybitSaved.getFlows().get(0).getValueUsd()).isEqualByComparingTo("100");
        assertThat(bybitSaved.getCorrelationId()).isNull();
        assertThat(bybitSaved.getContinuityCandidate()).isNull();
        assertThat(bybitSaved.getMatchedCounterparty()).isNull();
        verify(normalizedTransactionRepository, never()).findAllByTxHashAndNetworkIdAndSource(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(rawTransactionRepository, never()).findAllByTxHashAndNetworkId(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(deposit.getOnChainCorrelation().getStatus()).isNull();
    }

    @Test
    void confirmedUnmatchedBridgeIsLeftForDedicatedLinkingPhase() {
        ExternalLedgerRaw deposit = bridgeRow("bridge-deposit", "EXTERNAL_INBOUND");
        deposit.setStatus(ExternalLedgerRawStatus.CONFIRMED);
        deposit.getOnChainCorrelation().setStatus("UNMATCHED");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of());

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isZero();
        verify(pendingExternalLedgerRowQueryService, never()).loadNextBridgeRematchBatch(10);
        verify(normalizedTransactionStore, never()).upsert(org.mockito.ArgumentMatchers.any(NormalizedTransaction.class));
        verify(externalLedgerRawRepository, never()).save(deposit);
        assertThat(deposit.getOnChainCorrelation().getStatus()).isEqualTo("UNMATCHED");
    }

    @Test
    void unmatchedExternalVenueRowDefersExternalCustodyDecisionToLinkingPhase() {
        ExternalLedgerRaw deposit = bridgeRow("external-custody-in", "EXTERNAL_INBOUND");
        deposit.setSenderAddress("0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64");
        deposit.setReceivedAddress("0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(deposit));
        when(externalLedgerRawRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> bybitCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(bybitCaptor.capture());
        assertThat(bybitCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(bybitCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(bybitCaptor.getValue().getExcludedFromAccounting()).isFalse();
        assertThat(bybitCaptor.getValue().getAccountingExclusionReason()).isNull();
        assertThat(bybitCaptor.getValue().getMissingDataReasons()).doesNotContain("EXTERNAL_CUSTODY_UNTRACKED_VENUE");
        assertThat(bybitCaptor.getValue().getCounterpartyAddress()).isEqualTo("0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64");
        assertThat(deposit.getOnChainCorrelation().getStatus()).isNull();
    }

    @Test
    void unmatchedTrackedWalletAddressDefersBridgeGapDecisionToLinkingPhase() {
        ExternalLedgerRaw withdraw = bridgeRow("tracked-gap", "EXTERNAL_TRANSFER_OUT");
        withdraw.setReceivedAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(withdraw));
        when(externalLedgerRawRepository.findById(withdraw.getId())).thenReturn(Optional.of(withdraw));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> bybitCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(bybitCaptor.capture());
        assertThat(bybitCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        // Cycle/5 N16: SELL role → PENDING_PRICE until pricing or linking stage resolves the flow.
        assertThat(bybitCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(bybitCaptor.getValue().getMissingDataReasons()).doesNotContain("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        assertThat(bybitCaptor.getValue().getCounterpartyAddress()).isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(withdraw.getOnChainCorrelation().getStatus()).isNull();
    }

    @Test
    void utaRewardClaimDoesNotEnterTradePairingPath() {
        ExternalLedgerRaw reward = new ExternalLedgerRaw();
        reward.setId("reward-1");
        reward.setUid("uid-1");
        reward.setWalletRef("BYBIT:uid-1");
        reward.setSourceFileType("uta_derivatives");
        reward.setCanonicalType("REWARD_CLAIM");
        reward.setBybitType("BONUS");
        reward.setStatus(ExternalLedgerRawStatus.RAW);
        reward.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        reward.setAssetSymbol("USDT");
        reward.setCashFlow(new BigDecimal("0.1"));
        reward.setBasisRelevant(true);

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(reward));
        when(externalLedgerRawRepository.findById(reward.getId())).thenReturn(Optional.of(reward));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        verify(bybitTradePairer, never()).findOppositeLeg(reward);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
    }

    @Test
    void convertClusterBuildsAggregatedSwapInsteadOfEmptyFlowConfirmedSwap() {
        ExternalLedgerRaw convertSell = convertRow("convert-sell", "COOK", "-1", Instant.parse("2026-03-25T10:00:00Z"));
        ExternalLedgerRaw convertBuy = convertRow("convert-buy", "MNT", "0.2", Instant.parse("2026-03-25T10:00:01Z"));

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(convertSell));
        when(externalLedgerRawRepository.findById(convertSell.getId())).thenReturn(Optional.of(convertSell));
        when(bybitTradePairer.loadConvertCluster(convertSell)).thenReturn(List.of(convertSell, convertBuy));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getFlows()).hasSize(2);
        assertThat(saved.getFlows()).anySatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY));
        assertThat(saved.getFlows()).anySatisfy(flow -> assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL));
        verify(externalLedgerRawRepository).save(convertSell);
        verify(externalLedgerRawRepository).save(convertBuy);
    }

    @Test
    void convertWithoutOppositeSideIsExcludedFromAccounting() {
        ExternalLedgerRaw convertSell = convertRow("convert-sell", "COOK", "-1", Instant.parse("2026-03-25T10:00:00Z"));

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(convertSell));
        when(externalLedgerRawRepository.findById(convertSell.getId())).thenReturn(Optional.of(convertSell));
        when(bybitTradePairer.loadConvertCluster(convertSell)).thenReturn(List.of(convertSell));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(captor.getValue().getExcludedFromAccounting()).isTrue();
        assertThat(captor.getValue().getMissingDataReasons()).contains("BYBIT_CONVERT_CLUSTER_INCOMPLETE");
        assertThat(convertSell.getBasisRelevant()).isFalse();
        verify(externalLedgerRawRepository, org.mockito.Mockito.times(1)).save(convertSell);
    }

    @Test
    void extractedTransactionLogCurrencyConvertBuildsAggregatedSwap() {
        BybitExtractedEvent convertSell = extractedConvertRow(
                "convert-sell",
                "CMETH",
                "-0.66931648",
                "CURRENCY_SELL",
                Instant.parse("2025-04-17T12:08:56Z")
        );
        BybitExtractedEvent convertBuy = extractedConvertRow(
                "convert-buy",
                "ETH",
                "0.70215876",
                "CURRENCY_BUY",
                Instant.parse("2025-04-17T12:08:56Z")
        );

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(convertSell));
        when(bybitExtractedEventRepository.findById(convertSell.getId())).thenReturn(Optional.of(convertSell));
        when(bybitExtractedTradePairer.loadConvertCluster(convertSell)).thenReturn(List.of(convertSell, convertBuy));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getFlows())
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole() + ":" + flow.getQuantityDelta())
                .containsExactlyInAnyOrder(
                        "ETH:BUY:0.70215876",
                        "CMETH:SELL:-0.66931648"
                );
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(convertSell);
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(convertBuy);
        verify(bybitExtractedTradePairer, never()).findOppositeLeg(convertSell);
    }

    @Test
    void extractedFundingHistoryConvertBuildsAggregatedSwap() {
        BybitExtractedEvent convertSell = extractedConvertRow(
                "convert-sell-funding",
                "ZAMA",
                "-11.585",
                "Convert",
                Instant.parse("2026-02-09T05:09:04Z")
        );
        convertSell.setSourceFileType("fund_asset_changes");
        convertSell.setBybitDescription("Small Balance Conversion");
        BybitExtractedEvent convertBuy = extractedConvertRow(
                "convert-buy-funding",
                "MNT",
                "0.4940094251864499",
                "Convert",
                Instant.parse("2026-02-09T05:09:06Z")
        );
        convertBuy.setSourceFileType("fund_asset_changes");
        convertBuy.setBybitDescription("Small Balance Conversion");

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(convertSell));
        when(bybitExtractedEventRepository.findById(convertSell.getId())).thenReturn(Optional.of(convertSell));
        when(bybitExtractedTradePairer.loadConvertCluster(convertSell)).thenReturn(List.of(convertSell, convertBuy));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getFlows())
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole() + ":" + flow.getQuantityDelta())
                .containsExactlyInAnyOrder(
                        "ZAMA:SELL:-11.585",
                        "MNT:BUY:0.4940094251864499"
                );
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(convertSell);
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(convertBuy);
    }

    @Test
    void extractedDepositHydratesSenderAddressFromIntegrationRawEvent() {
        BybitExtractedEvent deposit = new BybitExtractedEvent();
        deposit.setId("deposit-raw-fallback");
        deposit.setIntegrationRawEventId("integration-raw-1");
        deposit.setUid("uid-1");
        deposit.setWalletRef("BYBIT:uid-1");
        deposit.setSourceFileType("withdraw_deposit");
        deposit.setCanonicalType("EXTERNAL_INBOUND");
        deposit.setStatus(BybitExtractedEventStatus.RAW);
        deposit.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        deposit.setAssetSymbol("MNT");
        deposit.setQuantityRaw(new BigDecimal("834.076712"));
        deposit.setBasisRelevant(true);
        deposit.setTxHash("0xdeposit");
        deposit.setNetworkId(NetworkId.MANTLE);
        deposit.setReceivedAddress("0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d");

        IntegrationRawEvent rawEvent = new IntegrationRawEvent();
        rawEvent.setId("integration-raw-1");
        rawEvent.setPayload(new Document("fromAddress", "0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64")
                .append("toAddress", "0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d")
                .append("txID", "0xdeposit"));

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(deposit));
        when(bybitExtractedEventRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(integrationRawEventRepository.findById("integration-raw-1")).thenReturn(Optional.of(rawEvent));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(saved.getCounterpartyAddress()).isEqualTo("0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64");
        assertThat(saved.getExcludedFromAccounting()).isFalse();
        assertThat(deposit.getSenderAddress()).isEqualTo("0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64");
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(deposit);
    }

    @Test
    void extractedOnChainEarnSubscriptionPairBecomesConfirmedStakingDeposit() {
        BybitExtractedEvent ethLeg = extractedLiquidStakingRow(
                "cmeth-eth-leg",
                "ETH",
                "-0.11384604",
                Instant.parse("2025-04-28T17:47:36Z")
        );
        BybitExtractedEvent cmethLeg = extractedLiquidStakingRow(
                "cmeth-receipt-leg",
                "CMETH",
                "0.10687862",
                Instant.parse("2025-04-28T17:52:26Z")
        );

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(ethLeg));
        when(bybitExtractedEventRepository.findById(ethLeg.getId())).thenReturn(Optional.of(ethLeg));
        when(bybitExtractedTradePairer.findLiquidStakingCounterLeg(ethLeg)).thenReturn(Optional.of(cmethLeg));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(saved.getFlows())
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole() + ":" + flow.getQuantityDelta())
                .containsExactlyInAnyOrder(
                        "ETH:TRANSFER:-0.11384604",
                        "CMETH:TRANSFER:0.10687862"
                );
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(ethLeg);
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(cmethLeg);
    }

    @Test
    void extractedLiquidStakingPairThatCrossesBybitSubAccountsIsNormalizedAsIndependentLegs() {
        // Cycle/5 N13: METH out on FUND + CMETH in on EARN must not collapse into one
        // STAKING_DEPOSIT (single walletAddress would leak the debit). See ADR-006 §9.
        BybitExtractedEvent methFundLeg = extractedLiquidStakingRow(
                "meth-fund-leg",
                "METH",
                "-0.66865026",
                Instant.parse("2025-04-28T17:47:36Z")
        );
        methFundLeg.setWalletRef("BYBIT:UID:FUND");
        BybitExtractedEvent cmethEarnLeg = extractedLiquidStakingRow(
                "cmeth-earn-leg",
                "CMETH",
                "0.66865026",
                Instant.parse("2025-04-28T17:52:26Z")
        );
        cmethEarnLeg.setWalletRef("BYBIT:UID:EARN");

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(methFundLeg));
        when(bybitExtractedEventRepository.findById(methFundLeg.getId())).thenReturn(Optional.of(methFundLeg));
        when(bybitExtractedTradePairer.findLiquidStakingCounterLeg(methFundLeg)).thenReturn(Optional.of(cmethEarnLeg));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isNotEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        // Counter leg on EARN must NOT be marked confirmed by the pair path — it remains pending
        // for its own normalization pass.
        verify(bybitExtractedEventRepository, never()).save(cmethEarnLeg);
    }

    @Test
    void extractedEth20StakeMintPairBecomesConfirmedStakingDeposit() {
        BybitExtractedEvent stakeLeg = extractedLiquidStakingRow(
                "eth20-stake-leg",
                "ETH",
                "-0.709",
                Instant.parse("2025-03-12T20:08:36Z")
        );
        stakeLeg.setBybitType("ETH 2.0");
        stakeLeg.setBybitDescription("Stake");

        BybitExtractedEvent mintLeg = extractedLiquidStakingRow(
                "eth20-mint-leg",
                "METH",
                "0.66865026",
                Instant.parse("2025-03-12T20:37:05Z")
        );
        mintLeg.setBybitType("ETH 2.0");
        mintLeg.setBybitDescription("Mint");

        when(pendingBybitExtractedRowQueryService.loadNextBatch(10)).thenReturn(List.of(stakeLeg));
        when(bybitExtractedEventRepository.findById(stakeLeg.getId())).thenReturn(Optional.of(stakeLeg));
        when(bybitExtractedTradePairer.findLiquidStakingCounterLeg(stakeLeg)).thenReturn(Optional.of(mintLeg));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(saved.getFlows())
                .extracting(flow -> flow.getAssetSymbol() + ":" + flow.getRole() + ":" + flow.getQuantityDelta())
                .containsExactlyInAnyOrder(
                        "ETH:TRANSFER:-0.709",
                        "METH:TRANSFER:0.66865026"
                );
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(stakeLeg);
        verify(bybitExtractedEventRepository, org.mockito.Mockito.atLeastOnce()).save(mintLeg);
    }

    @Test
    void loanRowsAreDemotedOutOfPriceableLane() {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId("loan-1");
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("fund_asset_changes");
        row.setBybitType("Loans");
        row.setCanonicalType("BORROW");
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        row.setAssetSymbol("USDT");
        row.setQuantityRaw(new BigDecimal("-523"));

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(row));
        when(externalLedgerRawRepository.findById(row.getId())).thenReturn(Optional.of(row));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(captor.getValue().getMissingDataReasons()).contains("BYBIT_LOAN_SEMANTICS_UNSUPPORTED");
        assertThat(captor.getValue().getExcludedFromAccounting()).isTrue();
        assertThat(captor.getValue().getAccountingExclusionReason()).isEqualTo("BYBIT_LOAN_SEMANTICS_UNSUPPORTED");
    }

    @Test
    void fundingHistoryWithdrawIsBasisDisposingAnchorEvenWhenChainAwareSiblingExists() {
        // Cycle/5 N17: FH/Withdraw is the canonical FUND accounting anchor — it DISPOSES basis at
        // market price (role=SELL, status=PENDING_PRICE → PriceableFlowPolicy stamps unitPriceUsd).
        // The chain-aware WITHDRAWAL stream sibling is the basisRelevant=false continuity mirror
        // (excluded via BYBIT_BASIS_IRRELEVANT in builder.buildMappedRow), NOT the anchor.
        // The previous shadow-pairer behaviour wrongly excluded FH/Withdraw whenever a chain-aware
        // sibling existed, leaking basis silently. The pairer no longer applies to FH rows.
        ExternalLedgerRaw anchor = new ExternalLedgerRaw();
        anchor.setId("fh-withdraw-1");
        anchor.setUid("uid-1");
        anchor.setWalletRef("BYBIT:uid-1");
        anchor.setSourceFileType("fund_asset_changes");
        anchor.setBybitType("Withdraw");
        anchor.setCanonicalType("EXTERNAL_TRANSFER_OUT");
        anchor.setChain("BYBIT");
        anchor.setStatus(ExternalLedgerRawStatus.RAW);
        anchor.setTimeUtc(Instant.parse("2026-02-19T08:14:22Z"));
        anchor.setAssetSymbol("ETH");
        anchor.setQuantityRaw(new BigDecimal("-3.06"));
        anchor.setBasisRelevant(true);

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(anchor));
        when(externalLedgerRawRepository.findById(anchor.getId())).thenReturn(Optional.of(anchor));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getExcludedFromAccounting()).isFalse();
        assertThat(saved.getAccountingExclusionReason()).isNull();
        assertThat(saved.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.SELL);
    }

    @Test
    void fundingHistoryDepositIsBasisAcquiringAnchorEvenWhenChainAwareSiblingExists() {
        // Cycle/5 N17: FH/Deposit is the canonical FUND accounting anchor — it ACQUIREs basis at
        // market price (role=BUY, status=PENDING_PRICE). DEPOSIT_ONCHAIN remains the
        // basisRelevant=false continuity mirror.
        ExternalLedgerRaw anchor = new ExternalLedgerRaw();
        anchor.setId("fh-deposit-1");
        anchor.setUid("uid-1");
        anchor.setWalletRef("BYBIT:uid-1");
        anchor.setSourceFileType("fund_asset_changes");
        anchor.setBybitType("Deposit");
        anchor.setCanonicalType("EXTERNAL_INBOUND");
        anchor.setChain("BYBIT");
        anchor.setStatus(ExternalLedgerRawStatus.RAW);
        anchor.setTimeUtc(Instant.parse("2026-02-19T08:14:22Z"));
        anchor.setAssetSymbol("ETH");
        anchor.setQuantityRaw(new BigDecimal("0.699"));
        anchor.setBasisRelevant(true);

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(anchor));
        when(externalLedgerRawRepository.findById(anchor.getId())).thenReturn(Optional.of(anchor));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getExcludedFromAccounting()).isFalse();
        assertThat(saved.getAccountingExclusionReason()).isNull();
        assertThat(saved.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.BUY);
    }

    private BybitNormalizationService service() {
        return new BybitNormalizationService(
                pendingBybitExtractedRowQueryService,
                bybitExtractedEventRepository,
                integrationRawEventRepository,
                bybitExtractedTradePairer,
                bybitExtractedTransferShadowPairer,
                pendingExternalLedgerRowQueryService,
                externalLedgerRawRepository,
                bybitTradePairer,
                bybitTransferShadowPairer,
                new BybitExtractedEventMapper(),
                bybitExtractionService,
                new BybitCanonicalTransactionBuilder(),
                normalizedTransactionStore,
                accountingUniverseService,
                bybitInternalTransferPairer,
                bybitEarnPrincipalTransferPairer,
                bybitPrincipalEventExclusivityService,
                bybitInternalTransferExternalCpReclassifier,
                bybitStreamAuthorityCollapser,
                bybitStakingConversionPairer,
                bybitBotTransferCostBasisService
        );
    }

    private ExternalLedgerRaw tradeRow(String id, String direction) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("uta_derivatives");
        row.setCanonicalType("SWAP");
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        row.setUtaDirection(direction);
        row.setUtaContract("ETHUSDT");
        row.setAssetSymbol("ETH");
        row.setQuantityRaw(new BigDecimal("1"));
        row.setFilledPrice(new BigDecimal("2500"));
        row.setCashFlow(new BigDecimal("1"));
        row.setBasisRelevant(true);
        return row;
    }

    private BybitExtractedEvent extractedTradeRow(String id, String direction) {
        BybitExtractedEvent row = new BybitExtractedEvent();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("uta_derivatives");
        row.setCanonicalType("SWAP");
        row.setStatus(BybitExtractedEventStatus.RAW);
        row.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        row.setUtaDirection(direction);
        row.setUtaContract("ETHUSDT");
        row.setAssetSymbol("ETH");
        row.setQuantityRaw(new BigDecimal("1"));
        row.setFilledPrice(new BigDecimal("2500"));
        row.setCashFlow(new BigDecimal("1"));
        row.setBasisRelevant(true);
        return row;
    }

    private BybitExtractedEvent extractedLiquidStakingRow(
            String id,
            String assetSymbol,
            String quantityRaw,
            Instant timeUtc
    ) {
        BybitExtractedEvent row = new BybitExtractedEvent();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("fund_asset_changes");
        row.setSourceFile("FUNDING_HISTORY");
        row.setBybitType("Earn");
        row.setBybitDescription("On-chain Earn subscription");
        row.setCanonicalType("INTERNAL_TRANSFER");
        row.setStatus(BybitExtractedEventStatus.RAW);
        row.setTimeUtc(timeUtc);
        row.setAssetSymbol(assetSymbol);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        row.setBasisRelevant(true);
        return row;
    }

    private ExternalLedgerRaw bridgeRow(String id, String canonicalType) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setSourceFileType("withdraw_deposit");
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setTimeUtc(Instant.parse("2026-03-25T10:00:00Z"));
        row.setCanonicalType(canonicalType);
        row.setAssetSymbol("USDC");
        row.setQuantityRaw(new BigDecimal("100"));
        row.setBasisRelevant(true);
        row.setTxHash("0xbridge");
        row.setNetworkId(NetworkId.ARBITRUM);
        return row;
    }

    private ExternalLedgerRaw convertRow(String id, String assetSymbol, String quantityRaw, Instant timeUtc) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("fund_asset_changes");
        row.setBybitType("Convert");
        row.setCanonicalType("SWAP");
        row.setStatus(ExternalLedgerRawStatus.RAW);
        row.setTimeUtc(timeUtc);
        row.setAssetSymbol(assetSymbol);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        row.setBasisRelevant(true);
        return row;
    }

    private BybitExtractedEvent extractedConvertRow(
            String id,
            String assetSymbol,
            String quantityRaw,
            String bybitType,
            Instant timeUtc
    ) {
        BybitExtractedEvent row = new BybitExtractedEvent();
        row.setId(id);
        row.setUid("uid-1");
        row.setWalletRef("BYBIT:uid-1");
        row.setSourceFileType("uta_derivatives");
        row.setBybitType(bybitType);
        row.setBybitDescription("Currency convert");
        row.setCanonicalType("SWAP");
        row.setStatus(BybitExtractedEventStatus.RAW);
        row.setTimeUtc(timeUtc);
        row.setAssetSymbol(assetSymbol);
        row.setQuantityRaw(new BigDecimal(quantityRaw));
        row.setBasisRelevant(true);
        return row;
    }

    private NormalizedTransaction onChainTx(String id, NormalizedTransactionType type, NormalizedLegRole role) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash("0xbridge");
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("100"));
        transaction.setFlows(List.of(flow));
        return transaction;
    }
}
