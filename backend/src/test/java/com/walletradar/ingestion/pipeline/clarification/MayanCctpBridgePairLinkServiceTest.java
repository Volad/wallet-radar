package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MayanCctpBridgePairLinkServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Mock
    private MayanStatusGateway mayanStatusGateway;
    @Mock
    private PendingMayanBridgeSourceQueryService pendingMayanBridgeSourceQueryService;
    @Mock
    private MayanReceivingTransactionDiscoveryService mayanReceivingTransactionDiscoveryService;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private MayanCctpBridgePairLinkService service;

    @BeforeEach
    void setUp() {
        service = new MayanCctpBridgePairLinkService(
                mayanStatusGateway,
                pendingMayanBridgeSourceQueryService,
                mayanReceivingTransactionDiscoveryService,
                new RawTransactionClarificationEnricher(),
                rawTransactionRepository,
                normalizedTransactionRepository
        );
        lenient().when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("official Mayan source confirmation links existing redeemWithFee destination and enables continuity despite fee-bearing quantity drift")
    void officialMayanSourceConfirmationLinksExistingRedeemWithFeeDestinationAndEnablesContinuityDespiteFeeBearingQuantityDrift() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-3.139239"
        );
        NormalizedTransaction destination = externalTransferIn(
                "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                NetworkId.AVALANCHE,
                "USDC",
                "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e",
                "3.107299"
        );

        when(mayanStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new MayanBridgeStatus(
                        source.getTxHash(),
                        destination.getTxHash(),
                        NetworkId.AVALANCHE,
                        WALLET,
                        "MCTP_BRIDGE",
                        "REDEEMED_ON_EVM_WITH_FEE",
                        "COMPLETED",
                        "3.139239",
                        "3.107299",
                        "0.03194",
                        "0"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                destination.getTxHash(),
                NetworkId.AVALANCHE,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(destination));

        service.link(sourceRaw, source);

        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getClarificationEvidence().get("mayanStatus", Document.class))
                .containsEntry("provider", "MAYAN")
                .containsEntry("receivingTxHash", destination.getTxHash())
                .containsEntry("receivingNetworkId", NetworkId.AVALANCHE.name())
                .containsEntry("destinationWalletAddress", WALLET)
                .containsEntry("service", "MCTP_BRIDGE")
                .containsEntry("redeemRelayerFee", "0.03194");

        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());

        assertThat(source.getMatchedCounterparty()).isEqualTo(destination.getTxHash());
        assertThat(source.getCorrelationId()).isEqualTo("bridge:mayan:" + source.getTxHash());
        assertThat(source.getContinuityCandidate()).isTrue();

        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(destination.getContinuityCandidate()).isTrue();
        assertThat(destination.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("non-terminal Mayan status does not materialize a bridge pair")
    void nonTerminalMayanStatusDoesNotMaterializeBridgePair() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-3.139239"
        );

        when(mayanStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new MayanBridgeStatus(
                        source.getTxHash(),
                        "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                        NetworkId.AVALANCHE,
                        WALLET,
                        "MCTP_BRIDGE",
                        "ATTESTING",
                        "PENDING",
                        "3.139239",
                        "3.107299",
                        "0.03194",
                        "0"
                )));

        service.link(sourceRaw, source);

        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getCorrelationId()).isNull();
        assertThat(source.getMatchedCounterparty()).isNull();
    }

    @Test
    @DisplayName("terminal Mayan status without materialized destination keeps source outstanding for later retry")
    void terminalMayanStatusWithoutMaterializedDestinationKeepsSourceOutstanding() {
        RawTransaction sourceRaw = sourceRawTransaction(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM
        );
        NormalizedTransaction source = bridgeOut(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-3.139239"
        );

        when(mayanStatusGateway.fetchBridgeStatus(source.getTxHash()))
                .thenReturn(Optional.of(new MayanBridgeStatus(
                        source.getTxHash(),
                        "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                        NetworkId.AVALANCHE,
                        WALLET,
                        "MCTP_BRIDGE",
                        "REDEEMED_ON_EVM_WITH_FEE",
                        "COMPLETED",
                        "3.139239",
                        "3.107299",
                        "0.03194",
                        "0"
                )));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                NetworkId.AVALANCHE,
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of());
        when(mayanReceivingTransactionDiscoveryService.findOrDiscover(any())).thenReturn(Optional.empty());

        service.link(sourceRaw, source);

        verify(rawTransactionRepository).save(sourceRaw);
        verify(normalizedTransactionRepository, never()).save(any());
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(source.getMatchedCounterparty()).isNull();
        assertThat(source.getCorrelationId()).isNull();
        assertThat(source.getContinuityCandidate()).isNull();
    }

    @Test
    @DisplayName("destination arrival links previously seeded Mayan source")
    void destinationArrivalLinksPreviouslySeededMayanSource() {
        RawTransaction destinationRaw = destinationRawTransaction(
                "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                NetworkId.AVALANCHE
        );
        NormalizedTransaction source = bridgeOut(
                "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98",
                NetworkId.ARBITRUM,
                "USDC",
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "-3.139239"
        );
        source.setMatchedCounterparty("0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467");
        source.setCorrelationId("bridge:mayan:" + source.getTxHash());
        source.setContinuityCandidate(false);

        NormalizedTransaction destination = externalTransferIn(
                "0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467",
                NetworkId.AVALANCHE,
                "USDC",
                "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e",
                "3.107299"
        );

        when(normalizedTransactionRepository.findAllByMatchedCounterpartyAndSource(
                destination.getTxHash(),
                NormalizedTransactionSource.ON_CHAIN
        )).thenReturn(List.of(source));

        service.link(destinationRaw, destination);

        verify(mayanStatusGateway, never()).fetchBridgeStatus(any());
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(source.getTxHash(), destination.getTxHash());
        assertThat(destination.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(destination.getMatchedCounterparty()).isEqualTo(source.getTxHash());
        assertThat(destination.getCorrelationId()).isEqualTo(source.getCorrelationId());
        assertThat(source.getContinuityCandidate()).isTrue();
        assertThat(destination.getContinuityCandidate()).isTrue();
    }

    private RawTransaction sourceRawTransaction(String txHash, NetworkId networkId) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId.name() + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document("methodId", "0x30c48952")
                .append("functionName", "swapAndStartBridgeTokensViaMayan(tuple _bridgeData,tuple[] _swapData,tuple _mayanData)")
                .append("explorer", new Document("tokenTransfers", List.of())));
        return rawTransaction;
    }

    private RawTransaction destinationRawTransaction(String txHash, NetworkId networkId) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":" + networkId.name() + ":" + WALLET);
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(networkId.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document("methodId", "0xe2de2a03")
                .append("functionName", "redeemWithFee(bytes cctpMsg,bytes cctpSigs,bytes encodedVm,tuple bridgeParams)")
                .append("explorer", new Document("tokenTransfers", List.of())));
        return rawTransaction;
    }

    private NormalizedTransaction bridgeOut(
            String txHash,
            NetworkId networkId,
            String symbol,
            String contract,
            String qty
    ) {
        NormalizedTransaction transaction = base(txHash, networkId, NormalizedTransactionType.BRIDGE_OUT);
        transaction.setProtocolName("Mayan");
        transaction.setFlows(List.of(flow(symbol, contract, qty)));
        return transaction;
    }

    private NormalizedTransaction externalTransferIn(
            String txHash,
            NetworkId networkId,
            String symbol,
            String contract,
            String qty
    ) {
        NormalizedTransaction transaction = base(txHash, networkId, NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setFlows(List.of(flow(symbol, contract, qty)));
        transaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        transaction.setConfidence(ConfidenceLevel.LOW);
        return transaction;
    }

    private NormalizedTransaction base(String txHash, NetworkId networkId, NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(txHash + ":" + networkId + ":" + WALLET);
        transaction.setTxHash(txHash);
        transaction.setNetworkId(networkId);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(type);
        transaction.setBlockTimestamp(Instant.parse("2026-04-01T00:00:00Z"));
        transaction.setTransactionIndex(1);
        return transaction;
    }

    private NormalizedTransaction.Flow flow(String symbol, String contract, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
