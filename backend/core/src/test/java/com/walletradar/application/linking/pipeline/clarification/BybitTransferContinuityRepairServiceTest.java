package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.session.application.AccountingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitTransferContinuityRepairServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    // Cycle/7 S3: Bybit deposit-receiving wallet always carries the sub-account suffix (FUND for
    // external corridor) in production. The repair service now propagates this suffix to the
    // on-chain counterpartyAddress / matchedCounterparty.
    private static final String BYBIT = "BYBIT:33625378:FUND";
    private static final String TX_HASH = "0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    private BybitTransferContinuityRepairService service;

    @BeforeEach
    void setUp() {
        service = new BybitTransferContinuityRepairService(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountingUniverseService.shareUniverseMembers(WALLET, BYBIT)).thenReturn(true);
        lenient().when(accountingUniverseService.shareUniverseMembers(BYBIT, WALLET)).thenReturn(true);
    }

    @Test
    @DisplayName("matched Bybit deposit repairs missing on-chain continuity metadata after rerun")
    void matchedBybitDepositRepairsMissingOnChainContinuityMetadataAfterRerun() {
        NormalizedTransaction onChain = onChainRow();
        NormalizedTransaction bybit = bybitRow();

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getWalletAddress)
                .containsExactlyInAnyOrder(WALLET, BYBIT);

        assertThat(onChain.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(onChain.getCounterpartyType()).isEqualTo(CounterpartyType.CEX);
        assertThat(onChain.getCounterpartyAddress()).isEqualTo(BYBIT);
        assertThat(onChain.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(onChain.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        assertThat(onChain.getContinuityCandidate()).isTrue();
        assertThat(onChain.getMatchedCounterparty()).isEqualTo(BYBIT);
        assertThat(onChain.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(onChain.getFlows().getFirst().getCounterpartyType()).isEqualTo(CounterpartyType.CEX);
        assertThat(onChain.getFlows().getFirst().getCounterpartyAddress()).isEqualTo(BYBIT);
        assertThat(onChain.getFlows().getFirst().getUnitPriceUsd()).isNull();

        assertThat(bybit.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(bybit.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        assertThat(bybit.getContinuityCandidate()).isTrue();
        assertThat(bybit.getMatchedCounterparty()).isEqualTo(WALLET);
    }

    @Test
    @DisplayName("Bybit NEEDS_REVIEW custody row promotes to confirmed after successful pairing")
    void bybitNeedsReviewCustodyRowPromotesToConfirmedAfterSuccessfulPairing() {
        NormalizedTransaction onChain = onChainRow();
        onChain.setStatus(NormalizedTransactionStatus.CONFIRMED);
        NormalizedTransaction bybit = bybitRow();
        bybit.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        bybit.setCorrelationId(null);
        bybit.setContinuityCandidate(false);
        bybit.setMatchedCounterparty(null);
        bybit.setMissingDataReasons(new java.util.ArrayList<>(List.of("EXTERNAL_CUSTODY_UNTRACKED_VENUE")));
        bybit.setConfirmedAt(null);
        bybit.setExcludedFromAccounting(true);
        bybit.setAccountingExclusionReason("EXTERNAL_CUSTODY_UNTRACKED_VENUE");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());
        assertThat(bybit.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(bybit.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        assertThat(bybit.getContinuityCandidate()).isTrue();
        assertThat(bybit.getMatchedCounterparty()).isEqualTo(WALLET);
        assertThat(bybit.getMissingDataReasons()).doesNotContain("EXTERNAL_CUSTODY_UNTRACKED_VENUE");
        assertThat(bybit.getConfirmedAt()).isNull();
        assertThat(bybit.getExcludedFromAccounting()).isFalse();
        assertThat(bybit.getAccountingExclusionReason()).isNull();
    }

    @Test
    @DisplayName("same-sign mirror excluded Bybit corridor leg re-pairs after internal pairer demotion")
    void sameSignMirrorExcludedCorridorLegRePairsAfterInternalPairerDemotion() {
        NormalizedTransaction onChain = onChainRow();
        onChain.setStatus(NormalizedTransactionStatus.CONFIRMED);
        NormalizedTransaction bybit = bybitRow();
        bybit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        bybit.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        bybit.setContinuityCandidate(false);
        bybit.setMatchedCounterparty(WALLET);
        bybit.setExcludedFromAccounting(true);
        bybit.setAccountingExclusionReason(
                com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferPairer.SAME_SIGN_MIRROR_REASON
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        assertThat(bybit.getExcludedFromAccounting()).isFalse();
        assertThat(bybit.getAccountingExclusionReason()).isNull();
        assertThat(bybit.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("one-wei dust transfer without Bybit row is ignored")
    void oneWeiDustTransferWithoutBybitRowIsIgnored() {
        NormalizedTransaction onChain = onChainRow();
        onChain.setId("on-chain-dust");
        onChain.setTxHash("0xcce37c54e31867ed9bca2f34ffbfdbb62329811896b9e60f2ae087a4d2b41e67");
        onChain.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        onChain.getFlows().getFirst().setQuantityDelta(new BigDecimal("0.000000000000000001"));
        onChain.getFlows().getFirst().setRole(NormalizedLegRole.BUY);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                onChain.getTxHash(),
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of());

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(onChain.getCorrelationId()).isNull();
        assertThat(onChain.getMatchedCounterparty()).isNull();
    }

    @Test
    @DisplayName("wallet to Bybit repair is skipped across different accounting universes")
    void walletToBybitRepairIsSkippedAcrossDifferentAccountingUniverses() {
        NormalizedTransaction onChain = onChainRow();
        NormalizedTransaction bybit = bybitRow();

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));
        when(accountingUniverseService.shareUniverseMembers(WALLET, BYBIT)).thenReturn(false);

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(onChain.getCorrelationId()).isNull();
        assertThat(onChain.getMatchedCounterparty()).isNull();
    }

    @Test
    @DisplayName("excluded Bybit custody row with unrelated reason is not revived by repair")
    void excludedBybitCustodyRowWithUnrelatedReasonIsNotRevivedByRepair() {
        NormalizedTransaction onChain = onChainRow();
        NormalizedTransaction bybit = bybitRow();
        bybit.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        bybit.setExcludedFromAccounting(true);
        bybit.setAccountingExclusionReason("BYBIT_TRANSFER_SHADOW_ROW");
        bybit.setMissingDataReasons(new java.util.ArrayList<>(List.of("BYBIT_TRANSFER_SHADOW_ROW")));
        // Clear the BYBIT-CORRIDOR correlationId so hasBybitCorridorCorrelation returns false
        // and isRepairableExcludedBybitLeg correctly returns false for this unrelated reason.
        bybit.setCorrelationId(null);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("misclassified BRIDGE_OUT on-chain leg pairs with Bybit deposit and rewrites to INTERNAL_TRANSFER")
    void misclassifiedBridgeOutOnChainLegPairsWithBybitDeposit() {
        NormalizedTransaction onChain = onChainRow();
        onChain.setType(NormalizedTransactionType.BRIDGE_OUT);
        onChain.setCounterpartyAddress("MULTI");
        onChain.getFlows().getFirst().setRole(NormalizedLegRole.TRANSFER);
        onChain.getFlows().getFirst().setCounterpartyAddress("0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d");
        NormalizedTransaction bybit = bybitRow();
        bybit.setCorrelationId(null);
        bybit.setContinuityCandidate(false);
        bybit.setMatchedCounterparty(null);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        assertThat(onChain.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(onChain.getCounterpartyAddress()).isEqualTo(BYBIT);
        assertThat(bybit.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    @Test
    @DisplayName("RC-9 T-9/T-10: canonical leg + corrId are order-independent and idempotent across reruns")
    void canonicalLegSelectionIsOrderIndependentAndIdempotent() {
        String expectedCorr = "BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH;

        // Full rebuild order: [lowId, highId].
        NormalizedTransaction onChainA = onChainRow();
        NormalizedTransaction lowA = unpairedBybitRow("bybit-id-1");
        NormalizedTransaction highA = unpairedBybitRow("bybit-id-2");
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH, NetworkId.ARBITRUM, NormalizedTransactionSource.BYBIT))
                .thenReturn(List.of(lowA, highA));

        assertThat(service.repair(onChainA)).isTrue();
        assertThat(onChainA.getCorrelationId()).isEqualTo(expectedCorr);
        // Lowest _id is the canonical leg; it alone is stamped.
        assertThat(lowA.getCorrelationId()).isEqualTo(expectedCorr);
        assertThat(lowA.getMatchedCounterparty()).isEqualTo(WALLET);
        assertThat(highA.getCorrelationId()).isNull();

        // Incremental refresh re-materialises the same legs in reversed order: [highId, lowId].
        NormalizedTransaction onChainB = onChainRow();
        NormalizedTransaction lowB = unpairedBybitRow("bybit-id-1");
        NormalizedTransaction highB = unpairedBybitRow("bybit-id-2");
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH, NetworkId.ARBITRUM, NormalizedTransactionSource.BYBIT))
                .thenReturn(List.of(highB, lowB));

        assertThat(service.repair(onChainB)).isTrue();
        // T-9: identical corridor correlation + canonical leg regardless of materialisation order.
        assertThat(onChainB.getCorrelationId()).isEqualTo(expectedCorr);
        assertThat(lowB.getCorrelationId()).isEqualTo(expectedCorr);
        assertThat(lowB.getMatchedCounterparty()).isEqualTo(WALLET);
        assertThat(highB.getCorrelationId()).isNull();

        // T-10: re-stamping over already-stamped legs is value-idempotent — N further refresh
        // passes leave the corridor correlation + canonical-leg pairing bit-identical.
        service.repair(onChainB);
        service.repair(onChainB);
        assertThat(onChainB.getCorrelationId()).isEqualTo(expectedCorr);
        assertThat(onChainB.getMatchedCounterparty()).isEqualTo(BYBIT);
        assertThat(lowB.getCorrelationId()).isEqualTo(expectedCorr);
        assertThat(lowB.getMatchedCounterparty()).isEqualTo(WALLET);
        assertThat(highB.getCorrelationId()).isNull();
    }

    private NormalizedTransaction unpairedBybitRow(String id) {
        NormalizedTransaction transaction = bybitRow();
        transaction.setId(id);
        transaction.setCorrelationId(null);
        transaction.setContinuityCandidate(false);
        transaction.setMatchedCounterparty(null);
        return transaction;
    }

    @Test
    @DisplayName("Dzengi deposit without networkId pairs to on-chain row by txHash")
    void dzengiDepositWithoutNetworkIdPairsToOnChainByTxHash() {
        String dzengiRef = "DZENGI:user123";
        NormalizedTransaction onChain = onChainRow();
        onChain.setCorrelationId(null);
        onChain.setContinuityCandidate(false);
        onChain.setMatchedCounterparty(null);

        NormalizedTransaction dzengi = dzengiRow();
        dzengi.setWalletAddress(dzengiRef);
        dzengi.setNetworkId(null);
        dzengi.setCorrelationId(null);
        dzengi.setContinuityCandidate(false);
        dzengi.setMatchedCounterparty(null);

        when(accountingUniverseService.shareUniverseMembers(WALLET, dzengiRef)).thenReturn(true);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(onChain), List.of(), List.of(dzengi));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of());
        when(normalizedTransactionRepository.findAllByTxHashAndSource(
                TX_HASH,
                NormalizedTransactionSource.DZENGI
        )).thenReturn(List.of(dzengi));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        assertThat(onChain.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        assertThat(onChain.getMatchedCounterparty()).isEqualTo(dzengiRef);
        assertThat(dzengi.getMatchedCounterparty()).isEqualTo(WALLET);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    private NormalizedTransaction onChainRow() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("on-chain");
        transaction.setTxHash(TX_HASH);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-03-12T10:00:00Z"));
        transaction.setTransactionIndex(1);
        transaction.setFlows(List.of(flow("-3.06", NormalizedLegRole.SELL)));
        return transaction;
    }

    private NormalizedTransaction bybitRow() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("bybit");
        transaction.setTxHash(TX_HASH);
        transaction.setWalletAddress(BYBIT);
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        transaction.setContinuityCandidate(true);
        transaction.setMatchedCounterparty(WALLET);
        transaction.setBlockTimestamp(Instant.parse("2026-03-12T10:00:01Z"));
        transaction.setTransactionIndex(0);
        transaction.setFlows(List.of(flow("3.06", NormalizedLegRole.BUY)));
        return transaction;
    }

    private NormalizedTransaction dzengiRow() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("dzengi");
        transaction.setTxHash(TX_HASH);
        transaction.setWalletAddress("DZENGI:user123");
        transaction.setSource(NormalizedTransactionSource.DZENGI);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(Instant.parse("2026-03-12T10:00:01Z"));
        transaction.setTransactionIndex(0);
        transaction.setFlows(List.of(flow("3.06", NormalizedLegRole.BUY)));
        return transaction;
    }

    @Test
    @DisplayName("Solana corridor pairs on case-sensitive base58 signatures")
    void solanaCorridorPairsOnCaseSensitiveBase58Signatures() {
        String solanaSignature = "3xKzVeUuM7g5q5Z2nNuG6XJoYwYz5Z9Wxq8KkFaWQRrQRSyDpL77UvBASE58CaseSensitive";
        String walletSolana = "9hVgwTW4cKvGZJsW6ZkxnyKxKjVnzhgUgYr3D4yX2Lj8";
        // Quantity match between on-chain leg and Bybit FH leg is asserted within the existing
        // RELATIVE_QTY_TOLERANCE (5×10⁻⁴); we use identical gross amounts here because the goal of
        // this test is to verify that case-sensitive base58 txHash matching pairs them — fee-grossed
        // quantity tolerance is exercised separately by BybitExtractionServiceTest.
        NormalizedTransaction onChain = new NormalizedTransaction();
        onChain.setId("on-chain-sol");
        onChain.setTxHash(solanaSignature);
        onChain.setWalletAddress(walletSolana);
        onChain.setSource(NormalizedTransactionSource.ON_CHAIN);
        onChain.setNetworkId(NetworkId.SOLANA);
        onChain.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        onChain.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        onChain.setBlockTimestamp(Instant.parse("2026-03-25T07:50:14Z"));
        onChain.setTransactionIndex(0);
        onChain.setFlows(List.of(flowFor("SOL", "-0.6", NormalizedLegRole.SELL)));

        NormalizedTransaction bybit = new NormalizedTransaction();
        bybit.setId("bybit-sol");
        bybit.setTxHash(solanaSignature);
        bybit.setWalletAddress("BYBIT:33625378:FUND");
        bybit.setSource(NormalizedTransactionSource.BYBIT);
        bybit.setNetworkId(NetworkId.SOLANA);
        bybit.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        bybit.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        bybit.setBlockTimestamp(Instant.parse("2026-03-25T07:50:16Z"));
        bybit.setTransactionIndex(0);
        bybit.setFlows(List.of(flowFor("SOL", "0.6", NormalizedLegRole.BUY)));

        when(accountingUniverseService.shareUniverseMembers(walletSolana, "BYBIT:33625378:FUND")).thenReturn(true);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                solanaSignature,
                NetworkId.SOLANA,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        assertThat(onChain.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:SOLANA:" + solanaSignature);
        assertThat(onChain.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(bybit.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:SOLANA:" + solanaSignature);
    }

    @Test
    @DisplayName("withdrawal fee drift pairs when wallet endpoint matches")
    void withdrawalFeeDriftPairsWhenWalletEndpointMatches() {
        String txHash = "0xabc123feeDriftEth";
        NormalizedTransaction onChain = onChainRow();
        onChain.setTxHash(txHash);
        onChain.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        onChain.getFlows().getFirst().setQuantityDelta(new BigDecimal("-0.02743"));
        onChain.getFlows().getFirst().setRole(NormalizedLegRole.SELL);

        NormalizedTransaction bybit = bybitRow();
        bybit.setTxHash(txHash);
        bybit.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        bybit.setCorrelationId(null);
        bybit.setContinuityCandidate(false);
        bybit.setMatchedCounterparty(null);
        bybit.setCounterpartyAddress(WALLET);
        bybit.getFlows().getFirst().setQuantityDelta(new BigDecimal("0.02739"));
        bybit.getFlows().getFirst().setRole(NormalizedLegRole.BUY);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(), List.of(bybit));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());
        assertThat(onChain.getCorrelationId()).isEqualTo("BYBIT-CORRIDOR:ARBITRUM:" + txHash.toLowerCase());
        assertThat(bybit.getMatchedCounterparty()).isEqualTo(WALLET);
    }

    @Test
    @DisplayName("USDC withdrawal fee one unit pairs when wallet endpoint matches")
    void usdcWithdrawalFeeOneUnitPairs() {
        String txHash = "0xabc123feeDriftUsdc";
        NormalizedTransaction onChain = onChainRow();
        onChain.setTxHash(txHash);
        onChain.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        onChain.getFlows().getFirst().setAssetSymbol("USDC");
        onChain.getFlows().getFirst().setQuantityDelta(new BigDecimal("-2"));
        onChain.getFlows().getFirst().setRole(NormalizedLegRole.SELL);

        NormalizedTransaction bybit = bybitRow();
        bybit.setTxHash(txHash);
        bybit.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        bybit.setCorrelationId(null);
        bybit.setContinuityCandidate(false);
        bybit.setMatchedCounterparty(null);
        bybit.setCounterpartyAddress(WALLET);
        bybit.getFlows().getFirst().setAssetSymbol("USDC");
        bybit.getFlows().getFirst().setQuantityDelta(new BigDecimal("1"));
        bybit.getFlows().getFirst().setRole(NormalizedLegRole.BUY);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(), List.of(bybit));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("multiple on-chain same tx ambiguous without endpoint match does not pair")
    void multipleOnChainSameTxAmbiguousWithoutEndpointMatchDoesNotPair() {
        String txHash = "0xabc123ambiguousOnChain";
        NormalizedTransaction onChainA = onChainRow();
        onChainA.setId("on-chain-a");
        onChainA.setTxHash(txHash);
        onChainA.setWalletAddress("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        onChainA.getFlows().getFirst().setQuantityDelta(new BigDecimal("-1.10"));

        NormalizedTransaction onChainB = onChainRow();
        onChainB.setId("on-chain-b");
        onChainB.setTxHash(txHash);
        onChainB.setWalletAddress("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        onChainB.getFlows().getFirst().setQuantityDelta(new BigDecimal("-1.10"));

        NormalizedTransaction bybit = bybitRow();
        bybit.setTxHash(txHash);
        bybit.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        bybit.setCorrelationId(null);
        bybit.setContinuityCandidate(false);
        bybit.setMatchedCounterparty(null);
        bybit.setCounterpartyAddress(null);
        bybit.getFlows().getFirst().setQuantityDelta(new BigDecimal("1.00"));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(), List.of(bybit));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(onChainA, onChainB));
        when(accountingUniverseService.shareUniverseMembers("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", BYBIT))
                .thenReturn(true);
        when(accountingUniverseService.shareUniverseMembers("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", BYBIT))
                .thenReturn(true);

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("already paired Bybit corridor externals are reclassified to INTERNAL_TRANSFER")
    void alreadyPairedBybitCorridorExternalsAreReclassifiedToInternalTransfer() {
        NormalizedTransaction bybit = bybitRow();
        bybit.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        bybit.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:" + TX_HASH);
        bybit.setMatchedCounterparty(WALLET);
        bybit.setContinuityCandidate(true);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(), List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        assertThat(bybit.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    private NormalizedTransaction.Flow flow(String quantity, NormalizedLegRole role) {
        return flowFor("ETH", quantity, role);
    }

    private NormalizedTransaction.Flow flowFor(String assetSymbol, String quantity, NormalizedLegRole role) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(role);
        return flow;
    }
}
