package com.walletradar.ingestion.job.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.ingestion.pipeline.bybit.BybitTradePairer;
import com.walletradar.ingestion.pipeline.bybit.PendingExternalLedgerRowQueryService;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private PendingExternalLedgerRowQueryService pendingExternalLedgerRowQueryService;
    @Mock
    private ExternalLedgerRawRepository externalLedgerRawRepository;
    @Mock
    private BybitTradePairer bybitTradePairer;
    @Mock
    private IdempotentNormalizedTransactionStore normalizedTransactionStore;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private RawTransactionRepository rawTransactionRepository;

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
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    void matchedWithdrawCorrelatesWithOnChainReceiveAndMarksContinuityMetadata() {
        ExternalLedgerRaw withdraw = bridgeRow("bridge-withdraw", "EXTERNAL_TRANSFER_OUT");
        NormalizedTransaction onChain = onChainTx("on-chain-1", NormalizedTransactionType.EXTERNAL_TRANSFER_IN, NormalizedLegRole.BUY);
        RawTransaction raw = new RawTransaction();
        raw.setId("raw-1");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(withdraw));
        when(externalLedgerRawRepository.findById(withdraw.getId())).thenReturn(Optional.of(withdraw));
        when(rawTransactionRepository.findAllByTxHashAndNetworkId("0xbridge", "ARBITRUM")).thenReturn(List.of(raw));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xbridge",
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(onChain));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> bybitCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(bybitCaptor.capture());
        NormalizedTransaction bybitSaved = bybitCaptor.getValue();
        assertThat(bybitSaved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(bybitSaved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(bybitSaved.getCorrelationId()).isEqualTo("BYBIT:ARBITRUM:0xbridge");
        assertThat(bybitSaved.getContinuityCandidate()).isTrue();
        assertThat(bybitSaved.getMatchedCounterparty()).isEqualTo("0xwallet");

        ArgumentCaptor<NormalizedTransaction> onChainCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(onChainCaptor.capture());
        NormalizedTransaction onChainSaved = onChainCaptor.getValue();
        assertThat(onChainSaved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(onChainSaved.getCorrelationId()).isEqualTo("BYBIT:ARBITRUM:0xbridge");
        assertThat(onChainSaved.getContinuityCandidate()).isTrue();
        assertThat(onChainSaved.getMatchedCounterparty()).isEqualTo("BYBIT:uid-1");
        assertThat(onChainSaved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(withdraw.getOnChainCorrelation().getStatus()).isEqualTo("MATCHED");
    }

    @Test
    void matchedDepositCorrelatesWithOnChainSendAndMarksContinuityMetadata() {
        ExternalLedgerRaw deposit = bridgeRow("bridge-deposit", "EXTERNAL_INBOUND");
        NormalizedTransaction onChain = onChainTx("on-chain-2", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, NormalizedLegRole.SELL);
        RawTransaction raw = new RawTransaction();
        raw.setId("raw-2");

        when(pendingExternalLedgerRowQueryService.loadNextBatch(10)).thenReturn(List.of(deposit));
        when(externalLedgerRawRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(rawTransactionRepository.findAllByTxHashAndNetworkId("0xbridge", "ARBITRUM")).thenReturn(List.of(raw));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0xbridge",
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(onChain));

        BybitNormalizationService service = service();
        int processed = service.processNextBatch(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> bybitCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionStore).upsert(bybitCaptor.capture());
        NormalizedTransaction bybitSaved = bybitCaptor.getValue();
        assertThat(bybitSaved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(bybitSaved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(bybitSaved.getCorrelationId()).isEqualTo("BYBIT:ARBITRUM:0xbridge");
        assertThat(bybitSaved.getContinuityCandidate()).isTrue();

        ArgumentCaptor<NormalizedTransaction> onChainCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(onChainCaptor.capture());
        NormalizedTransaction onChainSaved = onChainCaptor.getValue();
        assertThat(onChainSaved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(onChainSaved.getContinuityCandidate()).isTrue();
        assertThat(onChainSaved.getMatchedCounterparty()).isEqualTo("BYBIT:uid-1");
        assertThat(onChainSaved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.SELL);
        assertThat(deposit.getOnChainCorrelation().getCorrelationId()).isEqualTo("BYBIT:ARBITRUM:0xbridge");
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
    void convertWithoutOppositeSideFallsBackToNeedsReview() {
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
        assertThat(captor.getValue().getMissingDataReasons()).contains("BYBIT_CONVERT_CLUSTER_INCOMPLETE");
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

    private BybitNormalizationService service() {
        return new BybitNormalizationService(
                pendingExternalLedgerRowQueryService,
                externalLedgerRawRepository,
                bybitTradePairer,
                new BybitCanonicalTransactionBuilder(),
                normalizedTransactionStore,
                normalizedTransactionRepository,
                rawTransactionRepository
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
