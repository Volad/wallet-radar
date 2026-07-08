package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.support.CowSwapSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.bson.Document;

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
class CowSwapEthFlowSettlementLinkServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String REQUEST_HASH = "0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105";
    private static final String SETTLEMENT_HASH = "0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private CowSwapEthFlowSettlementLinkService service;

    @BeforeEach
    void setUp() {
        service = new CowSwapEthFlowSettlementLinkService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(false);
    }

    @Test
    @DisplayName("GPv2 settlement inflow links to preceding CoW Eth Flow request")
    void gpv2SettlementInflowLinksToPrecedingCowEthFlowRequest() {
        String expectedCorrelation = CowSwapSupport.resolveEthFlowCorrelationId(
                OnChainRawTransactionView.wrap(ethFlowRequestRaw())
        );
        NormalizedTransaction request = dexOrderRequest(REQUEST_HASH, Instant.parse("2026-01-01T10:00:00Z"), expectedCorrelation);
        NormalizedTransaction settlement = externalSettlementIn(
                SETTLEMENT_HASH,
                Instant.parse("2026-01-01T10:05:00Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of(request));

        int linked = service.linkOutstandingSettlements(25);

        assertThat(linked).isEqualTo(1);
        verify(normalizedTransactionRepository, org.mockito.Mockito.atLeastOnce()).save(any());
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
        assertThat(settlement.getProtocolName()).isEqualTo(CowSwapSupport.PROTOCOL_NAME);
        assertThat(settlement.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(settlement.getMatchedCounterparty()).isEqualTo(REQUEST_HASH);
        assertThat(settlement.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(settlement.getFlows().getFirst().getUnitPriceUsd()).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("settlement does not link when multiple outstanding requests exist")
    void settlementDoesNotLinkWhenMultipleOutstandingRequestsExist() {
        NormalizedTransaction settlement = externalSettlementIn(
                SETTLEMENT_HASH,
                Instant.parse("2026-01-01T10:05:00Z")
        );
        NormalizedTransaction requestA = dexOrderRequest(
                REQUEST_HASH,
                Instant.parse("2026-01-01T10:00:00Z"),
                "0xcorrelation-a"
        );
        NormalizedTransaction requestB = dexOrderRequest(
                "0xotherrequesthash00000000000000000000000000000000000000000000000001",
                Instant.parse("2026-01-01T10:01:00Z"),
                "0xcorrelation-b"
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of(requestA, requestB));

        int linked = service.linkOutstandingSettlements(25);

        assertThat(linked).isZero();
        verify(normalizedTransactionRepository, never()).save(any());
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    private static RawTransaction ethFlowRequestRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash(REQUEST_HASH);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec")
                .append("methodId", "0x322bba21")
                .append("input", cowEthFlowCreateOrderInput())
                .append("value", "27638811423349461"));
        return rawTransaction;
    }

    private static String cowEthFlowCreateOrderInput() {
        return "0x322bba21"
                + paddedAddress("0x5979d7b546e38e414f7e9822514be443a4800529")
                + paddedAddress(WALLET)
                + paddedUint("27638811423349461")
                + paddedUint("22628189600680790")
                + paddedBytes32("0xd7e27923e5a18d36851057738a3970e4ddd2905e85b5fc19436a160647863fff")
                + paddedUint("0")
                + paddedUint("1760524229")
                + paddedBool(false)
                + paddedUint("58228845");
    }

    private static String paddedAddress(String address) {
        String normalized = address.toLowerCase().replace("0x", "");
        return "000000000000000000000000" + normalized;
    }

    private static String paddedUint(String value) {
        return String.format("%064x", new java.math.BigInteger(value));
    }

    private static String paddedBytes32(String value) {
        return value.toLowerCase().replace("0x", "");
    }

    private static String paddedBool(boolean value) {
        return value ? paddedUint("1") : paddedUint("0");
    }

    private NormalizedTransaction dexOrderRequest(String txHash, Instant timestamp, String correlationId) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":ARBITRUM:" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setType(NormalizedTransactionType.DEX_ORDER_REQUEST);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setProtocolName(CowSwapSupport.PROTOCOL_NAME);
        transaction.setProtocolVersion(CowSwapSupport.ETH_FLOW_VERSION);
        transaction.setCorrelationId(correlationId);
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(1);
        return transaction;
    }

    private NormalizedTransaction externalSettlementIn(String txHash, Instant timestamp) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":ARBITRUM:" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(9);
        transaction.setCounterpartyAddress(CowSwapSupport.GPV2_SETTLEMENT);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("wstETH");
        flow.setQuantityDelta(new BigDecimal("0.022742145033450122"));
        flow.setRole(NormalizedLegRole.BUY);
        flow.setUnitPriceUsd(new BigDecimal("2500"));
        transaction.setFlows(List.of(flow));
        return transaction;
    }
}
