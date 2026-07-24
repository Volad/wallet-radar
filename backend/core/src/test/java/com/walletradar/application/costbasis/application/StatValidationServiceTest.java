package com.walletradar.application.costbasis.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.application.normalization.pipeline.ton.TonNormalizedTransactionBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.bson.Document;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatValidationServiceTest {

    @Mock
    private PendingStatQueryService pendingStatQueryService;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void validPendingStatPromotesToConfirmed() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "2500", PriceSource.BINANCE)
        );
        Document metadata = new Document("evidenceCompleteness", "FULL_LOGS_PRESENT")
                .append("vaultAddress", "0xvault");
        Document clarificationEvidence = new Document("source", "full-receipt")
                .append("fluidLogOperate", new Document("borrow", "1"));
        transaction.setMetadata(metadata);
        transaction.setClarificationEvidence(clarificationEvidence);
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        assertThat(outcome.demotedToNeedsReview()).isZero();

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getStatAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getMetadata()).isEqualTo(metadata);
        assertThat(captor.getValue().getMetadata()).isNotSameAs(metadata);
        assertThat(captor.getValue().getClarificationEvidence()).isEqualTo(clarificationEvidence);
        assertThat(captor.getValue().getClarificationEvidence()).isNotSameAs(clarificationEvidence);
    }

    @Test
    void confirmPreservesCustodialOffChainThroughCopyAndReplace() {
        // ADR-072/ADR-079 regression: a Telegram-Wallet EXTERNAL_CUSTODY TON inbound carries
        // custodialOffChain=true (stamped by the counterparty resolver at normalization). The
        // stat-validation copy-and-replace confirm cycle previously OMITTED custodialOffChain from
        // copy(), silently dropping it on the persisted CONFIRMED row so the informational custody
        // ledger (filters custodialOffChain=true) came back empty. It must survive the confirm save.
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.TRANSFER, "USDT", "13", "1", PriceSource.STABLECOIN)
        );
        transaction.setNetworkId(NetworkId.TON);
        transaction.setCounterpartyType(CounterpartyType.EXTERNAL_CUSTODY);
        transaction.setCounterpartyAddress("0:023895AEF955024920A291C6F3715E291DF1B3DD254EAFA8B09E21A2D58D5897");
        transaction.setProtocolName("Telegram Wallet");
        transaction.setCustodialOffChain(true);
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        // The flag (and its sibling custody metadata) must persist so the custody ledger query returns it.
        assertThat(saved.getCustodialOffChain()).isTrue();
        assertThat(saved.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
        assertThat(saved.getProtocolName()).isEqualTo("Telegram Wallet");
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    void confirmPreservesLpReceiptFlagThroughCopyAndReplaceSoLedgerStampsLpReceiptFamily() {
        // ADR-081 (C1) regression, same class as custodialOffChain: a Solana Meteora DAMM LP_ENTRY
        // carries an MLP receipt leg flagged lpReceipt=true (from the DAMM lpMint at normalization).
        // The stat-validation copy-and-replace confirm cycle previously OMITTED lpReceipt from the
        // per-flow copy(), so LedgerPointCollector saw null on the CONFIRMED row and never stamped
        // FAMILY:LP_RECEIPT — leaving the confusable MLP rendering as a priced spot asset.
        String mlpMint = "6fymg7doag2taxdmp7nhnvhbkqxsorodzmdxnrmzwftf";
        NormalizedTransaction.Flow solLeg = flow(NormalizedLegRole.TRANSFER, "SOL", "-1.0", "150", PriceSource.BINANCE);
        NormalizedTransaction.Flow mlpLeg = flow(NormalizedLegRole.TRANSFER, "MLP", "0.3096", null, null);
        mlpLeg.setAssetContract(mlpMint);
        mlpLeg.setLpReceipt(Boolean.TRUE);
        NormalizedTransaction transaction = transaction(NormalizedTransactionType.LP_ENTRY, solLeg, mlpLeg);
        transaction.setNetworkId(NetworkId.SOLANA);
        transaction.setCorrelationId("lp-position:solana:meteora-damm:pool:9GrpWallet");
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        // 1) The flag survives the copy-and-replace confirm cycle on the persisted CONFIRMED row.
        NormalizedTransaction.Flow savedMlp = saved.getFlows().stream()
                .filter(f -> mlpMint.equals(f.getAssetContract())).findFirst().orElseThrow();
        assertThat(savedMlp.getLpReceipt()).isTrue();
        // The non-receipt SOL leg is untouched.
        assertThat(saved.getFlows().stream()
                .filter(f -> "SOL".equals(f.getAssetSymbol())).findFirst().orElseThrow()
                .getLpReceipt()).isNull();

        // 2) End-to-end: LedgerPointCollector stamps FAMILY:LP_RECEIPT for the surviving flagged flow.
        java.util.List<com.walletradar.application.costbasis.domain.AssetLedgerPoint> points =
                new java.util.ArrayList<>();
        com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector collector =
                new com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector(
                        "universe-1", points, Instant.now());
        com.walletradar.application.costbasis.application.replay.model.AssetKey mlpKey =
                new com.walletradar.application.costbasis.application.replay.model.AssetKey(
                        "9GrpWallet", NetworkId.SOLANA, mlpMint, "MLP", mlpMint);
        com.walletradar.application.costbasis.application.replay.model.PositionState after =
                new com.walletradar.application.costbasis.application.replay.model.PositionState(mlpKey);
        after.setQuantity(new BigDecimal("0.3096"));
        collector.record(saved, savedMlp, 0, mlpKey,
                com.walletradar.application.costbasis.application.replay.model.PositionSnapshot.mirrorTax(
                        BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0),
                after,
                com.walletradar.application.costbasis.domain.AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getAccountingFamilyIdentity()).isEqualTo("FAMILY:LP_RECEIPT");
    }

    @Test
    void replaySafePromotionPreservesCustodialOffChain() {
        // Symmetric coverage for the promoteReplaySafeNeedsReview() copy path (same copy() method).
        NormalizedTransaction custody = transaction(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.FEE, "TON", "-0.0001", "3", PriceSource.BINANCE)
        );
        custody.setNetworkId(NetworkId.TON);
        custody.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        custody.setCounterpartyType(CounterpartyType.EXTERNAL_CUSTODY);
        custody.setCounterpartyAddress("0:DD6FF02C59634745529B99A8D5BEEEA9F6C38A9188E6A7E96A424E3820C8AC0A");
        custody.setProtocolName("Telegram Wallet");
        custody.setCustodialOffChain(true);
        custody.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        when(normalizedTransactionRepository.findAllActiveAccountingByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                anyCollection(),
                org.mockito.ArgumentMatchers.eq(NormalizedTransactionStatus.NEEDS_REVIEW)
        )).thenReturn(List.of(custody));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        int promoted = service.promoteReplaySafeNeedsReview(List.of("0xwallet"));

        assertThat(promoted).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getCustodialOffChain()).isTrue();
    }

    @Test
    void invalidSwapWithoutBuyLegFallsIntoNeedsReview() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.SELL, "USDC", "-1000", "1", PriceSource.STABLECOIN)
        );
        when(pendingStatQueryService.loadNextBatch(10, 120)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(10, 120);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isZero();
        assertThat(outcome.demotedToNeedsReview()).isEqualTo(1);

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(captor.getValue().getMissingDataReasons()).contains(StatValidationService.SWAP_MISSING_BUY_LEG_REASON);
    }

    @Test
    void crossCanonicalStakingConversionWithoutCounterpartyStillPromotes() {
        // ADR-054 / D1: a Bybit ETH→mETH STAKING_DEPOSIT is an internal identity change with no external
        // counterparty. Now that it is routed to PENDING_PRICE (so the mETH leg gets a market price) it
        // passes through STAT validation and must still CONFIRM — the counterparty presence checks
        // (designed for bridge/internal-transfer linking) do not apply to cross-canonical staking.
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.STAKING_DEPOSIT,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.709", "1880.97", PriceSource.BYBIT),
                flow(NormalizedLegRole.TRANSFER, "METH", "0.66865026", "1993.86", PriceSource.BYBIT)
        );
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setCrossCanonicalStakingConversion(Boolean.TRUE);
        transaction.setCounterpartyType(null);
        transaction.setCounterpartyAddress(null);
        transaction.getFlows().forEach(flow -> {
            flow.setCounterpartyType(null);
            flow.setCounterpartyAddress(null);
        });
        when(pendingStatQueryService.loadNextBatch(10, 120)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(10, 120);

        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        assertThat(outcome.demotedToNeedsReview()).isZero();
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getMissingDataReasons())
                .doesNotContain(
                        StatValidationService.COUNTERPARTY_TYPE_MISSING_REASON,
                        StatValidationService.FLOW_COUNTERPARTY_MISSING_REASON);
    }

    @Test
    void nonStakingTransferWithoutCounterpartyStillDemotes() {
        // Negative control: the counterparty exemption is scoped to the cross-canonical staking flag —
        // an ordinary transfer without counterparty is still demoted to NEEDS_REVIEW.
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.INTERNAL_TRANSFER,
                flow(NormalizedLegRole.TRANSFER, "ETH", "-0.709", "1880.97", PriceSource.BYBIT)
        );
        transaction.setCounterpartyType(null);
        transaction.setCounterpartyAddress(null);
        transaction.getFlows().forEach(flow -> {
            flow.setCounterpartyType(null);
            flow.setCounterpartyAddress(null);
        });
        when(pendingStatQueryService.loadNextBatch(10, 120)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(10, 120);

        assertThat(outcome.demotedToNeedsReview()).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(captor.getValue().getMissingDataReasons())
                .contains(StatValidationService.COUNTERPARTY_TYPE_MISSING_REASON);
    }

    @Test
    void continuityTransferWithoutPrincipalMarketPriceStillPromotes() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-1000", null, null),
                flow(NormalizedLegRole.FEE, "ETH", "-0.001", "2500", PriceSource.BINANCE)
        );
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        assertThat(outcome.demotedToNeedsReview()).isZero();

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getMissingDataReasons()).doesNotContain(StatValidationService.FLOW_PRICE_MISSING_REASON);
    }

    @Test
    void feeOnlyReviewRowStillPromotesToConfirmed() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        transaction.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        assertThat(outcome.demotedToNeedsReview()).isZero();

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getMissingDataReasons()).contains("CLASSIFICATION_FAILED");
        assertThat(captor.getValue().getMissingDataReasons()).doesNotContain(StatValidationService.NO_NON_FEE_FLOW_REASON);
    }

    @Test
    void replaySafeNeedsReviewRowsPromoteWhenTheyAreFeeOnlyOrEmpty() {
        NormalizedTransaction feeOnly = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        feeOnly.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        feeOnly.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        NormalizedTransaction empty = transaction(NormalizedTransactionType.UNKNOWN);
        empty.setId("tx-2");
        empty.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        empty.setFlows(List.of());
        empty.setMissingDataReasons(List.of("ROUTER_METHOD_OVERLOAD_UNSUPPORTED"));

        NormalizedTransaction principal = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-10", "1", PriceSource.STABLECOIN),
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        principal.setId("tx-3");
        principal.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        principal.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        when(normalizedTransactionRepository.findAllActiveAccountingByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                anyCollection(),
                org.mockito.ArgumentMatchers.eq(NormalizedTransactionStatus.NEEDS_REVIEW)
        )).thenReturn(List.of(feeOnly, empty, principal));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        int promoted = service.promoteReplaySafeNeedsReview(List.of("0xwallet"));

        assertThat(promoted).isEqualTo(2);

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NormalizedTransaction::getId)
                .containsExactly("tx-1", "tx-2");
        assertThat(captor.getAllValues())
                .extracting(NormalizedTransaction::getStatus)
                .containsOnly(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    void replaySafePromotionRejectsOnChainDroppedValueButStillPromotesEvmFeeOnly() {
        // RC-T2 (ADR-066 amendment to ADR-014): a TON on-chain UNKNOWN row that dropped real jetton
        // value (fee-only flow + dropped-value marker) must NOT be silently confirmed.
        NormalizedTransaction tonDropped = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "TON", "-0.005", "3", PriceSource.BINANCE)
        );
        tonDropped.setId("ton-1");
        tonDropped.setNetworkId(NetworkId.TON);
        tonDropped.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        tonDropped.setMissingDataReasons(List.of(
                "TON_UNCLASSIFIED", TonNormalizedTransactionBuilder.ONCHAIN_UNRESOLVED_VALUE));

        // EVM fee-only UNKNOWN (no marker): replay-safe promotion must remain unchanged.
        NormalizedTransaction evmFeeOnly = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        evmFeeOnly.setId("evm-1");
        evmFeeOnly.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        evmFeeOnly.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        when(normalizedTransactionRepository.findAllActiveAccountingByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                anyCollection(),
                org.mockito.ArgumentMatchers.eq(NormalizedTransactionStatus.NEEDS_REVIEW)
        )).thenReturn(List.of(tonDropped, evmFeeOnly));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        int promoted = service.promoteReplaySafeNeedsReview(List.of("0xwallet"));

        assertThat(promoted).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("evm-1");
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    void statValidationKeepsOnChainDroppedValueFeeOnlyRowInNeedsReview() {
        NormalizedTransaction tonDropped = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "TON", "-0.005", "3", PriceSource.BINANCE)
        );
        tonDropped.setNetworkId(NetworkId.TON);
        tonDropped.setMissingDataReasons(List.of(
                "TON_UNCLASSIFIED", TonNormalizedTransactionBuilder.ONCHAIN_UNRESOLVED_VALUE));
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(tonDropped));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.promotedToConfirmed()).isZero();
        assertThat(outcome.demotedToNeedsReview()).isEqualTo(1);

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(captor.getValue().getMissingDataReasons()).contains(StatValidationService.NO_NON_FEE_FLOW_REASON);
    }

    private NormalizedTransaction transaction(
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_STAT);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        transaction.setMissingDataReasons(List.of());
        transaction.setFlows(List.of(flows));
        transaction.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        transaction.setCounterpartyAddress("0xcounterparty");
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetSymbol,
            String quantity,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        if (role != NormalizedLegRole.FEE) {
            flow.setCounterpartyAddress("0xcounterparty");
            flow.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        }
        return flow;
    }
}
