package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnmatchedExternalTransferInPricingFallbackServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String PEER = "0x2222222222222222222222222222222222222222";
    private static final String BYBIT_FUND = "BYBIT:33625378:FUND";

    @Mock
    private MongoOperations mongoOperations;

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private UnmatchedExternalTransferInPricingFallbackService service;

    private List<NormalizedTransaction> stubbedOnChainInbounds = new ArrayList<>();
    private List<NormalizedTransaction> stubbedBybitInbounds = new ArrayList<>();
    private List<NormalizedTransaction> stubbedOutbounds = new ArrayList<>();
    private List<NormalizedTransaction> stubbedInternals = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new UnmatchedExternalTransferInPricingFallbackService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        wireFindStub();
    }

    @Test
    @DisplayName("orphan on-chain EXTERNAL_TRANSFER_IN without paired EXTERNAL_TRANSFER_OUT is flipped to pending-price BUY")
    void orphanInboundIsResetForMarketPricing() {
        NormalizedTransaction orphan = onChainInbound("0xhash-orphan", "ETH", "0.5", NetworkId.ETHEREUM);
        stubbedOnChainInbounds = List.of(orphan);
        stubbedOutbounds = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(orphan.getConfirmedAt()).isNull();
        assertThat(orphan.getContinuityCandidate()).isFalse();
        assertThat(orphan.getFlows()).anySatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY));

        ArgumentCaptor<List<NormalizedTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).containsExactly(orphan);
    }

    @Test
    @DisplayName("on-chain inbound with peer EXTERNAL_TRANSFER_OUT on same hash + network is left alone")
    void pairedInboundIsNotReset() {
        NormalizedTransaction paired = onChainInbound("0xhash-pair", "USDC", "100", NetworkId.ARBITRUM);
        NormalizedTransaction peer = outbound("0xhash-pair", NetworkId.ARBITRUM, PEER);
        stubbedOnChainInbounds = List.of(paired);
        stubbedOutbounds = List.of(peer);

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isZero();
        assertThat(paired.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(paired.getContinuityCandidate()).isTrue();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("outbound on a different network does not match — on-chain orphan is still reset")
    void crossNetworkOutboundDoesNotPair() {
        NormalizedTransaction orphan = onChainInbound("0xhash-xchain", "USDT", "50", NetworkId.ETHEREUM);
        NormalizedTransaction wrongNetworkPeer = outbound("0xhash-xchain", NetworkId.ARBITRUM, PEER);
        stubbedOnChainInbounds = List.of(orphan);
        stubbedOutbounds = List.of(wrongNetworkPeer);

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(orphan.getContinuityCandidate()).isFalse();
    }

    @Test
    @DisplayName("Bybit EXTERNAL_TRANSFER_IN without INTERNAL_TRANSFER peer is flipped to pending-price BUY")
    void bybitOrphanInboundIsResetForMarketPricing() {
        NormalizedTransaction orphan = bybitInbound("bybit-orphan-corr", "LDO", "378");
        stubbedBybitInbounds = List.of(orphan);
        stubbedInternals = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(orphan.getContinuityCandidate()).isFalse();
        assertThat(orphan.getFlows()).anySatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY));
        assertThat(orphan.getCorrelationId()).isEqualTo("bybit-orphan-corr");
    }

    @Test
    @DisplayName("Bybit inbound with matching INTERNAL_TRANSFER on same correlationId is preserved")
    void bybitPairedInboundIsNotReset() {
        NormalizedTransaction paired = bybitInbound("bybit-pair-corr", "ETH", "1.5");
        NormalizedTransaction internalPeer = internalTransfer("bybit-pair-corr");
        stubbedBybitInbounds = List.of(paired);
        stubbedInternals = List.of(internalPeer);

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isZero();
        assertThat(paired.getContinuityCandidate()).isTrue();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("no candidates yields a no-op pass")
    void noOpWhenNoCandidates() {
        stubbedOnChainInbounds = List.of();
        stubbedBybitInbounds = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    private void wireFindStub() {
        lenient().when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    String queryString = query.getQueryObject().toString();
                    if (queryString.contains("EXTERNAL_TRANSFER_IN") && queryString.contains("ON_CHAIN")) {
                        return new ArrayList<>(stubbedOnChainInbounds);
                    }
                    if (queryString.contains("EXTERNAL_TRANSFER_IN") && queryString.contains("BYBIT")) {
                        return new ArrayList<>(stubbedBybitInbounds);
                    }
                    if (queryString.contains("EXTERNAL_TRANSFER_OUT")) {
                        return new ArrayList<>(stubbedOutbounds);
                    }
                    if (queryString.contains("INTERNAL_TRANSFER")) {
                        return new ArrayList<>(stubbedInternals);
                    }
                    return new ArrayList<>();
                });
    }

    private NormalizedTransaction onChainInbound(String txHash, String asset, String quantity, NetworkId networkId) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(txHash + ":in");
        tx.setWalletAddress(WALLET);
        tx.setMatchedCounterparty(PEER);
        tx.setNetworkId(networkId);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setContinuityCandidate(true);
        tx.setConfirmedAt(Instant.parse("2024-01-01T00:00:00Z"));
        tx.setBlockTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        tx.setTxHash(txHash);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction bybitInbound(String correlationId, String asset, String quantity) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(correlationId + ":bybit-in");
        tx.setWalletAddress(BYBIT_FUND);
        tx.setMatchedCounterparty(PEER);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId(correlationId);
        tx.setCounterpartyAddress(PEER);
        tx.setConfirmedAt(Instant.parse("2024-01-01T00:00:00Z"));
        tx.setBlockTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        tx.setTxHash("0xbybit-" + correlationId.hashCode());

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction outbound(String txHash, NetworkId networkId, String wallet) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(txHash + ":out");
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId);
        tx.setWalletAddress(wallet);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        return tx;
    }

    private NormalizedTransaction internalTransfer(String correlationId) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(correlationId + ":internal");
        tx.setCorrelationId(correlationId);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setWalletAddress(WALLET);
        return tx;
    }
}
