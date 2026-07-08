package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.support.GmxEventTopicSupport;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainLifecycleLinkServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private OnChainLifecycleLinkService service;

    @BeforeEach
    void setUp() {
        service = new OnChainLifecycleLinkService(normalizedTransactionRepository);
    }

    @Test
    @DisplayName("exact async pair gets bidirectional matched counterparty linkage")
    void exactAsyncPairGetsBidirectionalMatchedCounterpartyLinkage() {
        RawTransaction rawTransaction = rawTransaction("0x9774", NetworkId.ARBITRUM, WALLET);
        NormalizedTransaction settlement = normalized(
                "settlement-id",
                "0x9774",
                NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                "0xbdc08",
                WALLET,
                NetworkId.ARBITRUM
        );
        NormalizedTransaction request = normalized(
                "request-id",
                "0xa83c",
                NormalizedTransactionType.LP_EXIT_REQUEST,
                "0xbdc08",
                WALLET,
                NetworkId.ARBITRUM
        );

        when(normalizedTransactionRepository.findAllByCorrelationIdInAndSourceAndWalletAddressAndNetworkId(
                eq(Set.of("0xbdc08")),
                eq(NormalizedTransactionSource.ON_CHAIN),
                eq(WALLET),
                eq(NetworkId.ARBITRUM)
        )).thenReturn(List.of(request));
        when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.link(rawTransaction, settlement);

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        List<NormalizedTransaction> updates = updatesCaptor.getValue();

        assertThat(settlement.getMatchedCounterparty()).isEqualTo("0xa83c");
        assertThat(request.getMatchedCounterparty()).isEqualTo("0x9774");
        assertThat(updates)
                .extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder("0x9774", "0xa83c");
    }

    @Test
    @DisplayName("accepted asymmetric GMX derivative sibling request still links to terminal tx")
    void acceptedAsymmetricGmxDerivativeSiblingRequestStillLinksToTerminalTx() {
        RawTransaction rawTransaction = rawTransaction("0x53bb", NetworkId.ARBITRUM, WALLET);
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceipt", new Document("logs", List.of(
                        eventEmitterLog("OrderExecuted", "0x8185"),
                        eventEmitterLog("OrderCancelled", "0x3231")
                ))));

        NormalizedTransaction terminal = normalized(
                "terminal-id",
                "0x53bb",
                NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
                "0x8185",
                WALLET,
                NetworkId.ARBITRUM
        );
        NormalizedTransaction primaryRequest = normalized(
                "primary-id",
                "0xf8d8",
                NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                "0x8185",
                WALLET,
                NetworkId.ARBITRUM
        );
        NormalizedTransaction siblingRequest = normalized(
                "sibling-id",
                "0x2c46",
                NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                "0x3231",
                WALLET,
                NetworkId.ARBITRUM
        );
        terminal.setProtocolName("GMX");
        primaryRequest.setProtocolName("GMX");
        siblingRequest.setProtocolName("GMX");

        when(normalizedTransactionRepository.findAllByCorrelationIdInAndSourceAndWalletAddressAndNetworkId(
                eq(Set.of("0x8185", "0x3231")),
                eq(NormalizedTransactionSource.ON_CHAIN),
                eq(WALLET),
                eq(NetworkId.ARBITRUM)
        )).thenReturn(List.of(primaryRequest, siblingRequest));
        when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.link(rawTransaction, terminal);

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        List<NormalizedTransaction> updates = updatesCaptor.getValue();

        assertThat(terminal.getMatchedCounterparty()).isEqualTo("0xf8d8");
        assertThat(primaryRequest.getMatchedCounterparty()).isEqualTo("0x53bb");
        assertThat(siblingRequest.getMatchedCounterparty()).isEqualTo("0x53bb");
        assertThat(updates)
                .extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder("0x53bb", "0xf8d8", "0x2c46");
    }

    private RawTransaction rawTransaction(String txHash, NetworkId networkId, String walletAddress) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId + ":" + walletAddress);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(walletAddress);
        rawTransaction.setRawData(new Document("logs", List.of()));
        return rawTransaction;
    }

    private NormalizedTransaction normalized(
            String id,
            String txHash,
            NormalizedTransactionType type,
            String correlationId,
            String walletAddress,
            NetworkId networkId
    ) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(id);
        normalizedTransaction.setTxHash(txHash);
        normalizedTransaction.setType(type);
        normalizedTransaction.setCorrelationId(correlationId);
        normalizedTransaction.setWalletAddress(walletAddress);
        normalizedTransaction.setNetworkId(networkId);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.parse("2026-03-28T00:00:00Z"));
        normalizedTransaction.setTransactionIndex(1);
        return normalizedTransaction;
    }

    private Document eventEmitterLog(String eventName, String correlationId) {
        return new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                .append("topics", List.of(
                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                        GmxEventTopicSupport.topicHash(eventName),
                        correlationId,
                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                ))
                .append("eventName", eventName);
    }
}
