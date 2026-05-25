package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcrossBridgePairLinkServiceTest {

    private static final String WALLET = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private AcrossBridgePairLinkService service;

    @BeforeEach
    void setUp() {
        service = new AcrossBridgePairLinkService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("high-confidence same-wallet Across pair receives bridge continuity metadata")
    void highConfidenceSameWalletAcrossPairReceivesBridgeContinuityMetadata() {
        NormalizedTransaction source = bridgeOut(
                "0x8fc7da0a6aba524098b75fb9c1bfa651b4b50a90850832393c1313a745ac1e13",
                NetworkId.ARBITRUM,
                "USDC",
                "-641.214425"
        );
        NormalizedTransaction destination = bridgeIn(
                "0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3",
                NetworkId.UNICHAIN,
                "USDC",
                "641.146308",
                source.getBlockTimestamp().plusSeconds(4)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(destination));

        boolean changed = service.link(source);

        assertThat(changed).isTrue();
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:across:" + source.getTxHash());
        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getContinuityCandidate()).isTrue();
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getContinuityCandidate()).isTrue();
        assertThat(source.getFlows()).filteredOn(f -> f.getRole() != NormalizedLegRole.FEE)
                .allSatisfy(flow -> {
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                    assertThat(flow.getUnitPriceUsd()).isNull();
                });
    }

    @Test
    @DisplayName("retags priced SELL outbound leg to TRANSFER when continuity is established")
    void retagsPricedSellOutboundToTransferWhenContinuityEstablished() {
        NormalizedTransaction source = bridgeOut(
                "0x8fc7da0a6aba524098b75fb9c1bfa651b4b50a90850832393c1313a745ac1e13",
                NetworkId.ARBITRUM,
                "USDC",
                "-641.214425"
        );
        source.getFlows().getFirst().setRole(NormalizedLegRole.SELL);
        source.getFlows().getFirst().setUnitPriceUsd(new BigDecimal("1.0"));

        NormalizedTransaction destination = bridgeIn(
                "0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3",
                NetworkId.UNICHAIN,
                "USDC",
                "641.146308",
                source.getBlockTimestamp().plusSeconds(4)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(destination));

        assertThat(service.link(source)).isTrue();
        assertThat(source.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(source.getFlows().getFirst().getUnitPriceUsd()).isNull();
        assertThat(source.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("ambiguous candidate set does not auto-link Across bridge continuity")
    void ambiguousCandidateSetDoesNotAutoLinkAcrossBridgeContinuity() {
        NormalizedTransaction source = bridgeOut(
                "0xec9b4c23e62b8f38579fd8b4fedd9af13e08c57bcf724e3656c510a55183cc9a",
                NetworkId.UNICHAIN,
                "USDC",
                "-513.283613"
        );
        NormalizedTransaction firstDestination = bridgeIn(
                "0xf3398033d0969bcd922271bceb6b6472a0a7663ab93ae6f0135cb139eed42aaf",
                NetworkId.ARBITRUM,
                "USDC",
                "513.226620",
                source.getBlockTimestamp().plusSeconds(2)
        );
        NormalizedTransaction secondDestination = bridgeIn(
                "0xsecondcandidate",
                NetworkId.BASE,
                "USDC",
                "513.210000",
                source.getBlockTimestamp().plusSeconds(3)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(firstDestination, secondDestination));

        boolean changed = service.link(source);

        assertThat(changed).isFalse();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getMatchedCounterparty()).isNull();
        assertThat(firstDestination.getMatchedCounterparty()).isNull();
    }

    @Test
    @DisplayName("same-wallet Across destination can promote bounded external inbound row into BRIDGE_IN")
    void sameWalletAcrossDestinationCanPromoteBoundedExternalInboundRowIntoBridgeIn() {
        NormalizedTransaction source = bridgeOut(
                "0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966",
                NetworkId.ZKSYNC,
                "ETH",
                "-0.689595000000000000"
        );
        NormalizedTransaction destination = externalInbound(
                "0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41",
                NetworkId.ARBITRUM,
                "ETH",
                "0.689498081026196974",
                source.getBlockTimestamp().plusSeconds(11)
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(source));

        boolean changed = service.link(destination);

        assertThat(changed).isTrue();
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getProtocolName()).isEqualTo("Across");
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo("bridge:across:" + source.getTxHash());
        assertThat(destination.getContinuityCandidate()).isTrue();
        assertThat(destination.getFlows()).allSatisfy(flow -> {
            if (flow.getRole() != NormalizedLegRole.FEE) {
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
            }
        });
    }

    private NormalizedTransaction bridgeOut(String txHash, NetworkId networkId, String symbol, String qty) {
        NormalizedTransaction transaction = base(txHash, networkId, NormalizedTransactionType.BRIDGE_OUT, Instant.parse("2026-03-31T10:15:00Z"));
        transaction.setProtocolName("Across");
        transaction.setFlows(List.of(flow(symbol, qty)));
        return transaction;
    }

    private NormalizedTransaction bridgeIn(
            String txHash,
            NetworkId networkId,
            String symbol,
            String qty,
            Instant blockTimestamp
    ) {
        NormalizedTransaction transaction = base(txHash, networkId, NormalizedTransactionType.BRIDGE_IN, blockTimestamp);
        transaction.setFlows(List.of(flow(symbol, qty)));
        return transaction;
    }

    private NormalizedTransaction externalInbound(
            String txHash,
            NetworkId networkId,
            String symbol,
            String qty,
            Instant blockTimestamp
    ) {
        NormalizedTransaction transaction = base(txHash, networkId, NormalizedTransactionType.EXTERNAL_TRANSFER_IN, blockTimestamp);
        transaction.setFlows(List.of(flow(symbol, qty)));
        return transaction;
    }

    private NormalizedTransaction base(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            Instant blockTimestamp
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":" + networkId + ":" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(networkId);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setBlockTimestamp(blockTimestamp);
        transaction.setTransactionIndex(1);
        return transaction;
    }

    private NormalizedTransaction.Flow flow(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
